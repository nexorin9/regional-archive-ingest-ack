package cn.hospital.region.archive.identity;

/**
 * 患者主索引写入失败 —— <b>可重放</b>，不是终局判定。
 *
 * <p>写院内 HIS 失败（对端 5xx、连接超时、凭据过期）时抛本异常。调用方据此做的处理是
 * <b>一条也不做</b>：台账状态留在待确认、报文原文与幂等键原样保留，等人重试或等重投调度。
 * 绝不允许「写失败却把这条标成已落地」——那会让次日对账时数字对不上，而现场查不出是哪一步错的。
 *
 * <p>{@link #replayable()} 是给调用方的分流依据：可重放的失败退回待确认，
 * 不可重放的（例如报文本身与本地字段规范对不上，对端必然再拒一次）才允许进入人工处置。
 */
public class PatientIndexWriteException extends Exception {

    private final boolean replayable;

    public PatientIndexWriteException(String message, boolean replayable) {
        super(message);
        this.replayable = replayable;
    }

    public PatientIndexWriteException(String message, boolean replayable, Throwable cause) {
        super(message, cause);
        this.replayable = replayable;
    }

    /** 失败是否可在不修改报文的前提下重试（对端抖动 / 超时 → true）。 */
    public boolean replayable() {
        return replayable;
    }
}