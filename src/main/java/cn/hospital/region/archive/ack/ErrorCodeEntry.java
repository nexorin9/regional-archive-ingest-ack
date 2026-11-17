package cn.hospital.region.archive.ack;

import java.util.Objects;

/**
 * HL7 v2 错误码目录条目 —— <b>三段式</b>：{@code 码^说明^表来源}，另附 HL7 版本供对端回查。
 *
 * <p>典型条目即任务里点名的两例：
 * <pre>
 *   103^Table value not found^HL70357        （表值未找到，HL7 v2 表 0357）
 *   201^Unsupported event code^HL70357      （不支持的事件码，HL7 v2 表 0357）
 * </pre>
 *
 * <p><b>为什么要标准码而不只是一句中文</b>：区域平台收到 {@code MSA-1=AE} 只知道「这条重投一次可能就成了」，
 * 但它还想知道「重投前该先改什么」。带 {@code 103} 的回执可以被对端直接归到「取值非法」那一类并触发
 * 自己的整改流程；只回一句「字段值不对」的中文，对端只能进人工信箱。
 *
 * <p><b>标准码与自定义码的界限</b>：本产品只对<b>确实是标准语义</b>的成因绑定 HL7 标准码
 * （如字符集不支持 → {@code 207}、版本不兼容 → {@code 204}）；「Z 段语义对齐失败」这类院内业务结局
 * 在 HL7 v2 表 0357 里<b>没有</b>对应码，硬套 {@code 206} 只会误导对端，因此走 {@link #custom(String, String, String)}
 * 并在码值上带 {@code CUSTOM-} 前缀——让对端一眼看出「这不是标准码，你们大概率解析不了」。
 *
 * @param code        码值：标准码为纯数字（如 {@code 207}）；自定义码带 {@code CUSTOM-} 前缀
 * @param text        码的英文说明（标准码取 HL7 原文，自定义码取本产品口径）
 * @param tableSource 表来源（标准码形如 {@code HL70357}；自定义码为 {@code LOCAL}）
 * @param hl7Version  该码所属 HL7 版本
 * @param zhLabel     中文说明，进 ERR-3 与 MSA-3
 */
public record ErrorCodeEntry(String code, String text, String tableSource, String hl7Version, String zhLabel) {

    /** 自定义码前缀：输出里出现它就说明对端按 HL7 标准码目录解析不了这一位。 */
    public static final String CUSTOM_PREFIX = "CUSTOM-";

    /** 标准码表来源：HL7 v2 表 0357（HL7 Error Codes），国内 ACK 解析器的实际依据。 */
    public static final String TABLE_0357 = "HL70357";

    /** 自定义码的表来源标记。 */
    public static final String TABLE_LOCAL = "LOCAL";

    /** 本产品使用的 HL7 版本（国内区域平台的事实标准），目录条目统一标注它。 */
    public static final String HL7_VERSION = "2.5.1";

    public ErrorCodeEntry {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(tableSource, "tableSource");
        Objects.requireNonNull(hl7Version, "hl7Version");
        zhLabel = zhLabel == null ? text : zhLabel;
        if (code.isBlank()) {
            throw new IllegalArgumentException("错误码不得为空串");
        }
        if (text.isBlank()) {
            throw new IllegalArgumentException("错误码 " + code + " 缺说明");
        }
        if (tableSource.isBlank()) {
            throw new IllegalArgumentException("错误码 " + code + " 缺表来源");
        }
        if (hl7Version.isBlank()) {
            throw new IllegalArgumentException("错误码 " + code + " 缺 HL7 版本");
        }
    }

    /** 标准码条目。 */
    public static ErrorCodeEntry standard(int code, String text, String zhLabel) {
        return new ErrorCodeEntry(Integer.toString(code), text, TABLE_0357, HL7_VERSION, zhLabel);
    }

    /** 标准码条目（表来源可覆盖，用于表 0357 之外的码）。 */
    public static ErrorCodeEntry standard(String code, String text, String tableSource, String zhLabel) {
        return new ErrorCodeEntry(code, text, tableSource, HL7_VERSION, zhLabel);
    }

    /**
     * 自定义码条目。码值自动加 {@code CUSTOM-} 前缀。
     *
     * <p>只在<b>确实没有对应标准码</b>时用：前缀一旦出现在出站报文里，等于向对端声明
     * 「这一位你们解析不了，别拿它做路由」，比冒充标准码诚实。
     */
    public static ErrorCodeEntry custom(String localKey, String text, String zhLabel) {
        String key = localKey.startsWith(CUSTOM_PREFIX) ? localKey : CUSTOM_PREFIX + localKey;
        return new ErrorCodeEntry(key, text, TABLE_LOCAL, HL7_VERSION, zhLabel);
    }

    /** 是否为自定义码（码值带 {@code CUSTOM-} 前缀）。 */
    public boolean custom() {
        return code.startsWith(CUSTOM_PREFIX);
    }

    /** 三段式渲染：{@code 码^说明^表来源}——ERR-3 的前三个组件。 */
    public String toCwe() {
        return code + "^" + text + "^" + tableSource;
    }

    /** 带版本位渲染：{@code 码^说明^表来源^版本}——用于日志与只读导出。 */
    public String toEr7Form() {
        return toCwe() + "^" + hl7Version;
    }

    /** 一行中文，用于日志与 API 响应，不含患者可识别信息。 */
    public String describeZh() {
        return zhLabel + "（" + code + (custom() ? "，自定义码，对端不可解析" : "") + "）";
    }
}