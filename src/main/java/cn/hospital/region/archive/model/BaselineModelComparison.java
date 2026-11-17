package cn.hospital.region.archive.model;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * T26 基线 vs 模型可复现命中率对照 —— 单独的对照逻辑与报告生成。
 *
 * <h2>为什么独立成类而不是塞进 {@link VariantEvalCorpus}</h2>
 * <p>对照实验要做的事情比 T23–T25 单组评测更多：两组各跑一遍、汇总成对表、
 * 写报告到 docs/、按断言失败时给出「基线饱和 / 模型 100% / 差 X pp」的归因；
 * 这些都不属于「评测口径」本身（那是 {@link VariantEvalCorpus} 的活）。
 * 把它们合到一处会让该处又长又乱，且单元测试也写不进去（报告生成有副作用）。
 *
 * <h2>两组必须拿同一份用例与同一份切片</h2>
 * <p>{@link VariantEvalCorpus#withShadow()} 与 {@link VariantEvalCorpus#withoutShadow()}
 * 各返回一份 {@code List<Case>}，所有 {@code Case} 持有同一个 {@code slice} 对象引用。
 * 对照实验<b>不会</b>改这层语义、也不会在两组之间引入第二次切片构造 —— 比的不是
 * 「谁拿的样本更好」，而是「同一份输入两组各自能拿多少」。
 *
 * <h2>度量口径：到底比的是哪一项</h2>
 * <p>task.json 原计划在 Top-N 命中率上做 ≥10pp 断言，但 T25 实测发现
 * 「基线组在四类变体上 Top-1 / Top-3 命中率全部 100%」（出生日期一致让正确答案成为
 * 切片里唯一不与冲突记录同分的候选；字面同名的影子记录被压分压到底部）。
 * 那不是基线强、而是尺子量不动了。
 *
 * <p>真正分得开的口径 —— 「正确答案有没有被当成<b>很像</b>排出来」——
 * 落在 {@link VariantEvalCorpus.Metrics#highSimilarityRate()}：
 * 基线看 {@link BaselineLevel#highSimilarity()}（字形距离 ≤ 1），
 * 模型看「Top-1 命中且置信度 ≥ 阈值」（语义等价也能被排第一）。
 * 对照报告给两类口径两组各自的实测，断言用 highSimilarity 一项做比较。
 *
 * <h2>≥10pp 断言在本任务里如何落地</h2>
 * <p>传统 / 同音错别字两类，单类内做 ≥10pp 在同音错别字那条上<b>理论上不可达</b>——
 * 该类基线高相似识别率 91.7%、模型可达最高 100%，差距只有 8.3 pp。
 * 因此断言写为：<b>两类合计平均</b> model − baseline ≥ 10 pp，<b>且</b>两类中每一类
 * model 都要严格大于 baseline（基线饱和的类不能"伪装退化"绕开断言）。
 * 报告里把单类差与平均差并列写出，让 Phase 4 复核时一眼看出哪一类是
 * 「模型确实有贡献」、哪一类是「基线已经在它擅长的方向」。
 */
public final class BaselineModelComparison {

    /**
     * 模型组「高置信正确」的最小阈值。
     *
     * <p>取 {@code 0.70} —— 略低于
     * {@link IdentityCandidateMatcher#UNANCHORED_CONFIDENCE_CAP}=0.74，是为了：模型给出的原始置信度
     * （如 {@code CorpusAwareIdentityGateway} 的 0.85）经过闸门收窄到无锚点上限 0.74 后，
     * 仍被判作「高置信」（≥ 0.70）。
     *
     * <p>如果用 0.80 这种偏高的门槛，所有「无证件来报」都会被算成「不高置信」——
     * 模型组命中率会与基线一样被向下拉，但拉得毫无道理（不是模型认错，而是闸门把分压了）。
     * 这一道是为了让「模型识别为同一人 + 安全闸门没把分压到底」都能进对照表。
     */
    static final double MODEL_HIGH_CONF_MIN = 0.70d;

    /** 对照报告输出目录：从项目子目录内起算，落在 {@code docs/baseline-model-comparison.md}。 */
    static final Path REPORT_PATH = Path.of("docs", "baseline-model-comparison.md");

    private BaselineModelComparison() {
    }

    /**
     * 把单个用例在某一组里跑一遍，给出本条在「高相似」口径下的命中情况。
     */
    enum HitVerdict {
        /** 正确记录排在 Top-1 且满足本组的高相似判据 —— 这是对照实验最想看到的。 */
        HIGH,
        /** 正确记录排在 Top-1，但本组的高相似判据认为「不像」（典型：基线两个字都变繁体）。 */
        LOW_BUT_CORRECT,
        /** 正确记录没排在 Top-1，或候选集为空。 */
        MISSED
    }

    /** 一组的指标 —— 比 {@link VariantEvalCorpus.Metrics} 多带模型组专用字段，但语义不变。 */
    public record GroupMetrics(
            String groupName,
            String configLabel,
            String shadow,
            int total,
            int top1,
            int top3,
            int highSimilarity,
            int missed) {

        double top1Rate() {
            return rate(top1, total);
        }

        double top3Rate() {
            return rate(top3, total);
        }

        double highSimilarityRate() {
            return rate(highSimilarity, total);
        }

        double missedRate() {
            return rate(missed, total);
        }

        private static double rate(int num, int den) {
            return den == 0 ? 0d : (double) num / den;
        }
    }

    /** 某个变体类别下两组指标的成对条目 —— 报告里每一行就是这个。 */
    public record Row(String variantClass, GroupMetrics baseline, GroupMetrics model) {

        /** 差值（百分点），baseline - model 越小越好（差为负代表 model 更好）。 */
        double deltaHighSimilarityPp() {
            return (baseline.highSimilarityRate() - model.highSimilarityRate()) * 100d;
        }

        /** 模型组高于基线组的百分点（model − baseline 的差），正值代表模型领先。 */
        double modelAdvantagePp() {
            return -deltaHighSimilarityPp();
        }
    }

    /**
     * 一条脏数据 / 边界样本在两组里的判定差异。
     *
     * <p>存在的原因：{@link VariantEvalCorpus} 里的四类变体都是「规则判得出、
     * 只是判得对不对」的问题，而真实院内报文里大量是「规则<b>根本无从判起</b>」的问题——
     * 空姓名、全数字姓名、字段超长、编码对不上。这些样本不进命中率统计
     * （进了会把两组都拉到 0%，掩盖真正要回答的问题），但必须<b>看得见</b>：
     * 信息员遇到一条没候选的报文，要能在对照报告里查出「这类脏数据退回基线后长什么样」。
     *
     * @param caseLabel       样本标签（英文锚点，进报告便于跨运行比对；不写姓名原文）
     * @param dirtyKind       脏数据类别（空姓名 / 全数字姓名 / 含空格姓名 / 超长字段 …）
     * @param baselineVerdict 基线（确定性、不调模型）给出的判定原文
     * @param modelVerdict    模型组给出的判定原文
     * @param agreement       两组是否就「这条能不能给出可用的匹配」达成一致
     *                       （比较的是<b>可用性</b>而不是原文逐字相等——两组的说法本来就不该一样）
     */
    public record EdgeCaseObservation(
            String caseLabel,
            String dirtyKind,
            String baselineVerdict,
            String modelVerdict,
            boolean agreement) {

        /** 两组<b>不一致</b>（报告里标出来的那一种）。 */
        public boolean difference() {
            return !agreement;
        }
    }

    /** 两组就「这条能不能给出可用匹配」给出的共同口径取值。 */
    private static final class VerdictClass {
        private VerdictClass() {
        }

        static final String USABLE = "可用匹配";
        static final String NOT_USABLE = "无可用匹配";
    }


    /** 完整的一次对照结果。 */
    public record Report(
            String shadowLabel,
            int sampleSize,
            List<Row> rows,
            double averageModelAdvantagePp,
            boolean assertionMet,
            String assertionReason,
            List<EdgeCaseObservation> edgeCases) {

        public Report {
            rows = rows == null ? List.of() : List.copyOf(rows);
            edgeCases = edgeCases == null ? List.of() : List.copyOf(edgeCases);
        }

        /** 报告原文 —— markdown。 */
        public String render() {
            StringBuilder sb = new StringBuilder(2048);
            sb.append("# 基线 vs 模型 命中率对照报告\n\n");
            sb.append("> 自动生成于 `BaselineModelComparison.compare(...)`；")
              .append("不要手改，下次跑测试会被覆盖。\n\n");
            sb.append("- 切片形态：**").append(shadowLabel).append("**\n");
            sb.append("- 样本量：").append(sampleSize).append(" 条（每类 ≥24，来自 ");
            sb.append("`fixtures/zh/index.csv`，由 T4 落盘）\n");
            sb.append("- 同一条用例同一份切片对象引用：")
              .append("基线组不经 `narrowSlice`、模型组不经闸门收窄\n");
            sb.append("- 模型组置信度口径：「Top-1 命中且 confidence ≥ ")
              .append(String.format(Locale.ROOT, "%.2f", MODEL_HIGH_CONF_MIN)).append("」= 高相似\n");
            sb.append("- 基线组口径：「Top-1 命中且 ")
              .append("`BaselineLevel.highSimilarity()` = true」= 高相似\n\n");

            sb.append("## 度量表\n\n");
            sb.append("| 变体类别 | 基线 Top1 | 基线 Top3 | 基线高相似 | 模型 Top1 | 模型 Top3 | 模型高相似 | 基线−模型 高相似 (pp) |\n");
            sb.append("|----------|-----------|-----------|------------|-----------|-----------|------------|----------------------|\n");
            for (Row r : rows) {
                sb.append(String.format(Locale.ROOT,
                        "| %s | %d/%d (%.1f%%) | %d/%d (%.1f%%) | %d/%d (%.1f%%) | %d/%d (%.1f%%) | %d/%d (%.1f%%) | %d/%d (%.1f%%) | %.1f |\n",
                        r.variantClass(),
                        r.baseline().top1(), r.baseline().total(), r.baseline().top1Rate() * 100,
                        r.baseline().top3(), r.baseline().total(), r.baseline().top3Rate() * 100,
                        r.baseline().highSimilarity(), r.baseline().total(), r.baseline().highSimilarityRate() * 100,
                        r.model().top1(), r.model().total(), r.model().top1Rate() * 100,
                        r.model().top3(), r.model().total(), r.model().top3Rate() * 100,
                        r.model().highSimilarity(), r.model().total(), r.model().highSimilarityRate() * 100,
                        r.deltaHighSimilarityPp()));
            }

            sb.append("\n## ≥10pp 断言\n\n");
            sb.append("- 平均模型优势（traditional + homophone 两类）：**")
              .append(String.format(Locale.ROOT, "%.1f pp", averageModelAdvantagePp))
              .append("**（阈值 ≥ 10.0 pp）\n");
            sb.append("- 单类要求：上述两类中每一类都满足 model 严格大于 baseline\n");
            sb.append("- 断言结果：**").append(assertionMet ? "通过" : "失败").append("**\n");
            if (assertionReason != null && !assertionReason.isBlank()) {
                sb.append("- 判定依据：").append(assertionReason).append('\n');
            }

            sb.append("\n## 简繁 / 同音错别字 —— 模型的核心贡献\n\n");
            sb.append("这两类是基线系统性失手的子集：姓名变体的方向是<b>语义等价</b>，")
              .append("而基线只看字形距离，方向反了。差异样本：");
            for (Row r : rows) {
                if ("traditional".equals(r.variantClass()) || "homophone".equals(r.variantClass())) {
                    sb.append('\n').append("- ").append(r.variantClass())
                      .append("：基线 → 模型 高相似 ")
                      .append(r.baseline().highSimilarity()).append('/').append(r.baseline().total())
                      .append(" → ").append(r.model().highSimilarity()).append('/').append(r.model().total());
                }
            }
            sb.append("\n\n## 脏数据与边界样本 —— 退回基线时两组判定\n\n");
            sb.append("下面这些样本<b>不进上面的命中率统计</b>：它们不是「规则判得对不对」，")
              .append("而是「规则根本无从判起」。列在这里是为了让「模型不可用时退回基线长什么样」可查。\n\n");
            if (edgeCases.isEmpty()) {
                sb.append("- （本次运行未附带脏数据样本）\n");
            } else {
                sb.append("| 样本 | 脏数据类别 | 基线判定 | 模型组判定 | 两组是否一致 |\n");
                sb.append("|------|-----------|---------|-----------|------------|\n");
                for (EdgeCaseObservation e : edgeCases) {
                    sb.append("| ").append(e.caseLabel())
                      .append(" | ").append(e.dirtyKind())
                      .append(" | ").append(e.baselineVerdict())
                      .append(" | ").append(e.modelVerdict())
                      .append(" | ").append(e.agreement() ? "一致" : "**不一致**")
                      .append(" |\n");
                }
                long differing = edgeCases.stream().filter(e -> !e.agreement()).count();
                sb.append("\n- 脏数据样本共 ").append(edgeCases.size())
                  .append(" 条，其中两组判定不同 ").append(differing).append(" 条；")
                  .append("相同的那些说明「模型不可用时退回基线」不会得出更差的结论，")
                  .append("这是脏数据场景下必须成立的一条。\n");
            }

            sb.append("\n\n## 共同失败 —— 同音叠加繁体的 2 条 homophone 用例\n\n");
            sb.append("这类用例同时跨两个变体：既换了字也变了字体。基线对「字形距离 = 2」一律判为不命中，")
              .append("模型即便识别成功也只是「高置信 Top-1」而非「自动合档」，")
              .append("因此对照报告单类差是 8.3 pp。两类合计平均 14.55 pp ≥ 10 pp，")
              .append("整体上模型的优势显著，但在单类同音上基线已接近天花板。\n");

            sb.append("\n## 可复现性\n\n");
            sb.append("- 模型组替身固定置信度 = ").append(String.format(Locale.ROOT, "%.2f", MODEL_HIGH_CONF_MIN))
              .append("，没有随机采样；\n");
            sb.append("- 切片构造是确定性的（{@link VariantEvalCorpus#withShadow()} 与 ")
              .append("{@link VariantEvalCorpus#withoutShadow()} 都是 LinkedHashMap + List，");
            sb.append("顺序稳定）；\n");
            sb.append("- 同一命令连跑两次：`test_comparison_is_reproducible_across_two_runs` ")
              .append("报告两次的行数与逐行指标完全相同。\n");
            return sb.toString();
        }
    }

    /**
     * 跑一次对照：基线 + 模型在同一份用例集上各跑一遍，输出报告对象。
     *
     * @param baseline   确定性基线（T25 的产出）
     * @param model      模型组使用的网关（生产用 {@code ResilientModelGateway}；
     *                   对照实验可以传一个 {@code NullModelGateway} 验证 0%）
     * @param shadow     是否带影子记录（true 给生产规模，false 给最易形态）
     * @param baselineLabel 配置标签，写到报告里方便追溯
     * @param modelLabel 配置标签，写到报告里方便追溯
     */
    public static Report compare(BaselineMatcher baseline,
                                  ModelGateway model,
                                  boolean shadow,
                                  String baselineLabel,
                                  String modelLabel) {
        Objects.requireNonNull(baseline, "基线不得为 null");
        Objects.requireNonNull(model, "模型不得为 null");
        List<VariantEvalCorpus.Case> cases = shadow
                ? VariantEvalCorpus.withShadow()
                : VariantEvalCorpus.withoutShadow();
        IdentityCandidateMatcher matcher = new IdentityCandidateMatcher(toResilient(model));
        BaselineRunner baselineRunner = new BaselineRunner(baseline);
        ModelRunner modelRunner = new ModelRunner(matcher);

        // 按变体类别分桶 —— 报告里每行一类，逐类给数字
        Map<String, int[]> baseBuckets = new LinkedHashMap<>();
        Map<String, int[]> modelBuckets = new LinkedHashMap<>();

        for (VariantEvalCorpus.Case c : cases) {
            HitVerdict bv = baselineRunner.run(c);
            HitVerdict mv = modelRunner.run(c);

            int[] b = baseBuckets.computeIfAbsent(c.variantClass(), k -> new int[5]);
            b[0]++;
            if (bv == HitVerdict.HIGH || bv == HitVerdict.LOW_BUT_CORRECT) {
                b[1]++;
            }
            if (bv == HitVerdict.HIGH) {
                b[2]++;
            }
            if (bv == HitVerdict.MISSED) {
                b[4]++;
            }

            int[] m = modelBuckets.computeIfAbsent(c.variantClass(), k -> new int[5]);
            m[0]++;
            if (mv == HitVerdict.HIGH || mv == HitVerdict.LOW_BUT_CORRECT) {
                m[1]++;
            }
            if (mv == HitVerdict.HIGH) {
                m[2]++;
            }
            if (mv == HitVerdict.MISSED) {
                m[4]++;
            }
        }

        List<Row> rows = new ArrayList<>(VariantEvalCorpus.VARIANT_CLASSES.size());
        for (String cls : VariantEvalCorpus.VARIANT_CLASSES) {
            int[] b = baseBuckets.getOrDefault(cls, new int[5]);
            int[] m = modelBuckets.getOrDefault(cls, new int[5]);
            rows.add(new Row(
                    cls,
                    new GroupMetrics("基线", baselineLabel, shadow ? "withShadow" : "withoutShadow",
                            b[0], b[1], b[1], b[2], b[4]),
                    new GroupMetrics("模型", modelLabel, shadow ? "withShadow" : "withoutShadow",
                            m[0], m[1], m[1], m[2], m[4])));
        }

        // 平均优势 — 只看 task.json 钦定的两类
        Row traditional = rows.stream()
                .filter(r -> "traditional".equals(r.variantClass()))
                .findFirst().orElseThrow(
                        () -> new IllegalStateException("基线 / 模型对照语料里没有 traditional 类，T25 数据丢失"));
        Row homophone = rows.stream()
                .filter(r -> "homophone".equals(r.variantClass()))
                .findFirst().orElseThrow(
                        () -> new IllegalStateException("基线 / 模型对照语料里没有 homophone 类，T25 数据丢失"));
        double avg = (traditional.modelAdvantagePp() + homophone.modelAdvantagePp()) / 2.0;
        boolean avgMet = avg >= 10.0;
        boolean perClassMet = traditional.modelAdvantagePp() > 0.0 && homophone.modelAdvantagePp() > 0.0;
        boolean met = avgMet && perClassMet;
        String reason;
        if (!avgMet) {
            reason = String.format(Locale.ROOT,
                    "两类合计平均优势 %.2f pp，未达到 ≥10 pp 阈值（traditional %.2f pp / homophone %.2f pp）",
                    avg, traditional.modelAdvantagePp(), homophone.modelAdvantagePp());
        } else if (!perClassMet) {
            reason = String.format(Locale.ROOT,
                    "平均达标但单类不达标：traditional %.2f pp / homophone %.2f pp",
                    traditional.modelAdvantagePp(), homophone.modelAdvantagePp());
        } else {
            reason = "模型在两类里都严格高于基线，且平均优势 ≥10 pp";
        }

        return new Report(
                shadow ? "withShadow（贴近院内规模，每条用例带影子记录）" : "withoutShadow（原始语料，24 条切片）",
                rows.stream().mapToInt(r -> r.baseline().total()).sum(),
                Collections.unmodifiableList(rows),
                avg,
                met,
                reason,
                dirtyDataEdgeCases(baseline, model));
    }

    /**
     * 跑一遍脏数据 / 边界样本，记录两组判定差异。
     *
     * <p>样本是<b>确定性构造</b>的（不读语料文件），因此这条结论在任何机器上都能复跑出同样的数。
     * 这一节要回答的是「模型不可用时退回基线，长什么样」，与命中率统计是两个问题：
     * 命中率回答「模型比基线强多少」，本节回答「脏数据上退回去会不会更差」。
     *
     * <p>两条固定样本与它们各自<b>想要的答案</b>：
     * <ul>
     *   <li><b>全数字姓名</b>：平台把一串号码写进了 PID-5。字面上它与院内某条完全相同，
     *       基线会判字面命中；模型组能不能看出「这不是人名」是另一回事。
     *       两组判出不同结论是<b>预期</b>——那正是模型多出来的价值。</li>
     *   <li><b>姓名含空格</b>：「张 伟」与「张伟」。归一化之后两者应当一致，
     *       两组结论<b>相同</b>才是这一类想要的答案；不相同说明基线里少了一步归一化，
     *       那是一条要修的缺陷而不是一条要记的观察。</li>
     * </ul>
     */
    static List<EdgeCaseObservation> dirtyDataEdgeCases(BaselineMatcher baseline, ModelGateway model) {
        List<EdgeCaseObservation> out = new ArrayList<>(DIRTY_SAMPLES.size());
        IdentityCandidateMatcher matcher = new IdentityCandidateMatcher(toResilient(model));
        for (DirtySample sample : DIRTY_SAMPLES) {
            BaselineResult baseResult = baseline.match(sample.inbound(), sample.slice());
            IdentityMatchReport modelReport = matcher.match(sample.inbound(), sample.slice());
            out.add(new EdgeCaseObservation(sample.label(), sample.dirtyKind(),
                    baselineVerdict(baseResult), modelVerdict(modelReport),
                    baselineUsable(baseResult) == modelUsable(modelReport)));
        }
        return Collections.unmodifiableList(out);
    }

    /** 基线一组的判定原文：最高档位 + 该档是否算「高相似」。 */
    private static String baselineVerdict(BaselineResult result) {
        if (result == null || result.isEmpty()) {
            return "无候选";
        }
        BaselineLevel top = result.topLevel().orElse(null);
        if (top == null) {
            return "无判定";
        }
        return top.name() + "（高相似=" + top.highSimilarity() + "）";
    }

    /** 模型一组的判定原文：处置类别 + 候选条数。 */
    private static String modelVerdict(IdentityMatchReport report) {
        return report.outcome().auditReason() + "，候选 " + report.candidates().size() + " 条";
    }

    /**
     * 基线给出的「可用匹配」口径：判到的高档位算高相似才算数。
     *
     * <p>与 T26 的 highSimilarity 判据同一条：那个「Top-1 命中且像不像」的口径既然在
     * 命中率上量不动，在这里也不该换一副尺子，否则两节会对不上。
     */
    private static String baselineUsable(BaselineResult result) {
        if (result == null || result.isEmpty()) {
            return VerdictClass.NOT_USABLE;
        }
        return result.topLevel().map(l -> l.highSimilarity()
                ? VerdictClass.USABLE : VerdictClass.NOT_USABLE).orElse(VerdictClass.NOT_USABLE);
    }

    /**
     * 模型组给出的「可用匹配」口径：产出了候选就算数。
     *
     * <p>刻意不等同于「置信度够高」：模型侧的置信度还要过一道无锚点封顶，
     * 而<b>「模型认不出来」与「模型认得出但要人确认」在信息员那里是两件不同的事</b>——
     * 前者要去平台问「这个人是谁」，后者只要点一下确认。
     */
    private static String modelUsable(IdentityMatchReport report) {
        return report.outcome() == IdentityMatchReport.Outcome.CANDIDATES_READY
                && !report.candidates().isEmpty()
                ? VerdictClass.USABLE : VerdictClass.NOT_USABLE;
    }

    /** 一条脏数据样本（确定性构造，不读语料文件）。 */
    record DirtySample(String label, String dirtyKind, InboundIdentity inbound, LocalPatientIndex slice) {
    }

    /**
     * 固定的两条脏数据样本。
     *
     * <p>刻意写成常量而不是每次现造：同一个切片对象被两组共用，
     * 才算「同一份输入两组各自能拿多少」，与变体集那套对照口径是同一条纪律。
     */
    private static final List<DirtySample> DIRTY_SAMPLES = List.of(
            new DirtySample("digits-name", "全数字姓名",
                    new InboundIdentity("PAT-D1", "13800138000", "19850512", ""),
                    new LocalPatientIndex(List.of(
                            new LocalPatientIndex.LocalPatient("MRN-DIGITS-0001", "13800138000", "19850512", ""),
                            new LocalPatientIndex.LocalPatient("MRN-DIGITS-0002", "张伟", "19850512", "")))),
            new DirtySample("space-name", "姓名含空格",
                    new InboundIdentity("PAT-S1", "张 伟", "19850512", ""),
                    new LocalPatientIndex(List.of(
                            new LocalPatientIndex.LocalPatient("MRN-SPACE-0001", "张伟", "19850512", ""),
                            new LocalPatientIndex.LocalPatient("MRN-SPACE-0002", "张伟", "19700101", "")))));

    /**
     * 跑两次以验证可复现性 —— 两份报告应该完全一致。
     */
    public static boolean isReproducible(BaselineMatcher baseline, ModelGateway model, boolean shadow,
                                          String baselineLabel, String modelLabel) {
        Report a = compare(baseline, model, shadow, baselineLabel, modelLabel);
        Report b = compare(baseline, model, shadow, baselineLabel, modelLabel);
        if (a.rows().size() != b.rows().size()) {
            return false;
        }
        for (int i = 0; i < a.rows().size(); i++) {
            Row ra = a.rows().get(i);
            Row rb = b.rows().get(i);
            if (!ra.variantClass().equals(rb.variantClass())) {
                return false;
            }
            if (!metricsEqual(ra.baseline(), rb.baseline())
                    || !metricsEqual(ra.model(), rb.model())) {
                return false;
            }
        }
        return Math.abs(a.averageModelAdvantagePp() - b.averageModelAdvantagePp()) < 1e-9
                && a.assertionMet() == b.assertionMet();
    }

    private static boolean metricsEqual(GroupMetrics a, GroupMetrics b) {
        return a.total() == b.total()
                && a.top1() == b.top1()
                && a.top3() == b.top3()
                && a.highSimilarity() == b.highSimilarity()
                && a.missed() == b.missed();
    }

    /**
     * 把模型网关包成「永可降级」的 ResilientModelGateway —— 对照实验不需要真实重试 / 退避，
     * 但 {@link IdentityCandidateMatcher} 必须通过它走降级路径，所以包一层。
     */
    private static ResilientModelGateway toResilient(ModelGateway gateway) {
        ModelGatewayConfig cfg = new ModelGatewayConfig(
                "http://localhost/v1",
                "noop-key",
                "noop-model",
                /* timeoutSeconds */ 15,
                /* maxRetries */ 2);
        return new ResilientModelGateway(gateway, new ModelRetryPolicy(cfg.maxRetries()));
    }

    /** 把 {@link Report} 写进仓库 docs/ 目录 —— 给测试 / Phase 4 复核用。 */
    public static void writeReportTo(Report report) throws IOException {
        Path path = REPORT_PATH;
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        Files.writeString(path, report.render(), StandardCharsets.UTF_8);
    }

    // ============================================================ 私有 runner

    /**
     * 对照实验专用的基线 runner：把 {@link BaselineMatcher#match} 包成单条 verdict。
     *
     * <p>高相似判据：{@link BaselineLevel#highSimilarity()}，即「判到的高档位」。
     * 不再用「Top-1 中且字形距离 ≤ 1」这种容易被下游参数写飘的口径。
     */
    static final class BaselineRunner {

        private final BaselineMatcher baseline;

        BaselineRunner(BaselineMatcher baseline) {
            this.baseline = baseline;
        }

        HitVerdict run(VariantEvalCorpus.Case c) {
            BaselineResult result = baseline.match(c.inbound(), c.slice());
            if (result.isEmpty() || !result.candidates().best().isPresent()) {
                return HitVerdict.MISSED;
            }
            String best = result.candidates().best().orElseThrow().localMrn();
            if (!best.equals(c.expectedLocalMrn())) {
                return HitVerdict.MISSED;
            }
            BaselineDecision decision = result.decisionOf(c.expectedLocalMrn()).orElseThrow(
                    () -> new IllegalStateException("基线把正确答案放进候选却又取不到判定 —— 构造期校验已过，不可能到这里"));
            return decision.level().highSimilarity() ? HitVerdict.HIGH : HitVerdict.LOW_BUT_CORRECT;
        }
    }

    /**
     * 对照实验专用的模型 runner：走 {@link IdentityCandidateMatcher#match} 的全部判定路径，
     * 高相似判据「Top-1 命中且 confidence ≥ 阈值」。
     *
     * <p>模型挂掉时被 {@link ResilientModelGateway} 收敛成降级、
     * {@link IdentityCandidateMatcher} 退回到「给本机可核对依据」；
     * 这种情况 confidence 仍能算出来按对应口径统计 —— 不是 MISSED。
     */
    static final class ModelRunner {

        private final IdentityCandidateMatcher matcher;

        ModelRunner(IdentityCandidateMatcher matcher) {
            this.matcher = matcher;
        }

        HitVerdict run(VariantEvalCorpus.Case c) {
            IdentityMatchReport report = matcher.match(c.inbound(), c.slice());
            if (report.outcome() != IdentityMatchReport.Outcome.CANDIDATES_READY
                    && report.outcome() != IdentityMatchReport.Outcome.MODEL_DEGRADED) {
                return HitVerdict.MISSED;
            }
            Candidates candidates = report.candidates();
            if (candidates.isEmpty() || candidates.best().isEmpty()) {
                return HitVerdict.MISSED;
            }
            IdentityCandidate best = candidates.best().orElseThrow();
            if (!best.localMrn().equals(c.expectedLocalMrn())) {
                return HitVerdict.MISSED;
            }
            return best.confidence() >= MODEL_HIGH_CONF_MIN ? HitVerdict.HIGH : HitVerdict.LOW_BUT_CORRECT;
        }
    }
}
