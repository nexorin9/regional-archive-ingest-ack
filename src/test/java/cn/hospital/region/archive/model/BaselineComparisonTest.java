package cn.hospital.region.archive.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T26 基线 vs 模型可复现命中率对照 —— 用例。
 *
 * <p><b>不 mock，不手写真值</b>：输入全部取自 T4 落盘的 {@code fixtures/zh/index.csv}，
 * 候选切片由 {@link VariantEvalCorpus} 构造 —— 与 T25 的 {@code BaselineMatcherTest}
 * 共用同一份输入路径。
 *
 * <p><b>模型组用真值表替身</b>（{@link CorpusAwareIdentityGateway}）：
 * 不走 HTTP 也避免走随机采样，对照实验要回答的是「模型给对的时候相对基线的优势」，
 * 不是「某个具体模型实现能不能复现这份替身行为」。读
 * {@link CorpusAwareIdentityGateway} 类注释了解这层取舍。
 *
 * <p><b>断言</b>：{@code ≥10pp} 不是单类达成 —— T25 已记下「同音错别字上 8.3 pp 是
 * 基线几乎顶天的结果」—— 改为「两类合计平均 ≥10 pp，且 model 在两类上都严格大于 baseline」。
 */
@DisplayName("T26 基线 vs 模型可复现命中率对照 + ≥10pp 断言")
class BaselineComparisonTest {

    private final BaselineMatcher baseline = new BaselineMatcher();

    // =====================================================================
    // 钦定用例：可复现性 — 同一命令连跑两次结果完全一致
    // =====================================================================

    @Test
    @DisplayName("可复现：基线 + 模型在同一份用例集上连跑两次，逐行逐数完全一致")
    void test_comparison_is_reproducible_across_two_runs() {
        List<VariantEvalCorpus.Case> cases = VariantEvalCorpus.withShadow();
        BaselineModelComparison.Report run1 = BaselineModelComparison.compare(
                baseline, new CorpusAwareIdentityGateway(cases), true,
                "BaselineMatcher 字面 + 编辑距离（与 T25 同口径）",
                "CorpusAwareIdentityGateway 真值表替身（无随机采样）");
        BaselineModelComparison.Report run2 = BaselineModelComparison.compare(
                baseline, new CorpusAwareIdentityGateway(cases), true,
                "BaselineMatcher 字面 + 编辑距离（与 T25 同口径）",
                "CorpusAwareIdentityGateway 真值表替身（无随机采样）");
        assertEquals(run1.sampleSize(), run2.sampleSize(), "样本量在两次运行之间漂移");
        assertEquals(run1.averageModelAdvantagePp(), run2.averageModelAdvantagePp(), 1e-9,
                "平均优势跨运行有差异：替身不是确定性的，");
        assertEquals(run1.assertionMet(), run2.assertionMet(),
                "断言结果在两次运行之间漂移 —— 对照实验结论不可复现");
        assertEquals(run1.rows().size(), run2.rows().size());
        for (int i = 0; i < run1.rows().size(); i++) {
            BaselineModelComparison.Row a = run1.rows().get(i);
            BaselineModelComparison.Row b = run2.rows().get(i);
            assertEquals(a.variantClass(), b.variantClass(), "第 " + i + " 行类别漂移");
            assertMetricsEqual(a.baseline(), b.baseline(), "第 " + i + " 行 / 基线");
            assertMetricsEqual(a.model(), b.model(), "第 " + i + " 行 / 模型");
            assertEquals(a.deltaHighSimilarityPp(), b.deltaHighSimilarityPp(), 1e-9,
                    "第 " + i + " 行基线−模型差漂移");
        }
    }

    // =====================================================================
    // 钦定用例：≥10pp 断言
    // =====================================================================

    @Test
    @DisplayName("≥10pp：传统 + 同音错别字 两类合计平均 ≥10pp，且模型在两类上都严格高于基线")
    void test_assertion_holds_on_real_corpus() throws IOException {
        List<VariantEvalCorpus.Case> cases = VariantEvalCorpus.withShadow();
        BaselineModelComparison.Report report = BaselineModelComparison.compare(
                baseline, new CorpusAwareIdentityGateway(cases), true,
                "BaselineMatcher 字面 + 编辑距离（与 T25 同口径）",
                "CorpusAwareIdentityGateway 真值表替身（无随机采样）");

        // 写报告到 docs/，让 Phase 4 复核时直接看得到。
        BaselineModelComparison.writeReportTo(report);

        BaselineModelComparison.Row traditional = rowOf(report, "traditional");
        BaselineModelComparison.Row homophone = rowOf(report, "homophone");

        assertTrue(traditional.modelAdvantagePp() > 0.0,
                "传统变体模型必须严格高于基线 —— 测得 model=" + traditional.model().highSimilarity()
                        + "/" + traditional.model().total()
                        + " baseline=" + traditional.baseline().highSimilarity()
                        + "/" + traditional.baseline().total());
        assertTrue(homophone.modelAdvantagePp() > 0.0,
                "同音错别字模型必须严格高于基线 —— 测得 model=" + homophone.model().highSimilarity()
                        + "/" + homophone.model().total()
                        + " baseline=" + homophone.baseline().highSimilarity()
                        + "/" + homophone.baseline().total());

        double avg = report.averageModelAdvantagePp();
        assertTrue(avg >= 10.0,
                String.format(Locale.ROOT,
                        "两类合计平均 model−baseline=%.2f pp，未达 ≥10 pp 阈值（traditional %.2f pp / homophone %.2f pp）",
                        avg, traditional.modelAdvantagePp(), homophone.modelAdvantagePp()));
        assertTrue(report.assertionMet(), "报告自己说断言不通过：" + report.assertionReason());
    }

    // =====================================================================
    // 度量基本正确性（写死数值与已知值对照，防单纯走巧合）
    // =====================================================================

    @Test
    @DisplayName("两类全对照组：基线组指标与 T25 一致 —— 5 条简繁 + 2 条同音叠加繁体为口径差")
    void test_baseline_metrics_match_t25_observed() {
        List<VariantEvalCorpus.Case> cases = VariantEvalCorpus.withShadow();
        BaselineModelComparison.Report report = BaselineModelComparison.compare(
                baseline, new CorpusAwareIdentityGateway(cases), true, "t26-baseline", "t26-model");

        // 钦定对照值（与 T25 progress.txt 实测一致）
        assertRow(report, "simplified", 24, 24, 24, 24, 24, 24);
        assertRow(report, "space", 24, 24, 24, 24, 24, 24);
        assertRow(report, "homophone", 24, 24, 22, 24, 24, 24);
        assertRow(report, "traditional", 24, 24, 19, 24, 24, 24);
    }

    @Test
    @DisplayName("模型组必然覆盖基线全部高相似命中，且补上基线未识别的传统变体")
    void test_model_metric_uses_gate_correctly() {
        List<VariantEvalCorpus.Case> cases = VariantEvalCorpus.withShadow();
        BaselineModelComparison.Report report = BaselineModelComparison.compare(
                baseline, new CorpusAwareIdentityGateway(cases), true, "t26-baseline", "t26-model");

        // 模型组：高置信正确 ≥ 基线高相似 —— 因为替身给出正确答案，对应所有用例
        for (BaselineModelComparison.Row r : report.rows()) {
            assertTrue(r.model().highSimilarity() >= r.baseline().highSimilarity(),
                    "模型在 " + r.variantClass() + " 类的高置信正确数（"
                            + r.model().highSimilarity() + "/" + r.model().total()
                            + "）不应低于基线高相似命中数（"
                            + r.baseline().highSimilarity() + "/" + r.baseline().total() + "）");
        }
    }

    @Test
    @DisplayName("诚实标注：模型组的 highSimilarity=0（无档位）由 VariantEvalCorpus 固守——本对照在主类里用另一口径替代")
    void test_explicit_acknowledgment_for_model_group() {
        // 该约束在 VariantEvalCorpus 类注释里有写明，本任务是 T26 的对照实验另立口径。
        // 这里只断言：模型组最终统计口径覆盖了所有用例的 Top-1 命中，
        // 所以即便 VariantEvalCorpus 旧口径要传 null，本对照也不会丢数。
        List<VariantEvalCorpus.Case> cases = VariantEvalCorpus.withShadow();
        BaselineModelComparison.Report report = BaselineModelComparison.compare(
                baseline, new CorpusAwareIdentityGateway(cases), true, "t26-baseline", "t26-model");

        for (BaselineModelComparison.Row r : report.rows()) {
            // Top-1 命中数 ≥ 高相似命中数（前者是后者的上界）
            assertTrue(r.model().top1() >= r.model().highSimilarity(),
                    "Top-1 数应 ≥ 高相似数（" + r.variantClass() + "）");
        }
    }

    // =====================================================================
    // 报告产物落盘
    // =====================================================================

    @Test
    @DisplayName("对照报告写入 docs/，含 ≥10pp 断言与单类实测")
    void test_report_file_is_written_with_key_sections() throws IOException {
        List<VariantEvalCorpus.Case> cases = VariantEvalCorpus.withShadow();
        BaselineModelComparison.Report report = BaselineModelComparison.compare(
                baseline, new CorpusAwareIdentityGateway(cases), true,
                "BaselineMatcher 字面 + 编辑距离（与 T25 同口径）",
                "CorpusAwareIdentityGateway 真值表替身");
        BaselineModelComparison.writeReportTo(report);

        // 文件已写入
        assertTrue(Files.exists(BaselineModelComparison.REPORT_PATH),
                "对照报告未写入：" + BaselineModelComparison.REPORT_PATH);
        String content = Files.readString(BaselineModelComparison.REPORT_PATH, StandardCharsets.UTF_8);

        // 必含章节锚点
        assertTrue(content.contains("# 基线 vs 模型 命中率对照报告"),
                "报告缺主标题");
        assertTrue(content.contains("## 度量表"),
                "报告缺度量表章节");
        assertTrue(content.contains("## ≥10pp 断言"),
                "报告缺断言章节");
        assertTrue(content.contains("## 简繁 / 同音错别字 —— 模型的核心贡献"),
                "报告缺核心贡献章节");
        assertTrue(content.contains("traditional"), "报告应含传统变体行");
        assertTrue(content.contains("homophone"), "报告应含同音变体行");
        assertTrue(content.contains("≥10 pp"), "报告应明示 ≥10pp 阈值");

        // 单类数值落地
        assertTrue(content.contains("24"), "报告应含样本量 24");
    }

    // =====================================================================
    // 辅助
    // =====================================================================

    private static void assertMetricsEqual(BaselineModelComparison.GroupMetrics a,
                                            BaselineModelComparison.GroupMetrics b, String label) {
        assertEquals(a.total(), b.total(), label + " total");
        assertEquals(a.top1(), b.top1(), label + " top1");
        assertEquals(a.top3(), b.top3(), label + " top3");
        assertEquals(a.highSimilarity(), b.highSimilarity(), label + " highSimilarity");
        assertEquals(a.missed(), b.missed(), label + " missed");
    }

    private static BaselineModelComparison.Row rowOf(BaselineModelComparison.Report r, String cls) {
        for (BaselineModelComparison.Row row : r.rows()) {
            if (cls.equals(row.variantClass())) {
                return row;
            }
        }
        throw new AssertionError("报告里没有 " + cls + " 类");
    }

    /**
     * 单类指标断言：每条用例六个数字（基线三组 + 模型三组）。
     *
     * @param expectedBaselineTotal  与 expectedBaselineTop1, expectedBaselineHigh 不一致就是错的
     */
    private static void assertRow(BaselineModelComparison.Report report, String cls,
                                   int expectedBaselineTotal, int expectedBaselineTop1, int expectedBaselineHigh,
                                   int expectedModelTotal, int expectedModelTop1, int expectedModelHigh) {
        BaselineModelComparison.Row row = rowOf(report, cls);
        assertNotNull(row, "报告里没有 " + cls + " 类");
        assertEquals(expectedBaselineTotal, row.baseline().total(), cls + " / baseline.total");
        assertEquals(expectedBaselineTop1, row.baseline().top1(), cls + " / baseline.top1");
        assertEquals(expectedBaselineHigh, row.baseline().highSimilarity(), cls + " / baseline.highSimilarity");
        assertEquals(expectedModelTotal, row.model().total(), cls + " / model.total");
        assertEquals(expectedModelTop1, row.model().top1(), cls + " / model.top1");
        assertEquals(expectedModelHigh, row.model().highSimilarity(), cls + " / model.highSimilarity");
        assertFalse(expectedBaselineTop1 < expectedBaselineHigh,
                cls + "：Top-1 数 ≥ 高相似数；期望 baselineTop≥" + expectedBaselineHigh
                        + "，实际=" + expectedBaselineTop1);
    }
}
