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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.text.MessageFormat;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T31 验收 —— 院内患者主索引<b>真实 HTTP PUT</b> 与写回失败语义。
 *
 * <h2>这一组在验什么</h2>
 * <ul>
 *   <li><b>正式对接规范</b>：方法、路径、{@code Authorization}、{@code Content-Type} 与
 *       JSON 三字段（{@code patientRef} / {@code localMrn} / {@code sourceBatch}）逐字符合规范；</li>
 *   <li><b>失败绝不静默改数据</b>：对端 500 / 401 / 读超时 / 连不上，
 *       行一律停在待确认、报文原文与幂等键原样保留、审计链不断，<b>绝不置已落地</b>；</li>
 *   <li><b>端口不变</b>：换成真实 HTTP 实现后，T16「重放 100 次只写一次」与
 *       T30「无确认动作时写入 0 次」两条结论原样成立 —— 只是这次数的是对端真实收到的请求数；</li>
 *   <li><b>可重放是真的</b>：500 退回待确认后，对端恢复即可原样再确认并落地。</li>
 * </ul>
 *
 * <p><b>不用任何 mock 库</b>：真实 {@link com.sun.net.httpserver.HttpServer} + 真实 TCP +
 * 真实 UTF-8 JSON 请求体；台账侧是 H2 PG 兼容模式 + 真实 JDBC + 真实迁移脚本。
 * 「出站写了几次」数的是替身收到的请求条数，不是 mock 的调用记录。
 */
class HisPatientIndexClientTest {

    private static final String H2_URL =
            "jdbc:h2:mem:his_client_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-10-20T09:00:00Z"), ZoneId.of("Asia/Shanghai"));

    private static final String LOCAL_AET = "CHSCOMMUNITY";
    private static final String REMOTE_AET = "REGIONAL_ARCHIVE";
    private static final String OPERATOR = "operator-031";

    /** 替身要求的令牌。刻意取得不像样例凭据，便于断言「令牌一个字都没漏出去」。 */
    private static final String TOKEN = "his-mpi-token-7f3c19d2e8";

    private static final String PATIENT_REF = "PAT-0001";
    private static final String LOCAL_MRN = "LOCMRN-0001";

    /** 批次号口径（MSH-3 + MSH-7 日期部分）的实际取值，本端从台账 batch_id 取，不是写死的。 */
    private static final String EXPECTED_BATCH = "REGIONAL_ARCHIVE-20261020";

    private HisMpiStubServer stub;
    private JdbcLedgerStore store;
    private ArchiveIngestService ingest;
    private ConfirmGate gate;

    // =====================================================================
    // 夹具
    // =====================================================================

    private static Connection openConnection() throws SQLException {
        return DriverManager.getConnection(H2_URL, "sa", "");
    }

    private static String er7Of(String controlId) {
        return String.join("\r",
                "MSH|^~\\&|REGIONAL_ARCHIVE|FACILITY_A|ARCHIVE_IN|FACILITY_A|20261020090000"
                        + "||ADT^A08^ADT_A08|" + controlId + "|P|2.5.1|||AL|NE|UNICODE UTF-8",
                "PID|1||" + PATIENT_REF + "^^^FACILITY_A^MR||张^三||19780315|M",
                "");
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
    void setUp() throws Exception {
        for (String table : new String[]{"confirm_refusal", "message_audit", "message_ledger"}) {
            try (Connection conn = openConnection();
                 PreparedStatement ps = conn.prepareStatement("DELETE FROM " + table)) {
                ps.executeUpdate();
            }
        }
        stub = HisMpiStubServer.start("", TOKEN);
        store = new JdbcLedgerStore(HisPatientIndexClientTest::openConnection, FIXED_CLOCK,
                AuditWriter.jdbc(FIXED_CLOCK));
        ingest = new ArchiveIngestService(new AckComposer(LOCAL_AET, REMOTE_AET, FIXED_CLOCK),
                store, new IdempotencyGuard(store), client(Duration.ofSeconds(5)));
        gate = new ConfirmGate(store, ingest, new RevertService(store),
                ConfirmRefusalJournal.jdbc(FIXED_CLOCK, HisPatientIndexClientTest::openConnection),
                new JdbcPatientKeyStore(HisPatientIndexClientTest::openConnection));
    }

    @AfterEach
    void tearDown() {
        if (stub != null) {
            stub.close();
        }
    }

    private HisPatientIndexClient client(Duration timeout) {
        return new HisPatientIndexClient(stub.baseUrl(), TOKEN, timeout);
    }

    private long receive(String controlId) throws Exception {
        return ingest.ingest(er7Of(controlId).getBytes(StandardCharsets.UTF_8)).ledgerId();
    }

    private LedgerRow row(long ledgerId) throws SQLException {
        return store.findById(ledgerId).orElseThrow(() ->
                new AssertionError("台账里没有 id=" + ledgerId));
    }

    private static ConfirmDecision decision() {
        return ConfirmDecision.fromCandidate(OPERATOR, PATIENT_REF, LOCAL_MRN, 1);
    }

    private int auditRowCount(long ledgerId) throws SQLException {
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM message_audit WHERE ledger_id = ?")) {
            ps.setLong(1, ledgerId);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    // =====================================================================
    // 1. 出站报文形状：方法 / 路径 / 鉴权 / 三字段
    // =====================================================================

    @Test
    @DisplayName("PUT 请求带齐 Authorization 与 patientRef/localMrn/sourceBatch 三字段")
    void test_put_carries_patientRef_localMrn_sourceBatch_and_bearer() throws Exception {
        client(Duration.ofSeconds(5)).put(PATIENT_REF, LOCAL_MRN, EXPECTED_BATCH);

        assertEquals(1, stub.requestCount(), "一次 put 应对应对端一次请求");
        HisMpiStubServer.ReceivedRequest req = stub.lastRequest();
        assertNotNull(req);
        assertEquals("PUT", req.method());
        assertEquals("/patient-index", req.path());
        assertEquals("Bearer " + TOKEN, req.authorization());
        assertTrue(req.contentType().startsWith("application/json"),
                "Content-Type 应为 application/json，实际 " + req.contentType());

        Map<String, Object> body = req.json();
        assertEquals(java.util.Set.of("patientRef", "localMrn", "sourceBatch"), body.keySet(),
                "请求体只允许正式规范里的三个字段，实际 " + body.keySet());
        assertEquals(PATIENT_REF, body.get("patientRef"));
        assertEquals(LOCAL_MRN, body.get("localMrn"));
        assertEquals(EXPECTED_BATCH, body.get("sourceBatch"));
    }

    @Test
    @DisplayName("HIS_MPI_ENDPOINT 带基础路径时前缀原样保留：/mpi + /patient-index")
    void test_endpoint_base_path_prefix_is_preserved() throws Exception {
        try (HisMpiStubServer prefixed = HisMpiStubServer.start("/mpi", null)) {
            HisPatientIndexClient client =
                    new HisPatientIndexClient(prefixed.baseUrl(), TOKEN, Duration.ofSeconds(5));
            client.put(PATIENT_REF, LOCAL_MRN, EXPECTED_BATCH);

            assertEquals(1, prefixed.requestCount());
            assertEquals("/mpi/patient-index", prefixed.lastRequest().path(),
                    "端点自带基础路径时不能把前缀吃掉 —— 院内前置机大多挂在子路径上");
        }
    }

    @Test
    @DisplayName("对端幂等键随三字段稳定变化，且不含主键明文")
    void test_idempotency_key_is_stable_and_leaks_no_primary_keys() throws Exception {
        HisPatientIndexClient client = client(Duration.ofSeconds(5));
        client.put(PATIENT_REF, LOCAL_MRN, EXPECTED_BATCH);
        String first = stub.lastRequest().idempotencyKey();
        client.put(PATIENT_REF, LOCAL_MRN, EXPECTED_BATCH);
        String same = stub.lastRequest().idempotencyKey();
        client.put(PATIENT_REF, LOCAL_MRN, "REGIONAL_ARCHIVE-20261021");
        String otherBatch = stub.lastRequest().idempotencyKey();

        assertTrue(first.startsWith("his-"), "对端幂等键应带 his- 前缀，实际 " + first);
        assertEquals(first.length(), "his-".length() + 32, "键长固定（his- + 32 位十六进制）");
        assertEquals(first, same, "同一笔写重发时键必须一致，对端才可能去重");
        assertNotEquals(first, otherBatch, "换一批来源就该换一把键");
        assertFalse(first.contains(PATIENT_REF), "幂等键不得带区域主键明文");
        assertFalse(first.contains(LOCAL_MRN), "幂等键不得带院内主键明文");
    }

    @Test
    @DisplayName("三个字段任一为空白：入参即拒，一个请求都不发")
    void test_blank_arguments_are_rejected_before_any_http() {
        HisPatientIndexClient client = client(Duration.ofSeconds(5));

        assertThrows(IllegalArgumentException.class,
                () -> client.put("  ", LOCAL_MRN, EXPECTED_BATCH));
        assertThrows(IllegalArgumentException.class,
                () -> client.put(PATIENT_REF, "", EXPECTED_BATCH));
        assertThrows(IllegalArgumentException.class,
                () -> client.put(PATIENT_REF, LOCAL_MRN, null));
        assertEquals(0, stub.requestCount(), "入参不合法时不得向 HIS 发出任何请求");
    }

    // =====================================================================
    // 2. 端到端：确认 → 真实 PUT → 已落地
    // =====================================================================

    @Test
    @DisplayName("确认一次：状态到已落地，对端收到台账批次号，审计链完整")
    void test_successful_confirm_lands_row_and_reaches_the_real_endpoint() throws Exception {
        long ledgerId = receive("CTRL-T31-0001");

        ConfirmOutcome outcome = gate.confirm(ledgerId, decision());

        assertTrue(outcome.confirmed());
        assertTrue(outcome.wrotePatientIndex());
        assertEquals(1, stub.requestCount());
        HisMpiStubServer.ReceivedRequest req = stub.lastRequest();
        assertEquals(PATIENT_REF, req.field("patientRef"));
        assertEquals(LOCAL_MRN, req.field("localMrn"));
        assertEquals(EXPECTED_BATCH, req.field("sourceBatch"),
                "sourceBatch 必须取自台账 batch_id，而不是端内拼一个字符串");

        LedgerRow landed = row(ledgerId);
        assertEquals(MessageState.LANDED, landed.state());
        assertEquals(LOCAL_MRN, landed.localMrn(), "院内主键随落地一并记进台账");
        assertEquals(2, auditRowCount(ledgerId), "接收→待确认、待确认→已落地，各一行审计");
        try (Connection conn = openConnection()) {
            assertTrue(LedgerAuditChain.verify(conn, ledgerId).intact());
        }
    }

    @Test
    @DisplayName("重放 100 次再确认：对端恰好收到一次写")
    void test_replay_100_times_then_confirm_writes_exactly_once() throws Exception {
        String er7 = er7Of("CTRL-T31-REPLAY");
        long ledgerId = 0L;
        for (int i = 0; i < 100; i++) {
            ArchiveIngestService.IngestOutcome outcome =
                    ingest.ingest(er7.getBytes(StandardCharsets.UTF_8));
            ledgerId = outcome.ledgerId();
            assertEquals(i == 0 ? ArchiveIngestService.IngestStatus.ACCEPTED
                            : ArchiveIngestService.IngestStatus.DUPLICATE, outcome.status(),
                    "第 " + (i + 1) + " 次到达：首次应为已接收，其余 99 次都应是重复到达");
        }
        assertEquals(1, ledgerCount("CTRL-T31-REPLAY"), "台账只增一行");
        assertEquals(0, stub.requestCount(), "接收阶段绝不碰患者主索引");

        gate.confirm(ledgerId, decision());

        assertEquals(1, stub.requestCount(),
                "T16 的结论换成真实 HTTP 后不变：重放 100 次，HIS 只收到一次写");
    }

    @Test
    @DisplayName("收 8 条、无人确认：对端一次请求都没收到")
    void test_no_confirm_means_zero_http_requests() throws Exception {
        for (int i = 0; i < 8; i++) {
            receive(String.format("CTRL-T31-BATCH-%02d", i));
        }
        assertEquals(0, stub.requestCount(),
                "没有确认动作时，出站写次数必须恰好为 0");
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM message_ledger WHERE state = 'PENDING_CONFIRM'")) {
            try (var rs = ps.executeQuery()) {
                assertEquals(8, rs.next() ? rs.getInt(1) : 0, "8 条都停在待确认等人");
            }
        }
    }

    // =====================================================================
    // 3. 写回失败：行留在待确认，原文与幂等键保留，可重放
    // =====================================================================

    @Test
    @DisplayName("对端 500：抛可重放失败，行退回待确认，原文与幂等键原样保留")
    void test_upstream_500_keeps_pending_confirm_replayable() throws Exception {
        long ledgerId = receive("CTRL-T31-FAIL-500");
        stub.setHttpStatus(500);

        HisPatientIndexWriteException thrown = assertThrows(HisPatientIndexWriteException.class,
                () -> gate.confirm(ledgerId, decision()));

        assertEquals(HisWriteErrorCode.SERVER_ERROR, thrown.code());
        assertEquals(Integer.valueOf(500), thrown.httpStatus());
        assertTrue(thrown.replayable(), "5xx 是抖动，原样重投即可恢复");
        assertEquals(1, stub.requestCount(), "失败也说明请求真的发出去了");

        assertStillReplayable(ledgerId, "CTRL-T31-FAIL-500");
    }

    @Test
    @DisplayName("对端 401：非抖动类同样不置已落地，行留在待确认")
    void test_upstream_401_rolls_back_and_never_lands() throws Exception {
        long ledgerId = receive("CTRL-T31-FAIL-401");
        stub.setRequireBearer("另一个令牌");

        HisPatientIndexWriteException thrown = assertThrows(HisPatientIndexWriteException.class,
                () -> gate.confirm(ledgerId, decision()));

        assertEquals(HisWriteErrorCode.CLIENT_ERROR, thrown.code(),
                "实际状态码=" + thrown.httpStatus() + " 消息=" + thrown.getMessage());
        assertEquals(Integer.valueOf(401), thrown.httpStatus());
        assertFalse(thrown.jittery(), "401 是配置问题，重投不会自己好");
        assertTrue(thrown.replayable(), "行必须退回待确认等人处理 —— 这条不因失败分类而打折");

        assertStillReplayable(ledgerId, "CTRL-T31-FAIL-401");
    }

    @Test
    @DisplayName("读超时：分类为 READ_TIMEOUT，行留在待确认")
    void test_read_timeout_is_classified_and_replayable() throws Exception {
        long ledgerId = receive("CTRL-T31-TIMEOUT");
        stub.setResponseDelayMillis(800L);
        // 客户端装配必须换掉：setUp 里那个是 5 秒读超时，扛得住 800ms 延迟
        ingest = new ArchiveIngestService(new AckComposer(LOCAL_AET, REMOTE_AET, FIXED_CLOCK),
                store, new IdempotencyGuard(store), client(Duration.ofMillis(150)));
        gate = new ConfirmGate(store, ingest, new RevertService(store),
                ConfirmRefusalJournal.jdbc(FIXED_CLOCK, HisPatientIndexClientTest::openConnection),
                new JdbcPatientKeyStore(HisPatientIndexClientTest::openConnection));

        HisPatientIndexWriteException thrown = assertThrows(HisPatientIndexWriteException.class,
                () -> gate.confirm(ledgerId, decision()));

        assertEquals(HisWriteErrorCode.READ_TIMEOUT, thrown.code());
        assertTrue(thrown.replayable());
        assertStillReplayable(ledgerId, "CTRL-T31-TIMEOUT");
    }

    @Test
    @DisplayName("连不上：分类为 TRANSPORT_FAILURE，行留在待确认")
    void test_connection_refused_is_transport_failure() throws Exception {
        long ledgerId = receive("CTRL-T31-REFUSED");
        // 端口 1 回环上没有服务：真实「连不上」，而不是构造一个假的传输层异常
        HisPatientIndexClient unreachable =
                new HisPatientIndexClient("http://127.0.0.1:1", TOKEN, Duration.ofSeconds(2));
        ArchiveIngestService offline = new ArchiveIngestService(
                new AckComposer(LOCAL_AET, REMOTE_AET, FIXED_CLOCK), store,
                new IdempotencyGuard(store), unreachable);
        ConfirmGate offlineGate = new ConfirmGate(store, offline, new RevertService(store),
                ConfirmRefusalJournal.jdbc(FIXED_CLOCK, HisPatientIndexClientTest::openConnection),
                new JdbcPatientKeyStore(HisPatientIndexClientTest::openConnection));

        HisPatientIndexWriteException thrown = assertThrows(HisPatientIndexWriteException.class,
                () -> offlineGate.confirm(ledgerId, decision()));

        assertEquals(HisWriteErrorCode.TRANSPORT_FAILURE, thrown.code());
        assertTrue(thrown.replayable());
        assertStillReplayable(ledgerId, "CTRL-T31-REFUSED");
        assertEquals(0, stub.requestCount(), "请求连不出去，对端当然没收到");
    }

    @Test
    @DisplayName("失败后对端恢复：原样再确认即可落地，重放不新增行")
    void test_replay_after_failure_lands_on_second_attempt() throws Exception {
        long ledgerId = receive("CTRL-T31-RETRY-OK");
        stub.setHttpStatus(503);
        assertThrows(HisPatientIndexWriteException.class, () -> gate.confirm(ledgerId, decision()));
        assertEquals(MessageState.PENDING_CONFIRM, row(ledgerId).state());

        // 只改状态码、不 reset：reset 会连同已收请求一起清掉，
        // 那样「两次请求」就数不出来了 —— 而这正是本用例要验的东西。
        stub.setHttpStatus(200);
        ConfirmOutcome outcome = gate.confirm(ledgerId, decision());

        assertTrue(outcome.wrotePatientIndex());
        assertEquals(MessageState.LANDED, row(ledgerId).state());
        assertEquals(2, stub.requestCount(), "第一次 503、第二次 200，共两次请求");
        assertEquals(1, ledgerCount("CTRL-T31-RETRY-OK"), "重放不新增台账行");
    }

    // =====================================================================
    // 4. 失败分类与信息边界
    // =====================================================================

    @Test
    @DisplayName("状态码族 → 失败分类码：5xx/408/429 抖动，其余 4xx 与 3xx 不抖动")
    void test_error_codes_classify_status_families() throws Exception {
        assertEquals(HisWriteErrorCode.SERVER_ERROR, HisWriteErrorCode.classify(500));
        assertEquals(HisWriteErrorCode.SERVER_ERROR, HisWriteErrorCode.classify(503));
        assertEquals(HisWriteErrorCode.SERVER_ERROR, HisWriteErrorCode.classify(429));
        assertEquals(HisWriteErrorCode.SERVER_ERROR, HisWriteErrorCode.classify(408));
        assertEquals(HisWriteErrorCode.CLIENT_ERROR, HisWriteErrorCode.classify(400));
        assertEquals(HisWriteErrorCode.CLIENT_ERROR, HisWriteErrorCode.classify(401));
        assertEquals(HisWriteErrorCode.CLIENT_ERROR, HisWriteErrorCode.classify(409));
        assertEquals(HisWriteErrorCode.REDIRECT_UNRESOLVED, HisWriteErrorCode.classify(302));

        // 分类不只停在枚举上：真正打一发 400，异常必须带着同一个码出来
        stub.setHttpStatus(400);
        HisPatientIndexWriteException thrown = assertThrows(HisPatientIndexWriteException.class,
                () -> client(Duration.ofSeconds(5)).put(PATIENT_REF, LOCAL_MRN, EXPECTED_BATCH));
        assertEquals(HisWriteErrorCode.CLIENT_ERROR, thrown.code(),
                "实际状态码=" + thrown.httpStatus() + " 消息=" + thrown.getMessage());
        assertEquals(Integer.valueOf(400), thrown.httpStatus());
    }

    @Test
    @DisplayName("令牌与院内响应体一个字都不进异常消息与日志")
    void test_token_and_response_body_never_leak_into_message_or_logs() throws Exception {
        Logger target = Logger.getLogger(HisPatientIndexClient.class.getName());
        LogCapture capture = new LogCapture();
        capture.setLevel(Level.ALL);
        target.addHandler(capture);
        target.setLevel(Level.ALL);
        stub.setHttpStatus(500);
        try {
            HisPatientIndexWriteException thrown = assertThrows(HisPatientIndexWriteException.class,
                    () -> client(Duration.ofSeconds(5)).put(PATIENT_REF, LOCAL_MRN, EXPECTED_BATCH));

            assertFalse(thrown.getMessage().contains(TOKEN), "异常消息里不得出现令牌");
            assertFalse(thrown.getMessage().contains("ip-10-2-33-7"),
                    "非 2xx 响应体不得进异常消息：" + thrown.getMessage());
            assertFalse(capture.text().contains(TOKEN), "日志里不得出现令牌");
            assertFalse(capture.text().contains("ip-10-2-33-7"),
                    "日志里不得出现院内响应体内容");
            assertTrue(capture.text().contains("500"), "状态码要留在日志里，否则没法定位");
        } finally {
            target.removeHandler(capture);
        }
    }

    @Test
    @DisplayName("配置摘要不含令牌，端点非法直接拒绝装配")
    void test_describe_hides_token_and_bad_endpoints_are_rejected() {
        String described = client(Duration.ofSeconds(5)).describe();
        assertFalse(described.contains(TOKEN), "describe() 会被打进启动日志，不得含令牌");
        assertTrue(described.contains("/patient-index"), "摘要要写清出站路径，便于核对配置");

        assertThrows(IllegalArgumentException.class,
                () -> new HisPatientIndexClient("ftp://前置机/patient-index", TOKEN, Duration.ofSeconds(5)));
        assertThrows(IllegalArgumentException.class,
                () -> new HisPatientIndexClient("   ", TOKEN, Duration.ofSeconds(5)));
        assertThrows(IllegalArgumentException.class,
                () -> new HisPatientIndexClient(stub.baseUrl(), "  ", Duration.ofSeconds(5)));
        assertThrows(IllegalArgumentException.class,
                () -> new HisPatientIndexClient(stub.baseUrl(), TOKEN, Duration.ZERO));
    }

    // =====================================================================
    // 断言辅助
    // =====================================================================

    /** 写失败后的台账原样断言：停在待确认、原文与幂等键都在、审计链不断。 */
    private void assertStillReplayable(long ledgerId, String controlId) throws Exception {
        LedgerRow row = row(ledgerId);
        assertEquals(MessageState.PENDING_CONFIRM, row.state(),
                "写失败绝不能置已落地 —— 那会让回执说落地了而 HIS 里查无此人");
        assertEquals(controlId, row.key().controlId(), "幂等键必须在，重放靠它命中同一条");
        assertEquals("ADT^A08^ADT_A08", row.key().messageType());
        assertEquals("P", row.key().processingId());
        assertTrue(row.rawPayload().contains(controlId), "报文原文必须保留，重投要靠它");
        assertTrue(row.rawPayload().contains(PATIENT_REF));
        assertEquals("", row.localMrn(), "写没成功就不能留下院内主键，否则下次确认会被判成同目标重确认");
        try (Connection conn = openConnection()) {
            assertTrue(LedgerAuditChain.verify(conn, ledgerId).intact(),
                    "写失败与回退都必须留审计行，链不能断");
        }
    }

    private int ledgerCount(String controlId) throws SQLException {
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM message_ledger WHERE control_id = ?")) {
            ps.setString(1, controlId);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /** 收集日志行（按 java.util.logging 的 {0} 占位符格式化成最终文本后再比对）。 */
    private static final class LogCapture extends Handler {

        private final List<String> lines = new CopyOnWriteArrayList<>();

        @Override
        public void publish(LogRecord record) {
            Object[] params = record.getParameters();
            String text = params == null || params.length == 0
                    ? String.valueOf(record.getMessage())
                    : MessageFormat.format(String.valueOf(record.getMessage()), params);
            lines.add(text);
        }

        @Override
        public void flush() {
            // 无缓冲，不需要处理
        }

        @Override
        public void close() {
            // 无资源需要释放
        }

        String text() {
            return String.join("\n", new ArrayList<>(lines));
        }
    }
}