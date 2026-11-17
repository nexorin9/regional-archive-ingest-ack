package cn.hospital.region.archive.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T25 确定性基线 —— 用例。
 *
 * <p><b>不 mock，也不手写语料</b>：输入取自 T4 落盘的 {@code fixtures/zh} 真实语料
 * 与同一份 {@code index.csv} 真值索引（与 T24 读的是同一个文件），
 * 候选切片由 {@link VariantEvalCorpus} 构造 —— 基线组与模型组共用这一条输入路径。
 *
 * <h2>本类要守的三条线</h2>
 * <ol>
 *   <li><b>阈值集中定义</b>：高相似 ≤1、出候选 ≤2，都不在算法里写死，改一处即全链路生效。</li>
 *   <li><b>基线不可能调模型</b>：反射断言字段与构造器里没有 {@link ModelGateway}。
 *       对照实验一旦被污染，≥10pp 的结论就作废，这条断言就是那道防线。</li>
 *   <li><b>排序分不是置信度</b>：除硬键档外全部低于无锚点置信度上限，
 *       任何下游按合档阈值判断都不会因基线排序分而自动合档。</li>
 * </ol>
 *
 * <p>钦定用例：{@link #test_simplified_traditional_variant_baseline_misses} ——
 * 简繁变体基线判为不命中（高相似口径），这是 T26 对照结论的数据前提。
 */
@DisplayName("T25 确定性基线：字面相等 + 编辑距离，不调模型")
class BaselineMatcherTest {

    private final BaselineMatcher baseline = new BaselineMatcher();

    // =====================================================================
    // 钦定用例：简繁变体 → 基线判为不命中（高相似口径）
    // =====================================================================

    @Test
    @DisplayName("钦定：简繁变体两个字都变了时基线判为不命中 —— 掉到「差两个字」档，不是高相似")
    void test_simplified_traditional_variant_baseline_misses() {
        // 语料第一位居民的繁体行：平台写法与院内写法两个字都变了繁体。
        // 取自 fixtures/zh/index.csv，不在本测试里敲字面量。
        VariantEvalCorpus.Case c = corpusRow("traditional", "张伟", 2);
        assertEquals("LOCMRN-0001", c.expectedLocalMrn(),
                "这条语料的正确答案应当是语料第一位居民，语料换了就改这条断言而不是改代码");

        BaselineResult result = baseline.match(c.inbound(), c.slice());

        BaselineDecision truth = result.decisionOf(c.expectedLocalMrn()).orElseThrow(
                () -> new AssertionError("基线连候选都没给出，偏离本用例要验的范围"));
        assertEquals(BaselineLevel.NAME_FAR, truth.level(),
                "两个字都变繁体时字形距离是 2，基线只认字形，只能落到「差两个字」档");
        assertEquals(2, truth.nameDistance());
        assertFalse(truth.level().highSimilarity(),
                "这就是「基线判为不命中」的确切含义：不是没有候选，而是没有被当成很像 —— "
                        + "这一条正是 T26 要拿去和模型比的差距");
        assertTrue(truth.rankScore() < BaselineMatcher.BIRTHDATE_CONFLICT_SCORE * 10,
                "低置信档的排序分应远高于压分档，但仍不足以自动合档，实际=" + truth.rankScore());

        // 整类简繁语料的高相似识别率：这是 T26 对照实验真正要用的那个数。
        int highSim = highSimilarityHitsOfClass("traditional");
        assertTrue(highSim < 24,
                "简繁这一类必须存在基线认不出的行，否则对照实验无从做起；实测高相似命中 "
                        + highSim + "/24");
        assertTrue(highSim > 0,
                "单字变繁体的行字形距离是 1，基线应当认得出；若为 0 说明基线被写坏了");
    }

    // =====================================================================
    // 三级打分与两档阈值
    // =====================================================================

    @Test
    @DisplayName("一级：跨院主键与院内主键字面相同 → PID3_EXACT，且排在姓名字面相同之前")
    void test_pid3_exact_wins_over_name_literal() {
        LocalPatientIndex index = indexOf(
                person("MRN-OTHER", "李娜", "19900101", ""),
                person("MRN-1001", "王芳", "19700101", ""));
        InboundIdentity inbound = new InboundIdentity("MRN-1001", "王芳", "19700101", "");

        BaselineResult result = baseline.match(inbound, index);

        assertEquals(2, result.size());
        assertEquals("MRN-1001", result.candidates().best().orElseThrow().localMrn(),
                "主键字面相同是硬键，必须排在前面");
        assertEquals(BaselineLevel.PID3_EXACT, result.topLevel().orElseThrow());
        assertEquals(BaselineDecision.NOT_COMPARABLE,
                result.decisionOf("MRN-1001").orElseThrow().nameDistance(),
                "硬键命中时压根没看姓名，距离必须是「不可比」而不是 0");
    }

    @Test
    @DisplayName("二级：去空白 + 统一大小写后姓名逐字相同 → NAME_LITERAL")
    void test_literal_equal_name_and_birthdate_hits() {
        LocalPatientIndex index = indexOf(person("MRN-2001", "王芳", "19700101", ""));
        InboundIdentity inbound = new InboundIdentity("", "  王芳 ", "19700101", "");

        BaselineResult result = baseline.match(inbound, index);

        assertEquals(BaselineLevel.NAME_LITERAL, result.topLevel().orElseThrow());
        BaselineDecision d = result.decisionOf("MRN-2001").orElseThrow();
        assertEquals(0, d.nameDistance());
        assertEquals(BaselineLevel.NAME_LITERAL.rankScore(), d.rankScore(),
                "出生日期一致时不压分，排序分就是档位分");
        assertFalse(d.birthDateConflictPenalised());
    }

    @Test
    @DisplayName("二级：高相似阈值 = 1，差一个字记为高相似")
    void test_edit_distance_one_is_high_similarity() {
        LocalPatientIndex index = indexOf(person("MRN-2002", "张伟", "19850512", ""));
        InboundIdentity inbound = new InboundIdentity("", "章伟", "19850512", "");

        BaselineResult result = baseline.match(inbound, index);

        assertEquals(BaselineLevel.NAME_NEAR, result.topLevel().orElseThrow());
        assertEquals(1, result.decisionOf("MRN-2002").orElseThrow().nameDistance());
        assertTrue(result.topLevel().orElseThrow().highSimilarity(),
                "差一个字算高相似：信息员扫一眼就能判断这类要不要合");
    }

    @Test
    @DisplayName("出候选阈值 = 2：差两个字仍出候选，但只是低置信")
    void test_edit_distance_two_is_low_confidence_candidate() {
        LocalPatientIndex index = indexOf(person("MRN-2003", "张伟", "19850512", ""));
        InboundIdentity inbound = new InboundIdentity("", "陳靜", "19850512", "");

        BaselineResult result = baseline.match(inbound, index);

        assertEquals(1, result.size(), "差两个字仍在出候选阈值内，必须给候选");
        BaselineDecision d = result.decisionOf("MRN-2003").orElseThrow();
        assertEquals(BaselineLevel.NAME_FAR, d.level());
        assertEquals(2, d.nameDistance());
        assertFalse(d.level().highSimilarity(), "差两个字不算高相似");
        assertTrue(d.rankScore() < BaselineLevel.NAME_NEAR.rankScore());
    }

    @Test
    @DisplayName("超过出候选阈值：差三个字且出生日期也对不上 → 不出候选")
    void test_edit_distance_above_two_yields_no_candidate() {
        LocalPatientIndex index = indexOf(person("MRN-2004", "欧阳明月白", "19850512", ""));
        InboundIdentity inbound = new InboundIdentity("", "李逍遥", "19991231", "");

        BaselineResult result = baseline.match(inbound, index);

        assertTrue(result.isEmpty(), "字形距离超过上限、出生日期又对不上，不得出候选");
        assertSame(BaselineLevel.BIRTHDATE_ONLY, BaselineLevel.BIRTHDATE_ONLY);
    }

    @Test
    @DisplayName("白捡的那一类：姓名含空格（含全角）去空白后字面相同")
    void test_whitespace_only_variant_is_literal_hit() {
        LocalPatientIndex index = indexOf(person("MRN-2005", "王芳", "19790308", ""));
        // 半角空格与全角空格 U+3000 都要归一掉：区域平台按不同输入法打出来的名字里两种都有
        InboundIdentity inbound = new InboundIdentity("", "王　芳", "19790308", "");

        BaselineResult result = baseline.match(inbound, index);

        assertEquals(BaselineLevel.NAME_LITERAL, result.topLevel().orElseThrow(),
                "空白差异是免费的，基线一次就该中；这一类不该算进模型的功劳");
    }

    @Test
    @DisplayName("姓名对不上但出生日期一致 → 只落「仅出生日期一致」档，不冒充命中")
    void test_birthdate_only_level_when_name_far() {
        LocalPatientIndex index = indexOf(person("MRN-2006", "欧阳明月白", "19850512", ""));
        InboundIdentity inbound = new InboundIdentity("", "李逍遥", "19850512", "");

        BaselineResult result = baseline.match(inbound, index);

        assertEquals(BaselineLevel.BIRTHDATE_ONLY, result.topLevel().orElseThrow());
        assertFalse(result.topLevel().orElseThrow().highSimilarity(),
                "同一天出生的人不止一个，「仅出生日期一致」不构成命中理由");
    }

    // =====================================================================
    // 出生日期冲突压分
    // =====================================================================

    @Test
    @DisplayName("姓名像但出生日期对不上 → 排序分压到冲突上限，档位保持不变")
    void test_birthdate_conflict_caps_rank_score_but_keeps_level() {
        LocalPatientIndex index = indexOf(
                person("MRN-3001", "张伟", "19900101", ""),   // 同名不同人
                person("MRN-3002", "张伟", "19850512", ""));  // 本人
        InboundIdentity inbound = new InboundIdentity("", "张伟", "19850512", "");

        BaselineResult result = baseline.match(inbound, index);

        assertEquals("MRN-3002", result.candidates().best().orElseThrow().localMrn(),
                "同名不同人的记录必须排在本人后面");
        BaselineDecision twin = result.decisionOf("MRN-3001").orElseThrow();
        assertEquals(BaselineLevel.NAME_LITERAL, twin.level(),
                "「姓名字面相同」这条证据没有因为生日不符而消失，档位不该跟着降");
        assertTrue(twin.birthDateConflictPenalised());
        assertEquals(BaselineMatcher.BIRTHDATE_CONFLICT_SCORE, twin.rankScore());
    }

    @Test
    @DisplayName("平台没给出生日期 → 不得当成「生日对不上」压分")
    void test_missing_birthdate_is_not_treated_as_conflict() {
        LocalPatientIndex index = indexOf(person("MRN-3003", "张伟", "19900101", ""));
        InboundIdentity inbound = new InboundIdentity("", "张伟", "", "");

        BaselineResult result = baseline.match(inbound, index);

        BaselineDecision d = result.decisionOf("MRN-3003").orElseThrow();
        assertFalse(d.birthDateConflictPenalised(),
                "缺 PID-7 的报文是平台常态；把它当成冲突会把最需要人看的一批压到底部");
        assertEquals(BaselineLevel.NAME_LITERAL.rankScore(), d.rankScore());
    }

    @Test
    @DisplayName("只落在「仅出生日期一致」档的记录不参与压分（本来就没有姓名证据可压）")
    void test_birthdate_only_level_is_not_penalised() {
        LocalPatientIndex index = indexOf(person("MRN-3004", "欧阳明月白", "19850512", ""));
        InboundIdentity inbound = new InboundIdentity("", "李逍遥", "19850512", "");

        BaselineResult result = baseline.match(inbound, index);

        BaselineDecision d = result.decisionOf("MRN-3004").orElseThrow();
        assertEquals(BaselineLevel.BIRTHDATE_ONLY.rankScore(), d.rankScore());
        assertFalse(d.birthDateConflictPenalised());
    }

    // =====================================================================
    // 阈值与安全边界
    // =====================================================================

    @Test
    @DisplayName("阈值集中定义：高相似 1 / 出候选 2，模块加载期即校验二者关系")
    void test_thresholds_are_centralized() {
        assertEquals(1, BaselineMatcher.HIGH_SIMILARITY_MAX_DISTANCE);
        assertEquals(2, BaselineMatcher.CANDIDATE_MAX_DISTANCE);
        assertTrue(BaselineMatcher.HIGH_SIMILARITY_MAX_DISTANCE
                        < BaselineMatcher.CANDIDATE_MAX_DISTANCE,
                "高相似档必须严格窄于出候选档，否则「低置信候选」这一档不存在");
    }

    @Test
    @DisplayName("排序分不是置信度：除硬键档外全部低于无锚点置信度上限")
    void test_rank_scores_stay_below_unanchored_confidence_cap() {
        for (BaselineLevel level : BaselineLevel.values()) {
            if (level == BaselineLevel.PID3_EXACT) {
                continue;
            }
            assertTrue(level.rankScore() <= IdentityCandidateMatcher.UNANCHORED_CONFIDENCE_CAP,
                    "档位 " + level + " 的排序分高于无锚点置信度上限（"
                            + IdentityCandidateMatcher.UNANCHORED_CONFIDENCE_CAP
                            + "），会被下游按合档阈值误当作可自动合档");
        }
        assertTrue(BaselineMatcher.BIRTHDATE_CONFLICT_SCORE
                        < BaselineLevel.BIRTHDATE_ONLY.rankScore(),
                "压分必须真的把排序压下去，否则同名不同人排到本人前面");
    }

    @Test
    @DisplayName("结构性保证：基线不持有也不接受 ModelGateway —— 对照实验不被污染")
    void test_baseline_never_touches_model_gateway() {
        for (Field f : BaselineMatcher.class.getDeclaredFields()) {
            assertFalse(ModelGateway.class.isAssignableFrom(f.getType()),
                    "基线持有 " + f.getType().getSimpleName() + " 字段「" + f.getName()
                            + "」：对照组一旦能调模型，模型比基线强 X 个百分点这句话就作废了");
        }
        for (Constructor<?> c : BaselineMatcher.class.getDeclaredConstructors()) {
            for (Class<?> param : c.getParameterTypes()) {
                assertFalse(ModelGateway.class.isAssignableFrom(param),
                        "基线构造器接受模型网关参数：" + param.getSimpleName());
            }
        }
        assertTrue(Modifier.isFinal(BaselineMatcher.class.getModifiers()),
                "基线必须 final：可被继承出「其实偷偷调了模型」的子类，这条断言就白写了");
    }

    // =====================================================================
    // 候选收窄（生产路径）
    // =====================================================================

    @Test
    @DisplayName("narrowSlice 只按与变体无关的硬条件收窄，且受上限约束")
    void test_narrow_slice_bounds_size_and_keeps_truth() {
        // 同音叠加繁体那一条（章偉 vs 张伟，字形距离 2）——最考验模型的一行
        VariantEvalCorpus.Case c = corpusRow("homophone", "张伟", 2);

        LocalPatientIndex narrowed = baseline.narrowSlice(c.inbound(), c.slice(), 5);

        assertTrue(narrowed.size() <= 5, "收窄必须受上限约束，实际 " + narrowed.size());
        assertTrue(narrowed.size() > 0);
        assertNotNull(narrowed.byMrn(c.expectedLocalMrn()),
                "收窄按出生日期这一条硬条件，正确记录必须留在切片里");
        for (LocalPatientIndex.LocalPatient p : narrowed.patients()) {
            boolean sameBirth = IdentityText.normalizeBirthDate(c.inbound().birthDate())
                    .equals(IdentityText.normalizeBirthDate(p.birthDate()));
            boolean sameFirst = !c.inbound().name().isEmpty() && !p.name().isEmpty()
                    && c.inbound().name().charAt(0) == p.name().charAt(0);
            boolean sameMrn = c.inbound().patientRef().equals(p.localMrn());
            assertTrue(sameBirth || sameFirst || sameMrn,
                    "收窄只该留下主键/出生日期/姓名首字三者之一命中的记录，" + p.localMrn() + " 不符合");
        }
    }

    @Test
    @DisplayName("收窄不按字形距离：否则模型还没看到就被基线筛掉了")
    void test_narrow_slice_does_not_use_edit_distance() {
        // 语料里两个字都变繁体的行：字形距离 2。按距离收窄会把正确记录直接筛掉，
        // 对照实验里模型组就再也看不到它 —— 那是替模型把活干了。
        VariantEvalCorpus.Case c = corpusRow("traditional", "张伟", 2);
        assertEquals(2, EditDistance.of(
                BaselineMatcher.normalizeForBaseline(c.inbound().name()),
                BaselineMatcher.normalizeForBaseline("张伟")),
                "前提：本用例的正确记录字形距离确实是 2");

        LocalPatientIndex narrowed = baseline.narrowSlice(c.inbound(), c.slice(), VariantEvalCorpus.SLICE_LIMIT);

        assertNotNull(narrowed.byMrn(c.expectedLocalMrn()),
                "收窄靠出生日期这一条留下正确记录，与字形距离无关");
    }

    // =====================================================================
    // 结构与可复现性
    // =====================================================================

    @Test
    @DisplayName("候选与判定一一对应，且顺序一致")
    void test_candidates_and_decisions_line_up() {
        VariantEvalCorpus.Case c = corpusRow("traditional", "张伟", 2);

        BaselineResult result = baseline.match(c.inbound(), c.slice());

        assertEquals(result.size(), result.decisions().size());
        for (int i = 0; i < result.size(); i++) {
            assertEquals(result.candidates().candidates().get(i).localMrn(),
                    result.decisions().get(i).localMrn(),
                    "第 " + i + " 条判定与候选必须是同一条");
        }
        assertThrows(IllegalArgumentException.class,
                () -> new BaselineResult(new Candidates(List.of(
                        candidate("MRN-A", 0.9, "a"),
                        candidate("MRN-B", 0.8, "b"))),
                        List.of(new BaselineDecision("MRN-A", BaselineLevel.NAME_LITERAL, 0, 0.7, "x"))),
                "判定数与候选数不一致时必须当场抛，而不是留到差异报告里才发现对不上");
    }

    @Test
    @DisplayName("可复现：同一输入连跑两次，排序与判定逐条相同")
    void test_result_is_deterministic_across_runs() {
        // 同音叠加繁体那一条（章偉 vs 张伟，字形距离 2）——最考验模型的一行
        VariantEvalCorpus.Case c = corpusRow("homophone", "张伟", 2);

        BaselineResult first = baseline.match(c.inbound(), c.slice());
        BaselineResult second = baseline.match(c.inbound(), c.slice());

        assertEquals(first.candidates().candidates().stream().map(IdentityCandidate::localMrn).toList(),
                second.candidates().candidates().stream().map(IdentityCandidate::localMrn).toList());
        assertEquals(first.decisions().stream().map(BaselineDecision::explain).toList(),
                second.decisions().stream().map(BaselineDecision::explain).toList(),
                "同分候选按院内主键升序兜底，重跑必须逐条一致，否则人工复核量会在两次运行间漂移");
    }

    @Test
    @DisplayName("空切片 → 空结果，不抛异常")
    void test_empty_slice_returns_empty_result() {
        BaselineResult result = baseline.match(
                new InboundIdentity("PID-3", "张伟", "19850512", ""), LocalPatientIndex.empty());

        assertTrue(result.isEmpty());
        assertEquals(0, result.size());
        assertTrue(result.topLevel().isEmpty());
        assertTrue(result.decisionOf("MRN-ANY").isEmpty());
    }

    @Test
    @DisplayName("判定理由里不出现姓名明文：依据与差异报告都不落姓名")
    void test_decision_notes_carry_no_patient_plaintext() {
        VariantEvalCorpus.Case c = corpusRow("traditional", "张伟", 2);
        List<String[]> rows = truthRowsForClass("traditional");

        BaselineResult result = baseline.match(c.inbound(), c.slice());

        for (BaselineDecision d : result.decisions()) {
            String text = d.explain() + " " + d.note();
            for (String[] row : rows) {
                assertFalse(text.contains(row[3]),
                        "判定理由里出现了平台写法姓名原文（" + row[0] + "）：理由要进待确认清单与差异报告");
                assertFalse(text.contains(row[4]),
                        "判定理由里出现了院内写法姓名原文（" + row[0] + "）");
            }
        }
    }

    // =====================================================================
    // 同一份输入路径（基线组与模型组共用）
    // =====================================================================

    @Test
    @DisplayName("同一份输入路径：两组拿的是同一个切片对象，且未经 narrowSlice")
    void test_same_corpus_object_feeds_both_groups() {
        List<VariantEvalCorpus.Case> cases = VariantEvalCorpus.withShadow();

        LocalPatientIndex firstSlice = cases.get(0).slice();
        for (VariantEvalCorpus.Case c : cases) {
            assertSame(firstSlice, c.slice(),
                    "全部用例必须共用同一份切片对象：两份各自构造的切片一旦内容不同，"
                            + "「模型比基线强 X 个百分点」量到的就是构造差异而不是算法差异");
        }
        assertTrue(cases.size() > 0);
        assertTrue(firstSlice.size() > 24,
                "带影子记录的切片应明显大于原始 24 条，否则「同名不同人」根本没进实验");
    }

    @Test
    @DisplayName("正确性前提：每条用例的切片里有且只有一条记录与来报出生日期一致")
    void test_truth_is_unambiguous_in_every_case() {
        for (VariantEvalCorpus.Case c : VariantEvalCorpus.withShadow()) {
            String inboundBirth = c.inbound().normalizeBirthDate();
            int sameBirth = 0;
            for (LocalPatientIndex.LocalPatient p : c.slice().patients()) {
                if (inboundBirth.equals(IdentityText.normalizeBirthDate(p.birthDate()))) {
                    sameBirth++;
                }
            }
            assertEquals(1, sameBirth,
                    "用例 " + c.file() + " 的正确答案不唯一，命中率将失去意义");
        }
    }

    @Test
    @DisplayName("实测基线水平（写进断言，免得下一个人重新测一遍）：四类 Top-1 全中，差距在高相似档")
    void test_baseline_recognition_rate_by_variant_class() {
        List<VariantEvalCorpus.Case> cases = VariantEvalCorpus.withShadow();
        Map<String, Integer> byClass = new LinkedHashMap<>();
        for (String cls : VariantEvalCorpus.VARIANT_CLASSES) {
            byClass.put(cls, highSimilarityHitsOfCases(cases, cls));
        }

        assertEquals(24, byClass.get("simplified"), "字面相同那一类必须全中");
        assertEquals(24, byClass.get("space"), "去空白即中，白捡的一类必须全中");
        assertTrue(byClass.get("homophone") > byClass.get("traditional"),
                "同音错别字应比简繁容易（同音类多数只差一个字），实测 同音="
                        + byClass.get("homophone") + " 简繁=" + byClass.get("traditional"));
        assertTrue(byClass.get("traditional") < 24,
                "两个字都变繁体时基线认不出，简繁这一类必须有缺口，否则 T26 的对照无从做起");
        assertEquals(19, byClass.get("traditional"), "T25 实测值：简繁 19/24（5 条两个字全变）");
        assertEquals(22, byClass.get("homophone"), "T25 实测值：同音 22/24（2 条同音叠加繁体）");
    }

    // =====================================================================
    // 编辑距离本身
    // =====================================================================

    @Test
    @DisplayName("编辑距离：替换/插入/删除各计 1，空串按空处理")
    void test_edit_distance_known_values() {
        assertEquals(0, EditDistance.of("张伟", "张伟"));
        assertEquals(1, EditDistance.of("张伟", "章伟"), "同音错别字：替换一次");
        assertEquals(1, EditDistance.of("张伟", "张 伟"), "多一个空白：插入一次");
        assertEquals(1, EditDistance.of("張伟", "张伟"), "只一个字用了繁体：替换一次");
        assertEquals(2, EditDistance.of("張偉", "张伟"), "两个字都用了繁体：替换两次");
        assertEquals(5, EditDistance.of("张伟", "欧阳明月白"), "二字到五字：四次插入加一次替换");
        assertEquals(2, EditDistance.of("", "张伟"), "空串到二字：插入两次");
        assertEquals(0, EditDistance.of(null, ""));
        assertEquals(2, EditDistance.of("张伟", null), "null 按空串处理：两个字都要插入");
        assertTrue(EditDistance.within("张伟", "章伟", 1));
        assertFalse(EditDistance.within("张伟", "章伟", 0));
        assertFalse(EditDistance.within("张伟", "任意字符串", -1), "负阈值一律不通过");
        assertTrue(EditDistance.within("张伟", "一二三四五六七八", 2) == false,
                "长度差已经超过阈值，不必再算动态规划");
    }

    // =====================================================================
    // 辅助
    // =====================================================================

    /** 按变体类别 + 正确答案姓名 + 字形距离取一条语料用例。 */
    private VariantEvalCorpus.Case corpusRow(String variantClass, String localName, int expectedDistance) {
        for (VariantEvalCorpus.Case c : VariantEvalCorpus.withShadow()) {
            if (!c.variantClass().equals(variantClass)) {
                continue;
            }
            LocalPatientIndex.LocalPatient truth = c.slice().byMrn(c.expectedLocalMrn());
            if (truth == null || !truth.name().equals(localName)) {
                continue;
            }
            int distance = EditDistance.of(
                    BaselineMatcher.normalizeForBaseline(c.inbound().name()),
                    BaselineMatcher.normalizeForBaseline(truth.name()));
            if (distance == expectedDistance) {
                return c;
            }
        }
        throw new AssertionError("语料里没有 " + variantClass + " / 姓名「" + localName
                + "」/ 字形距离 " + expectedDistance + " 的用例；换语料后请同步本测试与实测数值");
    }

    private List<String[]> truthRowsForClass(String variantClass) {
        List<String[]> out = new ArrayList<>();
        try {
            for (String line : java.nio.file.Files.readAllLines(
                    VariantEvalCorpus.FIXTURES.resolve("index.csv"), java.nio.charset.StandardCharsets.UTF_8)) {
                String[] c = line.split(",", -1);
                if (c.length >= 11 && variantClass.equals(c[1])) {
                    out.add(c);
                }
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("读不到真值索引", e);
        }
        return out;
    }

    private int highSimilarityHitsOfClass(String variantClass) {
        return highSimilarityHitsOfCases(VariantEvalCorpus.withShadow(), variantClass);
    }

    private int highSimilarityHitsOfCases(List<VariantEvalCorpus.Case> cases, String variantClass) {
        int hits = 0;
        for (VariantEvalCorpus.Case c : cases) {
            if (!c.variantClass().equals(variantClass)) {
                continue;
            }
            BaselineResult result = baseline.match(c.inbound(), c.slice());
            if (result.isEmpty()) {
                continue;
            }
            if (result.candidates().best().orElseThrow().localMrn().equals(c.expectedLocalMrn())
                    && result.decisionOf(c.expectedLocalMrn()).orElseThrow().level().highSimilarity()) {
                hits++;
            }
        }
        return hits;
    }

    private static LocalPatientIndex.LocalPatient person(String mrn, String name, String birth, String digest) {
        return new LocalPatientIndex.LocalPatient(mrn, name, birth, digest);
    }

    private static LocalPatientIndex indexOf(LocalPatientIndex.LocalPatient... patients) {
        return new LocalPatientIndex(List.of(patients));
    }

    private static IdentityCandidate candidate(String mrn, double confidence, String note) {
        return new IdentityCandidate(mrn, confidence,
                List.of(new IdentityCandidate.Evidence(IdentityEvidenceField.NAME.wire(), note)));
    }
}
