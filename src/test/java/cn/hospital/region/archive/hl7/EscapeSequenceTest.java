package cn.hospital.region.archive.hl7;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FSRET 五类转义序列还原 + 未成对转义告警的验收。
 *
 * <p>复刻源产品 {@code github_ref/dcm4che/dcm4che-hl7/.../HL7Parser.java:113-131} 的
 * ACK/NAK 时序对应字段值处理：
 * <ul>
 *   <li>完整 {@code \X\} → 对应分隔符；</li>
 *   <li>未成对转义 → 保留原文并写告警，不抛错。</li>
 * </ul>
 */
class EscapeSequenceTest {

    private static final Delimiters D = Delimiters.DEFAULT; // | ^ ~ \ &

    private static String restoreCollect(String raw) {
        List<String> warnings = new ArrayList<>();
        String r = Escapes.restore(raw, D, warnings);
        return r; // 单独跑警告断言用另一个重载
    }

    @Test
    @DisplayName("\\F\\ → 字段分隔符 |")
    void fs_maps_to_field_separator() {
        String r = Escapes.restore("A\\F\\B", D);
        assertEquals("A|B", r);
    }

    @Test
    @DisplayName("\\S\\ → 组件分隔符 ^")
    void s_maps_to_component_separator() {
        String r = Escapes.restore("X\\S\\Y", D);
        assertEquals("X^Y", r);
    }

    @Test
    @DisplayName("\\R\\ → 重复分隔符 ~")
    void r_maps_to_repetition_separator() {
        String r = Escapes.restore("X\\R\\Y", D);
        assertEquals("X~Y", r);
    }

    @Test
    @DisplayName("\\E\\ → 转义字符本身 \\")
    void e_maps_to_escape_character() {
        String r = Escapes.restore("X\\E\\Y", D);
        assertEquals("X\\Y", r);
    }

    @Test
    @DisplayName("\\T\\ → 子组件分隔符 &")
    void t_maps_to_subcomponent_separator() {
        String r = Escapes.restore("X\\T\\Y", D);
        assertEquals("X&Y", r);
    }

    @Test
    @DisplayName("非默认分隔符（!$%*&）下转义仍按当前 escape 字符还原（不硬编码 \\）")
    void restore_uses_active_delimiters_not_hardcoded() {
        // escape 字符是 *，所以转义序列是 *F*（→字段分隔符 !）、*S*（→组件分隔符 $）
        Delimiters alt = Delimiters.fromChars('!', '$', '%', '*', '&');
        String r = Escapes.restore("A*F*B*S*C", alt);
        assertEquals("A!B$C", r);
        // 反例：用错误的 \X\ 形式（escape 字符实际是 *），应原样保留
        List<String> warnings = new ArrayList<>();
        String r2 = Escapes.restore("A\\F\\B", alt, warnings);
        assertEquals("A\\F\\B", r2, "非默认 escape 字符下，\\X\\ 不是有效转义序列");
        assertTrue(warnings.isEmpty(), "非默认 escape 字符下不应产生未成对告警");
    }

    @Test
    @DisplayName("完整 \\A\\（X 不在 FSRET 内）按非标准转义文本原样保留 \\A\\")
    void unrecognized_x_retained_as_literal_text() {
        String r = Escapes.restore("A\\A\\B", D);
        assertEquals("A\\A\\B", r);
    }

    @Test
    @DisplayName("未成对 \\F（缺关闭符）：保留 \\F 并写告警，不抛错")
    void unpaired_escape_warns_and_retains() {
        List<String> warnings = new ArrayList<>();
        String r = Escapes.restore("A\\FB", D, warnings);
        assertEquals("A\\FB", r);
        assertFalse(warnings.isEmpty(), "未成对转义必须写告警");
        assertTrue(warnings.get(0).contains("未成对"),
                "告警消息应说明「未成对」；实际=" + warnings.get(0));
    }

    @Test
    @DisplayName("字段末尾单个 \\ ：保留 \\ 并写告警")
    void trailing_backslash_warns_and_retains() {
        List<String> warnings = new ArrayList<>();
        String r = Escapes.restore("AB\\", D, warnings);
        assertEquals("AB\\", r);
        assertFalse(warnings.isEmpty(), "末尾 \\ 必须写告警");
    }

    @Test
    @DisplayName("\\<eof> 类的 \\X 末尾：保留 \\X 并写告警")
    void trailing_x_after_backslash_warns_and_retains() {
        List<String> warnings = new ArrayList<>();
        String r = Escapes.restore("AB\\X", D, warnings);
        assertEquals("AB\\X", r);
        assertFalse(warnings.isEmpty());
    }

    @Test
    @DisplayName("\\F\\ 与未成对 \\X 混合：\\F\\ 还原、\\X 保留并记一次告警")
    void mixed_paired_and_unpaired() {
        List<String> warnings = new ArrayList<>();
        String r = Escapes.restore("A\\F\\B\\XC", D, warnings);
        assertEquals("A|B\\XC", r);
        assertFalse(warnings.isEmpty(), "未成对 \\X 必须记告警");
    }

    @Test
    @DisplayName("空字符串与 null 入参安全返回")
    void empty_and_null_safe() {
        assertEquals("", Escapes.restore("", D));
        assertEquals(null, Escapes.restore((String) null, D));
    }

    @Test
    @DisplayName("无转义符的普通字段值原样返回，不产生告警")
    void plain_value_passes_through_without_warning() {
        List<String> warnings = new ArrayList<>();
        String r = Escapes.restore("hello world 张三", D, warnings);
        assertEquals("hello world 张三", r);
        assertTrue(warnings.isEmpty(), "普通文本不应产生告警");
    }

    @Test
    @DisplayName("通过 Hl7Parser.parse 还原真实 esc_sequences_v251 报文：含五类转义")
    void esc_sequences_v251_fixture_restores_via_parser() throws Exception {
        Path p = Paths.get("src/test/resources/fixtures/hapi/esc_sequences_v251.hl7");
        if (!Files.exists(p)) return;
        List<String> warnings = new ArrayList<>();
        Hl7Message msg = Hl7Parser.parse(Files.readString(p), warnings);
        // 该 fixture 在 PID-4 放置带 FSRET 五类转义的样例值
        //   原始 = A\F\B\S\C\T\D\R\E\F\^^^^^L
        //   还原 = A|B^C&D~E|^^^^^L
        // 用 fields() 取原始索引避开 field() 的 ~ 切分逻辑
        String pid4Raw = msg.segment("PID").fields().get(4 - 1);  // fields[3] = PID-4
        assertEquals("A|B^C&D~E|^^^^^L", pid4Raw);
        // 这条报文所有 \X\ 都是完整且 X ∈ FSRET，没有未成对告警
        assertTrue(warnings.isEmpty(), "完整转义序列不应产生告警；实际=" + warnings);
    }

    @Test
    @DisplayName("通过 Hl7Parser.parse 处理 unpaired_escape_v251 报文：未成对部分原样保留")
    void unpaired_escape_v251_fixture_warns_and_retains_via_parser() throws Exception {
        Path p = Paths.get("src/test/resources/fixtures/hapi/unpaired_escape_v251.hl7");
        if (!Files.exists(p)) return;
        List<String> warnings = new ArrayList<>();
        Hl7Message msg = Hl7Parser.parse(Files.readString(p), warnings);
        // 该 fixture 在 PID-4 放置带未成对转义的样例值
        //   原始 = A\F\B\\S\C^^^^^L
        //   \F\  → |       → 已还原
        //   \\   → 保留 \\ → 未成对
        //   \C   → 保留 \C → 未成对
        String pid4Raw = msg.segment("PID").fields().get(4 - 1);
        assertEquals("A|B\\\\S\\C^^^^^L", pid4Raw);
        // 至少应产生一条未成对告警
        assertFalse(warnings.isEmpty(), "未成对转义必须产生告警");
        assertTrue(warnings.get(0).contains("未成对"),
                "告警消息应说明「未成对」；实际=" + warnings.get(0));
    }

    // ---- 正向转义（T8 出站拼装用：MSA-3 / ERR-4 自由文本必须先转义再拼）----

    @Test
    @DisplayName("escape：五类分隔符逐个写成 \\X\\，其余字符原样保留")
    void escape_writes_fsret_markers() {
        assertEquals("A\\F\\B", Escapes.escape("A|B", D));
        assertEquals("A\\S\\B", Escapes.escape("A^B", D));
        assertEquals("A\\R\\B", Escapes.escape("A~B", D));
        assertEquals("A\\T\\B", Escapes.escape("A&B", D));
        assertEquals("A\\E\\B", Escapes.escape("A\\B", D));
        assertEquals("中文abc123", Escapes.escape("中文abc123", D));
    }

    @Test
    @DisplayName("escape 与 restore 成对：还原结果逐字等于原文")
    void escape_restore_round_trip_is_lossless() {
        String raw = "MSH|^~\\&|REGIONAL_ARCHIVE|FACILI，第 1 段";
        String escaped = Escapes.escape(raw, D);

        assertFalse(escaped.contains("|"), "转义后不得残留裸字段分隔符，否则出站报文会被切错位");
        assertEquals(raw, Escapes.restore(escaped, D));
    }

    @Test
    @DisplayName("escape 按目标报文的自述分隔符转义，不用本端默认值")
    void escape_honours_non_default_delimiters() {
        // 字段 '!'、组件 '$'、重复 '#'、转义 '@'、子组件 '%'
        Delimiters bang = Delimiters.fromChars('!', '$', '#', '@', '%');
        String escaped = Escapes.escape("A!B$C#D@E%F", bang);

        // 目标报文里这五个字符全是分隔符，必须逐个写成 @X@（转义符是 @）
        assertEquals("A@F@B@S@C@R@D@E@E@T@F", escaped);
        assertEquals("A!B$C#D@E%F", Escapes.restore(escaped, bang));
        // 本端默认分隔符 '|' 在目标报文里是普通字符：不得被转义，否则对端收到的是转义符
        assertEquals("A|B", Escapes.escape("A|B", bang));
    }
}
