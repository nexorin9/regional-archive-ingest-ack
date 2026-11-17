package cn.hospital.region.archive.redelivery;

import java.util.List;
import java.util.Objects;

/**
 * 一次取回的对端回执列表（一个批次一页）。
 *
 * @param batchId         对端认的批次号
 * @param declaredAckCount 对端自述的回执条数；{@code -1} 表示响应里没带 {@code ackCount}
 * @param acks            实际解析出的回执条目（按对端返回顺序）
 * @param countMismatch   对端自述条数与实际解析条数不一致（<b>只记告警，不作失败</b>）
 *
 * <h2>为什么 {@code countMismatch} 不当失败</h2>
 * <p>{@code ackCount} 是对端<b>自述</b>的数字，它与 {@code acks} 数组长度不一致时，
 * 本产品没有依据去判断该信哪一个 —— 信数组则可能漏算，信数字则无法知道是哪几条。
 * 正确处置是<b>以数组为准继续算差集，同时把不一致原样记进报表</b>，
 * 让人次日追问平台时有一句可复述的事实（自述 N 条 vs 实际 M 条）。
 * 这正是本产品最怕的那类坑：<b>把对端的口径差异偷偷吞掉</b>。
 */
public record RemoteAckPage(String batchId, int declaredAckCount, List<RemoteAck> acks,
                            boolean countMismatch) {

    public RemoteAckPage {
        batchId = Objects.requireNonNull(batchId, "batchId 不得为 null");
        acks = List.copyOf(Objects.requireNonNull(acks, "回执列表不得为 null"));
    }

    /** 对端实际回报的回执条数（以数组为准）。 */
    public int actualAckCount() {
        return acks.size();
    }

    /** 去重后对端确认收到过的控制号个数（归一后去重）。 */
    public int distinctAckedControlCount() {
        return (int) acks.stream().map(RemoteAck::normalizedControlId).distinct().count();
    }

    /** 对端回了 AA 的条数（人工核对「已受理」用）。 */
    public long acceptedCount() {
        return acks.stream().filter(RemoteAck::accepted).count();
    }

    /** 单行摘要，进日志与 CLI 报表。 */
    public String toLogLine() {
        StringBuilder sb = new StringBuilder();
        sb.append("对端回执列表 batch=").append(batchId)
                .append(" 自述条数=").append(declaredAckCount < 0 ? "(未回报)" : declaredAckCount)
                .append(" 实际解析=").append(actualAckCount())
                .append(" 去重后=").append(distinctAckedControlCount())
                .append(" 其中AA=").append(acceptedCount());
        if (countMismatch) {
            sb.append(" ⚠对端自述条数与实际解析条数不一致（以数组为准，口径差异已记录）");
        }
        return sb.toString();
    }
}