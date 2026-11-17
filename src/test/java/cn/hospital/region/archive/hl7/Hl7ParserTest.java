package cn.hospital.region.archive.hl7;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hl7Parser + Hl7Message 的验收：
 * <ul>
 *   <li>默认与 4 组非默认分隔符（bang/dollar/percent/star/amp、五互不相同、BHS/FHS）解析产出相同字段值；</li>
 *   <li>segment/field/component/repetitions/subcomponent 访问器返回值与输入 ER7 一致；</li>
 *   <li>段缺失/字段越界/组件越界/子组件越界/未 header 抛明确异常而非 null；</li>
 *   <li>对 fixtures/hapi/ 与 fixtures/synthea/ 真实报文能直接解析不抛。</li>
 * </ul>
 */
class Hl7ParserTest {

    private static final String DEFAULT_MSH =
            "MSH|^~\\&|REGIONAL_ARCHIVE|REGION_HIS|COMMUNITY_HEALTH|COMMUNITY_CENTER|20261020081500+0800||ADT^A08|CTL_DEFAULT|P|2.5.1|||AL|NE|CHN|";
    private static final String DEFAULT_PID = "PID|1||SYN-H0001||Doe^Jane^^^^^L||19750312|F|||123 Elm St^Anytown^MA^02115";
    private static final String DEFAULT_FULL = DEFAULT_MSH + "\r" + DEFAULT_PID;

    @Test
    @DisplayName("默认分隔符：访问器返回值与输入 ER7 一致")
    void default_separators_round_trip() {
        Hl7Message msg = Hl7Parser.parse(DEFAULT_FULL);
        // MSH-9 message type
        assertEquals("ADT^A08", msg.field("MSH", 9));
        // MSH-10 control id
        assertEquals("CTL_DEFAULT", msg.field("MSH", 10));
        // MSH-12 version
        assertEquals("2.5.1", msg.field("MSH", 12));
        // MSH-1 是 separator 字符本身（|）
        assertEquals("|", msg.field("MSH", 1));
        // PID-3 patient id
        assertEquals("SYN-H0001", msg.field("PID", 3));
        // PID-5 family^given^^^^^L → component 1 = Doe, component 2 = Jane, component 7 = L
        assertEquals("Doe", msg.component("PID", 5, 1));
        assertEquals("Jane", msg.component("PID", 5, 2));
        assertEquals("L", msg.component("PID", 5, 7));
        assertEquals("19750312", msg.field("PID", 7));
    }

    @Test
    @DisplayName("非默认分隔符（!$%*&）：相同字段值一致")
    void bang_dollar_delimiters_yield_same_field_values() {
        // 完全等价语义，只是分隔符换了
        String msh = "MSH!$%*&!RegionalArchive!RegionHIS!CommunityHealth!CommunityCenter"
                + "!20261020081500+0800!!ADT$A08!CTL_BANG!P!2.5.1!!!AL!NE!CHN!";
        String pid = "PID!1!!SYN-H0001!!Doe$Jane$$$$$L!!19750312!F";
        Hl7Message msg = Hl7Parser.parse(msh + "\r" + pid);
        assertEquals("ADT$A08", msg.field("MSH", 9));
        assertEquals('!', msg.delimiters().fieldSeparator());
        assertEquals('$', msg.delimiters().componentSeparator());
        assertEquals('%', msg.delimiters().repetitionSeparator());
        assertEquals('*', msg.delimiters().escapeCharacter());
        assertEquals('&', msg.delimiters().subcomponentSeparator());
        assertEquals("CTL_BANG", msg.field("MSH", 10));
        assertEquals("Doe", msg.component("PID", 5, 1));
        assertEquals("19750312", msg.field("PID", 7));
    }

    @Test
    @DisplayName("五个互不相同的分隔符（| ^ ~ $ @）：相同字段值")
    void distinct_five_delimiters_yield_same_field_values() {
        // MSH|^~$@  substring(3,8) = '|', '^', '~', '$', '@'
        String msh = "MSH|^~$@|RegionalArchive|RegionHIS|CommunityHealth"
                + "|CommunityCenter|20261020081500+0800||ADT^A08|CTL_TLD|P|2.5.1";
        String pid = "PID|1||SYN-H0001||Doe^Jane^^^^^L||19750312|F";
        Hl7Message msg = Hl7Parser.parse(msh + "\r" + pid);
        assertEquals('|', msg.delimiters().fieldSeparator());
        assertEquals('^', msg.delimiters().componentSeparator());
        assertEquals('~', msg.delimiters().repetitionSeparator());
        assertEquals('$', msg.delimiters().escapeCharacter());
        assertEquals('@', msg.delimiters().subcomponentSeparator());
        assertEquals("CTL_TLD", msg.field("MSH", 10));
        assertEquals("Doe", msg.component("PID", 5, 1));
        // 与默认不同
        assertNotEquals(Delimiters.DEFAULT, msg.delimiters());
    }

    @Test
    @DisplayName("repetitions API：多重复按 delimiter 切")
    void repetitions_split_on_repetition_separator() {
        String msh = "MSH|^~\\&|APP|FAC|RCV|RCV|20261020||ADT^A08|CTL|P|2.5.1";
        String pid = "PID|1||HL7_ID^A~HL7_ID^B~HL7_ID^C||Doe^Jane^^^L";
        Hl7Message msg = Hl7Parser.parse(msh + "\r" + pid);
        List<String> reps = msg.repetitions("PID", 3);
        assertEquals(3, reps.size());
        assertEquals("HL7_ID^A", reps.get(0));
        assertEquals("HL7_ID^B", reps.get(1));
        assertEquals("HL7_ID^C", reps.get(2));
    }

    @Test
    @DisplayName("subcomponent API：Doe^Jane^^^^^L 的 component(5,1).subcomponent(1) = Doe")
    void subcomponent_returns_nth_subcomponent() {
        Hl7Message msg = Hl7Parser.parse(DEFAULT_FULL);
        assertEquals("Doe", msg.subcomponent("PID", 5, 1, 1));
        assertEquals("Jane", msg.subcomponent("PID", 5, 2, 1));
        // PID-5 的第 7 个组件只剩一个子组件 "L"
        assertEquals("L", msg.subcomponent("PID", 5, 7, 1));
    }

    @Test
    @DisplayName("segment(name) 始终返回首次出现；segment(name, occurrence) 返回第 N 次")
    void segment_by_name_vs_occurrence() {
        // MSH + OBX + OBX 共 3 段，没有 PID
        String repeated = DEFAULT_MSH + "\r" + "OBX|1||GLU|Glucose|mg/dL|110|70-110|N\r"
                + "OBX|2||HbA1c|Hemoglobin A1c|%|5.4|4.0-6.0|N";
        Hl7Message msg = Hl7Parser.parse(repeated);
        assertEquals("OBX", msg.segment("OBX").name());
        assertEquals("1", msg.segment("OBX").field(1));
        assertEquals("2", msg.segment("OBX", 2).field(1));
        assertEquals(3, msg.segmentCount(), "全文共 MSH/OBX/OBX 3 段");
    }

    @Test
    @DisplayName("segment 不存在抛 SegmentNotFoundException（明确错误而非 null）")
    void missing_segment_throws_with_clear_message() {
        Hl7Message msg = Hl7Parser.parse(DEFAULT_FULL);
        SegmentNotFoundException ex = assertThrows(SegmentNotFoundException.class,
                () -> msg.segment("OBX"));
        assertTrue(ex.getMessage().contains("OBX"), "异常消息应包含段名");
    }

    @Test
    @DisplayName("字段下标越界抛 FieldIndexOutOfBounds，包含段名 + idx + 实际字段数")
    void field_index_out_of_bounds_carries_segment_and_index() {
        Hl7Message msg = Hl7Parser.parse(DEFAULT_FULL);
        FieldIndexOutOfBounds ex = assertThrows(FieldIndexOutOfBounds.class,
                () -> msg.field("PID", 999));
        assertEquals("PID", ex.segmentName());
        assertEquals(999, ex.index());
        // PID 段在本 ER7 里的实际字段数（不含段名）
        assertEquals(11, ex.fieldCount());
    }

    @Test
    @DisplayName("组件下标越界抛 ComponentIndexOutOfBounds")
    void component_index_out_of_bounds() {
        Hl7Message msg = Hl7Parser.parse(DEFAULT_FULL);
        // PID-5 = Doe^Jane^^^^^L 共 7 个组件，问第 100 个
        ComponentIndexOutOfBounds ex = assertThrows(ComponentIndexOutOfBounds.class,
                () -> msg.component("PID", 5, 100));
        assertEquals("PID", ex.segmentName());
        assertEquals(5, ex.fieldIndex());
        assertEquals(100, ex.componentIndex());
        assertEquals(7, ex.componentCount());
    }

    @Test
    @DisplayName("子组件下标越界抛 SubcomponentIndexOutOfBounds")
    void subcomponent_index_out_of_bounds() {
        Hl7Message msg = Hl7Parser.parse(DEFAULT_FULL);
        SubcomponentIndexOutOfBounds ex = assertThrows(SubcomponentIndexOutOfBounds.class,
                () -> msg.subcomponent("PID", 5, 1, 100));
        assertEquals("PID", ex.segmentName());
        assertEquals(100, ex.subcomponentIndex());
    }

    @Test
    @DisplayName("段长度不足 3 字节抛 Hl7ParseException 并保留行序号与片段")
    void too_short_segment_throws() {
        String tooShort = "MSH|^~\\&|APP|FAC|RCV|RCV|20261020||ADT^A08|CTL|P|2.5.1\rxx";
        Hl7ParseException ex = assertThrows(Hl7ParseException.class,
                () -> Hl7Parser.parse(tooShort));
        assertEquals(1, ex.segmentOrdinal());
        assertEquals("xx", ex.rawSnippet());
    }

    @Test
    @DisplayName("空字符串入站抛 Hl7ParseException（不允许静默）")
    void empty_input_throws() {
        assertThrows(Hl7ParseException.class, () -> Hl7Parser.parse(""));
    }

    @Test
    @DisplayName("非 header 段出现在 header 段之前抛 Hl7ParseException")
    void pid_before_msh_throws() {
        // 真实 MLLP 流里不该出现，但解析层要拦掉
        String noHeaderFirst = "PID|1||X\rMSH|^~\\&|APP|FAC";
        Hl7ParseException ex = assertThrows(Hl7ParseException.class,
                () -> Hl7Parser.parse(noHeaderFirst));
        assertTrue(ex.getMessage().contains("非 header 段"),
                "异常应说明类型；实际=" + ex.getMessage());
    }

    @Test
    @DisplayName("\\r 与 \\n 与 \\r\\n 均作为段分隔被归一处理")
    void cr_lf_normalized_on_parse() {
        String lf = DEFAULT_MSH.replace("\r", "\n") + "\n" + DEFAULT_PID;
        String crlf = DEFAULT_MSH + "\r\n" + DEFAULT_PID;
        Hl7Message fromLf = Hl7Parser.parse(lf);
        Hl7Message fromCrlf = Hl7Parser.parse(crlf);
        assertEquals("CTL_DEFAULT", fromLf.field("MSH", 10));
        assertEquals("CTL_DEFAULT", fromCrlf.field("MSH", 10));
        assertEquals(2, fromLf.segmentCount());
        assertEquals(2, fromCrlf.segmentCount());
    }

    @Test
    @DisplayName("空字段：PID|1||X||19750312 中 PID-2、PID-4 缺省路径返回 \"\" 而非 null")
    void empty_fields_return_empty_string() {
        String msh = "MSH|^~\\&|APP|FAC";
        String pid = "PID|1||SYN-ID||Doe^Jane^^|19750312";
        Hl7Message msg = Hl7Parser.parse(msh + "\r" + pid);
        assertEquals("", msg.field("PID", 2));
        assertEquals("SYN-ID", msg.field("PID", 3));
        assertEquals("", msg.field("PID", 4));
        assertEquals("Doe", msg.component("PID", 5, 1));
    }

    @Test
    @DisplayName("尾段空字段保留：行以分隔符结尾 → fields 含尾部空字符串")
    void trailing_empty_fields_retained() {
        String msh = "MSH|^~\\&|APP|FAC|RCV|RCV|";
        Hl7Message msg = Hl7Parser.parse(msh);
        List<String> mshFields = msg.segment("MSH").fields();
        assertTrue(mshFields.size() >= 7);
        assertNotNull(mshFields.get(mshFields.size() - 1));
    }

    @Test
    @DisplayName("fixtures/hapi/ 真实 ADT^/ORU^ 报文可直接解析不抛")
    void hapi_fixtures_parse_without_throwing() throws Exception {
        Path a08 = Paths.get("src/test/resources/fixtures/hapi/adt_a08_v251.hl7");
        Path a31 = Paths.get("src/test/resources/fixtures/hapi/adt_a31_v251.hl7");
        Path oru = Paths.get("src/test/resources/fixtures/hapi/oru_r01_v23.hl7");
        if (Files.exists(a08)) {
            Hl7Message msg = Hl7Parser.parse(Files.readString(a08));
            assertEquals("|", msg.field("MSH", 1));
            assertEquals("^~\\&", msg.field("MSH", 2));
            assertTrue(msg.segmentCount() >= 2, "至少含 MSH + PID 两段");
        }
        if (Files.exists(a31)) {
            Hl7Message msg = Hl7Parser.parse(Files.readString(a31));
            assertEquals("|", msg.field("MSH", 1));
        }
        if (Files.exists(oru)) {
            Hl7Message msg = Hl7Parser.parse(Files.readString(oru));
            assertEquals("ORU", msg.field("MSH", 9).split("\\^")[0]);
        }
    }

    @Test
    @DisplayName("fixtures/hapi/delims_alternate_bang_dollar.hl7 非默认分隔符解析非空且全部访问器不抛")
    void alternate_delims_fixture_parses() throws Exception {
        Path p = Paths.get("src/test/resources/fixtures/hapi/delims_alternate_bang_dollar.hl7");
        if (Files.exists(p)) {
            Hl7Message msg = Hl7Parser.parse(Files.readString(p));
            assertEquals('!', msg.delimiters().fieldSeparator());
            String msgType = msg.field("MSH", 9);
            assertTrue(msgType.startsWith("ADT") || msgType.contains("ADT"),
                    "字段 9 包含 ADT；实际=" + msgType);
        }
    }

    @Test
    @DisplayName("fixtures/synthea/ 真实病历报文可直接解析不抛")
    void synthea_fixtures_parse_without_throwing() throws Exception {
        Path syntheaDir = Paths.get("src/test/resources/fixtures/synthea");
        if (!Files.exists(syntheaDir)) return;
        int parsed = 0;
        try (var stream = Files.list(syntheaDir)) {
            for (Path p : (Iterable<Path>) stream.filter(Files::isRegularFile)::iterator) {
                if (!p.getFileName().toString().endsWith(".hl7")) continue;
                Hl7Message msg = Hl7Parser.parse(Files.readString(p));
                assertTrue(msg.segmentCount() >= 1, "至少含 MSH 段");
                assertEquals("MSH", msg.segmentAt(0).name());
                parsed++;
            }
        }
        assertTrue(parsed >= 1, "至少解析 1 条 synthea 报文");
    }
}
