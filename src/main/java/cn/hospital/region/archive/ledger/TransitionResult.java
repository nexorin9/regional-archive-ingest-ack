package cn.hospital.region.archive.ledger;

import java.time.Instant;
import java.util.Objects;

/**
 * 一次 {@link LedgerStore#transition} 成功后的回执 —— 含新版本号，供调用方接着做乐观锁。
 *
 * <p>{@code previousVersion} / {@code newVersion} 是 T15 {@code IdempotencyGuard} 乐观锁的输入：
 * 调用方拿 {@link #newVersion()} 作为下一次迁移的期望版本。T13 已经在 {@code transition} 里
 * 做了 {@code version = version + 1} 的自增与 {@code WHERE state = ?} 的来源态守卫；
 * T15 在同一条 SQL 上再加 {@code AND version = ?} 即可，不需改本 record。
 */
public record TransitionResult(long ledgerId,
                               MessageState from,
                               MessageState to,
                               long previousVersion,
                               long newVersion,
                               String operator,
                               String reason,
                               Instant at) {

    public TransitionResult {
        if (ledgerId <= 0) {
            throw new IllegalArgumentException("台账行主键必须为正数，实际=" + ledgerId);
        }
        from = Objects.requireNonNull(from, "from 不得为 null");
        to = Objects.requireNonNull(to, "to 不得为 null");
        if (newVersion != previousVersion + 1) {
            throw new IllegalArgumentException(
                    "版本号必须恰好自增 1：previous=" + previousVersion + " new=" + newVersion);
        }
        operator = Objects.requireNonNull(operator, "operator 不得为 null");
        reason = Objects.requireNonNull(reason, "reason 不得为 null");
        at = at == null ? Instant.EPOCH : at;
    }

    /** 单行日志摘要，不含报文原文。 */
    public String toLogLine() {
        return "ledgerId=" + ledgerId + " " + from.dbValue() + "→" + to.dbValue()
                + " v" + previousVersion + "→" + newVersion
                + " operator=" + operator + " reason=" + reason;
    }
}