package cn.hospital.region.archive.ledger;

import java.time.Instant;
import java.util.Objects;

/**
 * 台账一行的只读投影 —— {@link JdbcLedgerStore} 从 {@code message_ledger} 读出的完整视图。
 *
 * <p>本 record 是「台账当前长什么样」的唯一口径：状态、四态计数、按批次导出（T19）、
 * 对账差集（T34）都从这里取数。{@code rawPayload} 原样带出（重投 T32 需要重发报文原文），
 * 调用方打日志时必须走脱敏出口，不要整条 {@code toString()} 进日志。
 */
public record LedgerRow(long id,
                        IdempotencyKey key,
                        String batchId,
                        MessageState state,
                        String rawPayload,
                        String localMrn,
                        boolean degraded,
                        int retryCount,
                        long version,
                        Instant createdAt,
                        Instant updatedAt) {

    public LedgerRow {
        if (id <= 0) {
            throw new IllegalArgumentException("台账行主键必须为正数，实际=" + id);
        }
        key = Objects.requireNonNull(key, "幂等键不得为 null");
        batchId = Objects.requireNonNull(batchId, "batchId 不得为 null");
        state = Objects.requireNonNull(state, "state 不得为 null");
        rawPayload = rawPayload == null ? "" : rawPayload;
        localMrn = localMrn == null ? "" : localMrn;
        createdAt = createdAt == null ? Instant.EPOCH : createdAt;
        updatedAt = updatedAt == null ? createdAt : updatedAt;
    }

    /**
     * 脱敏后的单行日志摘要。
     *
     * <p>只带控制号 / 消息类型 / 状态 / 版本，<b>不</b>带 rawPayload、localMrn；
     * 控制号本身不是患者标识，但空值显式写「(空)」——缺控制号的报文在日志里显示成裸分隔符串
     * 是排障时最难认的一种形态。
     */
    public String toLogLine() {
        return "ledgerId=" + id
                + " type=" + (key.messageType().isEmpty() ? "(空)" : key.messageType())
                + " ctrl=" + (key.controlId().isEmpty() ? "(空)" : key.controlId())
                + " batch=" + batchId
                + " state=" + state
                + " v=" + version
                + (degraded ? " degraded" : "")
                + (retryCount > 0 ? " retry=" + retryCount : "");
    }
}