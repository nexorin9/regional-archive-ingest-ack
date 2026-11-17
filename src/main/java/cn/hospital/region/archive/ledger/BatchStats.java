package cn.hospital.region.archive.ledger;

import java.sql.SQLException;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 批次四态统计 —— 「下午按批次核对收到几条」这句话的可执行答案。
 *
 * <h2>谁在什么时刻用</h2>
 * <p>区域平台每天上午推一个批次，信息员下午要回答两件事：<b>这一批到底收了几条、落地了几条</b>，
 * 以及<b>剩下的那些卡在哪一档</b>。本 record 就是把这两问压成一张可以直接贴在科室群里的分布表。
 * 它不新增业务状态，只读出已有状态 —— 因此统计口径与接入主路径写入的状态<b>不可能</b>分叉。
 *
 * <h2>四态与重投子态分开呈现</h2>
 * <p>「四态」指 {@code RECEIVED / PENDING_CONFIRM / LANDED / REJECTED}；重投四态
 * （{@code PENDING_RETRY / RETRYING / RETRY_CONFIRMED / RETRY_PERMANENT_FAILED}）
 * 是次级态，单独归到 {@link #retryStates()}。合成一张八行的表会让人去问
 * 「这一批到底收到了几条」——收到几条和落地几条是两个数，分开摆才不会混。
 *
 * <h2>拒收原因分布为什么从审计行取</h2>
 * <p>拒收原因不在台账行上，而在推动它进拒收的那条 {@code message_audit} 的 {@code reason} 里
 * （{@code rejected:} + 本地码）。取审计行而不是重新解析报文原文有两个理由：
 * 报文可能<b>根本解析不出来</b>（缺 MSH-10 时连控制号都没有），且重新解析等于把读路径
 * 绑死在解析器的当前行为上——解析器改了，同一批报文昨天报一个原因今天报另一个，
 * 而报表是要跟平台对账的。
 */
public record BatchStats(String batchId,
                         long total,
                         Map<MessageState, Long> counts,
                         Map<MessageState, Long> retryStates,
                         Map<String, Long> rejectionReasons,
                         long lowConfidencePending) {

    /** 四态主态（统计表按这个顺序呈现，与产品文档里的状态生命周期一致）。 */
    public static final List<MessageState> MAIN_STATES = List.of(
            MessageState.RECEIVED,
            MessageState.PENDING_CONFIRM,
            MessageState.LANDED,
            MessageState.REJECTED);

    public BatchStats {
        Objects.requireNonNull(batchId, "batchId 不得为 null");
        Objects.requireNonNull(counts, "counts 不得为 null");
        Objects.requireNonNull(retryStates, "retryStates 不得为 null");
        Objects.requireNonNull(rejectionReasons, "rejectionReasons 不得为 null");
        if (lowConfidencePending < 0) {
            throw new IllegalArgumentException("低置信待确认条数不得为负数，实际=" + lowConfidencePending);
        }
        if (total < 0) {
            throw new IllegalArgumentException("批次总数不得为负数，实际=" + total);
        }
    }

    /**
     * 读一个批次的统计。
     *
     * <p>不存在的批次不抛异常，返回全 0 分布：批次号打错时运维要看到的是
     * 「这一批 0 条」而不是一坨栈回溯——后者会让人以为数据库挂了。
     */
    public static BatchStats of(LedgerReadView view, String batchId) throws SQLException {
        Objects.requireNonNull(view, "台账只读面不得为 null");
        requireBatchId(batchId);

        Map<MessageState, Long> all = new EnumMap<>(view.countByState(batchId));
        Map<MessageState, Long> main = new EnumMap<>(MessageState.class);
        Map<MessageState, Long> retry = new EnumMap<>(MessageState.class);
        // 总数 = 全部八态之和，**不是**四态之和。一条停在 PENDING_RETRY 的报文仍然是
        // 「这一批收到了的一条」；只数四态会让「整批都在等重投」报成「共 0 条」——
        // 而信息员看到这个结论会去查平台是不是没推，而真实原因是本端卡在重投队列里。
        long total = 0L;
        for (MessageState s : MessageState.values()) {
            long n = all.getOrDefault(s, 0L);
            if (MAIN_STATES.contains(s)) {
                main.put(s, n);
            } else {
                retry.put(s, n);
            }
            total += n;
        }
        return new BatchStats(batchId, total,
                Collections.unmodifiableMap(main),
                Collections.unmodifiableMap(retry),
                Collections.unmodifiableMap(new LinkedHashMap<>(view.rejectionReasonCounts(batchId))),
                view.countDegradedPending(batchId));
    }

    /** 已落地条数。 */
    public long landed() {
        return counts.getOrDefault(MessageState.LANDED, 0L);
    }

    /** 待确认条数。 */
    public long pendingConfirm() {
        return counts.getOrDefault(MessageState.PENDING_CONFIRM, 0L);
    }

    /** 拒收条数。 */
    public long rejected() {
        return counts.getOrDefault(MessageState.REJECTED, 0L);
    }

    /** 已接收但尚未进入业务决策的条数。 */
    public long received() {
        return counts.getOrDefault(MessageState.RECEIVED, 0L);
    }

    /** 重投子态上的条数合计（次日平台追问未收回执时看这个数）。 */
    public long retrying() {
        return retryStates.values().stream().mapToLong(Long::longValue).sum();
    }

    /** 是否整批都已落地（人工待办清零的判据）。 */
    public boolean allLanded() {
        return total > 0 && landed() == total;
    }

    /**
     * 一句话结论，直接能贴进科室群：「批次 X 共 8 条：已落地 5 / 待确认 2 / 拒收 1」。
     *
     * <p>有重投子态条目时额外挂一句 —— 否则「共 8 条」与三个分项加起来对不上，
     * 收件人第一反应是这张表算错了，而不是「还有几条在等重投」。
     */
    public String headline() {
        String base = "批次 " + batchId + " 共 " + total + " 条：已落地 " + landed()
                + " / 待确认 " + pendingConfirm() + " / 拒收 " + rejected();
        return retrying() > 0 ? base + "（另有 " + retrying() + " 条在重投中）" : base;
    }

    /**
     * 逐行分布表（四态 + 重投子态 + 拒收原因），供 {@code ledger batch-stats} 打印。
     *
     * <p>每一行都显式出现，包括 0 条的那些行：报表上写没写「待确认 0」，
     * 是「这一批没有需要人处理的待办」和「这一行忘了查」之间的差别。
     */
    public List<String> displayLines() {
        List<String> lines = new java.util.ArrayList<>();
        lines.add(headline());
        lines.add("状态分布：");
        for (MessageState s : MAIN_STATES) {
            lines.add(String.format("  %-16s %d", s.dbValue(), counts.getOrDefault(s, 0L)));
        }
        for (Map.Entry<MessageState, Long> e : retryStates.entrySet()) {
            lines.add(String.format("  %-16s %d", e.getKey().dbValue(), e.getValue()));
        }
        lines.add("低置信待确认（模型降级）：" + lowConfidencePending);
        if (rejectionReasons.isEmpty()) {
            lines.add("拒收原因：无");
        } else {
            lines.add("拒收原因分布：");
            rejectionReasons.forEach((code, n) ->
                    lines.add(String.format("  %-28s %d", code, n)));
        }
        return lines;
    }

    private static void requireBatchId(String batchId) {
        Objects.requireNonNull(batchId, "batchId 不得为 null");
        if (batchId.isBlank()) {
            throw new IllegalArgumentException("batchId 不得为空串：批次号是统计与导出的唯一入口");
        }
    }
}