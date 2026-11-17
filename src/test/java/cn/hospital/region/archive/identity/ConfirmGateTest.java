package cn.hospital.region.archive.identity;

import cn.hospital.region.archive.ack.AckComposer;
import cn.hospital.region.archive.ledger.ArchiveIngestService;
import cn.hospital.region.archive.ledger.AuditWriter;
import cn.hospital.region.archive.ledger.IdempotencyGuard;
import cn.hospital.region.archive.ledger.JdbcLedgerStore;
import cn.hospital.region.archive.ledger.LedgerAuditChain;
import cn.hospital.region.archive.ledger.LedgerRow;
import cn.hospital.region.archive.ledger.LedgerSchema;
import cn.hospital.region.archive.ledger.MessageState;
import cn.hospital.region.archive.ledger.RevertService;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T30 验收 —— 确认门是「待确认 → 已落地」的唯一通道。
 *
 * <h2>这一组在验什么</h2>
 * <ul>
 *   <li><b>红线</b>：没有任何确认动作时，{@link PatientIndexWriter} 端口的 put 调用
 *       次数<b>恰好为 0</b> —— 收 8 条报文、重放 100 次、跑模型与硬校验，都不写；</li>
 *   <li><b>确认即生效</b>：点一次确认 → 一次出站写、状态到已落地、审计链完整；</li>
 *   <li><b>非法态直接拒</b>：已落地重复确认 / 拒收条目被确认 / 不存在的行，
 *       全部<b>不静默成功</b>，且在 {@code confirm_refusal} 留一行可查的痕；</li>
 *   <li><b>职责分离</b>：{@link ConfirmGate} 不含 {@code PatientIndexWriter} 字段
 *       （反射断言），确认门里发不出 HTTP；</li>
 *   <li><b>撤回无后门</b>：{@link ConfirmGate#revert} 转调 {@link RevertService}，
 *       审计链仍然连续（审计行数 == version）。</li>
 * </ul>
 *
 * <p><b>不用任何 mock 库</b>：H2 PG 兼容模式 + 真实 JDBC + 真实事务 + 真实迁移脚本
 * （含本任务的 V4）。「出站写调用次数」靠端口计数替身，其余一律真查库。
 */
class ConfirmGateTest {

    private static final String H2_URL =
            "jdbc:h2:mem:confirm_gate_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-10-20T08:00:00Z"), ZoneId.of("Asia/Shanghai"));

    private static final String LOCAL_AET = "CHSCOMMUNITY";
    private static final String REMOTE_AET = "REGIONAL_ARCHIVE";
    private static final String OPERATOR = "operator-007";

    private static final String VALID_ER7 = String.join("\r",
            "MSH|^~\\&|REGIONAL_ARCHIVE|FACILITY_A|ARCHIVE_IN|FACILITY_A|20261020090000"
                    + "||ADT^A01^ADT_A01|CTRL-T30-0001|P|2.5.1|||AL|NE|UNICODE UTF-8",
            "PID|1||PAT-0001^^^FACILITY_A^MR||张^三||19780315|M",
            "");

    /** 缺 MSH-10 的报文：入账即拒收，用来验「拒收条目不能被确认」。 */
    private static final String REJECTED_ER7 = String.join("\r",
            "MSH|^~\\&|REGIONAL_ARCHIVE|FACILITY_A|ARCHIVE_IN|FACILITY_A|20261020090000"
                    + "||ADT^A01^ADT_A01||P|2.5.1|||AL|NE|UNICODE UTF-8",
            "PID|1||PAT-0002^^^FACILITY_A^MR||李^四||19791020|F",
            "");

    private JdbcLedgerStore store;
    private CountingPatientIndexWriter patientIndex;
    private ArchiveIngestService ingest;
    private RevertService revertService;
    private ConfirmRefusalJournal refusals;
    private ConfirmGate gate;

    // =====================================================================
    // 夹具
    // =====================================================================

    private static Connection openConnection() throws SQLException {
        return DriverManager.getConnection(H2_URL, "sa", "");
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
    void setUp() throws SQLException {
        for (String table : new String[]{"confirm_refusal", "message_audit", "message_ledger"}) {
            try (Connection conn = openConnection();
                 PreparedStatement ps = conn.prepareStatement("DELETE FROM " + table)) {
                ps.executeUpdate();
            }
        }
        store = new JdbcLedgerStore(ConfirmGateTest::openConnection, FIXED_CLOCK,
                AuditWriter.jdbc(FIXED_CLOCK));
        patientIndex = new CountingPatientIndexWriter();
        ingest = new ArchiveIngestService(new AckComposer(LOCAL_AET, REMOTE_AET, FIXED_CLOCK),
                store, new IdempotencyGuard(store), patientIndex);
        revertService = new RevertService(store);
        refusals = ConfirmRefusalJournal.jdbc(FIXED_CLOCK, ConfirmGateTest::openConnection);
        gate = new ConfirmGate(store, ingest, revertService, refusals,
                new JdbcPatientKeyStore(ConfirmGateTest::openConnection));
    }

    private byte[] er7(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** 收一条报文，返回台账行 id。 */
    private long receive(String text) throws Exception {
        ArchiveIngestService.IngestOutcome outcome = ingest.ingest(er7(text));
        return outcome.ledgerId();
    }

    /** 一条合法的「点确认」决定：区域主键 PAT-0001 + 院内主键 LOCMRN-0001。 */
    private static ConfirmDecision decision() {
        return ConfirmDecision.fromCandidate(OPERATOR, "PAT-0001", "LOCMRN-0001", 1);
    }

    private int refusalRowCount() throws SQLException {
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM confirm_refusal");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private List<String> refusalRows() throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT ledger_id, actual_state, operator, reason FROM confirm_refusal ORDER BY id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                rows.add(rs.getLong(1) + "|" + rs.getString(2) + "|" + rs.getString(3)
                        + "|" + rs.getString(4));
            }
        }
        return rows;
    }

    private int auditRowCount(long ledgerId) throws SQLException {
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM message_audit WHERE ledger_id = ?")) {
            ps.setLong(1, ledgerId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /** 按控制号数台账行数（{@code DELETE} 只清行不清 IDENTITY 序列，故 id 不可跨用例比较）。 */
    private int rowCountByControlId(String controlId) throws SQLException {
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM message_ledger WHERE control_id = ?")) {
            ps.setString(1, controlId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    // =====================================================================
    // 1. 红线：没有确认动作，出站写恰好 0 次
    // =====================================================================

    @Test
    @DisplayName("红线：收报文、重放、跑硬校验全程不写患者主索引，确认门不被调用时 put 次数恒为 0")
    void test_no_confirm_means_zero_writes() throws Exception {
        // 8 条各不相同的合法报文：入账 → 待确认，没有一条被确认过
        for (int i = 1; i <= 8; i++) {
            long id = receive(VALID_ER7.replace("CTRL-T30-0001", "CTRL-T30-B" + i));
            assertEquals(MessageState.PENDING_CONFIRM, store.findById(id).orElseThrow().state());
        }

        // 同一条再重放 100 次（真实幂等键，不换控制号）
        for (int i = 0; i < 100; i++) {
            ingest.ingest(er7(VALID_ER7));
        }

        assertEquals(0, patientIndex.invocationCount(),
                "没有任何人工确认动作时，患者主索引写端口的调用次数必须恰好为 0");
        assertTrue(patientIndex.neverCalled());
        assertEquals(0, refusalRowCount(), "没有确认动作就没有拒绝留痕");
    }

    @Test
    @DisplayName("红线：只有 confirm() 能让 put 次数变成 1，其余路径碰不到这个端口")
    void test_only_confirm_triggers_the_write() throws Exception {
        long id = receive(VALID_ER7);
        assertEquals(0, patientIndex.invocationCount(), "入账本身不写");

        ConfirmOutcome out = gate.confirm(id, decision());

        assertTrue(out.confirmed());
        assertTrue(out.wrotePatientIndex());
        assertEquals(1, patientIndex.invocationCount());
        assertEquals("PAT-0001", patientIndex.invocations().get(0).patientRef());
        assertEquals("LOCMRN-0001", patientIndex.invocations().get(0).localMrn());
        assertEquals(store.findById(id).orElseThrow().batchId(),
                patientIndex.invocations().get(0).sourceBatch(), "出站第三字段是来源批次号");
        assertEquals(MessageState.LANDED, out.state());
    }

    // =====================================================================
    // 2. 非法态直接拒绝，且不得静默成功
    // =====================================================================

    @Test
    @DisplayName("已落地条目被重复确认：拒绝、零写入、留一行 ALREADY_LANDED 痕，状态与审计不变")
    void test_confirm_on_non_pending_state_is_rejected() throws Exception {
        long id = receive(VALID_ER7);
        assertTrue(gate.confirm(id, decision()).confirmed());
        assertEquals(1, patientIndex.invocationCount());

        LedgerRow landed = store.findById(id).orElseThrow();
        int auditBefore = auditRowCount(id);

        // 再点一次确认（界面把收尾后的条目又摆上确认台）
        ConfirmOutcome second = gate.confirm(id, decision());

        assertFalse(second.confirmed(), "已落地的条目不得重复确认");
        assertFalse(second.wrotePatientIndex());
        assertEquals(ConfirmGate.Refusal.ALREADY_LANDED, second.refusal());
        assertEquals(MessageState.LANDED, second.state(), "报告点击那一刻的实际状态，不假装成了别的态");

        // 台账、审计、出站写三者一个字节都没动
        assertEquals(landed.state(), store.findById(id).orElseThrow().state());
        assertEquals(landed.version(), store.findById(id).orElseThrow().version());
        assertEquals(auditBefore, auditRowCount(id), "被拒不写迁移审计行");
        assertEquals(1, patientIndex.invocationCount(), "被拒不产生第二次出站写");

        // 留痕可查：谁、哪条、当时什么态、为什么
        assertEquals(List.of(id + "|LANDED|" + OPERATOR + "|ALREADY_LANDED"), refusalRows());
        assertTrue(LedgerAuditChain.verify(openConnection(), id).intact(),
                "拒绝留痕不得破坏审计链（因此它另立 confirm_refusal 表而不是写进 message_audit）");
    }

    @Test
    @DisplayName("拒收条目被确认：拒绝、零写入，留 NOT_PENDING_CONFIRM 痕")
    void test_confirming_a_rejected_row_is_refused() throws Exception {
        long id = receive(REJECTED_ER7);
        assertEquals(MessageState.REJECTED, store.findById(id).orElseThrow().state());
        int auditBefore = auditRowCount(id);

        ConfirmOutcome out = gate.confirm(id,
                new ConfirmDecision(OPERATOR, IdentityKeyBinding.bound("PAT-0002", "LOCMRN-0002"),
                        "manual-entry", "手工核过一遍"));

        assertFalse(out.confirmed());
        assertEquals(ConfirmGate.Refusal.NOT_PENDING_CONFIRM, out.refusal());
        assertEquals(MessageState.REJECTED, out.state());
        assertEquals(0, patientIndex.invocationCount(), "拒收条目绝不写患者主索引");
        assertEquals(auditBefore, auditRowCount(id), "被拒不写迁移审计行");
        assertTrue(refusalRows().get(0).contains("|REJECTED|" + OPERATOR + "|NOT_PENDING_CONFIRM"));
    }

    @Test
    @DisplayName("不存在的台账行：拒绝并留痕，状态报 null 而不是拿某个状态顶替")
    void test_confirming_a_missing_row_is_refused() throws Exception {
        ConfirmOutcome out = gate.confirm(999_999_999L, decision());

        assertFalse(out.confirmed());
        assertEquals(ConfirmGate.Refusal.LEDGER_ROW_MISSING, out.refusal());
        assertNull(out.state(), "没有这一行就没有「实际状态」，不拿 RECEIVED 之类的态顶替");
        assertEquals(0, patientIndex.invocationCount());
        assertEquals(List.of("999999999|" + ConfirmRefusalJournal.NO_ROW_STATE
                + "|" + OPERATOR + "|LEDGER_ROW_MISSING"), refusalRows());
    }

    @Test
    @DisplayName("非法 ledgerId 与空操作人：入参侧就拒，一个字都不写")
    void test_bad_arguments_are_rejected_before_any_write() throws Exception {
        assertThrows(NullPointerException.class, () -> gate.confirm(1L, null));

        long id = receive(VALID_ER7);
        // 空操作人在 ConfirmDecision 构造时就被拒：没有人的确认在审计行里等于匿名操作
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new ConfirmDecision("  ", IdentityKeyBinding.bound("PAT-0001", "LOCMRN-0001"),
                        "manual-entry", ""));
        assertTrue(e.getMessage().contains("operator"));
        assertEquals(0, patientIndex.invocationCount());
        assertEquals(MessageState.PENDING_CONFIRM, store.findById(id).orElseThrow().state());
    }

    // =====================================================================
    // 3. 双主键绑定：区域主键与院内主键各写各的列
    // =====================================================================

    @Test
    @DisplayName("确认后两套主键分列落在同一行：patient_ref=区域主键，local_mrn=院内主键")
    void test_confirm_binds_both_primary_keys() throws Exception {
        long id = receive(VALID_ER7);
        assertNull(new JdbcPatientKeyStore(ConfirmGateTest::openConnection).read(id).orElse(null),
                "入账时 patient_ref 尚未绑定");

        gate.confirm(id, decision());

        IdentityKeyBinding bound = new JdbcPatientKeyStore(ConfirmGateTest::openConnection)
                .read(id).orElseThrow();
        assertEquals("PAT-0001", bound.patientRef());
        assertEquals("LOCMRN-0001", bound.localMrn());

        // 直接查列，不信对象回读
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT patient_ref, local_mrn FROM message_ledger WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("PAT-0001", rs.getString(1));
                assertEquals("LOCMRN-0001", rs.getString(2));
            }
        }
    }

    @Test
    @DisplayName("决策里混装姓名当主键被拒：主键列只放编号")
    void test_local_mrn_must_not_be_a_name() {
        assertThrows(IllegalArgumentException.class,
                () -> new ConfirmDecision(OPERATOR,
                        IdentityKeyBinding.bound("PAT-0001", "张三"), "manual-entry", ""));
    }

    // =====================================================================
    // 4. 职责分离：确认门里没有网络调用
    // =====================================================================

    @Test
    @DisplayName("ConfirmGate 不持有 PatientIndexWriter 字段：确认门自己发不出 HTTP")
    void test_confirm_gate_holds_no_network_port() {
        for (Field f : ConfirmGate.class.getDeclaredFields()) {
            assertFalse(PatientIndexWriter.class.isAssignableFrom(f.getType()),
                    "ConfirmGate 不得直接持有出站端口（确认后由接入主路径写），实际持有：" + f.getName());
        }
        assertNotNull(gate.revertService(), "撤回走 T17 的 RevertService 实例");
    }

    @Test
    @DisplayName("撤回走同一个 RevertService：确认 → 撤回 → 再确认，审计链连续且不重复写 HIS")
    void test_revert_goes_through_revert_service_and_reconfirm_does_not_rewrite() throws Exception {
        long id = receive(VALID_ER7);
        gate.confirm(id, decision());
        assertEquals(1, patientIndex.invocationCount());

        RevertService.RevertOutcome back = gate.revert(id, OPERATOR, "合错人了，重新挑");
        assertTrue(back.reverted());
        assertEquals(MessageState.PENDING_CONFIRM, back.state());
        assertEquals(1, patientIndex.invocationCount(), "撤回不写患者主索引，也不删 HIS 侧记录");

        // 挑同一个院内主键再确认：确认生效，但不重复写 HIS
        ConfirmOutcome again = gate.confirm(id, decision());
        assertTrue(again.confirmed());
        assertFalse(again.wrotePatientIndex(), "同一条报文、同一个院内主键，HIS 里只该有一条映射");
        assertEquals(1, patientIndex.invocationCount());

        assertTrue(LedgerAuditChain.verify(openConnection(), id).intact());
    }

    // =====================================================================
    // 5. 并发确认：两个信息员同时点同一条
    // =====================================================================

    @Test
    @DisplayName("两个信息员同时点同一条：一人生效、一人被拒，出站写恰好 1 次")
    void test_concurrent_confirm_produces_one_write_and_one_refusal() throws Exception {
        long id = receive(VALID_ER7);

        int threads = 2;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<ConfirmOutcome> results = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        synchronized (results) {
                            results.add(gate.confirm(id, decision()));
                        }
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS), "两个确认线程都应在 30 秒内返回");
        } finally {
            pool.shutdownNow();
        }

        assertEquals(2, results.size());
        long confirmedCount = results.stream().filter(ConfirmOutcome::confirmed).count();
        assertEquals(1, confirmedCount, "同一条报文只能被确认一次生效");
        assertEquals(1, results.stream().filter(ConfirmOutcome::refused).count());
        assertEquals(1, patientIndex.invocationCount(), "出站写恰好 1 次，不因并发多写");
        assertTrue(LedgerAuditChain.verify(openConnection(), id).intact());
    }

    // =====================================================================
    // 6. 写失败：状态退回待确认，可重放
    // =====================================================================

    @Test
    @DisplayName("HIS 写失败：状态退回待确认、原文与幂等键保留，重放同一报文后确认可成功")
    void test_write_failure_keeps_row_replayable() throws Exception {
        long id = receive(VALID_ER7);
        patientIndex.failNextWith("对端 500：HIS 侧暂时不可写");

        assertThrows(PatientIndexWriteException.class, () -> gate.confirm(id, decision()));

        LedgerRow after = store.findById(id).orElseThrow();
        assertEquals(MessageState.PENDING_CONFIRM, after.state(), "写失败绝不留在已落地");
        assertTrue(after.rawPayload().contains("PID|1||PAT-0001"), "报文原文保留，可重放");
        assertEquals("CTRL-T30-0001", after.key().controlId(), "幂等键保留");

        // 重放同一报文（对端重推）不应产生第二条台账行
        ingest.ingest(er7(VALID_ER7));
        assertEquals(1, rowCountByControlId("CTRL-T30-0001"), "重放不新增台账行");

        // 再点一次确认：出站写成功
        ConfirmOutcome retry = gate.confirm(id, decision());
        assertTrue(retry.confirmed());
        assertTrue(retry.wrotePatientIndex());
        assertEquals(2, patientIndex.invocationCount(), "两次调用：一次失败一次成功");
    }

    // =====================================================================
    // 7. 留痕不阻断业务
    // =====================================================================

    @Test
    @DisplayName("留痕口失效时拒绝结论照常成立：补证据失败不得把拒绝变成报错")
    void test_refusal_survives_a_broken_journal() throws Exception {
        long id = receive(VALID_ER7);
        gate.confirm(id, decision());
        int auditBefore = auditRowCount(id);

        // 把连接提供者换成必然失败的实现：库连不上，留痕写不进去
        ConfirmGate broken = new ConfirmGate(store, ingest, revertService,
                ConfirmRefusalJournal.jdbc(FIXED_CLOCK, () -> {
                    throw new SQLException("库连不上，留痕写不进去");
                }), null);

        ConfirmOutcome out = broken.confirm(id, decision());
        assertFalse(out.confirmed(), "留痕失败不改变拒绝结论");
        assertEquals(ConfirmGate.Refusal.ALREADY_LANDED, out.refusal());
        assertEquals(1, patientIndex.invocationCount());
        assertEquals(auditBefore, auditRowCount(id));
        assertEquals(0, refusalRowCount(),
                "留痕写不进去就是没有证据行——但拒绝结论本身已经成立，不因此报错");
    }

    @Test
    @DisplayName("确认结果自相矛盾时构造器就拒：confirmed 与 refusal 不得同时成立")
    void test_outcome_cannot_be_self_contradictory() {
        assertThrows(IllegalArgumentException.class, () -> new ConfirmOutcome(
                true, MessageState.LANDED, true, "PAT-0001", "LOCMRN-0001",
                ConfirmGate.Refusal.ALREADY_LANDED, "既成了又被拒"));
        assertThrows(IllegalArgumentException.class, () -> new ConfirmOutcome(
                false, MessageState.LANDED, false, "", "", null, "被拒了却没有原因码"));
    }
}