package cn.hospital.region.archive.ack;

import cn.hospital.region.archive.hl7.Delimiters;
import cn.hospital.region.archive.hl7.Escapes;
import cn.hospital.region.archive.hl7.FieldLocator;
import cn.hospital.region.archive.hl7.LocatableError;

import java.util.Objects;

/**
 * ERR 段组装 —— 拒收回执（AE / AR）必须带 ERR，区域平台靠它定位到「哪条报文、哪一段、哪个字段出的问题」。
 *
 * <p>字段占用（AE / AR 两条分支共用同一组字段，成功回执不产出本段）：
 * <pre>
 *   ERR-1  错误码与位置（ERL 三段式）= &lt;本地码&gt;^&lt;段名&gt;^&lt;字段序号&gt;
 *   ERR-2  保留空位（保持后续字段不错位）
 *   ERR-3  CWE 三段式+补位          = &lt;本地码&gt;^&lt;中文说明&gt;^&lt;目录标准码&gt;^&lt;表来源&gt;
 *   ERR-4  中文说明                  = FieldLocator 唯一文案出口 + 「HL7码:」引用位
 * </pre>
 *
 * <p><b>本地码与标准码同时出现</b>：ERR-1 与 ERR-3 首组件带的是本端本地码（说明「本端判定成什么」，
 * 人看得懂、能定位到段与字段），ERR-3 第三组件带的是 {@link Hl7ErrorCodes} 目录里查到的 HL7 标准码
 * （说明「按 HL7 属于哪一类」，区域平台能直接解析并路由）。两者不是重复：一个给人看，一个给机器解析。
 *
 * <p><b>为什么 ERR-1 放位置而不是按 v2.5 弃用留空</b>：v2.5 把 ERR-1 标为 deprecated，但国内区域平台
 * 的 ACK 解析器普遍仍在读这一位找位置；把位置只放 ERR-3（CWE 错误码位）会让老解析器只拿到一个码、
 * 报不出是哪条报文哪一段出的问题。ERR-1 与 ERR-3 同时带码，位置以 ERR-1 为准，两类解析器都能用。
 *
 * <p><b>文本一律转义后再拼</b>：ERR-4 会带上 T7 定位出的原值片段（MSH 段原文截断），片段里必然含字段分隔符。
 * 不转义就直接拼，等于把回执报文从 ERR-4 处切成两段，对端解析必然错位。转义由
 * {@link Escapes#escape} 实现，是本产品唯一的正向转义出口。
 *
 * <p><b>定位信息的唯一来源</b>：ERR-4 的中文说明调 {@link FieldLocator#describe}，与 HTTP 响应、日志、
 * 台账导出共用同一句文案出口；各调用点自行拼「段名 + 字段 + 序号」会在不同端点报出不同位置。
 */
public record ErrSegment(
        String errorCode,
        String segmentName,
        int fieldIndex,
        String hl7ErrorText,
        String zhDescription,
        String catalogCode,
        String tableSource) {

    /** 原值片段与中文说明合成的 ERR-4 上限，防止一条报文把整个 ACK 撑大。 */
    public static final int MAX_DESCRIPTION = 200;

    /** ERR-4 末尾的 HL7 码引用位前缀：对端按此位回查标准码目录。 */
    public static final String HL7_CODE_REFERENCE_PREFIX = "HL7码:";

    public ErrSegment {
        Objects.requireNonNull(errorCode, "errorCode");
        errorCode = errorCode.isBlank() ? "200" : errorCode;
        segmentName = segmentName == null ? "" : segmentName;
        hl7ErrorText = hl7ErrorText == null ? "" : hl7ErrorText;
        zhDescription = zhDescription == null ? "" : zhDescription;
        tableSource = tableSource == null ? ErrorCodeEntry.TABLE_LOCAL : tableSource;
        catalogCode = catalogCode == null ? "" : catalogCode;
        if (zhDescription.length() > MAX_DESCRIPTION) {
            zhDescription = zhDescription.substring(0, MAX_DESCRIPTION);
        }
    }

    /**
     * 由拒收原因 + 可定位错误组装。
     *
     * <p>ERR-3 的第三、四组件在这里被<b>补齐</b>：目录里查到的标准码与它的表来源/版本。
     * 这是错误码目录真正进入出站报文的位置——ERR-1 带的仍是本端本地码（人看得懂、能定位），
     * ERR-3 带的才是对端能直接解析的标准码。
     *
     * @param reason 拒收原因（提供建议码与中文说明）
     * @param error  可定位错误；为 {@code null} 时表示成因不在某个字段上
     *               （如身份冲突错在两套主键之间），此时不产出段字段位置
     */
    public static ErrSegment of(RejectionReason reason, LocatableError error) {
        Objects.requireNonNull(reason, "reason");
        String localCode = error == null ? reason.suggestedCode() : error.suggestedCode();
        ErrorCodeEntry entry = Hl7ErrorCodes.forSuggestedCode(localCode);
        String text = error == null ? reason.zhLabel() : FieldLocator.describe(error);
        String full = text + "（" + HL7_CODE_REFERENCE_PREFIX + localCode + "）";
        if (error == null) {
            return new ErrSegment(localCode, "", 0, reason.zhLabel(), full,
                    entry.code(), entry.tableSource());
        }
        return new ErrSegment(localCode, error.segmentName(), error.fieldIndex(), reason.zhLabel(), full,
                entry.code(), entry.tableSource());
    }

    /**
     * 目录条目视图：ERR-3 里带的标准码（自定义码带 {@code CUSTOM-} 前缀）。
     *
     * <p>取回完整条目（含说明与版本）供只读导出与对账使用。
     */
    public ErrorCodeEntry catalogEntry() {
        return catalogCode.isBlank()
                ? Hl7ErrorCodes.forSuggestedCode(errorCode)
                : Hl7ErrorCodes.requireByCode(catalogCode);
    }

    /** 标准码是否对端可解析（自定义码为 {@code false}）。 */
    public boolean standardCodeResolvable() {
        return !catalogCode.startsWith(ErrorCodeEntry.CUSTOM_PREFIX);
    }

    /** ERR-1 的段字段位置（{@code 段名^字段序号^组件序号}）；无可定位字段时返回空串。 */
    public String errLocationReference() {
        if (segmentName.isEmpty()) {
            return "";
        }
        return segmentName + "^" + fieldIndex + "^1";
    }

    /** 拼成 ERR 段的一行 ER7 文本（不含行尾 CR）。分隔符取入站报文自述值，不写死。 */
    public String toEr7(Delimiters d) {
        Objects.requireNonNull(d, "delimiters");
        char sep = d.fieldSeparator();
        char comp = d.componentSeparator();

        StringBuilder sb = new StringBuilder("ERR").append(sep);
        // ERR-1：错误码与位置，ERL 三段式（码 ^ 段名 ^ 字段序号）——
        // 三个值是 ERR-1 这一**字段内的组件**，不是三个字段；写成三个字段会让后续所有字段整体错位一位。
        sb.append(Escapes.escape(errorCode, d));
        if (!segmentName.isEmpty()) {
            sb.append(comp).append(Escapes.escape(segmentName, d)).append(comp).append(fieldIndex);
        }
        sb.append(sep);
        // ERR-2：保留空位（保持 ERR-3 / ERR-4 不错位）
        sb.append(sep);
        // ERR-3：CWE —— <本地码> ^ <中文说明> ^ <目录标准码> ^ <表来源>
        // 第三、四组件是错误码目录真正进入出站报文的位置：对端按 HL70357 就能解析第三组件。
        // 自定义码（带 CUSTOM- 前缀）如实上报并标注不可解析，不冒充标准码。
        sb.append(Escapes.escape(errorCode, d)).append(comp).append(Escapes.escape(hl7ErrorText, d));
        if (!catalogCode.isBlank()) {
            sb.append(comp).append(Escapes.escape(catalogCode, d));
        }
        if (!tableSource.isBlank()) {
            sb.append(comp).append(Escapes.escape(tableSource, d));
        }
        sb.append(sep);
        // ERR-4：中文说明 + HL7 码引用位
        sb.append(Escapes.escape(zhDescription, d));
        return sb.toString();
    }
}