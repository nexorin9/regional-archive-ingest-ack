package cn.hospital.region.archive.model;

import cn.hospital.region.archive.ack.AckComposer;
import cn.hospital.region.archive.identity.CountingPatientIndexWriter;
import cn.hospital.region.archive.ledger.ArchiveIngestService;
import cn.hospital.region.archive.ledger.AuditWriter;
import cn.hospital.region.archive.ledger.IdempotencyGuard;
import cn.hospital.region.archive.ledger.IdempotencyKey;
import cn.hospital.region.archive.ledger.JdbcLedgerStore;
import cn.hospital.region.archive.ledger.LedgerSchema;
import cn.hospital.region.archive.ledger.MessageState;
import cn.hospital.region.archive.mllp.MllpFrameCodec;
import cn.hospital.region.archive.mllp.MllpServer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T21 · 五类错误码 + 单一降级路径 —— 「模型挂了主路径照跑」的可复核答案。
 *
 * <h2>这一组在验什么</h2>
 * <ul>
 *   <li><b>五类错误各有一个断言用例</b>：连接超时 / 读超时 / 非 2xx / 不可解析 / 结构校验失败，
 *       每一类都能从一次真实调用里分类出来；</li>
 *   <li><b>重试只花在有意义的失败上</b>：两类超时按 {@code MODEL_MAX_RETRIES} 退避重试，
 *       后三类第一次失败就降级；</li>
 *   <li><b>红线</b>：降级后状态仍为待确认，且患者主索引出站调用<b>恰好 0 次</b>；</li>
 *   <li><b>模型全挂</b>：整批报文照样逐条走完主路径并各回一条 MSA 回执，
 *       不出现「一条都不回执」的情况。</li>
 * </ul>
 *
 * <p><b>不用任何 mock 库</b>：协议替身是真实 {@code com.sun.net.httpserver}（真实 TCP、真实 UTF-8 JSON），
 * 台账是真实 H2 PG 兼容模式 + 真实 JDBC，监听口是真实 {@link MllpServer} + 真实帧字节。
 * 「错误分类到底准不准」「降级之后回执还发不发得出去」只有真跑一遍才算数。
 */
class ModelGatewayDegradationTest {

    private static final String H2_URL =
            "jdbc:h2:mem:model_degradation_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-10-20T06:00:00Z"), ZoneId.of("Asia/Shanghai"));

    private static final String LOCAL_AET = "CHSCOMMUNITY";
    private static final String REMOTE_AET = "REGIONAL_ARCHIVE";
    private static final String API_KEY = "sk-local-stub-key";

    private static final String BATCH_ID = "REGIONAL_ARCHIVE-20261020";

    /** 结构与必填字段齐全、且带两条 Z 段的健康档案变更报文。 */
    static final String ER7_WITH_Z = String.join("\r",
            "MSH|^~\\&|REGIONAL_ARCHIVE|FACILITY_A|ARCHIVE_IN|FACILITY_A|20261020090000"
                    + "||ADT^A01^ADT_A01|CTRL-T21-0001|P|2.5.1|||AL|NE|UNICODE UTF-8",
            "PID|1||PAT-0001^^^FACILITY_A^MR||张^三||19780315|M",
            "ZX1||既往病史摘要|高血压随访;2型糖尿病",
            "ZX2||下次随访时间|20270115",
            "");

    /** 同一条报文去掉 Z 段：结构仍然通过，但不该为模型付一次调用。 */
    static final String ER7_NO_Z = String.join("\r",
            "MSH|^~\\&|REGIONAL_ARCHIVE|FACILITY_A|ARCHIVE_IN|FACILITY_A|20261020090000"
                    + "||ADT^A01^ADT_A01|CTRL-T21-0002|P|2.5.1|||AL|NE|UNICODE UTF-8",
            "PID|1||PAT-0002^^^FACILITY_A^MR||李^四||19820315|F",
            "");

    private JdbcLedgerStore store;
    private IdempotencyGuard guard;
    private CountingPatientIndexWriter patientIndex;
    private InMemoryModelCallAudit audit;

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

    /** 每例从空台账起步：H2 带 {@code DB_CLOSE_DELAY=-1} 跨方法存活，不清表会让断言读到上一例的行。 */
    @BeforeEach
    void setUp() throws SQLException {
        for (String table : List.of("message_audit", "message_ledger")) {
            try (Connection conn = openConnection();
                 PreparedStatement ps = conn.prepareStatement("DELETE FROM " + table)) {
                ps.executeUpdate();
            }
        }
        store = new JdbcLedgerStore(ModelGatewayDegradationTest::openConnection, FIXED_CLOCK,
                AuditWriter.jdbc(FIXED_CLOCK));
        guard = new IdempotencyGuard(store);
        patientIndex = new CountingPatientIndexWriter();
        audit = new InMemoryModelCallAudit();
    }

    /** 装配可降级网关；退避用记录式 sleeper（只记不睡），让退避序列可断言又不拖慢测试。 */
    private ResilientModelGateway gatewayFor(String baseUrl, int timeoutSeconds, int maxRetries,
                                             List<Duration> sleepRecord) {
        ModelGatewayConfig config =
                new ModelGatewayConfig(baseUrl, API_KEY, "stub-model-local", timeoutSeconds, maxRetries);
        // 连接超时给足 3 秒：本组用例打的是本机替身或本机死端口，不该由连接超时制造抖动。
        java.net.http.HttpClient http = java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        ModelGateway strict = new OpenAiCompatibleGateway(config, audit, http, FIXED_CLOCK);
        return new ResilientModelGateway(strict,
                new ModelRetryPolicy(maxRetries, ModelRetryPolicy.recordingSleeper(sleepRecord)));
    }

    /** 接上可降级网关的接入主路径。 */
    private ArchiveIngestService ingestWith(ResilientModelGateway gateway) {
        return new ArchiveIngestService(new AckComposer(LOCAL_AET, REMOTE_AET, FIXED_CLOCK),
                store, guard, patientIndex, ArchiveIngestService.BatchIdResolver.sendingAppAndDay(),
                gateway);
    }

    private static byte[] er7(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 取 MSA 段的第 n 个字段（1 起，对应 HL7 的 {@code MSA-n}）。
     *
     * <p>注意 {@code split} 之后的第 0 位是段名 {@code "MSA"}，所以 MSA-1 对应下标 1。
     */
    private static String msaField(String er7Text, int msaFieldNumber) {
        for (String line : er7Text.split("\r")) {
            if (line.startsWith("MSA|")) {
                return line.split("\\|", -1)[msaFieldNumber];
            }
        }
        throw new AssertionError("回执里没有 MSA 段：" + er7Text);
    }

    /** 取一个必然没有服务监听的端口：先占一个空闲端口再放开，连上去立刻被拒。 */
    private static int closedPort() throws Exception {
        try (ServerSocket probe = new ServerSocket(0)) {
            return probe.getLocalPort();
        }
    }

    private static List<ZSegment> sampleZSegments() {
        List<ZSegment> out = new ArrayList<>();
        out.add(new ZSegment("ZX1", java.util.Map.of(2, "既往病史摘要", 3, "高血压随访;2型糖尿病")));
        out.add(new ZSegment("ZX2", java.util.Map.of(2, "下次随访时间", 3, "20270115")));
        return out;
    }

    // =====================================================================
    // 1. 五类错误码：分类、重试取舍、说明文案
    // =====================================================================

    @Test
    @DisplayName("五类错误码分类齐全：只有两类超时可重试，且每一类都带中文说明与处置建议")
    void test_five_error_codes_are_classified_and_labelled() {
        assertEquals(5, ModelErrorCode.values().length,
                "模型侧只有五类失败；多出来的兜底类意味着某个抛点没回答「这是哪一类」");
        List<ModelErrorCode> retryable = new ArrayList<>();
        for (ModelErrorCode code : ModelErrorCode.values()) {
            assertFalse(code.zhLabel().isBlank(), code + " 缺中文说明");
            assertFalse(code.remedy().isBlank(), code + " 缺处置建议");
            assertEquals(code.name(), code.auditToken(), "审计片段应与枚举名逐字一致");
            if (code.retryable()) {
                retryable.add(code);
            }
        }
        assertEquals(List.of(ModelErrorCode.CONNECT_TIMEOUT, ModelErrorCode.READ_TIMEOUT), retryable,
                "只有两类超时有重试价值；把预算花在 401 或坏 JSON 上只会拖慢每一条报文");
    }

    @Test
    @DisplayName("连接超时：按 MODEL_MAX_RETRIES 退避重试，退避时长是指数的，耗尽后降级为 CONNECT_TIMEOUT")
    void test_connect_timeout_retries_with_exponential_backoff_then_degrades() throws Exception {
        List<Duration> sleeps = ModelRetryPolicy.newSleeperRecord();
        ResilientModelGateway gateway = gatewayFor("http://127.0.0.1:" + closedPort(), 2, 2, sleeps);

        ModelOutcome<ZFieldMap> outcome = gateway.alignZFields(sampleZSegments());

        DegradedResult degraded = outcome.degradedResult().orElseThrow();
        assertEquals(ModelErrorCode.CONNECT_TIMEOUT, degraded.errorCode());
        assertEquals(3, degraded.attempts(), "首次 + 2 次重试 = 3 次调用");
        assertEquals(List.of(Duration.ofMillis(200), Duration.ofMillis(400)), sleeps,
                "退避应是指数的：200ms → 400ms");
        assertTrue(outcome.degraded());
    }

    @Test
    @DisplayName("读超时：连上了但不返回，同样按预算重试，耗尽后降级为 READ_TIMEOUT")
    void test_read_timeout_is_retried_then_degrades() throws Exception {
        List<Duration> sleeps = ModelRetryPolicy.newSleeperRecord();
        try (OpenAiCompatibleStubServer stub = OpenAiCompatibleStubServer.start(API_KEY)) {
            stub.setResponseDelayMillis(3000);   // 端点超时配 1 秒：每次尝试都在 1 秒处被判超时
            ResilientModelGateway gateway = gatewayFor(stub.baseUrl(), 1, 1, sleeps);

            ModelOutcome<ZFieldMap> outcome = gateway.alignZFields(sampleZSegments());

            DegradedResult degraded = outcome.degradedResult().orElseThrow();
            assertEquals(ModelErrorCode.READ_TIMEOUT, degraded.errorCode(),
                    "端点可连但迟迟不返回，应归读超时而不是连接超时");
            assertEquals(2, degraded.attempts(), "首次 + 1 次重试");
            assertEquals(1, sleeps.size());
            assertEquals(2, stub.requestCount(), "重试必须真的打到了端点上（MODEL_MAX_RETRIES=1 → 两次请求）");
        }
    }

    @Test
    @DisplayName("非 2xx：一次就降级，不重试——401 重试一万次还是 401")
    void test_non_2xx_is_not_retried() throws Exception {
        List<Duration> sleeps = ModelRetryPolicy.newSleeperRecord();
        try (OpenAiCompatibleStubServer stub = OpenAiCompatibleStubServer.start(API_KEY)) {
            stub.setHttpStatus(401);
            ResilientModelGateway gateway = gatewayFor(stub.baseUrl(), 5, 3, sleeps);

            ModelOutcome<ZFieldMap> outcome = gateway.alignZFields(sampleZSegments());

            DegradedResult degraded = outcome.degradedResult().orElseThrow();
            assertEquals(ModelErrorCode.NON_2XX, degraded.errorCode());
            assertEquals(1, degraded.attempts(), "确定性子类不该重试");
            assertTrue(sleeps.isEmpty(), "不重试就没有退避");
            assertEquals(1, stub.requestCount());
            assertFalse(degraded.retryableButExhausted());
            assertTrue(degraded.reason().contains("该类失败重试无效"),
                    "降级说明应讲清为什么不重试：" + degraded.reason());
        }
    }

    @Test
    @DisplayName("响应不可解析：一次就降级为 UNPARSABLE，且与结构不符分开归类")
    void test_unparsable_response_is_not_retried() throws Exception {
        List<Duration> sleeps = ModelRetryPolicy.newSleeperRecord();
        try (OpenAiCompatibleStubServer stub = OpenAiCompatibleStubServer.start(API_KEY)) {
            // 合法 JSON 之外的东西：正文被网关截断、或被换成 HTML 错误页，真实端点都这么干过。
            stub.setContentOverride("{\"mappings\": [");
            ResilientModelGateway gateway = gatewayFor(stub.baseUrl(), 5, 3, sleeps);

            ModelOutcome<ZFieldMap> outcome = gateway.alignZFields(sampleZSegments());

            DegradedResult degraded = outcome.degradedResult().orElseThrow();
            assertEquals(ModelErrorCode.UNPARSABLE, degraded.errorCode());
            assertEquals(1, degraded.attempts());
            assertTrue(sleeps.isEmpty());
            assertEquals(1, stub.requestCount());
        }
    }

    @Test
    @DisplayName("结构校验失败：JSON 合法但不符合固定 Schema，归 SCHEMA_INVALID 而非 UNPARSABLE")
    void test_schema_invalid_response_is_not_retried() throws Exception {
        List<Duration> sleeps = ModelRetryPolicy.newSleeperRecord();
        try (OpenAiCompatibleStubServer stub = OpenAiCompatibleStubServer.start(API_KEY)) {
            stub.setContentOverride("{\"unexpected\":true}");
            ResilientModelGateway gateway = gatewayFor(stub.baseUrl(), 5, 3, sleeps);

            ModelOutcome<ZFieldMap> outcome = gateway.alignZFields(sampleZSegments());

            DegradedResult degraded = outcome.degradedResult().orElseThrow();
            assertEquals(ModelErrorCode.SCHEMA_INVALID, degraded.errorCode(),
                    "「是 JSON 但形状不对」与「压根不是 JSON」是两类不同的现场问题");
            assertEquals(1, degraded.attempts());
            assertTrue(sleeps.isEmpty());
        }
    }

    @Test
    @DisplayName("退避序列是指数的且封顶：200/400/800… 到 5 秒后不再增长")
    void test_backoff_is_exponential_and_capped() {
        ModelRetryPolicy policy = new ModelRetryPolicy(10, duration -> { });
        assertEquals(Duration.ofMillis(200), policy.backoffFor(1));
        assertEquals(Duration.ofMillis(400), policy.backoffFor(2));
        assertEquals(Duration.ofMillis(800), policy.backoffFor(3));
        assertEquals(ModelRetryPolicy.MAX_BACKOFF, policy.backoffFor(6),
                "不封顶的话 MODEL_MAX_RETRIES=10 的最后一次退避会比报文在队列里等的时间还长");
        assertEquals(ModelRetryPolicy.MAX_BACKOFF, policy.backoffFor(10));
        assertEquals(11, policy.maxAttempts(), "MODEL_MAX_RETRIES=10 表示「首次之外再试 10 次」");
        assertThrows(IllegalArgumentException.class, () -> new ModelRetryPolicy(11, d -> { }));
    }

    @Test
    @DisplayName("退避等待被中断时当场降级并把中断标志还回去，不空转重试")
    void test_interrupted_backoff_degrades_without_spinning() throws Exception {
        ModelRetryPolicy interrupted = new ModelRetryPolicy(3, duration -> {
            throw new InterruptedException("停机");
        });
        ResilientModelGateway gateway = new ResilientModelGateway(new AlwaysFailingGateway(), interrupted);

        ModelOutcome<ZFieldMap> outcome = gateway.alignZFields(sampleZSegments());

        DegradedResult degraded = outcome.degradedResult().orElseThrow();
        assertEquals(1, degraded.attempts(), "等待被打断就不该再试");
        assertTrue(degraded.detail().contains("被中断"));
        assertTrue(Thread.interrupted(), "中断标志必须还回去，否则上层停不下来");
    }

    // =====================================================================
    // 2. 红线：降级绝不推进业务状态
    // =====================================================================

    @Test
    @DisplayName("红线：降级结果注入后状态仍为待确认，患者主索引出站调用恰好 0 次")
    void test_degraded_never_advances_business_state() throws Exception {
        try (OpenAiCompatibleStubServer stub = OpenAiCompatibleStubServer.start(API_KEY)) {
            stub.setHttpStatus(503);      // 模型服务整体故障
            ArchiveIngestService ingest = ingestWith(gatewayFor(stub.baseUrl(), 5, 1, ModelRetryPolicy.newSleeperRecord()));

            ArchiveIngestService.IngestOutcome outcome = ingest.ingest(er7(ER7_WITH_Z));

            assertEquals(ArchiveIngestService.IngestStatus.ACCEPTED, outcome.status(),
                    "降级不影响这条被接收入账");
            assertEquals(MessageState.PENDING_CONFIRM, outcome.state(),
                    "降级只能停在待确认：绝不推进已落地，也绝不推进拒收");
            assertEquals(AckComposer.CODE_AE, outcome.ackCode());

            IdempotencyKey key = IdempotencyKey.of("ADT^A01^ADT_A01", "CTRL-T21-0001", "P");
            var row = store.findByIdempotencyKey(key).orElseThrow();
            assertEquals(MessageState.PENDING_CONFIRM, row.state());
            assertTrue(row.degraded(), "降级条目必须在台账上留下 degraded 标记");
            assertTrue(row.localMrn().isEmpty(), "降级不等于落地：院内主键不得被填上");
            assertEquals(1L, store.countDegradedPending(BATCH_ID), "本批低置信待确认应恰为 1 条");

            assertTrue(patientIndex.neverCalled(),
                    "没有人工确认时出站写必须恰好 0 次，实际 " + patientIndex.invocationCount());
        }
    }

    @Test
    @DisplayName("降级不把条目卡死：人工确认后照样落地并只写一次")
    void test_degraded_entry_can_still_be_confirmed_by_a_person() throws Exception {
        try (OpenAiCompatibleStubServer stub = OpenAiCompatibleStubServer.start(API_KEY)) {
            stub.setHttpStatus(500);
            ArchiveIngestService ingest = ingestWith(gatewayFor(stub.baseUrl(), 5, 0, ModelRetryPolicy.newSleeperRecord()));

            ingest.ingest(er7(ER7_WITH_Z));
            assertTrue(patientIndex.neverCalled());

            ArchiveIngestService.LandOutcome landed = ingest.land("ADT^A01^ADT_A01",
                    "CTRL-T21-0001", "P", "operator-021", "PAT-0001", "MRN-884512");

            assertTrue(landed.landed());
            assertTrue(landed.wrotePatientIndex());
            assertEquals(1, patientIndex.invocationCount());
            assertEquals(MessageState.LANDED,
                    store.findByIdempotencyKey(IdempotencyKey.of("ADT^A01^ADT_A01", "CTRL-T21-0001", "P"))
                            .orElseThrow().state());
        }
    }

    @Test
    @DisplayName("模型全挂：整批 8 条经真实 MLLP 帧打进监听口，8 条各回一条 MSA 回执，不出现漏回执")
    void test_all_model_down_still_acks_every_message() throws Exception {
        try (OpenAiCompatibleStubServer stub = OpenAiCompatibleStubServer.start(API_KEY)) {
            stub.setHttpStatus(500);      // 模型服务整体不可用
            ArchiveIngestService ingest = ingestWith(gatewayFor(stub.baseUrl(), 5, 0, ModelRetryPolicy.newSleeperRecord()));

            try (MllpServer server = new MllpServer("127.0.0.1", 0, ingest, 10000);
                 Socket client = new Socket()) {
                int port = server.start();
                client.connect(new InetSocketAddress("127.0.0.1", port), 5000);
                client.setSoTimeout(20000);

                for (int i = 0; i < 8; i++) {
                    String message = ER7_WITH_Z.replace("CTRL-T21-0001",
                            String.format("CTRL-T21-B%04d", i));
                    OutputStream out = client.getOutputStream();
                    out.write(MllpFrameCodec.encode(er7(message)));
                    out.flush();

                    InputStream in = client.getInputStream();
                    assertEquals(MllpFrameCodec.ACK, in.read(), "传输级确认仍要照发");
                    byte[] ack = MllpFrameCodec.readFrame(in);
                    assertNotNull(ack, "第 " + (i + 1) + " 条必须有应用级回执");
                    String text = new String(ack, StandardCharsets.UTF_8);
                    assertEquals("AE", msaField(text, 1),
                            "降级回 AE：让区域平台知道这条可重投，而不是以为已经处理完");
                    assertEquals(String.format("CTRL-T21-B%04d", i), msaField(text, 2),
                            "MSA-2 必须逐字复制入站 MSH-10，平台靠它把回执对回它发的那条");
                    assertTrue(text.contains("ERR|"), "AE 必须带 ERR 段");
                    assertTrue(text.contains("model-degraded"),
                            "ERR 应带上本端的降级码，实际：" + text);
                }

                assertEquals(8, server.receivedCount(), "监听口应收到 8 帧");
                assertEquals(8, server.ackedCount(), "8 帧都要各回一条应用级回执");
            }
        }

        // 台账侧：8 条全部入账、全部待确认、全部标了 degraded，一条都没被丢下。
        var counts = store.countByState(BATCH_ID);
        assertEquals(8L, counts.get(MessageState.PENDING_CONFIRM));
        assertEquals(0L, counts.get(MessageState.LANDED), "模型全挂不该有任何一条落地");
        assertEquals(0L, counts.get(MessageState.REJECTED), "模型全挂不该把任何一条判成拒收");
        assertEquals(8L, store.countDegradedPending(BATCH_ID));
        assertEquals(8, store.listByBatch(BATCH_ID, null).size(), "8 条一条不丢");
        assertTrue(patientIndex.neverCalled(),
                "模型全挂期间出站写必须恰好 0 次，实际 " + patientIndex.invocationCount());
    }

    // =====================================================================
    // 3. 降级与不降级的对照：模型健康时不该被误标降级
    // =====================================================================

    @Test
    @DisplayName("对照：模型健康时该条照常受理，回 AA、台账 degraded=false，不走降级分支")
    void test_healthy_model_does_not_degrade() throws Exception {
        try (OpenAiCompatibleStubServer stub = OpenAiCompatibleStubServer.start(API_KEY)) {
            ArchiveIngestService ingest = ingestWith(gatewayFor(stub.baseUrl(), 5, 2, ModelRetryPolicy.newSleeperRecord()));

            ArchiveIngestService.IngestOutcome outcome = ingest.ingest(er7(ER7_WITH_Z));

            assertEquals(ArchiveIngestService.IngestStatus.ACCEPTED, outcome.status());
            assertEquals(AckComposer.CODE_AA, outcome.ackCode(),
                    "模型健康就该照常回 AA，不能因为接了模型就一律降级");
            assertEquals(MessageState.PENDING_CONFIRM, outcome.state());
            assertEquals(1, stub.requestCount(), "带 Z 段的报文应当真的问了一次模型");

            var row = store.findByIdempotencyKey(
                    IdempotencyKey.of("ADT^A01^ADT_A01", "CTRL-T21-0001", "P")).orElseThrow();
            assertFalse(row.degraded(), "模型健康时不得标降级");
            assertEquals(0L, store.countDegradedPending(BATCH_ID));
        }
    }

    @Test
    @DisplayName("没有 Z 段的报文不发起模型调用：启用模型不等于每条都要调一次")
    void test_message_without_z_segments_makes_no_model_call() throws Exception {
        try (OpenAiCompatibleStubServer stub = OpenAiCompatibleStubServer.start(API_KEY)) {
            ArchiveIngestService ingest = ingestWith(gatewayFor(stub.baseUrl(), 5, 2, ModelRetryPolicy.newSleeperRecord()));

            ArchiveIngestService.IngestOutcome outcome = ingest.ingest(er7(ER7_NO_Z));

            assertEquals(AckComposer.CODE_AA, outcome.ackCode());
            assertEquals(0, stub.requestCount(),
                    "没有 Z 段就没有要对齐的字段，一次模型调用都不该发");
            assertEquals(0, audit.count(), "没发请求就不该有调用审计行");
            assertFalse(store.findByIdempotencyKey(IdempotencyKey.of("ADT^A01^ADT_A01", "CTRL-T21-0002", "P"))
                    .orElseThrow().degraded());
        }
    }

    @Test
    @DisplayName("模型可用时也应读得出 Z 段：ZX1.3 → pastHistorySummary")
    void test_z_segment_reader_feeds_the_gateway() throws Exception {
        try (OpenAiCompatibleStubServer stub = OpenAiCompatibleStubServer.start(API_KEY)) {
            ResilientModelGateway gateway = gatewayFor(stub.baseUrl(), 5, 0, ModelRetryPolicy.newSleeperRecord());

            ModelOutcome<ZFieldMap> outcome = gateway.alignZFields(
                    ZSegmentReader.read(cn.hospital.region.archive.hl7.FrameDecoder
                            .decode(er7(ER7_WITH_Z)).message));

            assertFalse(outcome.degraded());
            ZFieldMap map = outcome.value();
            assertEquals("pastHistorySummary", map.bindingOf("ZX1.3").orElseThrow().localField(),
                    "绑定以段内定位串为键（ZX1.3），不是段名");
            assertEquals("nextFollowUpDate", map.bindingOf("ZX2.3").orElseThrow().localField());
            assertEquals(2, map.size(), "两条 Z 段都应被读到（ZX1 与 ZX2）");
            assertTrue(stub.lastBody().contains("既往病史摘要"), "出站报文应带 Z 段平台字段名");
        }
    }

    // =====================================================================
    // 4. 留痕：失败调用同样写审计行，且带错误码
    // =====================================================================

    @Test
    @DisplayName("失败调用也留审计行并带错误码，token 记 0 而不是编一个数出来")
    void test_failed_calls_are_audited_with_error_code() throws Exception {
        try (OpenAiCompatibleStubServer stub = OpenAiCompatibleStubServer.start(API_KEY)) {
            stub.setHttpStatus(429);
            ResilientModelGateway gateway = gatewayFor(stub.baseUrl(), 5, 0, ModelRetryPolicy.newSleeperRecord());

            gateway.alignZFields(sampleZSegments());

            assertEquals(1, audit.count(), "挂掉的那天一个字都答不出来，是因为没留痕");
            ModelCallRecord record = audit.latest();
            assertTrue(record.degraded());
            assertEquals(ModelErrorCode.NON_2XX, record.errorCode());
            assertEquals(0, record.totalTokens(), "没拿到响应就没有 token 用量，记 0 不编数");
            assertFalse(record.inputSummary().contains("高血压"),
                    "审计行只留入参形态，不留原文：" + record.inputSummary());
            assertTrue(record.summaryLine().contains("NON_2XX"));
        }
    }

    @Test
    @DisplayName("降级行必须带错误码：否则报表里「降级了」退化成一个没法解释的布尔值")
    void test_degraded_audit_row_requires_error_code() {
        assertThrows(IllegalArgumentException.class, () -> new ModelCallRecord(
                FIXED_CLOCK.instant(), "m", OpenAiCompatibleGateway.OP_Z_FIELD_MAP, 1L,
                0, 0, "rid", true, "in"));
        // 成功行不带错误码是正常的
        ModelCallRecord ok = new ModelCallRecord(FIXED_CLOCK.instant(), "m",
                OpenAiCompatibleGateway.OP_Z_FIELD_MAP, 1L, 3, 4, "rid", false, "in", null);
        assertFalse(ok.summaryLine().contains("错误码"));
    }

    @Test
    @DisplayName("降级结果绝不冒充取值：拿 value() 直接抛，不给「降级当成空值用」的口子")
    void test_degraded_outcome_refuses_to_hand_out_a_value() {
        ModelOutcome<ZFieldMap> outcome = ModelOutcome.degraded(
                ModelErrorCode.CONNECT_TIMEOUT, OpenAiCompatibleGateway.OP_Z_FIELD_MAP, 3, "连不上");

        assertTrue(outcome.degraded());
        assertThrows(IllegalStateException.class, outcome::value);
        assertTrue(outcome.degradedResult().isPresent());
        assertEquals("model-degraded:CONNECT_TIMEOUT",
                outcome.degradedResult().orElseThrow().ledgerReason(),
                "台账审计 reason 的前缀要能与拒收类区分开");
    }

    // =====================================================================
    // 辅助：一个永远失败的可注入网关（只用于退避被打断这一类不需要真实端点的用例）
    // =====================================================================

    /** 恒定失败的可注入网关：不发网络请求，只按固定错误码抛，用来验退避被打断时的处置。 */
    private static final class AlwaysFailingGateway implements ModelGateway {

        @Override
        public ZFieldMap alignZFields(List<ZSegment> zSegments) {
            throw new ModelCallException(ModelErrorCode.CONNECT_TIMEOUT, "连不上语义对齐服务");
        }

        @Override
        public Candidates identityCandidates(String pid3, String pid5, String pid7,
                                              LocalPatientIndex localIndex) {
            throw new ModelCallException(ModelErrorCode.CONNECT_TIMEOUT, "连不上语义对齐服务");
        }
    }
}