package cn.hospital.region.archive.model;

import java.util.Locale;

/**
 * Z 段字段<b>没被合档</b>的原因 —— 每一条都必须能说清是哪一类。
 *
 * <p>本枚举存在的理由只有一条：<b>「没合上」不能没有说法</b>。
 * 一条 Z 段没对上，如果只表现为「结果里少了一项」，信息员看到的就是
 * 「模型说不用管」；而这一项可能是既往病史摘要。说不清原因的缺失，
 * 下一步一定是「先手工看一眼」，而看一眼的成本恰好是自动化本来要省掉的那部分。
 *
 * <p>每一类都对应一种<b>处置</b>，而不只是一句描述：
 * 全部落 {@link ZAlignmentReport#unresolved()}，由调用方推进到待确认等人确认，
 * 没有任何一类会推进到已落地或拒收（见 {@link ZFieldAligner} 类注释）。
 */
public enum AlignmentReason {

    /** Z 段既没带字段名也没带字段值：整段是空的，无从对齐。 */
    EMPTY_Z_SEGMENT("Z 段为空",
            "该 Z 段既没有平台字段名也没有字段值，无从对齐；请确认区域平台下发时该段是否漏填"),

    /** Z 段字段序号超出 ZX-n 的版式（字段名在 -2、字段值在 -3）。 */
    BEYOND_Z_LAYOUT("Z 段字段序号超出版式",
            "该 Z 段携带了下标大于 3 的字段，超出 ZX-2 字段名 / ZX-3 字段值的版式；本产品只对齐这两个位置"),

    /** 本次档案包带值的 Z 段数量超过本地字段字典条目数：本地装不下这份包。 */
    MORE_Z_FIELDS_THAN_LOCAL_FIELDS("可对齐 Z 段数超出本地字段数",
            "本批次带值的 Z 段数量超过院内本地字段字典条目数，自动挑一部分合入等于替人决定丢掉哪几条；整批转人工"),

    /** 模型没给出绑定（自己说判断不了）。 */
    NO_BINDING_PRODUCED("模型未给出绑定",
            "模型未对该 Z 段给出本地字段映射；本产品不替模型补一个，" +
                    "补出来的字段名没有任何依据可查"),

    /** 模型给的本地字段名不在站内字典内：编造的字段名。 */
    UNKNOWN_LOCAL_FIELD("本地字段名不在院内字典内",
            "模型给出的本地字段名不在院内字段字典中，视为编造，不予合档"),

    /** 置信度低于阈值：够不上自动合档的线。 */
    LOW_CONFIDENCE("置信度低于合档阈值",
            "模型给出的对齐置信度低于合档阈值，宁可转人工也不写错字段"),

    /** 多个 Z 段字段抢同一个本地字段：写进去必然丢一条。 */
    AMBIGUOUS_LOCAL_FIELD("多个 Z 段指向同一本地字段",
            "有多个 Z 段字段被对齐到同一个院内字段，写入会覆盖其中一条；" +
                    "请人工判断保留哪一条或如何合并"),

    /** 一个 Z 段字段被映射到两个本地字段：网关在结构校验阶段就已拒绝。 */
    MULTIPLE_BINDINGS_FOR_ONE_Z_FIELD("同一 Z 段被映射到多个本地字段",
            "模型对同一个 Z 段字段给出了多个互不相同的本地字段映射，无法判定该听哪一个；整条降级转人工"),

    /** 模型输出里混进了诊断、治疗建议或签名内容：越过临床边界，整条拒收该绑定。 */
    CLINICAL_ADVICE_IN_OUTPUT("输出含诊断或治疗建议",
            "模型输出中混入了诊断、治疗建议或代签内容，超出字段对齐的职责边界；该绑定不予合档");

    private final String shortLabel;
    private final String zhExplanation;

    AlignmentReason(String shortLabel, String zhExplanation) {
        this.shortLabel = shortLabel;
        this.zhExplanation = zhExplanation;
    }

    /** 短标签，进确认台的一行说明。 */
    public String shortLabel() {
        return shortLabel;
    }

    /** 给信息员看的完整解释：为什么没合上、下一步该问谁。 */
    public String zhExplanation() {
        return zhExplanation;
    }

    /** 机器可读的原因码，进台账审计 reason（与待确认清单同口径）。 */
    public String auditToken() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** 台账审计行的 reason 短语，例如 {@code align-unresolved:low_confidence}。 */
    public String ledgerReason() {
        return "align-unresolved:" + auditToken();
    }
}