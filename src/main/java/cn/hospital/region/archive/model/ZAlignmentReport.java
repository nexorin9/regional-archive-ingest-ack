package cn.hospital.region.archive.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Z 段语义对齐的产物 —— <b>合上的</b>与<b>没合上的</b>，两半都要看得见。
 *
 * <p>只返回「合上的」会丢掉本任务最要紧的东西。信息员看到
 * {@code ZX1.3 → pastHistorySummary} 安心地点了确认，但他无从知道
 * {@code ZX4.3} 也在这份包里、而它<b>没有</b>被合进去——而那一条恰好就是
 * 「档案变更类型」。被静默丢掉的字段和被明确拒掉的字段，
 * 在下一班人眼里长得一模一样，所以本类型把后者也作为一等公民返回。
 *
 * <p><b>本类型不含任何业务状态</b>，它不会让报文落地，也不会让报文被拒收：
 * 落地只走确认门（T30）。{@link #requiresHumanConfirmation()} 是给调用方的信号，
 * 由调用方决定把这一条推进到待确认。
 *
 * @param threshold 本次生效的合档置信度阈值（随报告一起带出，便于复现与复盘）
 * @param merged    合上的绑定，键是 Z 段内定位串（{@code ZX1.3} 这类），保持报文内顺序
 * @param unresolved 没合上的逐条说明，带原因与理由
 */
public record ZAlignmentReport(double threshold,
                               Map<String, ZFieldMap.LocalFieldBinding> merged,
                               List<Unresolved> unresolved) {

    public ZAlignmentReport {
        if (Double.isNaN(threshold) || threshold < 0 || threshold > 1) {
            throw new IllegalArgumentException("置信度阈值须在 0 到 1 之间，实际 " + threshold);
        }
        Objects.requireNonNull(merged, "已合档绑定不得为 null");
        Objects.requireNonNull(unresolved, "未合档清单不得为 null");
        merged = Collections.unmodifiableMap(new LinkedHashMap<>(merged));
        unresolved = List.copyOf(unresolved);
    }

    /**
     * 一条没合上的 Z 段字段。
     *
     * @param zField            Z 段内定位串，例如 {@code ZX1.3}
     * @param platformLabel     平台写的字段名（可能为空：平台漏填字段名时按字段值兜底对齐）
     * @param proposedLocalField 模型建议的本地字段名；模型没给或已被校验拒掉时为空
     * @param confidence        模型给出的置信度；模型没给时为 {@code -1}
     * @param rationale         模型写的依据（可能为空）
     * @param reason            没合上的原因
     */
    public record Unresolved(String zField,
                             String platformLabel,
                             String proposedLocalField,
                             double confidence,
                             String rationale,
                             AlignmentReason reason) {

        /** 模型没给出置信度时的占位值；真实置信度恒在 0 到 1，不会与它混淆。 */
        public static final double NO_CONFIDENCE = -1d;

        public Unresolved {
            Objects.requireNonNull(zField, "Z 段定位串不得为 null");
            Objects.requireNonNull(reason, "未合档原因不得为 null：没有原因的缺失无法处置");
            platformLabel = platformLabel == null ? "" : platformLabel;
            proposedLocalField = proposedLocalField == null ? "" : proposedLocalField;
            rationale = rationale == null ? "" : rationale;
            if (!Double.isNaN(confidence) && confidence != NO_CONFIDENCE
                    && (confidence < 0 || confidence > 1)) {
                throw new IllegalArgumentException("置信度须在 0 到 1 之间，实际 " + confidence);
            }
        }

        /** 模型是否给过置信度。 */
        public boolean hasConfidence() {
            return confidence != NO_CONFIDENCE;
        }

        /** 给信息员看的一行说明：哪个 Z 段、什么平台字段名、为什么没合上。 */
        public String zhOneLine() {
            StringBuilder sb = new StringBuilder();
            sb.append(zField);
            sb.append("（平台字段名：").append(platformLabel.isEmpty() ? "<未提供>" : platformLabel).append("）：");
            sb.append(reason.shortLabel());
            if (!proposedLocalField.isEmpty()) {
                sb.append("（模型给的是 ").append(proposedLocalField);
                if (hasConfidence()) {
                    sb.append("，置信度 ").append(String.format(Locale.ROOT, "%.2f", confidence));
                }
                sb.append("）");
            }
            return sb.toString();
        }
    }

    /** 空结果：没有任何可对齐的东西（报文里就没有 Z 段）。不是异常。 */
    public static ZAlignmentReport empty(double threshold) {
        return new ZAlignmentReport(threshold, Map.of(), List.of());
    }

    /** 某个 Z 段字段的已合档绑定；未合档返回空。 */
    public Optional<ZFieldMap.LocalFieldBinding> bindingOf(String zField) {
        return Optional.ofNullable(merged.get(zField));
    }

    /** 已合档条数。 */
    public int size() {
        return merged.size();
    }

    public boolean isEmpty() {
        return merged.isEmpty() && unresolved.isEmpty();
    }

    /**
     * 是否有条目需要人工确认。
     *
     * <p>只要 {@link #unresolved()} 非空就为真——哪怕已合上的那几条完全正常。
     * 调用方据此把该条报文推进到<b>待确认</b>而不是已落地：档案包里有一部分内容
     * 没有归处时，把整条当成「对齐好了」放过去，等于让这部分内容悄悄消失。
     */
    public boolean requiresHumanConfirmation() {
        return !unresolved.isEmpty();
    }

    /** 供下游消费的 {@link ZFieldMap} 视图（只含已合档的绑定）。 */
    public ZFieldMap zFieldMap() {
        return new ZFieldMap(merged);
    }

    /** 未合档原因分布，按原因码统计，用于「这批为什么都卡在待确认」这一问。 */
    public Map<AlignmentReason, Integer> reasonCounts() {
        Map<AlignmentReason, Integer> counts = new LinkedHashMap<>();
        for (Unresolved u : unresolved) {
            counts.merge(u.reason(), 1, Integer::sum);
        }
        return Collections.unmodifiableMap(counts);
    }

    /** 一行人话摘要，进启动日志与调用审计（不含任何报文原文）。 */
    public String zhSummary() {
        if (merged.isEmpty() && unresolved.isEmpty()) {
            return "无可对齐的 Z 段";
        }
        return "已合档 " + merged.size() + " 项、未合档 " + unresolved.size()
                + " 项（阈值 " + String.format(Locale.ROOT, "%.2f", threshold) + "）";
    }
}