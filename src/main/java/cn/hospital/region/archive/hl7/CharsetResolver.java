package cn.hospital.region.archive.hl7;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 把 MSH-18 字符集声明映射为 Java {@link Charset}。
 *
 * <p>国内区域健康档案平台下发的中文档案报文大量声明 {@code GB18030-2000} 或 {@code GB18030}；
 * 少量声明 {@code ASCII} 或 {@code UNICODE UTF-8}；极少数声明 {@code UNICODE}（即 UTF-16）。
 *
 * <p>映射表（大小写不敏感，匹配前 {@code trim().toUpperCase(Locale.ROOT)}）：
 * <pre>
 *   GB18030, GB18030-2000     → GB18030
 *   UNICODE UTF-8, ISO_IR 192, UTF-8 → UTF-8
 *   ASCII                     → US-ASCII
 *   UNICODE, UTF-16           → UTF-16
 *   null / 空字符串           → UTF-8（DEFAULTED，写告警）
 *   其他                       → 抛 {@link UnsupportedCharset}（消息含 MSH-18 原值）
 * </pre>
 *
 * <p>源产品对应实现参考自 {@code org.dcm4che3.hl7.HL7Charset.toCharsetName(String)}；
 * 本类只做 MSH-18 → Charset 的查表映射，不做编码翻译（翻译在 {@link FrameDecoder#decode(byte[])}）。
 */
public final class CharsetResolver {

    /** 解析状态：OK = MSH-18 显式声明且命中；DEFAULTED = 未声明/空，按 UTF-8 默认。 */
    public enum Status { OK, DEFAULTED }

    /** 解析结果（charset + 标准化后的声明值 + 状态）。 */
    public static final class Decision {
        public final Charset charset;
        public final String declaredValue;
        public final Status status;

        Decision(Charset charset, String declaredValue, Status status) {
            this.charset = Objects.requireNonNull(charset, "charset");
            this.declaredValue = declaredValue == null ? "" : declaredValue;
            this.status = status;
        }
    }

    private CharsetResolver() {
    }

    /**
     * 解析 MSH-18 的字符集声明。
     *
     * @param msh18    MSH-18 字段值（可能为 null 或空白）
     * @param warnings 解析告警容器（DEFAULTED 场景会写入一条；可为 null 表示不收集）
     * @return 解析后的 {@link Decision}
     * @throws UnsupportedCharset MSH-18 不在已知映射中
     */
    public static Decision resolve(String msh18, List<String> warnings) {
        if (msh18 == null || msh18.trim().isEmpty()) {
            if (warnings != null) {
                warnings.add("hl7/charset: MSH-18 未声明，按 UTF-8 默认解码（DEFAULTED）");
            }
            return new Decision(StandardCharsets.UTF_8, "", Status.DEFAULTED);
        }
        String upper = msh18.trim().toUpperCase(Locale.ROOT);
        Charset cs;
        switch (upper) {
            case "GB18030":
            case "GB18030-2000":
                cs = Charset.forName("GB18030");
                break;
            case "UNICODE UTF-8":
            case "ISO_IR 192":
            case "UTF-8":
                cs = StandardCharsets.UTF_8;
                break;
            case "ASCII":
                cs = StandardCharsets.US_ASCII;
                break;
            case "UNICODE":
            case "UTF-16":
                cs = StandardCharsets.UTF_16;
                break;
            default:
                throw new UnsupportedCharset(
                        "未识别的 MSH-18 字符集: \"" + msh18 + "\"", msh18);
        }
        return new Decision(cs, upper, Status.OK);
    }

    /** 便捷重载：不收集告警。 */
    public static Decision resolve(String msh18) {
        return resolve(msh18, null);
    }
}
