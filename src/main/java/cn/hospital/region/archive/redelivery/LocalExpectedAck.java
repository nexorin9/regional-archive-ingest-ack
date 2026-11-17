package cn.hospital.region.archive.redelivery;

import cn.hospital.region.archive.ledger.IdempotencyKey;
import cn.hospital.region.archive.ledger.LedgerReadView;
import cn.hospital.region.archive.ledger.MessageState;

import java.util.Objects;

/**
 * 本端「这一批应该已经回过执的一条」—— 差集左侧的每一行。
 *
 * <p>四个字段<b>各司其职，缺一不可</b>：
 * <ul>
 *   <li>{@code key}（MSH-9+MSH-10+MSH-11）—— 与对端比对用的锚，同时是重投时回读报文原文的入口；</li>
 *   <li>{@code ledgerId} —— 重投队列要外键指回台账行；</li>
 *   <li>{@code batchId} —— 差集按批做，不能把别批次的条目算进来；</li>
 *   <li>{@code state} —— <b>差集结果的处置分支</b>：已落地 / 拒收的条目即便对端没回执也不该重投，
 *       这个判断落在重投队列的业务闸上，但差集必须把真实状态带过去，闸门才判得出来；</li>
 *   <li>{@code ackCode} —— 本端当时回的 MSA-1。{@code AR} 是确定性事实，对端没记下也不该重投。</li>
 * </ul>
 *
 * <p>本 record <b>不含报文原文，也不含任何患者标识</b>：差集要处理的是「哪几条没收到」，
 * 这件事在整批几百条上反复发生，明文不该为了一个计数而被读进内存。
 * 需要原文时按 {@code ledgerId} 回台账读。</p>
 */
public record LocalExpectedAck(IdempotencyKey key, long ledgerId, String batchId,
                               MessageState state, String ackCode) {

    public LocalExpectedAck {
        key = Objects.requireNonNull(key, "幂等键不得为 null");
        batchId = Objects.requireNonNull(batchId, "batchId 不得为 null");
        state = Objects.requireNonNull(state, "台账状态不得为 null");
        ackCode = ackCode == null ? "" : ackCode.trim();
        if (ledgerId <= 0) {
            throw new IllegalArgumentException("台账行主键必须为正数，实际=" + ledgerId);
        }
        if (batchId.isBlank()) {
            throw new IllegalArgumentException("batchId 不得为空白：差集按批做，无批次号的条目无处安放");
        }
        if (ControlIdNormalizer.normalize(key.controlId()).isEmpty()) {
            throw new IllegalArgumentException("幂等键里的控制号归一后为空："
                    + "拿空控制号与对端比对，会把「对端漏报」与「本端没这条」混成一件");
        }
    }

    /** 归一后的控制号（差集比较键）。 */
    public String normalizedControlId() {
        return ControlIdNormalizer.normalize(key.controlId());
    }

    /** 从台账只读投影行构造；{@code ackCode} 由调用方从回执账页取（台账不存 MSA 码）。 */
    public static LocalExpectedAck fromExportRow(LedgerReadView.ExportRow row, String ackCode) {
        Objects.requireNonNull(row, "台账投影行不得为 null");
        return new LocalExpectedAck(row.key(), row.id(), row.batchId(), row.state(), ackCode);
    }

    /** 单行摘要（只有主键与状态，无患者标识）。 */
    public String toLogLine() {
        return "ledgerId=" + ledgerId
                + " controlId=" + key.controlId()
                + " msa=" + (ackCode.isEmpty() ? "(本端未回执)" : ackCode)
                + " state=" + state.dbValue()
                + " 归一键=" + normalizedControlId();
    }
}