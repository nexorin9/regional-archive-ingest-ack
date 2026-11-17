package cn.hospital.region.archive.redelivery;

import cn.hospital.region.archive.ledger.IdempotencyKey;
import cn.hospital.region.archive.ledger.LedgerSchema;
import cn.hospital.region.archive.ledger.MessageState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T33 重投调度器验收 —— 退避 + 上限 + 人工放行 + 可复现性。
 *
 * <p>不 mock 数据库：用一个共享 H2 PG 兼容内存库（{@code DB_CLOSE_DELAY=-1}）跑
 * V1..V5 迁移；{@link JdbcRedeliveryQueue} 每条调用 open/close 一条连接、接管一个事务。
 * 「20 次连续失败 → 上限 → 人工放行后又跑」这类断言只能真事务才能验。
 *
 * <p>调度器驱动用了 {@link RedeliveryScheduler#tickAt(long)} 注入时钟，生产侧
 * 调用 {@link RedeliveryScheduler#tick()}。
 */
class RedeliverySchedulerTest {

    private static final String H2_URL =
            "jdbc:h2:mem:sched_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

    /** 固定时钟：2026-10-20 09:00:00 +08:00；让 backoff/nextAt 可复现。 */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-10-20T01:00:00Z"), ZoneId.of("Asia/Shanghai"));

    private static final long ORIGIN_MS = FIXED_CLOCK.millis();

    private static JdbcRedeliveryQueue queue() {
        return new JdbcRedeliveryQueue(RedeliverySchedulerTest::openConnection, FIXED_CLOCK);
    }

    private static Connection openConnection() throws SQLException {
        return DriverManager.getConnection(H2_URL, "sa", "");
    }

    @BeforeAll
    static void migrateUp() throws SQLException {
        try (Connection conn = openConnection(); Statement st = conn.createStatement()) {
            LedgerSchema.migrate(conn);
        }
    }

    @BeforeEach
    void cleanTables() throws SQLException {
        try (Connection conn = openConnection(); Statement st = conn.createStatement()) {
            st.execute("DELETE FROM redelivery_queue");
            st.execute("DELETE FROM message_ledger");
            st.execute("DELETE FROM schema_version WHERE version IN ('test', 'sched-test')");
        }
    }

    // =====================================================================
    // 测试用工具
    // =====================================================================

    private static IdempotencyKey key(String s) {
        return IdempotencyKey.of("ADT^A08", s, "P");
    }

    private static long makeLedgerRow(IdempotencyKey k, String batchId) throws SQLException {
        try (Connection conn = openConnection();
             var ps = conn.prepareStatement(
                     "INSERT INTO message_ledger (message_type, control_id, processing_id, "
                             + "batch_id, state, raw_payload) VALUES (?, ?, ?, ?, ?, ?)",
                     new String[]{"id"})) {
            ps.setString(1, k.messageType());
            ps.setString(2, k.controlId());
            ps.setString(3, k.processingId());
            ps.setString(4, batchId);
            ps.setString(5, MessageState.RECEIVED.dbValue());
            ps.setString(6, "MSH|^~\\&|CHSCOMMUNITY|REGIONAL_ARCHIVE|REGIONAL_ARCHIVE|FACILITY_A|"
                    + "202610200900||ADT^A08|" + k.controlId() + "|P|2.5.1|||AL|NE||UNICODE UTF-8");
            ps.executeUpdate();
            try (var rs = ps.getGeneratedKeys()) {
                if (!rs.next()) {
                    throw new SQLException("INSERT message_ledger 未返回主键");
                }
                return rs.getLong(1);
            }
        }
    }

    /** 用于造一条已入队的 receiver-only 队列条目（直接插 SQL，绕开 offer 的准入）。 */
    private static long insertQueuedRow(IdempotencyKey k, long ledgerId, String batchId,
                                        int attempt, long nextAttemptMs, String enqueueReason)
            throws SQLException {
        try (Connection conn = openConnection();
             var ps = conn.prepareStatement(
                     "INSERT INTO redelivery_queue (ledger_id, idempotency_key, batch_id, status, "
                             + "attempt, next_attempt_at_ms, last_error, enqueue_reason) "
                             + "VALUES (?, ?, ?, 'QUEUED', ?, ?, ?, ?)",
                     new String[]{"id"})) {
            ps.setLong(1, ledgerId);
            ps.setString(2, k.storageKey());
            ps.setString(3, batchId);
            ps.setInt(4, attempt);
            ps.setLong(5, nextAttemptMs);
            ps.setString(6, "");
            ps.setString(7, enqueueReason);
            ps.executeUpdate();
            try (var rs = ps.getGeneratedKeys()) {
                if (!rs.next()) {
                    throw new SQLException("INSERT redelivery_queue 无返回主键");
                }
                return rs.getLong(1);
            }
        }
    }

    /** 把某一行的 status 手动推到 PERMANENT_FAILED，便于测人工放行。 */
    private static void forcePermanentFailed(long queueId) throws SQLException {
        try (Connection conn = openConnection();
             var ps = conn.prepareStatement(
                     "UPDATE redelivery_queue SET status='PERMANENT_FAILED', "
                             + "last_error='forced for test' WHERE id=?")) {
            ps.setLong(1, queueId);
            ps.executeUpdate();
        }
    }

    /** 用 {@link RedeliveryResult#retryableFailure} 的失败动作，每次返回同样的错误码。 */
    private static RedeliveryAction alwaysRetryable(String code) {
        return entry -> RedeliveryResult.retryableFailure(code,
                "retryable " + code + " for ledgerId=" + entry.ledgerId());
    }

    private static RedeliveryAction alwaysPermanent(String code) {
        return entry -> RedeliveryResult.permanentFailure(code,
                "permanent " + code + " for ledgerId=" + entry.ledgerId());
    }

    /** 第 N 次调用时切换为成功。count 从 0 开始；调用 0、1、2、... 第 switchAt 次之前返回失败，之后返回成功。 */
    private static RedeliveryAction succeedAfterN(int switchAt) {
        AtomicInteger calls = new AtomicInteger();
        return entry -> {
            int n = calls.getAndIncrement();
            if (n >= switchAt) {
                return RedeliveryResult.ok("对端回 AA（call#" + n + "）");
            }
            return RedeliveryResult.retryableFailure("UPSTREAM_500", "第 " + n + " 次失败");
        };
    }

    private static RedeliveryScheduler scheduler(RedeliveryQueue q, RedeliveryAction a,
                                                 BackoffPolicy b, int maxAttempts) {
        return new RedeliveryScheduler(q, a, b, maxAttempts, FIXED_CLOCK, RedeliveryScheduler.NOOP_HOOKS);
    }

    // =====================================================================
    // 1. 退避正确性：固定种子下延迟严格递增且不超过封顶
    // =====================================================================

    @Test
    @DisplayName("退避：用固定种子断言延迟严格递增且不超过封顶")
    void backoff_intervals_increase_and_are_capped() {
        // base=1000ms, max=60000ms, jitter=0.2, seed=42 —— 业内常见默认
        BackoffPolicy policy = new ExponentialBackoff(1_000L, 60_000L, 0.2, 42L);

        long now = 0L;
        long[] delays = new long[6];
        long[] expected = new long[]{1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L}; // 上界

        // 用 attempt=1..6 试算
        for (int i = 0; i < 6; i++) {
            int attempt = i + 1;
            long next = policy.nextAttemptAtMs(now, attempt);
            delays[i] = next - now;

            // 不超过封顶
            assertTrue(delays[i] <= 60_000L, "延迟不得超过 maxMs，实际=" + delays[i]);
            // 至少 1ms（在 0 延迟极端情况下也满足）
            assertTrue(delays[i] >= 0L, "延迟不得为负，实际=" + delays[i]);
            // 不大于无 jitter 的 2 倍上界
            assertTrue(delays[i] <= expected[i] * 1.2 + 1,
                    "尝试 " + attempt + " 延迟=" + delays[i] + " 应 ≤ " + expected[i] * 1.2 + 1);
        }

        // 严格递增：每次失败的下一次间隔都比上次长（2x 基础 + 抖动 ≠ 退化）
        for (int i = 1; i < delays.length; i++) {
            assertTrue(delays[i] > delays[i - 1],
                    "第 " + (i + 1) + " 次延迟必须严格大于第 " + i + " 次："
                            + delays[i - 1] + " vs " + delays[i]);
        }

        // 可复现：同种子创建第二个策略，对 attempt 序列必须逐字相等
        BackoffPolicy againPolicy = new ExponentialBackoff(1_000L, 60_000L, 0.2, 42L);
        for (int i = 0; i < 6; i++) {
            long again = againPolicy.nextAttemptAtMs(now, i + 1) - now;
            assertEquals(delays[i], again, "相同种子 + attempt 必须可复现：index=" + i);
        }
    }

    @Test
    @DisplayName("退避：无抖动 (jitter=0) 时序列逐字为 base × 2^(attempt-1)")
    void backoff_no_jitter_is_pure_exponential() {
        BackoffPolicy policy = new ExponentialBackoff(1_000L, 60_000L, 0.0, 7L);

        assertEquals(1_000L, policy.nextAttemptAtMs(0L, 1) - 0L);
        assertEquals(2_000L, policy.nextAttemptAtMs(0L, 2) - 0L);
        assertEquals(4_000L, policy.nextAttemptAtMs(0L, 3) - 0L);
        assertEquals(8_000L, policy.nextAttemptAtMs(0L, 4) - 0L);
        assertEquals(16_000L, policy.nextAttemptAtMs(0L, 5) - 0L);
        assertEquals(32_000L, policy.nextAttemptAtMs(0L, 6) - 0L);
        assertEquals(60_000L, policy.nextAttemptAtMs(0L, 7) - 0L, "达到 maxMs 封顶");
        assertEquals(60_000L, policy.nextAttemptAtMs(0L, 8) - 0L, "封顶后不变");
        assertEquals(60_000L, policy.nextAttemptAtMs(0L, 30) - 0L, "很大 attempt 仍被 cap 住");
    }

    @Test
    @DisplayName("退避：大 attempt 仍被 cap 住，不溢出")
    void backoff_caps_large_attempts_without_overflow() {
        // 哪怕 attempt = 1000 也得保证在 maxMs 之内，不溢出
        BackoffPolicy policy = new ExponentialBackoff(1_000L, 60_000L, 0.5, 99L);
        for (int i = 1; i < 100; i++) {
            long delay = policy.nextAttemptAtMs(0L, i) - 0L;
            assertTrue(delay <= 90_000L, // 0.5 jitter → maxMs * 1.5
                    "attempt=" + i + " 延迟=" + delay + " 不得超 1.5 × 60_000");
            assertTrue(delay >= 0L, "延迟不得为负");
        }
    }

    @Test
    @DisplayName("退避：base / max 互换 / jitter 出界构造期即被拒")
    void backoff_input_validation() {
        assertThrows(IllegalArgumentException.class,
                () -> new ExponentialBackoff(0L, 60_000L, 0.2, 1L), "baseMs<=0 必拒");
        assertThrows(IllegalArgumentException.class,
                () -> new ExponentialBackoff(-1L, 60_000L, 0.2, 1L), "baseMs<0 必拒");
        assertThrows(IllegalArgumentException.class,
                () -> new ExponentialBackoff(1_000L, 500L, 0.2, 1L), "maxMs<baseMs 必拒");
        assertThrows(IllegalArgumentException.class,
                () -> new ExponentialBackoff(1_000L, 1_000L, -0.1, 1L), "jitter<0 必拒");
        assertThrows(IllegalArgumentException.class,
                () -> new ExponentialBackoff(1_000L, 1_000L, 1.0, 1L), "jitter≥1.0 必拒");
        assertThrows(IllegalArgumentException.class,
                () -> new ExponentialBackoff(1_000L, 1_000L, 1.5, 1L), "jitter>1 必拒");

        // 同参数两次构造对同一 attempt 输出相等（参数与种子一致）
        BackoffPolicy a = new ExponentialBackoff(500L, 5_000L, 0.3, 17L);
        BackoffPolicy b = new ExponentialBackoff(500L, 5_000L, 0.3, 17L);
        assertEquals(a.nextAttemptAtMs(0L, 1), b.nextAttemptAtMs(0L, 1));
        assertEquals(a.nextAttemptAtMs(0L, 4), b.nextAttemptAtMs(0L, 4));

        // 不同种子 → 输出不同（至少有很微小差异，更稳健）
        BackoffPolicy c = new ExponentialBackoff(500L, 5_000L, 0.3, 17L);
        BackoffPolicy d = new ExponentialBackoff(500L, 5_000L, 0.3, 18L);
        boolean anyDifferent = false;
        for (int i = 1; i <= 5; i++) {
            if (c.nextAttemptAtMs(0L, i) != d.nextAttemptAtMs(0L, i)) {
                anyDifferent = true;
                break;
            }
        }
        assertTrue(anyDifferent, "不同种子在 5 次序列内至少应有一次结果不同");
    }

    // =====================================================================
    // 2. 调度：成功 → ACK_CONFIRMED；命中上限 → PERMANENT_FAILED
    // =====================================================================

    @Test
    @DisplayName("调度：第一次成功直接 ACK_CONFIRMED，attempt=1，无重试")
    void first_attempt_success_yields_ack_confirmed() throws SQLException {
        IdempotencyKey k = key("SCHED-OK-0001");
        long ledgerId = makeLedgerRow(k, "BATCH-SCHED-OK");

        JdbcRedeliveryQueue q = queue();
        insertQueuedRow(k, ledgerId, "BATCH-SCHED-OK", 0, 0L, "msa-ae");
        long queueId = q.listByLedger(ledgerId).get(0).id();

        RedeliveryScheduler s = scheduler(q, entry -> RedeliveryResult.ok("对端回 AA"), newTestBackoff(), 3);

        Optional<RedeliveryEntry> first = s.tickAt(ORIGIN_MS);
        assertTrue(first.isPresent());

        // 调度器返回的是 claimNextDue 时的引用，DB 状态以 listByLedger 为准
        RedeliveryEntry fromQueue = q.listByLedger(ledgerId).get(0);
        assertEquals(RedeliveryStatus.ACK_CONFIRMED, fromQueue.status());
        assertEquals(1, fromQueue.attempt());

        // 队列里不再有 QUEUED
        assertEquals(0L, q.queuedCount());
    }

    @Test
    @DisplayName("调度：连续失败达到 MAX_ATTEMPTS 后转 PERMANENT_FAILED，不再自动出队")
    void exceeds_max_attempts_becomes_permanent_failure() throws SQLException {
        IdempotencyKey k = key("SCHED-PERM-0001");
        long ledgerId = makeLedgerRow(k, "BATCH-SCHED-PERM");
        insertQueuedRow(k, ledgerId, "BATCH-SCHED-PERM", 0, 0L, "msa-ae");

        JdbcRedeliveryQueue q = queue();
        long queueId = q.listByLedger(ledgerId).get(0).id();

        // MAX_ATTEMPTS=3：总共 3 次失败后转 PERMANENT_FAILED
        RedeliveryScheduler s = scheduler(q, alwaysRetryable("UPSTREAM_500"), newTestBackoff(), 3);

        // tick 1 (now=ORIGIN) → attempt=1，失败，可重试，落 attempt=1, nextAt=X
        Optional<RedeliveryEntry> t1 = s.tickAt(ORIGIN_MS);
        assertTrue(t1.isPresent());

        long next1 = q.listByLedger(ledgerId).get(0).nextAttemptAtMs();
        assertTrue(next1 > ORIGIN_MS);
        assertEquals(1, q.listByLedger(ledgerId).get(0).attempt());

        // 队列里现在有一个 QUEUED 状态行（因为 reschedule+release）
        assertEquals(1L, q.queuedCount());

        // tick 2 at next1 → attempt=2，再失败，可重试
        Optional<RedeliveryEntry> t2 = s.tickAt(next1);
        assertTrue(t2.isPresent());
        long next2 = q.listByLedger(ledgerId).get(0).nextAttemptAtMs();
        assertTrue(next2 > next1, "nextAttemptAt 应递增");
        assertEquals(2, q.listByLedger(ledgerId).get(0).attempt());

        // tick 3 at next2 → attempt=3==MAX_ATTEMPTS → PERMANENT_FAILED
        Optional<RedeliveryEntry> t3 = s.tickAt(next2);
        assertTrue(t3.isPresent());

        // 行应停 PERMANENT_FAILED，队列里 QUEUED=0
        RedeliveryEntry row = q.listByLedger(ledgerId).get(0);
        assertEquals(RedeliveryStatus.PERMANENT_FAILED, row.status());
        assertEquals(3, row.attempt());
        assertEquals(0L, q.queuedCount());

        // 即使时钟继续推进，claimNextDue 也拿不到这条
        long future = next2 + 1_000_000L;
        Optional<RedeliveryEntry> none = q.claimNextDue(future);
        assertFalse(none.isPresent(), "PERMANENT_FAILED 不应被 claimNextDue 自动取出");
    }

    @Test
    @DisplayName("调度：永远返回不可重试失败，第一次失败就直接 PERMANENT_FAILED")
    void permanent_failure_short_circuits_to_permanent_failed() throws SQLException {
        IdempotencyKey k = key("SCHED-PERM-SHORT-0001");
        long ledgerId = makeLedgerRow(k, "BATCH-SCHED-SHORT");
        insertQueuedRow(k, ledgerId, "BATCH-SCHED-SHORT", 0, 0L, "msa-ae");
        JdbcRedeliveryQueue q = queue();

        RedeliveryScheduler s = scheduler(q, alwaysPermanent("BAD_REQUEST_400"), newTestBackoff(), 5);

        Optional<RedeliveryEntry> ticked = s.tickAt(ORIGIN_MS);
        assertTrue(ticked.isPresent());
        RedeliveryEntry row = q.listByLedger(ledgerId).get(0);
        assertEquals(RedeliveryStatus.PERMANENT_FAILED, row.status());
        assertEquals(1, row.attempt());
        assertEquals(0L, q.queuedCount(), "永久失败后队列里不应再有 QUEUED");
    }

    @Test
    @DisplayName("调度：异常被 action 抛时归为不可重试失败 → PERMANENT_FAILED")
    void uncaught_exception_short_circuits() throws SQLException {
        IdempotencyKey k = key("SCHED-EXC-0001");
        long ledgerId = makeLedgerRow(k, "BATCH-SCHED-EXC");
        insertQueuedRow(k, ledgerId, "BATCH-SCHED-EXC", 0, 0L, "msa-ae");
        JdbcRedeliveryQueue q = queue();

        RedeliveryAction throwing = entry -> {
            throw new IllegalStateException("网络断了");
        };
        RedeliveryScheduler s = scheduler(q, throwing, newTestBackoff(), 5);

        Optional<RedeliveryEntry> ticked = s.tickAt(ORIGIN_MS);
        assertTrue(ticked.isPresent());
        RedeliveryEntry row = q.listByLedger(ledgerId).get(0);
        assertEquals(RedeliveryStatus.PERMANENT_FAILED, row.status());
        assertTrue(row.lastError().contains("UNCAUGHT_EXCEPTION"),
                "lastError 应记录 UNCAUGHT_EXCEPTION 码: " + row.lastError());
    }

    @Test
    @DisplayName("调度：中途某次成功后停止重试（first failure then success 不再递增 attempt）")
    void recover_after_two_retries_yields_ack_confirmed_with_attempt_3() throws SQLException {
        IdempotencyKey k = key("SCHED-RECOVER-0001");
        long ledgerId = makeLedgerRow(k, "BATCH-SCHED-RECOVER");
        insertQueuedRow(k, ledgerId, "BATCH-SCHED-RECOVER", 0, 0L, "msa-ae");
        JdbcRedeliveryQueue q = queue();

        RedeliveryScheduler s = scheduler(q, succeedAfterN(2), newTestBackoff(), 5);

        // tick 1 (fail) → attempt=1, reschedule
        assertTrue(s.tickAt(ORIGIN_MS).isPresent());
        assertEquals(1, q.listByLedger(ledgerId).get(0).attempt());
        long next1 = q.listByLedger(ledgerId).get(0).nextAttemptAtMs();

        // tick 2 (fail) → attempt=2, reschedule
        assertTrue(s.tickAt(next1).isPresent());
        assertEquals(2, q.listByLedger(ledgerId).get(0).attempt());
        long next2 = q.listByLedger(ledgerId).get(0).nextAttemptAtMs();

        // tick 3 (success) → ACK_CONFIRMED, attempt=3
        assertTrue(s.tickAt(next2).isPresent());
        RedeliveryEntry row = q.listByLedger(ledgerId).get(0);
        assertEquals(3, row.attempt());
        assertEquals(RedeliveryStatus.ACK_CONFIRMED, row.status());
        assertEquals(0L, q.queuedCount());
    }

    // =====================================================================
    // 3. 人工放行：PERMANENT_FAILED → QUEUED + 重置 attempt
    // =====================================================================

    @Test
    @DisplayName("人工放行：PERMANENT_FAILED 可被放回 QUEUED，attempt=0，nextAt=now，并被重新 claim")
    void manual_release_resumes_once() throws SQLException {
        IdempotencyKey k = key("SCHED-RELEASE-0001");
        long ledgerId = makeLedgerRow(k, "BATCH-SCHED-RELEASE");
        insertQueuedRow(k, ledgerId, "BATCH-SCHED-RELEASE", 0, 0L, "msa-ae");
        JdbcRedeliveryQueue q = queue();

        RedeliveryScheduler s = scheduler(q, alwaysRetryable("UPSTREAM_500"), newTestBackoff(), 2);

        // 跑到上限：tick 两次后变 PERMANENT_FAILED
        s.tickAt(ORIGIN_MS);
        long next1 = q.listByLedger(ledgerId).get(0).nextAttemptAtMs();
        s.tickAt(next1);
        long queueId = q.listByLedger(ledgerId).get(0).id();
        forcePermanentFailed(queueId);

        // 验一次是 PERMANENT_FAILED
        assertEquals(RedeliveryStatus.PERMANENT_FAILED,
                q.listByLedger(ledgerId).get(0).status());

        // 人工放行 —— 此时 attempt 已被强制写为 3（被 force 了 lastError 但 attempt 应仍 2）
        long releaseAt = ORIGIN_MS + 5_000_000L;
        RedeliveryEntry released = s.manualRelease(queueId, ledgerId, releaseAt, "operator-007");
        assertEquals(RedeliveryStatus.QUEUED, released.status());
        assertEquals(0, released.attempt(), "attempt 复位到 0");
        assertEquals(releaseAt, released.nextAttemptAtMs());
        assertEquals(1L, q.queuedCount());

        // 验证 DB 与返回值一致（防止引用残影）
        RedeliveryEntry fromQueue = q.listByLedger(ledgerId).stream()
                .filter(e -> e.id() == queueId).findFirst().orElseThrow();
        assertEquals(0, fromQueue.attempt(), "DB 中 attempt 也应是 0");
        assertEquals(RedeliveryStatus.QUEUED, fromQueue.status());

        // 立即可被 claim —— 这次 action 切到成功，验证一次性恢复
        RedeliveryScheduler s2 = new RedeliveryScheduler(q,
                entry -> RedeliveryResult.ok("恢复成功"),
                newTestBackoff(), 5, FIXED_CLOCK, RedeliveryScheduler.NOOP_HOOKS);
        Optional<RedeliveryEntry> ticked = s2.tickAt(releaseAt);
        assertTrue(ticked.isPresent());
        RedeliveryEntry finalRow = q.listByLedger(ledgerId).stream()
                .filter(e -> e.id() == queueId).findFirst().orElseThrow();
        assertEquals(1, finalRow.attempt());
        assertEquals(RedeliveryStatus.ACK_CONFIRMED, finalRow.status());
    }

    @Test
    @DisplayName("人工放行：operator 为空被前置拒绝，不动 DB")
    void manual_release_blank_operator_refused() throws SQLException {
        IdempotencyKey k = key("SCHED-RELEASE-NOOP-0001");
        long ledgerId = makeLedgerRow(k, "BATCH-SCHED-RELEASE-NOOP");
        insertQueuedRow(k, ledgerId, "BATCH-SCHED-RELEASE-NOOP", 0, 0L, "msa-ae");
        JdbcRedeliveryQueue q = queue();
        long queueId = q.listByLedger(ledgerId).get(0).id();
        forcePermanentFailed(queueId);

        RedeliveryScheduler s = scheduler(q, alwaysRetryable("UPSTREAM_500"), newTestBackoff(), 3);

        IllegalArgumentException ex1 = assertThrows(IllegalArgumentException.class,
                () -> s.manualRelease(queueId, ledgerId, ORIGIN_MS, null));
        assertTrue(ex1.getMessage().contains("operator"));
        IllegalArgumentException ex2 = assertThrows(IllegalArgumentException.class,
                () -> s.manualRelease(queueId, ledgerId, ORIGIN_MS, "   "));
        assertTrue(ex2.getMessage().contains("operator"));

        // 状态未变
        assertEquals(RedeliveryStatus.PERMANENT_FAILED,
                q.listByLedger(ledgerId).get(0).status());
    }

    @Test
    @DisplayName("人工放行：行不在 PERMANENT_FAILED 时拒绝并明示拒绝原因")
    void manual_release_refused_on_non_permanent_failed() throws SQLException {
        IdempotencyKey k = key("SCHED-REFUSE-0001");
        long ledgerId = makeLedgerRow(k, "BATCH-SCHED-REFUSE");
        insertQueuedRow(k, ledgerId, "BATCH-SCHED-REFUSE", 0, 0L, "msa-ae");
        JdbcRedeliveryQueue q = queue();
        long queueId = q.listByLedger(ledgerId).get(0).id();

        RedeliveryScheduler s = scheduler(q, alwaysRetryable("UPSTREAM_500"), newTestBackoff(), 3);

        // 行仍是 QUEUED，未走到 PERMANENT_FAILED —— 直接 manualRelease 应被拒
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> s.manualRelease(queueId, ledgerId, ORIGIN_MS, "operator-008"));
        assertTrue(ex.getMessage().contains("PERMANENT_FAILED")
                        || ex.getMessage().contains("CONSUMED")
                        || ex.getMessage().contains("QUEUED"),
                "拒绝理由必须显式说明状态: " + ex.getMessage());

        assertEquals(RedeliveryStatus.QUEUED, q.listByLedger(ledgerId).get(0).status(),
                "拒绝后行不应被改");
    }

    @Test
    @DisplayName("人工放行：同一行被人释放两次时第二次被拒（已是 QUEUED）")
    void manual_release_idempotent_refusal_on_already_queued() throws SQLException {
        IdempotencyKey k = key("SCHED-DBL-RELEASE-0001");
        long ledgerId = makeLedgerRow(k, "BATCH-SCHED-DBL");
        long queueId = insertQueuedRow(k, ledgerId, "BATCH-SCHED-DBL", 0, 0L, "msa-ae");
        JdbcRedeliveryQueue q = queue();

        // 把这一行推到 PERMANENT_FAILED
        forcePermanentFailed(queueId);

        RedeliveryScheduler s = scheduler(q, alwaysRetryable("UPSTREAM_500"), newTestBackoff(), 3);

        RedeliveryEntry first = s.manualRelease(queueId, ledgerId, ORIGIN_MS, "operator-009");
        assertEquals(RedeliveryStatus.QUEUED, first.status());

        // 第二次释放：状态已 QUEUED，应被拒
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> s.manualRelease(queueId, ledgerId, ORIGIN_MS, "operator-009"));
        assertTrue(ex.getMessage().contains("PERMANENT_FAILED")
                        || ex.getMessage().contains("QUEUED"),
                "拒绝理由含状态: " + ex.getMessage());
    }

    // =====================================================================
    // 4. 调度器接口可见性
    // =====================================================================

    @Test
    @DisplayName("调度器：暴露 BackoffPolicy 供测试直接验证退避")
    void scheduler_exposes_backoff() {
        BackoffPolicy policy = newTestBackoff();
        RedeliveryScheduler s = scheduler(queue(), alwaysRetryable("UPSTREAM_500"),
                policy, 3);
        assertSame(policy, s.backoff(), "暴露的策略应与构造时传入一致");
        assertEquals(3, s.maxAttempts());
    }

    @Test
    @DisplayName("调度器：队列空或未到期时 tick() 返回 empty")
    void scheduler_tick_returns_empty_when_no_due() {
        JdbcRedeliveryQueue q = queue();
        RedeliveryScheduler s = scheduler(q, alwaysRetryable("UPSTREAM_500"),
                newTestBackoff(), 3);
        Optional<RedeliveryEntry> empty = s.tickAt(ORIGIN_MS);
        assertFalse(empty.isPresent());
    }

    @Test
    @DisplayName("调度器：maxAttempts < 1 在构造期拒")
    void scheduler_max_attempts_input_validation() {
        assertThrows(IllegalArgumentException.class,
                () -> scheduler(queue(), alwaysRetryable("UPSTREAM_500"),
                        newTestBackoff(), 0));
        assertThrows(IllegalArgumentException.class,
                () -> scheduler(queue(), alwaysRetryable("UPSTREAM_500"),
                        newTestBackoff(), -1));
    }

    @Test
    @DisplayName("调度器：null action / null backoff / null clock 在构造期拒")
    void scheduler_input_validation() {
        JdbcRedeliveryQueue q = queue();
        BackoffPolicy b = newTestBackoff();

        assertThrows(NullPointerException.class,
                () -> new RedeliveryScheduler(null, alwaysRetryable("X"), b, 3, FIXED_CLOCK,
                        RedeliveryScheduler.NOOP_HOOKS));
        assertThrows(NullPointerException.class,
                () -> new RedeliveryScheduler(q, null, b, 3, FIXED_CLOCK,
                        RedeliveryScheduler.NOOP_HOOKS));
        assertThrows(NullPointerException.class,
                () -> new RedeliveryScheduler(q, alwaysRetryable("X"), null, 3, FIXED_CLOCK,
                        RedeliveryScheduler.NOOP_HOOKS));
        assertThrows(NullPointerException.class,
                () -> new RedeliveryScheduler(q, alwaysRetryable("X"), b, 3, null,
                        RedeliveryScheduler.NOOP_HOOKS));
        // null hooks 是 OK 的，调度器默认 NOOP_HOOKS
        RedeliveryScheduler noHooks = new RedeliveryScheduler(q, alwaysRetryable("X"), b, 3, FIXED_CLOCK, null);
        assertNotNull(noHooks);
    }

    // =====================================================================
    // 5. 钩子正确触发
    // =====================================================================

    @Test
    @DisplayName("钩子：成功 onSuccess 触发，永久失败 onPermanentFailure 触发，人工放行 onManualRelease 触发")
    void hooks_invoked_at_each_outcome() throws SQLException {
        IdempotencyKey k1 = key("HOOK-OK-0001");
        IdempotencyKey k2 = key("HOOK-PERM-0002");
        IdempotencyKey k3 = key("HOOK-REL-0003");
        long lid1 = makeLedgerRow(k1, "BATCH-HOOK");
        long lid2 = makeLedgerRow(k2, "BATCH-HOOK");
        long lid3 = makeLedgerRow(k3, "BATCH-HOOK");
        long qid1 = insertQueuedRow(k1, lid1, "BATCH-HOOK", 0, 0L, "msa-ae");
        long qid2 = insertQueuedRow(k2, lid2, "BATCH-HOOK", 0, 0L, "msa-ae");
        long qid3 = insertQueuedRow(k3, lid3, "BATCH-HOOK", 0, 0L, "msa-ae");

        JdbcRedeliveryQueue q = queue();

        List<String> calls = new ArrayList<>();
        RedeliveryScheduler.Hooks hooks = new RedeliveryScheduler.Hooks() {
            @Override public void onSuccess(RedeliveryEntry e, int a) {
                calls.add("OK:" + e.ledgerId() + ":" + a);
            }
            @Override public void onPermanentFailure(RedeliveryEntry e, int a, String le) {
                calls.add("PERM:" + e.ledgerId() + ":" + a + ":" + le);
            }
            @Override public void onManualRelease(RedeliveryEntry e, String o) {
                calls.add("REL:" + e.ledgerId() + ":" + o);
            }
        };

        RedeliveryScheduler s = new RedeliveryScheduler(q,
                entry -> RedeliveryResult.ok("ok"), newTestBackoff(), 3, FIXED_CLOCK, hooks);
        // success
        s.tickAt(ORIGIN_MS);

        // 第二个 queue 改成总是不可重试失败
        RedeliveryScheduler s2 = new RedeliveryScheduler(q,
                alwaysPermanent("BAD_REQUEST_400"), newTestBackoff(), 3, FIXED_CLOCK, hooks);
        s2.tickAt(ORIGIN_MS);

        // 第三个：先走到 PERMANENT_FAILED，再手动放行
        RedeliveryScheduler s3 = new RedeliveryScheduler(q,
                alwaysRetryable("UPSTREAM_500"), newTestBackoff(), 2, FIXED_CLOCK, hooks);
        s3.tickAt(ORIGIN_MS);
        // 第二轮 tick 才到 PERMANENT_FAILED
        long next = q.listByLedger(lid3).get(0).nextAttemptAtMs();
        s3.tickAt(next);
        forcePermanentFailed(qid3);
        s3.manualRelease(qid3, lid3, ORIGIN_MS, "operator-hook");

        // 期望四条：OK（lid1）、PERM（lid2）、PERM（lid3 达到 MAX_ATTEMPTS=2）、REL（lid3 人工放行）
        assertEquals(4, calls.size(), "应当恰好触发 4 次钩子：实际 calls=" + calls);
        assertTrue(calls.get(0).startsWith("OK:") && calls.get(0).contains(":" + lid1 + ":1"),
                "第一条应是 OK 含 ledgerId 与 attempt=1，实际=" + calls.get(0));
        assertTrue(calls.get(1).startsWith("PERM:") && calls.get(1).contains(":" + lid2 + ":1"),
                "第二条应是 PERM 含 ledgerId 与 attempt=1（不可重试），实际=" + calls.get(1));
        assertTrue(calls.get(2).startsWith("PERM:") && calls.get(2).contains(":" + lid3 + ":2:"),
                "第三条应是 PERM 含 ledgerId 与 attempt=2（达 MAX_ATTEMPTS=2），实际=" + calls.get(2));
        assertTrue(calls.get(3).startsWith("REL:") && calls.get(3).contains(":" + lid3 + ":operator-hook"),
                "第四条应是 REL 含 ledgerId 与 operator，实际=" + calls.get(3));
    }

    @Test
    @DisplayName("钩子：成功钩子抛错也不会回滚队列已 ACK_CONFIRMED")
    void hooks_failure_does_not_undo_settle() throws SQLException {
        IdempotencyKey k = key("HOOK-FAIL-0001");
        long lid = makeLedgerRow(k, "BATCH-HOOK-FAIL");
        insertQueuedRow(k, lid, "BATCH-HOOK-FAIL", 0, 0L, "msa-ae");
        JdbcRedeliveryQueue q = queue();

        RedeliveryScheduler.Hooks failing = new RedeliveryScheduler.Hooks() {
            @Override public void onSuccess(RedeliveryEntry e, int a) throws java.sql.SQLException {
                throw new SQLException("ledger 写失败");
            }
            @Override public void onPermanentFailure(RedeliveryEntry e, int a, String le) { }
            @Override public void onManualRelease(RedeliveryEntry e, String o) { }
        };
        RedeliveryScheduler s = new RedeliveryScheduler(q,
                entry -> RedeliveryResult.ok("ok"), newTestBackoff(), 3, FIXED_CLOCK, failing);

        s.tickAt(ORIGIN_MS);

        // 队列应仍为 ACK_CONFIRMED —— 钩子错只是 WARNING，不回滚队列
        RedeliveryEntry row = q.listByLedger(lid).get(0);
        assertEquals(RedeliveryStatus.ACK_CONFIRMED, row.status());
        assertEquals(1, row.attempt());
    }

    // =====================================================================
    // 工具
    // =====================================================================

    /** 测试用退避策略 —— 短延迟 + 无抖动，便于人工推时钟。 */
    private static BackoffPolicy newTestBackoff() {
        return new ExponentialBackoff(100L, 5_000L, 0.0, 7L);
    }

    /** 读回某 queueId 的最新状态，便于在测试中切片断言。 */
    private static RedeliveryEntry listQueueStatus(JdbcRedeliveryQueue q, long queueId, long ledgerId) throws SQLException {
        return q.listByLedger(ledgerId).stream()
                .filter(e -> e.id() == queueId)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("queueId=" + queueId + " 不存在"));
    }
}
