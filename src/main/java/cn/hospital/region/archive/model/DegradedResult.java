package cn.hospital.region.archive.model;

import java.util.Objects;

/**
 * 降级结果 —— <b>模型侧唯一一种失败形态</b>。
 *
 * <p>业务层拿到的模型结果只有两种：{@link ModelOutcome.Succeeded} 或本类。
 * 没有第三种，也不需要：五类错误码在重试耗尽之后<b>全部收敛到这一条降级路径</b>，
 * 而这一条降级路径<b>只有一个出口能改业务状态</b>（见
 * {@code ArchiveIngestService} 的模型环节）。
 *
 * <h2>降级之后发生什么、绝不发生什么</h2>
 * <p><b>会发生的</b>：该条推进到<b>待确认</b>、台账 {@code degraded=true}、回执按
 * {@code MSA-1=AE + ERR} 回给区域平台（AE 可重投，模型恢复后重投一次就能直接对齐）。
 *
 * <p><b>绝不会发生的</b>：推进到<b>已落地</b>或<b>拒收</b>。这两条都是终态：
 * 落地意味着数据已经写进了院内患者主索引，拒收意味着「对端别再发了」——
 * 而模型挂掉这件事对报文本身<b>什么也没说</b>，把它当成拒收等于让一次本端服务抖动
 * 永久丢掉一条居民档案；把它当成落地则会在 HIS 里写出一条没有任何身份依据的记录。
 *
 * <h2>为什么 detail 不进 ERR 段</h2>
 * <p>{@link #detail()} 带着模型端的原始报错（可能含端点地址与响应片段），它只进<b>本端</b>日志与调用审计。
 * 出站回执给的是 {@link cn.hospital.region.archive.ack.RejectionReason#MODEL_DEGRADED}
 * 这一条固定口径的 ERR —— 区域平台需要知道的是「这条可以重投」，不是本端的内部分类。
 */
public record DegradedResult(ModelErrorCode errorCode,
                             String operation,
                             int attempts,
                             String detail) {

    /** 重试耗尽前的尝试次数下界：至少调用过一次（哪怕是确定性子类里不重试的那次）。 */
    public static final int MIN_ATTEMPTS = 1;

    public DegradedResult {
        Objects.requireNonNull(errorCode, "错误码不得为 null：没有错误码的降级无法排障");
        Objects.requireNonNull(operation, "操作名不得为 null");
        if (attempts < MIN_ATTEMPTS) {
            throw new IllegalArgumentException("尝试次数至少为 " + MIN_ATTEMPTS + "，实际 " + attempts);
        }
        detail = detail == null ? "" : detail;
    }

    /** 便捷构造：重试耗尽后仍未成功。 */
    public static DegradedResult of(ModelErrorCode code, String operation, int attempts, String detail) {
        return new DegradedResult(code, operation, attempts, detail);
    }

    /** 是否属于「重试有意义但已经用完预算」的那一类超时。 */
    public boolean retryableButExhausted() {
        return errorCode.retryable();
    }

    /**
     * 台账审计行的 reason 短语，例如 {@code model-degraded:READ_TIMEOUT}。
     *
     * <p>前缀与 {@link cn.hospital.region.archive.ack.RejectionReason#MODEL_DEGRADED} 的建议码同名，
     * 便于 T19 的只读导出按前缀剥离；后半段带具体错误码，使「这批降级条目里多少是超时、
     * 多少是密钥失效」这个问题在台账里直接可答，不需要去翻模型服务的日志。
     */
    public String ledgerReason() {
        return "model-degraded:" + errorCode.auditToken();
    }

    /**
     * 给人看的一句话：为什么降级、这类错误该怎么处置。
     *
     * <p>进日志与启动摘要；<b>不</b>进出站回执（见类注释）。
     */
    public String reason() {
        StringBuilder sb = new StringBuilder();
        sb.append("语义对齐服务不可用（").append(errorCode.auditToken()).append("）：")
          .append(errorCode.zhLabel());
        if (retryableButExhausted()) {
            sb.append("；已重试 ").append(attempts).append(" 次仍失败");
        } else {
            sb.append("；该类失败重试无效，未重试");
        }
        sb.append("。本条降级为待确认，由人工确认或重投队列处理。处置建议：").append(errorCode.remedy());
        return sb.toString();
    }

    @Override
    public String toString() {
        return "DegradedResult{" + errorCode.auditToken()
                + " op=" + operation
                + " attempts=" + attempts
                + (detail.isEmpty() ? "" : " detail=" + detail) + "}";
    }
}