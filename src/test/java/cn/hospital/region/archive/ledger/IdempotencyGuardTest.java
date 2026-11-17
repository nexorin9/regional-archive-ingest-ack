package cn.hospital.region.archive.ledger;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T15 · {@link IdempotencyGuard} 的幂等键双保险 —— 「同一条报文无论到达多少次，
 * 业务处理只发生一次」这句话的可复核凭据。
 *
 * <h2>这一组在验什么</h2>
 * <ul>
 *   <li>同键先后两次 {@code tryAcquire}：第二次必须是 {@link AcquireResult.Duplicate}，
 *       台账<b>恒为 1 行</b>，且带的是<b>当前态</b>而非首次入账态；</li>
 *   <li>两层保险各自的命中来源可观测（{@code PRE_READ} vs {@code UNIQUE_CONSTRAINT}）；</li>
 *   <li>兜底层自己有效：换一个<b>不翻译 23505</b> 的 store，23505 冒到 guard 也会被翻译成
 *       Duplicate 而不是打断整批；</li>
 *   <li>并发窗口：20 线程同键 → 恰好 1 个 Acquired、19 个 Duplicate、台账 1 行；</li>
 *   <li>反向对照：20 条<b>不同</b>键并发 → 全部 Acquired、无丢失（防「去重写成把不同报文也吃掉」）。</li>
 * </ul>
 *
 * <p>与 T13/T14 一样<b>不 mock 数据库</b>：H2 PG 兼容模式 + 真实 JDBC + 真实唯一约束。
 * 「唯一约束到底拦不拦得住」只有真约束能回答。
 */
class IdempotencyGuardTest {

    private static final String H2_URL =
            "jdbc:h2:mem:idem_guard_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-10-20T03:00:00Z"), ZoneId.of("Asia/Shanghai"));

    private static final String SAMPLE_PAYLOAD = "MSH|^~\\&|CHSCOMMUNITY|REGIONAL_ARCHIVE|REGIONAL_ARCHIVE|FACILITY_A|"
            + "202610201000||ADT^A08|CTRL-T15|P|2.5.1|||AL|NE||UNICODE UTF-8\r"
            + "PID|1||PAT-0001^^^REGIONAL^MR||张^伟||19850512|M";

    private static int seq = 0;

    // =====================================================================
    // 夹具
    // =====================================================================

    private static Connection openConnection() throws SQLException {
        return DriverManager.getConnection(H2_URL, "sa", "");
    }

    private static JdbcLedgerStore store() {
        return new JdbcLedgerStore(IdempotencyGuardTest::openConnection, FIXED_CLOCK,
                AuditWriter.jdbc(FIXED_CLOCK));
    }

    private static LedgerEntry freshEntry() {
        int n = ++seq;
        return LedgerEntry.inbound(
                IdempotencyKey.of("ADT^A08", "CTRL-T15-" + n, "P"), "BATCH-T15-" + n, SAMPLE_PAYLOAD);
    }

    @BeforeAll
    static void migrateOnce() throws SQLException {
        try (Connection conn = openConnection()) {
            conn.setAutoCommit(false);
            LedgerSchema.migrate(conn);
            conn.commit();
        }
    }

    // =====================================================================
    // 1. task.json 点名的那条断言：同键两次 tryAcquire → 第二次 AlreadyProcessed，行数 1
    // =====================================================================

    @Test
    @DisplayName("同键先后两次 tryAcquire：第二次返回 Duplicate，台账行数恒为 1")
    void test_second_acquire_returns_already_processed_and_row_count_stays_one() throws Exception {
        JdbcLedgerStore store = store();
        IdempotencyGuard guard = new IdempotencyGuard(store);
        LedgerEntry entry = freshEntry();

        AcquireResult first = guard.tryAcquire(entry);
        assertTrue(first instanceof AcquireResult.Acquired, "首次必须是 Acquired");
        assertTrue(first.shouldRunBusiness(), "首次拿到的是新行，业务该跑");
        assertEquals(MessageState.RECEIVED, first.state());

        // 第二次：同一条报文对端重推。必须拿到 Duplicate —— 不是抛异常。
        // 主路径上抛异常意味着整批处理被打断，而重复到达只是常态。
        AcquireResult second = guard.tryAcquire(entry);
        assertTrue(second instanceof AcquireResult.Duplicate, "重复到达必须返回 Duplicate");
        assertFalse(second.shouldRunBusiness(), "重复到达绝不能再跑一次业务");
        assertEquals(first.ledgerId(), second.ledgerId(), "必须指回同一行，不能凭空多出一条");

        // 第三次：重启续跑（T35）也走同一条路，结论必须稳定
        AcquireResult third = guard.tryAcquire(entry);
        assertEquals(first.ledgerId(), third.ledgerId());

        assertEquals(1L, countLedgerRows(entry.key()), "三次 tryAcquire 只能留下一行");
        // 重复到达不产生审计行：审计只记状态迁移，不记「又收到一遍」
        assertEquals(0L, countAuditRows(first.ledgerId()));
    }

    @Test
    @DisplayName("重复到达带的是台账当前态而非首次入账态（否则同一条报文会回出两种结果）")
    void test_duplicate_reports_current_state_not_initial_state() throws Exception {
        JdbcLedgerStore store = store();
        IdempotencyGuard guard = new IdempotencyGuard(store);
        LedgerEntry entry = freshEntry();

        long id = guard.tryAcquire(entry).ledgerId();
        store.transition(id, MessageState.RECEIVED, MessageState.PENDING_CONFIRM, "system", "candidates-ready");

        AcquireResult again = guard.tryAcquire(entry);
        assertEquals(MessageState.PENDING_CONFIRM, again.state(),
                "回执必须按当前态给；报 RECEIVED 会让对账时同一条报文出现两种结果");
        assertEquals(1L, countAuditRows(id), "重复到达不新增审计行");
        assertEquals(1L, countLedgerRows(entry.key()));
    }

    // =====================================================================
    // 2. 两层保险的来源可观测
    // =====================================================================

    @Test
    @DisplayName("常态重复命中走先查层（不开事务），不撞唯一约束")
    void test_normal_duplicate_is_caught_by_pre_read() throws Exception {
        JdbcLedgerStore store = store();
        IdempotencyGuard guard = new IdempotencyGuard(store);
        LedgerEntry entry = freshEntry();

        guard.tryAcquire(entry);
        AcquireResult dup = guard.tryAcquire(entry);

        assertEquals(AcquireResult.DuplicateOrigin.PRE_READ, ((AcquireResult.Duplicate) dup).origin());
        assertEquals(0, guard.backstopHitCount(), "先查已命中的重复不该动用唯一约束兜底");
    }

    @Test
    @DisplayName("兜底层自己有效：store 不翻译 23505 时，guard 仍返回 Duplicate 而不是抛栈")
    void test_backstop_translates_raw_unique_violation() throws Exception {
        JdbcLedgerStore real = store();
        // 一个「忘了翻译唯一约束」的 store：把 JdbcLedgerStore 的翻译绕开，直接让 23505 上抛。
        // 这是 guard 第二层保险存在的理由 —— 它不能依赖每个 LedgerStore 实现都记得翻译。
        LedgerStore rawThrowingStore = new DelegatingStore(real) {
            @Override
            public InsertOutcome insertIfAbsent(LedgerEntry entry) throws SQLException {
                if (real.findByIdempotencyKey(entry.key()).isPresent()) {
                    // 模拟「实现没做 23505 → AlreadyProcessed 翻译」：直接裸插，让约束把 SQLException 抛上来
                    try (Connection conn = openConnection()) {
                        conn.setAutoCommit(false);
                        try (PreparedStatement ps = conn.prepareStatement(
                                "INSERT INTO message_ledger (message_type, control_id, processing_id, "
                                        + "batch_id, state, raw_payload, local_mrn, degraded, retry_count, "
                                        + "created_at, updated_at, version) "
                                        + "VALUES (?, ?, ?, ?, 'RECEIVED', ?, '', FALSE, 0, "
                                        + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)")) {
                            ps.setString(1, entry.key().messageType());
                            ps.setString(2, entry.key().controlId());
                            ps.setString(3, entry.key().processingId());
                            ps.setString(4, entry.batchId());
                            ps.setString(5, entry.rawPayload());
                            ps.executeUpdate();
                        }
                        conn.commit();
                    }
                    return real.insertIfAbsent(entry);
                }
                return real.insertIfAbsent(entry);
            }
        };

        IdempotencyGuard guard = new IdempotencyGuard(rawThrowingStore);
        LedgerEntry entry = freshEntry();

        AcquireResult first = guard.tryAcquire(entry);
        assertTrue(first instanceof AcquireResult.Acquired);

        // 第二次：guard 的先查会先命中……为了让兜底层被真正走到，先查必须失效。
        // 直接构造「先查查不到但行已存在」的等价情形：让 store 的 findByIdempotencyKey 第一次返回空。
        LedgerStore flakyLookup = new DelegatingStore(rawThrowingStore) {
            private final AtomicInteger lookups = new AtomicInteger();

            @Override
            public java.util.Optional<LedgerRow> findByIdempotencyKey(IdempotencyKey key)
                    throws SQLException {
                // 只在第二次调用时伪造「先查没查到」，模拟并发窗口 / 隔离级别下的快照读
                if (lookups.incrementAndGet() == 2) {
                    return java.util.Optional.empty();
                }
                return rawThrowingStore.findByIdempotencyKey(key);
            }
        };
        IdempotencyGuard flakyGuard = new IdempotencyGuard(flakyLookup);
        flakyGuard.tryAcquire(entry);          // 第 1 次查（命中）+ 插入
        AcquireResult dup = flakyGuard.tryAcquire(entry);   // 第 2 次查（伪造空）→ 走到插入

        assertTrue(dup instanceof AcquireResult.Duplicate,
                "23505 冒到 guard 时必须被翻译成 Duplicate，不得打断整批处理");
        assertEquals(AcquireResult.DuplicateOrigin.UNIQUE_CONSTRAINT,
                ((AcquireResult.Duplicate) dup).origin(), "必须标记为唯一约束兜底命中");
        assertEquals(first.ledgerId(), dup.ledgerId(), "兜底必须指回原行");
        assertEquals(1L, countLedgerRows(entry.key()), "兜底命中不得留下第二行");
    }

    @Test
    @DisplayName("兜底命中计数可观测，且正常不刷 INFO（每 50 条才汇总一次）")
    void test_backstop_hit_count_is_observable() throws Exception {
        JdbcLedgerStore real = store();
        IdempotencyGuard guard = new IdempotencyGuard(real);

        // 直接调用一次兜底路径：用一个「先查永远查不到」的 store 逼出唯一约束兜底
        LedgerStore neverFinds = new DelegatingStore(real) {
            @Override
            public java.util.Optional<LedgerRow> findByIdempotencyKey(IdempotencyKey key) {
                return java.util.Optional.empty();
            }
        };
        IdempotencyGuard neverFinding = new IdempotencyGuard(neverFinds);
        LedgerEntry entry = freshEntry();
        AcquireResult first = neverFinding.tryAcquire(entry);
        AcquireResult second = neverFinding.tryAcquire(entry);

        assertTrue(second instanceof AcquireResult.Duplicate);
        assertEquals(1, neverFinding.backstopHitCount(), "兜底命中 1 次应被计数");
        assertEquals(0, guard.backstopHitCount(), "普通 guard 未走兜底，计数应恒为 0");
        assertTrue(first.ledgerId() > 0, "首次仍必须真的入账并拿到主键");
    }

    // =====================================================================
    // 3. 并发窗口：唯一约束兜底的真实用武之地
    // =====================================================================

    @Test
    @DisplayName("20 线程同键并发：恰好 1 个 Acquired，台账恒 1 行，无丢失无重复")
    void test_20_threads_same_key_exactly_one_acquired() throws Exception {
        JdbcLedgerStore store = store();
        IdempotencyGuard guard = new IdempotencyGuard(store);
        LedgerEntry entry = freshEntry();

        int threads = 20;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ConcurrentLinkedQueue<AcquireResult> results = new ConcurrentLinkedQueue<>();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        results.add(guard.tryAcquire(entry));
                    } catch (Exception e) {
                        results.add(null);
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(60, TimeUnit.SECONDS), "20 线程应在 60s 内跑完");
        } finally {
            pool.shutdownNow();
        }

        List<AcquireResult> list = new ArrayList<>(results);
        assertEquals(threads, list.size());
        int acquired = 0;
        int duplicate = 0;
        Set<Long> ids = new HashSet<>();
        for (AcquireResult r : list) {
            assertNotNull(r, "任何线程都不该拿到失败：重复到达不是错误");
            ids.add(r.ledgerId());
            if (r instanceof AcquireResult.Acquired) {
                acquired++;
            } else {
                duplicate++;
                assertFalse(r.shouldRunBusiness(), "重复到达不得再跑业务");
            }
        }

        assertEquals(1, acquired, "20 个线程抢同一个幂等键，只能有一个真正入账");
        assertEquals(threads - 1, duplicate);
        assertEquals(1, ids.size(), "所有结果必须指向同一行");
        assertEquals(1L, countLedgerRows(entry.key()), "台账只能有一行");
    }

    @Test
    @DisplayName("反向对照：20 条不同幂等键并发全部 Acquired（去重没有把不同报文也吃掉）")
    void test_20_distinct_keys_no_loss() throws Exception {
        JdbcLedgerStore store = store();
        IdempotencyGuard guard = new IdempotencyGuard(store);

        int threads = 20;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ConcurrentLinkedQueue<AcquireResult> results = new ConcurrentLinkedQueue<>();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                LedgerEntry entry = freshEntry();
                pool.submit(() -> {
                    try {
                        start.await();
                        results.add(guard.tryAcquire(entry));
                    } catch (Exception e) {
                        results.add(null);
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(60, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        int acquired = 0;
        for (AcquireResult r : results) {
            assertNotNull(r, "不同键并发不应有任何失败");
            if (r instanceof AcquireResult.Acquired) {
                acquired++;
            }
        }
        assertEquals(threads, acquired, "不同幂等键必须全部首次入账，一条都不能被当成重复吃掉");
        assertTrue(countLedgerRowsInBatchPrefix("BATCH-T15-") >= threads,
                "不同键必须各占一行，不能被唯一约束误判成同一批");
    }

    // =====================================================================
    // 4. 失败不静默：台账不可用时 guard 必须上抛
    // =====================================================================

    @Test
    @DisplayName("台账不可用时 guard 上抛 SQLException，不把失败伪装成「已处理」")
    void test_store_failure_propagates_instead_of_fake_duplicate() throws Exception {
        JdbcLedgerStore store = store();
        LedgerStore broken = new DelegatingStore(store) {
            @Override
            public InsertOutcome insertIfAbsent(LedgerEntry entry) throws SQLException {
                throw new SQLException("模拟台账不可用（连接池耗尽 / 数据库重启）");
            }

            @Override
            public java.util.Optional<LedgerRow> findByIdempotencyKey(IdempotencyKey key)
                    throws SQLException {
                return java.util.Optional.empty();
            }
        };

        IdempotencyGuard guard = new IdempotencyGuard(broken);
        SQLException e = assertThrows(SQLException.class, () -> guard.tryAcquire(freshEntry()));
        assertTrue(e.getMessage().contains("台账不可用"));
    }

    // =====================================================================
    // 辅助
    // =====================================================================

    /** 委托给真实 store 的壳：只为在测试里替换单个方法，生产代码没有这个类。 */
    private static class DelegatingStore implements LedgerStore {
        private final LedgerStore delegate;

        DelegatingStore(LedgerStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public InsertOutcome insertIfAbsent(LedgerEntry entry) throws SQLException {
            return delegate.insertIfAbsent(entry);
        }

        @Override
        public java.util.Optional<LedgerRow> findByIdempotencyKey(IdempotencyKey key)
                throws SQLException {
            return delegate.findByIdempotencyKey(key);
        }

        @Override
        public java.util.Optional<LedgerRow> findById(long id) throws SQLException {
            return delegate.findById(id);
        }

        @Override
        public TransitionResult transition(long id, MessageState from, MessageState to,
                                           String operator, String reason)
                throws SQLException, IllegalStateTransitionException, LedgerStateConflictException {
            return delegate.transition(id, from, to, operator, reason);
        }

        @Override
        public TransitionResult claimLanding(long id, long expectedVersion, String operator,
                                             String reason, String localMrn)
                throws SQLException, IllegalStateTransitionException, LedgerStateConflictException,
                ConcurrentLedgerUpdateException {
            return delegate.claimLanding(id, expectedVersion, operator, reason, localMrn);
        }


        @Override
        public TransitionResult releaseLanding(long id, long expectedVersion, String operator,
                                               String reason)
                throws SQLException, IllegalStateTransitionException, LedgerStateConflictException,
                ConcurrentLedgerUpdateException {
            return delegate.releaseLanding(id, expectedVersion, operator, reason);
        }
        @Override
        public TransitionResult transition(long id, MessageState from, MessageState to,
                                           long expectedVersion, String operator, String reason)
                throws SQLException, IllegalStateTransitionException, LedgerStateConflictException,
                ConcurrentLedgerUpdateException {
            return delegate.transition(id, from, to, expectedVersion, operator, reason);
        }

        @Override
        public List<LedgerRow> listByBatch(String batchId, MessageState state) throws SQLException {
            return delegate.listByBatch(batchId, state);
        }

        @Override
        public java.util.Map<MessageState, Long> countByState(String batchId) throws SQLException {
            return delegate.countByState(batchId);
        }

        @Override
        public List<LedgerRow> findByStates(java.util.Set<MessageState> states) throws SQLException {
            return delegate.findByStates(states);
        }
    }

    private static long countLedgerRows(IdempotencyKey key) throws SQLException {
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM message_ledger "
                             + "WHERE message_type = ? AND control_id = ? AND processing_id = ?")) {
            ps.setString(1, key.messageType());
            ps.setString(2, key.controlId());
            ps.setString(3, key.processingId());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static long countLedgerRowsInBatchPrefix(String prefix) throws SQLException {
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM message_ledger WHERE batch_id LIKE ?")) {
            ps.setString(1, prefix + "%");
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static long countAuditRows(long ledgerId) throws SQLException {
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM message_audit WHERE ledger_id = ?")) {
            ps.setLong(1, ledgerId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }
}