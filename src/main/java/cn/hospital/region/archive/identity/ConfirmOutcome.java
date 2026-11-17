package cn.hospital.region.archive.identity;

import cn.hospital.region.archive.ledger.MessageState;

import java.util.Objects;

/**
 * 确认门的一次结论 —— <b>要么落地了，要么被拒了，没有第三种</b>。
 *
 * <h2>{@code confirmed} 与 {@code wrotePatientIndex} 不是一回事</h2>
 * <p>两者分开是因为撤回之后用<b>同一个</b>院内主键再点确认时：
 * 确认生效（行回到已落地、审计 +1、界面上这条待办消失了），
 * 但出站写被幂等抑制（同一条报文、同一个号，HIS 里只该有一条映射）。
 * 把两者合成一个布尔，会出现两种都很糟的读法：
 * 界面显示「已落地」而 HIS 里查无记录，或界面显示「未写入」而人以为没生效又点一次。
 *
 * @param confirmed           本次确认是否生效（行已停在已落地）
 * @param state               处理完后的状态；被拒时为点击那一刻的实际状态，
 *                            行不存在时为 {@code null}（没有「实际状态」可言，不拿别的态顶替）
 * @param wrotePatientIndex   本次是否真的向院内患者主索引发出了一次写入
 * @param patientRef          区域平台主键；未走到决策时可能为空串
 * @param localMrn            院内主键；未走到决策时可能为空串
 * @param refusal             被拒时的原因码；成功时为 {@code null}
 * @param note                人可读的一句话结论，进日志与 HTTP 响应（不含患者可识别信息）
 */
public record ConfirmOutcome(boolean confirmed,
                             MessageState state,
                             boolean wrotePatientIndex,
                             String patientRef,
                             String localMrn,
                             ConfirmGate.Refusal refusal,
                             String note) {

    public ConfirmOutcome {
        Objects.requireNonNull(note, "note 不得为 null");
        patientRef = patientRef == null ? "" : patientRef;
        localMrn = localMrn == null ? "" : localMrn;
        if (confirmed == (refusal != null)) {
            throw new IllegalArgumentException(
                    "confirmed=" + confirmed + " 与 refusal=" + refusal + " 自相矛盾："
                            + "要么生效且无拒绝原因，要么被拒且带原因码");
        }
        if (confirmed && state == null) {
            throw new IllegalArgumentException("确认生效时必须有落地状态");
        }
    }

    /** 确认生效。 */
    public static ConfirmOutcome landed(MessageState state, boolean wrotePatientIndex,
                                        String patientRef, String localMrn, String note) {
        return new ConfirmOutcome(true, state, wrotePatientIndex, patientRef, localMrn, null, note);
    }

    /** 被拒：行一个字节都没改，出站写调用次数为 0。{@code actualState} 可为 null（行不存在）。 */
    public static ConfirmOutcome refused(MessageState actualState, ConfirmGate.Refusal refusal,
                                         String note) {
        return new ConfirmOutcome(false, actualState, false, "", "", refusal, note);
    }

    /** 是否被拒。 */
    public boolean refused() {
        return refusal != null;
    }

    /**
     * 本次是否真的写了院内患者主索引 —— 与 {@link #confirmed()} 严格区分。
     *
     * <p>T31 把真实 HTTP PUT 接进来后，验收看的就是这个布尔。
     */
    public boolean hisWriteAttempted() {
        return wrotePatientIndex;
    }
}