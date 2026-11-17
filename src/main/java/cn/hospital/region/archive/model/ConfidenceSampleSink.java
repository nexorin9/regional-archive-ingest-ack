package cn.hospital.region.archive.model;

import java.util.List;

/**
 * 置信度观察点的<b>记录口</b> —— 一个端口，与 {@link ModelCallAudit} 同一套理由。
 *
 * <p>为什么单独开一个口而不是把观察点塞进 {@link ModelCallAudit}：
 * 两者的<b>粒度不同</b>。一次模型调用产出<b>一条</b>审计行（重试序列则每次尝试一行），
 * 而一次调用可能产出<b>零到几十条</b>观察点——一条报文的 Z 段有 5 到 8 个字段，
 * 身份候选有 1 到 3 条，再加上「模型没给结论」的那一条。
 * 塞进同一个出口就必须让调用方自己决定「这次调用算几条」，
 * 而那个决定会随着上游实现变化而漂移：改一个 JSON schema，报表的口径就跟着变，
 * 而报表上的数字是要拿去估工作量的。
 *
 * <p>本口<b>随调用传整批</b>而不是逐条传：一条报文的观察点在同一次处理里产出，
 * 逐条写会在中途失败时留下「3 条字段映射有分数、第 4 条没有」的半份记录，
 * 而半份记录算出来的分桶总数仍然是一条自洽的数字，只是它不对应任何一份完整报告。
 *
 * <p>实现<b>不应</b>把异常抛回调用链 —— 与 {@link ModelCallAudit} 同策略：
 * 统计挂掉不该让报文退回待确认，那会让「模型出了一次统计错误」变成一批业务副作用。
 */
@FunctionalInterface
public interface ConfidenceSampleSink {

    /**
     * 记一批观察点（同一条报文的一次处理产出的全部观察点）。
     *
     * <p>实现不应把异常抛回调用链；真抛了，业务层照常完成入账并打一条 WARNING。
     *
     * @param samples 观察点；{@code null} 或空列表视作无事发生
     */
    void record(List<ConfidenceSample> samples);

    /** 丢弃口：装配未启用统计时使用（等价于「这条观察点不存在」）。 */
    static ConfidenceSampleSink discarding() {
        return samples -> {
            // 刻意空实现：不启用统计时不该每次调用都判一次 null
        };
    }
}