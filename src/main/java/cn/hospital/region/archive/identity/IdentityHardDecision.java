package cn.hospital.region.archive.identity;

import cn.hospital.region.archive.ack.ErrorCodeEntry;
import cn.hospital.region.archive.ack.Hl7ErrorCodes;
import cn.hospital.region.archive.ack.RejectionReason;
import cn.hospital.region.archive.model.IdentityAnchorLevel;

import java.util.Objects;

/**
 * 身份硬校验的结论 —— <b>三态，且只有三态</b>。
 *
 * <p>本 record 不判定「这位居民是谁」，只判定「这条报文的证件号能不能当锚点、
 * 对不上时该怎么办」。候选排序是 {@code IdentityCandidateMatcher} 的事，
 * 落地写入是确认门的事，两者都读本结论的三个布尔出口而各不越界。
 *
 * <h2>三态对应三种现场动作</h2>
 * <table border="1">
 *   <caption>三态与处置</caption>
 *   <tr><th>态</th><th>锚点</th><th>候选</th><th>合档</th><th>回执</th><th>重投</th></tr>
 *   <tr><td>{@link State#MATCH} 一致</td><td>IDCARD</td><td>出</td><td>可</td><td>AA</td><td>—</td></tr>
 *   <tr><td>{@link State#CONFLICT} 冲突</td><td>MISSING</td><td>不出</td><td>禁</td><td>AR</td><td>不进</td></tr>
 *   <tr><td>{@link State#MISSING} 缺失/不可用</td><td>MISSING</td><td>出</td><td>禁</td><td>AE 或走确认</td><td>—</td></tr>
 * </table>
 *
 * <p>「缺失」也占一态而不是并进「一致/冲突」两分，是因为它决定<b>要不要叫人</b>：
 * 缺失时信息员看到的是一条待确认，冲突时他看到的是一条拒收说明加线下核对任务。
 * 两者的处置清单不一样，混成一态就会有人把该核对的当成已处理。
 *
 * <h2>构造器里的不变量不是啰嗦</h2>
 * <p>{@link #mergeAllowed()} 只在 {@link State#MATCH} 为真、
 * {@link #candidateEligible()} 只在冲突态为假 —— 这两条一旦被写反，
 * 表现是「缺证件的报文被自动合档」或「冲突的报文还出候选让信息员点确认」，
 * 而这两种表现在页面上<b>都像正常流程</b>：前者没有人会看见，
 * 后者会被当成「系统有点笨但在帮忙」。所以在构造器里就炸掉。
 */
public record IdentityHardDecision(State state,
                                   IdentityAnchorLevel anchor,
                                   RejectionReason rejection,
                                   ErrorCodeEntry errorCode,
                                   String basis,
                                   boolean candidateEligible,
                                   boolean mergeAllowed) {

    /** 硬校验三态。 */
    public enum State {

        /** 两侧证件号归一后一致：硬锚点成立。 */
        MATCH("证件一致", "identity-hard-match"),

        /** 两侧证件号归一后不一致：拒收（AR），不出候选、不入重投。 */
        CONFLICT("证件冲突", "identity-conflict"),

        /** 至少一侧没有可用证件号：只出候选、不合档，等人工确认。 */
        MISSING("无硬锚点", "identity-hard-missing");

        private final String zhLabel;
        private final String auditToken;

        State(String zhLabel, String auditToken) {
            this.zhLabel = zhLabel;
            this.auditToken = auditToken;
        }

        public String zhLabel() {
            return zhLabel;
        }

        /** 台账审计行的 reason（冲突态用 {@link RejectionReason#IDENTITY_CONFLICT} 的本地码）。 */
        public String auditToken() {
            return auditToken;
        }
    }

    public IdentityHardDecision {
        Objects.requireNonNull(state, "判定状态不得为 null");
        Objects.requireNonNull(anchor, "锚点等级不得为 null");
        basis = basis == null ? "" : basis;
        if (state == State.CONFLICT) {
            // 冲突态必须带得出站解析得了的标准码，否则区域平台只能把这条丢进人工信箱。
            // 这里直接取目录里的绑定，调用方拿不到「忘了取码就自己拼一个」的机会。
            Objects.requireNonNull(rejection, "冲突态必须带拒收原因");
            Objects.requireNonNull(errorCode, "冲突态必须带 HL7 标准错误码");
            if (rejection != RejectionReason.IDENTITY_CONFLICT) {
                throw new IllegalArgumentException("身份证冲突的拒收原因必须是 IDENTITY_CONFLICT，实际 " + rejection);
            }
            if (!Hl7ErrorCodes.ACCEPTED_NOT_PROCESSED.code().equals(errorCode.code())) {
                throw new IllegalArgumentException("身份证冲突必须绑定「已接受但不处理」标准码，实际 "
                        + errorCode.code());
            }
            if (candidateEligible || mergeAllowed) {
                throw new IllegalArgumentException("冲突态不得出候选、不得合档");
            }
            if (anchor != IdentityAnchorLevel.MISSING) {
                throw new IllegalArgumentException("冲突态没有可用锚点，anchor 必须是 MISSING，实际 " + anchor);
            }
        } else {
            if (rejection != null || errorCode != null) {
                throw new IllegalArgumentException("非冲突态不得携带拒收原因或错误码");
            }
            if (state == State.MATCH && (!mergeAllowed || anchor != IdentityAnchorLevel.IDCARD)) {
                throw new IllegalArgumentException("一致态必须标记 anchor=IDCARD 且允许合档");
            }
            if (state == State.MISSING && (mergeAllowed || anchor != IdentityAnchorLevel.MISSING)) {
                throw new IllegalArgumentException("缺失态不得允许合档，anchor 必须是 MISSING");
            }
        }
    }

    // ------------------------------------------------------------------ 三个出口

    /** 证件一致：硬锚点成立。 */
    public static IdentityHardDecision matched(String basis) {
        return new IdentityHardDecision(State.MATCH, IdentityAnchorLevel.IDCARD, null, null,
                basis, true, true);
    }

    /**
     * 证件冲突：拒收。
     *
     * <p>拒收原因与标准码都由本方法从
     * {@link RejectionReason#IDENTITY_CONFLICT} / {@link Hl7ErrorCodes} 取，
     * 调用方没有传入码的机会 —— 传入了就会出现同一类冲突在两条路径上报出不同码，
     * 而区域平台是按码统计的。
     */
    public static IdentityHardDecision conflicting(String basis) {
        RejectionReason reason = RejectionReason.IDENTITY_CONFLICT;
        return new IdentityHardDecision(State.CONFLICT, IdentityAnchorLevel.MISSING,
                reason, Hl7ErrorCodes.forRejection(reason), basis, false, false);
    }

    /** 无可用证件号：只出候选、不合档。 */
    public static IdentityHardDecision missing(String basis) {
        return new IdentityHardDecision(State.MISSING, IdentityAnchorLevel.MISSING, null, null,
                basis, true, false);
    }

    // ------------------------------------------------------------------ 调用方要问的几件事

    /** 是否应当拒收：回 {@code AR}、不出候选、不入重投队列。 */
    public boolean rejected() {
        return state == State.CONFLICT;
    }

    /** 是否需要人看一眼：除拒收外全部为真（缺失态等的就是这个人）。 */
    public boolean requiresHumanConfirmation() {
        return !rejected();
    }

    /**
     * 是否允许触发院内写入（患者主索引）。
     *
     * <p>与 {@link #mergeAllowed()} 同义但单独给一个名字：调用点写
     * 「先问能不能写，再写」时读它，比在调用点自己判断 {@code mergeAllowed} 更不容易写反。
     */
    public boolean allowsHisWrite() {
        return mergeAllowed;
    }

    /** 是否允许进入重投队列：AR 一律不进（{@link RejectionReason#redeliverable()}）。 */
    public boolean redeliverable() {
        return !rejected();
    }

    /** 台账审计行 reason：冲突态用拒收原因的本地码，其余用状态 token。 */
    public String ledgerReason() {
        return rejected() ? rejection.suggestedCode() : state.auditToken();
    }

    /** 出站错误码；非冲突态为空。 */
    public ErrorCodeEntry errorCodeOrNull() {
        return errorCode;
    }

    /** 给确认台与日志看的一行说明（无证件明文）。 */
    public String zhOneLine() {
        StringBuilder sb = new StringBuilder(state.zhLabel())
                .append("（").append(anchor.auditToken()).append("）：").append(basis);
        if (rejected()) {
            sb.append("；拒收原因 ").append(rejection.suggestedCode())
                    .append("，标准码 ").append(errorCode.code())
                    .append("（").append(errorCode.zhLabel()).append("）");
        } else if (!mergeAllowed) {
            // 依据里已经说过「只出候选不合档」，这里只补处置动作，不重复同一句。
            sb.append("；需人工确认后才能合档");
        }
        return sb.toString();
    }
}