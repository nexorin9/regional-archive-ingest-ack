package cn.hospital.region.archive.redelivery;

import java.util.List;

/**
 * 一次对账的差集结果 —— 「本端这一批回过的执」与「对端认下的执」之差。
 *
 * <h2>三个集合，处置各不相同</h2>
 * <ul>
 *   <li>{@code missing}：本端有、对端没认 → <b>重投清单</b>。这是信息员次日唯一要动的东西；</li>
 *   <li>{@code matched}：两侧都有 → 这一条<b>一条不重投</b>。差集的全部价值就在这里；
 *       如果这里算错，多投一条就是对端收到同一份报文的第二遍。</li>
 *   <li>{@code unexpectedRemoteControlIds}：对端有、本端台账没有 → <b>不重投</b>。
 *       典型成因是本端台账被重建过、或对端把别批次的回执混进了这一页。
 *       它是一个<b>要人去查的信号</b>，不是重投对象：照着它重投等于凭空造报文。</li>
 * </ul>
 *
 * <h2>两个必须显式带出去的异常</h2>
 * <ul>
 *   <li>{@code ambiguousControlIds}：同一个归一键对应多条本地条目（归一规则带来的理论歧义）。
 *       这类键被<b>整体排除在缺失清单之外</b>并原样上报 —— 宁可少重投一条让人看一眼，
 *       也不让一个归一规则替平台决定两条报文是不是同一条。</li>
 *   <li>{@code duplicatedRemoteControlIds}：对端对同一控制号回报了多条。
 *       幂等上无害（重投队列同键不重），但意味着对端可能重复处理过，值得记一笔。</li>
 * </ul>
 *
 * @param batchId                    本批批次号
 * @param declaredAckCount           对端自述回执条数（{@code -1} = 对端未回报）
 * @param remoteAckCount             对端实际解析条数
 * @param matched                    已对上的一侧（本地条目 + 对端回执）
 * @param missing                    差集：本端有、对端没认（重投清单）
 * @param unexpectedRemoteControlIds 对端有、本端台账没有的归一控制号（已排序）
 * @param ambiguousControlIds        归一后对应多条本地条目的控制号（已排序，不进重投清单）
 * @param duplicatedRemoteControlIds 对端重复回报的归一控制号（已排序）
 * @param countMismatch              对端自述条数与实际解析条数不一致
 */
public record AckDiff(String batchId,
                       int declaredAckCount,
                       int remoteAckCount,
                       List<MatchedAck> matched,
                       List<MissingAck> missing,
                       List<String> unexpectedRemoteControlIds,
                       List<String> ambiguousControlIds,
                       List<String> duplicatedRemoteControlIds,
                       boolean countMismatch) {

    public AckDiff {
        matched = List.copyOf(matched);
        missing = List.copyOf(missing);
        unexpectedRemoteControlIds = List.copyOf(unexpectedRemoteControlIds);
        ambiguousControlIds = List.copyOf(ambiguousControlIds);
        duplicatedRemoteControlIds = List.copyOf(duplicatedRemoteControlIds);
    }

    /** 本端这一批应发回执的条数（= 对上 + 缺失 + 歧义排除）。 */
    public int expectedCount() {
        return matched.size() + missing.size();
    }

    public int missingCount() {
        return missing.size();
    }

    public int matchedCount() {
        return matched.size();
    }

    /** 本批是否「一条都不缺」—— 只有真对上了才允许报 true。 */
    public boolean complete() {
        return missing.isEmpty() && unexpectedRemoteControlIds.isEmpty()
                && ambiguousControlIds.isEmpty() && !countMismatch;
    }

    /** 单行摘要，进日志与 CLI 报表。 */
    public String toLogLine() {
        StringBuilder sb = new StringBuilder();
        sb.append("批次 ").append(batchId).append(" 对账差集: 本端应发=").append(expectedCount())
                .append(" 对端回报=").append(remoteAckCount)
                .append("（自述=").append(declaredAckCount < 0 ? "未回报" : declaredAckCount).append('）')
                .append(" 对上=").append(matchedCount())
                .append(" 缺失=").append(missingCount());
        if (!ambiguousControlIds.isEmpty()) {
            sb.append(" ⚠归一后歧义（已排除出重投清单）=").append(ambiguousControlIds.size());
        }
        if (!unexpectedRemoteControlIds.isEmpty()) {
            sb.append(" ⚠对端多出（本端台账无此条，不重投）=").append(unexpectedRemoteControlIds.size());
        }
        if (!duplicatedRemoteControlIds.isEmpty()) {
            sb.append(" ⚠对端重复回报=").append(duplicatedRemoteControlIds.size());
        }
        if (countMismatch) {
            sb.append(" ⚠对端自述条数与实际解析不一致（以数组为准，口径差异已记录）");
        }
        if (missing.isEmpty()) {
            sb.append(" → 本批无缺失，一条不重投");
        } else {
            sb.append(" → 重投清单：");
            for (MissingAck m : missing) {
                sb.append(m.local().key().controlId()).append(' ');
            }
        }
        return sb.toString();
    }

    /** 一条对上的：本地条目 + 对端那条回执（保留原文控制号供人工核对）。 */
    public record MatchedAck(LocalExpectedAck local, RemoteAck remote) {
    }

    /** 一条缺失的：本地有、对端没认，直接可喂 {@link RedeliveryQueue#offer}。 */
    public record MissingAck(LocalExpectedAck local, String normalizedControlId, String reason) {

        /** 中文原因，进报表与重投队列的 last_error。 */
        public static final String REASON_NO_REMOTE_ACK = "对端回执列表里没有这一条（已按控制号归一比对）";
    }
}