package cn.hospital.region.archive.model;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

/**
 * 重试策略 —— <b>全部决策集中在这一个类里</b>。
 *
 * <p>网关（{@link OpenAiCompatibleGateway}）负责把失败<b>分类</b>，
 * 本类负责决定「这类失败要不要再来一次、隔多久再来」，
 * 执行方（{@link ResilientModelGateway}）只负责照做。三件事分给三个类，
 * 是为了让「重试了几次」这个数字在任何一处都只有一个来源。
 *
 * <h2>规则只有一条</h2>
 * <p>{@link ModelErrorCode#retryable()} 为真才重试，且总尝试次数不超过
 * {@code 1 + MODEL_MAX_RETRIES}。这个「1 +」是刻意的：{@code MODEL_MAX_RETRIES=2}
 * 表示「首次失败后再试两次」，共三次调用，而不是共两次。
 * 写成「总共 N 次」会让配置名与实际行为差一个量级，而这类偏差只在出事那天才被发现。
 *
 * <h2>指数退避与上限</h2>
 * <p>第 n 次重试前等待 {@code INITIAL_BACKOFF × 2^(n-1)}，封顶 {@link #MAX_BACKOFF}。
 * 封顶这一道不能省：{@code MODEL_MAX_RETRIES} 允许配到 10，不封顶的话最后一次退避
 * 会是 {@code 200ms × 2^9 ≈ 102 秒}——比报文在重投队列里等着的时间还长，
 * 于是「重试」本身成了让整批卡住的原因。
 *
 * <h2>{@link Sleeper} 为什么是一个端口</h2>
 * <p>退避要真睡才能验「退避确实是指数的」，但让测试真睡 200ms+400ms+800ms 会让每个用例
 * 都慢一秒多，跑到最后没人愿意加用例。这里把「睡」做成可注入的接口：
 * 生产用 {@link #systemSleeper()}，测试用记录式实现——既能断言退避时长序列，
 * 又不花一个时钟。真实退避路径由 {@code ModelRetryPolicySleeperTest} 用系统时钟单独验一次。
 */
public final class ModelRetryPolicy {

    /** 退避 sleeper 端口：睡指定时长；被中断时抛 {@link InterruptedException}。 */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    /** 首次重试前的等待。200ms：足以避开对端刚刚重启的那几秒抖动，又不至于拖慢单条处理。 */
    public static final Duration INITIAL_BACKOFF = Duration.ofMillis(200);

    /** 单次退避上限；见类注释里「封顶不能省」那段。 */
    public static final Duration MAX_BACKOFF = Duration.ofSeconds(5);

    private final int maxRetries;
    private final Sleeper sleeper;

    public ModelRetryPolicy(int maxRetries) {
        this(maxRetries, systemSleeper());
    }

    public ModelRetryPolicy(int maxRetries, Sleeper sleeper) {
        if (maxRetries < 0 || maxRetries > 10) {
            throw new IllegalArgumentException("MODEL_MAX_RETRIES 须在 0 到 10 之间，实际 " + maxRetries);
        }
        this.maxRetries = maxRetries;
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper 不得为 null");
    }

    /** 生产 sleeper：真睡。 */
    public static Sleeper systemSleeper() {
        return duration -> {
            long millis = Math.max(0L, duration.toMillis());
            if (millis == 0L) {
                return;
            }
            Thread.sleep(millis);
        };
    }

    /** 记录式 sleeper：只记不睡，供测试断言退避序列。 */
    public static Sleeper recordingSleeper(List<Duration> record) {
        Objects.requireNonNull(record, "record 不得为 null");
        return record::add;
    }

    /** 新的记录列表，配 {@link #recordingSleeper(List)} 用。 */
    public static List<Duration> newSleeperRecord() {
        return new CopyOnWriteArrayList<>();
    }

    /** {@code MODEL_MAX_RETRIES} 的取值：首次调用之外最多再试几次。 */
    public int maxRetries() {
        return maxRetries;
    }

    /** 总尝试次数上限：{@code 1 + MODEL_MAX_RETRIES}。 */
    public int maxAttempts() {
        return maxRetries + 1;
    }

    /**
     * 已经调用了 {@code attempts} 次且本次失败，要不要再来一次。
     *
     * @param attempts 已发生的调用次数（含首次），至少为 1
     */
    public boolean shouldRetry(ModelErrorCode code, int attempts) {
        Objects.requireNonNull(code, "错误码不得为 null");
        return code.retryable() && attempts < maxAttempts();
    }

    /**
     * 第 {@code attempt} 次失败后、第 {@code attempt + 1} 次调用前的等待时长。
     *
     * @param attempt 已发生的调用次数（1 表示首次刚失败，将要等 200ms）
     */
    public Duration backoffFor(int attempt) {
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt 从 1 起，实际 " + attempt);
        }
        long shift = Math.min(attempt - 1L, 20L);   // 20 位封顶：再移位就溢出且早已被 MAX_BACKOFF 截住
        long millis = INITIAL_BACKOFF.toMillis() * (1L << shift);
        return millis >= MAX_BACKOFF.toMillis() ? MAX_BACKOFF : Duration.ofMillis(millis);
    }

    /** 按策略睡一次；被中断时原样抛 {@link InterruptedException}，由调用方决定怎么办。 */
    public void awaitBackoff(int attempt) throws InterruptedException {
        sleeper.sleep(backoffFor(attempt));
    }

    /** 单行摘要，进启动摘要：重试预算与退避上限。 */
    public String summaryLine() {
        return "retries=" + maxRetries
                + " backoff=" + INITIAL_BACKOFF.toMillis() + "ms"
                + "→" + MAX_BACKOFF.toSeconds() + "s";
    }
}