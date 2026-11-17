package cn.hospital.region.archive.identity;

import java.util.Objects;

/**
 * 一条台账行上的<b>两套主键</b> —— 区域平台一套、院内一套，各自独立成字段。
 *
 * <h2>为什么把它们绑在一个类型里而不是两个 String</h2>
 * <p>拆列只解决了「落库不混装」，没解决「调用点不混装」：两个 {@code String} 并排传进
 * {@code bind(ledgerId, "TESTPAT-0001", "LOCMRN-0001")} 时，参数写反了编译器不管、
 * 运行时也静默 —— 而写反的后果是<b>把区域主键当成院内主键写进 HIS</b>，
 * 院内从此多出一位不存在的居民。把它们绑成一个有名字的类型后，写反在编译期就看出来，
 * 且本类型可以断言「两侧都有值时它们各自非空、互不冒名」。
 *
 * <h2>什么情况下可以只填一侧</h2>
 * <ul>
 *   <li><b>只有区域主键</b>：报文刚入账、还没人确认 —— 绝大多数行在生命早期都是这个形态。
 *       此时 {@link #localMrn()} 为空串，表示「这位居民在本院还没有号」。</li>
 *   <li><b>两侧都有</b>：人工确认之后，院内主索引给了号。
 *       这时才允许往 {@code PatientIndexWriter} 发那一次 PUT（T31）。</li>
 * </ul>
 * <p><b>不允许</b>只有院内主键而没有区域主键的绑定：那条报文从哪来就说不清了，
 * 而「说不清来路的档案」在次日对账差集里会变成一条永远配不上的孤儿。
 *
 * <p>两个主键<b>允许字面相同</b>（部分区域平台会原样回带基层住院号）：
 * 那仍然是两个来源各写了一次，绑定成立；但这种情形在确认台上要能一眼看出来，
 * 因此本类型给出 {@link #crossKey()} 供调用方补一句提示。
 */
public record IdentityKeyBinding(String patientRef, String localMrn) {

    public IdentityKeyBinding {
        patientRef = requireNonBlank(patientRef, "patientRef");
        localMrn = localMrn == null ? "" : localMrn.trim();
    }

    /** 报文刚入账：只有区域主键，院内主键空。 */
    public static IdentityKeyBinding inboundOnly(String patientRef) {
        return new IdentityKeyBinding(patientRef, "");
    }

    /** 人工确认之后：两侧主键齐备。 */
    public static IdentityKeyBinding bound(String patientRef, String localMrn) {
        return new IdentityKeyBinding(patientRef, localMrn);
    }

    /** 区域平台主键（报文 PID-3 第一组件）。 */
    public String patientRef() {
        return patientRef;
    }

    /** 院内主键（localMrn）；空串表示「还没有」。 */
    public String localMrn() {
        return localMrn;
    }

    /** 是否已由院内主索引给出院内主键。 */
    public boolean boundToHis() {
        return !localMrn.isEmpty();
    }

    /** 两侧主键字面相同 —— 平台原样回带了基层住院号的情形。 */
    public boolean crossKey() {
        return boundToHis() && patientRef.equals(localMrn);
    }

    /** 给日志与确认台看的一行说明：两侧主键片段，非明文敏感项（主键不是身份标识）。 */
    public String zhOneLine() {
        return "区域主键=" + patientRef
                + "，院内主键=" + (boundToHis() ? localMrn : "(尚未绑定)")
                + (crossKey() ? "，两侧字面相同" : "");
    }

    private static String requireNonBlank(String value, String field) {
        Objects.requireNonNull(value, field + "不得为 null");
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(field
                    + "不得为空串：一条说不清来路的报文在次日对账差集里会变成永远配不上的孤儿");
        }
        return trimmed;
    }
}