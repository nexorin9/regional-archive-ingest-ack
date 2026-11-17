package cn.hospital.region.archive.ledger;

import cn.hospital.region.archive.ack.AckComposer;
import cn.hospital.region.archive.identity.CountingPatientIndexWriter;
import cn.hospital.region.archive.mllp.MllpFrameCodec;
import cn.hospital.region.archive.mllp.MllpServer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T16 · 并发同键与重放 100 次的端到端断言 —— 「幂等到底算不算数」的可复核答案。
 *
 * <h2>这一组在验什么</h2>
 * <ul>
 *   <li><b>20 线程同一条报文</b>：只有 1 条真正进入业务处理，19 条拿到
 *       {@link AcquireResult.Duplicate}，台账<b>恒 1 行</b>，审计行条数与实际迁移数一致；</li>
 *   <li><b>端到端重放 100 次</b>：真实 {@link MllpServer} + 真实帧字节 + 真实 socket，
 *       台账仍只有 1 行，{@code PatientIndexWriter} 的出站调用<b>恰好 1 次</b>；</li>
 *   <li><b>反向对照</b>：20 条不同幂等键并发入库，全部成功无丢失
 *       （防「去重写成把不同报文也吃掉」）；</li>
 *   <li><b>红线</b>：并发失败路径不产生第二条 HIS 写入，也不留下 state 与 audit 不一致的台账行。</li>
 * </ul>
 *
 * <p><b>不用任何 mock 库</b>：H2 PG 兼容模式 + 真实 JDBC + 真实唯一约束 + 真实 socket 与帧字节。
 * 「唯一约束到底拦不拦得住」「乐观锁在并发下拦不拦得住」，只有真并发能回答。
 *
 * <h2>与 T31 的关系</h2>
 * <p>本组的出站端口接的是计数替身；T31 把同一个端口接到真实 HTTP PUT 上之后，
 * 本组断言<b>原样复跑、结论不变</b>（变的只是「写出去真到了 HIS」）。
 */
class IdempotencyConcurrencyTest {

    private static final String H2_URL =
            "jdbc:h2:mem:idem_concurrency_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-10-20T04:00:00Z"), ZoneId.of("Asia/Shanghai"));

    private static final String LOCAL_AET = "CHSCOMMUNITY";
    private static final String REMOTE_AET = "REGIONAL_ARCHIVE";

    /** 字段齐全的健康档案变更报文：MSH-9 / MSH-10 / MSH-11 / MSH-12 / MSH-18 与 PID-3/5/7 都在。 */
    static final String VALID_ER7 = String.join("\r",
            "MSH|^~\\&|REGIONAL_ARCHIVE|FACILITY_A|ARCHIVE_IN|FACILITY_A|20261020090000"
                    + "||ADT^A01^ADT_A01|CTRL-T16-0001|P|2.5.1|||AL|NE|UNICODE UTF-8",
            "PID|1||PAT-0001^^^FACILITY_A^MR||张^三||19780315|M",
            "");

    private static int seq = 0;

    private JdbcLedgerStore store;
    private IdempotencyGuard guard;
    private CountingPatientIndexWriter patientIndex;
    private ArchiveIngestService ingest;

    // =====================================================================
    // 夹具
    // =====================================================================

    private static Connection openConnection() throws SQLException {
        return DriverManager.getConnection(H2_URL, "sa", "");
    }

    private static JdbcLedgerStore store() {
        return new JdbcLedgerStore(IdempotencyConcurrencyTest::openConnection, FIXED_CLOCK,
                AuditWriter.jdbc(FIXED_CLOCK));
    }

    /** 一条控制号互不相同的合法报文（反向对照用例用）。 */
    private static String distinctEr7() {
        return VALID_ER7.replace("CTRL-T16-0001", "CTRL-T16-D" + String.format("%04d", ++seq));
    }

    private static byte[] er7(String er7) {
        return er7.getBytes(StandardCharsets.UTF_8);
    }

    /** 从一条报文里取出 MSH-10（控制号）—— 确认环节按真实控制号走，不按「第几条」反推。 */
    private static String controlIdOf(String er7) {
        return er7.split("\r")[0].split("\\|", -1)[9];
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
     * 每个用例从<b>空台账</b>起步。
     *
     * <p>本类的用例共用同一个固定控制号 {@code CTRL-T16-0001}（重放 100 次要盯的就是这一条），
     * 而 H2 内存库带 {@code DB_CLOSE_DELAY=-1}、同一 JVM 内跨方法存活。不清表的话前一个用例
     * 插进去的行会让后一个用例的「首次到达」全部变成 DUPLICATE——<b>断言会假绿或假红，
     * 与并发行为本身无关</b>。清表也让每个用例的台账行数断言（恒 1 行、20 条一条不丢）
     * 说的是「本次调用自己造出来的行数」，而不是全库累计。
     */
    @BeforeEach
    void setUp() throws SQLException {
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM message_audit"); ) {
            ps.executeUpdate();
        }
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM message_ledger")) {
            ps.executeUpdate();
        }
        store = store();
        guard = new IdempotencyGuard(store);
        patientIndex = new CountingPatientIndexWriter();
        ingest = new ArchiveIngestService(new AckComposer(LOCAL_AET, REMOTE_AET, FIXED_CLOCK),
                store, guard, patientIndex);
    }

    // =====================================================================
    // 1. 20 线程同一条报文：1 条进业务，19 条 AlreadyProcessed
    // =====================================================================

    @Test
    @DisplayName("20 线程同一条报文并发到达：仅 1 条进业务处理，台账恒 1 行，审计行数与迁移数一致")
    void test_20_threads_same_key_one_business_entry() throws Exception {
        byte[] payload = er7(VALID_ER7);

        int threads = 20;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ConcurrentLinkedQueue<ArchiveIngestService.IngestOutcome> results = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        results.add(ingest.ingest(payload));
                    } catch (Throwable e) {
                        failures.add(e);
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

        assertTrue(failures.isEmpty(), "任何线程都不该因重复到达而失败，实际有：" + failures);

        List<ArchiveIngestService.IngestOutcome> list = new ArrayList<>(results);
        assertEquals(threads, list.size());
        int accepted = 0;
        int duplicate = 0;
        Set<Long> ids = new HashSet<>();
        for (ArchiveIngestService.IngestOutcome o : list) {
            ids.add(o.ledgerId());
            if (o.status() == ArchiveIngestService.IngestStatus.ACCEPTED) {
                accepted++;
            } else {
                duplicate++;
                assertEquals(ArchiveIngestService.IngestStatus.DUPLICATE, o.status());
            }
        }

        assertEquals(1, accepted, "20 个线程抢同一条报文，只能有一条真正进入业务处理");
        assertEquals(threads - 1, duplicate, "其余 19 条必须是已处理，不得抛异常打断整批");
        assertEquals(1, ids.size(), "所有线程必须指向同一台账行");

        IdempotencyKey key = IdempotencyKey.of("ADT^A01^ADT_A01", "CTRL-T16-0001", "P");
        assertEquals(1L, countLedgerRows(key), "台账只能有一行");
        // 1 次迁移（RECEIVED → PENDING_CONFIRM）→ 恰好 1 行审计；重复到达不产生审计行
        assertEquals(1L, countAuditRows(list.get(0).ledgerId()),
                "审计行条数必须与实际迁移数一致；重复到达不该各写一条审计");
        assertEquals(MessageState.PENDING_CONFIRM, readState(list.get(0).ledgerId()));
        // 接收阶段绝不写患者主索引：没有确认动作，写入次数必须恰好为 0
        assertTrue(patientIndex.neverCalled(),
                "接收阶段不得写患者主索引，实际调用 " + patientIndex.invocationCount() + " 次");
    }

    // =====================================================================
    // 2. 端到端重放 100 次：真实 MLLP 帧字节，台账 1 行，出站写 1 次
    // =====================================================================

    @Test
    @DisplayName("同一条报文经真实 MLLP 帧重放 100 次：台账恒 1 行，患者主索引出站调用恰好 1 次")
    void test_100_replays_write_count_is_1() throws Exception {
        byte[] payload = er7(VALID_ER7);

        try (MllpServer server = new MllpServer("127.0.0.1", 0, ingest, 10000);
             Socket client = new Socket()) {
            int port = server.start();
            client.connect(new InetSocketAddress("127.0.0.1", port), 5000);
            client.setSoTimeout(15000);

            // 真帧字节（0x0B ... 0x1C 0x0D）打进去 100 次，逐次读回一条应用级回执。
            // 走的是真实 socket + 真实拆帧器，不是直接调 handler。
            for (int i = 0; i < 100; i++) {
                OutputStream out = client.getOutputStream();
                out.write(MllpFrameCodec.encode(payload));
                out.flush();

                InputStream in = client.getInputStream();
                int transport = in.read();
                assertEquals(MllpFrameCodec.ACK, transport,
                        "第 " + (i + 1) + " 帧的传输级确认应为 0x06");
                byte[] ack = MllpFrameCodec.readFrame(in);
                assertNotNull(ack, "第 " + (i + 1) + " 帧应回一条应用级回执");
                assertEquals("AA", msaField(new String(ack, StandardCharsets.UTF_8), 1),
                        "第 " + (i + 1) + " 次重放的回执都应是 AA（重复到达不是错误）");
            }

            assertEquals(100, server.receivedCount(), "监听口应收到 100 帧");
            assertEquals(100, server.ackedCount(), "100 帧都应各回一条应用级回执");
        }

        IdempotencyKey key = IdempotencyKey.of("ADT^A01^ADT_A01", "CTRL-T16-0001", "P");
        assertEquals(1L, countLedgerRows(key), "重放 100 次，台账仍只能有 1 行");

        long ledgerId = findLedgerId(key);
        assertEquals(1L, countAuditRows(ledgerId), "只有首次到达推进过一次状态，审计行 1 条");
        assertEquals(MessageState.PENDING_CONFIRM, readState(ledgerId));
        assertTrue(patientIndex.neverCalled(),
                "重放 100 次、尚未确认，出站写必须恰好 0 次，实际 "
                        + patientIndex.invocationCount());

        // 人工确认一次（模拟信息员点确认）→ 出站写恰好 1 次
        ArchiveIngestService.LandOutcome landed = ingest.land("ADT^A01^ADT_A01",
                "CTRL-T16-0001", "P", "operator-007", "PAT-0001", "MRN-884512");
        assertTrue(landed.landed(), "首次确认应当落地：" + landed.note());
        assertEquals(1, patientIndex.invocationCount(),
                "确认一次，患者主索引出站调用必须恰好 1 次");
        CountingPatientIndexWriter.Invocation call = patientIndex.invocations().get(0);
        assertEquals("PAT-0001", call.patientRef());
        assertEquals("MRN-884512", call.localMrn());
        assertTrue(call.sourceBatch().startsWith("REGIONAL_ARCHIVE"),
                "出站的来源批次号应来自台账 batch_id，实际=" + call.sourceBatch());

        // 重复确认（信息员手滑点两下 / 对端重试确认请求）→ 仍然不得写第二次
        for (int i = 0; i < 5; i++) {
            ArchiveIngestService.LandOutcome again = ingest.land("ADT^A01^ADT_A01",
                    "CTRL-T16-0001", "P", "operator-007", "PAT-0001", "MRN-884512");
            assertFalse(again.landed(), "重复确认必须被拒：" + again.note());
        }
        assertEquals(1, patientIndex.invocationCount(),
                "重复确认后出站调用仍必须恰好 1 次，实际 " + patientIndex.invocationCount());
        assertEquals(MessageState.LANDED, readState(ledgerId));
    }

    // =====================================================================
    // 3. 反向对照：20 条不同幂等键并发，全部成功无丢失
    // =====================================================================

    @Test
    @DisplayName("反向对照：20 条不同幂等键并发到达全部入账（去重没有把不同报文也吃掉）")
    void test_20_distinct_keys_no_loss() throws Exception {
        int threads = 20;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ConcurrentLinkedQueue<ArchiveIngestService.IngestOutcome> results = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        // 先把 20 条报文与它们各自的控制号备好，再提交线程。
        // 不这样做的后果：确认环节只能按「第几条」反推控制号，而控制号来自一个全类共享的
        // 自增序号——同类的其它用例会先把它推走，于是确认阶段报「台账里没有这条报文」，
        // 失败原因和并发行为毫无关系。
        List<byte[]> payloads = new ArrayList<>(threads);
        List<String> controlIds = new ArrayList<>(threads);
        for (int i = 0; i < threads; i++) {
            String er7 = distinctEr7();
            payloads.add(er7(er7));
            controlIds.add(controlIdOf(er7));
        }

        try {
            for (int i = 0; i < threads; i++) {
                byte[] payload = payloads.get(i);
                pool.submit(() -> {
                    try {
                        start.await();
                        results.add(ingest.ingest(payload));
                    } catch (Throwable e) {
                        failures.add(e);
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

        assertTrue(failures.isEmpty(), "不同键并发不应有任何失败，实际有：" + failures);
        assertEquals(threads, results.size());

        Set<Long> ids = new HashSet<>();
        for (ArchiveIngestService.IngestOutcome o : results) {
            assertEquals(ArchiveIngestService.IngestStatus.ACCEPTED, o.status(),
                    "不同幂等键每条都必须是首次到达");
            assertTrue(ids.add(o.ledgerId()), "每条报文必须各占一行");
        }
        assertEquals(threads, ids.size(), "一条都不能丢");
        assertEquals(threads, countLedgerRowsInBatchPrefix("REGIONAL_ARCHIVE-20261020"),
                "20 条应各占一行，不能被唯一约束误判成同一条");

        // 20 条逐条确认 → 出站写恰好 20 次，且参数互不相同（去重没有误伤正常报文）
        for (int i = 0; i < threads; i++) {
            ArchiveIngestService.LandOutcome o = ingest.land("ADT^A01^ADT_A01", controlIds.get(i), "P",
                    "operator-007", "PAT-0001", "MRN-" + i);
            assertTrue(o.landed(), "第 " + (i + 1) + " 条应落地：" + o.note());
        }
        assertEquals(threads, patientIndex.invocationCount(),
                "20 条确认对应 20 次出站写，一条不多一条不少");

        Set<String> writtenMrns = new HashSet<>();
        for (CountingPatientIndexWriter.Invocation call : patientIndex.invocations()) {
            assertTrue(writtenMrns.add(call.localMrn()),
                    "每条报文写到院内的是不同主键，实际重复了 " + call.localMrn());
            assertEquals("PAT-0001", call.patientRef(), "区域平台主键按报文带出");
        }
        assertEquals(threads, writtenMrns.size(), "20 条互不相同");
    }

    // =====================================================================
    // 4. 红线：并发失败路径不得产生第二条写入，也不得留下 state/audit 不一致的台账行
    // =====================================================================

    @Test
    @DisplayName("红线：并发认领只有一次成功，其余在写之前被拒，且审计链始终完整")
    void test_concurrent_land_writes_exactly_once_and_audit_chain_stays_intact() throws Exception {
        byte[] payload = er7(VALID_ER7);
        ingest.ingest(payload);
        IdempotencyKey key = IdempotencyKey.of("ADT^A01^ADT_A01", "CTRL-T16-0001", "P");
        long ledgerId = findLedgerId(key);

        // 20 个线程同时点确认：只有认领成功的那个会走到「写患者主索引」
        int threads = 20;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ConcurrentLinkedQueue<Boolean> landedFlags = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        landedFlags.add(ingest.land("ADT^A01^ADT_A01", "CTRL-T16-0001", "P",
                                "operator-007", "PAT-0001", "MRN-884512").landed());
                    } catch (Throwable e) {
                        failures.add(e);
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

        assertTrue(failures.isEmpty(), "并发确认不应抛异常打断调用方，实际有：" + failures);
        assertEquals(1, landedFlags.stream().filter(Boolean::booleanValue).count(),
                "20 个线程并发确认，只有 1 个应当落地，其余在写之前被拒");
        assertEquals(1, patientIndex.invocationCount(),
                "并发确认下患者主索引出站写必须恰好 1 次，实际 " + patientIndex.invocationCount());

        // 并发之后审计链必须仍然完整（没有 state 与 audit 不一致的台账行）
        try (Connection conn = openConnection()) {
            LedgerAuditChain.Verdict verdict = LedgerAuditChain.verify(conn, ledgerId);
            assertTrue(verdict.intact(), "审计链应完整，实际断裂类型=" + verdict.breakKind());
            assertEquals(2, verdict.auditRows(), "两次迁移（待确认 → 已落地）应留下 2 行审计");
            assertEquals(2L, versionOf(ledgerId));
        }
    }

    @Test
    @DisplayName("红线：HIS 写失败时退回待确认、原文与幂等键仍在，重试成功后才落地")
    void test_his_write_failure_keeps_pending_confirm_and_replayable() throws Exception {
        byte[] payload = er7(VALID_ER7);
        ingest.ingest(payload);
        IdempotencyKey key = IdempotencyKey.of("ADT^A01^ADT_A01", "CTRL-T16-0001", "P");
        long ledgerId = findLedgerId(key);

        patientIndex.failNextWith("模拟对端 500（HIS 侧短暂不可用）");
        assertThrows(cn.hospital.region.archive.identity.PatientIndexWriteException.class,
                () -> ingest.land("ADT^A01^ADT_A01", "CTRL-T16-0001", "P",
                        "operator-007", "PAT-0001", "MRN-884512"));

        assertEquals(MessageState.PENDING_CONFIRM, readState(ledgerId),
                "写失败必须退回待确认，绝不能把没写成功的条标成已落地");
        assertEquals(1, patientIndex.invocationCount(), "确实调过一次出站");
        assertTrue(rawPayloadOf(ledgerId).contains("CTRL-T16-0001"),
                "写失败后报文原文必须仍在台账（可重放）");
        assertEquals(1L, countLedgerRows(key), "幂等键不变，重放仍是同一条");

        // 重试：状态仍是待确认，重试成功后才落地
        ArchiveIngestService.LandOutcome retry = ingest.land("ADT^A01^ADT_A01",
                "CTRL-T16-0001", "P", "operator-007", "PAT-0001", "MRN-884512");
        assertTrue(retry.landed(), "重试应落地：" + retry.note());
        assertEquals(2, patientIndex.invocationCount(), "重试再写一次，累计 2 次");
        assertEquals(MessageState.LANDED, readState(ledgerId));

        try (Connection conn = openConnection()) {
            LedgerAuditChain.verify(conn, ledgerId);
        }
    }

    @Test
    @DisplayName("红线：未经确认直接走落地路径时，出站写调用数为 0")
    void test_no_confirm_means_zero_writes() throws Exception {
        // 连续到达 100 条不同的合法报文，全部入账、全部待确认，期间没有任何一次落地调用
        for (int i = 0; i < 100; i++) {
            ingest.ingest(er7(distinctEr7()));
        }
        assertTrue(patientIndex.neverCalled(),
                "没有人点确认时，患者主索引出站写必须恰好 0 次，实际 "
                        + patientIndex.invocationCount() + " 次");
        assertEquals(100L, countLedgerRowsInBatchPrefix("REGIONAL_ARCHIVE-20261020"));
    }

    // =====================================================================
    // 辅助
    // =====================================================================

    private static String msaField(String ackEr7, int n) {
        for (String line : ackEr7.split("\r")) {
            if (line.startsWith("MSA|")) {
                return line.split("\\|", -1)[n];
            }
        }
        throw new AssertionError("回执里没有 MSA 段：" + ackEr7);
    }

    private static long findLedgerId(IdempotencyKey key) throws SQLException {
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id FROM message_ledger WHERE message_type = ? AND control_id = ? "
                             + "AND processing_id = ?")) {
            ps.setString(1, key.messageType());
            ps.setString(2, key.controlId());
            ps.setString(3, key.processingId());
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "台账里应能找到这条报文：" + key);
                return rs.getLong(1);
            }
        }
    }

    private static MessageState readState(long ledgerId) throws SQLException {
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT state FROM message_ledger WHERE id = ?")) {
            ps.setLong(1, ledgerId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "台账行应存在：ledgerId=" + ledgerId);
                return MessageState.fromDbValue(rs.getString(1));
            }
        }
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

    private static String rawPayloadOf(long ledgerId) throws SQLException {
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT raw_payload FROM message_ledger WHERE id = ?")) {
            ps.setLong(1, ledgerId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getString(1);
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