package cn.hospital.region.archive.redelivery;

import cn.hospital.region.archive.ledger.IdempotencyKey;
import cn.hospital.region.archive.ledger.LedgerReadView;
import cn.hospital.region.archive.ledger.MessageState;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 对账差集 —— 本批次「哪几条对端没收到」的唯一算法。
 *
 * <h2>为什么差集值得单独一个类</h2>
 * <p>源产品的重试语义止于「NAK 就再试一次」（{@code MLLPConnection.java:116-131}）：
 * 一次调用内的瞬时回退，<b>没有跨批次的事实比对</b>。而现场真正的痛点是次日那句话 ——
 * 「昨天那批你还欠我 3 条」。要回答它，只能把两侧事实摆到一起：
 * <ul>
 *   <li>左侧：本端台账这一批<b>回过执</b>的每一条（T14/T19 的只读面）；</li>
 *   <li>右侧：区域平台<b>认下</b>的每一条（{@link BatchReconcilerClient} 的真实 HTTP GET）。</li>
 * </ul>
 * 两侧相减 = 可重投清单。<b>整批重发</b>是这里最容易犯也最贵的错：
 * 已落地的条目重投一次，对端就多收一份居民档案，本地多一次身份比对，
 * 而这些成本<b>换不来任何东西</b> —— 缺的那几条照样缺。
 *
 * <h2>差集算完之后不自动放行</h2>
 * <p>{@link #enqueueMissing} 只是把缺失项<b>提交给重投队列</b>，由队列自己的业务闸
 * （T32）判准入：{@code AR} / 已落地 的条目<b>在队列口就被拒</b>，
 * 本类不去替它做这个决定。差集的职责只到「哪些条目标成缺」为止。
 */
public final class AckReconciler {

    private static final Logger LOG = Logger.getLogger(AckReconciler.class.getName());

    private AckReconciler() {
    }

    /**
     * 算差集。
     *
     * @param expected 本批应发回执的条目；<b>跨批条目会被过滤掉</b>（差集按批做，
     *                 把别批的条目混进来会让重投清单凭空多出本批根本没发过的报文）
     * @param page     对端回执页；{@code null} 视为非法入参（取回执失败应抛异常，不得传空进来）
     */
    public static AckDiff diff(List<LocalExpectedAck> expected, RemoteAckPage page) {
        Objects.requireNonNull(expected, "本端应发条目不得为 null");
        Objects.requireNonNull(page, "对端回执页不得为 null：取回执失败必须抛异常，"
                + "拿空页做差集会让重投清单被静默清空");

        // ---------- 左侧：按归一控制号建索引，并把歧义键单独摘出来 ----------
        Map<String, List<LocalExpectedAck>> localByKey = new HashMap<>();
        for (LocalExpectedAck item : expected) {
            if (!item.batchId().equals(page.batchId())) {
                continue;
            }
            localByKey.computeIfAbsent(item.normalizedControlId(), k -> new ArrayList<>()).add(item);
        }
        List<String> ambiguousKeys = new ArrayList<>();
        for (Map.Entry<String, List<LocalExpectedAck>> e : localByKey.entrySet()) {
            if (e.getValue().size() > 1) {
                ambiguousKeys.add(e.getKey());
            }
        }
        ambiguousKeys.sort(Comparator.naturalOrder());

        // ---------- 右侧：对端回执按归一控制号建索引 ----------
        Map<String, RemoteAck> remoteByKey = new LinkedHashMap<>();
        List<String> duplicatedKeys = new ArrayList<>();
        for (RemoteAck ack : page.acks()) {
            String key = ack.normalizedControlId();
            RemoteAck existing = remoteByKey.putIfAbsent(key, ack);
            if (existing != null) {
                // 幂等上无害，但意味着对端可能重复处理过：记一笔，不改差集结论
                if (!duplicatedKeys.contains(key)) {
                    duplicatedKeys.add(key);
                }
            }
        }
        duplicatedKeys.sort(Comparator.naturalOrder());

        // ---------- 相减 ----------
        List<AckDiff.MatchedAck> matched = new ArrayList<>();
        List<AckDiff.MissingAck> missing = new ArrayList<>();
        for (Map.Entry<String, List<LocalExpectedAck>> e : localByKey.entrySet()) {
            String key = e.getKey();
            if (ambiguousKeys.contains(key)) {
                // 歧义键整体排除：宁可少重投一条让人看一眼
                continue;
            }
            LocalExpectedAck local = e.getValue().get(0);
            RemoteAck remote = remoteByKey.get(key);
            if (remote != null) {
                matched.add(new AckDiff.MatchedAck(local, remote));
            } else {
                missing.add(new AckDiff.MissingAck(local, key, AckDiff.MissingAck.REASON_NO_REMOTE_ACK));
            }
        }
        missing.sort(Comparator.comparing(m -> m.local().key().controlId()));
        matched.sort(Comparator.comparing(m -> m.local().key().controlId()));

        // ---------- 对端多出来的（本端台账无此条）----------
        List<String> unexpected = new ArrayList<>();
        for (String key : remoteByKey.keySet()) {
            if (!localByKey.containsKey(key)) {
                unexpected.add(key);
            }
        }
        unexpected.sort(Comparator.naturalOrder());

        AckDiff diff = new AckDiff(page.batchId(), page.declaredAckCount(), page.actualAckCount(),
                matched, missing, unexpected, ambiguousKeys, duplicatedKeys, page.countMismatch());
        LOG.log(Level.INFO, "{0}", diff.toLogLine());
        return diff;
    }

    /**
     * 把差集里的缺失条目提交给重投队列。
     *
     * <p>入队原因一律 {@link RedeliveryQueue.EnqueueTrigger#NO_ACK_NEXT_DAY} ——
     * 差集<b>只</b>由「对端次日未回执」触发；AE 那条路在报文处理时就入队（T32），不重复记。
     *
     * <p><b>准入判断不在这里</b>：{@code AR}（确定性事实）与已落地条目同样会出现在缺失清单里
     * （对端确实没记下它们），但它们<b>不该重投</b>。这个判断由队列的业务闸按
     * {@code ackCode} 与 {@code state} 判掉，本类原样提交并把被拒理由带回来 ——
     * 「被拒」是答案的一部分，不该被吞掉。</p>
     *
     * @param nextAttemptAtMs 期望可出队的时刻（epoch 毫秒）；通常传 0（立即可投）
     * @return 提交结果汇总：offered / duplicated / rejected 与逐条拒绝理由
     */
    public static EnqueueSummary enqueueMissing(AckDiff diff, RedeliveryQueue queue, long nextAttemptAtMs)
            throws SQLException {
        Objects.requireNonNull(diff, "差集结果不得为 null");
        Objects.requireNonNull(queue, "重投队列不得为 null");
        int offered = 0;
        int duplicated = 0;
        List<String> rejected = new ArrayList<>();
        for (AckDiff.MissingAck m : diff.missing()) {
            LocalExpectedAck local = m.local();
            RedeliveryQueue.OfferResult result = queue.offer(local.key(), local.ledgerId(),
                    local.batchId(), local.ackCode(), local.state(),
                    RedeliveryQueue.EnqueueTrigger.NO_ACK_NEXT_DAY, nextAttemptAtMs);
            if (result.isOffered()) {
                offered++;
            } else if (result.isDuplicated()) {
                duplicated++;
            } else {
                rejected.add(local.key().controlId() + ":" + result.rejectionReason().orElse("(未给理由)"));
            }
        }
        EnqueueSummary summary = new EnqueueSummary(diff.batchId(), diff.missingCount(),
                offered, duplicated, List.copyOf(rejected));
        LOG.log(Level.INFO, "差集重投提交: {0} → {1}", new Object[]{diff.toLogLine(),
                summary.toLogLine()});
        return summary;
    }

    /**
     * 从台账只读面取「本批应发条目」。
     *
     * <p>{@code ackCode} 不在台账里（台账记的是业务状态，不是回执码），因此由调用方
     * 从回执账页 {@code MessageReceiptBook} 按幂等键补齐；本方法只在<b>查不到的条目</b>上
     * 传空串，空串的语义是「本端未回执」，队列闸门按「非 AA、非 AR」处置。
     *
     * @param state            按哪个状态取；传 {@code null} 表示不限
     * @param ackCodeLookup    幂等键 → 本端 MSA 码；可为 {@code null}
     */
    public static List<LocalExpectedAck> expectedFromLedger(LedgerReadView readView,
                                                             String batchId,
                                                             MessageState state,
                                                             Function<IdempotencyKey, String> ackCodeLookup)
            throws SQLException {
        Objects.requireNonNull(readView, "台账只读面不得为 null");
        List<LedgerReadView.ExportRow> rows = readView.listForExport(batchId, state);
        List<LocalExpectedAck> out = new ArrayList<>(rows.size());
        for (LedgerReadView.ExportRow row : rows) {
            String ackCode = "";
            if (ackCodeLookup != null) {
                String found = ackCodeLookup.apply(row.key());
                if (found != null) {
                    ackCode = found;
                }
            }
            out.add(LocalExpectedAck.fromExportRow(row, ackCode));
        }
        return out;
    }

    /** 差集重投提交汇总。 */
    public record EnqueueSummary(String batchId, int missingCount,
                                 int offered, int duplicated, List<String> rejectedReasons) {

        public EnqueueSummary {
            rejectedReasons = List.copyOf(rejectedReasons);
        }

        /** 缺失条数与提交条数对不上（差集或队列里有静默吞掉的）——须人工看一眼。 */
        public boolean balanced() {
            return missingCount == offered + duplicated + rejectedReasons.size();
        }

        public String toLogLine() {
            StringBuilder sb = new StringBuilder();
            sb.append("批次 ").append(batchId).append(" 缺失 ").append(missingCount)
                    .append(" 条 → 入队 ").append(offered)
                    .append(" / 已存在 ").append(duplicated)
                    .append(" / 队列闸门拒绝 ").append(rejectedReasons.size());
            if (!rejectedReasons.isEmpty()) {
                sb.append("（").append(String.join("，", rejectedReasons)).append("）");
            }
            if (!balanced()) {
                sb.append(" ⚠条数对不上，需人工核查");
            }
            return sb.toString();
        }
    }
}