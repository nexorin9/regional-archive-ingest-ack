package cn.hospital.region.archive.model;

import java.util.Locale;
import java.util.Optional;

/**
 * 候选<b>依据字段</b>的封闭词表 —— 一条候选凭什么被列出来，必须能落在这几个字段上。
 *
 * <p>不做成自由字符串是有代价也值的：模型爱写「综合判断」「整体看起来像」这类
 * 无法核对的说法，而确认台上真正能帮信息员做决定的只有三件事——
 * 姓名对不对得上、出生日期对不对得上、证件摘要是不是同一个人。
 * 封闭词表把「无法核对」挡在门外：写不进这四个字段的依据，一律丢弃。
 *
 * <h2>为什么丢弃而不是照收</h2>
 * <p>依据是给人核对用的。一条写着「综合判断比较像」的依据，
 * 对信息员的用处<b>低于没有依据</b>：没有依据时他至少知道这条不能只看依据就点确认，
 * 而有一条看着像依据的废话，人是会照着点下去的。所以词表外依据不计入「有依据」。
 *
 * <p>模型侧 JSON Schema 里 {@code evidence[].field} 仍是自由字符串（那层只管形状不管取值），
 * 取值的收口在本枚举。
 */
public enum IdentityEvidenceField {

    /** 跨院主键（PID-3）：与院内主键字面相同即次强锚点。 */
    PATIENT_REF("patient_ref", "跨院主键"),

    /** 姓名（PID-5）：简繁、同音错别字、空格三种变体都落在这里。 */
    NAME("name", "姓名"),

    /** 出生日期（PID-7）：确定性核对，不参与相似度判断。 */
    BIRTH_DATE("birth_date", "出生日期"),

    /** 身份证摘要前 8 位（不可逆）：硬锚点，也是冲突判定的依据。 */
    ID_CARD_DIGEST8("id_card_digest8", "身份证摘要");

    private final String wire;
    private final String zhLabel;

    IdentityEvidenceField(String wire, String zhLabel) {
        this.wire = wire;
        this.zhLabel = zhLabel;
    }

    /** JSON 线上使用的字段名。 */
    public String wire() {
        return wire;
    }

    /** 确认台上显示的中文标签。 */
    public String zhLabel() {
        return zhLabel;
    }

    /**
     * 解析模型给出的依据字段名；<b>词表外返回 {@link Optional#empty()}</b>，
     * 调用方据此丢弃该条依据（不是抛异常 —— 模型说错话不是业务错误）。
     *
     * <p>比对时统一小写并去首尾空白：{@code "Name"} 与 {@code " name "} 说的是同一件事，
     * 因为模型在这类地方的大小写抖动经常被误当成「不同依据」。
     */
    public static Optional<IdentityEvidenceField> fromWire(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String key = raw.trim().toLowerCase(Locale.ROOT);
        for (IdentityEvidenceField f : values()) {
            if (f.wire.equals(key)) {
                return Optional.of(f);
            }
        }
        return Optional.empty();
    }
}