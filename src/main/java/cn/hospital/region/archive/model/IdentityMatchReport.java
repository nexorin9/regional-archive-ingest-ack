package cn.hospital.region.archive.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 一次身份候选比对的产物 —— 候选、锚点、以及<b>没给候选时的说法</b>。
 *
 * <h2>为什么「没有候选」也要是个对象</h2>
 * <p>返回空列表的写法里，调用方分不清三件完全不同的事：
 * 「压根没有这个人」（该新建档案）、「证件对不上」（该拒收）、
 * 「模型挂了」（该等人稍后再试）。三者在确认台上的处置分别是三步不同的动作，
 * 而空列表只给一个信号。所以本类型把 {@link Outcome} 作为一等字段。
 *
 * <h2>本类型不含任何业务状态</h2>
 * <p>它不会让报文落地，也不会让报文被拒收。落地只走确认门（T30），
 * 拒收由调用方按 {@link #rejected()} 与 {@link RejectionReason#IDENTITY_CONFLICT} 回 {@code AR}。
 * 本类型只回答一句话：<b>「这可能是谁，凭什么」</b>。
 *
 * @param anchor     本条报文站在什么锚点上（见 {@link IdentityAnchorLevel}）
 * @param outcome    处置类别，决定调用方下一步做什么
 * @param candidates 候选（按置信度降序、院内主键升序；可能为空）
 * @param conflicts  证件冲突命中的本地记录（仅 {@link Outcome#ID_CARD_CONFLICT_REJECTED} 非空）
 * @param note       一句中文说明：为什么是这个处置
 */
public record IdentityMatchReport(IdentityAnchorLevel anchor,
                                  Outcome outcome,
                                  Candidates candidates,
                                  List<IdCardConflict> conflicts,
                                  String note) {

    /** 四种处置，调用方必须逐个回答「然后做什么」。 */
    public enum Outcome {

        /** 有候选：进待确认，信息员点确认后才落地。 */
        CANDIDATES_READY("已给出候选", "identity-candidates-ready"),

        /** 没有候选：进待确认，说明为什么没有（可能该新建档案）。 */
        NO_CANDIDATE("未给出候选", "identity-no-candidate"),

        /** 证件冲突：整条不产出候选，调用方回 {@code AR}，不进重投队列。 */
        ID_CARD_CONFLICT_REJECTED("证件冲突拒收", "identity-idcard-conflict"),

        /** 模型不可达：候选只保留确定性核对命中项，整条进待确认并标降级。 */
        MODEL_DEGRADED("模型不可达降级", "identity-model-degraded");

        private final String zhLabel;
        private final String auditToken;

        Outcome(String zhLabel, String auditToken) {
            this.zhLabel = zhLabel;
            this.auditToken = auditToken;
        }

        public String zhLabel() {
            return zhLabel;
        }

        /** 台账审计行 reason 的写法（与待确认清单同口径）。 */
        public String auditReason() {
            return auditToken;
        }
    }

    /**
     * 一条证件冲突：报文的证件摘要与这条本地记录不一致，且两者在别的硬字段上还对得上。
     *
     * @param localMrn 冲突的院内主键（供信息员去院内系统里核对）
     * @param matchedOn 凭什么判定「本来该是同一个人」：跨院主键相同 / 姓名与出生日期同时一致
     */
    public record IdCardConflict(String localMrn, String matchedOn) {
        public IdCardConflict {
            Objects.requireNonNull(localMrn, "院内主键不得为 null");
            localMrn = localMrn.trim();
            if (localMrn.isEmpty()) {
                throw new IllegalArgumentException("院内主键不得为空");
            }
            matchedOn = matchedOn == null ? "" : matchedOn;
        }
    }

    public IdentityMatchReport {
        Objects.requireNonNull(anchor, "锚点等级不得为 null");
        Objects.requireNonNull(outcome, "处置类别不得为 null");
        candidates = candidates == null ? Candidates.empty() : candidates;
        conflicts = conflicts == null ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(conflicts));
        note = note == null ? "" : note;
        if (outcome == Outcome.ID_CARD_CONFLICT_REJECTED && conflicts.isEmpty()) {
            throw new IllegalArgumentException("证件冲突的结论必须带冲突记录：否则信息员无从知道跟谁冲突");
        }
        if (outcome != Outcome.ID_CARD_CONFLICT_REJECTED && !conflicts.isEmpty()) {
            throw new IllegalArgumentException("只有证件冲突结论才允许带冲突记录，实际 " + outcome);
        }
        if (outcome == Outcome.CANDIDATES_READY && candidates.isEmpty()) {
            throw new IllegalArgumentException("已给出候选的结论必须至少有一条候选");
        }
    }

    // ------------------------------------------------------------ 四个出口

    public static IdentityMatchReport candidatesReady(IdentityAnchorLevel anchor, Candidates candidates,
                                                      String note) {
        return new IdentityMatchReport(anchor, Outcome.CANDIDATES_READY, candidates, List.of(), note);
    }

    public static IdentityMatchReport noCandidate(IdentityAnchorLevel anchor, String note) {
        return new IdentityMatchReport(anchor, Outcome.NO_CANDIDATE, Candidates.empty(), List.of(), note);
    }

    public static IdentityMatchReport conflictRejected(IdentityAnchorLevel anchor,
                                                       List<IdCardConflict> conflicts) {
        return new IdentityMatchReport(anchor, Outcome.ID_CARD_CONFLICT_REJECTED,
                Candidates.empty(), conflicts,
                "报文的证件摘要与院内已有记录冲突，且两者在别的硬字段上对得上；"
                        + "不产出候选，按拒收回执处理，由信息员线下核对证件");
    }

    public static IdentityMatchReport modelDegraded(IdentityAnchorLevel anchor, Candidates candidates,
                                                    String note) {
        return new IdentityMatchReport(anchor, Outcome.MODEL_DEGRADED, candidates, List.of(), note);
    }

    // ------------------------------------------------------------ 调用方要问的几件事

    /** 是否应当拒收（回 {@code AR}，不进重投队列）。 */
    public boolean rejected() {
        return outcome == Outcome.ID_CARD_CONFLICT_REJECTED;
    }

    /**
     * 是否应当推进到<b>待确认</b>等人处理。
     *
     * <p>除了拒收之外<b>全部</b>返回 {@code true}——包括「没有候选」和「模型降级」：
     * 这两种情况的正确处置都是留一条待确认给信息员看，绝不是静默丢弃。
     */
    public boolean requiresHumanConfirmation() {
        return !rejected();
    }

    /** 置信度最高的一条候选；没有候选时为空。 */
    public java.util.Optional<IdentityCandidate> best() {
        return candidates.best();
    }

    /** 给确认台与台账审计看的一行说明。 */
    public String zhOneLine() {
        return outcome.zhLabel() + "（" + anchor.auditToken() + "）：" + note
                + "；候选 " + candidates.size() + " 条"
                + (conflicts.isEmpty() ? "" : "；冲突记录 " + conflicts.size() + " 条");
    }
}