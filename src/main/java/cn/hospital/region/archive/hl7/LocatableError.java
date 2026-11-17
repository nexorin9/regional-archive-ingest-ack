package cn.hospital.region.archive.hl7;

/**
 * 一条**可定位**的结构错误：报出「哪一段的哪个字段、第几段、原值长什么样、建议回什么错误码」。
 *
 * <p>这是把源产品「对不上格式的那条自己掉出来让我看，而不是整包退回去」这句用户原话落成数据结构的落点：
 * 源产品 {@code HL7Parser} 抛的是 {@code HL7Exception("Bad message format", lineNumber, rawLine)}，
 * 只给「第几行 + 整行原文」，无法直接映射到 HL7 的 ERR 段字段定位语义；本产品在此之上补出
 * <b>段名 + 字段序号 + 段内序号</b> 三元组，使 T8 的 {@code AckComposer} 能直接把 ERR-1/ERR-3 填成
 * 区域平台可解析的形式。
 *
 * <p><b>脱敏不变量（构造器强制）</b>：{@code rawSnippet} 长度恒 ≤ {@value #MAX_SNIPPET} 字符，
 * 且由 {@link FieldLocator} 保证其中不含患者可识别明文（姓名 / 身份证 / 出生日期 / 电话 / 地址）。
 * 患者数据段一律不落字段值，只落段名与字段数；确需核对时用 {@code valueDigest}
 * （SHA-256 前 8 位）做无明文比对。
 *
 * @param segmentName    段名（MSH / PID / …）；缺整段时给出应有段名
 * @param fieldIndex     字段序号（1-based，与 HL7 规范一致）；{@code 0} 表示「整段缺失」
 * @param segmentOrdinal 段在报文中的序号（0-based，与 {@link Hl7Message.Segment#ordinal()} 一致）；
 *                       整段缺失或位置未知时为 {@code -1}
 * @param rawSnippet     原值片段，恒 ≤ {@value #MAX_SNIPPET} 字符且不含患者可识别明文
 * @param suggestedCode  建议错误码（kebab-case，供 ERR 段与 API 共用）
 * @param severity       严重度：{@link Severity#BLOCKING} 阻断落地，{@link Severity#ADVISORY} 仅告警
 * @param valueDigest    出错字段原值的 SHA-256 前 8 位；字段为空（本次缺字段场景）时为 {@code ""}
 */
public record LocatableError(
        String segmentName,
        int fieldIndex,
        int segmentOrdinal,
        String rawSnippet,
        String suggestedCode,
        Severity severity,
        String valueDigest) {

    /** 原值片段的字符上限：日志行、ERR-4 中文说明与 API 响应共用同一上限。 */
    public static final int MAX_SNIPPET = 32;

    public LocatableError {
        if (segmentName == null || segmentName.isBlank()) {
            throw new IllegalArgumentException("segmentName 不能为空");
        }
        if (fieldIndex < 0) {
            throw new IllegalArgumentException("fieldIndex 不能为负，传入 " + fieldIndex);
        }
        if (suggestedCode == null || suggestedCode.isBlank()) {
            throw new IllegalArgumentException("suggestedCode 不能为空");
        }
        severity = severity == null ? Severity.BLOCKING : severity;
        rawSnippet = rawSnippet == null ? "" : rawSnippet;
        // 不变量：原值片段恒 ≤32 字符，超长由构造器截断，调用点无法绕过
        if (rawSnippet.length() > MAX_SNIPPET) {
            rawSnippet = rawSnippet.substring(0, MAX_SNIPPET);
        }
        valueDigest = valueDigest == null ? "" : valueDigest;
    }

    /** 严重度：阻断（回 AE/AR、不得落地）与告警（按默认路径继续但留痕）两级。 */
    public enum Severity {
        /** 缺了就没法处理：报文进拒收或待确认，绝不静默放行。 */
        BLOCKING,
        /** 缺了也能按既定默认路径处理：进告警台账，不阻断主路径。 */
        ADVISORY;

        public boolean blocking() {
            return this == BLOCKING;
        }
    }

    /** 整段缺失（fieldIndex=0）时的构造。 */
    public static LocatableError missingSegment(String segmentName, String suggestedCode, Severity severity) {
        return new LocatableError(segmentName, 0, -1,
                segmentName + "|（整段缺失）", suggestedCode, severity, "");
    }

    /** 字段缺失/为空时的构造：rawSnippet 由调用方（{@link FieldLocator}）按脱敏规则生成。 */
    public static LocatableError of(String segmentName, int fieldIndex, int segmentOrdinal,
                                    String rawSnippet, String suggestedCode,
                                    Severity severity, String valueDigest) {
        return new LocatableError(segmentName, fieldIndex, segmentOrdinal,
                rawSnippet, suggestedCode, severity, valueDigest);
    }

    public boolean blocking() {
        return severity == Severity.BLOCKING;
    }

    /** 字段展示名：{@code MSH-10}；整段缺失退化为段名本身。 */
    public String fieldRef() {
        return fieldIndex == 0 ? segmentName : segmentName + "-" + fieldIndex;
    }

    /**
     * ERR-3 用的段字段定位引用：{@code 段名^字段序号^组件序号}。
     * 整段缺失时只给 {@code 段名^0^0}，由区域平台按段缺失语义处理。
     *
     * <p>组件序号固定为 1：FieldLocator 只定位到字段级，字段内再分组件属于 T8 的 ERR 组装职责。
     */
    public String errSegmentReference() {
        return segmentName + "^" + fieldIndex + "^1";
    }

    /**
     * 人类可读单行描述 —— **全产品唯一的错误定位文案出口**。
     *
     * <p>API 响应、ERR-4 中文说明、日志与信息员台账导出一律调本方法，
     * 禁止各调用点自己拼「段名 + 字段 + 序号」，否则同一错误会在不同端点报出不同位置。
     */
    public String describe(String fieldLabel) {
        String where = segmentOrdinal < 0
                ? "位置未知"
                : "第 " + (segmentOrdinal + 1) + " 段";
        StringBuilder sb = new StringBuilder();
        sb.append(fieldRef());
        if (fieldLabel != null && !fieldLabel.isBlank()) {
            sb.append("（").append(fieldLabel).append("）");
        }
        sb.append(severity == Severity.BLOCKING ? "缺失或为空" : "未声明");
        sb.append("，").append(where);
        if (!rawSnippet.isEmpty()) {
            sb.append("，原值片段[").append(rawSnippet).append("]");
        }
        sb.append("，建议错误码 ").append(suggestedCode);
        return sb.toString();
    }

    @Override
    public String toString() {
        return describe(null);
    }
}