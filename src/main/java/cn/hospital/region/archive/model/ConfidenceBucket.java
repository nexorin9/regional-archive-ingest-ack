package cn.hospital.region.archive.model;

import java.util.Locale;

/**
 * 置信度分桶 —— 把 {@code 0~1} 的连续分数压成<b>三个可判读的档 + 一个「没给」</b>。
 *
 * <h2>为什么只有三档，阈值取 0.9 / 0.75</h2>
 * <p>档位边界不是随手取的，两条都落在产品已有的判据上：
 * <ul>
 *   <li><b>0.75</b> 是 {@link ZFieldAligner#DEFAULT_CONFIDENCE_THRESHOLD}：
 *       Z 段映射低于它就不合档、带原因进待确认清单。低于这条线，模型说的映射
 *       等于没说过；</li>
 *   <li><b>0.9</b> 就是 {@link IdentityCandidateMatcher#CROSS_REF_CONFIDENCE}：
 *       「跨院主键对得上」那条候选线。过线的候选信息员通常一眼就能点确认，
 *       没过的就得逐条看。</li>
 * </ul>
 * 再细分（0.85/0.95…）会让分桶报表出现「今天 3 条卡在 0.84」这种无法行动的数字；
 * 再粗（只分高/低）则看不出「模型够自信但不够格自动落地」这一档的存在——
 * 而这一档恰恰是本日复核工作量的主体。
 *
 * <h2>为什么多一个 UNKNOWN 桶</h2>
 * <p>{@link ZAlignmentReport.Unresolved#NO_CONFIDENCE}（{@code -1}）表示
 * 「模型压根没给分数」，它<b>不是</b> 0 分。让它落进 LOW 桶，
 * 报表就会把「模型不敢说」说成「模型说了它很没把握」，而这两种情况在人手里的动作不同：
 * 前者要回去看提示词与报文形状，后者才只是复核。
 * 更实际的理由是<b>不变量</b>：四桶计数之和必须等于样本总数，
 * 少一个桶就得靠「统计时把没分数的丢掉」来凑等式，而丢掉的那部分恰好是最该看的。
 *
 * <p><b>降级样本不是 UNKNOWN</b>：模型不可达时走的是确定性基线，
 * 基线给出的置信度是真的（{@link IdentityCandidateMatcher#CROSS_REF_CONFIDENCE} 之类），
 * 它落进自己的桶；降级与否由 {@link ConfidenceSample#degraded()} 单独标记，
 * 两个维度不混成一个。
 */
public enum ConfidenceBucket {

    /** {@code confidence ≥ 0.90}：过自动落地线，信息员通常不必逐条看。 */
    HIGH(0.90d, "高可信"),

    /** {@code 0.75 ≤ confidence < 0.90}：模型够自信但不够格自动落地，本档是复核工作量的主体。 */
    MEDIUM(0.75d, "待抽查"),

    /** {@code 0 ≤ confidence < 0.75}：低于合档阈值，人工判断。 */
    LOW(0.0d, "需人工判断"),

    /** 模型没给置信度（{@code -1}）：不是低分，是没有分数。 */
    UNKNOWN(-1.0d, "未给置信度");

    /** 「模型没给分数」的哨兵值，与 {@link ZAlignmentReport.Unresolved#NO_CONFIDENCE} 同口径。 */
    public static final double NO_CONFIDENCE = ZAlignmentReport.Unresolved.NO_CONFIDENCE;

    private final double lowerBound;
    private final String zhLabel;

    ConfidenceBucket(double lowerBound, String zhLabel) {
        this.lowerBound = lowerBound;
        this.zhLabel = zhLabel;
    }

    /** 本档下界（含）；{@link #UNKNOWN} 为负数，表示「无分数」不在 0~1 区间内。 */
    public double lowerBound() {
        return lowerBound;
    }

    /** 中文标签，供报表与终端输出。 */
    public String zhLabel() {
        return zhLabel;
    }

    /**
     * 归档桶。
     *
     * @param confidence 置信度；{@code < 0} 视为「没给」（{@link #NO_CONFIDENCE}）
     * @throws IllegalArgumentException {@code NaN} —— 那是上游算错了，不该被当成「没给」混过去
     */
    public static ConfidenceBucket of(double confidence) {
        if (Double.isNaN(confidence)) {
            throw new IllegalArgumentException("置信度不得为 NaN：无法判断它属于哪一档，"
                    + "按「没给」处理会把上游的算错藏进报表");
        }
        if (confidence < 0) {
            return UNKNOWN;
        }
        if (confidence >= HIGH.lowerBound) {
            return HIGH;
        }
        if (confidence >= MEDIUM.lowerBound) {
            return MEDIUM;
        }
        return LOW;
    }

    /** 是否需要人看一眼：只有 {@link #HIGH} 不需要。 */
    public boolean requiresReview() {
        return this != HIGH;
    }

    /** 区间写法，例如 {@code [0.75, 0.90)}；{@link #UNKNOWN} 写「未给分数」。 */
    public String zhRange() {
        if (this == UNKNOWN) {
            return "未给分数";
        }
        if (this == LOW) {
            return "[0, " + fmt(MEDIUM.lowerBound) + ")";
        }
        if (this == HIGH) {
            return "[" + fmt(lowerBound) + ", 1]";
        }
        return "[" + fmt(lowerBound) + ", " + fmt(HIGH.lowerBound) + ")";
    }

    /** 报表表头用的一行：{@code 高可信 [0.90, 1]}。 */
    public String zhDisplay() {
        return zhLabel + " " + zhRange();
    }

    /** 数据库存储名（{@link ConfidenceSeries} 之外的第二个稳定取值）。 */
    public String dbValue() {
        return name();
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }
}