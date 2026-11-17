package cn.hospital.region.archive.ledger;

import java.util.Objects;

/**
 * {@link JdbcLedgerStore#insertIfAbsent} 的返回类型 —— 幂等插入的两种结果。
 *
 * <p><b>为什么不是返回 {@code null} 或抛异常</b>：同一幂等键第二次到达时<b>不是</b>错误，
 * 而是最常见的情况（对端重推、网络抖动后重发、T35 的重启续跑）。把它做成一个正常的返回值，
 * 调用方（T15 的 {@code IdempotencyGuard}）就能写成一目了然的两分支：
 *
 * <pre>{@code
 * switch (store.insertIfAbsent(entry)) {
 *     case InsertOutcome.Inserted in -> business.process(in.ledgerId());      // 首次：继续走业务
 *     case InsertOutcome.AlreadyProcessed dup -> acknowledge(dup.currentState()); // 重复：按当前态回执
 * }
 * }</pre>
 *
 * <p><b>唯一约束冲突在这里被翻译掉</b>：V1 schema 的 {@code uk_ledger_idem_key} 冲突在
 * PostgreSQL 与 H2 PG 兼容模式下都表现为 SQLState {@code 23505}；本 store 捕获它、回滚、
 * 反查原行，返回 {@link AlreadyProcessed} 而<b>不</b>把 {@code SQLException} 抛给调用方。
 * 调用方永远不需要认识 SQLState。
 */
public sealed interface InsertOutcome permits InsertOutcome.Inserted, InsertOutcome.AlreadyProcessed {

    /** 本次调用真正插入了一行（该幂等键在本端此前不存在）。 */
    record Inserted(long ledgerId, IdempotencyKey key, MessageState initialState) implements InsertOutcome {

        public Inserted {
            if (ledgerId <= 0) {
                throw new IllegalArgumentException("新插入的行必须拿到正主键，实际=" + ledgerId);
            }
            initialState = Objects.requireNonNull(initialState, "initialState 不得为 null");
        }
    }

    /**
     * 该幂等键此前已入账，本次<b>没有</b>插入新行。
     *
     * @param existingLedgerId 原有那行的主键 —— 调用方要用它回执「这条我早就收过了」
     * @param currentState     原有那行<b>当前</b>的状态，不是首次入账时的状态：
     *                        同一键第二次到达时它可能已经推进到待确认甚至已落地，
     *                        回执必须按当前态给，否则对账时会看到「同一条报文回了两种结果」
     */
    record AlreadyProcessed(long existingLedgerId, IdempotencyKey key, MessageState currentState)
            implements InsertOutcome {

        public AlreadyProcessed {
            if (existingLedgerId <= 0) {
                throw new IllegalArgumentException("已存在的行必须持有正主键，实际=" + existingLedgerId);
            }
            currentState = Objects.requireNonNull(currentState, "currentState 不得为 null");
        }
    }

    /** 本次是否真的新插入了一行。 */
    default boolean isNew() {
        return this instanceof Inserted;
    }

    /** 台账行主键（新插入的用新 id，重复的用原 id）。 */
    default long ledgerId() {
        if (this instanceof Inserted in) {
            return in.ledgerId();
        }
        return ((AlreadyProcessed) this).existingLedgerId();
    }
}