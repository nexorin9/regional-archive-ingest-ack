package cn.hospital.region.archive.ledger;

import java.util.Objects;

/**
 * 台账行当前状态与调用方声称的来源态不一致 —— 存储层对该次迁移的<b>拒绝</b>。
 *
 * <p><b>与 T15 的 {@code ConcurrentLedgerUpdateException} 的分工</b>：
 * <ul>
 *   <li>本异常：<b>来源态</b>对不上。{@code transition(id, from, to, ...)} 里的 {@code from}
 *       与该行 {@code state} 列的实际值不同 —— 要么行不存在，要么调用方拿着过期的状态来推进，
 *       要么有人绕过状态机直接改过 state（裸 SQL UPDATE state 不会让 version 自增，
 *       所以这种改动在带版本守卫的迁移里正是靠「version 相符但 state 不符」被认出来的）。</li>
 *   <li>T15 的并发异常：调用方带版本守卫提交，而 {@code version} 已被并发方推进过。
 *       存储层<b>先查 version 再查 state</b>（version 是严格更强的检查），
 *       因此「两个调用方读到同一份 version」这个最典型的并发局面报的是并发异常。</li>
 * </ul>
 * 两者都不吞掉，都要求调用方<b>重读后重试</b>；都不得静默按「大概也没事」继续往下走。
 *
 * <p>本异常是 checked：调用方必须在签名上看见它，避免在异常路径上漏写状态不一致的处理。
 */
public class LedgerStateConflictException extends Exception {

    private final long ledgerId;
    private final MessageState claimedFrom;
    private final MessageState actualState;
    private final boolean rowExists;

    public LedgerStateConflictException(long ledgerId, MessageState claimedFrom,
                                        MessageState actualState, boolean rowExists) {
        super(buildMessage(ledgerId, claimedFrom, actualState, rowExists));
        this.ledgerId = ledgerId;
        this.claimedFrom = claimedFrom;
        this.actualState = actualState;
        this.rowExists = rowExists;
    }

    private static String buildMessage(long ledgerId, MessageState claimedFrom,
                                       MessageState actualState, boolean rowExists) {
        String db = claimedFrom == null ? "<null>" : claimedFrom.dbValue();
        if (!rowExists) {
            return "台账行不存在：ledgerId=" + ledgerId + "（调用方声称来源态为 " + db + "）";
        }
        return "台账行状态与来源态不符：ledgerId=" + ledgerId
                + " 调用方声称 from=" + db
                + " 库中实际 state=" + Objects.requireNonNullElse(actualState, MessageState.RECEIVED).dbValue()
                + " —— 该次迁移已被存储层拒绝；调用方须重读后重试";
    }

    public long ledgerId() {
        return ledgerId;
    }

    /** 调用方声称的来源态；{@code null} 表示调用方没给出。 */
    public MessageState claimedFrom() {
        return claimedFrom;
    }

    /** 库中实际状态；{@code null} 表示行不存在。 */
    public MessageState actualState() {
        return actualState;
    }

    /** 台账行是否存在。 */
    public boolean rowExists() {
        return rowExists;
    }
}