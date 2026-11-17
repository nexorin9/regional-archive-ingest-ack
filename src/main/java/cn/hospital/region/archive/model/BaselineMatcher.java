package cn.hospital.region.archive.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 确定性基线比对器 —— <b>不调模型</b>，只用字面相等与字形编辑距离。
 *
 * <p>本类存在的全部意义是<b>当对照组</b>。模型的每次调用都要花钱、都要等、
 * 都要在不可达时降级；那么「不用模型能做到什么程度」必须先有一个<b>可复核的数字</b>，
 * 否则「模型有用」只是形容词。本类就是那个数字的来源，
 * T26 在同一份中文变体集、同一份候选切片、同一套评测口径下把它与模型并排跑。
 *
 * <h2>档位与阈值（集中定义，便于复现）</h2>
 * <pre>
 *   跨院主键 PID-3 字面相同   → PID3_EXACT     硬键，不看姓名
 *   姓名去空白+小写后逐字相同 → NAME_LITERAL
 *   姓名字形距离 ≤ {@value #HIGH_SIMILARITY_MAX_DISTANCE}   → NAME_NEAR
 *   姓名字形距离 = {@value #CANDIDATE_MAX_DISTANCE}   → NAME_FAR（低置信候选）
 *   姓名字形距离 &gt; {@value #CANDIDATE_MAX_DISTANCE}      → 不出候选
 *   姓名对不上但出生日期一致   → BIRTHDATE_ONLY
 * </pre>
 *
 * <h2>能力边界：编辑距离对简繁与同音错别字的方向是<b>反的</b></h2>
 * <p>这是本类最重要的一段注释，也是模型在本产品里存在的理由。
 * 字形距离量的是「改几个字能变成对方」，而国内区域健康档案里最常见的三类姓名差异，
 * 恰好都不在这个方向上：
 * <ul>
 *   <li><b>姓名含空格</b>：{@code 张 伟} 与 {@code 张伟} 只差一个空白，去空白后逐字相同
 *       —— <b>基线一次就中</b>。这一类是白捡的，不该算进模型的功劳；
 *       T26 的对照报告里它当<b>对照项</b>：模型在这一类上必须也不该比基线更好。</li>
 *   <li><b>简繁异体</b>：{@code 張伟} 与 {@code 张伟} 字形差 1，落在「高相似」里，<b>方向对</b>。
 *       但一个字是繁是简跟「像不像」根本无关：一个繁体字（如「陳」）与一个同音简体字（如「沉」）
 *       字形同样差 1，而它们<b>不是同一个人</b>。距离 1 同时容纳了「同一人异体登记」与
 *       「两个不同的人碰巧同音」，基线<b>分不开</b>。</li>
 *   <li><b>同音错别字</b>：{@code 沈静} 与 {@code 陈静} 字形差 1，而它们<b>读音相同、大概率是同一个人</b>；
 *       反过来 {@code 张伟} 与 {@code 张薇} 字形也差 1，却<b>不是</b>同一个人。
 *       编辑距离把这两类判成同一个数字，而它们的业务后果<b>完全相反</b>
 *       —— 前者要合档，后者绝不能合。这是<b>方向反了</b>，不是精度不够，
 *       所以再怎么调 {@link #CANDIDATE_MAX_DISTANCE} 也调不出来。</li>
 * </ul>
 *
 * <h2>为什么基线不做繁折简，也不查拼音</h2>
 * <p>不查拼音是清楚的：拼音表要按方言与生僻字维护，而「同音不同字」本来就是<b>最大的歧义来源</b>，
 * 拿它当相似度等于把「张伟」和「张薇」绑在一起。
 * <p>不折简的理由与 {@link IdentityText} 里那份表<b>不一样</b>，这里要说清：
 * 那张表被候选闸门用来<b>写依据说明</b>（「姓名为简繁异体」这句人话），
 * 表外的字原样返回是安全的 —— 说错了也只是依据文案不准，不影响命中判定。
 * 若基线也折简，它就会变成一条随字符表大小漂移的曲线：表里有的一律命中、表外的漏掉，
 * 漏掉的那些<b>静默地变成不命中</b>，看起来像基线能力差，实际只是词表没覆盖。
 * 基线因此停在字面上：它给出的每个数字读者都能自己复算，
 * 变体识别交给模型（{@link IdentityCandidateMatcher}），两边职责不重叠。
 *
 * <h2>出生日期不加分，只用来压分</h2>
 * <p>同一天出生的居民在一个县里不止一个，所以「出生日期一致」单独<b>不构成</b>命中理由，
 * 只排最后一位档。出生日期真正起作用的地方是<b>压分</b>：姓名像但出生日期对不上，
 * 说明这多半是另一个人（全国叫「张伟」的人能把一个县的人口数填满），
 * 排序分压到 {@link #BIRTHDATE_CONFLICT_SCORE}。档位仍保留姓名那一档，
 * 因为「姓名字面相同」这条<b>证据本身</b>没有因为出生日期不符而消失。
 *
 * <h2>结构性保证：本类不可能调模型</h2>
 * <p>本类<b>不持有</b> {@link ModelGateway}，构造器不接受它，字段里也没有它 ——
 * 不是靠自觉，是结构上没有这个引用，由 {@code BaselineMatcherTest} 的反射断言守着。
 * 对照实验一旦被污染（基线偷偷调了模型拿高分），整个 ≥10pp 结论就作废；
 * 这条断言是那道防线的实现。
 *
 * <h2>本类不写 HIS、不改台账、不推进状态</h2>
 * <p>它连 {@code PatientIndexWriter} 端口都不持有，返回的是一个可读、可复算的排序。
 * 落地仍然只由确认门（T30）在人工确认之后推进。
 */
public final class BaselineMatcher {

    /** 编辑距离 ≤ 此值记为高相似（姓名差一个字以内）。 */
    public static final int HIGH_SIMILARITY_MAX_DISTANCE = 1;

    /** 编辑距离 ≤ 此值才出候选（含「差两个字」的远候选）。 */
    public static final int CANDIDATE_MAX_DISTANCE = 2;

    /**
     * 姓名相似但出生日期不一致时的排序分上限。
     *
     * <p>取 {@link BaselineLevel#BIRTHDATE_ONLY} 之下：仍然留在候选里
     * （信息员需要看见「有个同名的、但生日不一样」），
     * 但一定排在出生日期一致的同名记录之后。
     */
    public static final double BIRTHDATE_CONFLICT_SCORE = 0.10d;

    /** {@link #narrowSlice} 在未指定上限时保留的候选数。 */
    public static final int DEFAULT_SLICE_SIZE = 40;

    static {
        if (HIGH_SIMILARITY_MAX_DISTANCE > CANDIDATE_MAX_DISTANCE) {
            throw new IllegalStateException("高相似阈值不得大于出候选阈值");
        }
        if (BIRTHDATE_CONFLICT_SCORE >= BaselineLevel.BIRTHDATE_ONLY.rankScore()) {
            throw new IllegalStateException("出生日期冲突的压分必须低于「仅出生日期一致」档，"
                    + "否则压分等于没压");
        }
        // 除硬键档外，所有排序分都低于「无锚点置信度上限」——
        // 也就低于任何下游按合档阈值判断的门槛，基线排序分因此不可能被当成自动合档依据。
        for (BaselineLevel level : BaselineLevel.values()) {
            if (level != BaselineLevel.PID3_EXACT
                    && level.rankScore() > IdentityCandidateMatcher.UNANCHORED_CONFIDENCE_CAP) {
                throw new IllegalStateException("档位 " + level + " 的排序分高于无锚点置信度上限，"
                        + "会让基线排序分被下游误当作可自动合档的置信度");
            }
        }
    }

    /**
     * 基线归一：去首尾空白 → 去掉<b>全部</b>空白 → 转小写。
     *
     * <p>去空白是唯一做的归一（见类注释「为什么基线不做繁折简」）。
     * 姓名里的全角空格 U+3000 与半角空格同样去掉：区域平台按不同输入法打出来的名字里两种都有，
     * 而这一类<b>基线一次就中</b>，属于白捡的那部分。
     *
     * @param raw 原始姓名；{@code null} 按空串处理
     * @return 归一结果；任一方为空串时返回空串（调用点据此判定「不可比」）
     */
    public static String normalizeForBaseline(String raw) {
        return IdentityText.stripSpaces(raw == null ? "" : raw).toLowerCase(Locale.ROOT);
    }

    /**
     * 比对一次身份，给出基线的候选与逐条判定。
     *
     * @param inbound    报文里的来访居民（PID-3 / PID-5 / PID-7）
     * @param localIndex 院内候选切片（同一份切片也会送给模型组，见 {@code VariantEvalCorpus}）
     * @return 判定结果；<b>本方法不抛业务异常，也不返回 null</b>
     */
    public BaselineResult match(InboundIdentity inbound, LocalPatientIndex localIndex) {
        Objects.requireNonNull(inbound, "来访身份不得为 null");
        Objects.requireNonNull(localIndex, "院内候选切片不得为 null");
        if (localIndex.isEmpty()) {
            return BaselineResult.empty();
        }

        List<Indexed> paired = new ArrayList<>();
        for (LocalPatientIndex.LocalPatient p : localIndex.patients()) {
            Judgement judgement = judge(inbound, p);
            if (judgement == null) {
                continue;
            }
            paired.add(new Indexed(
                    new IdentityCandidate(p.localMrn(), judgement.score(), evidenceOf(inbound, p, judgement)),
                    new BaselineDecision(p.localMrn(), judgement.level(), judgement.nameDistance(),
                            judgement.score(), judgement.note())));
        }

        // 排序后判定必须跟着走，否则 BaselineResult 的一一对应约束会当场抛出来。
        paired.sort(Comparator
                .comparingDouble((Indexed x) -> x.candidate().confidence()).reversed()
                .thenComparing(x -> x.decision().localMrn()));

        List<IdentityCandidate> candidates = new ArrayList<>(paired.size());
        List<BaselineDecision> decisions = new ArrayList<>(paired.size());
        for (Indexed x : paired) {
            candidates.add(x.candidate());
            decisions.add(x.decision());
        }
        return new BaselineResult(new Candidates(candidates), decisions);
    }

    /**
     * 把院内候选切片<b>收窄</b>到「值得让模型看」的规模。
     *
     * <p>收窄只按<b>与变体无关</b>的硬条件：主键字面相同、出生日期一致、姓名首字相同。
     * 刻意<b>不</b>用编辑距离收窄：一旦用距离收窄，模型组就再也没机会看见那些
     * 「距离大但确实是同一人」的记录，§10 的对照实验会得出「模型没用」的错误结论 ——
     * 是收窄替模型把活干完了。对照实验要用的切片一律<b>不</b>经过本方法，
     * 两组拿到的是同一份切片（见 {@code VariantEvalCorpus}）。
     *
     * <p>模型不可达时的降级路径走的就是本方法：那时没有模型，退回基线名单，
     * 信息员至少拿到一份本机可核对的候选，而不是空列表。
     *
     * @param limit 保留上限；小于 1 时按 {@link #DEFAULT_SLICE_SIZE} 处理
     */
    public LocalPatientIndex narrowSlice(InboundIdentity inbound, LocalPatientIndex localIndex, int limit) {
        Objects.requireNonNull(inbound, "来访身份不得为 null");
        Objects.requireNonNull(localIndex, "院内候选切片不得为 null");
        int cap = limit < 1 ? DEFAULT_SLICE_SIZE : limit;

        String inboundName = normalizeForBaseline(inbound.name());
        String inboundBirth = IdentityText.normalizeBirthDate(inbound.birthDate());

        List<LocalPatientIndex.LocalPatient> kept = new ArrayList<>();
        for (LocalPatientIndex.LocalPatient p : localIndex.patients()) {
            if (kept.size() >= cap) {
                break;
            }
            if (!inbound.patientRef().isEmpty() && inbound.patientRef().equals(p.localMrn())) {
                kept.add(p);
                continue;
            }
            if (!inboundBirth.isEmpty()
                    && inboundBirth.equals(IdentityText.normalizeBirthDate(p.birthDate()))) {
                kept.add(p);
                continue;
            }
            if (inboundName.isEmpty() || p.name().isEmpty()) {
                continue;
            }
            String local = normalizeForBaseline(p.name());
            if (!local.isEmpty() && inboundName.charAt(0) == local.charAt(0)) {
                kept.add(p);
            }
        }
        return kept.isEmpty() ? LocalPatientIndex.empty() : new LocalPatientIndex(kept);
    }

    // ============================================================== 逐条判定

    /** 一条记录的判定结果；{@code null} 表示不满足任何一档、不出候选。 */
    private static Judgement judge(InboundIdentity inbound, LocalPatientIndex.LocalPatient p) {
        // 1) 硬键：跨院主键与院内主键字面相同。这条成立时不需要再看姓名是否相似。
        if (!inbound.patientRef().isEmpty() && inbound.patientRef().equals(p.localMrn())) {
            return Judgement.of(BaselineLevel.PID3_EXACT, BaselineDecision.NOT_COMPARABLE,
                    "区域平台主键与院内主键字面相同（硬键，不依赖姓名字形）");
        }

        String a = normalizeForBaseline(inbound.name());
        String b = normalizeForBaseline(p.name());
        boolean comparable = !a.isEmpty() && !b.isEmpty();
        int distance = comparable ? EditDistance.of(a, b) : BaselineDecision.NOT_COMPARABLE;

        // 2) 姓名字面相同。
        if (comparable && a.equals(b)) {
            return withBirthDateGuard(inbound, p, Judgement.of(BaselineLevel.NAME_LITERAL, distance,
                    "姓名字面相同：" + IdentityText.nameVariantNote(inbound.name(), p.name())));
        }

        // 3) 字形距离两档。
        if (comparable) {
            if (distance <= HIGH_SIMILARITY_MAX_DISTANCE) {
                return withBirthDateGuard(inbound, p, Judgement.of(BaselineLevel.NAME_NEAR, distance,
                        "姓名差 " + distance + " 个字（字形距离，"
                                + IdentityText.nameVariantNote(inbound.name(), p.name()) + "）"));
            }
            if (distance <= CANDIDATE_MAX_DISTANCE) {
                return withBirthDateGuard(inbound, p, Judgement.of(BaselineLevel.NAME_FAR, distance,
                        "姓名差 " + distance + " 个字，已到出候选上限（低置信）："
                                + IdentityText.nameVariantNote(inbound.name(), p.name())));
            }
        }

        // 4) 只剩出生日期：不构成命中理由，但也不该把同名不同人的记录直接排除掉。
        if (inbound.hasBirthDate() && IdentityText.sameBirthDate(inbound.birthDate(), p.birthDate())) {
            return Judgement.of(BaselineLevel.BIRTHDATE_ONLY, distance,
                    "姓名对不上（字形距离 " + (comparable ? distance : "不可比") + "），仅出生日期一致；"
                            + "该档位不足以判定同一人，仅供确认台并排显示");
        }
        return null;
    }

    /**
     * 出生日期冲突压分：姓名像但生日对不上，多半是另一个人。
     *
     * <p>只在<b>双方出生日期都非空且确实不等</b>时压。平台有时不给 PID-7，
     * 此时不该把「没给生日」当成「生日对不上」——那会把整批缺生日的报文压到底部，
     * 而它们恰恰是最需要人看的一批。
     */
    private static Judgement withBirthDateGuard(InboundIdentity inbound, LocalPatientIndex.LocalPatient p,
                                                Judgement judgement) {
        if (!inbound.hasBirthDate() || p.birthDate() == null || p.birthDate().trim().isEmpty()) {
            return judgement;
        }
        if (IdentityText.sameBirthDate(inbound.birthDate(), p.birthDate())) {
            return judgement;
        }
        return judgement.penalised(BIRTHDATE_CONFLICT_SCORE,
                judgement.note() + "；但出生日期与院内记录不一致，同名不同人比同人多，排序分已压到冲突上限");
    }

    /** 候选依据：与闸门同一套封闭词表，且<b>只写本机可核对的事实</b>。 */
    private static List<IdentityCandidate.Evidence> evidenceOf(InboundIdentity inbound,
                                                                LocalPatientIndex.LocalPatient p,
                                                                Judgement judgement) {
        List<IdentityCandidate.Evidence> out = new ArrayList<>(3);
        if (judgement.level() == BaselineLevel.PID3_EXACT) {
            out.add(new IdentityCandidate.Evidence(IdentityEvidenceField.PATIENT_REF.wire(),
                    "区域平台主键与院内主键字面相同（硬键，基线判定，未经模型）"));
        }
        if (inbound.hasName() && p.name() != null && !p.name().isEmpty()) {
            out.add(new IdentityCandidate.Evidence(IdentityEvidenceField.NAME.wire(),
                    IdentityText.nameVariantNote(inbound.name(), p.name()) + "（本机核对，未经模型）"));
        }
        if (inbound.hasBirthDate() && p.birthDate() != null && !p.birthDate().isEmpty()) {
            boolean same = IdentityText.sameBirthDate(inbound.birthDate(), p.birthDate());
            out.add(new IdentityCandidate.Evidence(IdentityEvidenceField.BIRTH_DATE.wire(),
                    (same ? "出生日期与院内记录一致" : "出生日期与院内记录不一致") + "（本机核对，未经模型）"));
        }
        return out;
    }

    /** 一条记录的判定草稿（压分前）。 */
    private static final class Judgement {

        private final BaselineLevel level;
        private final int nameDistance;
        private final double score;
        private final String note;

        private Judgement(BaselineLevel level, int nameDistance, double score, String note) {
            this.level = level;
            this.nameDistance = nameDistance;
            this.score = score;
            this.note = note;
        }

        static Judgement of(BaselineLevel level, int nameDistance, String note) {
            return new Judgement(level, nameDistance, level.rankScore(), note);
        }

        /** 压分但不改档位：证据没消失，只是先别排在前面。 */
        Judgement penalised(double score, String note) {
            return new Judgement(level, nameDistance, score, note);
        }

        BaselineLevel level() {
            return level;
        }

        int nameDistance() {
            return nameDistance;
        }

        double score() {
            return score;
        }

        String note() {
            return note;
        }
    }

    /** 候选与判定的临时捆绑，只为排序时成对移动。 */
    private record Indexed(IdentityCandidate candidate, BaselineDecision decision) {
    }
}
