package cn.hospital.region.archive.redelivery;

import cn.hospital.region.archive.ack.MessageReceiptBook;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 重投意图集 —— 「哪些报文的回执判定允许被重投一次」的<b>当前</b>答案。
 *
 * <p><b>准入条件只有一条：MSA-1=AE（{@link cn.hospital.region.archive.ack.RejectionReason#redeliverable()}）。</b>
 * AR 整类排除，且这个排除不是靠调用点自觉——{@link #offer(String, boolean)} 对不可重投的报文
 * 直接返回 {@code false} 且不落任何记录。把「坏包无限打给对端」这件事挡在入队口，
 * 比在调度时判断可靠：多一个调用点就多一处写反的机会。
 *
 * <p><b>为什么现在只是一个集合</b>：重投要落库（条目含 attempt、next_attempt_at、last_error），
 * 多消费者出队要有原子性——这些是落库队列（T32）的职责，本轮先把「AE 进、AR 不进」这条
 * 准入口径在类型上钉死，并让调用方（回执处理链）已经按它行事。T32 落地时本类整体被替换，
 * 替换的是存储与调度，不是这条准入语义。
 *
 * <p><b>本类不含报文原文</b>：只存幂等键。报文原文留在台账里，重投时按幂等键回读，
 * 避免同一份报文在两个地方各存一份、且只有一处参与脱敏。
 */
public final class RedeliveryIntentSet {

    private final Set<String> keys = ConcurrentHashMap.newKeySet();
    private final AtomicLong offered = new AtomicLong();
    private final AtomicLong permanentRejected = new AtomicLong();

    /**
     * 提交一条重投意图。
     *
     * @param idempotencyKey 幂等键 MSH-9＋MSH-10＋MSH-11
     * @param redeliverable  MSA-1 是否为 AE（AR / AA 一律为 false）
     * @return {@code true} 表示本条进入了重投集合；{@code false} 表示被排除（AR 或已受理）
     */
    public boolean offer(String idempotencyKey, boolean redeliverable) {
        if (idempotencyKey == null || idempotencyKey.isEmpty()) {
            throw new IllegalArgumentException("幂等键不得为空：无键的重投意图无法去重，也无法回读原文");
        }
        if (!redeliverable) {
            // 计数留痕：AR 被排除是业务决定（重投一万次还是同样的字节），需要能被报表看见
            permanentRejected.incrementAndGet();
            return false;
        }
        boolean added = keys.add(idempotencyKey);
        if (added) {
            offered.incrementAndGet();
        }
        return added;
    }

    /** 便捷入口：直接按 {@link MessageReceiptBook.Receipt} 的可重投标记提交。 */
    public boolean offer(MessageReceiptBook.Receipt receipt) {
        if (receipt == null) {
            throw new IllegalArgumentException("回执记录不得为 null");
        }
        return offer(receipt.idempotencyKey(), receipt.redeliverable());
    }

    /** 某条报文当前是否在重投集合里。 */
    public boolean contains(String idempotencyKey) {
        return idempotencyKey != null && keys.contains(idempotencyKey);
    }

    /** 当前集合条数。 */
    public int size() {
        return keys.size();
    }

    /** 累计收到的可重投请求数（含重复键的再次提交）。 */
    public long offeredCount() {
        return offered.get();
    }

    /** 因 AR / 已受理而被排除的请求数——排除动作本身也要可核对。 */
    public long permanentRejectedCount() {
        return permanentRejected.get();
    }

    /** 集合内的幂等键（排序后，便于逐条比对）。 */
    public List<String> keys() {
        List<String> out = new ArrayList<>(keys);
        out.sort(Comparator.naturalOrder());
        return List.copyOf(out);
    }

    /** 清空（供测试与运维重置用；不提供「删除单条」——重投条目只能被消费或转永久失败）。 */
    public void clear() {
        keys.clear();
    }
}