package cn.hospital.region.archive.redelivery;

import java.util.Objects;
import java.util.Random;

/**
 * 指数退避 + 对称抖动实现（{@code base &times; 2^attempt} 后夹在 {@code [base, maxMs]} 之间）。
 *
 * <h2>设计取舍</h2>
 * <ul>
 *   <li><b>cap 写在循环里而不是乘法后</b> ——{@code base * 2^30} 会先溢出 {@code long}，
 *       与其在乘法后再 min，不如每翻一倍就 cap 一次，让大 attempt 也能算得准。</li>
 *   <li><b>抖动对称</b> —— 抖动系数 {@code j} 给 {@code capped * (1 + (2r - 1) * j)}，
 *       其中 {@code r ∈ [0, 1)}。最终延迟落在 {@code [capped * (1-j), capped * (1+j)]}
 *       之间；{@code j=0} 时退化为固定指数（测试用），{@code j=0.2} 时为业内常见的 &plusmn;20%。</li>
 *   <li><b>种子强制注入</b> —— 随机源用 {@link Random#Random(long)}；构造期不接受默认时间种子，
 *       否则跨进程不可复现，T26 与本任务的退避用例都钉死具体种子。</li>
 *   <li><b>{@code attempt <= 0} 一律视作 1</b> —— 调度器内部约定「第一次失败后用 1」，
 *       但容错到 &le;0 也走 attempt=1 的延迟，避免初值错误直接除零或抛错。</li>
 * </ul>
 *
 * <h2>为什么不是「先算好延迟、再用时钟加」</h2>
 * {@code nextAttemptAtMs(now, attempt)} 接受 now 是为了让测试可以注入冻结时钟与跳变时钟；
 * 生产时钟仍按 {@link java.time.Clock#systemUTC()} 走。两层职责分开：策略算延迟，调用方
 * 决定「现在」是谁。
 */
public final class ExponentialBackoff implements BackoffPolicy {

    /** 退避基数毫秒；{@code baseMs} 必须 &gt; 0。 */
    private final long baseMs;
    /** 退避封顶毫秒；{@code maxMs &ge; baseMs}。 */
    private final long maxMs;
    /** 抖动系数；{@code 0 &le; j < 1}。0 表示无抖动。 */
    private final double jitterFraction;
    /** 同种子的确定性 RNG，仅在 {@code jitterFraction > 0} 时实际用到。 */
    private final Random rng;

    /**
     * @param baseMs         退避基数（毫秒；如 1000 表示初次 1 秒）
     * @param maxMs          退避封顶（毫秒；如 60_000 表示最久 1 分钟）
     * @param jitterFraction 抖动系数（{@code 0..1 之外} 抛错；{@code 0} 表示无抖动）
     * @param seed           随机源种子（强制传入；同一 (base, max, jitter, seed) 三次跑
     *                       出的序列逐字节一致）
     */
    public ExponentialBackoff(long baseMs, long maxMs, double jitterFraction, long seed) {
        if (baseMs <= 0) {
            throw new IllegalArgumentException("baseMs 必须为正数，实际=" + baseMs);
        }
        if (maxMs < baseMs) {
            throw new IllegalArgumentException("maxMs 必须 ≥ baseMs，实际 base=" + baseMs + " max=" + maxMs);
        }
        if (jitterFraction < 0.0 || jitterFraction >= 1.0) {
            throw new IllegalArgumentException("jitterFraction 必须在 [0, 1)，实际=" + jitterFraction);
        }
        this.baseMs = baseMs;
        this.maxMs = maxMs;
        this.jitterFraction = jitterFraction;
        this.rng = new Random(seed);
    }

    @Override
    public long nextAttemptAtMs(long nowMs, int nextAttempt) {
        if (nowMs < 0) {
            throw new IllegalArgumentException("nowMs 不得为负: " + nowMs);
        }
        if (nextAttempt < 0) {
            throw new IllegalArgumentException("nextAttempt 不得为负: " + nextAttempt);
        }
        int a = Math.max(1, nextAttempt);
        long capped = baseMs;
        for (int i = 1; i < a && capped < maxMs; i++) {
            capped = capped * 2L;
        }
        if (capped > maxMs) {
            capped = maxMs;
        }
        long withJitter = applyJitter(capped);
        return nowMs + withJitter;
    }

    private long applyJitter(long capped) {
        if (jitterFraction == 0.0 || capped == 0L) {
            return capped;
        }
        double r = rng.nextDouble();
        double multiplier = (1.0 - jitterFraction) + 2.0 * jitterFraction * r;
        long out = (long) (capped * multiplier);
        return out < 0 ? 0 : out;
    }

    public long baseMs() { return baseMs; }
    public long maxMs() { return maxMs; }
    public double jitterFraction() { return jitterFraction; }
}
