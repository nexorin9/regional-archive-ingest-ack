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
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T38 验收 —— 院内 HTTP API 的路由、令牌校验与 409 语义。
 *
 * <h2>这一组在验什么</h2>
 * <ul>
 *   <li><b>令牌</b>：不带的 401、错的 401、对的才进；<b>操作人只从令牌取</b>，
 *       请求体里伪造的 {@code operator} 不采信（否则「谁点的」由调用方自己说了算）；</li>
 *   <li><b>409 语义</b>：已落地重复确认、拒收条目被确认、撤回不可撤回态 —— 一律 409，
 *       绝不静默 200，也绝不 500；</li>
 *   <li><b>批次归属</b>：A 批的行不能在 B 批的路径上确认；</li>
 *   <li><b>响应契约</b>：字段名固定、错误体带 {@code errorCode} 与中文说明、
 *       5xx 不带 SQL 片段与栈。</li>
 * </ul>
 *
 * <p><b>全部打真实 HTTP</b>：起真实 {@link HttpApiServer}（真实回环端口、真实 HTTP/1.1），
 * 用真实 {@link HttpClient} 发请求、逐字节比对响应。0 个 mock 库；
 * 台账是真实 H2 PG 兼容模式 + 真实 JDBC 事务 + 真实迁移脚本。
 * 方法顺序固定：最后一个用例会 DROP 掉表来构造 500 路径，必须排在最后。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HttpApiRouteTest {

    private static final String H2_URL =
            "jdbc:h2:mem:http_api_route_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-10-20T09:00:00Z"), ZoneId.of("Asia/Shanghai"));

    private static final String LOCAL_AET = "CHSCOMMUNITY";
    private static final String REMOTE_AET = "REGIONAL_ARCHIVE";
    private static final String API_TOKEN = "test-token-not-a-real-secret";
    private static final String OPERATOR = "clerk-007";
    private static final String BATCH_ID = "ARCHIVE-20261020-B";

    // 夹具按用例重建（不跨用例共享）：出站写计数是本类最关键的断言之一，
    // 共享一个计数器会让「必须恰好 0 次」这类断言退化成「比上一条多 0 次」。
    private HttpApiServer server;
    private HttpClient client;
    private String base;

    /** 对端回执列表的本地对端进程（真实 HTTP + 正式字段规范）。 */
    private HttpStub remote;

    private JdbcLedgerStore store;
    private CountingPatientIndexWriter patientIndex;
    private ArchiveIngestService ingest;

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
    void startServer() throws Exception {
        // 先清表再重建：每次用例都从「空台账 + 全新监听端口」开始。
        for (String table : new String[]{"confirm_refusal", "message_audit", "message_ledger"}) {
            try (Connection conn = openConnection();
                 PreparedStatement ps = conn.prepareStatement("DELETE FROM " + table)) {
                ps.executeUpdate();
            }
        }
        remote = new HttpStub(BATCH_ID, List.of("ARCHIVE-0001"));
        store = new JdbcLedgerStore(HttpApiRouteTest::openConnection, FIXED_CLOCK,
                AuditWriter.jdbc(FIXED_CLOCK));
        patientIndex = new CountingPatientIndexWriter();
        ingest = new ArchiveIngestService(new AckComposer(LOCAL_AET, REMOTE_AET, FIXED_CLOCK), store,
                new IdempotencyGuard(store), patientIndex, (header, message) -> BATCH_ID);

        Routes routes = new Routes(new ApiTokenGuard(API_TOKEN, LOCAL_AET),
                new ConfirmGate(store, ingest, new RevertService(store),
                        ConfirmRefusalJournal.jdbc(FIXED_CLOCK, HttpApiRouteTest::openConnection),
                        new JdbcPatientKeyStore(HttpApiRouteTest::openConnection)),
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

    // =====================================================================
    // 1. 令牌
    // =====================================================================

    @Test
    @Order(1)
    @DisplayName("GET /healthz 不带令牌也能通（探针没有凭据）")
    void healthz_needs_no_token() throws Exception {
        HttpResponse<String> r = get("/healthz", null);
        assertEquals(200, r.statusCode());
        assertTrue(r.body().contains("\"service\":\"" + Routes.SERVICE_NAME + "\""), r.body());
        assertTrue(r.body().contains("\"schemaVersion\":\"" + LedgerSchema.CURRENT_VERSION + "\""), r.body());
        assertTrue(r.body().contains("\"status\":\"UP\""), r.body());
    }

    @Test
    @Order(2)
    @DisplayName("四个业务端点缺令牌一律 401，且错误体带 errorCode")
    void all_business_endpoints_require_token() throws Exception {
        long id = ingestOne("ARCHIVE-0100");
        record Call(String method, String path, String body) {
        }
        List<Call> calls = List.of(
                new Call("GET", "/messages?state=PENDING_CONFIRM", null),
                new Call("GET", "/batches/" + BATCH_ID + "/stats", null),
                new Call("GET", "/batches/" + BATCH_ID + "/reconcile", null),
                new Call("POST", "/batches/" + BATCH_ID + "/confirm",
                        "{\"ledgerId\":" + id + ",\"patientRef\":\"PAT-0001\",\"localMrn\":\"MRN-1\"}"));
        for (Call call : calls) {
            HttpResponse<String> r = call.method().equals("GET")
                    ? get(call.path(), null)
                    : post(call.path(), null, call.body());
            assertEquals(401, r.statusCode(), call.path() + " 应当 401，实际 " + r.statusCode());
            assertTrue(r.body().contains("\"errorCode\":\"TOKEN_MISSING\""),
                    call.path() + " 错误体缺 errorCode：" + r.body());
            assertTrue(r.body().contains("\"message\""), r.body());
        }
        // 令牌校验发生在任何业务动作之前：出站写一次都不该发生。
        assertEquals(0, patientIndex.invocationCount(), "未鉴权的请求不得触发出站写");
    }

    @Test
    @Order(21)
    @DisplayName("确认端点不带令牌 → 401，且台账与出站写一个字节都不动")
    void test_confirm_without_token_returns_401() throws Exception {
        long id = ingestOne("ARCHIVE-0110");
        // 入账本身会写一条迁移审计行；基线取在这里，之后只比「有没有多出来」。
        int auditBaseline = auditRowCount(id);
        String body = "{\"ledgerId\":" + id + ",\"patientRef\":\"PAT-0110\",\"localMrn\":\"MRN-0110\"}";

        HttpResponse<String> noHeader = post("/batches/" + BATCH_ID + "/confirm", null, body);
        assertEquals(401, noHeader.statusCode(), noHeader.body());
        assertTrue(noHeader.body().contains("\"errorCode\":\"TOKEN_MISSING\""), noHeader.body());

        HttpResponse<String> wrongToken = post("/batches/" + BATCH_ID + "/confirm",
                "Bearer " + OPERATOR + ":not-the-token", body);
        assertEquals(401, wrongToken.statusCode(), wrongToken.body());
        assertTrue(wrongToken.body().contains("\"errorCode\":\"TOKEN_INVALID\""), wrongToken.body());

        assertEquals(0, patientIndex.invocationCount(), "未鉴权的确认不得触发出站写");
        assertEquals(MessageState.PENDING_CONFIRM, stateOf(id), "未鉴权的确认不得改台账");
        assertEquals(auditBaseline, auditRowCount(id), "未鉴权的确认不得写审计行");
    }

    @Test
    @Order(3)
    @DisplayName("令牌带错 / 主体名不合法 → 401 TOKEN_INVALID")
    void wrong_token_is_401() throws Exception {
        HttpResponse<String> wrong = get("/messages?state=PENDING_CONFIRM", "wrong-secret");
        assertEquals(401, wrong.statusCode());
        assertTrue(wrong.body().contains("\"errorCode\":\"TOKEN_INVALID\""), wrong.body());

        // 主体名只允许字母数字与 . _ - @；'!' 属非法字符，且是 ASCII（HTTP 头放不下中文）。
        HttpResponse<String> badPrincipal = get("/messages?state=PENDING_CONFIRM",
                "bad!principal:" + API_TOKEN);
        assertEquals(401, badPrincipal.statusCode());
        assertTrue(badPrincipal.body().contains("TOKEN_INVALID"), badPrincipal.body());
    }

    @Test
    @Order(4)
    @DisplayName("操作人只从令牌取：请求体里的 operator 字段不被采信")
    void operator_taken_from_token() throws Exception {
        long id = ingestOne("ARCHIVE-0200");
        HttpResponse<String> r = post("/batches/" + BATCH_ID + "/confirm",
                "Bearer " + OPERATOR + ":" + API_TOKEN,
                "{\"ledgerId\":" + id + ",\"patientRef\":\"PAT-0200\",\"localMrn\":\"MRN-0200\","
                        + "\"operator\":\"冒名的人\",\"candidateSource\":\"model-candidate#1\"}");
        assertEquals(200, r.statusCode(), r.body());
        assertTrue(r.body().contains("\"operator\":\"" + OPERATOR + "\""),
                "响应里的操作人必须来自令牌：" + r.body());
        assertFalse(r.body().contains("冒名的人"), "请求体里的 operator 不得被采信：" + r.body());

        // 审计行里的操作人也必须是令牌里的那个人。
        assertEquals(OPERATOR, auditOperatorOf(id), "审计行 operator 必须来自令牌");
    }

    @Test
    @Order(5)
    @DisplayName("裸令牌 → 机器主体 service:<本院 AE>，可与人工主体区分")
    void bare_token_yields_service_principal() throws Exception {
        long id = ingestOne("ARCHIVE-0201");
        HttpResponse<String> r = post("/batches/" + BATCH_ID + "/confirm",
                "Bearer " + API_TOKEN,
                "{\"ledgerId\":" + id + ",\"patientRef\":\"PAT-0201\",\"localMrn\":\"MRN-0201\"}");
        assertEquals(200, r.statusCode(), r.body());
        assertTrue(r.body().contains("\"operator\":\"service:" + LOCAL_AET + "\""), r.body());
        assertTrue(r.body().contains("\"humanClaimed\":false"),
                "机器主体必须与人工主体可区分：" + r.body());
    }

    // =====================================================================
    // 2. 409 语义
    // =====================================================================

    @Test
    @Order(10)
    @DisplayName("已落地条目重复确认 → 409，且出站写恰好一次")
    void test_repeat_confirm_returns_409() throws Exception {
        long id = ingestOne("ARCHIVE-0300");
        String body = "{\"ledgerId\":" + id + ",\"patientRef\":\"PAT-0300\",\"localMrn\":\"MRN-0300\"}";

        HttpResponse<String> first = post("/batches/" + BATCH_ID + "/confirm",
                "Bearer " + OPERATOR + ":" + API_TOKEN, body);
        assertEquals(200, first.statusCode(), first.body());
        assertTrue(first.body().contains("\"wrotePatientIndex\":true"), first.body());
        assertEquals(1, patientIndex.invocationCount(), "首次确认应恰好写一次");

        HttpResponse<String> second = post("/batches/" + BATCH_ID + "/confirm",
                "Bearer " + OPERATOR + ":" + API_TOKEN, body);
        assertEquals(409, second.statusCode(), "重复确认必须是 409，实际 " + second.statusCode()
                + "：" + second.body());
        assertTrue(second.body().contains("\"errorCode\":\"STATE_CONFLICT\""), second.body());
        assertTrue(second.body().contains("ALREADY_LANDED"), second.body());
        assertTrue(second.body().contains("\"confirmed\":false"), second.body());
        assertTrue(second.body().contains("\"state\":\"LANDED\""),
                "409 响应必须带当前状态，调用方据此把该条从待办去掉：" + second.body());
        assertEquals(1, patientIndex.invocationCount(), "重复确认不得再写一次院内主索引");

        // 台账与审计链没有被动过。
        assertEquals(1, countRows("message_ledger", id));
        assertEquals(2, auditRowCount(id), "落地只应有一条审计行（插入不算迁移）");
    }

    @Test
    @Order(11)
    @DisplayName("拒收条目被确认 → 409 NOT_PENDING_CONFIRM，且留拒绝痕")
    void confirm_rejected_message_is_409() throws Exception {
        long id = ingestMissingControlId();
        HttpResponse<String> r = post("/batches/" + BATCH_ID + "/confirm",
                "Bearer " + OPERATOR + ":" + API_TOKEN,
                "{\"ledgerId\":" + id + ",\"patientRef\":\"PAT-0400\",\"localMrn\":\"MRN-0400\"}");
        assertEquals(409, r.statusCode(), r.body());
        assertTrue(r.body().contains("NOT_PENDING_CONFIRM"), r.body());
        assertEquals(0, patientIndex.invocationCount(), "拒收条目不得触发任何出站写");
        assertEquals(1, refusalRowCount(), "被拒确认必须在 confirm_refusal 留一行");
    }

    @Test
    @Order(12)
    @DisplayName("台账里没有的行 / 不属于该批的行 → 404")
    void unknown_or_foreign_ledger_row_is_404() throws Exception {
        long id = ingestOne("ARCHIVE-0500");
        HttpResponse<String> missing = post("/batches/" + BATCH_ID + "/confirm",
                "Bearer " + OPERATOR + ":" + API_TOKEN,
                "{\"ledgerId\":999999,\"patientRef\":\"PAT-X\",\"localMrn\":\"MRN-X\"}");
        assertEquals(404, missing.statusCode(), missing.body());
        assertTrue(missing.body().contains("\"errorCode\":\"LEDGER_ROW_NOT_FOUND\""), missing.body());

        HttpResponse<String> wrongBatch = post("/batches/OTHER-BATCH/confirm",
                "Bearer " + OPERATOR + ":" + API_TOKEN,
                "{\"ledgerId\":" + id + ",\"patientRef\":\"PAT-0500\",\"localMrn\":\"MRN-0500\"}");
        assertEquals(404, wrongBatch.statusCode(),
                "A 批的行不能在 B 批的路径上被确认：" + wrongBatch.body());
        assertTrue(wrongBatch.body().contains("\"actualBatchId\":\"" + BATCH_ID + "\""), wrongBatch.body());
        assertEquals(0, patientIndex.invocationCount(), "批次不匹配不得触发出站写");
    }

    @Test
    @Order(13)
    @DisplayName("两个调用方并发确认同一条 → 恰好一个 200、一个 409，出站写恰好一次")
    void concurrent_confirm_has_single_winner() throws Exception {
        long id = ingestOne("ARCHIVE-0600");
        String body = "{\"ledgerId\":" + id + ",\"patientRef\":\"PAT-0600\",\"localMrn\":\"MRN-0600\"}";
        int threads = 8;
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<HttpResponse<String>>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                final String principal = "clerk-" + (100 + i);
                futures.add(pool.submit(() -> {
                    go.await();
                    return post("/batches/" + BATCH_ID + "/confirm",
                            "Bearer " + principal + ":" + API_TOKEN, body);
                }));
            }
            go.countDown();
            int ok = 0;
            int conflict = 0;
            for (Future<HttpResponse<String>> f : futures) {
                HttpResponse<String> r = f.get(20, TimeUnit.SECONDS);
                if (r.statusCode() == 200) {
                    ok++;
                } else if (r.statusCode() == 409) {
                    conflict++;
                }
            }
            assertEquals(1, ok, "并发确认必须恰好一个赢家");
            assertEquals(threads - 1, conflict, "其余全部应为 409");
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, patientIndex.invocationCount(), "并发下出站写仍只能有一次");
        assertEquals(MessageState.LANDED, stateOf(id));
    }

    // =====================================================================
    // 3. 请求契约
    // =====================================================================

    @Test
    @Order(20)
    @DisplayName("确认体缺字段 / 不是 JSON / ledgerId 非法 → 400 并点名字段")
    void bad_request_bodies_are_400() throws Exception {
        long id = ingestOne("ARCHIVE-0700");
        String path = "/batches/" + BATCH_ID + "/confirm";
        String auth = "Bearer " + OPERATOR + ":" + API_TOKEN;

        HttpResponse<String> noBody = post(path, auth, "");
        assertEquals(400, noBody.statusCode(), noBody.body());
        assertTrue(noBody.body().contains("\"field\":\"body\""), noBody.body());

        HttpResponse<String> noLedgerId = post(path, auth, "{\"patientRef\":\"P\",\"localMrn\":\"M\"}");
        assertEquals(400, noLedgerId.statusCode(), noLedgerId.body());
        assertTrue(noLedgerId.body().contains("ledgerId"), "缺字段必须点名：" + noLedgerId.body());

        HttpResponse<String> noMrn = post(path, auth,
                "{\"ledgerId\":" + id + ",\"patientRef\":\"PAT-0700\"}");
        assertEquals(400, noMrn.statusCode(), noMrn.body());
        assertTrue(noMrn.body().contains("localMrn"), "缺字段必须点名：" + noMrn.body());

        HttpResponse<String> badJson = post(path, auth, "{\"ledgerId\":");
        assertEquals(400, badJson.statusCode(), badJson.body());
        assertTrue(badJson.body().contains("\"errorCode\":\"MALFORMED_JSON\""), badJson.body());

        HttpResponse<String> badId = post(path, auth,
                "{\"ledgerId\":\"abc\",\"patientRef\":\"P\",\"localMrn\":\"M\"}");
        assertEquals(400, badId.statusCode(), badId.body());
        assertTrue(badId.body().contains("\"parameter\":\"ledgerId\""), badId.body());

        assertEquals(0, patientIndex.invocationCount(), "被拒的请求不得触发出站写");
        assertEquals(MessageState.PENDING_CONFIRM, stateOf(id), "被拒的请求不得改台账");
    }

    @Test
    @Order(21)
    @DisplayName("GET /messages：缺 state / state 拼错 → 400 并列出合法取值")
    void messages_parameter_validation() throws Exception {
        ingestOne("ARCHIVE-0800");
        HttpResponse<String> noState = get("/messages", API_TOKEN);
        assertEquals(400, noState.statusCode(), noState.body());
        assertTrue(noState.body().contains("\"field\":\"state\""), noState.body());

        HttpResponse<String> typo = get("/messages?state=RECEVIED", API_TOKEN);
        assertEquals(400, typo.statusCode(), typo.body());
        assertTrue(typo.body().contains("PENDING_CONFIRM"),
                "报错要列出合法取值：" + typo.body());

        HttpResponse<String> ok = get("/messages?state=PENDING_CONFIRM", API_TOKEN);
        assertEquals(200, ok.statusCode(), ok.body());
        assertTrue(ok.body().contains("\"total\":1"), ok.body());
        assertTrue(ok.body().contains("\"state\":\"PENDING_CONFIRM\""), ok.body());
        assertFalse(ok.body().contains("rawPayload"),
                "响应体不得带报文原文：" + ok.body());
    }

    @Test
    @Order(22)
    @DisplayName("GET /messages 跨批次拉全院待办 + limit 截断")
    void messages_pages_across_batches() throws Exception {
        ingestOne("ARCHIVE-0901");
        ingestOne("ARCHIVE-0902");
        ingestOne("ARCHIVE-0903");

        HttpResponse<String> all = get("/messages?state=PENDING_CONFIRM&limit=2", API_TOKEN);
        assertEquals(200, all.statusCode(), all.body());
        assertTrue(all.body().contains("\"total\":2"), "limit 生效：" + all.body());
        assertTrue(all.body().contains("\"limit\":2"), all.body());

        HttpResponse<String> filtered = get("/messages?state=PENDING_CONFIRM&batchId=" + BATCH_ID,
                API_TOKEN);
        assertEquals(200, filtered.statusCode(), filtered.body());
        assertTrue(filtered.body().contains("\"total\":3"), filtered.body());
    }

    @Test
    @Order(23)
    @DisplayName("撤回：可撤回态 200，不可撤回态 409，且不静默改数据")
    void revert_semantics() throws Exception {
        long id = ingestOne("ARCHIVE-1000");
        String body = "{\"ledgerId\":" + id + ",\"patientRef\":\"PAT-1000\",\"localMrn\":\"MRN-1000\"}";
        assertEquals(200, post("/batches/" + BATCH_ID + "/confirm",
                "Bearer " + OPERATOR + ":" + API_TOKEN, body).statusCode());

        HttpResponse<String> ok = post("/batches/" + BATCH_ID + "/revert",
                "Bearer " + OPERATOR + ":" + API_TOKEN,
                "{\"ledgerId\":" + id + ",\"reason\":\"居民信息员说这条挂错了人\"}");
        assertEquals(200, ok.statusCode(), ok.body());
        assertTrue(ok.body().contains("\"state\":\"PENDING_CONFIRM\""), ok.body());
        assertTrue(ok.body().contains("\"fromState\":\"LANDED\""), ok.body());
        assertEquals(MessageState.PENDING_CONFIRM, stateOf(id));

        long rejected = ingestMissingControlId();
        HttpResponse<String> refuse = post("/batches/" + BATCH_ID + "/revert",
                "Bearer " + OPERATOR + ":" + API_TOKEN,
                "{\"ledgerId\":" + rejected + ",\"reason\":\"试着撤一条拒收的\"}");
        assertEquals(409, refuse.statusCode(), refuse.body());
        assertTrue(refuse.body().contains("\"errorCode\":\"STATE_CONFLICT\""), refuse.body());

        HttpResponse<String> noReason = post("/batches/" + BATCH_ID + "/revert",
                "Bearer " + OPERATOR + ":" + API_TOKEN, "{\"ledgerId\":" + id + "}");
        assertEquals(400, noReason.statusCode(), noReason.body());
        assertTrue(noReason.body().contains("reason"), noReason.body());
    }

    @Test
    @Order(24)
    @DisplayName("撤回后重新确认仍命中同一幂等键，不产生第二条 HIS 写入")
    void revert_then_reconfirm_does_not_duplicate_his_write() throws Exception {
        long id = ingestOne("ARCHIVE-1100");
        String body = "{\"ledgerId\":" + id + ",\"patientRef\":\"PAT-1100\",\"localMrn\":\"MRN-1100\"}";
        String auth = "Bearer " + OPERATOR + ":" + API_TOKEN;
        assertEquals(200, post("/batches/" + BATCH_ID + "/confirm", auth, body).statusCode());
        assertEquals(200, post("/batches/" + BATCH_ID + "/revert", auth,
                "{\"ledgerId\":" + id + ",\"reason\":\"先撤一条再确认\"}").statusCode());

        HttpResponse<String> again = post("/batches/" + BATCH_ID + "/confirm", auth, body);
        assertEquals(200, again.statusCode(), again.body());
        assertEquals(1, patientIndex.invocationCount(), "同一院内主键重确认不得再写一次");
        assertEquals(1, countRows("message_ledger", id));
    }

    // =====================================================================
    // 4. 只读端点
    // =====================================================================

    @Test
    @Order(30)
    @DisplayName("GET /batches/{id}/stats：四态分布与拒收原因")
    void stats_reports_four_states() throws Exception {
        ingestOne("ARCHIVE-1200");
        ingestOne("ARCHIVE-1201");
        ingestMissingControlId();

        HttpResponse<String> r = get("/batches/" + BATCH_ID + "/stats", API_TOKEN);
        assertEquals(200, r.statusCode(), r.body());
        assertTrue(r.body().contains("\"total\":3"), r.body());
        assertTrue(r.body().contains("\"pendingConfirm\":2"), r.body());
        assertTrue(r.body().contains("\"rejected\":1"), r.body());
        assertTrue(r.body().contains("\"landed\":0"), r.body());
        assertTrue(r.body().contains("\"counts\""), r.body());
        assertTrue(r.body().contains("\"retryStates\""), r.body());

        HttpResponse<String> empty = get("/batches/NO-SUCH-BATCH/stats", API_TOKEN);
        assertEquals(200, empty.statusCode(), "不存在的批次应报 0 条而不是报错：" + empty.body());
        assertTrue(empty.body().contains("\"total\":0"), empty.body());
    }

    @Test
    @Order(31)
    @DisplayName("GET .../reconcile：差集 + 控制号为空单列，不入重投队列")
    void reconcile_reports_diff_without_enqueuing() throws Exception {
        ingestOne("ARCHIVE-1300");   // 对端已认（stub 只认 ARCHIVE-0001，这里换一条）
        ingestOne("ARCHIVE-0001");   // 对端已认
        ingestOne("ARCHIVE-1301");   // 对端没认 → 缺失
        ingestMissingControlId();    // 控制号为空 → 无法比对

        HttpResponse<String> r = get("/batches/" + BATCH_ID + "/reconcile", API_TOKEN);
        assertEquals(200, r.statusCode(), r.body());
        // 对端只认了 ARCHIVE-0001，因此 1300 与 1301 两条都进缺失清单。
        assertTrue(r.body().contains("\"missingCount\":2"), r.body());
        assertTrue(r.body().contains("\"matchedCount\":1"), r.body());
        assertTrue(r.body().contains("ARCHIVE-1301"), "缺失清单要列出控制号：" + r.body());
        assertTrue(r.body().contains("ARCHIVE-1300"), "缺失清单要列出控制号：" + r.body());
        assertTrue(r.body().contains("\"uncomparableCount\":1"),
                "控制号为空的条目必须单列而不是被吞掉：" + r.body());
        assertTrue(r.body().contains("\"complete\":false"), r.body());
        // 只读：不入队。
        assertEquals(0, queueRowCount(), "对账端点只出清单，不得写重投队列");
    }

    @Test
    @Order(32)
    @DisplayName("未知路径也返回 JSON 错误体（不是 JDK 默认 HTML 页）")
    void unknown_route_returns_json() throws Exception {
        HttpResponse<String> r = get("/no/such/path", API_TOKEN);
        assertEquals(404, r.statusCode(), r.body());
        assertTrue(r.body().startsWith("{"), "错误体必须是 JSON：" + r.body());
        assertTrue(r.body().contains("\"errorCode\":\"ROUTE_NOT_FOUND\""), r.body());
        assertEquals("application/json; charset=utf-8",
                r.headers().firstValue("Content-Type").orElse(""));
    }

    // =====================================================================
    // 5. 500 路径（必须最后：会 DROP 掉台账表）
    // =====================================================================

    @Test
    @Order(99)
    @DisplayName("台账不可用 → 500 且响应体不含 SQL 表名与栈")
    void internal_error_does_not_leak_internals() throws Exception {
        long id = ingestOne("ARCHIVE-9900");
        try (Connection conn = openConnection(); Statement st = conn.createStatement()) {
            st.execute("DROP TABLE message_ledger");
        }
        HttpResponse<String> r = post("/batches/" + BATCH_ID + "/confirm",
                "Bearer " + OPERATOR + ":" + API_TOKEN,
                "{\"ledgerId\":" + id + ",\"patientRef\":\"PAT-9900\",\"localMrn\":\"MRN-9900\"}");
        assertEquals(500, r.statusCode(), r.body());
        assertTrue(r.body().contains("\"errorCode\":\"INTERNAL_ERROR\""), r.body());
        assertFalse(r.body().contains("message_ledger"),
                "5xx 响应不得带 SQL 表名：" + r.body());
        assertFalse(r.body().contains("SQLException"), "5xx 响应不得带异常类型：" + r.body());
        assertFalse(r.body().contains("at cn.hospital"),
                "5xx 响应不得带栈帧：" + r.body());
    }

    // =====================================================================
    // 辅助
    // =====================================================================

    private long ingestOne(String controlId) throws Exception {
        return ingest.ingest(er7(controlId, "PAT-" + controlId)).ledgerId();
    }

    private long ingestMissingControlId() throws Exception {
        String text = String.join("\r",
                "MSH|^~\\&|REGIONAL_ARCHIVE|FACILITY_A|ARCHIVE_IN|FACILITY_A|20261020090000"
                        + "||ADT^A01^ADT_A01||P|2.5.1|||AL|NE|UNICODE UTF-8",
                "PID|1||PAT-NOCTRL^^^FACILITY_A^MR||王^五||19820304|F",
                "");
        return ingest.ingest(text.getBytes(StandardCharsets.UTF_8)).ledgerId();
    }

    private static byte[] er7(String controlId, String patientRef) {
        return String.join("\r",
                "MSH|^~\\&|REGIONAL_ARCHIVE|FACILITY_A|ARCHIVE_IN|FACILITY_A|20261020090000"
                        + "||ADT^A01^ADT_A01|" + controlId + "|P|2.5.1|||AL|NE|UNICODE UTF-8",
                "PID|1||" + patientRef + "^^^FACILITY_A^MR||样^例||19800101|M",
                "").getBytes(StandardCharsets.UTF_8);
    }

    private HttpResponse<String> get(String path, String token) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(10)).GET();
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> post(String path, String authorization, String body)
            throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (authorization != null) {
            b.header("Authorization", authorization);
        }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static int countRows(String table, long id) throws SQLException {
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM " + table + " WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private static int auditRowCount(long ledgerId) throws SQLException {
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM message_audit WHERE ledger_id = ?")) {
            ps.setLong(1, ledgerId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private static int refusalRowCount() throws SQLException {
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM confirm_refusal");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private static int queueRowCount() throws SQLException {
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM redelivery_queue");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private static String auditOperatorOf(long ledgerId) throws SQLException {
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT operator FROM message_audit WHERE ledger_id = ? AND to_state = ? "
                             + "ORDER BY id DESC")) {
            ps.setLong(1, ledgerId);
            ps.setString(2, MessageState.LANDED.dbValue());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static MessageState stateOf(long ledgerId) throws SQLException {
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT state FROM message_ledger WHERE id = ?")) {
            ps.setLong(1, ledgerId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? MessageState.fromDbValue(rs.getString(1)) : null;
            }
        }
    }

    /**
     * 对端回执列表的本地对端进程：真实 HTTP + {@code BatchReconcilerClient} 声明的正式字段规范。
     *
     * <p>它只认 {@code ARCHIVE-0001} 这一条控制号，其余一律不在回执列表里 ——
     * 「差集里必须有缺失项」才有意义。
     */
    private static final class HttpStub implements AutoCloseable {

        private final HttpServer server;
        private final String baseUrl;

        HttpStub(String batchId, List<String> ackedControlIds) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
            List<String> acks = new ArrayList<>();
            for (String ctrl : ackedControlIds) {
                acks.add("{\"controlId\":\"" + ctrl + "\",\"messageType\":\"ADT^A01^ADT_A01\","
                        + "\"msaCode\":\"AA\",\"receivedAt\":\"2026-10-20T01:05:00Z\"}");
            }
            byte[] payload = ("{\"batchId\":\"" + batchId + "\",\"declaredAckCount\":" + acks.size()
                    + ",\"acks\":[" + String.join(",", acks) + "]}")
                    .getBytes(StandardCharsets.UTF_8);
            server.createContext("/batches/" + batchId + "/acks", exchange -> {
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(200, payload.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(payload);
                }
                exchange.close();
            });
            server.start();
            baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        }

        String baseUrl() {
            return baseUrl;
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}