package cn.hospital.region.archive.redelivery;

import cn.hospital.region.archive.ledger.IdempotencyKey;
import cn.hospital.region.archive.ledger.MessageState;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * 重投队列（落库版）—— 把「重试同一操作」从源产品的瞬时重试升级为<b>可查询 / 按条触发</b>的队列。
 *
 * <h2>设计依据</h2>
 * <p>参考实现 {@code github_ref/dcm4che/dcm4che-hl7/.../MLLPConnection.java:116-131 readACK()}：
 * 收到 {@code NAK} 时抛 {@code IOException("NAK received")} 由上层捕获后<b>瞬时重试一次</b>
 * —— 源产品的「重试」是一次调用内的单次回退，<b>无队列、无台账、不可查询</b>。
 * 这条机制拿不到当日末与次日对账的接缝：本产品把「重投同一报文」变成一条<b>落库</b>的
 * 事件，调度器（{@code RedeliveryScheduler}）、对账差集（{@code BatchReconcilerClient}）、
 * 重启续跑（{@code RecoveryScan}）都从这张表读。
 *
 * <h2>入队条件（{@link #offer} 入队口唯一允许的入口）</h2>
 * <ul>
 *   <li>MSA-1 = {@code AE}（应用错误，可能下一次成功）→ 入队</li>
 *   <li>状态 = {@code PENDING_CONFIRM} 且对端次日未回执（差集触发）→ 入队</li>
 *   <li>MSA-1 = {@code AR} / {@code LANDED} / {@code RECEIVED} → <b>绝不</b>入队</li>
 * </ul>
 * AR 是确定性事实（字符集不支持 / 身份锚点冲突 / 版本不兼容），再投一万次还是同样的字节；
 * LANDED 表示报文已对账完结；RECEIVED 是一次性处理单元。这三类<b>必须</b>在 offer 入口被拒，
 * 而不是让调用方自己记。
 *
 * <h2>同 key 不重</h2>
 * <p>{@code uk_redelivery_idem_key} 唯一约束保证同一条报文不会被多次入队占行；
 * 调用方重复调用 {@code offer} 第二次返回 {@code false} 且无副作用。
 * 历史状态（ACK_CONFIRMED / PERMANENT_FAILED）不再约束：结算后的报文若被人工
 * 放行，仍可重新入队。
 *
 * <h2>出队原子性</h2>
 * <p>{@link #claimNextDue(long)} 用单条 {@code UPDATE ... SET status='CONSUMED',
 * consumed_at=? WHERE status='QUEUED' AND next_attempt_at_ms<=?} 实现原子出队，
 * 不靠 JVM 内存锁；多线程调度器下同一行只能被一个消费者拿走。
 *
 * <h2>为什么有 {@link RedeliveryStatus} 而不是直接复用 {@code MessageState}</h2>
 * <p>台账状态机记的是「这条报文在产品中的业务阶段」（四态 + 重投四态），
 * 队列状态机记的是「调度器当前拿这条条目在做什么」（入队 / 持锁 / 已结算），
 * 两套语义混在一列上是排障噩梦。
 */
public interface RedeliveryQueue {

    /**
     * 入队条件枚举 —— 表达「这一次为什么入队」语义而不只是一段字符串，
     * 便于调用方在接入主路径（MSA=AE）与对账差集（PENDING_CONFIRM 无回执）
     * 两个不同入口用同一个 offer()。
     */
    enum EnqueueTrigger {
        /** 本端判定 MSA-1=AE，可重投。 */
        MSA_AE("msa-ae"),
        /** 对端次日追问未收回执（差集触发）。 */
        NO_ACK_NEXT_DAY("pending-confirm-no-ack"),
        /** 达到上限后被人工放行回队列。 */
        MANUAL_RELEASE("manual-release"),
        /** 重启续跑扫描发现的旧条目。 */
        RECOVERY_SCAN("recovery-scan");

        private final String label;

        EnqueueTrigger(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        /** 由 label 反查；非法字符串抛异常。 */
        public static EnqueueTrigger fromLabel(String label) {
            if (label == null) {
                throw new IllegalArgumentException("EnqueueTrigger label 不得为 null");
            }
            for (EnqueueTrigger t : values()) {
                if (t.label.equals(label)) {
                    return t;
                }
            }
            throw new IllegalArgumentException("未知 EnqueueTrigger label: " + label);
        }
    }

    /**
     * 入队一条报文。
     *
     * <p><b>准入算法</b>（{@code shouldEnqueue} 的精确口径）：
     * <ol>
     *   <li>若 {@code currentState = LANDED / REJECTED / RETRY_CONFIRMED / RETRY_PERMANENT_FAILED}
     *       → 状态闸先拦，不入队</li>
     *   <li>若 {@code ackCode = "AR"} → 确定性事实，<b>永不</b>入队</li>
     *   <li>若 {@code ackCode = "AA"} → 已被受理，不入队；<b>但</b>当
     *       {@code currentState = PENDING_CONFIRM} 且 {@code trigger != MSA_AE}（即对账差异触发
     *       与两条恢复通道）时<b>放行</b>：AA 答的是「本端受理了没有」，差集问的是
     *       「对端收到回执没有」，把两者当成一件事会让次日追问一条也重投不了</li>
     *   <li>若 {@code ackCode = "AE"} → 入队（标记 {@code EnqueueTrigger.MSA_AE}）</li>
     *   <li>若 {@code currentState = PENDING_CONFIRM} 且 {@code trigger != MSA_AE}
     *       → 入队（标记 {@code EnqueueTrigger.NO_ACK_NEXT_DAY}）</li>
     *   <li>其余状态（PENDING_RETRY 等）按调度器自身流程，不走 offer</li>
     * </ol>
     *
     * @param key         报文幂等键（MSH-9 + MSH-10 + MSH-11）
     * @param ledgerId    对应 message_ledger.id（已入账的台账行主键）
     * @param batchId     本批 batch_id
     * @param ackCode     本端回执码 AA / AE / AR
     * @param currentState该报文当前台账状态
     * @param trigger     入队原因
     * @param nextAttemptAtMs 期望下一次可被出队的时刻（epoch 毫秒）；可选（默认 0 = 立即可出）
     * @return {@code OfferResult.Offered} 表示条目已落库并返回行主键；
     *         {@code OfferResult.DuplicatedKey} 表示同幂等键已存在，<b>未</b>新增；
     *         {@code OfferResult.Rejected} 表示按准入规则应被拒（AR / AA / LANDED / 状态非法），
     *         <b>未</b>新增。调用方据需选择继续走原态还是丢弃。
     */
    OfferResult offer(IdempotencyKey key, long ledgerId, String batchId,
                      String ackCode, MessageState currentState,
                      EnqueueTrigger trigger, long nextAttemptAtMs)
            throws SQLException;

    /**
     * 原子出队：取一条 {@code status='QUEUED'} 且 {@code next_attempt_at_ms <= now}
     * 的最早到期条目，把 status 更新为 {@code CONSUMED}、{@code consumed_at} 写为当前时刻。
     *
     * <p>在同一事务内：UPDATE 一行（影响行数决定是否拿到条目）→ SELECT 该行读全列。
     * UPDATE 影响行数大于 0 即本次成功拿走，{@link Optional} 返回对应 {@link RedeliveryEntry}；
     * 若 0 行，则队列里没有到期条目，返回 {@code Optional.empty()}。
     *
     * <p>多线程下：影响行数只能是 0 或 1，数据库层保证不重复。
     *
     * @param nowMs 当前时刻（epoch 毫秒）
     */
    Optional<RedeliveryEntry> claimNextDue(long nowMs) throws SQLException;

    /**
     * 把状态从 {@code CONSUMED}（或 {@code QUEUED}）推进到结算态。
     *
     * <p>结算态由调度器在重投完成后写：
     * <ul>
     *   <li>对端回 AA → {@link RedeliveryStatus#ACK_CONFIRMED}</li>
     *   <li>达到 {@code MAX_ATTEMPTS} → {@link RedeliveryStatus#PERMANENT_FAILED}</li>
     * </ul>
     * 同一事务内同步递增 {@code attempt} 与更新 {@code last_error} / {@code last_attempt_at}。
     */
    void settle(long queueId, RedeliveryStatus next, int attempt, String lastError, long nowMs)
            throws SQLException;

    /** 仅把 {@code attempt} 与 {@code next_attempt_at_ms} 重排（不改变 status），给 T33 退避留口子。 */
    void reschedule(long queueId, int attempt, long nextAttemptAtMs) throws SQLException;

    /**
     * 同 {@link #reschedule(long, int, long)}，但顺带把本次失败原因写进 {@code last_error}。
     *
     * <p><b>为什么调度器要用这一条而不是上面那条</b>：条目退回 {@code QUEUED} 之后，
     * {@code last_error} 若仍是上一轮的空串或更早的旧原因，值班人员看到的就是一条
     * 「在队列里排着、但说不出为什么失败」的记录 —— 与本产品「失败不静默」的承诺相悖。
     * T37 的重连耗尽正是这种情形（传输层抛异常、条目退避重排），必须落到 {@code last_error}。
     *
     * <p>默认实现退回到三参版本，保证测试替身不必跟着改。
     */
    default void reschedule(long queueId, int attempt, long nextAttemptAtMs, String lastError)
            throws SQLException {
        reschedule(queueId, attempt, nextAttemptAtMs);
    }

    /** 把 {@link RedeliveryStatus#PERMANENT_FAILED} 的某条目重新放回 {@code QUEUED}（人工放行）。 */
    void releaseToQueued(long queueId, long nextAttemptAtMs) throws SQLException;

    /**
     * 人工放行专用：把 {@link RedeliveryStatus#PERMANENT_FAILED} 的行一次性写回 {@code QUEUED}，
     * 同时把 {@code attempt} 复位 0（人工放行即「重新开始」，重试计数归零）。
     *
     * <p>与 {@link #releaseToQueued} 的差别正是「attempt 是否归零」 —— tick 流程里
     * {@code releaseToQueued} 是与 {@link #reschedule} 配套使用：{@code reschedule} 已经把 attempt
     * 写好，{@code releaseToQueued} 只改状态；本方法是人工放行通道，没有 {@code reschedule}
     * 在前，{@code attempt} 必须在这里归零。
     *
     * <p>只接受源 status 为 {@code PERMANENT_FAILED} 的行；{@code QUEUED / CONSUMED /
     * ACK_CONFIRMED} 一律被拒。
     */
    void manualReleaseToQueued(long queueId, long nextAttemptAtMs) throws SQLException;

    /** 按 ledgerId 列出全部条目（按 id 升序），便于 T35 重启扫描与排障。 */
    List<RedeliveryEntry> listByLedger(long ledgerId) throws SQLException;

    /** 当前 QUEUED 条数（不算 CONSUMED / 终态）。 */
    long queuedCount() throws SQLException;

    /** 入队结果 —— 类型上区分「新建」「重复」与「拒绝」，避免调用方用魔法数核对返回。 */
    final class OfferResult {
        private enum Kind { OFFERED, DUPLICATED, REJECTED }

        private final Kind kind;
        private final RedeliveryEntry offered;
        private final RedeliveryEntry existingOnDuplicate;
        private final String rejectionReason;

        private OfferResult(Kind kind, RedeliveryEntry offered, RedeliveryEntry existing, String rejection) {
            this.kind = kind;
            this.offered = offered;
            this.existingOnDuplicate = existing;
            this.rejectionReason = rejection;
        }

        public static OfferResult offered(RedeliveryEntry entry) {
            return new OfferResult(Kind.OFFERED, entry, null, null);
        }

        public static OfferResult duplicated(RedeliveryEntry existing) {
            return new OfferResult(Kind.DUPLICATED, null, existing, null);
        }

        public static OfferResult rejected(String reason) {
            return new OfferResult(Kind.REJECTED, null, null, reason);
        }

        public Kind kind() {
            return kind;
        }

        public boolean isOffered() {
            return kind == Kind.OFFERED;
        }

        public boolean isDuplicated() {
            return kind == Kind.DUPLICATED;
        }

        public boolean isRejected() {
            return kind == Kind.REJECTED;
        }

        /** 新条目（仅在 {@link #isOffered()} 时非 null）。 */
        public Optional<RedeliveryEntry> offeredEntry() {
            return Optional.ofNullable(offered);
        }

        /** 重复时原已存在那一条（仅在 {@link #isDuplicated()} 时非 null），供调用方读 idempotencyKey 等。 */
        public Optional<RedeliveryEntry> existingEntry() {
            return Optional.ofNullable(existingOnDuplicate);
        }

        /** 准入被拒的原因（仅在 {@link #isRejected()} 时非 null）；文本格式固定便于机器筛。 */
        public Optional<String> rejectionReason() {
            return Optional.ofNullable(rejectionReason);
        }
    }
}
