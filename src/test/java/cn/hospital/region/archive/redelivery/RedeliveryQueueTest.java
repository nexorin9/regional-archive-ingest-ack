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
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * T32 重投队列验收 —— 入队条件 + AR 排除 + 同 key 不重 + 原子出队 + 结算/重排/放行。
 *
 * <p><b>不 mock 数据库</b>：用一个共享 H2 PG 兼容内存库（{@code DB_CLOSE_DELAY=-1}）跑
 * V1..V5 迁移，{@link JdbcRedeliveryQueue} 每条调用 open/close 一条连接、接管一个事务。
 * 「并发下出队只一条拿到」「唯一约束冲突被翻译成 Duplicate」「submitter 失败的整批回滚」
 * 这类断言只有真事务才能验。
 *
 * <p>{@link #cleanTables()} 在每条用例前清两张表，保证用例之间不互相污染。
 */
class RedeliveryQueueTest {

    private static final String H2_URL =
            "jdbc:h2:mem:rdq_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

    /** 固定时钟：2026-10-20 09:00:00 +08:00；让 created_at 等时间列可复现。 */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-10-20T01:00:00Z"), ZoneId.of("Asia/Shanghai"));

    private static JdbcRedeliveryQueue queue() {
        return new JdbcRedeliveryQueue(RedeliveryQueueTest::openConnection, FIXED_CLOCK);
    }

    private static Connection openConnection() throws SQLException {
        return DriverManager.getConnection(H2_URL, "sa", "");
    }

    @BeforeAll
    static void migrateUp() throws SQLException {
        try (Connection conn = openConnection(); Statement st = conn.createStatement()) {
            // 与生产同源：一次 migrate 跑完 V1..V5
            LedgerSchema.migrate(conn);
        }
    }

    @BeforeEach
    void cleanTables() throws SQLException {
        try (Connection conn = openConnection(); Statement st = conn.createStatement()) {
            st.execute("DELETE FROM redelivery_queue");
            st.execute("DELETE FROM message_ledger");
            st.execute("DELETE FROM schema_version WHERE version IN ('test', 'queue-test')");
        }
    }

    // ===================================================================
    // 测试用工具
    // ===================================================================

    private static IdempotencyKey key(String s) {
        return IdempotencyKey.of("ADT^A08", s, "P");
    }

    /**
     * 让 {@code message_ledger} 上有同 ledgerId + 同 idempotency_key 的一行存在；
     * 因为 V5 的 uk_redelivery_idem_key 与 V1 的 uk_ledger_idem_key 同名同口径，
     * 两条 INSERT 不能冲突——所以这里走 store 的写入接口的等价路径：用同 key 写
     * {@code message_ledger} 一行（手动，仅 ledgerId 与 idempotencyKey，供 offer 测试复用）。
     */
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

    // ===================================================================
    // 1. 准入：AE 入 / AR 排 / AA 排 / LANDED 排
    // ===================================================================

    @Test
    @DisplayName("AR 报文绝不进入重投队列（test_ar_message_not_entered_redelivery_set）")
    void ar_message_never_entered_queue() throws SQLException {
        IdempotencyKey k = key("AR-EXCLUDE-0001");
        long ledgerId = makeLedgerRow(k, "BATCH-AR");

        JdbcRedeliveryQueue q = queue();

        // offer(MACK_AE) — 入队
        var aeResult = q.offer(k, ledgerId, "BATCH-AR", "AE", MessageState.RECEIVED,
                RedeliveryQueue.EnqueueTrigger.MSA_AE, 0L);
        assertTrue(aeResult.isOffered(), "AE 应入队");

        // offer(AR) — 拒
        IdempotencyKey kAr = key("AR-EXCLUDE-0002");
        long lidAr = makeLedgerRow(kAr, "BATCH-AR");
        var arResult = q.offer(kAr, lidAr, "BATCH-AR", "AR", MessageState.RECEIVED,
                RedeliveryQueue.EnqueueTrigger.NO_ACK_NEXT_DAY, 0L);
        assertTrue(arResult.isRejected());
        assertTrue(arResult.rejectionReason().orElseThrow().contains("ack=AR"),
                "拒绝理由必须显式说明 ack=AR");

        // offer(AA) — 拒
        IdempotencyKey kAa = key("AR-EXCLUDE-0003");
        long lidAa = makeLedgerRow(kAa, "BATCH-AR");
        var aaResult = q.offer(kAa, lidAa, "BATCH-AR", "AA", MessageState.RECEIVED,
                RedeliveryQueue.EnqueueTrigger.MSA_AE, 0L);
        assertTrue(aaResult.isRejected());
        assertTrue(aaResult.rejectionReason().orElseThrow().contains("ack=AA"));

        // offer(LANDED 状态 + AE ack) — 拒（已对账完结是另一道闸）
        IdempotencyKey kLand = key("AR-EXCLUDE-0004");
        long lidLand = makeLedgerRow(kLand, "BATCH-AR");
        var landedResult = q.offer(kLand, lidLand, "BATCH-AR", "AE", MessageState.LANDED,
                RedeliveryQueue.EnqueueTrigger.MSA_AE, 0L);
        assertTrue(landedResult.isRejected());
        assertTrue(landedResult.rejectionReason().orElseThrow().contains("LANDED"));

        // 终态：队列里只有 1 条 AE，行 row.status=QUEUED、attempt=0、enqueue_reason=msa-ae
        assertEquals(1L, q.queuedCount());
        List<RedeliveryEntry> only = q.listByLedger(ledgerId);
        assertEquals(1, only.size());
        RedeliveryEntry e = only.get(0);
        assertEquals(RedeliveryStatus.QUEUED, e.status());
        assertEquals(0, e.attempt());
        assertEquals("msa-ae", e.enqueueReason());
    }

    // ===================================================================
    // 2. 同 key 不重（test_same_idempotency_key_not_duplicated_in_queue）
    // ===================================================================

    @Test
    @DisplayName("同一条幂等键的 offer 第二次返回 Duplicated，队列里仍只一条")
    void same_idempotency_key_not_duplicated() throws SQLException {
        IdempotencyKey k = key("DUP-0001");
        long ledgerId = makeLedgerRow(k, "BATCH-DUP");

        JdbcRedeliveryQueue q = queue();

        var first = q.offer(k, ledgerId, "BATCH-DUP", "AE", MessageState.RECEIVED,
                RedeliveryQueue.EnqueueTrigger.MSA_AE, 0L);
        assertTrue(first.isOffered(), "首次入队应成功");
        long firstId = first.offeredEntry().orElseThrow().id();

        var second = q.offer(k, ledgerId, "BATCH-DUP", "AE", MessageState.RECEIVED,
                RedeliveryQueue.EnqueueTrigger.MSA_AE, 0L);
        assertTrue(second.isDuplicated(), "重复 offer 必须返回 Duplicated，断言类型");
        assertEquals(firstId, second.existingEntry().orElseThrow().id(),
                "重复 offer 返回的 existing 行主键必须等于首次的 offered");

        // 同 key 的 NO_ACK_NEXT_DAY 入口也只给一条
        var third = q.offer(k, ledgerId, "BATCH-DUP", "AE", MessageState.PENDING_CONFIRM,
                RedeliveryQueue.EnqueueTrigger.NO_ACK_NEXT_DAY, 0L);
        assertTrue(third.isDuplicated());

        // 始终只 1 条
        assertEquals(1L, q.queuedCount());
        List<RedeliveryEntry> rows = q.listByLedger(ledgerId);
        assertEquals(1, rows.size());
        assertEquals(RedeliveryStatus.QUEUED, rows.get(0).status());
    }

    // ===================================================================
    // 3. 出队原子性（20 线程抢最早到期一条，只一条拿走）
    // ===================================================================

    @Test
    @DisplayName("出队原子性：20 线程抢最早到期的一条，只一条拿到，其余返回 empty")
    void claimNextDue_is_atomic_under_concurrency() throws Exception {
        IdempotencyKey k = key("ATOMIC-0001");
        long ledgerId = makeLedgerRow(k, "BATCH-ATOMIC");

        JdbcRedeliveryQueue q = queue();
        var offered = q.offer(k, ledgerId, "BATCH-ATOMIC", "AE", MessageState.RECEIVED,
                RedeliveryQueue.EnqueueTrigger.MSA_AE, 0L);
        assertTrue(offered.isOffered());
        long offeredId = offered.offeredEntry().orElseThrow().id();
        assertEquals(RedeliveryStatus.QUEUED,
                offered.offeredEntry().orElseThrow().status());

        int threads = 20;
        AtomicInteger winners = new AtomicInteger(0);
        AtomicInteger losers = new AtomicInteger(0);
        Set<Long> takenIds = new HashSet<>();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                Optional<RedeliveryEntry> r = q.claimNextDue(System.currentTimeMillis());
                if (r.isPresent()) {
                    winners.incrementAndGet();
                    synchronized (takenIds) {
                        takenIds.add(r.get().id());
                    }
                    return Boolean.TRUE;
                }
                losers.incrementAndGet();
                return Boolean.FALSE;
            }));
        }
        start.countDown();
        for (Future<Boolean> f : futures) {
            f.get(15, TimeUnit.SECONDS);
        }
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);

        assertEquals(1, winners.get(), "恰好 1 个线程拿到条目");
        assertEquals(threads - 1, losers.get(), "其余 19 个线程必须返回 empty");
        assertEquals(1, takenIds.size(), "拿到的行主键必须唯一");
        assertEquals(offeredId, takenIds.iterator().next(),
                "唯一一条 row 必须等于 offer 时返回的 id");

        // 出队后状态应变成 CONSUMED
        List<RedeliveryEntry> rows = q.listByLedger(ledgerId);
        assertEquals(1, rows.size());
        assertEquals(RedeliveryStatus.CONSUMED, rows.get(0).status());
        assertNotNull(rows.get(0).consumedAt());
        // 队列里没有 QUEUED 的了
        assertEquals(0L, q.queuedCount());
    }

    // ===================================================================
    // 4. 结算（settle）：CONSUMED → ACK_CONFIRMED / PERMANENT_FAILED
    // ===================================================================

    @Test
    @DisplayName("settle：CONSUMED → ACK_CONFIRMED 后 queuedCount=0，且行 terminal")
    void settle_ack_confirmed_moves_out_of_queue() throws SQLException {
        IdempotencyKey k = key("SETTLE-0001");
        long ledgerId = makeLedgerRow(k, "BATCH-SETTLE");

        JdbcRedeliveryQueue q = queue();
        var first = q.offer(k, ledgerId, "BATCH-SETTLE", "AE", MessageState.RECEIVED,
                RedeliveryQueue.EnqueueTrigger.MSA_AE, 0L);
        long queueId = first.offeredEntry().orElseThrow().id();

        Optional<RedeliveryEntry> claimed = q.claimNextDue(System.currentTimeMillis());
        assertTrue(claimed.isPresent());
        assertEquals(RedeliveryStatus.CONSUMED, claimed.get().status());

        q.settle(queueId, RedeliveryStatus.ACK_CONFIRMED, 1, "对端回 AA", System.currentTimeMillis());
        List<RedeliveryEntry> rows = q.listByLedger(ledgerId);
        assertEquals(1, rows.size());
        RedeliveryEntry e = rows.get(0);
        assertEquals(RedeliveryStatus.ACK_CONFIRMED, e.status());
        assertEquals(1, e.attempt());
        assertEquals("对端回 AA", e.lastError());
        assertEquals(0L, q.queuedCount());
        assertTrue(e.status().terminal());
    }

    @Test
    @DisplayName("settle 不允许推进到 QUEUED 或 CONSUMED（双闸）")
    void settle_rejects_invalid_target() throws SQLException {
        IdempotencyKey k = key("SETTLE-INVALID-0001");
        long ledgerId = makeLedgerRow(k, "BATCH-INVALID");

        JdbcRedeliveryQueue q = queue();
        var first = q.offer(k, ledgerId, "BATCH-INVALID", "AE", MessageState.RECEIVED,
                RedeliveryQueue.EnqueueTrigger.MSA_AE, 0L);
        long queueId = first.offeredEntry().orElseThrow().id();

        q.claimNextDue(System.currentTimeMillis());

        // settle 到 QUEUED 必须抛 IllegalArgumentException
        IllegalArgumentException qEx = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> q.settle(queueId, RedeliveryStatus.QUEUED, 1, "x", System.currentTimeMillis()));
        assertTrue(qEx.getMessage().contains("QUEUED"));

        // settle 到 CONSUMED 也必须抛
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> q.settle(queueId, RedeliveryStatus.CONSUMED, 1, "x", System.currentTimeMillis()));
    }

    // ===================================================================
    // 5. 重排（reschedule）：CONSUMED 上调 attempt 与 next_attempt_at_ms
    // ===================================================================

    @Test
    @DisplayName("reschedule 改 attempt & nextAttemptAtMs，但 status 不变；该行不出队列")
    void reschedule_updates_attempt_and_due_without_status_change() throws SQLException {
        IdempotencyKey k = key("RESCH-0001");
        long ledgerId = makeLedgerRow(k, "BATCH-RESCH");

        JdbcRedeliveryQueue q = queue();
        var first = q.offer(k, ledgerId, "BATCH-RESCH", "AE", MessageState.RECEIVED,
                RedeliveryQueue.EnqueueTrigger.MSA_AE, 0L);
        long queueId = first.offeredEntry().orElseThrow().id();

        q.claimNextDue(System.currentTimeMillis());
        // 让行处于 CONSUMED 状态

        long now = System.currentTimeMillis();
        long nextAt = now + 60_000L;
        q.reschedule(queueId, 1, nextAt);

        List<RedeliveryEntry> rows = q.listByLedger(ledgerId);
        RedeliveryEntry e = rows.get(0);
        assertEquals(RedeliveryStatus.CONSUMED, e.status(), "status 不变");
        assertEquals(1, e.attempt());
        assertEquals(nextAt, e.nextAttemptAtMs());
        // 由于 status=CONSUMED，queuedCount 仍为 0
        assertEquals(0L, q.queuedCount());
        // claimNextDue 不会拿到
        // 先 releaseToQueued 才能让 claimNextDue 拿到
    }

    // ===================================================================
    // 6. 释放回 QUEUED（人工放行）
    // ===================================================================

    @Test
    @DisplayName("PERMANENT_FAILED 可由 releaseToQueued 放回 QUEUED，再被 claimNextDue 拿到")
    void release_to_queued_then_due_again() throws SQLException {
        IdempotencyKey k = key("RELEASE-0001");
        long ledgerId = makeLedgerRow(k, "BATCH-RELEASE");

        JdbcRedeliveryQueue q = queue();
        var first = q.offer(k, ledgerId, "BATCH-RELEASE", "AE", MessageState.RECEIVED,
                RedeliveryQueue.EnqueueTrigger.MSA_AE, 0L);
        long queueId = first.offeredEntry().orElseThrow().id();
        long now = System.currentTimeMillis();

        q.claimNextDue(now);
        q.settle(queueId, RedeliveryStatus.PERMANENT_FAILED, 3, "达到 MAX_ATTEMPTS 上限", now);
        assertEquals(0L, q.queuedCount());

        long nextDue = now; // 立即
        q.releaseToQueued(queueId, nextDue);
        assertEquals(1L, q.queuedCount(), "放回后应在 QUEUED 集合中");

        Optional<RedeliveryEntry> taken = q.claimNextDue(nextDue);
        assertTrue(taken.isPresent());
        assertEquals(queueId, taken.get().id());
        assertEquals(RedeliveryStatus.CONSUMED, taken.get().status());
    }

    // ===================================================================
    // 7. enqueue_reason 区分四类入口，便于运维辨别
    // ===================================================================

    @Test
    @DisplayName("入队后 enqueue_reason 字段留下入口类型，便于排查")
    void enqueue_reason_is_preserved() throws SQLException {
        IdempotencyKey k1 = key("REASON-AE-0001");
        IdempotencyKey k2 = key("REASON-NEXT-0002");
        long lid1 = makeLedgerRow(k1, "BATCH-REASON");
        long lid2 = makeLedgerRow(k2, "BATCH-REASON");

        JdbcRedeliveryQueue q = queue();

        var ae = q.offer(k1, lid1, "BATCH-REASON", "AE", MessageState.RECEIVED,
                RedeliveryQueue.EnqueueTrigger.MSA_AE, 0L);
        assertEquals("msa-ae", ae.offeredEntry().orElseThrow().enqueueReason());

        var noAck = q.offer(k2, lid2, "BATCH-REASON", "AE", MessageState.PENDING_CONFIRM,
                RedeliveryQueue.EnqueueTrigger.NO_ACK_NEXT_DAY, 0L);
        assertEquals("pending-confirm-no-ack", noAck.offeredEntry().orElseThrow().enqueueReason());

        // ensure EnqueueTrigger.fromLabel 与 label 互逆
        assertSame(RedeliveryQueue.EnqueueTrigger.MSA_AE,
                RedeliveryQueue.EnqueueTrigger.fromLabel("msa-ae"));
        assertEquals("manual-release", RedeliveryQueue.EnqueueTrigger.MANUAL_RELEASE.label());
        assertEquals("recovery-scan", RedeliveryQueue.EnqueueTrigger.RECOVERY_SCAN.label());
    }

    // ===================================================================
    // 8. REDLIGHT：空键 / 非法 ID / 边界值校验
    // ===================================================================

    @Test
    @DisplayName("空幂等键 / ledgerId<=0 / nextAttemptAtMs<0 在 offer 前即被拒")
    void offer_input_validation_rejects_before_db() throws SQLException {
        JdbcRedeliveryQueue q = queue();

        try {
            q.offer(null, 1L, "BATCH", "AE", MessageState.RECEIVED,
                    RedeliveryQueue.EnqueueTrigger.MSA_AE, 0L);
            fail("null key 必须抛 NPE");
        } catch (NullPointerException expected) {
            assertNotNull(expected.getMessage());
        }

        IdempotencyKey k = key("VALIDATE-0001");
        try {
            q.offer(k, 0L, "BATCH", "AE", MessageState.RECEIVED,
                    RedeliveryQueue.EnqueueTrigger.MSA_AE, 0L);
            fail("ledgerId<=0 必须抛 IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("ledgerId"));
        }

        IdempotencyKey k2 = key("VALIDATE-0002");
        try {
            q.offer(k2, 1L, "BATCH", "AE", MessageState.RECEIVED,
                    RedeliveryQueue.EnqueueTrigger.MSA_AE, -1L);
            fail("nextAttemptAtMs<0 必须抛 IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("nextAttemptAtMs"));
        }
    }

    // ===================================================================
    // 9. 出队空集（队列为空或未到期）
    // ===================================================================

    @Test
    @DisplayName("claimNextDue 在空队列或全部未到期时返回 empty")
    void claim_returns_empty_when_no_due() throws SQLException {
        JdbcRedeliveryQueue q = queue();

        // 完全空
        Optional<RedeliveryEntry> first = q.claimNextDue(System.currentTimeMillis());
        assertFalse(first.isPresent());

        // 入队一行，但 nextAttemptAtMs 在未来
        IdempotencyKey k = key("EMPTY-0001");
        long ledgerId = makeLedgerRow(k, "BATCH-EMPTY");
        long future = System.currentTimeMillis() + 60_000L;
        q.offer(k, ledgerId, "BATCH-EMPTY", "AE", MessageState.RECEIVED,
                RedeliveryQueue.EnqueueTrigger.MSA_AE, future);

        Optional<RedeliveryEntry> due = q.claimNextDue(System.currentTimeMillis());
        assertFalse(due.isPresent(), "未到期不出队");

        // 时间推到未来
        Optional<RedeliveryEntry> nowDue = q.claimNextDue(future + 1L);
        assertTrue(nowDue.isPresent());
    }

    // ===================================================================
    // 10. offer 与 ledger 状态机的对齐：RECEIVED/AE → PENDING_RETRY 是允许的，
    // 但 REDLIGHT 要求入队先于状态推进，offer 不直接推 ledger 状态。
    // ===================================================================

    @Test
    @DisplayName("offer 不修改台账状态（offer 与 ledger transition 是两个独立事务）")
    void offer_does_not_change_ledger_state() throws SQLException {
        IdempotencyKey k = key("OFFER-NO-LEDGER-CHANGE-0001");
        long ledgerId = makeLedgerRow(k, "BATCH-OLC");

        // 入账前先看一眼原状态：应当是 RECEIVED（makeLedgerRow 写的）
        try (Connection conn = openConnection();
             var ps = conn.prepareStatement("SELECT state FROM message_ledger WHERE id = ?")) {
            ps.setLong(1, ledgerId);
            try (var rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("RECEIVED", rs.getString(1), "事先入账时由 makeLedgerRow 写入 RECEIVED");
            }
        }

        JdbcRedeliveryQueue q = queue();
        q.offer(k, ledgerId, "BATCH-OLC", "AE", MessageState.RECEIVED,
                RedeliveryQueue.EnqueueTrigger.MSA_AE, 0L);

        // offer 之后，message_ledger.state 仍应为 RECEIVED
        try (Connection conn = openConnection();
             var ps = conn.prepareStatement("SELECT state FROM message_ledger WHERE id = ?")) {
            ps.setLong(1, ledgerId);
            try (var rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("RECEIVED", rs.getString(1),
                        "offer() 不应触碰 ledger 状态——ledger 状态由 ArchiveIngestService.transition 推"
                                + " 进到 PENDING_RETRY，与 offer 是两条独立事务");
            }
        }
    }
}
