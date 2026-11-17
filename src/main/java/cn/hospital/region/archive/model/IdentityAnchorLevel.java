package cn.hospital.region.archive.model;

import java.util.Locale;

/**
 * 身份比对时<b>这条报文站在什么锚点上</b> —— 三种，不是两种。
 *
 * <p>「身份证一致 / 身份证冲突 / 身份证缺失」是本产品对身份的三态判定
 * （硬校验在 {@code identity} 层的 {@code IdentityHardRules}，本枚举是它在候选侧的镜像）。
 * 但候选侧还要回答一个硬校验回答不了的问题：<b>身份证没有的时候，人凭什么看候选</b>。
 * 所以这里区分三种锚点，而不是把「没有身份证」和「身份证对上了」之外的第三种情况
 * 统统塞进 {@code MISSING}。
 *
 * <p>三者的差别是<b>给信息员看的可核对程度</b>：
 * <ul>
 *   <li>{@link #IDCARD}：身份证摘要与院内记录一致 —— 硬锚点，置信度置高（{@code 0.95}），
 *       一个人对一个人，不需要再比别的；</li>
 *   <li>{@link #CROSS_REF}：区域平台主键与院内主键<b>字面相同</b>（部分区域平台会回带基层的
 *       住院号/病案号）—— 次强锚点（{@code 0.90}），依据是主键相等；</li>
 *   <li>{@link #MISSING}：两条硬锚点都没有 —— 姓名再像也只是线索，置信度一律被压到
 *       <b>合档阈值以下</b>（见 {@link IdentityCandidateMatcher#UNANCHORED_CONFIDENCE_CAP}），
 *       保证这一类候选在任何下游都不可能被自动落地。</li>
 * </ul>
 *
 * <p>「身份证有但对不上」不在这三种里：那种情况不产生候选，直接进
 * {@link IdentityMatchReport.Outcome#ID_CARD_CONFLICT_REJECTED}。
 */
public enum IdentityAnchorLevel {

    /** 身份证摘要与院内记录一致（硬锚点）。 */
    IDCARD("身份证一致", "anchor=IDCARD"),

    /** 区域平台主键与院内主键字面相同（次强锚点）。 */
    CROSS_REF("跨院主键一致", "anchor=CROSS_REF"),

    /** 无任何硬锚点可用：只能出候选等人工确认。 */
    MISSING("无硬锚点", "anchor=MISSING");

    private final String zhLabel;
    private final String auditToken;

    IdentityAnchorLevel(String zhLabel, String auditToken) {
        this.zhLabel = zhLabel;
        this.auditToken = auditToken;
    }

    /** 给信息员看的短标签。 */
    public String zhLabel() {
        return zhLabel;
    }

    /** 台账审计行里的写法（与待确认清单同口径）。 */
    public String auditToken() {
        return auditToken;
    }

    /** 台账审计 reason 前缀，例如 {@code identity-candidate:anchor_idcard}。 */
    public String ledgerReason() {
        return "identity-candidate:" + name().toLowerCase(Locale.ROOT);
    }
}