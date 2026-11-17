package cn.hospital.region.archive.obs;

import cn.hospital.region.archive.ledger.BatchStats;
import cn.hospital.region.archive.ledger.MessageState;
import cn.hospital.region.archive.model.ConfidenceBucketReport;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 批次可观测报告 —— 把「这一批处理得怎么样」压成<b>一张能贴进科室群</b>的表。
 *
 * <h2>为什么单独一层，而不是直接把三个已有报表拼起来</h2>
 * <p>本产品已经有三份各自正确的报表：{@link BatchStats}（四态分布与拒收原因）、
 * {@link cn.hospital.region.archive.obs.CapacityMetrics}（P50/P95/P99 与队列深度）、
 * {@link ConfidenceBucketReport}（置信度分桶与降级率）。它们分属三个包、面向三个问题。
 * 信息员下午问的却是<b>一个问题</b>：「这一批还有几条要我点确认？有没有哪边出问题了？」
 * 答这个问题需要三份数字<b>在同一行里</b>——三份报表分三处取，
 * 就会出现「统计那张是上午的、容量那张是昨天的」这种口径错位。
 *
 * <p>本类因此只做<b>拼装与派生</b>，不重新统计任何一件事：处理量、四态分布、
 * 拒收率、P50/P95/P99 全部取自上面三份的既有结果，
 * 派生的只有两个：{@link #rejectionRate()} 与 {@link #throughputVsManualRate()}。
 *
 * <h2>拒收率为什么单独派生而不是让 BatchStats 算</h2>
 * <p>{@code BatchStats} 的分母是<b>台账里的全部八态</b>（含重投子态），
 * 而运维口中的「拒收率」分母是<b>这一批收到的报文数</b>。两者在有重投时不相等，
 * 且没有谁错。本类把口径写死成前者并在输出里标注，
 * 是因为这两个数一旦混用，趋势图会突然「变好」——实际只是重投队列被清空了。
 *
 * <h2>两个数据源可以缺</h2>
 * <p>耗时分布来自基准跑批，不是每一批都有；置信度分桶来自模型审计表，
 * 模型全挂的那天是空的。两者都给 {@code null} 时本类照常出数，
 * 相应几行显示「未采集」——一份缺了两行的报告仍然有用，
 * 一份因为缺数据就拒绝生成的报告则永远没人看。
 */
public record BatchObservabilityReport(String batchId,
                                       BatchStats stats,
                                       CapacityMetrics.BatchMetrics latency,
                                       ConfidenceBucketReport confidence) {

    public BatchObservabilityReport {
        Objects.requireNonNull(batchId, "批次号不得为 null");
        Objects.requireNonNull(stats, "批次统计不得为 null");
        if (!batchId.equals(stats.batchId())) {
            throw new IllegalArgumentException("批次号与统计对象不一致：报表 " + batchId
                    + " / 统计 " + stats.batchId());
        }
    }

    /** 便捷构造：只有台账统计（耗时与置信度未采集）。 */
    public static BatchObservabilityReport of(String batchId, BatchStats stats) {
        return new BatchObservabilityReport(batchId, stats, null, null);
    }

    /** 这一批的报文总数（八态之和，与 {@link BatchStats#total()} 同口径）。 */
    public long processed() {
        return stats.total();
    }

    /** 已落地条数。 */
    public long landed() {
        return stats.counts().getOrDefault(MessageState.LANDED, 0L);
    }

    /** 待确认条数 —— 信息员要点的次数。 */
    public long pendingConfirm() {
        return stats.counts().getOrDefault(MessageState.PENDING_CONFIRM, 0L);
    }

    /** 拒收条数。 */
    public long rejected() {
        return stats.counts().getOrDefault(MessageState.REJECTED, 0L);
    }

    /**
     * 拒收率 = 拒收 / 本批总数。
     *
     * <p>分母含重投子态（见类注释的口径说明）。总数为 0 时返回 0.0 而不是 NaN：
     * NaN 进了报表会一路传染到同比环比，最后整张图变成空白。
     */
    public double rejectionRate() {
        long total = processed();
        return total == 0 ? 0.0 : (double) rejected() / total;
    }

    /**
     * 待确认率 = 待确认 / 本批总数 —— 「这一批有多少比例要我动手」。
     *
     * <p>与 {@link #lowConfidenceRatio()}（模型自评低置信占比）刻意分开：
     * 前者是<b>事实</b>（台账里真的停在那），后者是<b>原因估计</b>（模型给了多少低分）。
     * 两者差距大说明模型对置信度判断不准，那是改模型的事，不是改台账的事。
     */
    public double pendingConfirmRate() {
        long total = processed();
        return total == 0 ? 0.0 : (double) pendingConfirm() / total;
    }

    /** 模型自评低置信占比（观察点口径）；未采集置信度数据时返回 {@code null}。 */
    public Double lowConfidenceRatio() {
        return confidence == null ? null : confidence.lowConfidenceRatio();
    }

    /** 模型降级率（调用次数口径）；未采集时返回 {@code null}。 */
    public Double degradationRatio() {
        return confidence == null ? null : confidence.degradationRatio();
    }

    /** P99 单条处理耗时（毫秒）；未采集耗时数据时返回 {@code null}。 */
    public Double p99Millis() {
        return latency == null ? null
                : latency.latency().p99Nanos() / 1_000_000.0;
    }

    /** P50 单条处理耗时（毫秒）；未采集时返回 {@code null}。 */
    public Double p50Millis() {
        return latency == null ? null
                : latency.latency().p50Nanos() / 1_000_000.0;
    }

    /** 是否采集到耗时分布（本批是否有延迟数据）。 */
    public boolean hasLatency() {
        return latency != null;
    }

    /** 是否采集到置信度数据。 */
    public boolean hasConfidence() {
        return confidence != null;
    }

    /**
     * 人工负载对比：本批待确认条数 vs 一个可配置的人工基线（每批默认 300 条）。
     *
     * <p>存在的理由是把「容量」翻译成运维能说的话：「这批要人点 40 下，
     * 按每人每批 20 下算，两个人十分钟」。缺了这个换算，条数只是一个没有参照的数字。
     * 基线可配置而不是写死，因为不同社区中心的批量习惯差别很大。
     */
    public static final long DEFAULT_MANUAL_PER_BATCH = 300L;

    /** 按默认基线算出的人工点确认次数估计。 */
    public double manualClickEstimate() {
        return manualClickEstimate(DEFAULT_MANUAL_PER_BATCH);
    }

    /**
     * 按给定基线算人工点确认次数估计。
     *
     * @param baselinePerOperatorPerBatch 每名操作员每批能点完多少条；必须 &gt; 0
     */
    public double manualClickEstimate(long baselinePerOperatorPerBatch) {
        if (baselinePerOperatorPerBatch <= 0) {
            throw new IllegalArgumentException("人工基线必须为正数：" + baselinePerOperatorPerBatch);
        }
        return pendingConfirm() / (double) baselinePerOperatorPerBatch;
    }

    /** 四态分布（缺失补 0，顺序固定为 {@link BatchStats#MAIN_STATES}）。 */
    public Map<MessageState, Long> mainStateCounts() {
        Map<MessageState, Long> out = new EnumMap<>(MessageState.class);
        for (MessageState s : BatchStats.MAIN_STATES) {
            out.put(s, stats.counts().getOrDefault(s, 0L));
        }
        return Map.copyOf(out);
    }

    /** 展示行：给人看的部分，逐行可读、不含患者可识别信息。 */
    public List<String> displayLines() {
        StringBuilder sb = new StringBuilder();
        sb.append("批次 ").append(batchId).append(" 共 ").append(processed()).append(" 条：")
                .append("已落地 ").append(landed())
                .append(" / 待确认 ").append(pendingConfirm())
                .append(" / 拒收 ").append(rejected())
                .append(String.format(Locale.ROOT, "（拒收率 %.1f%%，待确认率 %.1f%%）",
                        rejectionRate() * 100, pendingConfirmRate() * 100));
        List<String> lines = new java.util.ArrayList<>();
        lines.add(sb.toString());

        for (Map.Entry<MessageState, Long> e : mainStateCounts().entrySet()) {
            lines.add(String.format("  %-28s %d", e.getKey().dbValue(), e.getValue()));
        }
        for (Map.Entry<MessageState, Long> e : stats.retryStates().entrySet()) {
            lines.add(String.format("  %-28s %d（重投子态）", e.getKey().dbValue(), e.getValue()));
        }
        if (!stats.rejectionReasons().isEmpty()) {
            stats.rejectionReasons().forEach((reason, n) -> lines.add(
                    String.format("  拒收原因 %-20s %d", reason, n)));
        }
        if (hasLatency()) {
            lines.add(String.format(Locale.ROOT,
                    "  单条处理耗时 P50=%.2fms P99=%.2fms（阈值 %dms）", p50Millis(), p99Millis(),
                    CapacityMetrics.P99_THRESHOLD_MS));
        } else {
            lines.add("  单条处理耗时 未采集（本批未跑容量基准）");
        }
        if (hasConfidence()) {
            lines.add(String.format(Locale.ROOT,
                    "  模型低置信占比 %.1f%%、降级率 %.1f%%",
                    lowConfidenceRatio() * 100, degradationRatio() * 100));
        } else {
            lines.add("  置信度分桶 未采集（本批无模型调用审计）");
        }
        lines.add(String.format(Locale.ROOT,
                "  人工负载估计：待确认 %d 条 ≈ %.1f 人·批（基线每批 %d 条/人）",
                pendingConfirm(), manualClickEstimate(), DEFAULT_MANUAL_PER_BATCH));
        return List.copyOf(lines);
    }

    /** 逐行原始数据（键=值），供两轮之间做差分对比。 */
    public String rawData() {
        StringBuilder sb = new StringBuilder();
        sb.append("batch.id=").append(batchId).append('\n');
        sb.append("batch.processed=").append(processed()).append('\n');
        sb.append("batch.landed=").append(landed()).append('\n');
        sb.append("batch.pending_confirm=").append(pendingConfirm()).append('\n');
        sb.append("batch.rejected=").append(rejected()).append('\n');
        sb.append("batch.rejection_rate=").append(String.format(Locale.ROOT, "%.4f",
                rejectionRate())).append('\n');
        sb.append("batch.pending_confirm_rate=").append(String.format(Locale.ROOT, "%.4f",
                pendingConfirmRate())).append('\n');
        for (Map.Entry<MessageState, Long> e : mainStateCounts().entrySet()) {
            sb.append("state.").append(e.getKey().dbValue()).append('=').append(e.getValue()).append('\n');
        }
        sb.append("latency.p50_ms=").append(hasLatency()
                ? String.format(Locale.ROOT, "%.3f", p50Millis()) : "not_collected").append('\n');
        sb.append("latency.p99_ms=").append(hasLatency()
                ? String.format(Locale.ROOT, "%.3f", p99Millis()) : "not_collected").append('\n');
        sb.append("model.low_confidence_ratio=").append(hasConfidence()
                ? String.format(Locale.ROOT, "%.4f", lowConfidenceRatio()) : "not_collected").append('\n');
        sb.append("model.degradation_ratio=").append(hasConfidence()
                ? String.format(Locale.ROOT, "%.4f", degradationRatio()) : "not_collected").append('\n');
        sb.append("manual.click_estimate=").append(String.format(Locale.ROOT, "%.3f",
                manualClickEstimate())).append('\n');
        return sb.toString();
    }
}