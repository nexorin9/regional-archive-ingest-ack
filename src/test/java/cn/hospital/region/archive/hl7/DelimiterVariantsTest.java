package cn.hospital.region.archive.hl7;

import cn.hospital.region.archive.fixtures.FixturesManifest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分隔符与双编码的**契约测试**：同一份业务内容，换分隔符、换字节编码，业务字段值必须逐字相同。
 *
 * <p>为什么这层值得单独立一个测试：国内区域平台的 HL7 实现极不统一，默认 {@code |^~\&} 之外
 * 还能见到 {@code !$%*&}、{@code #@~!%} 乃至 {@code :<>[]} 这类自定义组合。
 * 只要解析器里有任何一处把分隔符写死（历史缺陷：曾用 {@code String.split} 未转义，
 * 把 {@code | ^ & $ *} 全当成正则元字符），这类报文就会整批掉出来。契约测试就是钉死这条线。
 *
 * <p>覆盖：
 * <ul>
 *   <li>5 组分隔符变体逐组解析成功，且同一逻辑字段值跨变体完全一致；</li>
 *   <li>分隔符来自 MSH 自读（{@code substring(3,8)}），不是代码里的常量；</li>
 *   <li>语料里的非默认分隔符样例解析成功；</li>
 *   <li>ERR 段样例解析成功且 ERR-3 能指向具体段字段；</li>
 *   <li>GB18030 与 UTF-8 的同一中文报文，按各自编码解码后字段值逐字相同，
 *       且**用错编码会得到不同值**（证明样本真的是两种字节编码，不是同一份文件改名）。</li>
 * </ul>
 */
class DelimiterVariantsTest {

    private static final Charset GB18030 = Charset.forName("GB18030");

    /** 5 组分隔符变体：字段 / 组件 / 重复 / 转义 / 子组件，各组 5 字符互不相同。 */
    private static final List<Delimiters> VARIANTS = List.of(
            Delimiters.fromChars('|', '^', '~', '\\', '&'),   // HL7 默认
            Delimiters.fromChars('!', '$', '%', '*', '&'),   // 国内平台常见（语料里就有这一组）
            Delimiters.fromChars('#', '@', '~', '!', '%'),
            Delimiters.fromChars(';', '*', '$', '@', '!'),
            Delimiters.fromChars(':', '<', '>', '[', ']'));

    // ------------------------------------------------------------ 分隔符变体

    @Test
    @DisplayName("5 组分隔符变体解析出的业务字段值完全一致")
    void five_delimiter_variants_yield_identical_field_values() {
        Map<String, String> baseline = null;
        for (Delimiters d : VARIANTS) {
            Hl7Message msg = Hl7Parser.parse(buildMessage(d));
            Map<String, String> logical = logicalFields(msg);

            if (baseline == null) {
                baseline = logical;
                continue;
            }
            assertEquals(baseline, logical,
                    "分隔符 " + d.asString() + " 解析出的字段值与其他变体不一致");
        }
        assertNotEquals(null, baseline);
        assertEquals(16, baseline.size(), "逻辑字段清单应覆盖 MSH-9/10/11/12/18、PID-3/5/7、PV1-3、ZX1-1 等");
    }

    @Test
    @DisplayName("每组变体单独解析都不抛错，且必需字段齐备（不报阻断错）")
    void each_variant_parses_without_blocking_error() {
        for (Delimiters d : VARIANTS) {
            Hl7Message msg = Hl7Parser.parse(buildMessage(d));

            assertTrue(FieldLocator.firstBlocking(msg).isEmpty(),
                    "分隔符 " + d.asString() + " 的报文不应报阻断错：" + FieldLocator.locate(msg));
        }
    }

    @Test
    @DisplayName("分隔符从 MSH 自读（substring(3,8)），不是代码常量")
    void delimiters_are_read_from_header_not_hardcoded() {
        for (Delimiters declared : VARIANTS) {
            String mshLine = buildMsh(declared);

            Delimiters read = Delimiters.readFromHeader(mshLine);

            assertEquals(declared.fieldSeparator(), read.fieldSeparator());
            assertEquals(declared.componentSeparator(), read.componentSeparator());
            assertEquals(declared.repetitionSeparator(), read.repetitionSeparator());
            assertEquals(declared.escapeCharacter(), read.escapeCharacter());
            assertEquals(declared.subcomponentSeparator(), read.subcomponentSeparator());
            assertEquals(declared, read);
        }
        assertEquals(5, VARIANTS.stream().map(Delimiters::asString).distinct().count(),
                "5 组变体应互不相同，否则测不到「未写死」");
    }

    @Test
    @DisplayName("中文姓名在非 ^ 组件分隔符下同样按组件切开")
    void chinese_name_splits_by_declared_component_separator() {
        for (Delimiters d : VARIANTS) {
            Hl7Message msg = Hl7Parser.parse(buildMessage(d));

            assertEquals("张", msg.component("PID", 5, 1), "分隔符 " + d.asString());
            assertEquals("伟", msg.component("PID", 5, 2), "分隔符 " + d.asString());
        }
    }

    // ------------------------------------------------------------ 语料契约

    @Test
    @DisplayName("语料里的非默认分隔符样例解析成功")
    void alternate_delimiter_fixture_from_corpus_parses() throws IOException {
        Hl7Message msg = Hl7Parser.parse(readText(FixturesManifest.HAPI_DIR.resolve("delims_alternate_bang_dollar.hl7")));

        assertEquals("!$%*&", msg.delimiters().asString(), "分隔符应从样例自读");
        assertEquals("ADT^A08", msg.component("MSH", 9, 1) + "^" + msg.component("MSH", 9, 2));
        assertEquals("HL700300", msg.field("MSH", 10));
        assertEquals("Synthea", msg.component("PID", 3, 4));
        assertTrue(FieldLocator.firstBlocking(msg).isEmpty(), FieldLocator.locate(msg).toString());
    }

    @Test
    @DisplayName("ERR 段样例解析成功，ERR-3 能指向具体段字段")
    void err_segment_sample_from_corpus_parses_and_locates_segment_field() throws IOException {
        Hl7Message msg = Hl7Parser.parse(readText(FixturesManifest.HAPI_DIR.resolve("ack_ae_err_v251.hl7")));

        assertTrue(msg.has("MSA"), "ACK 样例须含 MSA 段");
        assertEquals("AE", msg.field("MSA", 1), "MSA-1 应为 AE");
        // MSA-2 回显的是**被确认报文**的 MSH-10（HL700002），不是本条回执自己的 MSH-10（ACK000002）。
        // T8 组 MSA 时必须取入站报文的控制号，取成回执自己的号就会对不上账。
        assertEquals("HL700002", msg.field("MSA", 2));
        assertEquals("ACK000002", msg.field("MSH", 10), "回执自身的 MSH-10 是回执自己的控制号");
        assertFalse(msg.field("MSA", 2).equals(msg.field("MSH", 10)));

        assertTrue(msg.has("ERR"), "ACK 样例须含 ERR 段");
        String err3 = msg.component("ERR", 3, 1);
        assertEquals("MSH", err3, "ERR-3-1 指向出错段 MSH");
        assertEquals("10", msg.component("ERR", 3, 2), "ERR-3-2 指向出错字段 MSH-10");
        assertEquals("Missing required field", msg.component("ERR", 3, 4), "ERR-3-4 为 HL7 标准说明");
    }

    @Test
    @DisplayName("语料 ERR 样例指出的 MSH-10，与 FieldLocator 对同一条报文的定位一致")
    void corpus_err_reference_agrees_with_field_locator() {
        // ERR-3 = MSH^10^1^Missing required field：语料声明缺的是 MSH-10，
        // FieldLocator 对同一份必填清单也必须把 MSH-10 判成阻断错，两处口径不能打架。
        FieldLocator.RequiredField msh10 = FieldLocator.requiredFields().stream()
                .filter(f -> f.segmentName().equals("MSH") && f.fieldIndex() == 10)
                .findFirst().orElseThrow();

        assertEquals(FieldLocator.CODE_CONTROL_ID_MISSING, msh10.code());
        assertTrue(msh10.severity().blocking());
        assertEquals("MSH^10^1", LocatableError.of("MSH", 10, 0, "MSH|...",
                msh10.code(), msh10.severity(), "").errSegmentReference());
    }

    // ------------------------------------------------------------ 双编码契约

    @Test
    @DisplayName("MSH-18 自述的 GB18030 与 UTF-8 同一报文，解析结果逐字相同")
    void gb18030_and_utf8_declared_samples_yield_same_fields() throws IOException {
        FrameDecoder.DecodedMessage gb = FrameDecoder.decode(
                Files.readAllBytes(FixturesManifest.HAPI_DIR.resolve("msh18_gb18030_declared.hl7")));
        FrameDecoder.DecodedMessage utf8 = FrameDecoder.decode(
                Files.readAllBytes(FixturesManifest.HAPI_DIR.resolve("msh18_unicode_utf8_declared.hl7")));

        assertEquals("GB18030-2000", gb.charsetName, "MSH-18 原值应被保留在解码结果里");
        assertEquals("UNICODE UTF-8", utf8.charsetName);
        // 两条样例控制号不同（HL700200 / HL700201），可比的是中文身份字段
        assertEquals(utf8.message.component("PID", 5, 1), gb.message.component("PID", 5, 1));
        assertEquals(utf8.message.component("PID", 5, 2), gb.message.component("PID", 5, 2));
        assertEquals(utf8.message.field("PID", 7), gb.message.field("PID", 7));
        assertEquals(utf8.message.field("MSH", 12), gb.message.field("MSH", 12));
        assertEquals("张", gb.message.component("PID", 5, 1));
        assertEquals("伟", gb.message.component("PID", 5, 2));
        assertEquals("19850512", gb.message.field("PID", 7));
    }

    @Test
    @DisplayName("中文变体集的 GB18030/UTF-8 孪生样本解码后逐字相同，且用错编码会得到不同值")
    void gb18030_and_utf8_zh_twins_yield_same_fields() throws IOException {
        List<Path> twins = List.of(
                FixturesManifest.ZH_DIR.resolve("zh_0001_utf8.hl7"),
                FixturesManifest.ZH_DIR.resolve("zh_0001_gb18030.hl7"));

        Hl7Message asUtf8 = Hl7Parser.parse(readText(twins.get(0)));
        Hl7Message asGb = Hl7Parser.parse(readText(twins.get(1)));

        assertEquals(contentFields(asUtf8), contentFields(asGb),
                "GB18030 孪生样本按 GB18030 解码后应与 UTF-8 孪生样本逐字相同");

        // 声明必须各随自己的编码：两份样本的 MSH-18 本就该不同，
        // 相同反而说明语料把 GB18030 字节写成了自述 UTF-8 —— 主路径会解出乱码且不报错。
        assertEquals("UNICODE UTF-8", asUtf8.field("MSH", 18));
        assertEquals("GB18030-2000", asGb.field("MSH", 18));

        // 反向断言：用错字符集解码必须得到不同值 —— 否则说明「双编码」是同一份字节改了名
        String wrong = Hl7Parser.parse(
                new String(Files.readAllBytes(twins.get(1)), StandardCharsets.UTF_8)).field("PID", 5);
        assertNotEquals(asGb.field("PID", 5), wrong,
                "GB18030 字节按 UTF-8 强解后姓名应变为乱码，证明样本确实是两种编码");
    }

    @Test
    @DisplayName("中文变体集在 UTF-8 与 GB18030 两类编码下都能整批解析成功")
    void zh_corpus_parses_under_both_encodings() throws IOException {
        int utf8 = 0;
        int gb = 0;
        for (Path p : FixturesManifest.zhVariantFiles()) {
            Hl7Message msg = Hl7Parser.parse(readText(p));
            assertTrue(FieldLocator.firstBlocking(msg).isEmpty(),
                    p.getFileName() + " 不应报阻断错：" + FieldLocator.locate(msg));
            if (p.getFileName().toString().contains("gb18030")) gb++; else utf8++;
        }
        assertTrue(utf8 >= 20 && gb >= 20, "两类编码样本都须成规模（utf8=" + utf8 + " gb18030=" + gb + "）");
    }

    // ------------------------------------------------------------ 构造与读取

    /** 按给定分隔符构造一条完整 ER7：MSH + PID（含组件与重复）+ PV1 + Z 段。 */
    private static String buildMessage(Delimiters d) {
        char f = d.fieldSeparator(), c = d.componentSeparator(), r = d.repetitionSeparator();
        String pid = "PID"
                + f + "1"                    // PID-1
                + f                          // PID-2 空
                // PID-3 两个重复，重复分隔符 r 同样来自 MSH 自述
                + f + join(c, "PAT-REMOTE-001", "", "", "REGION_HIS", "MR") + r + "PAT-ALT-002"
                + f                          // PID-4 空
                + f + join(c, "张", "伟")     // PID-5
                + f                          // PID-6 空
                + f + "19850512"             // PID-7
                + f + "F";                   // PID-8
        String pv1 = "PV1" + f + "1" + f + "I" + f + "OUTPATIENT";
        String zx = "ZX1" + f + "档案更新类型";
        return buildMsh(d) + "\r" + pid + "\r" + pv1 + "\r" + zx + "\r";
    }

    /** 用给定组件分隔符拼组件列表（末位不加尾分隔符）。 */
    private static String join(char componentSeparator, String... parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append(componentSeparator);
            sb.append(parts[i]);
        }
        return sb.toString();
    }

    private static String buildMsh(Delimiters d) {
        char f = d.fieldSeparator(), c = d.componentSeparator();
        return "MSH" + f + d.asString().substring(1)          // MSH-1 分隔符 + MSH-2 四个编码字符
                + f + "REGIONAL_ARCHIVE"                      // MSH-3
                + f + "REGION_HIS"                            // MSH-4
                + f + "COMMUNITY_HEALTH"                      // MSH-5
                + f + "COMMUNITY_CENTER"                      // MSH-6
                + f + "20261020081500+0800"                   // MSH-7
                + f                                           // MSH-8 空
                + f + "ADT" + c + "A08"                       // MSH-9
                + f + "CTRL-DELIM-0001"                       // MSH-10
                + f + "P"                                     // MSH-11
                + f + "2.5.1"                                 // MSH-12
                + f + f + f                                   // MSH-13 / MSH-14 / MSH-15 空
                + f + "AL"                                    // MSH-16
                + f + "NE"                                    // MSH-17
                + f + "UNICODE UTF-8";                        // MSH-18
    }

    /**
     * 取一组与分隔符无关的「逻辑字段值」：同一业务含义在不同分隔符下必须取到同一个值。
     * 跨变体比对统一走组件/重复访问器，不比较原始行。
     */
    /**
     * 内容字段：<b>不含 MSH-18</b>。
     *
     * <p>MSH-18 是编码声明而不是消息内容，两份编码孪生样本里它本来就该不同
     * （UTF-8 那份自述 {@code UNICODE UTF-8}、GB18030 那份自述 {@code GB18030-2000}），
     * 声明各随自己的落盘编码才是对的：声明与字节对不上时字符集解析会按声明解出乱码
     * 且一声不响。所以比「同一条报文两种编码」时要比内容，不能把声明一起比。
     */
    private static Map<String, String> contentFields(Hl7Message m) {
        Map<String, String> out = new LinkedHashMap<>(logicalFields(m));
        out.remove("MSH-18");
        return out;
    }

    private static Map<String, String> logicalFields(Hl7Message m) {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("MSH-3", m.field("MSH", 3));
        out.put("MSH-9-1", m.component("MSH", 9, 1));
        out.put("MSH-9-2", m.component("MSH", 9, 2));
        out.put("MSH-10", m.field("MSH", 10));
        out.put("MSH-11", m.field("MSH", 11));
        out.put("MSH-12", m.field("MSH", 12));
        out.put("MSH-18", m.field("MSH", 18));
        put(out, m, "PID", "PID-3-1", () -> m.component("PID", 3, 1));
        put(out, m, "PID", "PID-3-4", () -> m.component("PID", 3, 4));
        put(out, m, "PID", "PID-3-5", () -> m.component("PID", 3, 5));
        put(out, m, "PID", "PID-3-rep2", () -> m.repetitions("PID", 3).get(1));
        put(out, m, "PID", "PID-5-1", () -> m.component("PID", 5, 1));
        put(out, m, "PID", "PID-5-2", () -> m.component("PID", 5, 2));
        put(out, m, "PID", "PID-7", () -> m.field("PID", 7));
        put(out, m, "PV1", "PV1-3", () -> m.field("PV1", 3));
        put(out, m, "ZX1", "ZX1-1", () -> m.field("ZX1", 1));
        return out;
    }

    /**
     * 取一个逻辑字段值：段不存在则该条目整体跳过；字段越界退化为空串。
     *
     * <p>语料里的 ACK 只有 MSH/MSA、中文变体只有 MSH/PID/ZX —— 不跳过的话，
     * 「这条报文没有 PV1」会变成跨编码比对的噪声，把真正的不一致淹掉。
     */
    private static void put(Map<String, String> out, Hl7Message m, String segName, String key,
                            java.util.function.Supplier<String> getter) {
        if (!m.has(segName)) {
            return;
        }
        try {
            out.put(key, getter.get());
        } catch (RuntimeException e) {
            out.put(key, "");
        }
    }

    /** 按文件名判定编码：GB18030 样本按 GB18030 解，其余按 UTF-8（与 T4 的读数口径一致）。 */
    private static String readText(Path p) throws IOException {
        Charset cs = p.getFileName().toString().contains("gb18030") ? GB18030 : StandardCharsets.UTF_8;
        return new String(Files.readAllBytes(p), cs);
    }

    @Test
    @DisplayName("反向断言：默认分隔符报文与变体报文只在分隔符上不同")
    void variants_differ_only_in_delimiters() {
        String defaultMsg = buildMessage(VARIANTS.get(0));
        String bangMsg = buildMessage(VARIANTS.get(1));

        assertFalse(defaultMsg.equals(bangMsg), "两组报文源码应不同");
        assertEquals(logicalFields(Hl7Parser.parse(defaultMsg)), logicalFields(Hl7Parser.parse(bangMsg)));
    }
}