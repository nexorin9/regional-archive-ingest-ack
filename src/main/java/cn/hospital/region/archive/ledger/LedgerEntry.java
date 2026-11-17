package cn.hospital.region.archive.ledger;

import java.util.Objects;

/**
 * 一条待入账的报文 —— {@code insertIfAbsent} 的入参。
 *
 * <p>只带「台账行在首次落库那一刻就确定了的字段」。{@code state} 在构造器里被<b>强制</b>为
 * {@link MessageState#RECEIVED}：入站台账行的默认状态只能是已接收，其它状态一律是
 * 一次 {@link JdbcLedgerStore#transition} 的结果。这条约束把「直接 INSERT 一条已落地」这种
 * 后门堵在编译期 —— 写不出这样的 {@code LedgerEntry}。
 *
 * <p>{@code localMrn} 与 {@code patientRef}（区域平台 PID-3）在本 record 里<b>不</b>分开两个字段：
 * 入账那一刻 {@code patientRef} 只是「报文里带的区域主键」，它<b>尚未经人工确认</b>，
 * 与确认后才成立的院内主键不是同一类事实。把两者放进同一个构造器一次传进来，
 * 等于允许调用方在入账时就填一个从未被核对过的院内主键。
 * 双主键的绑定由 {@code identity.IdentityKeyBinding} 与 {@code identity.JdbcPatientKeyStore}
 * 在确认之后分两列写入（V3 新增的 {@code patient_ref} 与 V1 的 {@code local_mrn}），
 * 两侧各自独立、互不冒名。故本 record 仍只带 {@code localMrn} 一列，与入账语义一致。
 */
public record LedgerEntry(IdempotencyKey key,
                          String batchId,
                          String rawPayload,
                          String localMrn,
                          boolean degraded,
                          int retryCount) {

    public LedgerEntry {
        Objects.requireNonNull(key, "幂等键不得为 null");
        // 三段全空不是问题：报文头被整个剥掉的报文仍要入账并回一条带 ERR 的 AR（那是 T7/T8 的职责）。
        batchId = requireNonBlank(batchId, "batchId");
        rawPayload = Objects.requireNonNull(rawPayload, "rawPayload 不得为 null（空报文用空串）");
        localMrn = localMrn == null ? "" : localMrn;
        if (retryCount < 0) {
            throw new IllegalArgumentException("retryCount 不得为负数: " + retryCount);
        }
    }

    /** 常规构造：首次入账的报文（localMrn 空、未降级、重投计数 0）。 */
    public static LedgerEntry inbound(IdempotencyKey key, String batchId, String rawPayload) {
        return new LedgerEntry(key, batchId, rawPayload, "", false, 0);
    }

    /** 带模型降级标记的构造。 */
    public static LedgerEntry inboundDegraded(IdempotencyKey key, String batchId, String rawPayload) {
        return new LedgerEntry(key, batchId, rawPayload, "", true, 0);
    }

    private static String requireNonBlank(String value, String field) {
        Objects.requireNonNull(value, field + " 不得为 null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " 不得为空串（一条没有批次归属的报文无法按批次对账）");
        }
        return value;
    }
}