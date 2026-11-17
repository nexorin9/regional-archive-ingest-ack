package cn.hospital.region.archive.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 一个置信度观察点 —— 分桶统计的<b>最小计数单位</b>。
 *
 * <p>为什么按「观察点」而不是按「报文」存：一条报文能贡献 N 个观察点
 * （Z 段 N 个字段映射 + 身份候选 N 条 + 可能还有 1 条「模型没给结论」），
 * 而信息员要的两个数——各桶条数、涉及几条报文——口径不同，必须能从同一批观察点各自算出来。
 * 只存报文级的一个分数，等于在入库那一刻就把 N 个字段的信息压成了一个数，
 * 而那个数取决于你恰好选了哪个字段去代表它。
 *
 * @param at         该观察点的时刻（模型给出结论的时刻，不是报文到达时刻）。
 *                   必须带时刻，否则「导出某一天」只能靠全表扫描加应用层过滤，
 *                   而 {@code --date} 是这个产品每天都要回答的问题，不该每次扫全表
 * @param series     所属序列（Z 段映射 / 身份候选）
 * @param subjectRef 报文级引用（MSH-10 控制号；取不到时留空串）。
 *                   <b>只用于去重算「涉及几条报文」，不进 CSV 明细列</b>
 * @param confidence 模型给出的置信度；{@link ConfidenceBucket#NO_CONFIDENCE} 表示没给
 * @param degraded   该观察点是否来自降级路径（模型不可达，走确定性基线）
 */
public record ConfidenceSample(Instant at,
                               ConfidenceSeries series,
                               String subjectRef,
                               double confidence,
                               boolean degraded) {

    public ConfidenceSample {
        Objects.requireNonNull(at, "观察点时刻不得为 null");
        Objects.requireNonNull(series, "置信度序列不得为 null");
        subjectRef = subjectRef == null ? "" : subjectRef.trim();
        // 归桶前先过一道：NaN 在库里能存下来（DOUBLE PRECISION 允许），
        // 到了统计层才发现会让整个报表挂掉，而它的来源只有上游算错一处。
        ConfidenceBucket.of(confidence);
    }

    /** 本观察点落在哪一档。 */
    public ConfidenceBucket bucket() {
        return ConfidenceBucket.of(confidence);
    }

    /** 是否带分数（{@code false} 即 {@link ConfidenceBucket#UNKNOWN}）。 */
    public boolean hasConfidence() {
        return confidence >= 0;
    }

    /**
     * 从一条报文的 Z 段对齐报告取观察点。
     *
     * <p><b>合上的与没合上的都取</b>：只统计合上的那些，报表就会显示「今天 8 个字段都对上了、
     * 置信度 0.92」，而同一份报告里那个<b>没</b>合上的字段恰好是「档案变更类型」——
     * 信息员看到这份报表会以为这条报文没问题。
     *
     * @param at         给出结论的时刻
     * @param subjectRef 报文级引用（通常为 MSH-10 控制号）
     * @param report     对齐报告
     * @return 观察点列表；报告为空时返回空列表
     */
    public static List<ConfidenceSample> fromAlignment(Instant at, String subjectRef, ZAlignmentReport report) {
        Objects.requireNonNull(report, "对齐报告不得为 null");
        List<ConfidenceSample> out = new ArrayList<>(report.size() + report.unresolved().size());
        // 合上的：模型给了分数，落在 HIGH/MEDIUM/LOW 三档之一
        for (Map.Entry<String, ZFieldMap.LocalFieldBinding> e : report.merged().entrySet()) {
            out.add(new ConfidenceSample(at, ConfidenceSeries.Z_ALIGNMENT, subjectRef,
                    e.getValue().confidence(), false));
        }
        // 没合上的：没分数的落 UNKNOWN，有分数的（低于阈值被拒）落真实档位
        for (ZAlignmentReport.Unresolved u : report.unresolved()) {
            out.add(new ConfidenceSample(at, ConfidenceSeries.Z_ALIGNMENT, subjectRef,
                    u.hasConfidence() ? u.confidence() : ConfidenceBucket.NO_CONFIDENCE, false));
        }
        return List.copyOf(out);
    }

    /**
     * 从接入主路径拿到的 {@link ZFieldMap} 取观察点 —— 这是<b>常驻进程</b>实际会走的那个入口。
     *
     * <p>与 {@link #fromAlignment} 的差别只有一处，而且是有意留的：主路径拿到的是
     * {@code ZFieldMap}（合上的那些），拿不到 {@link ZAlignmentReport#unresolved()}。
     * 因此本方法<b>只记合上的映射</b>，加一条兜底的「没给结论」观察点。
     * 手里有完整报告的调用方（对齐器自己的用例、对照实验）应当用
     * {@link #fromAlignment}，那一路会把「低于阈值被拒」的那些字段也逐条记进去。
     *
     * <p>合不上的字段为什么不在这里补一条：主路径没拿到它们，补出来的只能是猜测，
     * 而分桶统计的价值恰恰在于「桶里的每一条都对得上一次真实的模型结论」。
     *
     * <p><b>调用前提</b>：只在模型<b>确实被问过</b>时调用本方法。
     * 一条压根没有 Z 段的报文不需要「没能对齐」，它没有可对齐的东西；
     * 给它记一条 UNKNOWN 会在分桶表里凭空多出一条「模型没给出结论」，
     * 而那一条对应的报文根本不会进待确认清单，工作量口径就此虚高。
     *
     * @param at         给出结论的时刻
     * @param subjectRef 报文级引用（通常为 MSH-10 控制号）
     * @param map        对齐产物（合上的映射）
     * @param degraded   本次是否走了降级路径（模型不可达）
     */
    public static List<ConfidenceSample> fromZFieldMap(Instant at, String subjectRef,
                                                       ZFieldMap map, boolean degraded) {
        Objects.requireNonNull(map, "Z 段映射不得为 null");
        if (map.isEmpty()) {
            // 模型被问过却什么都没给：这是「没有结论」这个事实本身，必须占一个观察点，
            // 否则「各桶条数之和 = 观察点总数」会在报表上少一条，而少的那条恰是降级的那条。
            return List.of(new ConfidenceSample(at, ConfidenceSeries.Z_ALIGNMENT,
                    subjectRef, ConfidenceBucket.NO_CONFIDENCE, degraded));
        }
        List<ConfidenceSample> out = new ArrayList<>(map.size());
        for (Map.Entry<String, ZFieldMap.LocalFieldBinding> e : map.bindings().entrySet()) {
            out.add(new ConfidenceSample(at, ConfidenceSeries.Z_ALIGNMENT, subjectRef,
                    e.getValue().confidence(), degraded));
        }
        return List.copyOf(out);
    }

    /**
     * 从一条报文的身份匹配报告取观察点。
     *
     * <p><b>四种结论产出四种观察点数量</b>，这正是「分桶条数之和 = 总数」能成立的原因——
     * 每种情况都有确定的条数，没有一条观察点凭空消失：
     * <ul>
     *   <li>{@code CANDIDATES_READY}：每条候选一个观察点；</li>
     *   <li>{@code NO_CANDIDATE}：候选为空，补一个「没给结论」的 UNKNOWN 观察点
     *       —— 没有候选正是信息员最需要看见的一种情况；</li>
     *   <li>{@code MODEL_DEGRADED}：候选（确定性基线给的）+ 一个 UNKNOWN 观察点，全部标降级；</li>
     *   <li>{@code ID_CARD_CONFLICT_REJECTED}：<b>零个观察点</b>。它整条拒收，
     *       不产出候选也不进待确认，不占人工复核工作量——
     *       把它算进「预计待确认」会把工作量口径虚高，而它的工作量在拒收台账上，不在这里。</li>
     * </ul>
     *
     * @param at         给出结论的时刻
     * @param subjectRef 报文级引用（通常为 MSH-10 控制号）
     * @param report     身份匹配报告
     */
    public static List<ConfidenceSample> fromIdentity(Instant at, String subjectRef, IdentityMatchReport report) {
        Objects.requireNonNull(report, "身份匹配报告不得为 null");
        if (report.rejected()) {
            return List.of();
        }
        boolean degraded = report.outcome() == IdentityMatchReport.Outcome.MODEL_DEGRADED;
        List<ConfidenceSample> out = new ArrayList<>(report.candidates().size() + 1);
        for (IdentityCandidate c : report.candidates().candidates()) {
            out.add(new ConfidenceSample(at, ConfidenceSeries.IDENTITY_CANDIDATE, subjectRef,
                    c.confidence(), degraded));
        }
        if (report.outcome() != IdentityMatchReport.Outcome.CANDIDATES_READY) {
            // 「模型没给结论」本身是一条观察：它让 UNKNOWN 桶有真实含义，
            // 也让分桶总数不依赖候选条数（候选 0 条 ≠ 观察 0 条）
            out.add(new ConfidenceSample(at, ConfidenceSeries.IDENTITY_CANDIDATE, subjectRef,
                    ConfidenceBucket.NO_CONFIDENCE, degraded));
        }
        return List.copyOf(out);
    }

    /**
     * 一行中文说明，供排障打印；不含姓名、证件等任何可识别内容
     * （{@code subjectRef} 是控制号，不是患者标识）。
     */
    public String zhOneLine() {
        return series.zhLabel() + " " + bucket().zhDisplay()
                + " 置信度=" + (hasConfidence() ? String.format(Locale.ROOT, "%.3f", confidence) : "未给")
                + (degraded ? " 降级" : "");
    }
}