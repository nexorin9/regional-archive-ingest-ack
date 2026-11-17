package cn.hospital.region.archive.model;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 单个序列的分桶统计 —— 条数与占比，四个桶<b>预填</b>。
 *
 * <h2>预填而不是省略</h2>
 * <p>「今天高可信 0 条」和「今天没有高可信这一档」在报表上必须是两件不同的事：
 * 前者说明模型今天很保守，后者说明报表没跑对。预填让每个桶都有一行，
 * 空桶显示 0 与 0.0%，不需要读报表的人猜「没列出来的算不算 0」。
 *
 * <h2>唯一允许被打破的不变量</h2>
 * <p>{@code sum(counts) == total}。这条在构造器里断言：它一旦破了，
 * 说明「统计时把某些观察点跳过了」——而跳过的那部分恰恰是信息员最需要看到的
 * （模型没给分数的那些）。宁可报表跑不起来，也不要一份总数对不上的报表被人拿去估工作量。
 */
public record ConfidenceSeriesStats(ConfidenceSeries series,
                                    Map<ConfidenceBucket, Integer> counts,
                                    int total) {

    public ConfidenceSeriesStats {
        Objects.requireNonNull(series, "置信度序列不得为 null");
        Objects.requireNonNull(counts, "分桶计数不得为 null");
        if (total < 0) {
            throw new IllegalArgumentException("样本总数不得为负数：" + total);
        }
        EnumMap<ConfidenceBucket, Integer> filled = new EnumMap<>(ConfidenceBucket.class);
        int sum = 0;
        for (ConfidenceBucket b : ConfidenceBucket.values()) {
            int n = counts.getOrDefault(b, 0);
            if (n < 0) {
                throw new IllegalArgumentException("分桶计数不得为负数：" + b + "=" + n);
            }
            filled.put(b, n);
            sum += n;
        }
        if (sum != total) {
            throw new IllegalArgumentException("分桶计数之和（" + sum + "）必须等于样本总数（" + total
                    + "）：有观察点在统计时被跳过了");
        }
        counts = Map.copyOf(filled);
    }

    /**
     * 从观察点列表统计。
     *
     * @param series  序列
     * @param samples 该序列的观察点（允许空）
     */
    public static ConfidenceSeriesStats of(ConfidenceSeries series, List<ConfidenceSample> samples) {
        Objects.requireNonNull(samples, "观察点不得为 null");
        EnumMap<ConfidenceBucket, Integer> counts = new EnumMap<>(ConfidenceBucket.class);
        for (ConfidenceSample s : samples) {
            if (s.series() != series) {
                // 跨序列的样本不是本序列的账：混进来会让「各桶条数之和 = 总数」变成一个
                // 谁都算不清的等式。调用方按序列分组传进来，这里只做一次断言。
                throw new IllegalArgumentException("观察点序列不符：期望 " + series + "，实际 " + s.series());
            }
            counts.merge(s.bucket(), 1, Integer::sum);
        }
        return new ConfidenceSeriesStats(series, counts, samples.size());
    }

    /** 空统计：四个桶全 0、总数 0。 */
    public static ConfidenceSeriesStats empty(ConfidenceSeries series) {
        return of(series, List.of());
    }

    /** 本桶条数。 */
    public int count(ConfidenceBucket bucket) {
        Objects.requireNonNull(bucket, "桶不得为 null");
        return counts.getOrDefault(bucket, 0);
    }

    /** 本桶占比；总样本为 0 时返回 {@code 0}（而不是 NaN——报表里出现 NaN 会让人以为算错了）。 */
    public double ratio(ConfidenceBucket bucket) {
        if (total == 0) {
            return 0d;
        }
        return (double) count(bucket) / total;
    }

    /** 非高可信条数：{@code MEDIUM + LOW + UNKNOWN}。 */
    public int notHighCount() {
        return total - count(ConfidenceBucket.HIGH);
    }

    /** 低置信占比（含 UNKNOWN）——「模型没说准的那些占多少」。 */
    public double lowConfidenceRatio() {
        return total == 0 ? 0d : (double) notHighCount() / total;
    }

    /**
     * 按桶顺序（高→低→未给）输出「条数 + 占比」两列，供终端表格打印。
     *
     * <p>对齐按<b>显示宽度</b>而不是字符数：中文标签是双宽字符，
     * 用 {@code %-16s} 补空格会让含中文的那几行明显右移，整张表看起来是歪的。
     * 这是一份值班人员每天要对着读的行，歪掉的列会让人怀疑数字对错了位置。
     */
    public List<String> displayRows() {
        return java.util.Arrays.stream(ConfidenceBucket.values())
                .map(b -> String.format(java.util.Locale.ROOT, "    %s %6d 条  %6.1f%%",
                        padTo(b.zhDisplay(), COLUMN_WIDTH), count(b), ratio(b) * 100))
                .toList();
    }

    /** 第一列的目标显示宽度（半角计 1、中日韩与全角标点计 2）。 */
    private static final int COLUMN_WIDTH = 22;

    /** 按显示宽度右侧补空格（CJK 记 2 列）。 */
    private static String padTo(String text, int width) {
        int w = displayWidth(text);
        return w >= width ? text : text + " ".repeat(width - w);
    }

    private static int displayWidth(String text) {
        int w = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            w += (c >= 0x1100 && (c <= 0x115F || c == 0x2329 || c == 0x232A
                    || (c >= 0x2E80 && c <= 0xA4CF)
                    || (c >= 0xAC00 && c <= 0xD7A3)
                    || (c >= 0xF900 && c <= 0xFAFF)
                    || (c >= 0xFE30 && c <= 0xFE6F)
                    || (c >= 0xFF00 && c <= 0xFF60)
                    || (c >= 0xFFE0 && c <= 0xFFE6))) ? 2 : 1;
        }
        return w;
    }

    /** 一行中文摘要。 */
    public String zhSummary() {
        return series.zhLabel() + "：" + total + " 个观察点，"
                + "高可信 " + count(ConfidenceBucket.HIGH) + " 条，"
                + "需复核 " + notHighCount() + " 条（" + String.format(java.util.Locale.ROOT, "%.1f%%", lowConfidenceRatio() * 100) + "）";
    }
}