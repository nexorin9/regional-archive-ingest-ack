package cn.hospital.region.archive.model;

import cn.hospital.region.archive.hl7.CharsetResolver;
import cn.hospital.region.archive.hl7.FrameDecoder;
import cn.hospital.region.archive.hl7.Hl7Message;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T23 Z 段语义对齐 —— 合档闸门的用例。
 *
 * <p><b>不 mock</b>：起的是真的 {@code com.sun.net.httpserver.HttpServer}，
 * 走真的 TCP 与 UTF-8 JSON；对齐输入的一部分直接取自 T4 落盘的
 * {@code fixtures/zh} 真实语料（含 GB18030 与 UTF-8 两套编码），
 * 经 {@link FrameDecoder} → {@link ZSegmentReader} 读出来，与主路径同一段代码。
 *
 * <p>本任务钦定的三条断言口径（测试方法名与之逐一对应）：
 * <ol>
 *   <li><b>歧义映射被拒</b>——任选其一等于拿一条档案字段赌一次；</li>
 *   <li><b>低置信不合档</b>——阈值是真正的闸门而不是写在注释里的数字；</li>
 *   <li><b>输出不含临床建议</b>——字段对齐不产诊断、不代签。</li>
 * </ol>
 */
@DisplayName("T23 Z 段语义对齐：置信度闸门 + 歧义显式拒绝 + 临床边界")
class ZFieldAlignerTest {

    private static final String API_KEY = "stub-key-20261021";
    private static final String MODEL = "stub-model-local";
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-10-21T01:00:00Z"), ZoneOffset.UTC);

    private static final Path FIXTURES = Path.of("src", "test", "resources", "fixtures", "zh");

    private OpenAiCompatibleStubServer stub;
    private InMemoryModelCallAudit audit;
    private OpenAiCompatibleGateway gateway;

    @BeforeEach
    void setUp() throws IOException {
        stub = OpenAiCompatibleStubServer.start(API_KEY);
        audit = new InMemoryModelCallAudit();
        gateway = new OpenAiCompatibleGateway(config(), audit, HttpClient.newHttpClient(), FIXED_CLOCK);
    }

    @AfterEach
    void tearDown() {
        if (stub != null) {
            stub.close();
        }
    }

    private ModelGatewayConfig config() {
        return new ModelGatewayConfig(stub.baseUrl(), API_KEY, MODEL, 15, 0);
    }

    private ZFieldAligner aligner() {
        return new ZFieldAligner(gateway);
    }

    // ================= 钦定断言口径 =================

    @Test
    @DisplayName("歧义映射被拒：同一个 Z 段两个本地字段 → 整条拒；多个 Z 段抢同一字段 → 双方都不合档")
    void test_ambiguous_mapping_rejected() {
        // —— 形态一：同一个 Z 段被映射到两个不同的本地字段 ——
        // 这是「任选其一」最直接的形态：留哪一条都是在拿一条档案字段赌一次。
        stub.setContentOverride(Json.write(Map.of("mappings", List.of(
                mapping("ZX1.3", "pastHistorySummary", 0.91, "平台写既往病史摘要，对应院内既往病史摘要"),
                mapping("ZX1.3", "chronicDiseaseTag", 0.88, "平台写既往病史摘要，对应院内慢病标签")))));

        ModelCallException e = assertThrows(ModelCallException.class,
                () -> aligner().align(List.of(zx1())));
        assertEquals(ModelErrorCode.SCHEMA_INVALID, e.errorCode(),
                "同一 Z 段两个互斥映射必须按结构不符整条拒，不产半份结果");

        // —— 形态二：两个不同的 Z 段抢同一个本地字段 ——
        // 这里网关无从察觉（z_field 各不相同，Schema 全程成立），只能由对齐闸门判。
        stub.reset();
        stub.setContentOverride(Json.write(Map.of("mappings", List.of(
                mapping("ZX1.3", "pastHistorySummary", 0.91, "平台写既往病史摘要，对应院内既往病史摘要"),
                mapping("ZX4.3", "pastHistorySummary", 0.86, "平台写档案变更类型，但对齐到院内既往病史摘要")))));

        ZAlignmentReport report = aligner().align(List.of(zx1(), zx4()));
        assertTrue(report.merged().isEmpty(),
                "两个 Z 段争同一个本地字段时必须双方都不合档；留一条就是替人做了取舍，" +
                        "而写进去的那条会覆盖掉另一条的内容。实际合档：" + report.merged().keySet());
        assertEquals(2, report.unresolved().size());
        for (ZAlignmentReport.Unresolved u : report.unresolved()) {
            assertEquals(AlignmentReason.AMBIGUOUS_LOCAL_FIELD, u.reason());
            assertEquals("pastHistorySummary", u.proposedLocalField(),
                    "拒绝时必须保留模型建议的字段名与置信度，否则信息员只能凭字段名重猜一遍");
            assertTrue(u.confidence() > 0.8, "置信度应原样保留，实际 " + u.confidence());
        }
        assertTrue(report.requiresHumanConfirmation());
    }

    @Test
    @DisplayName("低置信不合档：0.62 不进 merged，转待确认；同一输入把阈值调到 0.6 即合档")
    void test_low_confidence_does_not_merge() {
        stub.setContentOverride(Json.write(Map.of("mappings", List.of(
                mapping("ZX1.3", "pastHistorySummary", 0.62,
                        "平台字段名为既往病史摘要，但档案包里只有摘要正文，缺少字段名佐证")))));

        ZAlignmentReport strict = aligner().align(List.of(zx1()));
        assertTrue(strict.merged().isEmpty(),
                "置信度 0.62 低于默认阈值 0.75，不得进 merged。实际 merged：" + strict.merged());
        assertEquals(1, strict.unresolved().size());
        ZAlignmentReport.Unresolved u = strict.unresolved().get(0);
        assertEquals(AlignmentReason.LOW_CONFIDENCE, u.reason());
        assertEquals("pastHistorySummary", u.proposedLocalField());
        assertEquals(0.62d, u.confidence(), 1e-9);
        assertTrue(u.hasConfidence());
        assertTrue(strict.requiresHumanConfirmation(), "有条目没合上就必须提示人工确认");
        assertEquals(ZFieldAligner.DEFAULT_CONFIDENCE_THRESHOLD, strict.threshold(), 1e-9);

        // 阈值必须是真正的闸门：同一份响应、同一个 0.62，把阈值降到 0.6 就该合档。
        // 少了这一步，「低于阈值不合档」可能只是因为某处写死了不 merge。
        ZAlignmentReport lax = new ZFieldAligner(gateway, 0.6d).align(List.of(zx1()));
        assertEquals(1, lax.merged().size(),
                "阈值下调到 0.6 后同一条必须合档，否则说明阈值根本没参与判定");
        assertEquals("pastHistorySummary", lax.bindingOf("ZX1.3").orElseThrow().localField());
        assertFalse(lax.requiresHumanConfirmation());
    }

    @Test
    @DisplayName("输出不含临床建议：带诊断/处方/代签内容的映射被拒，且全报告文本零命中")
    void test_no_clinical_advice_in_output() {
        // 模型的越界输出是真实会发生的事：依据里顺手写一句临床判断并不稀奇。
        stub.setContentOverride(Json.write(Map.of("mappings", List.of(
                mapping("ZX1.3", "pastHistorySummary", 0.95,
                        "平台字段名为既往病史摘要，摘要含高血压随访，建议患者服用降压药并调整剂量"),
                mapping("ZX2.3", "nextFollowUpDate", 0.93,
                        "取值形如 20270115，与院内下次随访时间一致，可直接映射"),
                mapping("ZX3.3", "attendingDoctorName", 0.90,
                        "平台字段名为责任医生姓名，对应院内责任医生姓名，由本系统代签完成")))));

        ZAlignmentReport report = aligner().align(List.of(zx1(), zx2(), zx3()));

        // 两条干净的一条合档，带临床结论的两条被拒——不是扣分，是拒收。
        assertEquals(1, report.merged().size(), "实际合档：" + report.merged().keySet());
        assertEquals("ZX2.3", report.merged().keySet().iterator().next());

        Map<String, AlignmentReason> reasons = new LinkedHashMap<>();
        for (ZAlignmentReport.Unresolved u : report.unresolved()) {
            reasons.put(u.zField(), u.reason());
        }
        assertEquals(AlignmentReason.CLINICAL_ADVICE_IN_OUTPUT, reasons.get("ZX1.3"),
                "依据里写「建议服用…调整剂量」属临床建议，必须拒收该绑定");
        assertEquals(AlignmentReason.CLINICAL_ADVICE_IN_OUTPUT, reasons.get("ZX3.3"),
                "依据里写「代签」越过签名边界，必须拒收该绑定");

        // 关键词断言：模型或平台写下的文本里一个越界词都不许有（含被拒条目残留的依据）。
        String modelAuthoredText = modelAuthoredTextOf(report);
        for (String token : List.of("诊断", "确诊", "疑似", "治疗方案", "处方", "建议服用",
                "调整剂量", "代签", "signature", "prescription")) {
            assertFalse(modelAuthoredText.contains(token),
                    "对齐输出不得出现「" + token + "」，实际输出：" + modelAuthoredText);
        }

        // 被拒条目的依据原文明文不得外泄：依据里往往带着居民档案内容，
        // 台账与待确认清单是最不该留原文的地方（T40 的明文扫描守的正是这条）。
        assertFalse(modelAuthoredText.contains("降压药"),
                "越界依据原文不得被复述进未合档清单，排障请走模型调用审计。实际：" + modelAuthoredText);
        assertTrue(modelAuthoredText.contains("依据含越界内容"),
                "被拒条目仍须写明是因越界而拒，只是不能复述原文");

        // 反向自检：闸门不是「见字就拒」的宽口径墙。
        // 「患者是否按时服用药物」说的是用药依从性这个字段的语义，是描述不是建议。
        assertEquals(null, ZFieldAligner.findClinicalBoundaryHit("平台字段名为用药依从性，"
                + "摘要描述患者是否按时服用药物，与院内用药依从性一致"));
        assertEquals(null, ZFieldAligner.findClinicalBoundaryHit(
                "平台字段名为既往病史摘要，字段值为高血压随访与2型糖尿病，对应院内既往病史摘要"));
    }

    // ================= 正常路径与真实语料 =================

    @Test
    @DisplayName("正常路径：fixtures/zh 真实语料的 Z 段合出映射，未合上的逐条带原因")
    void test_aligns_real_zh_fixture_z_segments() throws IOException {
        // 取自 T4 落盘的语料，经与主路径同一段解码代码读出来：
        // UTF-8 与 GB18030 两条同一份内容，走到这里应当得到同一组 Z 段。
        Hl7Message utf8 = decodeFixture("zh_0001_utf8.hl7");
        Hl7Message gb18030 = decodeFixture("zh_0001_gb18030.hl7");

        List<ZSegment> fromUtf8 = ZSegmentReader.read(utf8);
        List<ZSegment> fromGb18030 = ZSegmentReader.read(gb18030);
        assertEquals(4, fromUtf8.size(), "样例报文应带 4 个 Z 段");
        assertEquals(fromUtf8, fromGb18030, "同一份报文的两种编码读出的 Z 段必须逐字相同");

        // ZX1（既往病史摘要 0.82）/ ZX2（下次随访时间 0.77）合档；
        // ZX3 模型自答判断不了（无绑定）/ ZX4 替身没有样例（同样无绑定）——两条都必须有说法。
        ZAlignmentReport report = aligner().align(fromUtf8);
        assertEquals(List.of("ZX1.3", "ZX2.3"), List.copyOf(report.merged().keySet()));
        assertEquals(0.82d, report.bindingOf("ZX1.3").orElseThrow().confidence(), 1e-9);
        assertTrue(report.bindingOf("ZX1.3").orElseThrow().rationale().contains("既往病史"));
        assertEquals("nextFollowUpDate", report.bindingOf("ZX2.3").orElseThrow().localField());
        assertEquals(2, report.unresolved().size());
        for (ZAlignmentReport.Unresolved u : report.unresolved()) {
            assertEquals(AlignmentReason.NO_BINDING_PRODUCED, u.reason(),
                    "模型没给绑定的 Z 段必须有说法，不能悄悄消失");
        }
        assertTrue(report.requiresHumanConfirmation());
        assertEquals(Map.of(AlignmentReason.NO_BINDING_PRODUCED, 2), report.reasonCounts());
    }

    @Test
    @DisplayName("零可对齐内容：没有 Z 段时不发请求，带空段的也不发")
    void test_nothing_to_align_makes_no_model_call() throws IOException {
        ZAlignmentReport none = aligner().align(List.of());
        assertTrue(none.isEmpty());
        assertFalse(none.requiresHumanConfirmation(), "报文里没有 Z 段不是问题，不该逼信息员确认");
        assertEquals(0, stub.requestCount(), "没有 Z 段就不该为模型付一次调用");

        // ZX-2 与 ZX-3 都空：整段是空的。这属于报文自身的问题，与模型无关。
        ZAlignmentReport blank = aligner().align(List.of(
                ZSegment.of("ZX1", Map.of()), ZSegment.of("ZX2", Map.of())));
        assertEquals(0, stub.requestCount(), "版式问题不该由模型来判，也不该付调用成本");
        assertEquals(2, blank.unresolved().size());
        for (ZAlignmentReport.Unresolved u : blank.unresolved()) {
            assertEquals(AlignmentReason.EMPTY_Z_SEGMENT, u.reason());
        }
        assertTrue(blank.merged().isEmpty());
    }

    @Test
    @DisplayName("Z 段字段序号超出版式：显式拒绝，不静默忽略那段内容")
    void test_z_segment_beyond_layout_is_rejected_explicitly() {
        // ZX1-4 带了第四个字段。对齐器只消费 -2/-3，-4 的内容必须显式报出来，
        // 否则它在报告里彻底不见，等于静默丢了一段档案内容。
        ZSegment beyond = ZSegment.of("ZX1", new LinkedHashMap<>(Map.of(
                2, "既往病史摘要", 3, "高血压随访;2型糖尿病", 4, "平台内部流水号 A-99182")));
        stub.setContentOverride(Json.write(Map.of("mappings", List.of(
                mapping("ZX1.3", "pastHistorySummary", 0.9, "对应院内既往病史摘要")))));

        ZAlignmentReport report = aligner().align(List.of(beyond));
        assertEquals(0, stub.requestCount(), "超出版式的那段不送模型");
        assertTrue(report.merged().isEmpty());
        assertEquals(1, report.unresolved().size());
        assertEquals(AlignmentReason.BEYOND_Z_LAYOUT, report.unresolved().get(0).reason());
        assertEquals("ZX1.4", report.unresolved().get(0).zField(), "定位串应指向真正超出的那个下标");
    }

    @Test
    @DisplayName("可对齐 Z 段数超出本地字段数：整批转人工，不挑一部分合入")
    void test_more_z_fields_than_local_dictionary_refuses_whole_batch() {
        // 字典 6 条，这里给 8 段带值 Z 段：本地装不下这份包。
        // 挑 6 条合进去就等于替人决定丢掉哪两条，而丢掉的那些不会有任何人知道。
        List<ZSegment> tooMany = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            tooMany.add(ZSegment.of("ZX" + i, Map.of(2, "平台字段名" + i, 3, "字段值" + i)));
        }
        ZAlignmentReport report = aligner().align(tooMany);
        assertEquals(0, stub.requestCount(), "整批转人工时不该白发一次模型调用");
        assertTrue(report.merged().isEmpty());
        assertEquals(8, report.unresolved().size());
        for (ZAlignmentReport.Unresolved u : report.unresolved()) {
            assertEquals(AlignmentReason.MORE_Z_FIELDS_THAN_LOCAL_FIELDS, u.reason());
        }
        assertTrue(report.requiresHumanConfirmation());
    }

    @Test
    @DisplayName("模型给出字典外的字段名：判为编造，不予合档")
    void test_unknown_local_field_is_not_merged() {
        // 固定 JSON Schema 管得住形状、管不住取值：模型完全可以规规矩矩地
        // 返回一个站内根本不存在的字段名。
        stub.setContentOverride(Json.write(Map.of("mappings", List.of(
                mapping("ZX1.3", "allergyHistory", 0.94, "平台写既往病史摘要，对应院内过敏史")))));

        ZAlignmentReport report = aligner().align(List.of(zx1()));
        assertTrue(report.merged().isEmpty());
        assertEquals(1, report.unresolved().size());
        ZAlignmentReport.Unresolved u = report.unresolved().get(0);
        assertEquals(AlignmentReason.UNKNOWN_LOCAL_FIELD, u.reason());
        assertEquals("allergyHistory", u.proposedLocalField(), "被拒原因要能指认模型到底给了什么");
    }

    @Test
    @DisplayName("模型不可达时异常原样上抛，不在本类吞掉")
    void test_model_failure_propagates_to_the_single_degradation_path() {
        // 对齐器不处理降级：它一处理，就和 ResilientModelGateway 各写一半，
        // 必然出现某个调用点走了另写的那一半。
        stub.setHttpStatus(500);
        assertThrows(ModelCallException.class, () -> aligner().align(List.of(zx1())));
    }

    @Test
    @DisplayName("同一份报文连跑两次结论逐字相同（字典与判定顺序都可复现）")
    void test_alignment_is_reproducible() {
        stub.setContentOverride(Json.write(Map.of("mappings", List.of(
                mapping("ZX1.3", "pastHistorySummary", 0.82, "对应院内既往病史摘要"),
                mapping("ZX2.3", "nextFollowUpDate", 0.77, "取值形如 20270115"),
                mapping("ZX3.3", "", 0.2, "档案包里没有医生信息，判断不了")))));

        List<ZSegment> input = List.of(zx1(), zx2(), zx3());
        ZAlignmentReport first = aligner().align(input);
        ZAlignmentReport second = aligner().align(input);
        assertEquals(List.copyOf(first.merged().keySet()), List.copyOf(second.merged().keySet()));
        assertEquals(first.unresolved().stream().map(ZAlignmentReport.Unresolved::zField).toList(),
                second.unresolved().stream().map(ZAlignmentReport.Unresolved::zField).toList());
        assertEquals(first.zhSummary(), second.zhSummary());
    }

    // ================= 小工具 =================

    private static Map<String, Object> mapping(String zField, String localField,
                                               double confidence, String rationale) {
        Map<String, Object> m = Json.object();
        m.put("z_field", zField);
        m.put("local_field", localField);
        m.put("confidence", confidence);
        m.put("rationale", rationale);
        return m;
    }

    private static ZSegment zx1() {
        return ZSegment.of("ZX1", new LinkedHashMap<>(Map.of(2, "既往病史摘要", 3, "高血压随访;2型糖尿病")));
    }

    private static ZSegment zx2() {
        return ZSegment.of("ZX2", new LinkedHashMap<>(Map.of(2, "下次随访时间", 3, "20270115")));
    }

    private static ZSegment zx3() {
        return ZSegment.of("ZX3", new LinkedHashMap<>(Map.of(2, "责任医生姓名", 3, "李医生")));
    }

    private static ZSegment zx4() {
        return ZSegment.of("ZX4", new LinkedHashMap<>(Map.of(2, "档案变更类型", 3, "随访记录更新")));
    }

    private Hl7Message decodeFixture(String name) throws IOException {
        byte[] raw = Files.readAllBytes(FIXTURES.resolve(name));
        FrameDecoder.DecodedMessage decoded = FrameDecoder.decode(raw);
        assertEquals(CharsetResolver.Status.OK, decoded.charsetStatus,
                "MSH-18 声明的字符集应当被识别，而不是当成未声明兜底到 UTF-8");
        assertNotNull(decoded.message);
        return decoded.message;
    }

    /**
     * 报告里<b>由模型或平台写下</b>的文本 —— 关键词扫描只看这一半。
     *
     * <p>不看 {@link AlignmentReason#zhExplanation()}：那是本产品自己写死的原因说明，
     * 里面出现「诊断」「代签」是<b>在描述违规类别</b>，不是在下诊断。
     * 把两者混进一次扫描，得到的结论必然是「永远有一条不过」，而一条永远不过的自检等于没有自检。
     */
    private static String modelAuthoredTextOf(ZAlignmentReport report) {
        StringBuilder sb = new StringBuilder();
        report.merged().forEach((z, b) -> sb.append(z).append(b.localField()).append(b.rationale()));
        for (ZAlignmentReport.Unresolved u : report.unresolved()) {
            sb.append(u.zField()).append(u.platformLabel()).append(u.proposedLocalField()).append(u.rationale());
        }
        return sb.toString();
    }
}