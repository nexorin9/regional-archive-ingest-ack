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
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T13 存储层验收 —— 状态机与审计的原子性、幂等插入、以及「绕过状态机」的三重拦截。
 *
 * <p><b>不 mock 数据库</b>：每个用例类只建一个 H2 PG 兼容模式的内存库，
 * 迁移跑的是与生产同一份 {@code V1__schema.sql}，所有写入走真实 JDBC 与真实事务。
 * 「事务中途失败 → 整体回滚」这类断言只有真事务能验；用 mock 库把它 mock 掉等于没测。
 *
 * <p>连接策略：{@code DB_CLOSE_DELAY=-1} 让内存库在所有连接关闭后仍然存活，
 * 因此 store 每次调用开一条新连接、结束时关掉（生产语义），用例之间也不互相污染状态。
 */
class JdbcLedgerStoreTest {

    private static final String H2_URL =
            "jdbc:h2:mem:ledger_store_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

    /** 固定时钟：让 created_at / updated_at / audit.at 可复现，同毫秒并列时由 audit.id 定序。 */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-10-20T01:00:00Z"), ZoneId.of("Asia/Shanghai"));

    private static final String SAMPLE_PAYLOAD = "MSH|^~\\&|CHSCOMMUNITY|REGIONAL_ARCHIVE|REGIONAL_ARCHIVE|FACILITY_A|"
            + "202610200900||ADT^A08|CTRL-0001|P|2.5.1|||AL|NE||UNICODE UTF-8\r"
            + "PID|1||PAT-0001^^^REGIONAL^MR||张^伟||19850512|M";

    private static int batchSeq = 0;

    private static JdbcLedgerStore store() {
        return new JdbcLedgerStore(JdbcLedgerStoreTest::openConnection, FIXED_CLOCK,
                AuditWriter.jdbc(FIXED_CLOCK));
    }

    private static Connection openConnection() throws SQLException {
        return DriverManager.getConnection(H2_URL, "sa", "");
    }

    /** 每个用例一条独立台账行：批次号与控制号都带自增序号，互不撞唯一约束。 */
    private static LedgerEntry freshEntry() {
        int n = ++batchSeq;
        return LedgerEntry.inbound(
                IdempotencyKey.of("ADT^A08", "CTRL-T13-" + n, "P"), "BATCH-T13-" + n, SAMPLE_PAYLOAD);
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
    // 1. 状态迁移：一次一条审计行
    // =====================================================================

    @Test
    @DisplayName("一条报文 insert → 已接收→待确认 → 待确认→已落地，审计恰好 2 行且 from/to 正确")
    void test_transition_writes_one_audit_row_per_move() throws Exception {
        JdbcLedgerStore store = store();
        LedgerEntry entry = freshEntry();
        long ledgerId = store.insertIfAbsent(entry).ledgerId();

        assertEquals(MessageState.RECEIVED, store.stateOf(ledgerId).orElseThrow());

        TransitionResult first = store.transition(ledgerId, MessageState.RECEIVED,
                MessageState.PENDING_CONFIRM, "system", "identity-candidates-ready");
        assertEquals(0L, first.previousVersion());
        assertEquals(1L, first.newVersion());

        TransitionResult second = store.transition(ledgerId, MessageState.PENDING_CONFIRM,
                MessageState.LANDED, "operator-001", "manual-confirm");
        assertEquals(1L, second.previousVersion());
        assertEquals(2L, second.newVersion());

        assertEquals(MessageState.LANDED, store.stateOf(ledgerId).orElseThrow());

        // 逐条核审计：数量、from、to、operator、reason
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT from_state, to_state, operator, reason FROM message_audit "
                             + "WHERE ledger_id = ? ORDER BY at ASC, id ASC")) {
            ps.setLong(1, ledgerId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "第一次迁移必须留一行审计");
                assertEquals("RECEIVED", rs.getString("from_state"));
                assertEquals("PENDING_CONFIRM", rs.getString("to_state"));
                assertEquals("system", rs.getString("operator"));
                assertEquals("identity-candidates-ready", rs.getString("reason"));

                assertTrue(rs.next(), "第二次迁移必须再留一行审计");
                assertEquals("PENDING_CONFIRM", rs.getString("from_state"));
                assertEquals("LANDED", rs.getString("to_state"));
                assertEquals("operator-001", rs.getString("operator"));
                assertEquals("manual-confirm", rs.getString("reason"));

                assertFalse(rs.next(), "两次迁移只能有两行审计，多一行说明有写重投");
            }
        }

        assertEquals(2L, countAuditRows(ledgerId));
        assertEquals(2L, store.findById(ledgerId).orElseThrow().version());
    }

    @Test
    @DisplayName("审计链完整：合法迁移后 LedgerAuditChain.verify 不抛")
    void test_audit_chain_intact_after_legal_moves() throws Exception {
        JdbcLedgerStore store = store();
        long ledgerId = store.insertIfAbsent(freshEntry()).ledgerId();
        store.transition(ledgerId, MessageState.RECEIVED, MessageState.PENDING_CONFIRM,
                "system", "identity-candidates-ready");
        store.transition(ledgerId, MessageState.PENDING_CONFIRM, MessageState.PENDING_RETRY,
                "system", "peer-ack-missing");

        LedgerAuditChain.Verdict verdict = store.inspectChain(ledgerId);
        assertTrue(verdict.intact(), "合法迁移链必须完整，实际断点=" + verdict.breakKind() + " " + verdict.detail());
        assertEquals(LedgerAuditChain.Break.NONE, verdict.breakKind());
        assertEquals(2, verdict.auditRows());
        assertEquals(MessageState.PENDING_RETRY, verdict.chainTail());
        assertEquals(MessageState.PENDING_RETRY, verdict.ledgerState());
    }

    // =====================================================================
    // 2. 事务原子性：审计写失败 → 整体回滚
    // =====================================================================

    @Test
    @DisplayName("审计写入失败时整体回滚：台账 state 与审计行数都不变（失败不静默改数据）")
    void test_rollback_leaves_ledger_and_audit_unchanged() throws Exception {
        LedgerEntry entry = freshEntry();
        long ledgerId = store().insertIfAbsent(entry).ledgerId();
        store().transition(ledgerId, MessageState.RECEIVED, MessageState.PENDING_CONFIRM,
                "system", "identity-candidates-ready");

        long auditBefore = countAuditRows(ledgerId);
        assertEquals(1L, auditBefore);

        // 注入一个必失败的审计写入器：只有审计这一步失败，台账 UPDATE 已经执行过
        AtomicInteger auditAttempts = new AtomicInteger();
        AuditWriter failing = (conn, id, from, to, operator, reason) -> {
            auditAttempts.incrementAndGet();
            throw new SQLException("测试注入：审计写失败", "08006");
        };
        JdbcLedgerStore store = new JdbcLedgerStore(JdbcLedgerStoreTest::openConnection,
                FIXED_CLOCK, failing);

        SQLException thrown = assertThrows(SQLException.class,
                () -> store.transition(ledgerId, MessageState.PENDING_CONFIRM, MessageState.LANDED,
                        "operator-001", "manual-confirm"));
        assertEquals("08006", thrown.getSQLState());
        assertEquals(1, auditAttempts.get(), "审计写入器必须真的被调用过（否则这条用例没测到东西）");

        // 关键断言：台账状态没被推进，审计没多出行
        assertEquals(MessageState.PENDING_CONFIRM, store().stateOf(ledgerId).orElseThrow(),
                "审计写失败后 state 不得变 —— 这正是「失败不静默改数据」");
        assertEquals(auditBefore, countAuditRows(ledgerId), "审计写失败后不得留下半行审计");
        assertEquals(1L, store().findById(ledgerId).orElseThrow().version(),
                "version 也不得被推进：回滚必须把 UPDATE 一起撤掉");
    }

    // =====================================================================
    // 3. 绕过状态机：写时拦截 + 存储层拒绝 + 一致性断言（三重）
    // =====================================================================

    @Test
    @DisplayName("绕过状态机被三重拦截：非法边写时拒、错来源态存储层拒、裸 SQL 改 state 被审计链查出")
    void test_cannot_bypass_state_machine() throws Exception {
        JdbcLedgerStore store = store();
        long ledgerId = store.insertIfAbsent(freshEntry()).ledgerId();
        store.transition(ledgerId, MessageState.RECEIVED, MessageState.PENDING_CONFIRM,
                "system", "identity-candidates-ready");

        long auditBefore = countAuditRows(ledgerId);

        // ---- 拦截一：非法边（待确认→拒收）在开事务之前就被拒，一条数据都写不进去 ----
        IllegalStateTransitionException illegal = assertThrows(IllegalStateTransitionException.class,
                () -> store.transition(ledgerId, MessageState.PENDING_CONFIRM, MessageState.REJECTED,
                        "operator-001", "sneaky-reject"));
        assertEquals(MessageState.PENDING_CONFIRM, illegal.from());
        assertEquals(MessageState.REJECTED, illegal.to());
        assertEquals(MessageState.PENDING_CONFIRM, store.stateOf(ledgerId).orElseThrow());
        assertEquals(auditBefore, countAuditRows(ledgerId));

        // ---- 拦截二：拿着过期来源态来推进（RECEIVED→LANDED 本身合法，但当前态不是 RECEIVED）
        //      —— 被 UPDATE ... WHERE state=? 的来源态守卫拒掉 ----
        LedgerStateConflictException stale = assertThrows(LedgerStateConflictException.class,
                () -> store.transition(ledgerId, MessageState.RECEIVED, MessageState.LANDED,
                        "operator-001", "stale-source"));
        assertTrue(stale.rowExists());
        assertEquals(MessageState.RECEIVED, stale.claimedFrom());
        assertEquals(MessageState.PENDING_CONFIRM, stale.actualState());
        assertEquals(MessageState.PENDING_CONFIRM, store.stateOf(ledgerId).orElseThrow());
        assertEquals(auditBefore, countAuditRows(ledgerId));

        // ---- 拦截三：裸 SQL 直接 UPDATE state，绕过 transition（既不写审计也不走 version 自增）。
        //      这条只能靠读路径的一致性断言查出来 ----
        try (Connection conn = openConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE message_ledger SET state = 'LANDED' WHERE id = ?")) {
                ps.setLong(1, ledgerId);
                assertEquals(1, ps.executeUpdate(), "本用例必须真的改到了那一行，否则测不到绕过路径");
            }
            conn.commit();
        }

        LedgerAuditChainBrokenException broken = assertThrows(LedgerAuditChainBrokenException.class,
                () -> {
                    try (Connection conn = openConnection()) {
                        LedgerAuditChain.verify(conn, ledgerId);
                    }
                });
        assertEquals(LedgerAuditChain.Break.LEDGER_STATE_DIVERGES, broken.verdict().breakKind());
        assertEquals(MessageState.LANDED, broken.verdict().ledgerState());
        assertEquals(MessageState.PENDING_CONFIRM, broken.verdict().chainTail(),
                "审计链末端仍停在待确认，与被裸改的台账状态对不上");
        // 异常消息必须同时点出两个状态，否则运维只知道「不一致」不知道「哪边不对」
        assertTrue(broken.getMessage().contains("LANDED"), "异常消息要点出台账实际状态: " + broken.getMessage());
        assertTrue(broken.getMessage().contains("PENDING_CONFIRM"),
                "异常消息要点出审计链末端: " + broken.getMessage());
    }

    @Test
    @DisplayName("裸 INSERT 一条非 RECEIVED 的台账行会被审计链判为断链（历史脏数据兜底）")
    void test_audit_chain_detects_row_inserted_outside_receive_state() throws Exception {
        long ledgerId = insertRawLedgerRow("PENDING_CONFIRM");

        try (Connection conn = openConnection()) {
            LedgerAuditChain.Verdict verdict = LedgerAuditChain.inspect(conn, ledgerId);
            assertFalse(verdict.intact());
            assertEquals(LedgerAuditChain.Break.LEDGER_STATE_DIVERGES, verdict.breakKind());
            assertEquals(MessageState.RECEIVED, verdict.chainTail(),
                    "无审计行时链根只能是入账初态 RECEIVED");
            assertEquals(MessageState.PENDING_CONFIRM, verdict.ledgerState());
        }
    }

    @Test
    @DisplayName("审计链中间被删改（from 与上一行 to 对不上）同样被判为断链")
    void test_audit_chain_detects_non_contiguous_audit_rows() throws Exception {
        JdbcLedgerStore store = store();
        long ledgerId = store.insertIfAbsent(freshEntry()).ledgerId();
        store.transition(ledgerId, MessageState.RECEIVED, MessageState.PENDING_CONFIRM,
                "system", "identity-candidates-ready");
        store.transition(ledgerId, MessageState.PENDING_CONFIRM, MessageState.LANDED,
                "operator-001", "manual-confirm");

        // 删掉第一条审计，剩下的第一行 from=PENDING_CONFIRM 而链根期望 RECEIVED
        deleteOldestAuditRow(ledgerId);

        try (Connection conn = openConnection()) {
            LedgerAuditChain.Verdict verdict = LedgerAuditChain.inspect(conn, ledgerId);
            assertFalse(verdict.intact());
            assertEquals(LedgerAuditChain.Break.AUDIT_CHAIN_NOT_CONTIGUOUS, verdict.breakKind());
            assertTrue(verdict.detail().contains("审计链中间被删改"), verdict.detail());
        }
    }

    // =====================================================================
    // 4. 幂等插入
    // =====================================================================

    @Test
    @DisplayName("同一幂等键二次 insertIfAbsent 返回 AlreadyProcessed，带原 id 与当前态，且不新增行")
    void test_insert_if_absent_returns_already_processed_with_existing_id() throws Exception {
        JdbcLedgerStore store = store();
        LedgerEntry entry = freshEntry();

        InsertOutcome.Inserted first = (InsertOutcome.Inserted) store.insertIfAbsent(entry);
        assertTrue(first.isNew());

        // 同一键，但批次号换成别的：唯一约束只看幂等键三段，批次换了也必须命中已处理
        LedgerEntry replay = new LedgerEntry(entry.key(), "BATCH-T13-REPLAY", entry.rawPayload(),
                "", false, 0);
        InsertOutcome.AlreadyProcessed second =
                (InsertOutcome.AlreadyProcessed) store.insertIfAbsent(replay);
        assertFalse(second.isNew());
        assertEquals(first.ledgerId(), second.existingLedgerId());
        assertEquals(MessageState.RECEIVED, second.currentState());

        // 幂等命中不得新增第二行台账，也不得把批次号改掉
        LedgerRow row = store.findById(first.ledgerId()).orElseThrow();
        assertEquals(entry.batchId(), row.batchId(), "重复到达不得覆盖批次归属");
        assertEquals(1L, countLedgerRows(entry.key()),
                "该幂等键在库里始终只应有一行；重复到达若插出第二行，对账时会多出一条没回执的记录");

        // 重复到达时状态已推进 → AlreadyProcessed 必须带回当前态，供调用方按当前态回执
        store.transition(first.ledgerId(), MessageState.RECEIVED, MessageState.PENDING_CONFIRM,
                "system", "identity-candidates-ready");
        InsertOutcome.AlreadyProcessed afterMove =
                (InsertOutcome.AlreadyProcessed) store.insertIfAbsent(entry);
        assertEquals(MessageState.PENDING_CONFIRM, afterMove.currentState(),
                "已处理的返回必须带当前态，否则同一条报文会回出两种结果");
    }

    @Test
    @DisplayName("不同幂等键各占一行；同一批次的三条报文互不影响")
    void test_distinct_keys_insert_independently() throws Exception {
        JdbcLedgerStore store = store();
        String batchId = "BATCH-T13-MULTI-" + (++batchSeq);
        for (int i = 1; i <= 3; i++) {
            InsertOutcome outcome = store.insertIfAbsent(LedgerEntry.inbound(
                    IdempotencyKey.of("ADT^A08", "CTRL-MULTI-" + i, "P"), batchId, SAMPLE_PAYLOAD));
            assertTrue(outcome.isNew());
        }
        assertEquals(3, store.listByBatch(batchId, null).size());
        assertEquals(3L, store.countByState(batchId).get(MessageState.RECEIVED));
    }

    // =====================================================================
    // 5. 写入前置条件
    // =====================================================================

    @Test
    @DisplayName("台账行不存在时迁移被拒，异常消息区分「行不存在」与「状态不符」")
    void test_transition_rejects_missing_ledger_row() {
        JdbcLedgerStore store = store();
        LedgerStateConflictException missing = assertThrows(LedgerStateConflictException.class,
                () -> store.transition(9_999_999L, MessageState.RECEIVED, MessageState.PENDING_CONFIRM,
                        "system", "should-not-happen"));
        assertFalse(missing.rowExists());
        assertTrue(missing.getMessage().contains("不存在"), missing.getMessage());
    }

    @Test
    @DisplayName("operator 与 reason 不得为空串：每一次迁移都要能追到是谁做的、为什么")
    void test_transition_requires_operator_and_reason() throws Exception {
        JdbcLedgerStore store = store();
        long ledgerId = store.insertIfAbsent(freshEntry()).ledgerId();

        assertThrows(IllegalArgumentException.class,
                () -> store.transition(ledgerId, MessageState.RECEIVED, MessageState.PENDING_CONFIRM,
                        "  ", "reason"));
        assertThrows(IllegalArgumentException.class,
                () -> store.transition(ledgerId, MessageState.RECEIVED, MessageState.PENDING_CONFIRM,
                        "operator-001", ""));
        assertThrows(NullPointerException.class,
                () -> store.transition(ledgerId, MessageState.RECEIVED, MessageState.PENDING_CONFIRM,
                        null, "reason"));

        // 前置校验失败时不得留下任何痕迹
        assertEquals(MessageState.RECEIVED, store.stateOf(ledgerId).orElseThrow());
        assertEquals(0L, countAuditRows(ledgerId));
    }

    // =====================================================================
    // 6. 查询
    // =====================================================================

    @Test
    @DisplayName("listByBatch 按入账顺序返回；countByState 预填全部状态（含 0 条的拒收）")
    void test_list_by_batch_and_count_by_state_prefill_all_states() throws Exception {
        JdbcLedgerStore store = store();
        String batchId = "BATCH-T13-QUERY-" + (++batchSeq);
        long a = store.insertIfAbsent(LedgerEntry.inbound(
                IdempotencyKey.of("ADT^A08", "CTRL-Q-1", "P"), batchId, SAMPLE_PAYLOAD)).ledgerId();
        long b = store.insertIfAbsent(LedgerEntry.inbound(
                IdempotencyKey.of("ADT^A08", "CTRL-Q-2", "P"), batchId, SAMPLE_PAYLOAD)).ledgerId();
        long c = store.insertIfAbsent(LedgerEntry.inbound(
                IdempotencyKey.of("ADT^A08", "CTRL-Q-3", "P"), batchId, SAMPLE_PAYLOAD)).ledgerId();

        store.transition(a, MessageState.RECEIVED, MessageState.REJECTED, "system", "charset-unsupported");
        store.transition(b, MessageState.RECEIVED, MessageState.PENDING_CONFIRM, "system", "low-confidence");

        List<LedgerRow> all = store.listByBatch(batchId, null);
        assertEquals(3, all.size());
        assertEquals(List.of(a, b, c), List.of(all.get(0).id(), all.get(1).id(), all.get(2).id()),
                "必须按 id 升序（入账顺序）返回，便于下午按批次逐条核对");

        assertEquals(1, store.listByBatch(batchId, MessageState.REJECTED).size());
        assertEquals(1, store.listByBatch(batchId, MessageState.PENDING_CONFIRM).size());
        assertEquals(0, store.listByBatch(batchId, MessageState.LANDED).size());

        Map<MessageState, Long> counts = store.countByState(batchId);
        assertEquals(1L, counts.get(MessageState.REJECTED));
        assertEquals(1L, counts.get(MessageState.PENDING_CONFIRM));
        assertEquals(1L, counts.get(MessageState.RECEIVED));
        assertEquals(0L, counts.get(MessageState.LANDED));
        assertEquals(MessageState.values().length, counts.size(),
                "「这一批没有已落地条目」也必须显式出现，不能因 map 里缺 key 就从分布表上消失");
    }

    @Test
    @DisplayName("findByIdempotencyKey 查得到整行（含 raw_payload 与两条主键的分离口径）")
    void test_find_by_idempotency_key_returns_full_row() throws Exception {
        JdbcLedgerStore store = store();
        IdempotencyKey key = IdempotencyKey.of("ORU^R01", "CTRL-LOOKUP-1", "T");
        store.insertIfAbsent(new LedgerEntry(key, "BATCH-T13-LOOKUP", SAMPLE_PAYLOAD,
                "MRN-0001", true, 2));

        Optional<LedgerRow> found = store.findByIdempotencyKey(key);
        assertTrue(found.isPresent());
        LedgerRow row = found.orElseThrow();
        assertEquals("ORU^R01", row.key().messageType());
        assertEquals("BATCH-T13-LOOKUP", row.batchId());
        assertEquals(MessageState.RECEIVED, row.state());
        assertEquals(SAMPLE_PAYLOAD, row.rawPayload(), "重投要靠 raw_payload 重发原文，不能被截断或改写");
        assertEquals("MRN-0001", row.localMrn());
        assertTrue(row.degraded());
        assertEquals(2, row.retryCount());
        assertNotNull(row.createdAt());

        assertTrue(store.findByIdempotencyKey(
                IdempotencyKey.of("ORU^R01", "CTRL-NOT-THERE", "T")).isEmpty());
    }

    @Test
    @DisplayName("幂等键三段任一为空也照样入账（缺控制号的报文也要占一行，不能互相覆盖）")
    void test_key_with_empty_segments_still_inserts_distinctly() throws Exception {
        JdbcLedgerStore store = store();
        String batchId = "BATCH-T13-EMPTY-" + (++batchSeq);
        InsertOutcome noControl1 = store.insertIfAbsent(LedgerEntry.inbound(
                IdempotencyKey.of("ADT^A08", "", "P"), batchId, SAMPLE_PAYLOAD));
        InsertOutcome noType = store.insertIfAbsent(LedgerEntry.inbound(
                IdempotencyKey.of("", "", ""), batchId, SAMPLE_PAYLOAD));

        assertTrue(noControl1.isNew());
        assertTrue(noType.isNew());
        assertFalse(noControl1.ledgerId() == noType.ledgerId(),
                "缺控制号与完全缺头是两条不同报文，不得撞成同一行");
        assertEquals(2, store.listByBatch(batchId, null).size());

        // 键的往返：storageKey() 能原样 parse 回同一个键（导出的待确认清单要靠它去重）
        IdempotencyKey original = new IdempotencyKey("ADT^A08", "", "P");
        assertEquals(original, IdempotencyKey.parse(original.storageKey()));
        assertTrue(original.toString().contains("ctrl=(空)"),
                "缺控制号在日志里必须显眼，不能显示成裸分隔符串: " + original);
    }

    // =====================================================================
    // 工具
    // =====================================================================

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

    /** 用裸 SQL 造一条「不走 insertIfAbsent」的历史脏数据行。 */
    private static long insertRawLedgerRow(String state) throws SQLException {
        try (Connection conn = openConnection()) {
            conn.setAutoCommit(false);
            long id;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO message_ledger "
                            + "(message_type, control_id, processing_id, batch_id, state, raw_payload) "
                            + "VALUES ('ADT^A08', 'CTRL-RAW', 'P', 'BATCH-T13-RAW', ?, 'MSH|...')",
                    new String[]{"ID"})) {
                ps.setString(1, state);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    id = keys.getLong(1);
                }
            }
            conn.commit();
            return id;
        }
    }

    private static void deleteOldestAuditRow(long ledgerId) throws SQLException {
        try (Connection conn = openConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM message_audit WHERE id = "
                            + "(SELECT MIN(id) FROM message_audit WHERE ledger_id = ?)")) {
                ps.setLong(1, ledgerId);
                assertEquals(1, ps.executeUpdate());
            }
            conn.commit();
        }
    }
}