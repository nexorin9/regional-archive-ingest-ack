package cn.hospital.region.archive.redelivery;

import java.time.Instant;
import java.util.Objects;

/**
 * 一条重投条目 —— 一次「重投尝试」的快照。
 *
 * <p><b>与 {@code LedgerRow} 的关系</b>：同一报文在台账只有一行，在队列里可能有多行
 * （每次出队后被调度器持锁 → 重投结算，<i>可能</i> 再次出队），所以一条 {@code RedeliveryEntry}
 * 与 {@code LedgerRow} 是 N:1 而非 1:1。
 *
 * <p><b>不含报文原文</b>：报文原文留在台账里，按 {@code ledgerId} 回读，避免两处各存一份且只有
 * 一处参与脱敏。
 *
 * <p><b>{@code idempotencyKey} 字段</b>：原样存 {@code MSH-9 + MSH-10 + MSH-11} 的
 * {@code storageKey} 形式（{@code ledger.IdempotencyKey.storageKey()}），与台账
 * {@code uk_redelivery_idem_key} 同口径，让 T34 对账差集能直接按字节比对。
 */
public record RedeliveryEntry(long id,
                              long ledgerId,
                              String idempotencyKey,
                              String batchId,
                              RedeliveryStatus status,
                              int attempt,
                              long nextAttemptAtMs,
                              String lastError,
                              String enqueueReason,
                              Instant createdAt,
                              Instant lastAttemptAt,
                              Instant consumedAt) {

    public RedeliveryEntry {
        if (id <= 0) {
            throw new IllegalArgumentException("队列条目主键必须为正数，实际=" + id);
        }
        if (ledgerId <= 0) {
            throw new IllegalArgumentException("ledgerId 必须为正数，实际=" + ledgerId);
        }
        Objects.requireNonNull(idempotencyKey, "idempotencyKey 不得为 null");
        if (idempotencyKey.isEmpty()) {
            throw new IllegalArgumentException("idempotencyKey 不得为空串");
        }
        Objects.requireNonNull(batchId, "batchId 不得为 null");
        if (batchId.isBlank()) {
            throw new IllegalArgumentException("batchId 不得为空（无批次的重投条目无法对账）");
        }
        status = Objects.requireNonNull(status, "status 不得为 null");
        if (attempt < 0) {
            throw new IllegalArgumentException("attempt 不得为负数: " + attempt);
        }
        Objects.requireNonNull(lastError, "lastError 不得为 null（空串即可）");
        Objects.requireNonNull(enqueueReason, "enqueueReason 不得为 null（空串即可）");
        createdAt = createdAt == null ? Instant.EPOCH : createdAt;
        lastAttemptAt = lastAttemptAt == null ? createdAt : lastAttemptAt;
        // consumedAt 可空：QUEUED 时为 null；CONSUMED 起非 null。
    }

    /**
     * 脱敏后的日志摘要 —— 与 {@code LedgerRow.toLogLine} 同口径。
     * 只露 ledgerId / batchId / status / attempt，不带 idempotencyKey 也不带 lastError 原文。
     */
    public String toLogLine() {
        return "queueId=" + id
                + " ledgerId=" + ledgerId
                + " batch=" + batchId
                + " status=" + status
                + " attempt=" + attempt
                + " nextAtMs=" + nextAttemptAtMs;
    }
}
