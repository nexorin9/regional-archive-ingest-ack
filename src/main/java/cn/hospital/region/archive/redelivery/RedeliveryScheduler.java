package cn.hospital.region.archive.redelivery;

import java.sql.SQLException;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 重投调度器 —— {@link RedeliveryQueue} 之上的<b>消费侧</b>控制循环。
 *
 * <h2>职责边界</h2>
 * <ul>
 *   <li>本类<b>只</b>调度：claim → 执行 → 结算/重排/放回。每一步都对应一条队列表 SQL；
 *       不读报文原文、不写台账、不发 HTTP。</li>
 *   <li>台账侧的 {@code RETRYING → RETRY_CONFIRMED/PERMANENT_FAILED} 迁移与
 *       {@code ConfirmGate} 走 {@link Hooks}，由产品在装配时把
 *       {@link cn.hospital.region.archive.ledger.ArchiveIngestService} 的回调
 *       接进来 —— 调度器不直接持 ledger 依赖，避免双向耦合。</li>
 *   <li>{@link RedeliveryAction} 是产品侧的执行接口；测试侧随意 stub。</li>
 * </ul>
 *
 * <h2>退避与上限（task.json 第 1..3 条 step）</h2>
 * <ol>
 *   <li>成功的重投 → {@code queue.settle(ACK_CONFIRMED)}；同时回调 {@link Hooks#onSuccess} 推台账。</li>
 *   <li>不可重试失败 → 立即 {@code queue.settle(PERMANENT_FAILED)}；走 {@link Hooks#onPermanentFailure}。</li>
 *   <li>可重试失败且 {@code entry.attempt() + 1 < maxAttempts} →
 *       {@code reschedule}（写新 attempt 与 nextAttemptAtMs）+ {@code releaseToQueued}（把行放回队列）。</li>
 *   <li>可重试失败且 {@code entry.attempt() + 1 >= maxAttempts} →
 *       {@code queue.settle(PERMANENT_FAILED)}；同上钩子。</li>
 * </ol>
 *
 * <h2>人工放行</h2>
 * <p>{@link #manualRelease(long, long, String)} 把 {@link RedeliveryStatus#PERMANENT_FAILED}
 * 行放回 {@link RedeliveryStatus#QUEUED}，同时调用 {@link Hooks#onManualRelease} 推台账侧的
 * {@code RETRY_PERMANENT_FAILED → PENDING_RETRY}（T12 状态机允许的边）。
 *
 * <h2>为什么 {@code tick()} 是单步而不是循环</h2>
 * <p>测试需要确定性：跑一次 {@code tick} 看一条断言、推进时钟再跑一次……的节奏。
 * 生产侧再叠一个 {@link Thread#sleep(long) sleep}+{@code tick()} 循环，调度间隔从环境变量读。
 * 一个方法管一件事，方便钉测试桩。
 */
public final class RedeliveryScheduler {

    private static final Logger LOG = Logger.getLogger(RedeliveryScheduler.class.getName());

    /**
     * 调度器外部副作用钩子 —— 把「队列层结算」与「台账层迁移」分到两个层级，
     * 调度器只持队列依赖，避免 {@link cn.hospital.region.archive.ledger.ArchiveIngestService}
     * 反向依赖 {@link RedeliveryQueue}。
     */
    public interface Hooks {
        /**
         * 队列成功结算（{@code CONSUMED → ACK_CONFIRMED}）后调用。
         * 默认实现：把台账侧的对应行从 {@code RETRYING} 推至
         * {@code RETRY_CONFIRMED}（T12 状态机合法边），最后由 {@code ArchiveIngestService}
         * 的确认门推至 {@code LANDED}。
         */
        void onSuccess(RedeliveryEntry entry, int attempt) throws SQLException;

        /**
         * 队列永久失败（{@code CONSUMED → PERMANENT_FAILED}）后调用。
         * 默认实现：把台账侧的对应行从 {@code RETRYING} 推至
         * {@code RETRY_PERMANENT_FAILED}（T12 状态机合法边）。
         */
        void onPermanentFailure(RedeliveryEntry entry, int attempt, String lastError) throws SQLException;

        /**
         * 人工放行（队列 {@code PERMANENT_FAILED → QUEUED}）后调用。
         * 默认实现：把台账侧的对应行从 {@code RETRY_PERMANENT_FAILED} 推至
         * {@code PENDING_RETRY}（T12 状态机合法边）。
         *
         * @param operator 操作人（不可空；非空校验已在 manualRelease 前置）
         */
        void onManualRelease(RedeliveryEntry entry, String operator) throws SQLException;

        /**
         * 可重试失败且未超上限、条目已重新排期放回队列后调用（T36 闭环验收时补上的默认钩子）。
         *
         * <p><b>为什么必须是钩子而不是实现方自己管</b>：队列把条目放回 {@code QUEUED} 时，
         * 台账侧对应的行若还停在 {@code RETRYING}，两边就此劈叉——队列说「等着再投」、
         * 台账说「正在投」，重启续跑（T35）扫到的却是另一个状态。默认实现为 no-op：
         * 只关心队列层的单测（T32/T33）不需要它，而装配方
         * （{@link RedeliveryCoordinator}）会把它接成 {@code RETRYING → PENDING_RETRY}。
         */
        default void onRetryScheduled(RedeliveryEntry entry, int nextAttempt, long nextAttemptAtMs)
                throws SQLException {
        }
    }

    /** 测试可注入；生产默认把所有钩子替换为 no-op，方便单测只关注队列层。 */
    public static final Hooks NOOP_HOOKS = new Hooks() {
        @Override public void onSuccess(RedeliveryEntry e, int a) { }
        @Override public void onPermanentFailure(RedeliveryEntry e, int a, String le) { }
        @Override public void onManualRelease(RedeliveryEntry e, String o) { }
    };

    private final RedeliveryQueue queue;
    private final RedeliveryAction action;
    private final BackoffPolicy backoff;
    private final int maxAttempts;
    private final Clock clock;
    private final Hooks hooks;

    public RedeliveryScheduler(RedeliveryQueue queue, RedeliveryAction action,
                               BackoffPolicy backoff, int maxAttempts, Clock clock,
                               Hooks hooks) {
        this.queue = Objects.requireNonNull(queue, "queue 不得为 null");
        this.action = Objects.requireNonNull(action, "action 不得为 null");
        this.backoff = Objects.requireNonNull(backoff, "退避策略不得为 null");
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts 必须 ≥ 1，实际=" + maxAttempts);
        }
        this.maxAttempts = maxAttempts;
        this.clock = Objects.requireNonNull(clock, "时钟不得为 null");
        this.hooks = hooks == null ? NOOP_HOOKS : hooks;
    }

    /** 退避策略观察口（测试想直接看「下一个时间」） —— 给 RedeliverySchedulerTest 留口子。 */
    public BackoffPolicy backoff() {
        return backoff;
    }

    /** 最大尝试次数（含首次），调度器可观察以便排障。 */
    public int maxAttempts() {
        return maxAttempts;
    }

    /**
     * 跑一次调度：claim 一条到期条目、执行、按结论结算/重排。生产侧在循环里调。
     *
     * @return 拿到的条目（{@code Optional.empty()} = 队列空或没到期）
     */
    public Optional<RedeliveryEntry> tick() {
        long nowMs = clock.millis();
        Optional<RedeliveryEntry> claimed = claimSafely(nowMs);
        if (claimed.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(processOne(claimed.get(), nowMs));
    }

    /** 与 {@link #tick()} 等价，但允许测试用注入时钟。 */
    public Optional<RedeliveryEntry> tickAt(long nowMs) {
        Optional<RedeliveryEntry> claimed = claimSafely(nowMs);
        if (claimed.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(processOne(claimed.get(), nowMs));
    }

    private Optional<RedeliveryEntry> claimSafely(long nowMs) {
        try {
            return queue.claimNextDue(nowMs);
        } catch (SQLException e) {
            LOG.log(Level.SEVERE, "claimNextDue 时数据库异常，调度跳过本轮: {0}", e.toString());
            return Optional.empty();
        }
    }

    private RedeliveryEntry processOne(RedeliveryEntry entry, long nowMs) {
        RedeliveryResult result = invokeActionSafely(entry);
        int nextAttempt = entry.attempt() + 1;

        if (result.success()) {
            settleSuccess(entry, nextAttempt, result, nowMs);
            fireHooksSuccess(entry, nextAttempt);
        } else if (!result.retryable() || nextAttempt >= maxAttempts) {
            settlePermanentFailure(entry, nextAttempt, result, nowMs);
            fireHooksPermanentFailure(entry, nextAttempt, result);
        } else {
            rescheduleAndRelease(entry, nextAttempt, nowMs, result);
        }
        return entry;
    }

    private RedeliveryResult invokeActionSafely(RedeliveryEntry entry) {
        try {
            RedeliveryResult r = action.perform(entry);
            if (r == null) {
                return RedeliveryResult.permanentFailure("NULL_RESULT",
                        "action 返回 null —— 视为确定性失败");
            }
            return r;
        } catch (Throwable t) {
            // 任何异常归为不可重试失败：与 SUCCESS 或 retryable 一律区别开。
            // 由调度器把这条置 PERMANENT_FAILED，由调度器的人工放行接口回收。
            String msg = t.getClass().getSimpleName() + ": " + safeMessage(t);
            return RedeliveryResult.permanentFailure("UNCAUGHT_EXCEPTION", msg);
        }
    }

    private static String safeMessage(Throwable t) {
        return safeText(t.getMessage());
    }

    /** 折成一行并截断，避免多行异常消息把 last_error 撑破或把队列行搞乱。 */
    private static String safeText(String raw) {
        if (raw == null) {
            return "";
        }
        String oneLine = raw.replaceAll("\\s+", " ").trim();
        return oneLine.length() > 200 ? oneLine.substring(0, 200) : oneLine;
    }

    private void settleSuccess(RedeliveryEntry entry, int attempt, RedeliveryResult result, long nowMs) {
        try {
            queue.settle(entry.id(), RedeliveryStatus.ACK_CONFIRMED, attempt,
                    "对端回 AA: " + result.lastError(), nowMs);
        } catch (SQLException e) {
            LOG.log(Level.SEVERE, "settle ACK_CONFIRMED 失败，红黑轮转必须报警: queueId={0} 原因={1}",
                    new Object[]{entry.id(), e.toString()});
        }
    }

    private void settlePermanentFailure(RedeliveryEntry entry, int attempt, RedeliveryResult result, long nowMs) {
        try {
            String reason = result.retryable()
                    ? "达到 MAX_ATTEMPTS=" + maxAttempts + ", lastError=" + result.errorCode()
                    : "不可重试失败: " + result.errorCode();
            queue.settle(entry.id(), RedeliveryStatus.PERMANENT_FAILED, attempt,
                    reason, nowMs);
        } catch (SQLException e) {
            LOG.log(Level.SEVERE, "settle PERMANENT_FAILED 失败，红黑轮转必须报警: queueId={0} 原因={1}",
                    new Object[]{entry.id(), e.toString()});
        }
    }

    private void rescheduleAndRelease(RedeliveryEntry entry, int nextAttempt, long nowMs,
                                      RedeliveryResult result) {
        long nextAttemptAtMs = backoff.nextAttemptAtMs(nowMs, nextAttempt);
        try {
            // 失败原因随重排一起落到 last_error：条目退回 QUEUED 之后仍要能回答
            // 「上一次为什么失败」，否则值班人员看到的是一条说不出原因的排队记录。
            queue.reschedule(entry.id(), nextAttempt, nextAttemptAtMs,
                    "退避重投: " + result.errorCode() + " - " + safeText(result.lastError()));
            queue.releaseToQueued(entry.id(), nextAttemptAtMs);
        } catch (SQLException e) {
            LOG.log(Level.SEVERE, "reschedule/releaseToQueued 失败，该条目停在 CONSUMED 必须人工处置: "
                    + "queueId={0} 原因={1}", new Object[]{entry.id(), e.toString()});
            return;
        }
        try {
            // 队列已放回 QUEUED，台账侧必须跟着退回 PENDING_RETRY：否则重启续跑（T35）
            // 扫到的是 RETRYING，而队列说这条还没到点 —— 两边从此对不上。
            hooks.onRetryScheduled(entry, nextAttempt, nextAttemptAtMs);
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "重排期后 ledger 侧钩子抛错（队列已放回，台账仍停在重投中）: "
                    + "ledgerId={0} 原因={1}", new Object[]{entry.ledgerId(), t.toString()});
        }
    }

    private void fireHooksSuccess(RedeliveryEntry entry, int attempt) {
        try {
            hooks.onSuccess(entry, attempt);
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "成功结算后 ledger 侧钩子抛错（队列已 ACK_CONFIRMED，账仍写）: "
                    + "ledgerId={0} 原因={1}", new Object[]{entry.ledgerId(), t.toString()});
        }
    }

    private void fireHooksPermanentFailure(RedeliveryEntry entry, int attempt, RedeliveryResult result) {
        try {
            hooks.onPermanentFailure(entry, attempt, result.lastError());
        } catch (Throwable t) {
            LOG.log(Level.SEVERE, "永久失败结算后 ledger 侧钩子抛错（账没写但队列已 PERMANENT_FAILED）: "
                    + "ledgerId={0} 原因={1}", new Object[]{entry.ledgerId(), t.toString()});
        }
    }

    /**
     * 人工放行（已知 ledgerId 版）。这里直接读回，可避免扫表。
     *
     * <p>{@code ledgerId} 在主路径上几乎总是已知（来自 {@code RedeliveryEntry.ledgerId()}）；
     * 唯一不知的场景是运维从 CLI 反查 ID 后放行，此时由 CLI 端先 SELECT 出 ledgerId 再调。
     */
    public RedeliveryEntry manualRelease(long queueId, long ledgerId, long nowMs, String operator) {
        Objects.requireNonNull(queue, "internal");
        if (queueId <= 0 || ledgerId <= 0) {
            throw new IllegalArgumentException("queueId/ledgerId 必须为正数");
        }
        if (nowMs < 0) {
            throw new IllegalArgumentException("nowMs 不得为负");
        }
        if (operator == null || operator.isBlank()) {
            throw new IllegalArgumentException("operator 不得为空");
        }

        try {
            // 人工放行专用通道：一步把 PERMANENT_FAILED → QUEUED 并把 attempt 复位 0。
            queue.manualReleaseToQueued(queueId, nowMs);
        } catch (SQLException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            throw new IllegalStateException("手动放行失败（queueId=" + queueId + "）: " + msg, e);
        }

        RedeliveryEntry released;
        try {
            released = queue.listByLedger(ledgerId).stream()
                    .filter(e -> e.id() == queueId)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "人工放行后查不到该行（queueId=" + queueId + " ledgerId=" + ledgerId + "）"));
        } catch (SQLException e) {
            throw new IllegalStateException("读回放行结果失败（queueId=" + queueId + "）: " + e.getMessage(), e);
        }

        fireHooksManualRelease(released, operator);
        return released;
    }

    private void fireHooksManualRelease(RedeliveryEntry entry, String operator) {
        try {
            hooks.onManualRelease(entry, operator);
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "人工放行后 ledger 侧钩子抛错（队列已 QUEUED，账仍写）: "
                    + "ledgerId={0} 原因={1}", new Object[]{entry.ledgerId(), t.toString()});
        }
    }

    /** 测试暴露 —— 队列与回调一对一查询辅助器。 */
    public Consumer<RedeliveryEntry> successHookForTest() {
        return e -> {
            try { hooks.onSuccess(e, e.attempt()); } catch (SQLException ignored) { }
        };
    }
}
