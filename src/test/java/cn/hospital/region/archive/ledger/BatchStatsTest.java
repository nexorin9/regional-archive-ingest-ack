package cn.hospital.region.archive.ledger;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T19 批次四态统计用例 —— 「下午按批次核对收到几条」这句话的可核对答案。
 *
 * <p><b>不 mock 数据库</b>：H2 PG 兼容模式 + 真实 JDBC。统计口径要验的是
 * 「SQL 聚合与台账写入用的是同一个 state 取值」，那只有真库能答。
 * 每例前清表：H2 用 {@code DB_CLOSE_DELAY=-1} 跨方法存活，不清表会让上一例的行数渗进这一例。
 */
class BatchStatsTest {

    private static final String H2_URL =
            "jdbc:h2:mem:batch_stats_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-10-20T02:00:00Z"), ZoneId.of("Asia/Shanghai"));

    /** 平台日常推送的一个批次号：发送应用 + 日期。 */
    private static final String BATCH = "REGIONAL_ARCHIVE-20261020";

    private static int seq = 0;

    private static Connection openConnection() throws SQLException {
        return DriverManager.getConnection(H2_URL, "sa", "");
    }

    private static JdbcLedgerStore store() {
        return new JdbcLedgerStore(BatchStatsTest::openConnection, FIXED_CLOCK,
                AuditWriter.jdbc(FIXED_CLOCK));
    }

    private static String er7(String controlId) {
        return "MSH|^~\\&|CHSCOMMUNITY|REGIONAL_ARCHIVE|REGIONAL_ARCHIVE|FACILITY_A|202610201000||ADT^A08|"
                + controlId + "|P|2.5.1|||AL|NE||UNICODE UTF-8\r"
                + "PID|1||PAT-0001^^^REGIONAL^MR||张^伟||19850512|M";
    }

    /** 缺 MSH-10 的报文：拒收原因码会是 control-id-missing（FieldLocator 的建议码）。 */
    private static String er7MissingControlId() {
        return "MSH|^~\\&|CHSCOMMUNITY|REGIONAL_ARCHIVE|REGIONAL_ARCHIVE|FACILITY_A|202610201000||ADT^A08||"
                + "P|2.5.1|||AL|NE||UNICODE UTF-8\r"
                + "PID|1||PAT-0002^^^REGIONAL^MR||李^娜||19900201|F";
    }

    /** 缺 PID-3 的报文：拒收原因码 patient-id-missing，出错位置 PID.3。 */
    private static String er7MissingPatientId() {
        return "MSH|^~\\&|CHSCOMMUNITY|REGIONAL_ARCHIVE|REGIONAL_ARCHIVE|FACILITY_A|202610201000||ADT^A08|"
                + (++seq) + "|P|2.5.1|||AL|NE||UNICODE UTF-8\r"
                + "PID|1||||赵^敏||19930303|F";
    }

    /**
     * 铺一批 8 条：已落地 5 / 待确认 2 / 拒收 1。
     *
     * <p>其中一条待确认标记为模型降级（degraded=true），用来给「低置信待确认」一个非零口径。
     * 状态迁移全部走 {@link LedgerStore#transition}，不直接写 SQL —— 走 SQL 就等于
     * 这次统计验的是「我 SQL 写得对」而不是「接入主路径写进来的状态能被统计出来」。
     */
    private static void seedEightMessageBatch(JdbcLedgerStore store) throws Exception {
        // 5 条：已接收 → 待确认 → 已落地
        for (int i = 1; i <= 5; i++) {
            long id = store.insertIfAbsent(
                    LedgerEntry.inbound(IdempotencyKey.of("ADT^A08", "CTRL-B18-000" + i, "P"),
                            BATCH, er7("CTRL-B18-000" + i))).ledgerId();
            store.transition(id, MessageState.RECEIVED, MessageState.PENDING_CONFIRM,
                    "system", "received-awaiting-identity-confirm");
            store.transition(id, MessageState.PENDING_CONFIRM, MessageState.LANDED,
                    "operator-001", "confirmed-landed");
        }
        // 2 条待确认：一条正常、一条模型降级
        for (int i = 6; i <= 7; i++) {
            IdempotencyKey key = IdempotencyKey.of("ADT^A08", "CTRL-B18-000" + i, "P");
            long id = store.insertIfAbsent(i == 7
                    ? LedgerEntry.inboundDegraded(key, BATCH, er7("CTRL-B18-000" + i))
                    : LedgerEntry.inbound(key, BATCH, er7("CTRL-B18-000" + i))).ledgerId();
            store.transition(id, MessageState.RECEIVED, MessageState.PENDING_CONFIRM,
                    "system", "received-awaiting-identity-confirm");
        }
        // 1 条拒收：缺 PID-3
        long rejectedId = store.insertIfAbsent(
                LedgerEntry.inbound(IdempotencyKey.of("ADT^A08", "CTRL-B18-0008", "P"),
                        BATCH, er7MissingPatientId())).ledgerId();
        store.transition(rejectedId, MessageState.RECEIVED, MessageState.REJECTED, "system",
                ArchiveIngestService.REASON_PREFIX_REJECTED + "patient-id-missing");
    }

    @BeforeAll
    static void migrateOnce() throws SQLException {
        try (Connection conn = openConnection()) {
            conn.setAutoCommit(false);
            LedgerSchema.migrate(conn);
            conn.commit();
        }
    }

    @BeforeEach
    void cleanTables() throws SQLException {
        try (Connection conn = openConnection();
             var st = conn.createStatement()) {
            st.execute("DELETE FROM message_audit");
            st.execute("DELETE FROM message_ledger");
        }
    }

    // =====================================================================
    // 核心验收：8 条批次 → 已落地 5 / 待确认 2 / 拒收 1
    // =====================================================================

    @Test
    @DisplayName("8 条批次的统计为：已落地 5 / 待确认 2 / 拒收 1")
    void test_batch_of_8_yields_5_landed_2_pending_1_rejected() throws Exception {
        JdbcLedgerStore store = store();
        seedEightMessageBatch(store);

        BatchStats stats = BatchStats.of(store, BATCH);

        assertEquals(BATCH, stats.batchId());
        assertEquals(8L, stats.total(), "批次总数必须是 8");
        assertEquals(5L, stats.landed(), "已落地应为 5");
        assertEquals(2L, stats.pendingConfirm(), "待确认应为 2");
        assertEquals(1L, stats.rejected(), "拒收应为 1");
        assertEquals(0L, stats.received(), "入账后即推进，本批不该停在已接收");
        assertEquals(5L + 2L + 1L, stats.total(), "四态之和必须等于总数");
        assertFalse(stats.allLanded(), "还有待确认与拒收，不能算整批已落地");

        String headline = stats.headline();
        assertTrue(headline.contains("共 8 条"), "一句话结论应给出总数：" + headline);
        assertTrue(headline.contains("已落地 5"), "一句话结论应给出已落地数：" + headline);
        assertTrue(headline.contains("待确认 2"), "一句话结论应给出待确认数：" + headline);
        assertTrue(headline.contains("拒收 1"), "一句话结论应给出拒收数：" + headline);
    }

    @Test
    @DisplayName("低置信待确认 = 待确认里模型降级的那部分")
    void test_low_confidence_pending_counts_degraded_only_among_pending() throws Exception {
        JdbcLedgerStore store = store();
        seedEightMessageBatch(store);

        BatchStats stats = BatchStats.of(store, BATCH);

        // 7 号那条是 degraded 的待确认；已落地/拒收里没有降级条目 —— 若口径写错（按批全量数 degraded），
        // 这个断言在「拒收条目也带 degraded」时仍会通过，故这里显式再验一次总数口径。
        assertEquals(1L, stats.lowConfidencePending());
        assertEquals(2L, stats.pendingConfirm(), "低置信是待确认的子集，不能多于待确认总数");
        assertTrue(stats.lowConfidencePending() <= stats.pendingConfirm(),
                "低置信待确认条数必须不超过待确认总数");
    }

    @Test
    @DisplayName("拒收原因分布给出本地原因码，且按条数降序")
    void test_rejection_reason_distribution_is_sorted_and_coded() throws Exception {
        JdbcLedgerStore store = store();
        seedEightMessageBatch(store);
        // 再补两条同原因与一条别的原因，验证聚合与排序
        for (int i = 0; i < 2; i++) {
            long id = store.insertIfAbsent(LedgerEntry.inbound(
                    IdempotencyKey.of("ADT^A08", "CTRL-B18-100" + i, "P"), BATCH,
                    er7MissingPatientId())).ledgerId();
            store.transition(id, MessageState.RECEIVED, MessageState.REJECTED, "system",
                    ArchiveIngestService.REASON_PREFIX_REJECTED + "patient-id-missing");
        }
        long missingCtrl = store.insertIfAbsent(LedgerEntry.inbound(
                IdempotencyKey.of("ADT^A08", "CTRL-B18-2000", "P"), BATCH,
                er7MissingControlId())).ledgerId();
        store.transition(missingCtrl, MessageState.RECEIVED, MessageState.REJECTED, "system",
                ArchiveIngestService.REASON_PREFIX_REJECTED + "control-id-missing");

        BatchStats stats = BatchStats.of(store, BATCH);

        Map<String, Long> reasons = stats.rejectionReasons();
        assertEquals(3L, reasons.get("patient-id-missing"), "patient-id-missing 应累计 3 条");
        assertEquals(1L, reasons.get("control-id-missing"), "control-id-missing 应为 1 条");
        assertEquals(2, reasons.size(), "只应有这两种原因码");
        // 条数降序：patient-id-missing(3) 必须在 control-id-missing(1) 之前
        List<String> order = List.copyOf(reasons.keySet());
        assertEquals("patient-id-missing", order.get(0));
        assertEquals("control-id-missing", order.get(1));
    }

    @Test
    @DisplayName("分布表逐行显式出现，0 条的那几行也在")
    void test_display_lines_include_zero_rows_and_low_confidence() throws Exception {
        JdbcLedgerStore store = store();
        seedEightMessageBatch(store);

        List<String> lines = BatchStats.of(store, BATCH).displayLines();
        String all = String.join("\n", lines);

        for (MessageState s : BatchStats.MAIN_STATES) {
            assertTrue(all.contains(s.dbValue()),
                    "分布表必须逐行显式出现四态（含 0 条的行），缺 " + s.dbValue());
        }
        assertTrue(all.contains("低置信待确认（模型降级）：1"), "应给出低置信待确认数：" + all);
        assertTrue(all.contains("拒收原因分布"), "有拒收条目时应给出原因分布");
    }

    @Test
    @DisplayName("重投子态单列，不混进四态")
    void test_retry_substates_are_counted_separately() throws Exception {
        JdbcLedgerStore store = store();
        long id = store.insertIfAbsent(LedgerEntry.inbound(
                IdempotencyKey.of("ADT^A08", "CTRL-B19-0001", "P"), BATCH, er7("CTRL-B19-0001")))
                .ledgerId();
        store.transition(id, MessageState.RECEIVED, MessageState.PENDING_CONFIRM, "system", "await");
        store.transition(id, MessageState.PENDING_CONFIRM, MessageState.PENDING_RETRY, "system",
                "retry-queued");

        BatchStats stats = BatchStats.of(store, BATCH);

        assertEquals(1L, stats.total(), "重投中的条目仍属于本批");
        assertEquals(0L, stats.pendingConfirm(), "已进入重投子态就不再算待确认");
        assertEquals(1L, stats.retrying(), "重投子态应单列计数");
        assertEquals(1L, stats.retryStates().get(MessageState.PENDING_RETRY));
        assertTrue(stats.headline().contains("另有 1 条在重投中"),
                "总数与四态分项对不上时，一句话结论必须点明还有几条在重投：" + stats.headline());
    }

    @Test
    @DisplayName("不存在的批次返回全 0 分布而不是抛异常")
    void test_unknown_batch_returns_zero_distribution_not_exception() throws Exception {
        BatchStats stats = BatchStats.of(store(), "NO-SUCH-BATCH-20991231");

        assertEquals(0L, stats.total());
        for (MessageState s : BatchStats.MAIN_STATES) {
            assertEquals(0L, stats.counts().get(s), "未知批次下每个状态都应是 0：" + s);
        }
        assertTrue(stats.rejectionReasons().isEmpty());
        assertFalse(stats.allLanded(), "0 条的批次不构成「全部已落地」");
        assertTrue(String.join("\n", stats.displayLines()).contains("状态分布"),
                "0 条也要给出完整零分布，而不是空输出");
    }

    @Test
    @DisplayName("空批次号被拒：批次号是统计入口，不给默认批")
    void test_blank_batch_id_is_rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> BatchStats.of(store(), "  "));
    }
}