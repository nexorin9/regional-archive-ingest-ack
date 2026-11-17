package cn.hospital.region.archive.model;

/**
 * 姓名用字的<b>编辑距离</b>（Levenshtein）—— 确定性基线里唯一的「相似」来源。
 *
 * <h2>为什么自己写而不是引第三方库</h2>
 * <p>本项目依赖表被 T1 刻意压到三个（postgresql / h2 / junit-jupiter），
 * 为一个二十行的动态规划引一个 simmetrics 或 commons-text 进来，
 * 换来的是一次供应链引入和一份 jar 的体积，换不回任何东西。
 * 更重要的是：基线的每一个数字都必须是<b>读者能自己复算</b>的。
 * 用第三方库时，「为什么这条是 2 不是 1」就得翻它的源码；
 * 自己写则是本文档下面这二十行，读完就知道边界在哪。
 *
 * <h2>语义：算「改几个字能变成对方」</h2>
 * <p>替换、插入、删除各算一次。这是<b>字形</b>距离，不是<b>读音</b>距离，也不是语义距离 ——
 * 这正是 {@link BaselineMatcher} 里必须写清楚的能力边界，也是模型存在的理由：
 * 「張伟」与「张伟」在字形上差 1，但在业务上是同一个人的两种登记写法；
 * 「章伟」与「张伟」字形差 2，而它们<b>读音相同</b>。任何字形距离都看不见「读音」这一层。
 *
 * <h2>归一不在这里做</h2>
 * <p>本类<b>不</b>去空白、<b>不</b>繁折简、<b>不</b>转小写。
 * 归一是调用方的口径问题（基线只做「去空白 + 转小写」这一档，见 {@link BaselineMatcher}），
 * 混进距离函数里会让「距离为 1」这个数字的含义取决于谁调了它，对照实验就失去意义。
 */
public final class EditDistance {

    /** 距离下界：两段完全相同时为 0。 */
    public static final int IDENTICAL = 0;

    private EditDistance() {
    }

    /**
     * 两段文本的编辑距离（替换 / 插入 / 删除各计 1）。
     *
     * <p>用两行滚动数组而非完整 {@code n+1} 矩阵：候选切片会有几百上千条记录，
     * 每条都要算一次，而姓名一般 2–4 个字，完整矩阵的常数开销反而是主要成本。
     *
     * @param a 文本一；{@code null} 按空串处理
     * @param b 文本二；{@code null} 按空串处理
     * @return 非负距离；两段都为空返回 {@link #IDENTICAL}
     */
    public static int of(String a, String b) {
        String s = a == null ? "" : a;
        String t = b == null ? "" : b;
        if (s.equals(t)) {
            return IDENTICAL;
        }
        int n = s.length();
        int m = t.length();
        if (n == 0) {
            return m;
        }
        if (m == 0) {
            return n;
        }
        int[] prev = new int[m + 1];
        int[] curr = new int[m + 1];
        for (int j = 0; j <= m; j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= n; i++) {
            curr[0] = i;
            char ca = s.charAt(i - 1);
            for (int j = 1; j <= m; j++) {
                int substitution = prev[j - 1] + (ca == t.charAt(j - 1) ? 0 : 1);
                curr[j] = Math.min(substitution, Math.min(prev[j] + 1, curr[j - 1] + 1));
            }
            int[] swap = prev;
            prev = curr;
            curr = swap;
        }
        return prev[m];
    }

    /**
     * 距离是否<b>不超过</b> {@code max}。
     *
     * <p>给「超阈值就不出候选」这条路径用：全量算一遍再比阈值也可以，
     * 但候选切片里绝大多数记录的距离都远大于阈值，先判掉能让
     * 「几百条记录 × 每条一次距离」降到「几百次字符串长度比较」。
     *
     * @param max 允许的最大距离；负数一律返回 {@code false}
     */
    public static boolean within(String a, String b, int max) {
        if (max < 0) {
            return false;
        }
        String s = a == null ? "" : a;
        String t = b == null ? "" : b;
        if (s.isEmpty() || t.isEmpty()) {
            return s.length() <= max && t.length() <= max;
        }
        // 一旦两段长度差已经超过 max，后面怎么替换都拉不回来。
        if (Math.abs(s.length() - t.length()) > max) {
            return false;
        }
        return of(s, t) <= max;
    }
}
