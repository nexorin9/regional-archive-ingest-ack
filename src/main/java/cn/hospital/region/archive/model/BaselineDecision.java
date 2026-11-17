package cn.hospital.region.archive.model;

import java.util.Objects;

/**
 * 基线对<b>一条</b>院内记录的判定 —— 档位、字形距离、排序分与理由。
 *
 * <h2>为什么判定要单独留一份</h2>
 * <p>候选列表（{@link Candidates}）只带置信度与依据，而 T26 要回答的是
 * 「模型比基线强在哪一类变体上」。要做到这一点，就必须能逐条回看
 * 基线当初<b>为什么</b>把这条排进来：档位是「字面相同」还是「差一个字」，
 * 差的那个字属于哪个方向。这些在候选列表里都看不出来，
 * 所以判定与候选一对并存，由 {@link BaselineResult} 收在一起。
 *
 * <h2>字形距离有一个显式的「不可比」值</h2>
 * <p>{@link #NOT_COMPARABLE}（-1）表示<b>有一方姓名为空、根本没比过</b>，
 * 与「比过了、距离是 3」是两回事。区域平台的报文里 PID-5 缺失是真实存在的，
 * 把这两种情况都写成 0 或 3，差异报告里就会凭空多出一批「基线差了两个字」的结论。
 *
 * <h2>理由里不出现姓名</h2>
 * <p>{@link #note()} 与 {@link #explain()} 的文本都不复述姓名原文，
 * 变体类型由 {@link IdentityText#nameVariantNote} 判定（它只说「姓名为简繁异体」，
 * 不把两个字并排抄出来）。理由要进待确认清单与 T26 的差异报告，
 * 而那两处的约定是不落姓名明文 —— 姓名在确认台上本来就并排显示，理由里再抄一遍只多一处明文落点。
 *
 * <h2>排序分可以被压低，档位不会因此改变</h2>
 * <p>出生日期不一致时 {@link #rankScore()} 会掉到 {@link BaselineMatcher#BIRTHDATE_CONFLICT_SCORE}，
 * 但 {@link #level()} 仍是姓名那一档。「这个人确实对不上」这条<b>证据</b>没有因为
 * 出生日期不符而消失，压低排序分说的是「先别把他排在前面」，两者不是同一句话。
 */
public record BaselineDecision(String localMrn, BaselineLevel level, int nameDistance,
                               double rankScore, String note) {

    /** 有一方姓名为空、根本没做过字形比较时的占位值。 */
    public static final int NOT_COMPARABLE = -1;

    public BaselineDecision {
        Objects.requireNonNull(localMrn, "院内主键不得为 null");
        localMrn = localMrn.trim();
        if (localMrn.isEmpty()) {
            throw new IllegalArgumentException("院内主键不得为空");
        }
        Objects.requireNonNull(level, "命中档位不得为 null");
        if (nameDistance < NOT_COMPARABLE) {
            throw new IllegalArgumentException("字形距离不得小于 " + NOT_COMPARABLE + "：" + nameDistance);
        }
        if (Double.isNaN(rankScore) || rankScore < 0 || rankScore > 1) {
            throw new IllegalArgumentException("排序分须在 0 到 1 之间，实际 " + rankScore);
        }
        note = note == null ? "" : note;
    }

    /** 两侧姓名都非空、确实做过字形比较。 */
    public boolean nameComparable() {
        return nameDistance != NOT_COMPARABLE;
    }

    /** 出生日期不一致而被压过排序分。 */
    public boolean birthDateConflictPenalised() {
        return rankScore < level.rankScore();
    }

    /**
     * 单行判定说明，进 T26 的差异报告与调试日志。
     *
     * <p>只写主键、档位 key、距离与分值；姓名与出生日期不进这一行。
     */
    public String explain() {
        String distance = nameComparable() ? String.valueOf(nameDistance) : "不可比";
        return localMrn + " 档位=" + level.auditToken()
                + " 字形距离=" + distance
                + " 排序分=" + rankScore
                + (birthDateConflictPenalised() ? "（出生日期不一致，排序分已压低）" : "")
                + " 理由=" + note;
    }
}
