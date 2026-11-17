package cn.hospital.region.archive.ledger;

import java.util.Objects;

/**
 * 台账行的 {@code version} 已被并发方推进过 —— 乐观锁拒绝了本次迁移。
 *
 * <h2>什么时候会抛</h2>
 * <p>带版本守卫的 {@link LedgerStore#transition(long, MessageState, MessageState, long, String, String)}
 * 执行的是 {@code UPDATE ... WHERE id = ? AND state = ? AND version = ?}。影响行数为 0 有两种
 * 可能，实现上先把两者<b>分开</b>再决定抛哪个：
 * <ul>
 *   <li>来源态还对得上、但 {@code version} 已被别人推过 → <b>本异常</b>（真正的并发窗口）；</li>
 *   <li>{@code state} 也变了 → {@link LedgerStateConflictException}（调用方拿着过期的来源态）。</li>
 * </ul>
 * 不分开的话，调用方只能靠猜决定要不要重读，而猜错的后果是<b>用过期状态覆盖别人的写入</b>。
 *
 * <h2>与 {@link LedgerStateConflictException} 的分工</h2>
 * <p>一个管<b>状态</b>（我以为的 from 与库里实际 state 不符），一个管<b>版本</b>（from 对得上，
 * 但这行在我读之后、写入之前被人推进过）。两者都不吞掉，都要求重读后重试；重试约定统一在
 * {@link LedgerRetryPolicy}，上限 3 次后转人工可见错误，<b>不得静默吞掉</b>。
 *
 * <p>本异常是 checked：调用方必须在签名上看见它，避免在并发路径上漏写「重读 + 重试」的处理，
 * 从而把一次本可自动收敛的并发冲突变成静默的数据不一致。
 */
public class ConcurrentLedgerUpdateException extends Exception {

    private final long ledgerId;
    private final long expectedVersion;
    private final long actualVersion;
    private final MessageState actualState;

    public ConcurrentLedgerUpdateException(long ledgerId, long expectedVersion,
                                           long actualVersion, MessageState actualState) {
        super(buildMessage(ledgerId, expectedVersion, actualVersion, actualState));
        this.ledgerId = ledgerId;
        this.expectedVersion = expectedVersion;
        this.actualVersion = actualVersion;
        this.actualState = actualState;
    }

    private static String buildMessage(long ledgerId, long expectedVersion,
                                       long actualVersion, MessageState actualState) {
        return "台账行版本已被并发方推进：ledgerId=" + ledgerId
                + " 调用方期望 version=" + expectedVersion
                + " 库中实际 version=" + actualVersion
                + " state=" + Objects.requireNonNullElse(actualState, MessageState.RECEIVED).dbValue()
                + " —— 该次迁移已被存储层拒绝；调用方须重读 version 与 state 后重试"
                + "（重试上限见 LedgerRetryPolicy.DEFAULT_MAX_ATTEMPTS），不得静默按「大概也没事」继续";
    }

    public long ledgerId() {
        return ledgerId;
    }

    /** 调用方提交这次迁移时持有的 version。 */
    public long expectedVersion() {
        return expectedVersion;
    }

    /** 库里当前的 version —— 比 {@link #expectedVersion()} 大，说明确实被别人推进过。 */
    public long actualVersion() {
        return actualVersion;
    }

    /** 库里当前的状态；{@code null} 表示行已不存在。 */
    public MessageState actualState() {
        return actualState;
    }

    /** 行是否还在（不存在时重试也没有意义，调用方应改走别的处置）。 */
    public boolean rowExists() {
        return actualState != null;
    }

    /** 单行日志摘要，不含报文原文。 */
    public String toLogLine() {
        return "ledgerId=" + ledgerId + " expectedVersion=" + expectedVersion
                + " actualVersion=" + actualVersion
                + " state=" + (actualState == null ? "<行已不存在>" : actualState.dbValue());
    }
}