package cn.hospital.region.archive.http;

import cn.hospital.region.archive.ack.AckComposer;
import cn.hospital.region.archive.identity.ConfirmGate;
import cn.hospital.region.archive.identity.ConfirmRefusalJournal;
import cn.hospital.region.archive.identity.CountingPatientIndexWriter;
import cn.hospital.region.archive.identity.JdbcPatientKeyStore;
import cn.hospital.region.archive.ledger.ArchiveIngestService;
import cn.hospital.region.archive.ledger.AuditWriter;
import cn.hospital.region.archive.ledger.IdempotencyGuard;
import cn.hospital.region.archive.ledger.JdbcLedgerStore;
import cn.hospital.region.archive.ledger.LedgerSchema;
import cn.hospital.region.archive.ledger.MessageState;
import cn.hospital.region.archive.ledger.RevertService;
import cn.hospital.region.archive.redelivery.BatchReconcilerClient;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T39 的「OpenAPI 契约 ↔ 真实 HTTP」验证 ——<b>用真 HTTP 客户端</b>打端点，
 * 拿响应去对齐 {@code openapi.yaml} 的预期结构。
 *
 * <h2>与 T38 的分工</h2>
 * <p>T38 的 {@link HttpApiRouteTest} 守住**业务语义**（409、令牌、操作人来源）；
 * 本测试守住<b>契约对齐</b>：响应字段名固定、错误体带 {@code errorCode} +
 * {@code message} + {@code status}、一条确认请求打过去确实把台账从待确认拨到已落地。
 *
 * <h2>本任务必须解决的两个新失败模式</h2>
 * <ul>
 *   <li><b>字段名漂移</b>：实现里把 {@code processedAt} 改成了 {@code processedOn}，
 *       调用方按时拿不到值 ——
 *       每条断言都对响应字段做「在文档里写了就要在响应里出现」 的双向核对；</li>
 *   <li><b>接口层与存储层没打通</b>：路由返回 200 但台账其实没改 ——
 *       {@code test_confirm_over_http_flips_state_to_LANDED} 直接查 message_ledger
 *       的 state 列、把响应里写的状态与库里的真实状态做相等性断言。</li>
 * </ul>
 *
 * <p><b>0 mock 库</b>：起真 {@link HttpServer}、真 {@link HttpClient}、真 H2 PG 兼容
 * 模式 + 真 JDBC 事务 + 真迁移脚本。
 */
class HttpApiContractTest {

    private static final String H2_URL =
            "jdbc:h2:mem:http_api_contract_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-10-20T09:00:00Z"), ZoneId.of("Asia/Shanghai"));

    private static final String LOCAL_AET = "CHSCOMMUNITY";
    private static final String REMOTE_AET = "REGIONAL_ARCHIVE";
    private static final String API_TOKEN = "test-token-not-a-real-secret";
    private static final String OPERATOR = "clerk-contract";
    private static final String BATCH_ID = "ARCHIVE-20261020-CT";

    private HttpApiServer server;
    private HttpStub remote;
    private HttpClient client;
    private String base;
    private JdbcLedgerStore store;
    private CountingPatientIndexWriter patientIndex;
    private ArchiveIngestService ingest;

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
    void startServer() throws Exception {
        // 清表是为了不让用例间串味（与 T38 同款约定）。
        for (String table : new String[]{"confirm_refusal", "message_audit", "message_ledger"}) {
            try (Connection conn = openConnection();
                 PreparedStatement ps = conn.prepareStatement("DELETE FROM " + table)) {
                ps.executeUpdate();
            }
        }
        remote = new HttpStub(BATCH_ID, List.of("ARCHIVE-CT-0001"));
        store = new JdbcLedgerStore(HttpApiContractTest::openConnection, FIXED_CLOCK,
                AuditWriter.jdbc(FIXED_CLOCK));
        patientIndex = new CountingPatientIndexWriter();
        ingest = new ArchiveIngestService(new AckComposer(LOCAL_AET, REMOTE_AET, FIXED_CLOCK), store,
                new IdempotencyGuard(store), patientIndex, (header, message) -> BATCH_ID);

        Routes routes = new Routes(new ApiTokenGuard(API_TOKEN, LOCAL_AET),
                new ConfirmGate(store, ingest, new RevertService(store),
                        ConfirmRefusalJournal.jdbc(FIXED_CLOCK, HttpApiContractTest::openConnection),
                        new JdbcPatientKeyStore(HttpApiContractTest::openConnection)),
                store, store,
                new BatchReconcilerClient(remote.baseUrl(), "reconcile-token", Duration.ofSeconds(5)),
                key -> "", LOCAL_AET, FIXED_CLOCK, LedgerSchema.CURRENT_VERSION);

        server = HttpApiServer.start("127.0.0.1", 0, routes);
        base = "http://127.0.0.1:" + server.port();
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.close();
            server = null;
        }
        if (remote != null) {
            remote.close();
            remote = null;
        }
    }

    // ===========================================================================
    // T39 钦定两例
    // ===========================================================================

    @Test
    @DisplayName("test_confirm_over_http_flips_state_to_LANDED —— 接口真的把台账从待确认拨到已落地")
    void test_confirm_over_http_flips_state_to_LANDED() throws Exception {
        long ledgerId = ingestOne("ARCHIVE-CT-LED");
        promoteToPendingConfirm(ledgerId);

        assertEquals(MessageState.PENDING_CONFIRM, stateOf(ledgerId),
                "落接口前必须先确认台账处于待确认这一档，否则断言没意义");

        String body = "{\"ledgerId\":" + ledgerId
                + ",\"patientRef\":\"PAT-CT-1\",\"localMrn\":\"MRN-CT-1\"}";

        HttpResponse<String> r = post("/batches/" + BATCH_ID + "/confirm",
                "Bearer " + OPERATOR + ":" + API_TOKEN, body);

        assertEquals(200, r.statusCode(), "确认必须返回 200：" + r.body());
        assertTrue(r.body().contains("\"state\":\"LANDED\""),
                "响应里 state 必须是 LANDED，文档如此承诺：" + r.body());
        assertTrue(r.body().contains("\"wrotePatientIndex\":true"),
                "wrotePatientIndex 必须为 true：" + r.body());

        // 真正的接口层 → 存储层打通证据：直接查 message_ledger.state 列。
        assertEquals(MessageState.LANDED, stateOf(ledgerId),
                "接口返回 LANDED，但 message_ledger.state 没被推进到这里，必为 bug");

        assertEquals(1, patientIndex.invocationCount(),
                "确认落地一次，对应出站写应当恰好一次");
    }

    @Test
    @DisplayName("test_openapi_paths_match_registered_routes —— openapi.yaml 的路径集合等于 Routes")
    void test_openapi_paths_match_registered_routes() throws Exception {
        // 直接调用已经定型的 OpenApiConsistencyTest 的同一份产物：
        // 不重复定义 collection，避免「测试改测试，永不报错」。
        OpenApiSpec spec = OpenApiSpec.loadFromClasspath("http/openapi.yaml");

        List<String> declared = new ArrayList<>();
        for (Routes.DeclaredRoute r : Routes.declaredRoutes()) {
            declared.add(r.method() + " " + r.pathTemplate());
        }
        List<String> documented = new ArrayList<>();
        for (String p : spec.paths()) {
            for (String m : spec.methodsUnder(p)) {
                documented.add(m + " " + p);
            }
        }
        // 把列表排序后再比，差异顺序不应当诱导失败。
        Collections.sort(declared);
        Collections.sort(documented);

        assertEquals(declared, documented,
                "Routes 注册的所有方法必须在 openapi.yaml 里出现，反之亦然");
    }

    // ===========================================================================
    // 401 拒绝 / 字段对齐
    // ===========================================================================

    @Test
    @DisplayName("/healthz 响应字段都对齐 OpenAPI（status/service/apiVersion/localAet/schemaVersion/time）")
    void healthz_response_contains_documented_fields() throws Exception {
        HttpResponse<String> r = get("/healthz", null);
        assertEquals(200, r.statusCode());
        for (String field : List.of("status", "service", "apiVersion",
                "localAet", "schemaVersion", "time")) {
            assertTrue(r.body().contains("\"" + field + "\":"),
                    "OpenAPI 文档要求响应里必须有 " + field + "：" + r.body());
        }
    }

    @Test
    @DisplayName("错误响应含 OpenAPI 文档承诺的三项最小字段：errorCode / message / status")
    void error_body_has_minimum_documented_fields() throws Exception {
        // 故意打坏请求：缺令牌。
        HttpResponse<String> r = get("/messages?state=PENDING_CONFIRM", null);
        assertEquals(401, r.statusCode());
        for (String field : List.of("errorCode", "message", "status")) {
            assertTrue(r.body().contains("\"" + field + "\":"),
                    "错误响应必须含 " + field + "：实际 " + r.body());
        }
        // errorCode 取值在文档枚举里。
        assertTrue(r.body().contains("\"errorCode\":\"TOKEN_MISSING\""),
                "TOKEN_MISSING 必须在文档枚举里，实测响应：" + r.body());
    }

    @Test
    @DisplayName("POST /confirm 200 响应的字段都对齐 OpenAPI（confirmed/ledgerId/operator/state/wrotePatientIndex）")
    void confirm_success_response_has_documented_fields() throws Exception {
        long id = ingestOne("ARCHIVE-CT-FLD");
        promoteToPendingConfirm(id);
        String body = "{\"ledgerId\":" + id + ",\"patientRef\":\"PAT-CT-FLD\",\"localMrn\":\"MRN-CT-FLD\"}";
        HttpResponse<String> r = post("/batches/" + BATCH_ID + "/confirm",
                "Bearer " + OPERATOR + ":" + API_TOKEN, body);
        assertEquals(200, r.statusCode(), r.body());
        for (String field : List.of("confirmed", "ledgerId", "batchId", "operator",
                "humanClaimed", "state", "wrotePatientIndex")) {
            assertTrue(r.body().contains("\"" + field + "\":"),
                    "确认成功响应必须含 " + field + "：" + r.body());
        }
    }

    // ===========================================================================
    // 400 / 404 / 409 / 503 各档错误码都符合 OpenAPI
    // ===========================================================================

    @Test
    @DisplayName("POST /confirm 缺 ledgerId → 400 MISSING_FIELD，errorCode 在枚举内")
    void confirm_missing_field_returns_400() throws Exception {
        String body = "{\"patientRef\":\"PAT-X\",\"localMrn\":\"MRN-X\"}";
        HttpResponse<String> r = post("/batches/" + BATCH_ID + "/confirm",
                "Bearer " + OPERATOR + ":" + API_TOKEN, body);
        assertEquals(400, r.statusCode(), r.body());
        assertTrue(r.body().contains("\"errorCode\":\"MISSING_FIELD\""), r.body());
    }

    @Test
    @DisplayName("POST /confirm 跨批次 ledgerId → 404 LEDGER_ROW_NOT_FOUND")
    void confirm_wrong_batch_returns_404() throws Exception {
        long id = ingestOne("ARCHIVE-CT-XB");
        promoteToPendingConfirm(id);
        // 错批次号：BATCH_ID 是 ARCHIVE-20261020-CT，本条强行发到 ARCHIVE-OTHER
        String body = "{\"ledgerId\":" + id + ",\"patientRef\":\"PAT-XB\",\"localMrn\":\"MRN-XB\"}";
        HttpResponse<String> r = post("/batches/ARCHIVE-OTHER/confirm",
                "Bearer " + OPERATOR + ":" + API_TOKEN, body);
        assertEquals(404, r.statusCode(), r.body());
        assertTrue(r.body().contains("\"errorCode\":\"LEDGER_ROW_NOT_FOUND\""), r.body());
    }

    @Test
    @DisplayName("POST /confirm 已落地条目重复确认 → 409 STATE_CONFLICT，含文档承诺的 ALREADY_LANDED")
    void confirm_already_landed_returns_409() throws Exception {
        long id = ingestOne("ARCHIVE-CT-DUP");
        promoteToPendingConfirm(id);
        String body = "{\"ledgerId\":" + id + ",\"patientRef\":\"PAT-DUP\",\"localMrn\":\"MRN-DUP\"}";
        HttpResponse<String> first = post("/batches/" + BATCH_ID + "/confirm",
                "Bearer " + OPERATOR + ":" + API_TOKEN, body);
        assertEquals(200, first.statusCode(), first.body());
        HttpResponse<String> second = post("/batches/" + BATCH_ID + "/confirm",
                "Bearer " + OPERATOR + ":" + API_TOKEN, body);
        assertEquals(409, second.statusCode(),
                "重复确认按文档约定必须是 409（STATE_CONFLICT）：实测 " + second.statusCode());
        assertTrue(second.body().contains("\"errorCode\":\"STATE_CONFLICT\""), second.body());
        assertTrue(second.body().contains("ALREADY_LANDED"), second.body());
        // 即使 409，台账不能被改了 state。
        assertEquals(MessageState.LANDED, stateOf(id));
    }

    @Test
    @DisplayName("POST /messages 缺 state → 400 INVALID_PARAMETER，errorCode 在枚举内")
    void messages_missing_state_returns_400() throws Exception {
        HttpResponse<String> r = get("/messages", "Bearer " + OPERATOR + ":" + API_TOKEN);
        assertEquals(400, r.statusCode(), r.body());
        assertTrue(r.body().contains("\"errorCode\":\"MISSING_FIELD\""), r.body());
    }

    @Test
    @DisplayName("POST /messages state 拼错 → 400 INVALID_PARAMETER，合法取值在响应里")
    void messages_invalid_state_lists_legal_values() throws Exception {
        HttpResponse<String> r = get("/messages?state=RECEVIED",
                "Bearer " + OPERATOR + ":" + API_TOKEN);
        assertEquals(400, r.statusCode(), r.body());
        assertTrue(r.body().contains("\"errorCode\":\"INVALID_PARAMETER\""), r.body());
        assertTrue(r.body().contains("PENDING_CONFIRM"),
                "响应必须把合法取值列出来给调用方改：" + r.body());
    }

    @Test
    @DisplayName("打错路径 → 404 ROUTE_NOT_FOUND，响应是 JSON 而非默认 HTML")
    void unknown_route_returns_json_404() throws Exception {
        HttpResponse<String> r = get("/totally-unknown", "Bearer " + OPERATOR + ":" + API_TOKEN);
        assertEquals(404, r.statusCode(), r.body());
        assertTrue(r.body().contains("\"errorCode\":\"ROUTE_NOT_FOUND\""),
                "未知路径必须按错误体格式返回：" + r.body());
        String contentType = r.headers().firstValue("content-type").orElse("");
        assertTrue(contentType.contains("application/json"),
                "即使 404，响应也必须是 JSON（与 default JDK HTML 页区分）：" + contentType);
    }

    // ===========================================================================
    // 帮助函数
    // ===========================================================================

    private HttpResponse<String> get(String path, String authHeader) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).GET();
        if (authHeader != null) {
            b.header("Authorization", authHeader);
        }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> post(String path, String authHeader, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (authHeader != null) {
            b.header("Authorization", authHeader);
        }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /** 入账一条报文，并断言其落到了 PENDING_CONFIRM。 */
    private long ingestOne(String controlId) throws Exception {
        ArchiveIngestService.IngestOutcome outcome = ingest.ingest(
                ("MSH|^~\\&|" + LOCAL_AET + "|" + REMOTE_AET + "|REGIONAL|FACILITY|"
                        + FIXED_CLOCK.instant() + "||ADT^A08|" + controlId + "|P|2.5.1||||||UNICODE UTF-8\r"
                        + "PID|1||" + controlId + "^^^REGIONAL^MR||张^伟||19850512|M|||||||\r").getBytes(StandardCharsets.UTF_8));
        long id = outcome.ledgerId();
        // 入账主路径一般走到 PENDING_CONFIRM（模型环节无降级且无冲突）。
        assertEquals(MessageState.PENDING_CONFIRM, stateOf(id),
                "入账主路径应当让报文落在 PENDING_CONFIRM：" + controlId);
        return id;
    }

    private void promoteToPendingConfirm(long id) throws Exception {
        // ingestOne 已经把状态推到 PENDING_CONFIRM：
        // 不在这里再 transition —— 再 transition 会与「已不在 RECEIVED」的库冲突。
        assertEquals(MessageState.PENDING_CONFIRM, stateOf(id),
                "入账后必须是 PENDING_CONFIRM，否则下面对 /confirm 接口的断言没意义");
    }

    private MessageState stateOf(long id) throws SQLException {
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT state FROM message_ledger WHERE id=?")) {
            ps.setLong(1, id);
            try (var rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("ledger 里没有 id=" + id);
                }
                return MessageState.fromDbValue(rs.getString(1));
            }
        }
    }

    /** 仿 T38 的对端回执替身（拉取 ACK 列表，最小化实现）。 */
    static final class HttpStub implements AutoCloseable {

        private final HttpServer server;
        private final String batchId;
        private final List<String> declaredControlIds;

        HttpStub(String batchId, List<String> declaredControlIds) throws IOException {
            this.batchId = batchId;
            this.declaredControlIds = declaredControlIds;
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                byte[] body = ("{\"batchId\":\"" + batchId
                        + "\",\"controlIds\":["
                        + String.join(",", declaredControlIds.stream()
                                .map(s -> "\"" + s + "\"").toList())
                        + "]}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            });
            server.start();
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
