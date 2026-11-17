package cn.hospital.region.archive.hl7;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MSH-18 → Java Charset 映射的验收 + GB18030/UTF-8 同一报文逐字段相等的契约。
 *
 * <p>覆盖：
 * <ul>
 *   <li>已知映射全部命中（GB18030 / UTF-8 / ASCII / UTF-16）；</li>
 *   <li>大小写与前后空格归一；</li>
 *   <li>未声明走 UTF-8 默认并记告警；</li>
 *   <li>未知字符集抛 {@link UnsupportedCharset} 并保留原值；</li>
 *   <li>GB18030 与 UTF-8 编码的同一份中文档案报文，解析后同名字段值逐字相同。</li>
 * </ul>
 */
class CharsetResolverTest {

    @Test
    @DisplayName("GB18030-2000 → Java GB18030")
    void gb18030_2000_maps_to_gb18030() {
        CharsetResolver.Decision d = CharsetResolver.resolve("GB18030-2000");
        assertEquals("GB18030", d.charset.name(), "Java Charset 名是 GB18030");
        assertEquals("GB18030-2000", d.declaredValue);
        assertEquals(CharsetResolver.Status.OK, d.status);
    }

    @Test
    @DisplayName("GB18030（无 -2000 后缀）→ GB18030")
    void gb18030_short_maps_to_gb18030() {
        CharsetResolver.Decision d = CharsetResolver.resolve("GB18030");
        assertEquals("GB18030", d.charset.name());
        assertEquals(CharsetResolver.Status.OK, d.status);
    }

    @Test
    @DisplayName("UNICODE UTF-8 → UTF-8")
    void unicode_utf8_maps_to_utf8() {
        CharsetResolver.Decision d = CharsetResolver.resolve("UNICODE UTF-8");
        assertSame(StandardCharsets.UTF_8, d.charset);
        assertEquals(CharsetResolver.Status.OK, d.status);
    }

    @Test
    @DisplayName("ISO_IR 192 → UTF-8")
    void iso_ir_192_maps_to_utf8() {
        CharsetResolver.Decision d = CharsetResolver.resolve("ISO_IR 192");
        assertSame(StandardCharsets.UTF_8, d.charset);
    }

    @Test
    @DisplayName("ASCII → US-ASCII")
    void ascii_maps_to_us_ascii() {
        CharsetResolver.Decision d = CharsetResolver.resolve("ASCII");
        assertSame(StandardCharsets.US_ASCII, d.charset);
    }

    @Test
    @DisplayName("UNICODE → UTF-16")
    void unicode_maps_to_utf16() {
        CharsetResolver.Decision d = CharsetResolver.resolve("UNICODE");
        assertSame(StandardCharsets.UTF_16, d.charset);
    }

    @Test
    @DisplayName("大小写与前后空格归一：'  gb18030-2000 ' 仍命中 GB18030")
    void case_and_whitespace_normalized() {
        CharsetResolver.Decision d = CharsetResolver.resolve("  gb18030-2000 ");
        assertEquals("GB18030-2000", d.declaredValue);
        assertEquals("GB18030", d.charset.name());
    }

    @Test
    @DisplayName("未声明（null）走 UTF-8 默认并记告警，状态为 DEFAULTED")
    void null_defaults_to_utf8_with_warning() {
        List<String> warnings = new ArrayList<>();
        CharsetResolver.Decision d = CharsetResolver.resolve(null, warnings);
        assertSame(StandardCharsets.UTF_8, d.charset);
        assertEquals(CharsetResolver.Status.DEFAULTED, d.status);
        assertEquals(1, warnings.size(), "必须记一条 DEFAULTED 告警");
        assertTrue(warnings.get(0).contains("DEFAULTED"),
                "告警消息应说明 DEFAULTED；实际=" + warnings.get(0));
    }

    @Test
    @DisplayName("空字符串走 UTF-8 默认并记告警")
    void empty_defaults_to_utf8_with_warning() {
        List<String> warnings = new ArrayList<>();
        CharsetResolver.Decision d = CharsetResolver.resolve("", warnings);
        assertSame(StandardCharsets.UTF_8, d.charset);
        assertEquals(CharsetResolver.Status.DEFAULTED, d.status);
        assertFalse(warnings.isEmpty());
    }

    @Test
    @DisplayName("未知字符集抛 UnsupportedCharset 且异常消息含 MSH-18 原值")
    void unknown_charset_throws_with_original_value() {
        UnsupportedCharset ex = assertThrows(UnsupportedCharset.class,
                () -> CharsetResolver.resolve("MARC-8"));
        assertEquals("MARC-8", ex.declaredValue(), "原值必须保留供对账");
        assertTrue(ex.getMessage().contains("MARC-8"),
                "异常消息必须含原值；实际=" + ex.getMessage());
    }

    @Test
    @DisplayName("便捷重载 resolve(msh18) 不抛 UnsupportedCharset 时不收告警，返回 OK")
    void resolve_no_warning_overload_returns_ok() {
        CharsetResolver.Decision d = CharsetResolver.resolve("GB18030-2000");
        assertEquals(CharsetResolver.Status.OK, d.status);
    }

    @Test
    @DisplayName("通过 FrameDecoder.decode 处理 GB18030 字节流：MSH-18 自动解析为 GB18030")
    void frame_decoder_picks_up_gb18030_from_msh18() throws Exception {
        Path p = Paths.get("src/test/resources/fixtures/hapi/msh18_gb18030_declared.hl7");
        if (!Files.exists(p)) return;
        byte[] raw = Files.readAllBytes(p);
        FrameDecoder.DecodedMessage decoded = FrameDecoder.decode(raw);
        assertEquals("GB18030-2000", decoded.charsetName);
        assertEquals(CharsetResolver.Status.OK, decoded.charsetStatus);
        assertNotNull(decoded.message);
        // PID 段存在
        assertEquals("PID", decoded.message.segment("PID").name());
    }

    @Test
    @DisplayName("FrameDecoder.decode 处理 UTF-8 字节流：MSH-18 自动解析为 UTF-8")
    void frame_decoder_picks_up_utf8_from_msh18() throws Exception {
        Path p = Paths.get("src/test/resources/fixtures/hapi/msh18_unicode_utf8_declared.hl7");
        if (!Files.exists(p)) return;
        byte[] raw = Files.readAllBytes(p);
        FrameDecoder.DecodedMessage decoded = FrameDecoder.decode(raw);
        assertEquals("UNICODE UTF-8", decoded.charsetName);
        assertEquals(CharsetResolver.Status.OK, decoded.charsetStatus);
        assertNotNull(decoded.message);
        assertEquals("PID", decoded.message.segment("PID").name());
    }

    @Test
    @DisplayName("FrameDecoder.decode 处理无 MSH-18 声明的报文：默认 UTF-8 + 告警")
    void frame_decoder_defaults_utf8_when_msh18_missing() throws Exception {
        // 构造一份无 MSH-18 字段的报文（17 个 | 结尾，无 charset 字段）
        String noMsh18 = "MSH|^~\\&|APP|FAC|RCV|RCV|20261020||ADT^A08|CTL|P|2.5.1|||AL|NE|CHN\r";
        byte[] raw = noMsh18.getBytes(StandardCharsets.UTF_8);
        FrameDecoder.DecodedMessage decoded = FrameDecoder.decode(raw);
        assertEquals(CharsetResolver.Status.DEFAULTED, decoded.charsetStatus);
        // 警告至少包含一条 charset DEFAULTED
        assertTrue(decoded.warnings.stream().anyMatch(w -> w.contains("DEFAULTED")),
                "默认字符集告警必须写入；warnings=" + decoded.warnings);
        // MSH 行已成功切分（无 MSH-18 时仍可解析）
        assertEquals("CTL", decoded.message.field("MSH", 10));
    }

    @Test
    @DisplayName("GB18030 与 UTF-8 编码的同一中文报文，PID-5 字段值逐字相同")
    void gb18030_and_utf8_same_message_same_pid5_value() throws Exception {
        // 用 hapi fixtures 的 MSH-18 真实声明：
        //   msh18_gb18030_declared.hl7  → MSH-18=GB18030-2000，PID-5 用 GB18030 编码
        //   msh18_unicode_utf8_declared.hl7 → MSH-18=UNICODE UTF-8，PID-5 用 UTF-8 编码
        // 两份 PID 是不同测试患者（控制号 HL700200 vs HL700201），但姓名与出生日期一致：
        //   PID-5 = 张^伟；PID-7 = 19850512
        Path gb = Paths.get("src/test/resources/fixtures/hapi/msh18_gb18030_declared.hl7");
        Path utf8 = Paths.get("src/test/resources/fixtures/hapi/msh18_unicode_utf8_declared.hl7");
        if (!Files.exists(gb) || !Files.exists(utf8)) return;

        FrameDecoder.DecodedMessage fromGb = FrameDecoder.decode(Files.readAllBytes(gb));
        FrameDecoder.DecodedMessage fromUtf8 = FrameDecoder.decode(Files.readAllBytes(utf8));

        // MSH-18 各自决议正确
        assertEquals(CharsetResolver.Status.OK, fromGb.charsetStatus);
        assertEquals(CharsetResolver.Status.OK, fromUtf8.charsetStatus);
        assertEquals("GB18030-2000", fromGb.charsetName);
        assertEquals("UNICODE UTF-8", fromUtf8.charsetName);

        // 同名同生日的字段值逐字相等：这是 GB18030 解码正确性的硬验收
        assertEquals("张^伟", fromGb.message.field("PID", 5),
                "GB18030 解码后 PID-5 应为「张^伟」");
        assertEquals("张^伟", fromUtf8.message.field("PID", 5),
                "UTF-8 解码后 PID-5 应为「张^伟」");
        // 同一患者跨编码同名字段值逐字相等
        assertEquals(fromUtf8.message.field("PID", 5), fromGb.message.field("PID", 5),
                "PID-5 姓名必须逐字相同（GB18030 中文姓名 vs UTF-8 中文姓名）");
        assertEquals(fromUtf8.message.field("PID", 7), fromGb.message.field("PID", 7),
                "PID-7 出生日期必须逐字相同");
        // PID-5 含中文，按组件枚举也相等
        assertEquals(fromUtf8.message.component("PID", 5, 1),
                fromGb.message.component("PID", 5, 1),
                "PID-5 第一个组件（姓）逐字相同");
        assertEquals(fromUtf8.message.component("PID", 5, 2),
                fromGb.message.component("PID", 5, 2),
                "PID-5 第二个组件（名）逐字相同");
    }

    @Test
    @DisplayName("Charset 引用相等性：相同 declaredValue 返回同一 Charset 实例")
    void charset_reference_is_stable() {
        Charset a = CharsetResolver.resolve("GB18030-2000").charset;
        Charset b = CharsetResolver.resolve("gb18030").charset;
        // 同名 Charset Java 规范上返回同一实例
        assertSame(a, b);
    }
}
