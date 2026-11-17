package cn.hospital.region.archive.mllp;

import java.io.IOException;

/**
 * 重连重试<b>耗尽</b> —— 同一操作在 {@link MllpReconnectPolicy#maxAttempts()} 次内没能完成。
 *
 * <h2>为什么必须抛出而不是静默返回</h2>
 * <p>传输层的沉默失败在这里代价最高：报文已经不在对方手里了，若本端「安静地」把它当作
 * 投过了，次日对账时差集是空的，闭环看起来是绿的，实际这条居民档案从未到达区域平台。
 * 因此本类是<b>受检异常</b>：调用方不处理就编译不过。
 *
 * <h2>它<b>不</b>意味着业务状态被推进</h2>
 * <p>本异常只在传输层抛出，调用方
 * （{@link cn.hospital.region.archive.redelivery.MllpRedeliveryAction}）把它收敛成
 * 「可重试失败」交回落库的重投队列：条目回到「待重投」，报文原文与幂等键原样留在台账，
 * 由退避后重投或重启后的恢复扫描接手。<b>任何一侧都不会因为它而把报文标成已落地。</b>
 */
public class MllpReconnectExhaustedException extends IOException {

    private final String endpoint;
    private final int maxAttempts;
    private final int rebuildCount;

    public MllpReconnectExhaustedException(String endpoint, int maxAttempts, int rebuildCount,
                                           Throwable lastFailure) {
        super("重连 " + endpoint + " 已在 " + maxAttempts + " 次尝试内全部失败（重建连接 "
                + rebuildCount + " 次），最后一次失败："
                + describe(lastFailure), lastFailure);
        this.endpoint = endpoint;
        this.maxAttempts = maxAttempts;
        this.rebuildCount = rebuildCount;
    }

    /** {@code host:port}。 */
    public String endpoint() {
        return endpoint;
    }

    /** 含首次在内的尝试上限。 */
    public int maxAttempts() {
        return maxAttempts;
    }

    /** 实际重建连接的次数（= 尝试次数 − 1，耗尽时）。 */
    public int rebuildCount() {
        return rebuildCount;
    }

    private static String describe(Throwable t) {
        if (t == null) {
            return "(无)";
        }
        String msg = t.getMessage();
        return t.getClass().getSimpleName() + (msg == null || msg.isBlank() ? "" : ": " + msg);
    }
}
