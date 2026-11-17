package cn.hospital.region.archive.mllp;

import cn.hospital.region.archive.config.ConfigException;

import java.util.Map;

/**
 * 出站 MLLP 连接的<b>重连策略</b>：最多试几次、每次之间退避多久、建连与读回执各等多久。
 *
 * <h2>为什么这几个值必须从环境变量读</h2>
 * <p>「重投时对端断线」是<b>部署环境的事</b>：院内前置机到区域平台是专线（断了就是真的断了，
 * 多试几次也没用），走公网或跨网闸的链路抖动频繁（多试几次有意义）。写死在代码里等于
 * 让一家医院的行为固化在别人的发布节奏里，因此五项全部配置化，缺项才用默认值兜底 ——
 * 兜的全是<b>超时与重试预算</b>，不会把流量悄悄指到别的对端（判定标准见
 * {@link cn.hospital.region.archive.config.AppConfig} 的类注释）。
 *
 * <h2>为什么退避不在这里加抖动</h2>
 * <p>{@link cn.hospital.region.archive.redelivery.ExponentialBackoff} 那种对称抖动是为
 * <b>落库的分布式重试队列</b> 准备的：多实例同时退避会把压力重新叠回对端。本类处理的是
 * 进程内一次 socket 重建，退避窗口是毫秒级、最多几次，且同一时刻只有一个实例在做这件事，
 * 加抖动只会让测试变慢变不确定。故此处只做「翻倍 + 封顶」，抖动留给 T33 的队列层。
 *
 * @param maxAttempts      含首次在内的最大尝试次数；{@code ≥ 1}。耗尽后抛
 *                         {@link MllpReconnectExhaustedException}，不静默吞。
 * @param baseBackoffMs    第一次失败后的退避基数（毫秒）；{@code ≥ 1}
 * @param maxBackoffMs     退避封顶（毫秒）；{@code ≥ baseBackoffMs}
 * @param connectTimeoutMs 建连超时（毫秒）；{@code ≥ 1}
 * @param readTimeoutMs    读对端回执超时（毫秒）；{@code ≥ 1}
 */
public record MllpReconnectPolicy(int maxAttempts,
                                  long baseBackoffMs,
                                  long maxBackoffMs,
                                  int connectTimeoutMs,
                                  int readTimeoutMs) {

    /** 默认重试上限 3 次：专线环境下「连不上」几乎都是真断，3 次足够区分抖动与故障。 */
    public static final int DEFAULT_MAX_ATTEMPTS = 3;
    /** 默认退避基数 200ms：短到值班人员察觉不到，长到不给刚重启的对端立刻二次打死。 */
    public static final long DEFAULT_BASE_BACKOFF_MS = 200L;
    /** 默认退避封顶 2s：单次重投最多多花 0.2+0.4 秒，不会把重投队列的调度周期拖住。 */
    public static final long DEFAULT_MAX_BACKOFF_MS = 2_000L;
    /** 默认建连超时 5s。 */
    public static final int DEFAULT_CONNECT_TIMEOUT_MS = 5_000;
    /** 默认读回执超时 15s。 */
    public static final int DEFAULT_READ_TIMEOUT_MS = 15_000;

    public MllpReconnectPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts 必须 ≥ 1，实际=" + maxAttempts);
        }
        if (maxAttempts > 10) {
            throw new IllegalArgumentException("maxAttempts 不得超过 10，实际=" + maxAttempts
                    + "（再多也只是把同一个故障重复十遍）");
        }
        if (baseBackoffMs < 1) {
            throw new IllegalArgumentException("baseBackoffMs 必须 ≥ 1，实际=" + baseBackoffMs);
        }
        if (maxBackoffMs < baseBackoffMs) {
            throw new IllegalArgumentException("maxBackoffMs 必须 ≥ baseBackoffMs，实际 base="
                    + baseBackoffMs + " max=" + maxBackoffMs);
        }
        if (connectTimeoutMs < 1) {
            throw new IllegalArgumentException("connectTimeoutMs 必须 ≥ 1，实际=" + connectTimeoutMs);
        }
        if (readTimeoutMs < 1) {
            throw new IllegalArgumentException("readTimeoutMs 必须 ≥ 1，实际=" + readTimeoutMs);
        }
    }

    /** 全默认。 */
    public static MllpReconnectPolicy defaults() {
        return new MllpReconnectPolicy(DEFAULT_MAX_ATTEMPTS, DEFAULT_BASE_BACKOFF_MS,
                DEFAULT_MAX_BACKOFF_MS, DEFAULT_CONNECT_TIMEOUT_MS, DEFAULT_READ_TIMEOUT_MS);
    }

    /**
     * 从环境变量装配 —— 与 {@link cn.hospital.region.archive.config.AppConfig} 同一套
     * 解析口径，缺项走默认、非法值一律 {@link ConfigException} 拒绝（绝不悄悄改成别的值）。
     *
     * <table border="1">
     *   <caption>重连相关环境变量</caption>
     *   <tr><th>变量</th><th>默认值</th><th>含义</th></tr>
     *   <tr><td>{@code ARCHIVE_MLLP_RECONNECT_MAX_ATTEMPTS}</td><td>3</td><td>含首次的最大尝试次数</td></tr>
     *   <tr><td>{@code ARCHIVE_MLLP_RECONNECT_BASE_MS}</td><td>200</td><td>首次失败后的退避基数</td></tr>
     *   <tr><td>{@code ARCHIVE_MLLP_RECONNECT_MAX_MS}</td><td>2000</td><td>退避封顶</td></tr>
     *   <tr><td>{@code ARCHIVE_MLLP_CONNECT_TIMEOUT_MS}</td><td>5000</td><td>建连超时</td></tr>
     *   <tr><td>{@code ARCHIVE_MLLP_READ_TIMEOUT_MS}</td><td>15000</td><td>读回执超时</td></tr>
     * </table>
     */
    public static MllpReconnectPolicy fromEnv(Map<String, String> env) {
        int maxAttempts = intValue(env, "ARCHIVE_MLLP_RECONNECT_MAX_ATTEMPTS",
                DEFAULT_MAX_ATTEMPTS, 1, 10);
        long baseMs = longValue(env, "ARCHIVE_MLLP_RECONNECT_BASE_MS",
                DEFAULT_BASE_BACKOFF_MS, 1, 60_000L);
        long maxMs = longValue(env, "ARCHIVE_MLLP_RECONNECT_MAX_MS",
                Math.max(DEFAULT_MAX_BACKOFF_MS, baseMs), baseMs, 600_000L);
        int connectTimeoutMs = intValue(env, "ARCHIVE_MLLP_CONNECT_TIMEOUT_MS",
                DEFAULT_CONNECT_TIMEOUT_MS, 1, 600_000);
        int readTimeoutMs = intValue(env, "ARCHIVE_MLLP_READ_TIMEOUT_MS",
                DEFAULT_READ_TIMEOUT_MS, 1, 600_000);
        return new MllpReconnectPolicy(maxAttempts, baseMs, maxMs, connectTimeoutMs, readTimeoutMs);
    }

    /**
     * 第 {@code attempt} 次失败后应退避多久（毫秒）：{@code base × 2^(attempt-1)}，封顶
     * {@link #maxBackoffMs}。
     *
     * <p>封顶在<b>翻倍循环里</b>做而不是乘法之后 —— 与 T33 的退避策略同一考虑：
     * {@code base * 2^62} 会先溢出 {@code long}，届时连「封顶」这一步都算不出正确值。
     *
     * @param attempt 已失败次数（{@code ≥ 1}）；{@code ≤ 0} 一律按 1 算
     */
    public long backoffDelayMs(int attempt) {
        int a = Math.max(1, attempt);
        long delay = baseBackoffMs;
        for (int i = 1; i < a && delay < maxBackoffMs; i++) {
            delay = delay * 2L;
        }
        return Math.min(delay, maxBackoffMs);
    }

    /** 本次重连序列最多额外花掉的墙钟时间（毫秒），供启动摘要与排障看。 */
    public long worstCaseBackoffMs() {
        long total = 0L;
        for (int i = 1; i < maxAttempts; i++) {
            total += backoffDelayMs(i);
        }
        return total;
    }

    private static int intValue(Map<String, String> env, String name, int fallback, int min, int max) {
        String raw = env == null ? null : env.get(name);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        int parsed;
        try {
            parsed = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw ConfigException.invalid(name, "必须是整数，实际=" + raw.trim());
        }
        if (parsed < min || parsed > max) {
            throw ConfigException.invalid(name, "必须在 " + min + ".." + max + " 之间，实际=" + parsed);
        }
        return parsed;
    }

    private static long longValue(Map<String, String> env, String name, long fallback,
                                  long min, long max) {
        String raw = env == null ? null : env.get(name);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        long parsed;
        try {
            parsed = Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw ConfigException.invalid(name, "必须是整数，实际=" + raw.trim());
        }
        if (parsed < min || parsed > max) {
            throw ConfigException.invalid(name, "必须在 " + min + ".." + max + " 之间，实际=" + parsed);
        }
        return parsed;
    }
}
