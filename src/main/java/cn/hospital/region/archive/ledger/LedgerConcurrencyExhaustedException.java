package cn.hospital.region.archive.ledger;

/**
 * 重读重试用尽仍拿不到一次成功的迁移 —— <b>人工可见错误</b>，不是可以静默吞掉的抖动。
 *
 * <h2>为什么要有这一层，而不是直接把最后一次的并发异常抛出去</h2>
 * <p>{@link ConcurrentLedgerUpdateException} 是「重试信号」，调用方看到它就知道该重读再试；
 * 连着 3 次都是同一个信号时，结论已经变了：<b>不是抖动，是持续争用</b>。此时正确的处置不是
 * 继续 retry，也不是回一条 AA 蒙过去，而是让这条报文<b>停在当前态等人看</b> ——
 * 信息员次日追问时能看到「这一条卡住了」，而不是看到「系统说它收到了但库里没有」。
 *
 * <p>因此本异常携带重试过程中最后一次看到的真实状态与版本，供排障直接定位；
 * 它<b>不携带</b>报文原文（遵守 spec 的脱敏边界），也不自动推进任何业务状态。
 *
 * <p>本异常是 checked：调用方（接入主路径、确认门、重投调度）必须在签名上看见它，
 * 并明确决定「这条进待确认等人处理」还是「让调度器稍后再试」。
 */
public class LedgerConcurrencyExhaustedException extends Exception {

    private final long ledgerId;
    private final int attempts;
    private final long lastExpectedVersion;
    private final long lastActualVersion;
    private final MessageState lastActualState;

    public LedgerConcurrencyExhaustedException(long ledgerId, int attempts,
                                               long lastExpectedVersion, long lastActualVersion,
                                               MessageState lastActualState) {
        super(buildMessage(ledgerId, attempts, lastExpectedVersion, lastActualVersion, lastActualState));
        this.ledgerId = ledgerId;
        this.attempts = attempts;
        this.lastExpectedVersion = lastExpectedVersion;
        this.lastActualVersion = lastActualVersion;
        this.lastActualState = lastActualState;
    }

    private static String buildMessage(long ledgerId, int attempts, long lastExpectedVersion,
                                       long lastActualVersion, MessageState lastActualState) {
        return "台账并发更新重试已用尽：ledgerId=" + ledgerId
                + " 已重读重试 " + attempts + " 次仍未取得一次成功迁移"
                + "（最后一次 期望 version=" + lastExpectedVersion
                + " 实际 version=" + lastActualVersion
                + " state=" + (lastActualState == null ? "<行已不存在>" : lastActualState.dbValue())
                + "）—— 本次不推进业务状态，该报文保持原态待人工介入；"
                + "请查看该报文当前态后重新确认或交由重投队列处理，禁止按「大概也没事」静默放过";
    }

    public long ledgerId() {
        return ledgerId;
    }

    /** 已消耗的重试次数（等于配置的上限）。 */
    public int attempts() {
        return attempts;
    }

    public long lastExpectedVersion() {
        return lastExpectedVersion;
    }

    public long lastActualVersion() {
        return lastActualVersion;
    }

    public MessageState lastActualState() {
        return lastActualState;
    }
}