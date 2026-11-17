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
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T14 边界与异常用例集 —— 「失败不静默改数据」这句话的可复核凭据。
 *
 * <h2>这一组在验什么</h2>
 * <p>T13 验的是<b>正常路径的原子性</b>（状态变了审计也变了）。这一组验的是<b>失败路径</b>：
 * <ul>
 *   <li>重复到达 → 幂等命中且台账行数不变（不是抛异常让整批中断）；</li>
 *   <li>非法迁移 → 台账与审计<b>一行都不能多</b>；</li>
 *   <li>审计写失败 → 状态更新整体回滚，不留「状态变了但没痕」的半截数据；</li>
 *   <li>空批次 / 单条批次 → 都能正常入库与统计（0 条也要给出一张完整的零分布）；</li>
 *   <li>超长报文 → 截断入库并记 WARNING，超硬上限直接拒收，<b>不</b> OOM；</li>
 *   <li>20 线程 20 个不同幂等键并发 → 全部成功且无丢失（与 T16 的「同键只一条」互为对照：
 *       这一条防的是把去重写成「不同报文也吃掉」）。</li>
 * </ul>
 *
 * <p>与 T13 一样<b>不 mock 数据库</b>：H2 PG 兼容模式 + 真实 JDBC + 真实事务。
 * 「回滚到底回滚了没有」只有真事务能回答。
 */
class LedgerBoundaryTest {

    private static final String H2_URL =
            "jdbc:h2:mem:ledger_boundary_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-10-20T02:00:00Z"), ZoneId.of("Asia/Shanghai"));

    private static final String SAMPLE_PAYLOAD = "MSH|^~\\&|CHSCOMMUNITY|REGIONAL_ARCHIVE|REGIONAL_ARCHIVE|FACILITY_A|"
            + "202610201000||ADT^A08|CTRL-T14|P|2.5.1|||AL|NE||UNICODE UTF-8\r"
            + "PID|1||PAT-0001^^^REGIONAL^MR||张^伟||19850512|M";

    private static int seq = 0;

    // =====================================================================
    // 夹具
    // =====================================================================

    private static Connection openConnection() throws SQLException {
        return DriverManager.getConnection(H2_URL, "sa", "");
    }

    /** 正常 store：系统时钟固定 + 真实审计写入。 */
    private static JdbcLedgerStore store() {
        return new JdbcLedgerStore(LedgerBoundaryTest::openConnection, FIXED_CLOCK,
                AuditWriter.jdbc(FIXED_CLOCK));
    }

    /** 审计必失败的 store：{@link AuditWriter} 做成接口就是为了这里不留测试开关。 */
    private static JdbcLedgerStore storeWithFailingAudit(AtomicInteger auditAttempts) {
        AuditWriter failing = (conn, ledgerId, from, to, operator, reason) -> {
            auditAttempts.incrementAndGet();
            throw new SQLException("模拟审计写入失败（磁盘满 / 权限回收 / 连接被中断）");
        };
        return new JdbcLedgerStore(LedgerBoundaryTest::openConnection, FIXED_CLOCK, failing);
    }

    private static LedgerEntry freshEntry() {
        int n = ++seq;
        return LedgerEntry.inbound(
                IdempotencyKey.of("ADT^A08", "CTRL-T14-" + n, "P"), "BATCH-T14-" + n, SAMPLE_PAYLOAD);
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
    // 1. 重复入账：返回「已处理」而不是抛异常，台账行数不变
    // =====================================================================

    @Test
    @DisplayName("同一幂等键重复入账返回 AlreadyProcessed 而非抛异常，台账行数恒为 1")
    void test_duplicate_insert_returns_already_processed_without_row_growth() throws Exception {
        JdbcLedgerStore store = store();
        LedgerEntry entry = freshEntry();

        InsertOutcome first = store.insertIfAbsent(entry);
        assertTrue(first instanceof InsertOutcome.Inserted, "首次入账必须是 Inserted");

        // 第二次：同一条报文对端重推。必须拿到 AlreadyProcessed，且不抛异常 ——
        // 主路径上抛异常意味着整批处理被打断，而重复到达只是常态。
        InsertOutcome second = store.insertIfAbsent(entry);
        assertTrue(second instanceof InsertOutcome.AlreadyProcessed, "重复入账必须返回 AlreadyProcessed");
        assertEquals(first.ledgerId(), second.ledgerId(), "必须指回同一行，不能凭空多出一条");

        // 第三次：链路重复经过（T35 重启续跑），结论必须稳定
        InsertOutcome third = store.insertIfAbsent(entry);
        assertEquals(first.ledgerId(), third.ledgerId());

        assertEquals(1L, countLedgerRows(entry.key()), "三次入账只能留下一行");

        // 重复到达不该产生任何审计行（审计只记状态迁移，不记「又收到一遍」）
        assertEquals(0L, countAuditRows(first.ledgerId()));
    }

    @Test
    @DisplayName("重复入账时 AlreadyProcessed 带的是当前态而非首次入账态")
    void test_duplicate_insert_reports_current_state_not_initial_state() throws Exception {
        JdbcLedgerStore store = store();
        LedgerEntry entry = freshEntry();
        long id = store.insertIfAbsent(entry).ledgerId();
        store.transition(id, MessageState.RECEIVED, MessageState.PENDING_CONFIRM, "system", "candidates-ready");

        InsertOutcome again = store.insertIfAbsent(entry);
        assertTrue(again instanceof InsertOutcome.AlreadyProcessed);
        assertEquals(MessageState.PENDING_CONFIRM,
                ((InsertOutcome.AlreadyProcessed) again).currentState(),
                "回执必须按当前态给；报 RECEIVED 会让对账时同一条报文出现两种结果");
        assertEquals(1L, countAuditRows(id), "重复入账不新增审计行");
    }

    // =====================================================================
    // 2. 非法迁移：一行都不能多
    // =====================================================================

    @Test
    @DisplayName("非法迁移被拒后台账状态、version 与审计行数全部不变")
    void test_illegal_transition_leaves_state_and_audit_unchanged() throws Exception {
        JdbcLedgerStore store = store();
        LedgerEntry entry = freshEntry();
        long id = store.insertIfAbsent(entry).ledgerId();
        store.transition(id, MessageState.RECEIVED, MessageState.PENDING_CONFIRM, "system", "candidates-ready");

        LedgerRow before = store.findById(id).orElseThrow();
        long auditsBefore = countAuditRows(id);
        assertEquals(1L, auditsBefore);
        assertEquals(1L, before.version());

        // 待确认 → 拒收：非法边（拒收只发生在入站阶段的硬校验，见 MessageStateMachine 注释）
        assertThrows(IllegalStateTransitionException.class, () ->
                store.transition(id, MessageState.PENDING_CONFIRM, MessageState.REJECTED, "operator-001", "manual"));

        LedgerRow after = store.findById(id).orElseThrow();
        assertEquals(MessageState.PENDING_CONFIRM, after.state(), "非法迁移不得改变状态");
        assertEquals(1L, after.version(), "非法迁移不得推进乐观锁版本号");
        assertEquals(auditsBefore, countAuditRows(id), "非法迁移不得留下审计行");
        assertEquals(before.updatedAt(), after.updatedAt(), "非法迁移不得留下 updated_at 痕迹");

        // 已落地 → 已接收同样非法；再验一次，确认不是「只有一条边有守卫」
        store.transition(id, MessageState.PENDING_CONFIRM, MessageState.LANDED, "operator-001", "manual-confirm");
        long auditsLanded = countAuditRows(id);
        assertThrows(IllegalStateTransitionException.class, () ->
                store.transition(id, MessageState.LANDED, MessageState.RECEIVED, "system", "replay"));
        assertEquals(MessageState.LANDED, store.findById(id).orElseThrow().state());
        assertEquals(auditsLanded, countAuditRows(id));

        // 失败不静默改数据：审计链仍然自洽（审计行数 == version，且首尾相接）
        assertTrue(store.inspectChain(id).intact(),
                "非法迁移之后审计链必须仍然完整，否则说明数据已经被改脏");
    }

    @Test
    @DisplayName("来源态与库里实际不符时迁移被拒，且不覆盖对方的写入")
    void test_stale_source_state_is_rejected_without_overwrite() throws Exception {
        JdbcLedgerStore store = store();
        LedgerEntry entry = freshEntry();
        long id = store.insertIfAbsent(entry).ledgerId();
        store.transition(id, MessageState.RECEIVED, MessageState.PENDING_CONFIRM, "system", "candidates-ready");

        // 拿着过期状态（RECEIVED）再推一次：合法边，但来源态不符 → 存储层拒绝
        LedgerStateConflictException conflict = assertThrows(LedgerStateConflictException.class, () ->
                store.transition(id, MessageState.RECEIVED, MessageState.REJECTED, "system", "stale"));

        assertEquals(MessageState.PENDING_CONFIRM, conflict.actualState());
        assertEquals(MessageState.PENDING_CONFIRM, store.findById(id).orElseThrow().state());
        assertEquals(1L, countAuditRows(id), "被拒的迁移同样不得留下审计行");
    }

    // =====================================================================
    // 3. 审计写失败：整体回滚
    // =====================================================================

    @Test
    @DisplayName("审计写失败时状态更新整体回滚，台账状态与 version 保持原值")
    void test_audit_write_failure_rolls_back_ledger_update() throws Exception {
        AtomicInteger auditAttempts = new AtomicInteger();
        JdbcLedgerStore failing = storeWithFailingAudit(auditAttempts);
        LedgerEntry entry = freshEntry();

        // 入账不经过审计写入器，用「审计必失败」的 store 入账也没问题
        long id = failing.insertIfAbsent(entry).ledgerId();
        assertEquals(0, auditAttempts.get(), "入账不应触发审计写入（审计只记状态迁移）");

        SQLException failure = assertThrows(SQLException.class, () ->
                failing.transition(id, MessageState.RECEIVED, MessageState.PENDING_CONFIRM,
                        "system", "candidates-ready"));
        assertTrue(failure.getMessage().contains("模拟审计写入失败"));

        // 关键断言：UPDATE 已经在审计失败之前执行过，回滚必须把它撤掉
        LedgerRow row = store().findById(id).orElseThrow();
        assertEquals(MessageState.RECEIVED, row.state(), "审计失败后状态必须仍是迁移前的那一个");
        assertEquals(0L, row.version(), "审计失败后 version 必须仍是 0");
        assertEquals(0L, countAuditRows(id), "审计失败不得留下半截审计行");

        // 回滚后连接不能被这次失败污染：换正常 store 读得到、写得了
        store().transition(id, MessageState.RECEIVED, MessageState.PENDING_CONFIRM, "system", "retry-after-rollback");
        assertEquals(MessageState.PENDING_CONFIRM, store().findById(id).orElseThrow().state());
        assertEquals(1L, countAuditRows(id));
    }

    @Test
    @DisplayName("审计写失败连试三次，每次都整体回滚，不留半截状态")
    void test_repeated_audit_failures_never_leave_partial_state() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        JdbcLedgerStore failing = storeWithFailingAudit(attempts);
        LedgerEntry entry = freshEntry();
        long id = failing.insertIfAbsent(entry).ledgerId();

        for (int i = 0; i < 3; i++) {
            final int attempt = i;
            assertThrows(SQLException.class, () -> failing.transition(id, MessageState.RECEIVED,
                    MessageState.PENDING_CONFIRM, "system", "attempt-" + attempt));
        }
        assertEquals(3, attempts.get(), "三次调用三次审计写入尝试");
        LedgerRow row = store().findById(id).orElseThrow();
        assertEquals(MessageState.RECEIVED, row.state());
        assertEquals(0L, row.version());
        assertEquals(0L, countAuditRows(id));
        assertEquals(1L, countLedgerRows(entry.key()));
    }

    // =====================================================================
    // 4. 空批次与单条批次
    // =====================================================================

    @Test
    @DisplayName("0 条的空批次与 1 条的单条批次都能正常入库与统计")
    void test_empty_and_single_row_batches_both_ingest() throws Exception {
        JdbcLedgerStore store = store();

        // 空批次：区域平台推了一个 0 条变更包（当天无档案变更）。此时四态分布必须是一张完整的零表，
        // 而不是空 map —— 「今天这批 0 条」本身就是信息员要看到的一条结论。
        String emptyBatch = "BATCH-T14-EMPTY-" + (++seq);
        assertTrue(store.listByBatch(emptyBatch, null).isEmpty());
        Map<MessageState, Long> emptyCounts = store.countByState(emptyBatch);
        assertEquals(EnumSet.allOf(MessageState.class), EnumSet.copyOf(emptyCounts.keySet()),
                "空批次的分布表必须覆盖全部状态");
        for (MessageState state : MessageState.values()) {
            assertEquals(0L, emptyCounts.get(state), state + " 在空批次里必须是 0 而不是缺 key");
        }

        // 单条批次
        String singleBatch = "BATCH-T14-SINGLE-" + (++seq);
        LedgerEntry only = LedgerEntry.inbound(
                IdempotencyKey.of("ADT^A08", "CTRL-T14-SINGLE", "P"), singleBatch, SAMPLE_PAYLOAD);
        long id = store.insertIfAbsent(only).ledgerId();
        List<LedgerRow> rows = store.listByBatch(singleBatch, null);
        assertEquals(1, rows.size());
        assertEquals(id, rows.get(0).id());
        assertEquals(1L, store.countByState(singleBatch).get(MessageState.RECEIVED));

        // 单条批次同样要能走完整条确认路径（空批次路径不该成为「没测过的那条」）
        store.transition(id, MessageState.RECEIVED, MessageState.PENDING_CONFIRM, "system", "candidates-ready");
        store.transition(id, MessageState.PENDING_CONFIRM, MessageState.LANDED, "operator-001", "manual-confirm");
        assertEquals(1L, store.countByState(singleBatch).get(MessageState.LANDED));
        assertEquals(0L, store.countByState(singleBatch).get(MessageState.RECEIVED));
        assertTrue(store.inspectChain(id).intact());
    }

    @Test
    @DisplayName("按 state 过滤与不限状态查询在同一批次上口径一致")
    void test_list_by_batch_state_filter_matches_unfiltered_query() throws Exception {
        JdbcLedgerStore store = store();
        String batch = "BATCH-T14-FILTER-" + (++seq);
        long landedId = store.insertIfAbsent(LedgerEntry.inbound(
                IdempotencyKey.of("ADT^A08", "CTRL-T14-F1", "P"), batch, SAMPLE_PAYLOAD)).ledgerId();
        store.insertIfAbsent(LedgerEntry.inbound(
                IdempotencyKey.of("ADT^A08", "CTRL-T14-F2", "P"), batch, SAMPLE_PAYLOAD));
        store.transition(landedId, MessageState.RECEIVED, MessageState.PENDING_CONFIRM, "system", "candidates-ready");
        store.transition(landedId, MessageState.PENDING_CONFIRM, MessageState.LANDED, "operator-001", "manual-confirm");

        assertEquals(2, store.listByBatch(batch, null).size());
        assertEquals(1, store.listByBatch(batch, MessageState.RECEIVED).size());
        assertEquals(1, store.listByBatch(batch, MessageState.LANDED).size());
        assertEquals(0, store.listByBatch(batch, MessageState.REJECTED).size());
    }

    // =====================================================================
    // 5. 超长报文：截断 + 告警；超硬上限拒收
    // =====================================================================

    @Test
    @DisplayName("原始报文超 1MB 时截断入库并记 WARNING，报文头完整保留")
    void test_oversized_raw_payload_truncated_with_warning() throws Exception {
        JdbcLedgerStore store = store();
        String oversized = SAMPLE_PAYLOAD + "ZRR|1|" + "长".repeat(RawPayloadPolicy.MAX_STORED_CHARS + 512);
        assertTrue(oversized.length() > RawPayloadPolicy.MAX_STORED_CHARS,
                "构造的报文必须真的超过入库上限，实际=" + oversized.length());

        LogCollector logs = LogCollector.attachTo(JdbcLedgerStore.class);
        long id;
        try {
            id = store.insertIfAbsent(LedgerEntry.inbound(
                    IdempotencyKey.of("ORU^R01", "CTRL-T14-BIG", "P"), "BATCH-T14-BIG", oversized)).ledgerId();
        } finally {
            logs.detach();
        }

        // 入库成功（没有因「value too long for column」整条丢在事务里）
        LedgerRow row = store.findById(id).orElseThrow();
        assertEquals(RawPayloadPolicy.MAX_STORED_CHARS, row.rawPayload().length(),
                "入库长度必须精确等于上限，且不超列宽");
        assertTrue(row.rawPayload().startsWith(SAMPLE_PAYLOAD),
                "报文头必须完整保留 —— 幂等键、段字段定位、四态回执都依赖它");
        assertTrue(row.rawPayload().contains("[台账截断]"), "截断必须留痕，不能让人以为这行是完整原文");
        assertTrue(row.rawPayload().contains(String.valueOf(oversized.length())),
                "截断说明要写明原始长度，排障时才知道丢了多少");

        // 记了告警，且告警行里不含报文内容（只落长度与主键）
        assertEquals(1, logs.warningCount(), "截断必须留一条 WARNING");
        String warning = logs.firstWarning();
        assertTrue(warning.contains("原始=" + oversized.length() + "字符"), warning);
        assertTrue(warning.contains("CTRL-T14-BIG"), warning);
        assertFalse(warning.contains("长长长"), "告警行不得落报文内容，只落长度与主键");

        // 截断后的这行仍然能正常走状态迁移 —— 容量边界不该让台账变成只读
        store.transition(id, MessageState.RECEIVED, MessageState.PENDING_CONFIRM, "system", "candidates-ready");
        assertEquals(MessageState.PENDING_CONFIRM, store.findById(id).orElseThrow().state());
        assertTrue(store.inspectChain(id).intact());
    }

    @Test
    @DisplayName("超长报文若不经容量策略会撞列宽抛异常 —— 证明截断不是可有可无的装饰")
    void test_oversized_payload_would_exceed_db_column_without_policy() throws Exception {
        String justOver = "MSH|^~\\&|A|B|C|D|202610201000||ADT^A08|CTRL-TOO-LONG|P|2.5.1|||AL|NE||UNICODE UTF-8"
                + "填充".repeat(RawPayloadPolicy.MAX_STORED_CHARS / 2 + 8);
        assertTrue(justOver.length() > RawPayloadPolicy.MAX_STORED_CHARS,
                "构造的报文必须真的超过入库上限，实际=" + justOver.length());

        // 绕过 store 直接裸 SQL 写 —— 模拟「忘了加容量策略」的旧世界
        assertThrows(SQLException.class, () -> {
            try (Connection conn = openConnection()) {
                conn.setAutoCommit(false);
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO message_ledger (message_type, control_id, processing_id, batch_id, "
                                + "state, raw_payload) VALUES ('ADT^A08', 'CTRL-NO-POLICY', 'P', "
                                + "'BATCH-T14-NOPOLICY', 'RECEIVED', ?)")) {
                    ps.setString(1, justOver);
                    ps.executeUpdate();
                }
                conn.commit();
            }
        });

        // 同样长度经 store 写入：截断后成功入账
        long id = store().insertIfAbsent(LedgerEntry.inbound(
                IdempotencyKey.of("ADT^A08", "CTRL-NO-POLICY", "P"), "BATCH-T14-NOPOLICY", justOver)).ledgerId();
        assertEquals(RawPayloadPolicy.MAX_STORED_CHARS,
                store().findById(id).orElseThrow().rawPayload().length());
        assertEquals(1L, countLedgerRows(IdempotencyKey.of("ADT^A08", "CTRL-NO-POLICY", "P")),
                "裸 SQL 那次必须整条失败，不能留下半截行");
    }

    @Test
    @DisplayName("超过硬上限的报文被拒收，且一行都不写")
    void test_payload_beyond_hard_cap_rejected_before_any_write() {
        String huge = "X".repeat(RawPayloadPolicy.MAX_ACCEPTED_CHARS + 1);

        PayloadTooLargeException e = assertThrows(PayloadTooLargeException.class,
                () -> store().insertIfAbsent(LedgerEntry.inbound(
                        IdempotencyKey.of("ADT^A08", "CTRL-T14-HUGE", "P"), "BATCH-T14-HUGE", huge)));

        assertEquals(RawPayloadPolicy.MAX_ACCEPTED_CHARS, e.hardCap());
        assertTrue(e.originalLength() > RawPayloadPolicy.MAX_ACCEPTED_CHARS);
        // 拒收不是「静默丢」：异常消息里必须说清原始长度与上限，调用方拿它去拼 ERR 段
        assertTrue(e.getMessage().contains(String.valueOf(e.originalLength())));

        // 关键：拒收后一行都不能留（连被截断的半截数据也不留）
        try {
            assertEquals(0L, countLedgerRows(IdempotencyKey.of("ADT^A08", "CTRL-T14-HUGE", "P")));
        } catch (SQLException e2) {
            throw new AssertionError("读取台账行数失败", e2);
        }
    }

    @Test
    @DisplayName("截断点不切开代理对，入库后没有半个代理字符")
    void test_truncation_never_splits_a_surrogate_pair() {
        // 全部用代理对字符（😀 = 2 码元），切点必然频繁落在代理对中间
        String emoji = "😀".repeat(64);
        RawPayloadPolicy.Result cut = RawPayloadPolicy.apply(emoji, 40, 4000);

        assertTrue(cut.truncated());
        String stored = cut.storedPayload();
        assertTrue(stored.length() <= 40, "实际长度=" + stored.length());

        int noteStart = stored.indexOf("\n[台账截断]");
        assertTrue(noteStart > 0, "必须留下中文截断说明");
        String body = stored.substring(0, noteStart);

        // 正文末尾不能是落单的高代理项：它一定意味着切点切开了代理对
        assertFalse(Character.isHighSurrogate(body.charAt(body.length() - 1)),
                "正文末尾落单了一个高代理字符，切点切开了代理对");
        assertFalse(Character.isLowSurrogate(body.charAt(0)), "正文开头不应是落单的低代理项");
        assertEquals(0, body.length() % 2, "正文必须由完整的代理对组成");
        assertEquals(emoji.length(), cut.originalLength(), "原始长度按码元计");
        assertEquals(stored.length(), cut.storedLength(), "结论里的入库长度必须与实际字符串一致");
    }

    @Test
    @DisplayName("空报文（零字符）正常入账，不被容量策略当成异常")
    void test_empty_payload_string_is_accepted() throws Exception {
        long id = store().insertIfAbsent(LedgerEntry.inbound(
                IdempotencyKey.of("ADT^A08", "CTRL-T14-EMPTY-PAYLOAD", "P"),
                "BATCH-T14-EMPTYPAY", "")).ledgerId();
        assertEquals("", store().findById(id).orElseThrow().rawPayload(),
                "报文头被整个剥掉的报文也要入账并回带 ERR 的 AR（T7/T8 的职责），不能被容量策略挡下");
    }

    // =====================================================================
    // 6. 并发：20 线程 20 个不同幂等键，一条都不能丢
    // =====================================================================

    @Test
    @DisplayName("20 线程并发插入 20 条不同幂等键，全部成功且无丢失")
    void test_20_threads_distinct_keys_no_loss() throws Exception {
        JdbcLedgerStore store = store();
        String batch = "BATCH-T14-CONC-" + (++seq);
        int threads = 20;

        ConcurrentLinkedQueue<InsertOutcome> outcomes = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            final int n = i;
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    // 每个线程一条不同幂等键：这一组要证明的是「不同报文不被去重吃掉」
                    outcomes.add(store.insertIfAbsent(LedgerEntry.inbound(
                            IdempotencyKey.of("ADT^A08", "CTRL-T14-CONC-" + n, "P"), batch, SAMPLE_PAYLOAD)));
                } catch (Throwable t) {
                    failures.add(t);
                } finally {
                    done.countDown();
                }
            }));
        }
        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS), "20 个线程 60 秒内必须全部结束（有线程挂死就是丢消息）");
        for (var f : futures) {
            f.get(5, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        assertTrue(failures.isEmpty(), "并发插入不应有失败，实际: " + failures);
        assertEquals(threads, outcomes.size(), "每个线程都要拿到一个明确结果");
        for (InsertOutcome outcome : outcomes) {
            assertTrue(outcome instanceof InsertOutcome.Inserted,
                    "幂等键互不相同，20 条都必须是首次入账；出现 AlreadyProcessed 说明去重写坏了");
        }

        // 主键互不相同 + 台账行数与状态分布都对得上，一条不丢
        Set<Long> ids = new HashSet<>();
        for (InsertOutcome outcome : outcomes) {
            assertTrue(ids.add(outcome.ledgerId()), "并发产生了重复主键: " + outcome.ledgerId());
        }
        assertEquals(threads, ids.size());
        assertEquals(threads, store.listByBatch(batch, null).size());
        assertEquals(threads, store.countByState(batch).get(MessageState.RECEIVED));
        assertEquals(0L, store.countByState(batch).get(MessageState.LANDED),
                "本批次不该有任何已落地条目（并发不允许串状态）");
        assertEquals(0L, store.countByState(batch).get(MessageState.REJECTED));
        assertEquals(0L, store.countByState(batch).get(MessageState.PENDING_CONFIRM));
    }

    // =====================================================================
    // 夹具辅助
    // =====================================================================

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

    /** 把某个 logger 的 WARNING 记录收集到内存，用完即摘。 */
    private static final class LogCollector extends Handler {

        private final Logger target;
        private final List<LogRecord> records = new ArrayList<>();

        private LogCollector(Logger target) {
            this.target = target;
            setLevel(Level.ALL);
        }

        static LogCollector attachTo(Class<?> type) {
            Logger logger = Logger.getLogger(type.getName());
            LogCollector collector = new LogCollector(logger);
            collector.target.setLevel(Level.ALL);
            collector.target.addHandler(collector);
            return collector;
        }

        void detach() {
            target.removeHandler(this);
        }

        @Override
        public void publish(LogRecord record) {
            if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                records.add(record);
            }
        }

        int warningCount() {
            return records.size();
        }

        String firstWarning() {
            LogRecord first = records.get(0);
            return java.text.MessageFormat.format(first.getMessage(), first.getParameters());
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }
}