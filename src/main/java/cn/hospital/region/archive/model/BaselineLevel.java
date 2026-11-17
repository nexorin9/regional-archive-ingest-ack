package cn.hospital.region.archive.model;

import java.util.Locale;

/**
 * 确定性基线的<b>命中档位</b> —— 一条候选凭什么被基线排进来。
 *
 * <h2>档位与排序分是两件事</h2>
 * <p>档位回答「凭什么算命中」（给信息员看的可核对理由），排序分回答「排在第几」。
 * 分开是因为排序分会被压低而档位不该跟着变：
 * 姓名相似但出生日期对不上时，档位仍然是 {@link #NAME_LITERAL}（因为姓名确实字面相同），
 * 只是排序分被压到 {@link BaselineMatcher#BIRTHDATE_CONFLICT_SCORE}。
 * 把两者揉成一个数字，结果就是「降权」被信息员读成「不是同一个人」，而后者是<b>误判</b>。
 *
 * <h2>排序分不是置信度，不参与任何自动决策</h2>
 * <p>这些数字的唯一用途是<b>给确认台上的候选排序</b>。
 * 「待确认 → 已落地」只由确认门（{@code ConfirmGate}，T30）在人工确认动作之后推进，
 * 没有任何路径拿基线排序分去自动合档。写在这里是为了让读代码的人不必去翻确认门才能确认这一点：
 * 除了硬锚点档（{@link #PID3_EXACT}，那是<b>主键字面相等</b>，不是相似度），
 * 其余档位的排序分<b>一律低于</b> {@link IdentityCandidateMatcher#UNANCHORED_CONFIDENCE_CAP}，
 * 也就是低于任何下游「按合档阈值判断」的门槛。
 *
 * <h2>为什么档位顺序是主键 → 姓名 → 出生日期</h2>
 * <p>主键字面相等是<b>同一个人</b>的直接证据，姓名字面相等是很强的证据，
 * 而出生日期相等<b>单独看几乎不算证据</b>——一个县里同一天出生的孩子不止一个。
 * 因此出生日期排最后一位档：它够不上「凭什么说像」，只够得上「别急着排除」。
 * 出生日期真正的价值在别处（把同名不同人的记录分开），见 {@link BaselineMatcher} 的冲突压分。
 */
public enum BaselineLevel {

    /** 跨院主键（PID-3）与院内主键字面相同 —— 硬键命中，与姓名是否相似无关。 */
    PID3_EXACT("跨院主键与院内主键字面相同", 0.90d),

    /** 去空白与大小写后姓名逐字相同。 */
    NAME_LITERAL("姓名字面相同（已去空白、统一大小写）", 0.70d),

    /** 姓名编辑距离 ≤ {@link BaselineMatcher#HIGH_SIMILARITY_MAX_DISTANCE}。 */
    NAME_NEAR("姓名高度相似（编辑距离 ≤ 1）", 0.60d),

    /** 姓名编辑距离 = {@link BaselineMatcher#CANDIDATE_MAX_DISTANCE}，低置信候选。 */
    NAME_FAR("姓名较远（编辑距离 = 2）", 0.40d),

    /** 姓名不相似，但出生日期一致 —— 只用来「不排除」，单独不构成命中理由。 */
    BIRTHDATE_ONLY("姓名对不上，仅出生日期一致", 0.25d);

    private final String zhLabel;
    private final double rankScore;

    BaselineLevel(String zhLabel, double rankScore) {
        this.zhLabel = zhLabel;
        this.rankScore = rankScore;
    }

    /** 给信息员看的档位说明（确认台与 T26 差异报告用同一套话）。 */
    public String zhLabel() {
        return zhLabel;
    }

    /** 该档位的排序分。 */
    public double rankScore() {
        return rankScore;
    }

    /** 档位 key，进审计与 T26 的逐条差异样本。 */
    public String auditToken() {
        return "baseline-level:" + name().toLowerCase(Locale.ROOT);
    }

    /** 是不是「高相似」档：确认台上这类候选信息员扫一眼就能判断。 */
    public boolean highSimilarity() {
        return this == PID3_EXACT || this == NAME_LITERAL || this == NAME_NEAR;
    }
}
