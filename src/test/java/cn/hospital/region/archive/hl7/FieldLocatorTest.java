package cn.hospital.region.archive.hl7;

import cn.hospital.region.archive.fixtures.FixturesManifest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FieldLocator} 的验收：能否把「这条为什么没落地」说清楚到段名 + 字段号 + 段内序号。
 *
 * <p>覆盖：
 * <ul>
 *   <li>缺 MSH-10 → 定位到 MSH-10 且建议码 control-id-missing，片段恒 ≤32 字符；</li>
 *   <li>缺 MSH-9 / PID-3 各自绑定自己的建议码；</li>
 *   <li>缺整段与缺字段走同一出口；</li>
 *   <li>MSH-18 未声明只告警不阻断（国内区域平台大量不写字符集，阻断会整批打回）；</li>
 *   <li>患者数据段的错误片段**不含明文**，核对改走 SHA-256 前 8 位摘要；</li>
 *   <li>全部错误经同一个文案出口拼装，保证各端点报出同一位置。</li>
 * </ul>
 */
class FieldLocatorTest {

    private static final Charset GB18030 = Charset.forName("GB18030");

    // ------------------------------------------------------------ 语料驱动

    /** T4 语料里故意缺 MSH-10 的报文：这是本任务最核心的一条断言。 */
    @Test
    @DisplayName("缺 MSH-10 → segment=MSH field=10，片段长度 ≤32")
    void test_missing_msh10_returns_segment_MSH_field_10() throws IOException {
        Hl7Message msg = parseHapi("defect_missing_msh10.hl7");

        Optional<LocatableError> blocking = FieldLocator.firstBlocking(msg);

        assertTrue(blocking.isPresent(), "缺 MSH-10 必须产出阻断级错误");
        LocatableError err = blocking.orElseThrow();
        assertEquals("MSH", err.segmentName());
        assertEquals(10, err.fieldIndex());
        assertEquals(FieldLocator.CODE_CONTROL_ID_MISSING, err.suggestedCode());
        assertEquals(LocatableError.Severity.BLOCKING, err.severity());
        assertTrue(err.blocking());
        assertTrue(err.rawSnippet().length() <= LocatableError.MAX_SNIPPET,
                "原值片段必须 ≤" + LocatableError.MAX_SNIPPET + " 字符，实际 " + err.rawSnippet().length());
        assertEquals("MSH-10", err.fieldRef());
        assertEquals("MSH^10^1", err.errSegmentReference());
        assertEquals(0, err.segmentOrdinal(), "MSH 是第 0 段（与解析器 ordinal 一致）");
    }

    @Test
    @DisplayName("缺 MSH-10 的定位文案带字段中文名、段内序号与建议错误码")
    void missing_msh10_describe_carries_field_segment_and_code() throws IOException {
        LocatableError err = FieldLocator.firstBlocking(parseHapi("defect_missing_msh10.hl7")).orElseThrow();

        String line = FieldLocator.describe(err);

        assertTrue(line.contains("MSH-10"), "文案须带段名+字段号：" + line);
        assertTrue(line.contains("报文控制号"), "文案须带字段中文名：" + line);
        assertTrue(line.contains("第 1 段"), "文案须带段内序号（1-based）：" + line);
        assertTrue(line.contains(FieldLocator.CODE_CONTROL_ID_MISSING), "文案须带建议错误码：" + line);
    }

    @Test
    @DisplayName("缺 MSH-10 时片段携带 MSH 行原文（协议头无患者数据），供信息员直接核对")
    void msh_snippet_carries_header_plaintext_for_debugging() throws IOException {
        LocatableError err = FieldLocator.firstBlocking(parseHapi("defect_missing_msh10.hl7")).orElseThrow();

        assertTrue(err.rawSnippet().startsWith("MSH|^~\\&|"), "MSH 片段应是段原文：" + err.rawSnippet());
    }

    @Test
    @DisplayName("缺 PID-3 → 建议码 patient-id-missing")
    void test_missing_pid3_returns_code_patient_id_missing() throws IOException {
        LocatableError err = FieldLocator.firstBlocking(parseHapi("defect_missing_pid3.hl7")).orElseThrow();

        assertEquals("PID", err.segmentName());
        assertEquals(3, err.fieldIndex());
        assertEquals(FieldLocator.CODE_PATIENT_ID_MISSING, err.suggestedCode());
    }

    @Test
    @DisplayName("缺 MSH-9 → 建议码 unsupported-event-type")
    void missing_msh9_suggests_unsupported_event_type() {
        LocatableError err = FieldLocator.firstBlocking(parse(mshWithout(9))).orElseThrow();

        assertEquals("MSH", err.segmentName());
        assertEquals(9, err.fieldIndex());
        assertEquals(FieldLocator.CODE_UNSUPPORTED_EVENT_TYPE, err.suggestedCode());
    }

    @Test
    @DisplayName("缺 MSH-11 / MSH-12 各自绑定自己的建议码")
    void missing_msh11_and_msh12_have_own_codes() {
        LocatableError e11 = FieldLocator.firstBlocking(parse(mshWithout(11))).orElseThrow();
        LocatableError e12 = FieldLocator.firstBlocking(parse(mshWithout(12))).orElseThrow();

        assertEquals(11, e11.fieldIndex());
        assertEquals(FieldLocator.CODE_PROCESSING_ID_MISSING, e11.suggestedCode());
        assertEquals(12, e12.fieldIndex());
        assertEquals(FieldLocator.CODE_VERSION_MISSING, e12.suggestedCode());
    }

    @Test
    @DisplayName("缺 PID-5 / PID-7 各自绑定自己的建议码")
    void missing_pid5_and_pid7_have_own_codes() {
        LocatableError e5 = FieldLocator.firstBlocking(parse(er7(9, 10, 11, 12, 18, 3, 0, 7))).orElseThrow();
        LocatableError e7 = FieldLocator.firstBlocking(parse(er7(9, 10, 11, 12, 18, 3, 5, 0))).orElseThrow();

        assertEquals("PID", e5.segmentName());
        assertEquals(5, e5.fieldIndex());
        assertEquals(FieldLocator.CODE_PATIENT_NAME_MISSING, e5.suggestedCode());
        assertEquals("PID", e7.segmentName());
        assertEquals(7, e7.fieldIndex());
        assertEquals(FieldLocator.CODE_BIRTH_DATE_MISSING, e7.suggestedCode());
    }

    // ------------------------------------------------------------ 整段缺失

    @Test
    @DisplayName("缺整个 PID 段 → fieldIndex=0 + missing-required-segment")
    void missing_required_segment_reports_missing_required_segment() {
        String er7 = "MSH|^~\\&|ARCHIVE|REGION_HIS|CHC|CC|20261020081500+0800||ADT^A08|CTRL-1|P|2.5.1"
                + "|||AL|NE|CHN\r";
        Hl7Message msg = parse(er7);

        List<LocatableError> all = FieldLocator.locate(msg);
        LocatableError segMissing = all.stream()
                .filter(e -> FieldLocator.CODE_MISSING_SEGMENT.equals(e.suggestedCode()))
                .findFirst().orElseThrow();

        assertEquals("PID", segMissing.segmentName());
        assertEquals(0, segMissing.fieldIndex());
        assertEquals(-1, segMissing.segmentOrdinal(), "整段缺失时位置未知");
        assertEquals("PID", segMissing.fieldRef(), "fieldIndex=0 时退化为段名");
        assertTrue(segMissing.blocking());
    }

    @Test
    @DisplayName("缺 MSH 段也能定位，不抛裸异常")
    void missing_msh_segment_is_located_not_thrown() {
        // 解析器允许首段就是 PID 吗？不允许 —— 这里直接构造只有 PID 的报文走「MSH 缺失」判定，
        // 用 Batch（BHS）报文等价验证「必需段缺失」这条出口。
        String bhs = "BHS|^~\\&|ARCHIVE|HIS|CHC|CC\rPID|1||PAT-1||张^伟||19850512|F\r";

        List<LocatableError> all = FieldLocator.locate(parse(bhs));

        LocatableError mshMissing = all.stream()
                .filter(e -> "MSH".equals(e.segmentName()) && e.fieldIndex() == 0)
                .findFirst().orElseThrow();
        assertEquals(FieldLocator.CODE_MISSING_SEGMENT, mshMissing.suggestedCode());
    }

    // ------------------------------------------------------------ 严重度分级

    @Test
    @DisplayName("MSH-18 未声明只告警不阻断（按 T6 默认 UTF-8 继续）")
    void msh18_absent_is_advisory_not_blocking() {
        LocatableError e18 = FieldLocator.locate(parse(er7(9, 10, 11, 12, 0, 3, 5, 7))).stream()
                .filter(e -> e.fieldIndex() == 18)
                .findFirst().orElseThrow();

        assertEquals(FieldLocator.CODE_CHARSET_NOT_DECLARED, e18.suggestedCode());
        assertEquals(LocatableError.Severity.ADVISORY, e18.severity());
        assertFalse(e18.blocking());
        assertTrue(FieldLocator.firstBlocking(parse(er7(9, 10, 11, 12, 0, 3, 5, 7))).isEmpty(),
                "只有告警级时不得产出阻断错，主路径继续");
    }

    @Test
    @DisplayName("firstBlocking 跳过告警级，直接落到真正的阻断错")
    void first_blocking_skips_advisory() {
        Hl7Message msg = parse(er7(9, 10, 11, 12, 0, 0, 5, 7)); // 无 MSH-18 且无 PID-3

        List<LocatableError> all = FieldLocator.locate(msg);
        LocatableError blocking = FieldLocator.firstBlocking(msg).orElseThrow();

        assertEquals(FieldLocator.CODE_PATIENT_ID_MISSING, blocking.suggestedCode());
        assertTrue(all.size() >= 2, "告警与阻断应同时在列表里：" + all);
        assertEquals(all.stream().filter(LocatableError::blocking).findFirst().orElseThrow(),
                blocking, "firstBlocking 必须等于列表里第一条阻断错");
    }

    // ------------------------------------------------------------ 脱敏不变量

    @Test
    @DisplayName("患者数据段的错误片段不含姓名/出生日期明文，核对改走 SHA-256 前 8 位")
    void snippet_never_contains_patient_identifiable_plaintext() throws IOException {
        // defect_missing_pid3 的 PID 段里带有姓名 Doe^Jane 与出生日期 19750312
        LocatableError err = FieldLocator.firstBlocking(parseHapi("defect_missing_pid3.hl7")).orElseThrow();
        String snippet = err.rawSnippet();

        assertFalse(snippet.contains("Doe"), "片段不得含姓名明文：" + snippet);
        assertFalse(snippet.contains("Jane"), "片段不得含姓名明文：" + snippet);
        assertFalse(snippet.contains("19750312"), "片段不得含出生日期明文：" + snippet);
        assertTrue(snippet.startsWith("PID|（已脱敏"), "片段应为脱敏形式：" + snippet);
        assertTrue(snippet.length() <= LocatableError.MAX_SNIPPET);
        assertEquals("", err.valueDigest(), "字段本身为空时不应伪造摘要");
    }

    @Test
    @DisplayName("字段有值时给出 SHA-256 前 8 位摘要，供无明文比对")
    void value_digest_is_eight_hex_chars() {
        LocatableError err = FieldLocator.firstBlocking(parse(er7(9, 10, 11, 12, 18, 0, 5, 7))).orElseThrow();

        // PID-3 存在但 PID-5 为空 → 被定位的是空字段，摘要为空；这里直接验证摘要函数本身
        assertEquals(8, FieldLocator.digestOf("PAT-REMOTE-001").length());
        assertTrue(FieldLocator.digestOf("PAT-REMOTE-001").matches("[0-9a-f]{8}"));
        assertEquals(FieldLocator.digestOf("张伟"), FieldLocator.digestOf("张伟"), "摘要须稳定可复现");
        assertFalse(FieldLocator.digestOf("张伟").equals(FieldLocator.digestOf("张伟明")));
        assertEquals("", err.valueDigest());
    }

    @Test
    @DisplayName("构造器强制片段长度上限，调用点无法绕过")
    void constructor_truncates_snippet_to_32() {
        LocatableError err = LocatableError.of("PID", 3, 1, "x".repeat(200),
                FieldLocator.CODE_PATIENT_ID_MISSING, LocatableError.Severity.BLOCKING, "");

        assertEquals(LocatableError.MAX_SNIPPET, err.rawSnippet().length());
    }

    @Test
    @DisplayName("构造器拒绝空段名/负字段号/空建议码，避免产出定位不全的错误")
    void constructor_rejects_incomplete_locations() {
        assertThrows(IllegalArgumentException.class,
                () -> LocatableError.of("", 3, 0, "", "c", LocatableError.Severity.BLOCKING, ""));
        assertThrows(IllegalArgumentException.class,
                () -> LocatableError.of("PID", -1, 0, "", "c", LocatableError.Severity.BLOCKING, ""));
        assertThrows(IllegalArgumentException.class,
                () -> LocatableError.of("PID", 3, 0, "", "  ", LocatableError.Severity.BLOCKING, ""));
    }

    // ------------------------------------------------------------ 单一文案出口

    @Test
    @DisplayName("全部错误类型经同一文案出口，各端点报出同一位置")
    void all_errors_render_through_one_formatter() {
        List<LocatableError> all = FieldLocator.locate(parse(er7(0, 0, 0, 0, 0, 0, 0, 0)));

        assertTrue(all.size() >= 8, "全空报文应报出全部必需项：" + all);
        for (LocatableError e : all) {
            String line = FieldLocator.describe(e);
            assertTrue(line.contains(e.segmentName()), "文案须含段名：" + line);
            assertTrue(line.contains(e.suggestedCode()), "文案须含建议错误码：" + line);
            assertTrue(line.contains(e.fieldRef()), "文案须含字段定位：" + line);
            assertEquals(e.describe(e.fieldIndex() == 0 ? "必需段" : "字段").contains("建议错误码"), true);
            // 文案里不得出现患者数据段的原值
            if (!e.fieldRef().startsWith("MSH")) {
                assertFalse(line.contains("已脱敏：") && line.contains("1975"),
                        "非 MSH 文案不得带明文：" + line);
            }
        }
    }

    @Test
    @DisplayName("locate 返回顺序确定：先必需段后字段，与清单顺序一致")
    void locate_order_is_deterministic() {
        Hl7Message msg = parse(er7(0, 0, 11, 12, 18, 3, 0, 0));

        List<LocatableError> first = FieldLocator.locate(msg);
        List<LocatableError> second = FieldLocator.locate(msg);

        assertEquals(first, second, "同一报文两次定位结果必须完全一致");
        assertEquals(9, first.get(0).fieldIndex(), "先 MSH-9");
        assertEquals(10, first.get(1).fieldIndex(), "再 MSH-10");
        assertEquals("PID", first.get(2).segmentName(), "MSH 查完才查 PID");
        assertEquals(5, first.get(2).fieldIndex());
        assertEquals(7, first.get(3).fieldIndex());
        assertEquals(4, first.size(), "MSH-9/10 + PID-5/7 共 4 条，MSH-18 已声明不报");
    }

    // ------------------------------------------------------------ 正常报文不误报

    @Test
    @DisplayName("全语料逐条定位都不抛错（定位失败必须是数据不是异常）")
    void whole_corpus_is_locatable_without_throwing() throws IOException {
        int n = 0;
        for (Path p : FixturesManifest.hapiMessages()) {
            Hl7Message msg = Hl7Parser.parse(readText(p));
            List<LocatableError> located = FieldLocator.locate(msg);   // 不得抛异常
            FieldLocator.firstBlocking(msg);                            // 不得抛异常
            for (LocatableError e : located) {
                FieldLocator.describe(e);                              // 不得抛异常
                assertTrue(e.rawSnippet().length() <= LocatableError.MAX_SNIPPET);
            }
            n++;
        }
        assertTrue(n >= 16, "HAPI 语料应全量走过定位，实际 " + n + " 条");
    }

    @Test
    @DisplayName("带患者标识的合法 ADT/ORU 报文不产出阻断错")
    void well_formed_corpus_produces_no_blocking_error() throws IOException {
        int checked = 0;
        for (Path p : FixturesManifest.hapiMessages()) {
            String name = p.getFileName().toString();
            if (name.startsWith("defect_")) {
                continue;   // 故意缺陷样例，另有专门断言
            }
            Hl7Message msg = Hl7Parser.parse(readText(p));
            // 两条转义样例（esc/unpaired_escape）刻意把转义序列放在 PID-4、PID-5 留空，
            // 它们测的是解析期还原而不是患者标识完整，不纳入「必填齐备」这一条断言。
            if (!msg.has("PID") || msg.field("PID", 5).isBlank()) {
                continue;
            }
            assertTrue(FieldLocator.firstBlocking(msg).isEmpty(),
                    name + " 不应报阻断错，实际：" + FieldLocator.locate(msg));
            checked++;
        }
        // 16 条语料 - 2 条故意缺陷 - 2 条转义样例（PID-5 刻意留空）- 3 条 ACK（无 PID）= 9 条
        assertEquals(9, checked, "纳入校验的报文数");
    }

    @Test
    @DisplayName("ACK 回执免检 PID：回执规范上就没有患者段，不能把自己发的回执判成缺必填")
    void ack_message_is_exempt_from_pid_requirements() throws IOException {
        Hl7Message ack = parseHapi("ack_ae_err_v251.hl7");   // 只有 MSH / MSA / ERR

        assertTrue(FieldLocator.isAck(ack), "MSH-9=ACK 应识别为回执");
        assertFalse(ack.has("PID"), "该回执本身就没有患者段");
        assertTrue(FieldLocator.firstBlocking(ack).isEmpty(),
                "回执不应因缺 PID 被判阻断：" + FieldLocator.locate(ack));

        // 非 ACK 报文仍须照常要求 PID
        assertFalse(FieldLocator.isAck(parseHapi("adt_a08_v251.hl7")));
    }

    @Test
    @DisplayName("必需字段清单就是 spec 约定的 8 项，且只有 MSH-18 是告警级")
    void required_field_checklist_matches_spec() {
        List<FieldLocator.RequiredField> req = FieldLocator.requiredFields();

        assertEquals(8, req.size());
        assertTrue(req.stream().allMatch(f -> f.severity() == LocatableError.Severity.BLOCKING
                || (f.segmentName().equals("MSH") && f.fieldIndex() == 18)));
        assertEquals(1, req.stream().filter(f -> f.severity() == LocatableError.Severity.ADVISORY).count());
        assertTrue(req.stream().filter(f -> f.identifying()).count() >= 3, "PID 侧三项须标为可识别信息");
    }

    // ------------------------------------------------------------ 工具

    /** 构造一条可控的 ER7；传入 0 表示该字段留空。MSH-9/10/11/12/18 与 PID-3/5/7 按下标精确落位。 */
    private static String er7(int msh9, int msh10, int msh11, int msh12, int msh18, int pid3, int pid5, int pid7) {
        // MSH-1 是分隔符本身、MSH-2 紧随其后不带分隔符，其余字段一律 "|" + 值
        String msh = "MSH|^~\\&"
                + v("ARCHIVE")      // MSH-3
                + v("REGION_HIS")    // MSH-4
                + v("CHC")           // MSH-5
                + v("CC")            // MSH-6
                + v("20261020081500+0800")  // MSH-7
                + v("")              // MSH-8
                + v(pick(msh9, "ADT^A08"))          // MSH-9
                + v(pick(msh10, "CTRL-0001"))        // MSH-10
                + v(pick(msh11, "P"))                // MSH-11
                + v(pick(msh12, "2.5.1"))            // MSH-12
                + v("")              // MSH-13
                + v("")              // MSH-14
                + v("")              // MSH-15
                + v("AL")            // MSH-16
                + v("NE")            // MSH-17
                + v(pick(msh18, "UNICODE UTF-8"));    // MSH-18
        String pid = "PID"
                + v("1")                                       // PID-1
                + v("")                                        // PID-2
                + v(pick(pid3, "PAT-REMOTE-001^^^REGION_HIS^MR"))  // PID-3
                + v("")                                        // PID-4
                + v(pick(pid5, "张^伟"))                        // PID-5
                + v("")                                        // PID-6
                + v(pick(pid7, "19850512"))                    // PID-7
                + v("F");                                      // PID-8
        return msh + "\r" + pid + "\r";
    }

    /** 只留 MSH 与 PID，MSH-n（n ∈ 9/10/11/12）留空，其余齐备。 */
    private static String mshWithout(int emptyField) {
        String msh = "MSH|^~\\&"
                + v("ARCHIVE") + v("REGION_HIS") + v("CHC") + v("CC") + v("20261020081500+0800") + v("")
                + v(emptyField == 9 ? "" : "ADT^A08")
                + v(emptyField == 10 ? "" : "CTRL-0001")
                + v(emptyField == 11 ? "" : "P")
                + v(emptyField == 12 ? "" : "2.5.1")
                + v("") + v("") + v("")
                + v("AL") + v("NE") + v("UNICODE UTF-8");
        return msh + "\rPID|1||PAT-1||张^伟||19850512|F\r";
    }

    private static String pick(int emptyMarker, String value) {
        return emptyMarker == 0 ? "" : value;
    }

    private static String v(String value) {
        return "|" + value;
    }

    private static Hl7Message parse(String er7) {
        return Hl7Parser.parse(er7);
    }

    private static Hl7Message parseHapi(String name) throws IOException {
        return Hl7Parser.parse(readText(FixturesManifest.HAPI_DIR.resolve(name)));
    }

    private static String readText(Path p) throws IOException {
        byte[] raw = Files.readAllBytes(p);
        return new String(raw, p.getFileName().toString().contains("gb18030") ? GB18030 : StandardCharsets.UTF_8);
    }
}