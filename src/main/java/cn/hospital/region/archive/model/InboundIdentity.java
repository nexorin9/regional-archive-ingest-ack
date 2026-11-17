package cn.hospital.region.archive.model;

/**
 * 区域平台报文里的<b>来访居民身份</b> —— 一次候选比对只有这四样东西。
 *
 * <p>对应入站 {@code PID-3}（跨院患者标识）、{@code PID-5}（姓名）、{@code PID-7}（出生日期），
 * 外加<b>证件摘要</b>。证件本身不进这个类型：产品里证件只以 SHA-256 前 8 位
 * （{@link cn.hospital.region.archive.ledger.LedgerRedaction#digest8}）的形式参与判定，
 * 明文留给院内系统自己那边比对，不在本产品的内存、台账与日志里再走一圈。
 *
 * <h2>为什么四样就够</h2>
 * <p>候选比对的<b>权威数据源</b>就是这四个字段：两个主键（跨院主键、院内主键）、姓名、出生日期。
 * 加上证件摘要构成第三个可判定项。年龄、性别、地址这些字段在区域健康档案里
 * 时有时无、时对时错，拿它们参与打分只会让「同一个人在不同批次里得到不同分数」，
 * 而分数漂移会被信息员读成「系统不稳定」，最后干脆不信候选 —— 那比少一个字段糟得多。
 */
public record InboundIdentity(String patientRef, String name, String birthDate, String idCardDigest8) {

    public InboundIdentity {
        patientRef = trimToEmpty(patientRef);
        // 姓名按码点截到上限：上游把一整段文本灌进 PID-5 是真实发生的事，
        // 而不截的话编辑距离会在本机这一侧就吃掉几十毫秒，模型侧还会把整批预算撑爆。
        // 报文原文仍在台账 raw_payload 里（未截断），截断只影响「拿它比对」这一件事。
        name = ModelInputPolicy.clampForModel(trimToEmpty(name), "PID-5 姓名");
        birthDate = trimToEmpty(birthDate);
        idCardDigest8 = trimToEmpty(idCardDigest8);
    }

    /** 区域平台主键（PID-3）；不送给模型，模型比对用不到它。 */
    public String patientRef() {
        return patientRef;
    }

    /** 平台写法的姓名，可能简繁 / 同音错别字 / 含空格。 */
    public String name() {
        return name;
    }

    /** 出生日期（PID-7），归一后取前 8 位数字。 */
    public String birthDate() {
        return birthDate;
    }

    /** 证件摘要前 8 位；空串表示<b>平台没给证件</b>，不是「证件算出来是空的」。 */
    public String idCardDigest8() {
        return idCardDigest8;
    }

    /** 有没有硬锚点可用的证件摘要。 */
    public boolean hasIdCard() {
        return !idCardDigest8.isEmpty();
    }

    /** 有没有姓名可比。 */
    public boolean hasName() {
        return !name.isEmpty();
    }

    /** 有没有出生日期可比。 */
    public boolean hasBirthDate() {
        return !normalizeBirthDate().isEmpty();
    }

    /** 归一后的出生日期（去非数字、截前 8 位）。 */
    public String normalizeBirthDate() {
        return IdentityText.normalizeBirthDate(birthDate);
    }

    /**
     * 本条报文在<b>无冲突</b>前提下的锚点初值。
     *
     * <p>只有「有没有证件」这一件事是可以不比对就说的：跨院主键与院内主键是否字面相同
     * 要看了候选切片才知道，所以 {@link #CROSS_REF} 由闸门在比对后判定，不在这里给。
     */
    public IdentityAnchorLevel anchorHint() {
        return hasIdCard() ? IdentityAnchorLevel.IDCARD : IdentityAnchorLevel.MISSING;
    }

    private static String trimToEmpty(String raw) {
        return raw == null ? "" : raw.trim();
    }

    @Override
    public String toString() {
        return "InboundIdentity(跨院主键=" + patientRef
                + ", 姓名非空=" + hasName()
                + ", 出生日期非空=" + hasBirthDate()
                + ", 证件摘要=" + (hasIdCard() ? idCardDigest8 : "(缺)") + ")";
    }
}