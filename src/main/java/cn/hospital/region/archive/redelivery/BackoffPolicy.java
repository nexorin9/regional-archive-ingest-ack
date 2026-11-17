package cn.hospital.region.archive.redelivery;

/**
 * 重投退避策略 —— 计算「下一次允许出队的 epoch 毫秒时间」。
 *
 * <p>本策略只关心<b>时间几何</b>，不关心队列本身的状态语义；多个等待中的条目共享同一
 * 个策略实例，按各自 {@code nextAttempt} 走不同延迟。{@link #nextAttemptAtMs(long, int)}
 * 的确定性来自「种子可注入」——同一 (seed, base, max, jitter, now, attempt) 在不同进程
 * 跑出同一序列，让 T26 的对照报告与 T33 的退避用例都能复现。
 */
public interface BackoffPolicy {

    /**
     * 给定「现在」与「下一次尝试序号」，返回允许被 {@code RedeliveryQueue.claimNextDue}
     * 拿走的时刻（epoch 毫秒）。
     *
     * @param nowMs       当前时钟（epoch 毫秒）
     * @param nextAttempt 下一次尝试的序号（&ge;1；调度器在 {@code tick} 之后写入）
     * @return 期望的下次可调度时刻（epoch 毫秒），&ge; nowMs
     */
    long nextAttemptAtMs(long nowMs, int nextAttempt);
}
