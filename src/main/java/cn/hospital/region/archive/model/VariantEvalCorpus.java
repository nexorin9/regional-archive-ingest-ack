package cn.hospital.region.archive.model;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 中文变体评测语料的<b>唯一输入路径</b> —— 基线组与模型组都必须从这里取输入。
 *
 * <h2>为什么必须是同一份</h2>
 * <p>「模型比基线强 X 个百分点」这句话只有在下面四件事<b>完全一致</b>时才有意义：
 * 同一份变体集、同一份候选切片、同一套命中判定、同一套评测口径。
 * 只要有一项是两组各写一份，这句话就是两组各自的实现差异，而不是算法的差异。
 * 所以本类是<b>测试侧的单一事实源</b>：它只负责产出「用例」，不负责跑任何一组；
 * 跑的方式（基线的 {@link BaselineMatcher#match}、模型的
 * {@link IdentityCandidateMatcher#match}）由调用方给，评测口径由本类统一算。
 *
 * <h2>真值来自 T4 落盘的 index.csv，不在本类里手写</h2>
 * <p>列含义：{@code file,class,person_id,remote_name,local_name,birth_date,id_card,pid3,local_mrn,zx_label,local_field}。
 * 其中 {@code remote_name} 是平台写法（变体），{@code local_name} 是院内写法（标准），
 * {@code local_mrn} 是这条报文的正确答案。字面相等与一级匹配命中这些都是从 CSV 读出来的，
 * 不是测试里敲进去的字面量 —— 换语料不用改测试。
 *
 * <h2>候选切片：两组拿同一份，且<b>不经</b> {@link BaselineMatcher#narrowSlice}</h2>
 * <p>切片由本类一次性构造好，两组拿的是<b>同一个对象引用</b>。
 * 特别地，本类<b>不</b>调用 {@code narrowSlice}：那个方法是生产路径上的收窄，
 * 拿它来喂对照组等于让基线先把活干完，再拿剩下的给模型，比出来的不是算法强弱。
 *
 * <h2>影子记录：让「同名不同人」真实存在</h2>
 * <p>切片里除了每位居民自己的院内记录，还为<b>每一条变体报文</b>加一条影子记录：
 * 姓名取该报文的<b>平台写法</b>，出生日期取本人出生日期的<b>相邻年份</b>（另一个人）。
 *
 * <p>这不是为了把基线做难，而是为了让它<b>接近真实</b>。一个社区几万人，
 * 「张伟」重名、「张伟」用繁体登记、「张伟」当年户籍上写错成「章伟」都是常事；
 * 只放 24 个唯一姓名的切片，两组都只是在做「在一堆互不相干的记录里找唯一近似项」，
 * 那种题谁都能做满分，比不出任何东西。
 *
 * <p>影子记录的出生日期取相邻年份，是为了让「出生日期一致」这一条仍然<b>唯一</b>指向本人
 * （24 位居民的出生日期互不相同，构造器会断言这一点），
 * 否则正确答案就不唯一了，命中率也就失去意义。
 *
 * <h2>两种切片配置都要报</h2>
 * <p>{@link #withoutShadow()} 是 T4 原始语料（每类 24 条、切片 24 条、最容易）；
 * {@link #withShadow()} 才是贴近院内规模的形态（切片 120 条）。
 * T26 的对照报告<b>两个都要给</b> —— 只报其中一个，等于让读者没法判断结论有多少来自
 * 「影子记录」这个假设。差异大就说明结论对切片形态敏感，那本身就是一条要写进报告的发现。
 *
 * <h2>实测结论（先写在代码里，免得下一个人再花一轮才发现）</h2>
 * <p>T25 落地时在本语料上实测基线：<b>Top-1 与 Top-3 命中率四类全部 100%</b>，
 * 加不加影子记录都一样。原因是出生日期一致时，即使姓名字形距离到了 2，
 * 正确答案仍然是切片里唯一「不与任何冲突记录同分」的候选；而字面同名的影子记录
 * 被出生日期冲突压分压到 {@link BaselineMatcher#BIRTHDATE_CONFLICT_SCORE}，排不上去。
 * <p>结论：<b>Top-N 命中率在这份语料上已经饱和，分不出模型与基线</b>，
 * T26 若拿它做 ≥10pp 断言会直接判失败，那不是模型没用，是尺子量不动了。
 * 真正分得开的口径是 {@link Metrics#highSimilarityRate()} ——
 * <b>正确记录有没有被当成「很像」排出来</b>（落在 {@link BaselineLevel#highSimilarity()} 的档位）：
 * <pre>
 *   simplified  24/24 = 100.0%    （字面相同，无从区分）
 *   space       24/24 = 100.0%    （去空白即中，白捡）
 *   homophone   22/24 =  91.7%
 *   traditional 19/24 =  79.2%
 * </pre>
 * 简繁那一类掉下去的 5 条正是两个字都变了繁体（字形距离 = 2）的行，
 * 同音那一类掉下去的 2 条是「同音 + 繁体」叠加。模型要在这两档上超过基线，
 * 靠的正是「这两个字是繁简异体 / 读音相同」这类<b>字形距离看不见</b>的判断。
 * <p>所以：{@link #report} 的 {@code levelOf} 参数对<b>模型组传 {@code null}</b>，
 * 模型组没有档位概念，此时本项记 0 并须在对照报告里注明「模型组无档位，此项以人工抽查替代」，
 * <b>不得</b>拿基线的口径去套模型组。
 *
 * <h2>证件摘要一律留空：本类只测「没有硬锚点时」的变体识别</h2>
 * <p>切片与来报都<b>不带</b>证件摘要。原因是评测对象：证件摘要一致时，
 * {@link IdentityCandidateMatcher} 走的是<b>确定性硬锚点</b>分支，
 * 基线与模型在这条路径上做的是同一件事、都必然命中，比不出差异。
 * 真正决定「模型有没有用」的，是<b>平台没给证件</b>时姓名变体还能不能对上 ——
 * 那才是模型不可替代的那一段。真值 CSV 里的 {@code id_card} 列仍读出来放在
 * {@link Case#idCardPlaintext()} 里备查（不进内存比对路径，也不进任何指标），
 * 以便 T26 补一张「有硬锚点时两组都 100%」的对照行。
 */
final class VariantEvalCorpus {

    /** 语料真值索引：与 T4 落盘、T24 读的是同一份文件。 */
    static final Path FIXTURES = Path.of("src", "test", "resources", "fixtures", "zh");

    /** 四类变体（编码类 gb18030/utf8 是同一批人的另一种编码，不另计为一类）。 */
    static final List<String> VARIANT_CLASSES = List.of("simplified", "traditional", "homophone", "space");

    /** Top-N 命中率里的 N：与确认台一次展示的条数一致。 */
    static final int TOP_N = 3;

    /**
     * 收窄上限 —— 与生产默认 {@link BaselineMatcher#DEFAULT_SLICE_SIZE} 同值。
     *
     * <p>只给「断言收窄行为」的用例用。对照实验的切片<b>不</b>经过收窄
     * （见类注释「候选切片：两组拿同一份，且不经 narrowSlice」）。
     */
    static final int SLICE_LIMIT = 40;

    /** 一条评测用例：输入（来报 + 切片）与期望（正确院内主键）。 */
    record Case(String variantClass, String file, InboundIdentity inbound,
                LocalPatientIndex slice, String expectedLocalMrn, String idCardPlaintext) {

        /** 该切片是否带影子记录（切片规模最大的那一份）。 */
        public int sliceSize() {
            return slice.size();
        }
    }

    /** 一组指标：某变体类别下的一组用例跑完之后的口径。 */
    record Metrics(String variantClass, int total, int top1Hits, int top3Hits,
                   int highSimilarityHits, int noCandidate) {

        double top1Rate() {
            return total == 0 ? 0d : (double) top1Hits / total;
        }

        double top3Rate() {
            return total == 0 ? 0d : (double) top3Hits / total;
        }

        double highSimilarityRate() {
            return total == 0 ? 0d : (double) highSimilarityHits / total;
        }

        /** 供报告直接打印的一行；不参与任何断言逻辑。 */
        String toRow() {
            return String.format(Locale.ROOT,
                    "%-12s 样本=%-4d Top1=%-4d(%.1f%%) Top%d=%-4d(%.1f%%) 高相似=%-4d(%.1f%%) 无候选=%d",
                    variantClass, total, top1Hits, top1Rate() * 100, TOP_N, top3Hits, top3Rate() * 100,
                    highSimilarityHits, highSimilarityRate() * 100, noCandidate);
        }
    }

    private VariantEvalCorpus() {
    }

    // ============================================================== 输入构造

    /** T4 原始语料：切片 = 24 位居民各自的院内记录，最容易的一种形态。 */
    static List<Case> withoutShadow() {
        return build(false);
    }

    /** 贴近院内规模：每位居民的院内记录 + 每条变体报文的影子记录，切片 120 条。 */
    static List<Case> withShadow() {
        return build(true);
    }

    private static List<Case> build(boolean shadow) {
        List<String[]> rows = truthRows();
        Map<String, Truth> persons = new LinkedHashMap<>();
        for (String[] c : rows) {
            // file,class,person_id,remote_name,local_name,birth_date,id_card,pid3,local_mrn,zx_label,local_field
            persons.putIfAbsent(c[2], new Truth(c[8], c[4], c[5], c[7]));
        }
        Set<String> trueBirthDates = new LinkedHashSet<>();
        for (Truth t : persons.values()) {
            if (!trueBirthDates.add(t.birthDate)) {
                throw new IllegalStateException("语料真值索引里有两位居民出生日期相同（" + t.birthDate
                        + "），正确答案将不唯一；换语料或改影子记录构造规则，别改评测口径");
            }
        }

        // 切片：真值记录（证件摘要一律留空 —— 见类注释「证件摘要一律留空」）
        List<LocalPatientIndex.LocalPatient> slice = new ArrayList<>();
        for (Truth t : persons.values()) {
            slice.add(new LocalPatientIndex.LocalPatient(t.localMrn, t.localName, t.birthDate, ""));
        }
        // 切片：影子记录（平台写法 + 相邻年份出生日期 = 另一个人）
        if (shadow) {
            int seq = 1;
            for (String[] c : rows) {
                slice.add(new LocalPatientIndex.LocalPatient(
                        String.format(Locale.ROOT, "SHADOW-%04d", seq++),
                        c[3], adjacentYear(c[5]), ""));
            }
        }
        LocalPatientIndex localIndex = new LocalPatientIndex(slice);

        List<Case> cases = new ArrayList<>(rows.size());
        for (String[] c : rows) {
            InboundIdentity inbound = new InboundIdentity(c[7], c[3], c[5], "");
            cases.add(new Case(c[1], c[0], inbound, localIndex, c[8], c[6]));
        }
        assertTruthUnambiguous(cases);
        return Collections.unmodifiableList(cases);
    }

    /**
     * 正确性前提：切片里<b>有且只有一条</b>记录的出生日期与来报一致。
     *
     * <p>不满足这条时「命中率」就没有意义 —— 判对的人可能是碰巧撞上了另一个人。
     * 与其让 T26 拿着一份有歧义的报告去下结论，不如在这里直接炸掉。
     */
    private static void assertTruthUnambiguous(List<Case> cases) {
        for (Case c : cases) {
            String inboundBirth = c.inbound().normalizeBirthDate();
            int sameBirth = 0;
            boolean truthPresent = false;
            for (LocalPatientIndex.LocalPatient p : c.slice().patients()) {
                if (inboundBirth.equals(IdentityText.normalizeBirthDate(p.birthDate()))) {
                    sameBirth++;
                }
                if (p.localMrn().equals(c.expectedLocalMrn())) {
                    truthPresent = true;
                }
            }
            if (!truthPresent) {
                throw new IllegalStateException("切片里没有正确记录 " + c.expectedLocalMrn()
                        + "（用例 " + c.file() + "），评测无从谈起");
            }
            if (sameBirth != 1) {
                throw new IllegalStateException("用例 " + c.file() + " 的切片里有 " + sameBirth
                        + "条记录与来报出生日期一致，正确答案不唯一");
            }
        }
    }

    /** 出生日期相邻年份（保持在 1930–2100 之间即可用）。 */
    private static String adjacentYear(String birthDate) {
        if (IdentityText.normalizeBirthDate(birthDate).length() != 8) {
            throw new IllegalStateException("出生日期不是 8 位：" + birthDate);
        }
        int year = Integer.parseInt(birthDate.substring(0, 4));
        int shifted = year > 1930 ? year - 1 : year + 1;
        return String.format(Locale.ROOT, "%04d%s", shifted, birthDate.substring(4));
    }

    private static List<String[]> truthRows() {
        List<String[]> out = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(FIXTURES.resolve("index.csv"), StandardCharsets.UTF_8)) {
                String[] c = line.split(",", -1);
                boolean variant = c.length >= 11 && !c[0].equals("file") && !c[0].isBlank();
                if (variant && VARIANT_CLASSES.contains(c[1])) {
                    out.add(c);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("读不到变体语料真值索引 " + FIXTURES.resolve("index.csv")
                    + "：先跑 scripts/fetch_fixtures.sh", e);
        }
        if (out.isEmpty()) {
            throw new IllegalStateException("变体语料真值索引为空，评测无从谈起");
        }
        return out;
    }

    // ============================================================== 评测口径

    /**
     * 统一的命中判定 —— 两组都用这一份实现，这是「同一评测口径」的落点。
     *
     * @param candidates 某一组跑出来的候选（基线的 {@link Candidates} 与模型的同型）
     * @return 该条的命中情况
     */
    static Hit judge(Case c, Candidates candidates) {
        List<String> topN = new ArrayList<>(TOP_N);
        for (IdentityCandidate candidate : candidates.top(TOP_N).candidates()) {
            topN.add(candidate.localMrn());
        }
        boolean top1 = !candidates.isEmpty()
                && candidates.best().orElseThrow().localMrn().equals(c.expectedLocalMrn());
        boolean top3 = topN.contains(c.expectedLocalMrn());
        return new Hit(top1, top3, candidates.isEmpty());
    }

    /** 一条的命中情况。 */
    record Hit(boolean top1, boolean top3, boolean noCandidate) {
    }

    /**
     * 跑一组并按类别汇总。
     *
     * @param cases   用例（两组必须传<b>同一份</b>）
     * @param group   跑哪一组；基线传「用例 → 候选」，模型传对应方法引用
     * @param levelOf 该组<b>排在第一的候选</b>处于哪一档，用于统计「高相似」这一项；
     *                模型组没有档位概念，传 {@code null} 表示本项不统计（记 0，并在报告里注明）
     * @return 按变体类别分组的指标，顺序为语料里的出现顺序
     */
    static Map<String, Metrics> report(List<Case> cases, Function<Case, Candidates> group,
                                       Function<Candidates, BaselineLevel> levelOf) {
        Map<String, int[]> buckets = new LinkedHashMap<>();
        for (Case c : cases) {
            Candidates candidates = group.apply(c);
            Hit hit = judge(c, candidates);
            int[] counter = buckets.computeIfAbsent(c.variantClass(), k -> new int[4]);
            counter[0]++;
            if (hit.top1()) {
                counter[1]++;
            }
            if (hit.top3()) {
                counter[2]++;
            }
            if (levelOf != null && hit.top1() && !candidates.isEmpty()
                    && levelOf.apply(candidates).highSimilarity()) {
                counter[3]++;
            }
        }
        Map<String, Metrics> out = new LinkedHashMap<>();
        buckets.forEach((cls, counter) -> out.put(cls,
                new Metrics(cls, counter[0], counter[1], counter[2], counter[3], 0)));
        return Collections.unmodifiableMap(out);
    }

    /**
     * 「正确答案排在<b>高相似档</b>」这一项的单独统计，供 T26 补表用。
     *
     * <p>Top-N 命中率回答「正确答案有没有被列出来」，这一项回答「正确答案有没有被当成
     * <b>很像</b>列出来」。后者才对应信息员的实际体验：确认台上排在第一的那一条，
     * 是「姓名字面相同」还是「差两个字」，他要看的正是这个。
     * 只报 Top-N 的话，一个把几十条都平铺出来的组和一个把正确答案排第一的组看起来一样好。
     */
    static int highSimilarityHits(List<Case> cases, Function<Case, Candidates> group,
                                  Function<Candidates, BaselineLevel> levelOf) {
        if (levelOf == null) {
            throw new IllegalArgumentException("高相似统计需要能取到档位的实现；"
                    + "模型组没有档位概念，本项应传 null 并在报告里注明不统计");
        }
        int hits = 0;
        for (Case c : cases) {
            Candidates candidates = group.apply(c);
            if (candidates.isEmpty()) {
                continue;
            }
            if (candidates.best().orElseThrow().localMrn().equals(c.expectedLocalMrn())
                    && levelOf.apply(candidates).highSimilarity()) {
                hits++;
            }
        }
        return hits;
    }

    private record Truth(String localMrn, String localName, String birthDate, String patientRef) {
    }
}
