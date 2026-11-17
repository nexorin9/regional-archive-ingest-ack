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
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T15 · 乐观锁 —— 「同一份 version 只有一个人能提交，另一个抛
 * {@link ConcurrentLedgerUpdateException}」这句话的可复核凭据。
 *
 * <h2>这一组在验什么</h2>
 * <ul>
 *   <li>两个持有<b>同一份</b> version 的调用方，只有第一个 transition 成功，
 *       第二个抛并发异常且异常里带得上<b>期望</b>与<b>实际</b>两个版本号；</li>
 *   <li>失败的并发迁移<b>一行都不能多写</b>：状态、version、审计行数全部不变
 *       （"失败不静默改数据"在并发路径上的口径）；</li>
 *   <li>重试约定可执行：{@link LedgerRetryPolicy} 重读后重试能收敛；
 *       一直撞则上限 3 次后转 {@link LedgerConcurrencyExhaustedException}（人工可见），不静默吞；</li>
 *   <li>并发异常与来源态冲突异常<b>分得开</b>：两者处置不同，混在一起就会用过期状态覆盖写入；</li>
 *   <li>真并发：N 线程同时提交同一行，成功的恰好 1 次、审计行数与实际迁移数一致。</li>
 * </ul>
 *
 * <p>不 mock 数据库：H2 PG 兼容模式 + 真实 JDBC + 真实 CAS。乐观锁成立与否只有真 UPDATE 能回答。
 */
class OptimisticLockTest {

    private static final String H2_URL =
            "jdbc:h2:mem:optimistic_lock_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-10-20T04:00:00Z"), ZoneId.of("Asia/Shanghai"));

    private static final String SAMPLE_PAYLOAD = "MSH|^~\\&|CHSCOMMUNITY|REGIONAL_ARCHIVE|REGIONAL_ARCHIVE|FACILITY_A|"
            + "202610201000||ADT^A08|CTRL-T15-OPT|P|2.5.1|||AL|NE||UNICODE UTF-8\r"
            + "PID|1||PAT-0001^^^REGIONAL^MR||张^伟||19850512|M";

    private static int seq = 0;

    // =====================================================================
    // 夹具
    // =====================================================================

    private static Connection openConnection() throws SQLException {
        return DriverManager.getConnection(H2_URL, "sa", "");
    }

    private static JdbcLedgerStore store() {
        return new JdbcLedgerStore(OptimisticLockTest::openConnection, FIXED_CLOCK,
                AuditWriter.jdbc(FIXED_CLOCK));
    }

    private static LedgerEntry freshEntry() {
        int n = ++seq;
        return LedgerEntry.inbound(IdempotencyKey.of("ADT^A08", "CTRL-T15-OPT-" + n, "P"),
                "BATCH-T15-OPT-" + n, SAMPLE_PAYLOAD);
    }

    @BeforeAll
    static void migrateOnce() throws SQLException {
        try (Connection conn = openConnection()) {
            conn.setAutoCommit(false);
            LedgerSchema.migrate(conn);
            conn.commit();
        }
    }

    /** 入一条报文并返回它的台账行（含 version=0）。 */
    private static LedgerRow insertOne(JdbcLedgerStore store) throws Exception {
        LedgerEntry entry = freshEntry();
        long id = store.insertIfAbsent(entry).ledgerId();
        return store.findById(id).orElseThrow();
    }

    // =====================================================================
    // 1. 两个持有同一 version 的调用方，只有一个成功
    // =====================================================================

    @Test
    @DisplayName("两个持有同一 version 的对象只有一个 transition 成功，另一个抛并发异常")
    void test_two_holders_of_same_version_only_one_succeeds() throws Exception {
        JdbcLedgerStore store = store();
        LedgerRow row = insertOne(store);

        // 两个调用方在「读」这一瞬间看到的是同一份状态：RECEIVED / version=0
        long id = row.id();
        MessageState fromA = row.state();
        long versionA = row.version();
        MessageState fromB = row.state();
        long versionB = row.version();
        assertEquals(versionA, versionB, "前提：两个调用方读到同一份 version");

        // A 先提交
        TransitionResult ok = store.transition(id, fromA, MessageState.PENDING_CONFIRM,
                versionA, "system", "identity-candidates-ready");
        assertEquals(MessageState.PENDING_CONFIRM, ok.to());
        assertEquals(versionA + 1, ok.newVersion(), "成功后 version 恰好自增 1");

        // B 拿着过期的那份 version 提交 → 存储层拒绝，不覆盖 A 的写入
        ConcurrentLedgerUpdateException e = assertThrows(ConcurrentLedgerUpdateException.class,
                () -> store.transition(id, fromB, MessageState.PENDING_RETRY,
                        versionB, "system", "redelivery-scheduled"));

        // task.json 点名的那条断言：异常携带期望 version 与实际 version
        assertEquals(versionB, e.expectedVersion(), "异常必须带上调用方期望的 version");
        assertEquals(versionA + 1, e.actualVersion(), "异常必须带上的库中实际 version");
        assertTrue(e.actualVersion() > e.expectedVersion(), "实际版本必须大于期望版本");
        assertEquals(MessageState.PENDING_CONFIRM, e.actualState(), "异常必须带来源态的真实值");
        assertTrue(e.rowExists());
        assertTrue(e.getMessage().contains("ledgerId=" + id), "异常消息须可直接定位台账行");
        assertTrue(e.getMessage().contains("须重读"), "异常消息须写明处置约定：重读后重试");
    }

    @Test
    @DisplayName("并发失败的那次迁移一行都不能多写（状态/version/审计均不变）")
    void test_failed_concurrent_transition_leaves_row_and_audit_unchanged() throws Exception {
        JdbcLedgerStore store = store();
        LedgerRow row = insertOne(store);
        long id = row.id();

        store.transition(id, MessageState.RECEIVED, MessageState.PENDING_CONFIRM,
                row.version(), "system", "candidates-ready");

        LedgerRow before = store.findById(id).orElseThrow();
        long auditsBefore = countAuditRows(id);

        assertThrows(ConcurrentLedgerUpdateException.class,
                () -> store.transition(id, MessageState.RECEIVED, MessageState.PENDING_CONFIRM,
                        before.version() - 1, "operator-001", "manual-confirm"));

        LedgerRow after = store.findById(id).orElseThrow();
        assertEquals(before.state(), after.state(), "失败的并发迁移不得改变状态");
        assertEquals(before.version(), after.version(), "失败的并发迁移不得推进 version");
        assertEquals(auditsBefore, countAuditRows(id), "失败的并发迁移不得留下审计行");
        assertTrue(LedgerAuditChain.inspect(oneShotConnection(), id).intact(),
                "审计链必须仍然完整（失败路径没留下半截数据）");
    }

    @Test
    @DisplayName("并发异常与来源态冲突异常分得开：一个 version 变了，一个 state 变了")
    void test_concurrent_update_and_state_conflict_are_distinguishable() throws Exception {
        JdbcLedgerStore store = store();
        LedgerRow row = insertOne(store);
        long id = row.id();

        // 先把行推到 PENDING_CONFIRM
        store.transition(id, MessageState.RECEIVED, MessageState.PENDING_CONFIRM,
                row.version(), "system", "candidates-ready");
        LedgerRow current = store.findById(id).orElseThrow();

        // 拿着过期的 from（RECEIVED）但正确的 version → 状态冲突（先被 state 守卫拦下）
        LedgerStateConflictException stateConflict = assertThrows(LedgerStateConflictException.class,
                () -> store.transition(id, MessageState.RECEIVED, MessageState.LANDED,
                        current.version(), "operator-001", "manual-confirm"));
        assertEquals(MessageState.PENDING_CONFIRM, stateConflict.actualState());
        assertEquals(MessageState.RECEIVED, stateConflict.claimedFrom());

        // 拿着正确的 from 但过期的 version → 并发冲突（被 version 守卫拦下）
        ConcurrentLedgerUpdateException concurrent = assertThrows(ConcurrentLedgerUpdateException.class,
                () -> store.transition(id, MessageState.PENDING_CONFIRM, MessageState.LANDED,
                        current.version() - 1, "operator-001", "manual-confirm"));
        assertEquals(current.version() - 1, concurrent.expectedVersion());
        assertEquals(current.version(), concurrent.actualVersion());
        assertEquals(MessageState.PENDING_CONFIRM, concurrent.actualState());

        // 行没被动过：两次拒绝都没写任何东西
        LedgerRow after = store.findById(id).orElseThrow();
        assertEquals(current.state(), after.state());
        assertEquals(current.version(), after.version());
    }

    // =====================================================================
    // 2. 重试约定：重读后重试，上限 3 次
    // =====================================================================

    @Test
    @DisplayName("LedgerRetryPolicy：重读后重试能收敛，不必人工介入")
    void test_retry_policy_converges_after_reread() throws Exception {
        JdbcLedgerStore store = store();
        LedgerRow row = insertOne(store);
        long id = row.id();

        // 制造一次争用：另一个线程先把 version 推掉
        store.transition(id, MessageState.RECEIVED, MessageState.PENDING_CONFIRM,
                row.version(), "system", "candidates-ready");

        LedgerRetryPolicy policy = new LedgerRetryPolicy();
        assertEquals(3, policy.maxAttempts(), "重试上限规范里写死为 3");

        // 调用方拿着过期版本发起迁移 → policy 重读 → 拿到 PENDING_CONFIRM 的真实状态 → 一次成功
        TransitionResult result = policy.transitionTo(store, id, MessageState.LANDED,
                "operator-001", "manual-confirm");

        assertEquals(MessageState.LANDED, result.to());
        assertEquals(MessageState.PENDING_CONFIRM, result.from());
        assertEquals(1L, result.previousVersion(), "重读后拿到的是别人推过的那个版本");
        assertEquals(2L, result.newVersion());
        assertEquals("operator-001", result.operator());
    }

    @Test
    @DisplayName("重试用尽后转人工可见错误（LedgerConcurrencyExhaustedException），不静默吞掉")
    void test_retry_policy_exhausted_raises_human_visible_error() throws Exception {
        JdbcLedgerStore store = store();
        LedgerRow row = insertOne(store);
        long id = row.id();

        // 「持续争用」替身：每次尝试都输给别人，重读重试在这里永远收敛不了 ——
        // 正好用来验「用尽上限后停下来让人看」而不是继续空转。
        LedgerStore alwaysRacing = new RacingStore(store);

        LedgerRetryPolicy policy = new LedgerRetryPolicy();
        LedgerConcurrencyExhaustedException e = assertThrows(LedgerConcurrencyExhaustedException.class,
                () -> policy.transitionTo(alwaysRacing, id, MessageState.PENDING_CONFIRM,
                        "system", "candidates-ready"));

        assertEquals(3, e.attempts(), "必须正好用满上限 3 次");
        assertEquals(id, e.ledgerId());
        assertTrue(e.lastActualVersion() > e.lastExpectedVersion(),
                "异常须带上最后一次的期望/实际版本差异，供排障定位");
        assertTrue(e.getMessage().contains("人工"), "错误必须是人工可见的，不能只写「失败」");
        assertTrue(e.getMessage().contains("不推进业务状态"), "错误须写明本条没有推进业务状态");

        // 用尽上限 ≠ 状态被改坏：这一行还停在原地，等人来看
        LedgerRow after = store.findById(id).orElseThrow();
        assertEquals(MessageState.RECEIVED, after.state(), "重试用尽不得擅自推进业务状态");
    }

    @Test
    @DisplayName("LedgerRetryPolicy：非法迁移原样上抛，不混进「重读再试」")
    void test_retry_policy_does_not_swallow_illegal_transition() throws Exception {
        JdbcLedgerStore store = store();
        LedgerRow row = insertOne(store);
        long id = row.id();
        store.transition(id, MessageState.RECEIVED, MessageState.PENDING_CONFIRM,
                row.version(), "system", "candidates-ready");

        LedgerRetryPolicy policy = new LedgerRetryPolicy();
        // 待确认 → 拒收 是非法边（拒收只发生在入站阶段的硬校验，见 MessageStateMachine）。
        // 重读多少次都一样，本就该立刻失败，而不是重试到上限后报「并发冲突用尽」——
        // 那会把排障的人引向并发，而病根是一次非法边。
        assertThrows(IllegalStateTransitionException.class,
                () -> policy.transitionTo(store, id, MessageState.REJECTED,
                        "operator-001", "manual"));

        LedgerRow after = store.findById(id).orElseThrow();
        assertEquals(MessageState.PENDING_CONFIRM, after.state(), "非法迁移后状态必须原样不动");
        assertEquals(1L, after.version(), "非法迁移不得推进 version");
        assertEquals(1L, countAuditRows(id), "非法迁移不得留下审计行");
    }

    @Test
    @DisplayName("LedgerRetryPolicy：台账行不存在时直接报人工可见错误，不空转满上限")
    void test_retry_policy_missing_row_fails_fast() throws Exception {
        JdbcLedgerStore store = store();
        LedgerRetryPolicy policy = new LedgerRetryPolicy();

        LedgerConcurrencyExhaustedException e = assertThrows(LedgerConcurrencyExhaustedException.class,
                () -> policy.transitionTo(store, 999_999L, MessageState.PENDING_CONFIRM,
                        "system", "candidates-ready"));
        assertEquals(0, e.attempts(), "行不存在时不该重试，一次就报");
    }

    // =====================================================================
    // 3. 真并发：N 线程同时提交同一行
    // =====================================================================

    @Test
    @DisplayName("N 线程同时提交同一行：成功恰好 1 次，审计行数与实际迁移数一致")
    void test_concurrent_submissions_only_one_wins() throws Exception {
        JdbcLedgerStore store = store();
        LedgerRow row = insertOne(store);
        long id = row.id();
        long version = row.version();

        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        AtomicInteger otherErrors = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        // 每个线程都拿到同一份 version —— 乐观锁要挡的正是这个局面
                        store.transition(id, MessageState.RECEIVED, MessageState.PENDING_CONFIRM,
                                version, "system", "candidates-ready");
                        succeeded.incrementAndGet();
                    } catch (ConcurrentLedgerUpdateException | LedgerStateConflictException e) {
                        // 两种拒绝都算「没写进去」：先撞上的是版本守卫（version 已变），
                        // 后撞上的是来源态守卫（state 已被改成 PENDING_CONFIRM）。
                        // 并发跑批时它们按到达顺序随机出现，混为一类断言才是真实口径。
                        rejected.incrementAndGet();
                    } catch (Exception e) {
                        otherErrors.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(60, TimeUnit.SECONDS), threads + " 线程应在 60s 内跑完");
        } finally {
            pool.shutdownNow();
        }

        assertEquals(0, otherErrors.get(), "除了并发冲突与来源态冲突，不该有别的错误");
        assertEquals(1, succeeded.get(), "同一份 version 只能有一次提交成功");
        assertEquals(threads - 1, rejected.get(), "其余全部应被存储层的守卫拒绝，不得双写");

        // 红线：并发失败路径不得产生额外写入，也不得留下 state 与 audit 不一致的台账行
        LedgerRow after = store.findById(id).orElseThrow();
        assertEquals(MessageState.PENDING_CONFIRM, after.state());
        assertEquals(1L, after.version(), "只成功迁移了一次，version 只能是 1");
        assertEquals(1L, countAuditRows(id), "审计行数必须与实际迁移数一致（都是 1）");
        assertTrue(LedgerAuditChain.inspect(oneShotConnection(), id).intact(),
                "并发跑完后审计链必须仍然完整");
    }

    @Test
    @DisplayName("不带版本守卫的旧重载仍然可用（单线程路径），且不会假装有乐观锁")
    void test_transition_without_version_guard_still_works() throws Exception {
        JdbcLedgerStore store = store();
        LedgerRow row = insertOne(store);
        long id = row.id();

        TransitionResult result = store.transition(id, MessageState.RECEIVED,
                MessageState.PENDING_CONFIRM, "system", "candidates-ready");

        assertEquals(MessageState.PENDING_CONFIRM, result.to());
        assertEquals(0L, result.previousVersion());
        assertEquals(1L, result.newVersion());
        assertEquals(1L, countAuditRows(id));

        // 过期来源态仍会被 state 守卫拦下（这正是两个重载共有的那道防线）
        assertThrows(LedgerStateConflictException.class,
                () -> store.transition(id, MessageState.RECEIVED, MessageState.PENDING_CONFIRM,
                        "system", "again"));
        LedgerRow after = store.findById(id).orElseThrow();
        assertEquals(MessageState.PENDING_CONFIRM, after.state());
        assertEquals(result.newVersion(), after.version(), "被拒的迁移不得推进 version");
    }

    // =====================================================================
    // 辅助
    // =====================================================================

    /** 每次都输掉竞争的「持续争用」替身；只在测试里存在，且不触碰数据库。 */
    private static final class RacingStore implements LedgerStore {
        private final LedgerStore delegate;

        RacingStore(LedgerStore delegate) {
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
                throws SQLException, ConcurrentLedgerUpdateException {
            // 每次尝试都输给「别人」：重读回来的 version 一提交就又过期了。
            // 这是「持续争用」的样子，不是「一次抖动」—— 重读重试在这里永远收敛不了。
            // 只抛异常、不写库：被拒的路径本来就一行都不能多写。
            throw new ConcurrentLedgerUpdateException(id, expectedVersion, expectedVersion + 1, from);
        }

        @Override
        public java.util.List<LedgerRow> listByBatch(String batchId, MessageState state)
                throws SQLException {
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

    private static Connection oneShotConnection() throws SQLException {
        return openConnection();
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