package cn.hospital.region.archive.redelivery;

import cn.hospital.region.archive.ledger.ConcurrentLedgerUpdateException;
import cn.hospital.region.archive.ledger.IdempotencyKey;
import cn.hospital.region.archive.ledger.IllegalStateTransitionException;
import cn.hospital.region.archive.ledger.LedgerReadView;
import cn.hospital.region.archive.ledger.LedgerRow;
import cn.hospital.region.archive.ledger.LedgerStateConflictException;
import cn.hospital.region.archive.ledger.LedgerStore;
import cn.hospital.region.archive.ledger.MessageState;

import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 重投闭环协调器 —— 把「对账差集」与「重投队列」接到<b>台账状态机</b>上，
 * 使这条链真正闭成环：
 *
 * <pre>
 *   逐条 MSA 回执 → 对端回执页（HTTP GET，T34）
 *     → AckReconciler.diff        差集：哪几条对端没记下
 *     → queue.offer               入队（队列业务闸：AR / 已落地 在这里被拒）
 *     → 台账 待确认 → 待重投      本类推进
 *     → RedeliveryScheduler.tick  出队 → 重投（{@link RedeliveryAction}）
 *     → 台账 待重投 → 重投中      本类在动作<b>之前</b>推进
 *     → 对端回 AA
 *     → 台账 重投中 → 已确认      本类在成功钩子里推进
 * </pre>
 *
 * <h2>为什么台账侧的状态推进要挂在这里而不是分散在各处</h2>
 * <p>{@link RedeliveryQueue} 只管队列表（入队 / 出队 / 结算），{@link RedeliveryScheduler}
 * 只管调度（claim → 执行 → 结算），两者<b>都不写台账</b>——这是 T33 有意留的边界。
 * 缺了本类就会出现一个很常见的坏状态：队列把条目标成「已结算」，台账里的行还停在
 * {@code 待确认}，次日对账时两边对不上，且没有任何一行审计能解释这次推进是谁做的。
 * 因此本类保证<b>每一次台账推进都经过 {@code store.transition}</b>（唯一写入口，
 * 自动带一条 {@code message_audit} 行），并给出明确的原因短语。
 *
 * <h2>幂等：重复对账不会把报文推两遍</h2>
 * <p>{@link #reconcile(String, RemoteAckPage)} 连跑两次的结论一致：
 * 队列表的唯一约束让第二次 offer 变成「已存在」，台账侧读到行已在重投路径上就<b>跳过</b>，
 * 既不重复入队也不重复推进状态。
 *
 * <h2>已落地的条目不会被重投</h2>
 * <p>信息员可能在排队期间就把这条确认落地了。此时 {@link #tickAt(long)} 不会去推报文，
 * 而是按「已不在重投路径」收敛为永久失败并留下原因——重投一条已落地的报文，
 * 对端会多收一份居民档案，换不来任何东西。
 */
public final class RedeliveryCoordinator {

    private static final Logger LOG = Logger.getLogger(RedeliveryCoordinator.class.getName());

    /** 自动迁移的操作人：人工动作才有具体的人（来自 token subject）。 */
    public static final String SYSTEM_OPERATOR = "system";

    /** 入队后推进到待重投的原因短语。 */
    public static final String REASON_ENQUEUED = "redelivery-enqueued:no-ack-next-day";

    /** 出队执行前推进到重投中的原因短语。 */
    public static final String REASON_RETRYING = "redelivery-start";

    /** 对端回 AA 后推进到已确认的原因前缀（后接对端说明）。 */
    public static final String REASON_RETRY_CONFIRMED = "redelivery-acked:";

    /** 永久失败的原因前缀（后接队列给的 last_error）。 */
    public static final String REASON_RETRY_PERMANENT_FAILED = "redelivery-failed:";

    /** 可重试失败重新排期后回到待重投的原因前缀（后接尝试次数）。 */
    public static final String REASON_RETRY_RESCHEDULED = "redelivery-rescheduled:attempt=";

    /** 人工放行回队列的原因前缀。 */
    public static final String REASON_MANUAL_RELEASE = "redelivery-manual-release:";

    /** 队列状态里哪些属于「还在重投路径上」。 */
    private static final List<MessageState> RETRY_PATH =
            List.of(MessageState.PENDING_RETRY, MessageState.RETRYING,
                    MessageState.RETRY_PERMANENT_FAILED);

    /** 一次对账的完整结论。 */
    public record ReconcileOutcome(AckDiff diff,
                                   AckReconciler.EnqueueSummary enqueue,
                                   List<String> ledgerAdvancements,
                                   List<String> ledgerSkips) {

        public ReconcileOutcome {
            ledgerAdvancements = List.copyOf(ledgerAdvancements);
            ledgerSkips = List.copyOf(ledgerSkips);
        }

        /** 对账口径自洽：缺失条数 = 入队 + 已存在 + 队列闸门拒绝。 */
        public boolean balanced() {
            return enqueue.balanced()
                    && diff.expectedCount() == diff.matchedCount() + diff.missingCount()
                    && diff.missingCount() == enqueue.offered() + enqueue.duplicated()
                            + enqueue.rejectedReasons().size();
        }

        /** 单行摘要，进日志与 HTTP 响应（只有主键与计数，无患者标识）。 */
        public String toLogLine() {
            return diff.toLogLine() + " | " + enqueue.toLogLine()
                    + " | 台账推进=" + ledgerAdvancements.size()
                    + " 台账跳过=" + ledgerSkips.size()
                    + (balanced() ? "" : " ⚠口径不自洽");
        }
    }

    private final LedgerStore store;
    private final LedgerReadView readView;
    private final RedeliveryQueue queue;
    private final RedeliveryScheduler scheduler;

    /** 幂等键 → 本端 MSA 码（通常取自回执账页）；台账不存回执码，只能由调用方补。 */
    private final Function<IdempotencyKey, String> ackCodeLookup;

    /**
     * 完整装配。
     *
     * @param store         台账写入口（唯一能改 state 的口子）
     * @param readView      台账只读面（对账左侧取「本批应发条目」）
     * @param queue         重投队列（T32/T35）
     * @param action        重投执行动作（生产为 {@link MllpRedeliveryAction}）
     * @param backoff       退避策略（T33）
     * @param maxAttempts   最大尝试次数（含首次）
     * @param clock         时钟（入队时刻、退避基准）
     * @param ackCodeLookup 幂等键 → 本端 MSA 码（通常取自回执账页；查不到传空串）
     */
    public RedeliveryCoordinator(LedgerStore store, LedgerReadView readView, RedeliveryQueue queue,
                                 RedeliveryAction action, BackoffPolicy backoff, int maxAttempts,
                                 Clock clock, Function<IdempotencyKey, String> ackCodeLookup) {
        this.store = Objects.requireNonNull(store, "台账存储不得为 null");
        this.readView = Objects.requireNonNull(readView, "台账只读面不得为 null");
        this.queue = Objects.requireNonNull(queue, "重投队列不得为 null");
        Objects.requireNonNull(action, "重投动作不得为 null");
        this.clock = Objects.requireNonNull(clock, "时钟不得为 null");
        this.ackCodeLookup = Objects.requireNonNull(ackCodeLookup, "回执码查询不得为 null");
        this.scheduler = new RedeliveryScheduler(queue, wrapWithLedger(action), backoff,
                maxAttempts, clock, new LedgerHooks());
    }

    private final Clock clock;

    // =====================================================================
    // 对账：差集 → 入队 → 台账推进到待重投
    // =====================================================================

    /**
     * 跑一次对账：算出缺失清单、提交重投队列，并把入队的条目在台账推进到「待重投」。
     *
     * <p>可重复调用：第二次调用不会重复入队（队列唯一约束），也不会重复推进台账状态。
     *
     * @param batchId 本批批次号
     * @param page    对端回执页（由 {@link BatchReconcilerClient} 真实 HTTP GET 取得）
     */
    public ReconcileOutcome reconcile(String batchId, RemoteAckPage page) throws SQLException {
        Objects.requireNonNull(batchId, "批次号不得为 null");
        List<LocalExpectedAck> expected =
                AckReconciler.expectedFromLedger(readView, batchId, null, ackCodeLookup);
        AckDiff diff = AckReconciler.diff(expected, page);
        AckReconciler.EnqueueSummary summary =
                AckReconciler.enqueueMissing(diff, queue, clock.millis());

        List<String> advanced = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (AckDiff.MissingAck missing : diff.missing()) {
            LocalExpectedAck local = missing.local();
            if (queue.listByLedger(local.ledgerId()).isEmpty()) {
                // 队列闸门拒了（AR / 已落地 / 状态非法）：台账侧一个字节都不动
                skipped.add(local.key().controlId() + ":队列闸门未收，台账保持原态");
                continue;
            }
            String note = advanceToPendingRetry(local);
            if (note == null) {
                advanced.add(local.key().controlId());
            } else {
                skipped.add(local.key().controlId() + ":" + note);
            }
        }
        ReconcileOutcome outcome = new ReconcileOutcome(diff, summary, advanced, skipped);
        LOG.log(Level.INFO, "对账闭环: {0}", outcome.toLogLine());
        return outcome;
    }

    /**
     * 台账侧 待确认 → 待重投。
     *
     * @return {@code null} 表示已推进；非空表示跳过原因（已在重投路径 / 状态冲突等）
     */
    private String advanceToPendingRetry(LocalExpectedAck local) throws SQLException {
        LedgerRow row = store.findById(local.ledgerId()).orElse(null);
        if (row == null) {
            return "台账行已不存在";
        }
        if (RETRY_PATH.contains(row.state())) {
            return "已在重投路径(" + row.state().dbValue() + ")，不重复推进";
        }
        if (row.state() != MessageState.PENDING_CONFIRM) {
            // 已落地 / 已拒收：这类条目不该重投，队列闸门本该先拒；真到这里说明两侧口径不一致
            return "当前态为 " + row.state().dbValue() + "，不在待确认上，不推进";
        }
        try {
            store.transition(row.id(), MessageState.PENDING_CONFIRM, MessageState.PENDING_RETRY,
                    row.version(), SYSTEM_OPERATOR, REASON_ENQUEUED);
            return null;
        } catch (IllegalStateTransitionException | LedgerStateConflictException
                 | ConcurrentLedgerUpdateException e) {
            LOG.log(Level.WARNING, "待确认 → 待重投 未推进: ledgerId={0} 原因={1}",
                    new Object[]{row.id(), e.getMessage()});
            return "推进被拒：" + e.getClass().getSimpleName();
        }
    }

    // =====================================================================
    // 调度：出队 → 重投 → 回执 → 台账推进
    // =====================================================================

    /** 跑一次调度（一条）。生产侧在循环里调。 */
    public Optional<RedeliveryEntry> tick() {
        return scheduler.tick();
    }

    /** 与 {@link #tick()} 等价，但允许测试注入时刻。 */
    public Optional<RedeliveryEntry> tickAt(long nowMs) {
        return scheduler.tickAt(nowMs);
    }

    /** 人工放行（把永久失败的条目放回队列，并把台账推回待重投）。 */
    public RedeliveryEntry manualRelease(long queueId, long ledgerId, long nowMs, String operator) {
        return scheduler.manualRelease(queueId, ledgerId, nowMs, operator);
    }

    /**
     * 包一层动作：<b>执行之前</b>先把台账推进到「重投中」。
     *
     * <p>顺序不能反：先记「正在重投」再发报文，出问题时台账里留下一条
     * {@code 待重投 → 重投中} 的审计，说明这次已经发出去了；若先发报文后记账，
     * 进程在中间崩掉就会留下一条永远停在待重投、实际已经投过的行。
     *
     * <p>顺带处理一种真实情形：排队期间信息员已经把它确认落地了。此时<b>不重投</b>，
     * 直接给永久失败结论并写明当前态——重投一条已落地的报文只会让对端多收一份档案。
     */
    private RedeliveryAction wrapWithLedger(RedeliveryAction delegate) {
        return entry -> {
            LedgerRow row = store.findById(entry.ledgerId()).orElse(null);
            if (row == null) {
                return RedeliveryResult.permanentFailure("LEDGER_ROW_MISSING",
                        "台账行已不存在: ledgerId=" + entry.ledgerId());
            }
            if (row.state() == MessageState.PENDING_RETRY) {
                try {
                    store.transition(row.id(), MessageState.PENDING_RETRY, MessageState.RETRYING,
                            row.version(), SYSTEM_OPERATOR, REASON_RETRYING);
                } catch (SQLException | IllegalStateTransitionException | LedgerStateConflictException
                         | ConcurrentLedgerUpdateException e) {
                    // 有人在这一瞬间把这条推走了（多半是并发的人工动作）：本轮不投，退避后重来
                    return RedeliveryResult.retryableFailure("LEDGER_START_RETRY_CONFLICT",
                            "推进 待重投→重投中 被拒: " + e.getClass().getSimpleName());
                }
            } else if (row.state() != MessageState.RETRYING) {
                return RedeliveryResult.permanentFailure("LEDGER_NOT_IN_RETRY_PATH",
                        "该条已不在重投路径（当前态 " + row.state().dbValue() + "），不重投");
            }
            return delegate.perform(entry);
        };
    }

    /** 把队列侧的结算结果推到台账侧的回调。 */
    private final class LedgerHooks implements RedeliveryScheduler.Hooks {

        @Override
        public void onSuccess(RedeliveryEntry entry, int attempt) throws SQLException {
            advance(entry.ledgerId(), MessageState.RETRYING, MessageState.RETRY_CONFIRMED,
                    REASON_RETRY_CONFIRMED + "attempt=" + attempt);
        }

        @Override
        public void onPermanentFailure(RedeliveryEntry entry, int attempt, String lastError)
                throws SQLException {
            advance(entry.ledgerId(), MessageState.RETRYING, MessageState.RETRY_PERMANENT_FAILED,
                    REASON_RETRY_PERMANENT_FAILED + firstLine(lastError));
        }

        @Override
        public void onRetryScheduled(RedeliveryEntry entry, int nextAttempt, long nextAttemptAtMs)
                throws SQLException {
            advance(entry.ledgerId(), MessageState.RETRYING, MessageState.PENDING_RETRY,
                    REASON_RETRY_RESCHEDULED + nextAttempt + ",nextAtMs=" + nextAttemptAtMs);
        }

        @Override
        public void onManualRelease(RedeliveryEntry entry, String operator) throws SQLException {
            advance(entry.ledgerId(), MessageState.RETRY_PERMANENT_FAILED,
                    MessageState.PENDING_RETRY, REASON_MANUAL_RELEASE + operator);
        }

        /**
         * 推进台账状态并吞掉「来源态已变」。
         *
         * <p>吞掉不是静默：状态不符时打 WARNING 并原样保留对方给的当前态。
         * 队列已经结算，硬失败会把队列与台账彻底劈成两半（一边说成功、一边说没动过），
         * 那比「台账慢一步」更难查。真正要人工处置的是队列侧的红黑轮转，本类不碰。
         */
        private void advance(long ledgerId, MessageState from, MessageState to, String reason) {
            try {
                LedgerRow row = store.findById(ledgerId).orElse(null);
                if (row == null) {
                    LOG.log(Level.WARNING, "重投回调时台账行已不存在: ledgerId={0} {1}→{2}",
                            new Object[]{ledgerId, from.dbValue(), to.dbValue()});
                    return;
                }
                if (row.state() == to) {
                    return;
                }
                if (row.state() != from) {
                    LOG.log(Level.WARNING, "重投回调的来源态不符，台账保持 {0}（期望 {1}→{2}）: ledgerId={3}",
                            new Object[]{row.state().dbValue(), from.dbValue(), to.dbValue(), ledgerId});
                    return;
                }
                store.transition(row.id(), from, to, row.version(), SYSTEM_OPERATOR, reason);
            } catch (SQLException | IllegalStateTransitionException | LedgerStateConflictException
                     | ConcurrentLedgerUpdateException e) {
                LOG.log(Level.SEVERE, "重投回调推进台账失败: ledgerId={0} {1}→{2} 原因={3}",
                        new Object[]{ledgerId, from.dbValue(), to.dbValue(), e.getMessage()});
            }
        }
    }

    private static String firstLine(String value) {
        if (value == null || value.isBlank()) {
            return "(无描述)";
        }
        String oneLine = value.replaceAll("\\s+", " ").trim();
        return oneLine.length() > 180 ? oneLine.substring(0, 180) : oneLine;
    }
}