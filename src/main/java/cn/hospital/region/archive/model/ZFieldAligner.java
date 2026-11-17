package cn.hospital.region.archive.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * Z 段 → 院内本地字段的<b>合档闸门</b> —— 模型的第一件真活。
 *
 * <h2>为什么这一层不能省</h2>
 * <p>区域平台写的字段名是「既往病史摘要」，院内字段叫 {@code pastHistorySummary}，
 * 两边<b>一个字都对不上</b>。这不是拼写差异，是两套命名体系：
 * 字面相等与编辑距离在这段上<b>方向是反的</b>——越像越不可能对上，
 * 所以规则方案在这条接缝上必然失手（{@code BaselineMatcher} 在 T25 会把这条
 * 结论用数据坐实）。模型是唯一能跨语言体系做语义映射的东西。
 *
 * <p>但「模型给了映射」不等于「可以合档」。网关已经保证了响应结构可信
 * （固定 JSON Schema、拒绝未知键、字段形状校验），却保证不了三件事：
 * <ol>
 *   <li>置信度够不够（{@code 0.2} 的映射和 {@code 0.95} 的映射长得一模一样）；</li>
 *   <li>本地字段名是不是真的存在（Schema 管形状不管取值）；</li>
 *   <li>输出里有没有混进诊断、治疗建议或代签内容（那是本产品的红线）。</li>
 * </ol>
 * 这三件事都属于<b>业务判断</b>，不属于「传输与结构」，因此落在本类而不是网关里。
 *
 * <h2>一条硬规则：没合上的一律转人工，绝不静默丢弃</h2>
 * <p>本类<b>不推进任何业务状态</b>。任何没合上的条目都会带着
 * {@link AlignmentReason} 进 {@link ZAlignmentReport#unresolved()}，
 * 调用方据此把该条报文推进到<b>待确认</b>（{@link ZAlignmentReport#requiresHumanConfirmation()}）。
 * 没有第五种处置：不是因为「不落地上就没事」，而是因为这一条的主路径
 * 是<b>信息员点一下确认</b>，静默丢弃等于让那条内容凭空消失且无人知情。
 *
 * <h2>歧义一律显式拒绝，不任选其一</h2>
 * <p>「同一个 Z 段被映射到两个本地字段」由网关在结构校验阶段整条拒掉
 * （{@code SCHEMA_INVALID}，不产半份结果）；「多个 Z 段指向同一个本地字段」
 * 由本类把<b>冲突双方全部</b>移入未合档——留一条合档就是替人做了取舍，
 * 而写进去的那一条会覆盖掉另一条的内容。
 *
 * <p><b>失败语义</b>：本类<b>不</b>处理模型不可达。{@link ModelCallException}
 * 一律原样上抛，由 {@link ResilientModelGateway} 那条唯一降级路径统一收口——
 * 两处各写一半的必然结果是某个调用点走了另写的那一半。
 */
public final class ZFieldAligner {

    private static final Logger LOG = Logger.getLogger(ZFieldAligner.class.getName());

    /** 默认合档置信度阈值。低于此值不硬合档，转待确认等人确认。 */
    public static final double DEFAULT_CONFIDENCE_THRESHOLD = 0.75d;

    /**
     * ZX-n 版式里被对齐的位置：字段名在 -2、字段值在 -3。
     *
     * <p>下标大于 3 的字段本产品不消费（对齐器只把 -2 当平台字段名、-3 当字段值），
     * 但它们<b>不能被静默忽略</b>：忽略了就会有一段内容在报告里彻底不见。
     */
    static final int Z_LAYOUT_MAX_INDEX = 3;

    /** ZX-2 字段名的下标。 */
    static final int Z_LABEL_INDEX = 2;

    /** ZX-3 字段值的下标。 */
    static final int Z_VALUE_INDEX = 3;

    /**
     * 输出里出现即判越界的词 —— 诊断、治疗建议、处方、剂量调整、代签。
     *
     * <p><b>只用「越界必然成立」的词</b>，不用「沾边就算」的词：
     * 朴素地拦「服用」会误伤「用药依从性」的正常依据说明
     * （「患者是否按时服用药物」是描述字段语义，不是给建议），
     * 误报一次，信息员就会开始习惯性地忽略这条提示，那时它就真的没用了。
     */
    private static final List<String> CLINICAL_BOUNDARY_TOKENS = List.of(
            "诊断为", "确诊", "疑似", "考虑是否", "治疗方案", "治疗建议", "建议治疗",
            "处方", "建议服用", "建议停药", "建议加药", "服用剂量", "调整剂量", "剂量调整",
            "手术指征", "建议手术", "预后不良", "代签", "电子签名", "signature",
            "diagnos", "prescription", "treatment plan");

    private final ModelGateway gateway;
    private final double threshold;

    /** 用默认阈值 0.75 装配。 */
    public ZFieldAligner(ModelGateway gateway) {
        this(gateway, DEFAULT_CONFIDENCE_THRESHOLD);
    }

    public ZFieldAligner(ModelGateway gateway, double threshold) {
        this.gateway = Objects.requireNonNull(gateway, "模型网关不得为 null");
        if (Double.isNaN(threshold) || threshold < 0 || threshold > 1) {
            throw new IllegalArgumentException("置信度阈值须在 0 到 1 之间，实际 " + threshold);
        }
        this.threshold = threshold;
    }

    /** 本次生效的合档阈值。 */
    public double threshold() {
        return threshold;
    }

    /**
     * 对齐一批 Z 段。
     *
     * @param zSegments 报文里的 Z 段（一般由 {@link ZSegmentReader#read} 给出），不可为 null
     * @return 合档与未合档两份结果；<b>不为 null，也不抛「对齐不了」这种异常</b>
     * @throws ModelCallException 模型不可达或响应不可信 —— 由 {@link ResilientModelGateway}
     *                            统一降级，本类不吞
     */
    public ZAlignmentReport align(List<ZSegment> zSegments) {
        Objects.requireNonNull(zSegments, "Z 段集合不得为 null");
        if (zSegments.isEmpty()) {
            return ZAlignmentReport.empty(threshold);
        }

        // —— 第一遍：报文自身的版式问题，这一类不需要模型就能判定，也不该为它付一次调用 ——
        List<ZSegment> alignable = new ArrayList<>(zSegments.size());
        List<ZAlignmentReport.Unresolved> unresolved = new ArrayList<>();
        for (ZSegment z : zSegments) {
            ZAlignmentReport.Unresolved layoutProblem = inspectLayout(z);
            if (layoutProblem != null) {
                unresolved.add(layoutProblem);
            } else {
                alignable.add(z);
            }
        }
        if (alignable.isEmpty()) {
            return new ZAlignmentReport(threshold, Map.of(), unresolved);
        }

        // —— 第二遍：本地字典装不下这份档案包时整批不自动合档 ——
        // 挑一部分合进去 = 替人决定丢掉哪几条，而丢掉的那些不会有人知道。
        if (alignable.size() > LocalFieldDictionary.size()) {
            for (ZSegment z : alignable) {
                unresolved.add(new ZAlignmentReport.Unresolved(z.locate(Z_VALUE_INDEX), z.label(),
                        "", ZAlignmentReport.Unresolved.NO_CONFIDENCE, "",
                        AlignmentReason.MORE_Z_FIELDS_THAN_LOCAL_FIELDS));
            }
            LOG.warning("Z 段对齐整批转人工：带值 Z 段 " + alignable.size()
                    + " 条 > 本地字段字典 " + LocalFieldDictionary.size() + " 条");
            return new ZAlignmentReport(threshold, Map.of(), unresolved);
        }

        ZFieldMap raw = gateway.alignZFields(alignable);

        // —— 第三遍：逐条评估，顺序跟随报文，保证同一份报文的结论可复现 ——
        List<Evaluated> evaluated = new ArrayList<>(alignable.size());
        for (ZSegment z : alignable) {
            evaluated.add(evaluate(z, raw));
        }

        // —— 第四遍：本地字段抢用检测。冲突双方全部转人工，不留赢家 ——
        Map<String, List<Integer>> claimsByLocalField = new LinkedHashMap<>();
        for (int i = 0; i < evaluated.size(); i++) {
            Evaluated e = evaluated.get(i);
            if (e.reason() == null) {
                claimsByLocalField.computeIfAbsent(e.binding().localField(), k -> new ArrayList<>()).add(i);
            }
        }
        for (List<Integer> claimants : claimsByLocalField.values()) {
            if (claimants.size() < 2) {
                continue;
            }
            for (int i : claimants) {
                Evaluated e = evaluated.get(i);
                evaluated.set(i, e.rejected(AlignmentReason.AMBIGUOUS_LOCAL_FIELD,
                        "本地字段 " + e.binding().localField() + " 被 " + claimants.size() + " 个 Z 段同时认领"));
            }
        }

        Map<String, ZFieldMap.LocalFieldBinding> merged = new LinkedHashMap<>();
        List<ZAlignmentReport.Unresolved> rejected = new ArrayList<>();
        for (Evaluated e : evaluated) {
            if (e.reason() == null) {
                merged.put(e.zField, e.binding());
            } else {
                rejected.add(e.unresolved());
            }
        }
        unresolved.addAll(rejected);
        return new ZAlignmentReport(threshold, merged, unresolved);
    }

    // ================= 各段判定 =================

    /** ZX-n 版式检查：只看报文本身，与模型无关。 */
    private static ZAlignmentReport.Unresolved inspectLayout(ZSegment z) {
        boolean labelEmpty = z.label().trim().isEmpty();
        boolean valueEmpty = z.value().trim().isEmpty();
        if (labelEmpty && valueEmpty) {
            return new ZAlignmentReport.Unresolved(z.locate(Z_VALUE_INDEX), "", "",
                    ZAlignmentReport.Unresolved.NO_CONFIDENCE, "",
                    AlignmentReason.EMPTY_Z_SEGMENT);
        }
        for (Integer index : z.fields().keySet()) {
            if (index > Z_LAYOUT_MAX_INDEX) {
                return new ZAlignmentReport.Unresolved(z.locate(index), z.label(), "",
                        ZAlignmentReport.Unresolved.NO_CONFIDENCE, "",
                        AlignmentReason.BEYOND_Z_LAYOUT);
            }
        }
        return null;
    }

    /** 单条评估：字典复读 → 临床边界 → 置信度阈值。 */
    private Evaluated evaluate(ZSegment z, ZFieldMap raw) {
        String zField = z.locate(Z_VALUE_INDEX);
        var binding = raw.bindingOf(zField).orElse(null);
        if (binding == null) {
            return new Evaluated(z, zField,
                    new ZAlignmentReport.Unresolved(zField, z.label(), "",
                            ZAlignmentReport.Unresolved.NO_CONFIDENCE, "",
                            AlignmentReason.NO_BINDING_PRODUCED));
        }
        if (!LocalFieldDictionary.contains(binding.localField())) {
            return new Evaluated(z, zField, new ZAlignmentReport.Unresolved(zField, z.label(),
                    binding.localField(), binding.confidence(), binding.rationale(),
                    AlignmentReason.UNKNOWN_LOCAL_FIELD));
        }
        String boundaryHit = findClinicalBoundaryHit(binding.rationale());
        if (boundaryHit != null) {
            // 越界不是「扣分」，是拒收：把带临床结论的映射写进档案，性质比不对齐严重得多。
            LOG.warning("Z 段对齐拒绝一条越界绑定（" + zField + " → " + binding.localField()
                    + "）：依据命中越界内容，理由与字段名可查，依据原文不外发");
            return new Evaluated(z, zField, new ZAlignmentReport.Unresolved(zField, z.label(),
                    binding.localField(), binding.confidence(), redactRationale(binding.rationale(), boundaryHit),
                    AlignmentReason.CLINICAL_ADVICE_IN_OUTPUT));
        }
        if (binding.confidence() < threshold) {
            return new Evaluated(z, zField, new ZAlignmentReport.Unresolved(zField, z.label(),
                    binding.localField(), binding.confidence(), binding.rationale(),
                    AlignmentReason.LOW_CONFIDENCE));
        }
        return new Evaluated(z, zField, null, binding);
    }

    /** 在依据文本里找越界词；命中返回该词，否则返回 {@code null}。 */
    static String findClinicalBoundaryHit(String rationale) {
        if (rationale == null || rationale.isEmpty()) {
            return null;
        }
        String lower = rationale.toLowerCase(Locale.ROOT);
        for (String token : CLINICAL_BOUNDARY_TOKENS) {
            // 纯 ASCII 词按小写比对，中文词按原样比对。
            boolean hit = isAscii(token)
                    ? lower.contains(token.toLowerCase(Locale.ROOT))
                    : rationale.contains(token);
            if (hit) {
                return token;
            }
        }
        return null;
    }

    private static boolean isAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 127) {
                return false;
            }
        }
        return true;
    }

    /**
     * 命中越界词后，把依据原文<b>整段</b>换成占位说明，且<b>不复述命中的那个词</b>。
     *
     * <p>不复述有两个理由：一是依据里往往还带着居民档案内容，原文最不该去的地方
     * 就是台账与待确认清单（T40 的明文扫描守的就是这条）；
     * 二是「拒收理由里恰好含被拒的那个词」会让「输出零越界词」这条自检永远不成立，
     * 而一条永远不成立的自检等于没有自检。要看模型究竟写了什么，去<b>模型调用审计</b>——
     * 那里本来就是原始响应该待的地方。
     */
    private static String redactRationale(String rationale, String hit) {
        return "<依据含越界内容，整段拒收；原文见模型调用审计>";
    }

    // ================= 中间态 =================

    /** 一条 Z 段字段的评估结果。{@code reason() == null} 表示合上了。 */
    private static final class Evaluated {
        private final ZSegment z;
        private final String zField;
        private final ZAlignmentReport.Unresolved unresolved;
        private final ZFieldMap.LocalFieldBinding binding;

        Evaluated(ZSegment z, String zField, ZAlignmentReport.Unresolved unresolved) {
            this(z, zField, unresolved, null);
        }

        Evaluated(ZSegment z, String zField, ZAlignmentReport.Unresolved unresolved,
                  ZFieldMap.LocalFieldBinding binding) {
            this.z = z;
            this.zField = zField;
            this.unresolved = unresolved;
            this.binding = binding;
        }

        AlignmentReason reason() {
            return unresolved == null ? null : unresolved.reason();
        }

        ZFieldMap.LocalFieldBinding binding() {
            return binding;
        }

        /**
         * 换一个拒绝理由重判，<b>保留模型建议的字段名与置信度</b>。
         *
         * <p>保留是必须的：信息员要判断的恰恰是「模型说的是 {@code pastHistorySummary}、
         * 置信度 0.8，但另一条也认这个字段」——把模型建议抹掉，人就只能凭字段名重猜一遍。
         */
        Evaluated rejected(AlignmentReason reason, String detail) {
            String proposed = binding == null ? "" : binding.localField();
            double confidence = binding == null
                    ? ZAlignmentReport.Unresolved.NO_CONFIDENCE : binding.confidence();
            String rationale = binding == null ? "" : binding.rationale();
            ZAlignmentReport.Unresolved replaced = new ZAlignmentReport.Unresolved(
                    zField, z.label(), proposed, confidence,
                    rationale.isEmpty() ? detail : rationale + "；" + detail, reason);
            return new Evaluated(z, zField, replaced, binding);
        }

        ZAlignmentReport.Unresolved unresolved() {
            return unresolved;
        }
    }
}