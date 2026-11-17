package cn.hospital.region.archive.ledger;

import cn.hospital.region.archive.ack.AckComposer;
import cn.hospital.region.archive.identity.CountingPatientIndexWriter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T17 · 撤回重审的验收 —— 「信息员点完确认还能不能改」的可复核答案。
 *
 * <h2>这一组在验什么</h2>
 * <ul>
 *   <li><b>撤回是一次正常的业务状态迁移</b>：{@code LANDED → PENDING_CONFIRM} 成功，
 *       并且审计链里留下 {@code reason=revert:} 与操作人 —— 而不是删库改表；</li>
 *   <li><b>撤回不放行该放的以外</b>：{@code REJECTED → PENDING_CONFIRM} 虽是状态机合法边，
 *       但业务上必须被拒（拒收结论已以 AE/AR 回给区域平台）；</li>
 *   <li><b>撤回不清空原文与幂等键</b>：撤回后报文仍可重投（T32）、可对账（T34）、可再确认；</li>
 *   <li><b>撤回再确认不重复写 HIS</b>：复用 T15/T16 的口径，出站调用仍恰好 1 次。</li>
 * </ul>
 *
 * <p><b>不用任何 mock 库</b>：H2 PG 兼容模式 + 真实 JDBC 事务 + 真实乐观锁 CAS。
 */
class RevertServiceTest {

    private static final String H2_URL =
            "jdbc:h2:mem:revert_service_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-10-20T06:00:00Z"), ZoneId.of("Asia/Shanghai"));

    private static final String LOCAL_AET = "CHSCOMMUNITY";
    private static final String REMOTE_AET = "REGIONAL_ARCHIVE";

    private static final String MSG_TYPE = "ADT^A01^ADT_A01";
    private static final String CTRL_ID = "CTRL-T17-0001";
    private static final String PROC_ID = "P";
    private static final String OPERATOR = "operator-007";

    private static final String VALID_ER7 = String.join("\r",
            "MSH|^~\\&|REGIONAL_ARCHIVE|FACILITY_A|ARCHIVE_IN|FACILITY_A|20261020090000"
                    + "||ADT^A01^ADT_A01|CTRL-T17-0001|P|2.5.1|||AL|NE|UNICODE UTF-8",
            "PID|1||PAT-0001^^^FACILITY_A^MR||张^三||19780315|M",
            "");

    /** 缺 MSH-10 的报文：结构校验不过，入账即拒收（AR/AE），用来验「拒收不能撤回」。 */
    private static final String REJECTED_ER7 = String.join("\r",
            "MSH|^~\\&|REGIONAL_ARCHIVE|FACILITY_A|ARCHIVE_IN|FACILITY_A|20261020090000"
                    + "||ADT^A01^ADT_A01||P|2.5.1|||AL|NE|UNICODE UTF-8",
            "PID|1||PAT-0002^^^FACILITY_A^MR||李^四||19791020|F",
            "");

    private JdbcLedgerStore store;
    private CountingPatientIndexWriter patientIndex;
    private ArchiveIngestService ingest;
    private RevertService revertService;

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

    /**
     * 每个用例从<b>空台账</b>起步 —— 与 T16 同一理由：H2 带 {@code DB_CLOSE_DELAY=-1}
     * 跨方法存活，不清表会让「这条报文第一次到达」变成 DUPLICATE，
     * 断言结果与撤回行为无关。
     */
    @BeforeEach
    void setUp() throws SQLException {
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM message_audit")) {
            ps.executeUpdate();
        }
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM message_ledger")) {
            ps.executeUpdate();
        }
        store = new JdbcLedgerStore(RevertServiceTest::openConnection, FIXED_CLOCK,
                AuditWriter.jdbc(FIXED_CLOCK));
        patientIndex = new CountingPatientIndexWriter();
        ingest = new ArchiveIngestService(new AckComposer(LOCAL_AET, REMOTE_AET, FIXED_CLOCK),
                store, new IdempotencyGuard(store), patientIndex);
        revertService = new RevertService(store);
    }

    /** 走到「已落地」：入账 → 人工确认 → 出站写一次。返回台账行主键。 */
    private long ingestAndLand() throws Exception {
        ingest.ingest(VALID_ER7.getBytes(StandardCharsets.UTF_8));
        long id = ledgerIdOf(CTRL_ID);
        ArchiveIngestService.LandOutcome landed =
                ingest.land(MSG_TYPE, CTRL_ID, PROC_ID, OPERATOR, "PAT-0001", "MRN-884512");
        assertTrue(landed.landed(), "前置条件：这条应先落地：" + landed.note());
        return id;
    }

    // =====================================================================
    // 1. 撤回是正常的业务状态迁移：LANDED → PENDING_CONFIRM + 审计 reason=revert
    // =====================================================================

    @Test
    @DisplayName("撤回：已落地 → 待确认成功，审计行带 reason=revert 与操作人，原文与幂等键原样保留")
    void test_landed_to_pending_confirm_writes_audit_reason_revert() throws Exception {
        long id = ingestAndLand();
        assertEquals(MessageState.LANDED, readState(id));
        assertEquals(2L, countAuditRows(id), "落地这一条走过两段：RECEIVED→待确认、待确认→已落地");
        assertEquals(1, patientIndex.invocationCount());

        RevertService.RevertOutcome outcome =
                revertService.revert(id, OPERATOR, "居民电话核实后发现姓名与档案对不上，重新挑身份");

        assertTrue(outcome.reverted(), "已落地的条目必须可撤回：" + outcome.note());
        assertEquals(MessageState.LANDED, outcome.from());
        assertEquals(MessageState.PENDING_CONFIRM, outcome.state());

        // 台账：状态退回待确认，版本自增（乐观锁靠它）
        assertEquals(MessageState.PENDING_CONFIRM, readState(id), "撤回后应停在待确认");
        assertEquals(3L, versionOf(id), "三段迁移后版本应为 3（每段迁移自增 1）");

        // 审计：多一行，且这行的 from/to/reason/operator 逐字对得上
        AuditRow revert = lastAuditRow(id);
        assertEquals(MessageState.LANDED, revert.from());
        assertEquals(MessageState.PENDING_CONFIRM, revert.to());
        assertEquals(OPERATOR, revert.operator(), "撤回人来自 token，必须逐字落审计");
        assertTrue(revert.reason().startsWith(RevertService.REASON_PREFIX),
                "撤回审计行的 reason 应带固定前缀 " + RevertService.REASON_PREFIX
                        + "，实际=" + revert.reason());
        assertTrue(revert.reason().contains("姓名与档案对不上"), "撤回理由应逐字落审计");
        assertEquals(3L, countAuditRows(id), "撤回本身也是一次迁移，审计链 +1");

        // 审计链仍然完整：撤回顾问的是「这是不是一次正常迁移」，答案必须是
        assertTrue(LedgerAuditChain.verify(openConnection(), id).intact(),
                "撤回后审计链必须仍然自洽，撤回不得留下断点");

        // 原文与幂等键不清空 —— 撤回之后还能重投（T32）、能对账（T34）、能再确认
        assertEquals(VALID_ER7.trim(), readRawPayload(id).trim(), "撤回不得改写报文原文");
        assertEquals(MSG_TYPE, readColumn(id, "message_type"));
        assertEquals(CTRL_ID, readColumn(id, "control_id"));

        // 撤回本身不写患者主索引（本类根本没有 PatientIndexWriter 字段）
        assertEquals(1, patientIndex.invocationCount(), "撤回不得触发任何出站写入");
    }

    // =====================================================================
    // 2. 撤回再确认：不产生第二条 HIS 写入（复用 T15/T16 口径）
    // =====================================================================

    @Test
    @DisplayName("撤回再确认：仍命中同一幂等键，患者主索引出站调用仍恰好 1 次")
    void test_revert_then_reconfirm_does_not_duplicate_his_write() throws Exception {
        long id = ingestAndLand();
        assertEquals(1, patientIndex.invocationCount(), "前置条件：确认一次写一次");

        RevertService.RevertOutcome reverted =
                revertService.revert(id, OPERATOR, "误把同名的另一位居民当成了本人");
        assertTrue(reverted.reverted());
        assertEquals(MessageState.PENDING_CONFIRM, readState(id));
        assertEquals(1, patientIndex.invocationCount(), "撤回不得写出站写");

        // 重新确认：这一条走的是与首次确认完全相同的 land 路径
        ArchiveIngestService.LandOutcome reLand =
                ingest.land(MSG_TYPE, CTRL_ID, PROC_ID, OPERATOR, "PAT-0001", "MRN-884512");

        assertTrue(reLand.landed(), "撤回后应能重新确认：" + reLand.note());
        assertEquals(MessageState.LANDED, readState(id));

        // 红线：再确认不得让同一位居民在 HIS 里出现第二条档案
        assertEquals(1, patientIndex.invocationCount(),
                "撤回再确认后出站调用必须仍为 1 次，实际 " + patientIndex.invocationCount());
        CountingPatientIndexWriter.Invocation only = patientIndex.invocations().get(0);
        assertEquals("PAT-0001", only.patientRef());
        assertEquals("MRN-884512", only.localMrn());

        // 台账恒 1 行、审计链完整：入账 → 落地 → 撤回 → 再确认，共 4 段迁移
        assertEquals(1L, countLedgerRows(CTRL_ID), "台账仍只有一行");
        assertEquals(4L, countAuditRows(id), "四段迁移 = 四条审计");
        LedgerAuditChain.Verdict verdict = LedgerAuditChain.verify(openConnection(), id);
        assertTrue(verdict.intact(), "审计链应完整：" + verdict.detail());
    }

    // =====================================================================
    // 2b. 撤回真正要解决的场景：换一个院内主键重确认 → 必须真的写出站写
    // =====================================================================

    @Test
    @DisplayName("改判重确认：换一个院内主键必须真写一条新映射（撤回存在的理由）")
    void test_revert_then_reconfirm_with_different_mrn_does_write_correction() throws Exception {
        long id = ingestAndLand();
        assertEquals(1, patientIndex.invocationCount());
        assertEquals("MRN-884512", readColumn(id, "local_mrn"), "落地时应记住院内主键");

        // 信息员发现上次把同名的另一位居民当成了本人 → 撤回重挑身份
        assertTrue(revertService.revert(id, OPERATOR, "误把同名的另一位居民当成了本人").reverted());
        assertEquals("MRN-884512", readColumn(id, "local_mrn"), "撤回不清空已落地的主键");

        // 重新确认，这次挑的是**另一个**院内主键 —— 这是一次纠正，必须写出去
        ArchiveIngestService.LandOutcome corrected =
                ingest.land(MSG_TYPE, CTRL_ID, PROC_ID, OPERATOR, "PAT-0001", "MRN-773301");

        assertTrue(corrected.landed(), "改判后的确认应生效：" + corrected.note());
        assertTrue(corrected.wrotePatientIndex(), "换了院内主键就必须真写一条新映射");
        assertEquals(2, patientIndex.invocationCount(),
                "纠正这一次必须产生第二次出站写，否则 HIS 会永远留着第一次那个错映射");
        assertEquals("MRN-773301", readColumn(id, "local_mrn"), "台账记下纠正后的院内主键");
        assertEquals("MRN-773301", patientIndex.invocations().get(1).localMrn());
        assertEquals(MessageState.LANDED, readState(id));
        assertTrue(LedgerAuditChain.verify(openConnection(), id).intact());
    }

    @Test
    @DisplayName("同目标重确认：确认生效（状态回到已落地、审计 +1），但不出第二次出站写")
    void test_same_target_reconfirm_restores_landed_without_duplicate_write() throws Exception {
        long id = ingestAndLand();

        assertTrue(revertService.revert(id, OPERATOR, "误撤回").reverted());
        long auditsAfterRevert = countAuditRows(id);

        ArchiveIngestService.LandOutcome again =
                ingest.land(MSG_TYPE, CTRL_ID, PROC_ID, OPERATOR, "PAT-0001", "MRN-884512");

        assertTrue(again.landed(), "同一个身份映射再确认，确认应当生效：" + again.note());
        assertFalse(again.wrotePatientIndex(),
                "同一条报文、同一个院内主键在 HIS 里只该有一条映射，不得重复写出站写");
        assertEquals(1, patientIndex.invocationCount(), "出站写全程仍为 1 次");
        assertEquals(MessageState.LANDED, readState(id), "状态回到已落地：信息员点完确认不该还停在待确认");
        assertEquals(auditsAfterRevert + 1, countAuditRows(id), "这次确认也是一次迁移，审计 +1");
    }

    // =====================================================================
    // 3. 业务闸：REJECTED 不放行（状态机合法，但业务不许）
    // =====================================================================

    @Test
    @DisplayName("业务闸：拒收条目不可撤回——边合法但拒收已回给对端，事后翻案会让两端各执一词")
    void test_rejected_to_pending_confirm_is_refused() throws Exception {
        ingest.ingest(REJECTED_ER7.getBytes(StandardCharsets.UTF_8));
        long id = ledgerIdOf("");   // 缺 MSH-10，控制号为空串
        assertEquals(MessageState.REJECTED, readState(id), "前置条件：这条因缺 MSH-10 被拒收");
        long auditsBefore = countAuditRows(id);
        long versionBefore = versionOf(id);

        RevertNotAllowedException e = assertThrows(RevertNotAllowedException.class,
                () -> revertService.revert(id, OPERATOR, "拒收判错了"),
                "REJECTED → PENDING_CONFIRM 在状态机里是合法边，但业务上必须被业务闸拦下");

        assertEquals(id, e.ledgerId());
        assertEquals(MessageState.REJECTED, e.actualState());
        assertTrue(e.getMessage().contains("重投队列"),
                "拒绝理由应直接给出该走的路（发一条新报文），实际=" + e.getMessage());

        // 失败不静默改数据：状态、版本、审计行数一个字节都不变
        assertEquals(MessageState.REJECTED, readState(id));
        assertEquals(versionBefore, versionOf(id));
        assertEquals(auditsBefore, countAuditRows(id));
        assertTrue(patientIndex.neverCalled());
    }

    // =====================================================================
    // 4. 其余五态逐条被拒 + 台账行不存在
    // =====================================================================

    @Test
    @DisplayName("业务闸：其余五态逐条被拒，拒绝理由各不相同且能直接照着办")
    void test_every_other_state_is_refused_with_its_own_reason() {
        // REVERTABLE_FROM 的补集：这些态一条都不该被放行
        for (MessageState state : MessageState.values()) {
            if (RevertService.REVERTABLE_FROM.contains(state)) {
                continue;
            }
            String reason = RevertService.explainNotRevertable(state);
            assertFalse(reason.isBlank(), state + " 必须有一条可照着办的拒绝理由");
            // 两两互不相同（拿 RECEIVED 当基准；它自己不比自己）
            for (MessageState other : MessageState.values()) {
                if (other == state || RevertService.REVERTABLE_FROM.contains(other)) {
                    continue;
                }
                assertNotEquals(RevertService.explainNotRevertable(other), reason,
                        "不同来源态应给不同理由，否则排障会被带偏：" + state + " vs " + other);
            }
        }

        // 已落地的可撤回、待确认的可补充依据 —— 集合本身就是业务闸，不要靠状态机表里恰好有那条边
        assertEquals(2, RevertService.REVERTABLE_FROM.size());
        assertTrue(RevertService.REVERTABLE_FROM.contains(MessageState.LANDED));
        assertTrue(RevertService.REVERTABLE_FROM.contains(MessageState.PENDING_CONFIRM));
        assertFalse(RevertService.REVERTABLE_FROM.contains(MessageState.REJECTED));
    }

    @Test
    @DisplayName("业务闸：台账里没有这一行时拒绝，而不是静默成功")
    void test_missing_ledger_row_is_refused() {
        RevertNotAllowedException e = assertThrows(RevertNotAllowedException.class,
                () -> revertService.revert(999999L, OPERATOR, "撤回一条不存在的"));
        assertEquals(999999L, e.ledgerId());
        assertNull(e.actualState(), "行不存在时实际状态为 null，区别于「存在但不可撤」");
        assertTrue(e.getMessage().contains("<行不存在>"));
    }

    // =====================================================================
    // 5. 待确认 → 待确认：补充依据（唯一自环），状态不前进但留痕
    // =====================================================================

    @Test
    @DisplayName("补充依据：待确认 → 待确认自环合法，状态不前进但审计 +1，版本自增")
    void test_pending_confirm_self_loop_appends_evidence_without_advancing() throws Exception {
        ingest.ingest(VALID_ER7.getBytes(StandardCharsets.UTF_8));
        long id = ledgerIdOf(CTRL_ID);
        assertEquals(MessageState.PENDING_CONFIRM, readState(id));
        assertEquals(1L, countAuditRows(id), "前置条件：入账推进过一次");

        RevertService.RevertOutcome outcome =
                revertService.revert(id, OPERATOR, "打了回居民电话核过姓名，街道出了协助核对单");

        assertTrue(outcome.reverted());
        assertEquals(MessageState.PENDING_CONFIRM, outcome.from());
        assertEquals(MessageState.PENDING_CONFIRM, outcome.state());
        assertTrue(outcome.note().contains("业务状态未前进"), "自环的结论应明说状态没前进");

        assertEquals(MessageState.PENDING_CONFIRM, readState(id), "业务状态确实没前进");
        assertEquals(2L, versionOf(id), "但版本自增（乐观锁不能因此失去区分力）");
        assertEquals(2L, countAuditRows(id), "补充依据也留痕");

        AuditRow added = lastAuditRow(id);
        assertEquals(MessageState.PENDING_CONFIRM, added.from());
        assertEquals(MessageState.PENDING_CONFIRM, added.to());
        assertEquals(OPERATOR, added.operator());
        assertTrue(added.reason().startsWith(RevertService.REASON_PREFIX));
        assertTrue(LedgerAuditChain.verify(openConnection(), id).intact(),
                "自环之后审计链仍须自洽（from→to 都在合法边表内）");

        assertTrue(patientIndex.neverCalled(), "补充依据不得触发任何出站写入");
    }

    // =====================================================================
    // 6. 乐观锁：两个信息员同时撤回，只有一个生效
    // =====================================================================

    @Test
    @DisplayName("乐观锁：两个信息员同时撤回同一条，只有一个生效，其余在写之前被拒")
    void test_concurrent_reverts_exactly_one_wins() throws Exception {
        long id = ingestAndLand();
        long auditsBefore = countAuditRows(id);

        int threads = 2;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ConcurrentLinkedQueue<RevertService.RevertOutcome> wins = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Throwable> refused = new ConcurrentLinkedQueue<>();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        wins.add(revertService.revert(id, OPERATOR, "并发撤回"));
                    } catch (Throwable e) {
                        refused.add(e);
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(60, TimeUnit.SECONDS), "两个线程应在 60s 内跑完");
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, wins.size(), "只应有一个撤回真正生效");
        assertEquals(1, refused.size(), "另一个必须在写之前被拒");
        Throwable loser = refused.peek();
        assertTrue(loser instanceof ConcurrentLedgerUpdateException
                        || loser instanceof LedgerStateConflictException,
                "落败方应拿到并发/状态冲突异常而不是别的东西，实际=" + loser);

        assertEquals(MessageState.PENDING_CONFIRM, readState(id));
        assertEquals(auditsBefore + 1, countAuditRows(id), "只有生效的那次迁移写了一条审计");
        assertTrue(LedgerAuditChain.verify(openConnection(), id).intact(),
                "并发撤回不得在审计链上留下断点");
        assertEquals(1, patientIndex.invocationCount(), "并发撤回不产生任何额外出站写");
    }

    // =====================================================================
    // 7. 无操作人 / 无理由的请求在写之前就被拒
    // =====================================================================

    @Test
    @DisplayName("入参校验：operator 与 reason 为空白时拒绝，台账与审计一个字节都不变")
    void test_blank_operator_or_reason_is_refused_before_any_write() throws Exception {
        long id = ingestAndLand();
        long versionBefore = versionOf(id);
        long auditsBefore = countAuditRows(id);

        for (String[] bad : List.of(
                new String[]{null, "理由"},
                new String[]{"", "理由"},
                new String[]{"   ", "理由"},
                new String[]{OPERATOR, null},
                new String[]{OPERATOR, ""},
                new String[]{OPERATOR, "  "})) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> revertService.revert(id, bad[0], bad[1]),
                    "operator=" + bad[0] + " / reason=" + bad[1] + " 必须被拒");
            assertTrue(e.getMessage().contains("不得为空白"));
        }

        assertEquals(versionBefore, versionOf(id), "被拒的请求不得推进版本");
        assertEquals(auditsBefore, countAuditRows(id), "被拒的请求不得写审计");
        assertEquals(MessageState.LANDED, readState(id));
        assertEquals(1, patientIndex.invocationCount());
    }

    // =====================================================================
    // 查询辅助
    // =====================================================================

    private record AuditRow(MessageState from, MessageState to, String operator, String reason) {
    }

    private static long ledgerIdOf(String controlId) throws SQLException {
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id FROM message_ledger WHERE control_id = ?")) {
            ps.setString(1, controlId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "台账里应能找到控制号为「" + controlId + "」的报文");
                return rs.getLong(1);
            }
        }
    }

    private static MessageState readState(long ledgerId) throws SQLException {
        return MessageState.fromDbValue(readColumn(ledgerId, "state"));
    }

    private static long versionOf(long ledgerId) throws SQLException {
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT version FROM message_ledger WHERE id = ?")) {
            ps.setLong(1, ledgerId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getLong(1);
            }
        }
    }

    private static String readRawPayload(long ledgerId) throws SQLException {
        return readColumn(ledgerId, "raw_payload");
    }

    private static String readColumn(long ledgerId, String column) throws SQLException {
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT " + column + " FROM message_ledger WHERE id = ?")) {
            ps.setLong(1, ledgerId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "台账行应存在：ledgerId=" + ledgerId);
                return rs.getString(1);
            }
        }
    }

    /** 一条审计行（按时间倒序取最后一条）。 */
    private static AuditRow lastAuditRow(long ledgerId) throws SQLException {
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT from_state, to_state, operator, reason FROM message_audit "
                             + "WHERE ledger_id = ? ORDER BY id DESC LIMIT 1")) {
            ps.setLong(1, ledgerId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "应有审计行：ledgerId=" + ledgerId);
                return new AuditRow(
                        MessageState.fromDbValue(rs.getString(1)),
                        MessageState.fromDbValue(rs.getString(2)),
                        rs.getString(3),
                        rs.getString(4));
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

    private static long countLedgerRows(String controlId) throws SQLException {
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM message_ledger WHERE control_id = ?")) {
            ps.setString(1, controlId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }
}