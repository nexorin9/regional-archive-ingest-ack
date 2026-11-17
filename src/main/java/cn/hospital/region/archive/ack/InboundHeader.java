package cn.hospital.region.archive.ack;

import cn.hospital.region.archive.hl7.Delimiters;

import java.util.regex.Pattern;

/**
 * 入站报文头：从 ER7 的 MSH 段读回本产品回执时必须原样带上的那几个字段。
 *
 * <p>分隔符不写死：HL7 v2 规定分隔符由报文自己在 MSH-2 起自述，参考实现在
 * {@code HL7Parser.setDelimiters(line.substring(3, 8))} 处正是这么取的——本方法沿用同一口径，
 * 读到的 field/component/repetition/subcomponent/escape 五个字符直接来自报文原文。
 *
 * <p>本任务只需要回执拼装要用的字段（MSH-3/5/9/10/11/12/18）。按段名/字段序号的完整索引模型
 * （含 PID 等业务段）由后续结构化任务落地，届时本记录作为其最薄的一层保留或上提。
 *
 * @param sendingApplication    MSH-3 发送应用，对端
 * @param sendingFacility       MSH-4 发送机构
 * @param messageType           MSH-9 消息类型
 * @param controlId             MSH-10 消息控制号，回执须逐字复制
 * @param processingId          MSH-11 处理标识（P/T/D）
 * @param version               MSH-12 版本号
 * @param characterSet          MSH-18 字符集声明（MSH-18 是 <b>组件值</b>，如 {@code GB18030}）
 * @param fieldSeparator        字段分隔符（MSH-1，取自 MSH 段第 4 个字符）
 * @param encodingCharacters    MSH-2 编码字符（取自 MSH 段 substring(3,8) 的后 4 个字符）
 * @param componentSeparator    组件分隔符
 * @param repetitionSeparator   重复分隔符
 * @param subcomponentSeparator 子组件分隔符
 * @param escapeCharacter       转义字符
 */
public record InboundHeader(
        String sendingApplication,
        String sendingFacility,
        String timestamp,
        String messageType,
        String controlId,
        String processingId,
        String version,
        String characterSet,
        char fieldSeparator,
        String encodingCharacters,
        char componentSeparator,
        char repetitionSeparator,
        char subcomponentSeparator,
        char escapeCharacter) {

    /** HL7 默认编码字符，MSH 自述长度不足时兜底。 */
    public static final String DEFAULT_ENCODING_CHARACTERS = "^~\\&";

    /**
     * 解析 ER7 的 MSH 段。
     *
     * @param er7 整条报文的 ER7 文本，段分隔符为 CR
     * @throws IllegalArgumentException 首段不是 MSH，或 MSH 短到读不出编码字符
     */
    public static InboundHeader parse(String er7) {
        if (er7 == null || er7.isBlank()) {
            throw new IllegalArgumentException("报文为空，读不到 MSH 段");
        }
        String mshLine = er7;
        int cr = er7.indexOf('\r');
        if (cr >= 0) {
            mshLine = er7.substring(0, cr);
        }
        if (!mshLine.startsWith("MSH")) {
            throw new IllegalArgumentException("报文首段不是 MSH："
                    + mshLine.substring(0, Math.min(8, mshLine.length())));
        }
        if (mshLine.length() < 8) {
            throw new IllegalArgumentException("MSH 段长度不足 8 字符，读不出自述的编码字符");
        }
        // MSH-1 是字段分隔符本身，MSH-2 起 4 个编码字符：与参考实现 substring(3, 8) 同一口径。
        // 5 个分隔符统一由 Delimiters.readFromHeader 读出，不在本类另存一份映射：
        // 另存一份就会和解析侧各走各的（编码字符第 3 位是转义符、第 4 位是子组件分隔符，
        // 写反了会让出站文本的转义/还原打错标记）。
        Delimiters d = Delimiters.readFromHeader(mshLine);
        char fieldSep = d.fieldSeparator();
        String encodingChars = mshLine.substring(4, 8);
        char componentSep = d.componentSeparator();
        char repetitionSep = d.repetitionSeparator();
        char subcomponentSep = d.subcomponentSeparator();
        char escapeChar = d.escapeCharacter();

        // String.split 走正则：字段分隔符必须当字面量转义，否则 "|" 会被当成正则的「或」，
        // 结果是按每个字符切开，MSH-10 读出来变成一个字母。
        String[] parts = mshLine.split(Pattern.quote(String.valueOf(fieldSep)), -1);
        // parts[0] 是 "MSH"，parts[1] 对应 MSH-2，故 MSH-n 落在 parts[n-1]
        return new InboundHeader(
                field(parts, 3),
                field(parts, 4),
                field(parts, 7),
                field(parts, 9),
                field(parts, 10),
                field(parts, 11),
                field(parts, 12),
                field(parts, 18),
                fieldSep,
                encodingChars,
                componentSep,
                repetitionSep,
                subcomponentSep,
                escapeChar);
    }

    /** 取 MSH-n 的值；越界或为空时返回空串，不返回 null。 */
    private static String field(String[] parts, int mshIndex) {
        int idx = mshIndex - 1;
        if (idx < 0 || idx >= parts.length) {
            return "";
        }
        return parts[idx];
    }

    /** 版本号缺省时按 HL7 v2.5.1 处理——国内区域平台的事实标准。 */
    public String versionOrDefault() {
        return version.isBlank() ? "2.5.1" : version;
    }

    /**
     * 本报文自述的 5 个分隔符，供回执拼装复用。
     *
     * <p>回执必须沿用入站报文自己的分隔符：区域平台按收到的报文分隔符去切 ACK，
     * 用本端默认分隔符拼回来的 ACK 在非默认分隔符的对端那里会整段解析失败。
     */
    public Delimiters delimiters() {
        return Delimiters.fromChars(fieldSeparator, componentSeparator,
                repetitionSeparator, escapeCharacter, subcomponentSeparator);
    }
}