package cn.hospital.region.archive.identity;

import cn.hospital.region.archive.model.IdentityText;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * 身份<b>硬校验</b> —— 证件号这一件事的确定性判定，模型之前跑。
 *
 * <h2>为什么硬规则先跑、模型后跑，顺序不能反</h2>
 * <p>把「是不是同一个人」的<b>否决权</b>交给一个概率性的东西，等于把「绝对不能错」降级成
 * 「多数时候不错」。本类的三态里有一态是终局（拒收，区域平台不再重投、院内不建档），
 * 这一态只能由确定性代码给出。因此本类<b>结构上</b>不持有任何模型网关：
 * {@code IdentityHardRulesTest} 用反射断言它没有模型类型的字段 ——
 * 「先跑硬规则」不是注释里的一句话，而是加一个依赖就编译不过、改一行就测试红。
 *
 * <h2>三态里最容易搞错的是「缺失」不等于「冲突」</h2>
 * <p>区域健康档案平台并非每条报文都带证件号；带了的也可能是 15 位老号、
 * 校验位录错、字段里混进空格。这些情况都<b>不能</b>判成冲突：
 * 判成冲突等于让对端停止重投、让院内拒收一位<b>很可能就是同一个人</b>的居民档案，
 * 而这类误判在现场的表现是「档案莫名其妙没过来」，极难定位。
 * 所以只有「两侧都可用且归一后不等」才是冲突，其余一律落缺失（出候选、不合档）。
 *
 * <h2>为什么冲突要连同「跟谁冲突」一起给出</h2>
 * <p>信息员拿到一条拒收，第一句要问的是「跟哪条院内记录冲突」——
 * 只有一句「身份冲突」的话，他要自己去 HIS 里翻，而翻的时间随档案量线性增长。
 * {@link #evaluateAcross} 因此把冲突落在<b>具体院内主键</b>上，与候选侧的
 * {@code IdentityMatchReport.IdCardConflict} 同一口径。
 *
 * <h2>日志里没有人</h2>
 * <p>本类打 WARN 时只写院内主键、摘要与判定依据，
 * <b>不写证件明文、不写姓名、不写报文原文</b>——与台账导出、待确认清单同一套约束。
 */
public final class IdentityHardRules {

    private static final Logger LOG = Logger.getLogger(IdentityHardRules.class.getName());

    /** 院内一条记录参与硬校验所需的最小信息。 */
    public record LocalSubject(String localMrn, String idCard) {
        public LocalSubject {
            Objects.requireNonNull(localMrn, "院内主键不得为 null");
            localMrn = localMrn.trim();
            if (localMrn.isEmpty()) {
                throw new IllegalArgumentException("院内主键不得为空串");
            }
            idCard = IdentityText.stripSpaces(idCard);
        }
    }

    /**
     * 一条本地记录上的硬校验结论 —— 三态 + 冲突落在哪条院内记录。
     *
     * @param subject      被比对的院内记录
     * @param decision     三态结论（{@link IdentityHardDecision}）
     * @param idCardDigest 该侧证件号的 8 位摘要（无则空串）
     */
    public record SubjectVerdict(LocalSubject subject,
                                 IdentityHardDecision decision,
                                 String idCardDigest) {
        public SubjectVerdict {
            Objects.requireNonNull(subject, "院内记录不得为 null");
            Objects.requireNonNull(decision, "判定结论不得为 null");
        }

        /** 是否为冲突结论（调用方据此决定要不要拒收）。 */
        public boolean conflict() {
            return decision.rejected();
        }
    }

    /**
     * 单条比对：报文证件号 vs 院内一条记录。
     *
     * @param inboundIdCard 报文侧的证件号（PID-3 的证件组件）；{@code null}/空表示平台没给
     * @param localIdCard   院内记录的证件号
     * @return 三态结论；<b>不抛业务异常，也不返回 null</b>
     */
    public IdentityHardDecision evaluate(String inboundIdCard, String localIdCard) {
        IdCardNumber inbound = IdCardNumber.parse(inboundIdCard);
        IdCardNumber local = IdCardNumber.parse(localIdCard);
        return decide(inbound, local);
    }

    /**
     * 切片比对：报文证件号 vs 一组院内记录，逐条给结论。
     *
     * <p><b>冲突优先短路</b>：任一条记录冲突即整条报文拒收，后续记录不再判 ——
     * 冲突一旦成立就没有出候选的意义，而把剩下的记录判完是一次白做的功。
     *
     * @param inboundIdCard 报文侧证件号
     * @param locals        院内候选切片（已由确定性基线收窄，不是全量索引）
     * @return 逐条结论；<b>冲突时长度 ≤ 冲突出现的位置 + 1</b>（短路结果，不是全量）
     */
    public List<SubjectVerdict> evaluateAcross(String inboundIdCard, List<LocalSubject> locals) {
        Objects.requireNonNull(locals, "院内候选切片不得为 null");
        IdCardNumber inbound = IdCardNumber.parse(inboundIdCard);
        List<SubjectVerdict> out = new ArrayList<>(Math.min(locals.size(), 8));
        for (LocalSubject local : locals) {
            IdCardNumber localCard = IdCardNumber.parse(local.idCard());
            IdentityHardDecision decision = decide(inbound, localCard);
            SubjectVerdict verdict = new SubjectVerdict(local, decision, localCard.digest8());
            out.add(verdict);
            if (decision.rejected()) {
                LOG.warning("身份硬校验：证件号与院内记录冲突，整条拒收（不出候选、不入重投）；"
                        + "院内主键=" + local.localMrn()
                        + "，报文侧证件=" + inbound.zhBasis()
                        + "，院内侧证件=" + localCard.zhBasis()
                        + "（证件明文不入日志）");
                break;
            }
        }
        return List.copyOf(out);
    }

    // ------------------------------------------------------------------ 三态判定

    /**
     * 三态判定的唯一实现。
     *
     * <p>先看<b>能不能比</b>再看<b>比出来一样吗</b>：顺序反了会把「没法比」
     * 当成「不一样」，进而拒收一条本该进候选的报文。
     */
    private IdentityHardDecision decide(IdCardNumber inbound, IdCardNumber local) {
        if (!inbound.usable() || !local.usable()) {
            return IdentityHardDecision.missing(missingBasis(inbound, local));
        }
        if (inbound.sameAs(local)) {
            return IdentityHardDecision.matched("证件号归一后一致（报文侧 " + inbound.zhBasis()
                    + "；院内侧 " + local.zhBasis() + "），按硬锚点处理");
        }
        return IdentityHardDecision.conflicting("证件号归一后不一致（报文侧 " + inbound.zhBasis()
                + "，摘要 " + inbound.digest8()
                + "；院内侧 " + local.zhBasis()
                + "，摘要 " + local.digest8() + "），按拒收处理");
    }

    /** 缺失态的依据要说清「缺的是哪一侧、为什么缺」——信息员据此判断是催平台补字段还是查院内档案。 */
    private String missingBasis(IdCardNumber inbound, IdCardNumber local) {
        if (!inbound.usable() && !local.usable()) {
            return "两侧均无可用证件号（报文侧 " + inbound.zhBasis()
                    + "；院内侧 " + local.zhBasis() + "），只出候选不合档";
        }
        if (!inbound.usable()) {
            return "报文侧无可用证件号（" + inbound.zhBasis() + "），只出候选不合档";
        }
        return "院内记录无可用证件号（" + local.zhBasis() + "），只出候选不合档";
    }
}