package cn.hospital.region.archive.http;

import cn.hospital.region.archive.ack.AckComposer;
import cn.hospital.region.archive.identity.ConfirmGate;
import cn.hospital.region.archive.identity.ConfirmRefusalJournal;
import cn.hospital.region.archive.identity.JdbcPatientKeyStore;
import cn.hospital.region.archive.ledger.ArchiveIngestService;
import cn.hospital.region.archive.ledger.AuditWriter;
import cn.hospital.region.archive.ledger.IdempotencyGuard;
import cn.hospital.region.archive.ledger.JdbcLedgerStore;
import cn.hospital.region.archive.ledger.LedgerSchema;
import cn.hospital.region.archive.ledger.RevertService;
import cn.hospital.region.archive.redelivery.BatchReconcilerClient;

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
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code --api-smoke}：把无界面 API 在<b>真实回环端口</b>上跑一遍并打印逐条响应。
 *
 * <h2>它是什么，不是什么</h2>
 * <p><b>是</b>一条可复制的自检：起真实 {@link HttpApiServer}（真 socket、真 HTTP/1.1、
 * 真 JSON），灌一批报文进真实台账，再按真实 HTTP 协议逐个调端点，把状态码与响应体打出来。
 * 它与 {@code --mllp-smoke} / {@code --ack-contract-smoke} / {@code --capacity-benchmark}
 * 同一性质：<b>不读任何凭据、不连任何院内系统</b>，跑的是回环口与内嵌 H2。
 *
 * <p><b>不是</b>产品默认启动路径。生产启动仍然要求
 * {@code ARCHIVE_DB_URL} 等全套环境变量齐全（T2 的 fail fast），本命令<b>不</b>替代它、
 * 也<b>不</b>给真实接口加显式开关。回执对账端点在本自检里指向一个按正式字段规范实现的
 * 本地对端进程（真实 HTTP + 真实 JSON），与 T34 的对端替身同一口径。
 */
public final class ApiSmoke {

    /** 内嵌台账（PG 兼容模式）：与 T11 起的全部台账测试同一条建表脚本。 */
    private static final String H2_URL =
            "jdbc:h2:mem:api_smoke;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

    private static final String LOCAL_AET = "CHSCOMMUNITY";
    private static final String REMOTE_AET = "REGIONAL_ARCHIVE";
    private static final String API_TOKEN = "smoke-token-local-only";
    private static final String BATCH_ID = "ARCHIVE-20261020-A";
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-10-20T09:00:00Z"), ZoneId.of("Asia/Shanghai"));

    private ApiSmoke() {
    }

    /**
     * 跑一遍自检。
     *
     * @return 全部断言通过为 0，否则为 1
     */
    public static int run(java.io.PrintStream out) {
        List<String> failures = new ArrayList<>();
        try {
            Connection conn = openConnection();
            conn.setAutoCommit(false);
            LedgerSchema.migrate(conn);
            conn.commit();
            conn.close();

            JdbcLedgerStore store = new JdbcLedgerStore(ApiSmoke::openConnection, FIXED_CLOCK,
                    AuditWriter.jdbc(FIXED_CLOCK));
            // 批次号固定成 BATCH_ID：默认口径是「发送应用 + 日期」，那样批次号会随日期漂，
            // 自检里就没法用同一个 /batches/{batchId} 去对账了。
            ArchiveIngestService ingest = new ArchiveIngestService(
                    new AckComposer(LOCAL_AET, REMOTE_AET, FIXED_CLOCK), store,
                    new IdempotencyGuard(store), new SilentPatientIndexWriter(),
                    (header, message) -> BATCH_ID);
            ConfirmGate gate = new ConfirmGate(store, ingest, new RevertService(store),
                    ConfirmRefusalJournal.jdbc(FIXED_CLOCK, ApiSmoke::openConnection),
                    new JdbcPatientKeyStore(ApiSmoke::openConnection));

            // 灌一批：两条可确认、一条缺 MSH-10 入账即拒收。
            long first = ingest(ingest, 1);
            long second = ingest(ingest, 2);
            long rejected = ingestMissingControlId(ingest);

            try (AckStub regional = new AckStub(BATCH_ID, List.of("ARCHIVE-0001"))) {
                Routes routes = new Routes(new ApiTokenGuard(API_TOKEN, LOCAL_AET), gate, store, store,
                        new BatchReconcilerClient(regional.baseUrl(), "smoke-token", Duration.ofSeconds(5)),
                        // 台账不存 MSA 码（本端回过的执在回执账页里），本自检不接账页：
                        // 差集只用控制号比对，ackCode 空串不影响结论。
                        key -> "",
                        LOCAL_AET, FIXED_CLOCK, LedgerSchema.CURRENT_VERSION);

                try (HttpApiServer server = HttpApiServer.start("127.0.0.1", 0, routes)) {
                    out.println("院内 API 已监听 http://127.0.0.1:" + server.port() + "（自检模式：内嵌台账 + 回环对端，不读凭据）");
                    HttpClient client = HttpClient.newBuilder()
                            .connectTimeout(Duration.ofSeconds(5)).build();
                    String base = "http://127.0.0.1:" + server.port();

                    out.println();
                    out.println("── 1. GET /healthz（探针不带令牌）");
                    HttpResponse<String> health = get(client, base + "/healthz", null);
                    out.println("   HTTP " + health.statusCode() + "  " + health.body());
                    expect(out, failures, "healthz 200", health.statusCode() == 200);
                    expect(out, failures, "healthz 带服务名",
                            health.body().contains(Routes.SERVICE_NAME));

                    out.println();
                    out.println("── 2. GET /messages 不带令牌（缺凭据）");
                    HttpResponse<String> noToken = get(client, base + "/messages?state=PENDING_CONFIRM", null);
                    out.println("   HTTP " + noToken.statusCode() + "  " + noToken.body());
                    expect(out, failures, "无令牌 401", noToken.statusCode() == 401);
                    expect(out, failures, "无令牌 errorCode=TOKEN_MISSING",
                            noToken.body().contains("TOKEN_MISSING"));

                    out.println();
                    out.println("── 3. GET /messages?state=PENDING_CONFIRM（带令牌）");
                    HttpResponse<String> pending = get(client,
                            base + "/messages?state=PENDING_CONFIRM&batchId=" + BATCH_ID,
                            API_TOKEN);
                    out.println("   HTTP " + pending.statusCode() + "  " + pending.body());
                    expect(out, failures, "待办查询 200", pending.statusCode() == 200);
                    expect(out, failures, "待办 2 条", pending.body().contains("\"total\":2"));

                    out.println();
                    out.println("── 4. POST /batches/" + BATCH_ID + "/confirm（操作人取自令牌）");
                    HttpResponse<String> confirm = post(client,
                            base + "/batches/" + BATCH_ID + "/confirm",
                            "Bearer clerk-007:" + API_TOKEN,
                            "{\"ledgerId\":" + first + ",\"patientRef\":\"PAT-0001\","
                                    + "\"localMrn\":\"MRN-884512\",\"candidateSource\":\"model-candidate#1\"}");
                    out.println("   HTTP " + confirm.statusCode() + "  " + confirm.body());
                    expect(out, failures, "确认生效 200", confirm.statusCode() == 200);
                    expect(out, failures, "操作人取自令牌 clerk-007",
                            confirm.body().contains("\"operator\":\"clerk-007\""));
                    expect(out, failures, "状态到已落地", confirm.body().contains("\"state\":\"LANDED\""));

                    out.println();
                    out.println("── 5. 同一条重复确认（已落地 → 409，不静默成功）");
                    HttpResponse<String> repeat = post(client,
                            base + "/batches/" + BATCH_ID + "/confirm",
                            "Bearer clerk-007:" + API_TOKEN,
                            "{\"ledgerId\":" + first + ",\"patientRef\":\"PAT-0001\","
                                    + "\"localMrn\":\"MRN-884512\"}");
                    out.println("   HTTP " + repeat.statusCode() + "  " + repeat.body());
                    expect(out, failures, "重复确认 409", repeat.statusCode() == 409);
                    expect(out, failures, "重复确认拒绝码 ALREADY_LANDED",
                            repeat.body().contains("ALREADY_LANDED"));

                    out.println();
                    out.println("── 6. POST .../revert（撤回退回待确认）");
                    HttpResponse<String> revert = post(client,
                            base + "/batches/" + BATCH_ID + "/revert",
                            "Bearer clerk-007:" + API_TOKEN,
                            "{\"ledgerId\":" + first + ",\"reason\":\"居民信息员说这条挂错了人\"}");
                    out.println("   HTTP " + revert.statusCode() + "  " + revert.body());
                    expect(out, failures, "撤回 200", revert.statusCode() == 200);
                    expect(out, failures, "撤回后退回待确认",
                            revert.body().contains("\"state\":\"PENDING_CONFIRM\""));

                    out.println();
                    out.println("── 7. GET /batches/{batchId}/stats（四态分布）");
                    HttpResponse<String> stats = get(client,
                            base + "/batches/" + BATCH_ID + "/stats", API_TOKEN);
                    out.println("   HTTP " + stats.statusCode() + "  " + stats.body());
                    expect(out, failures, "统计 200", stats.statusCode() == 200);
                    expect(out, failures, "统计含拒收 1 条",
                            stats.body().contains("\"rejected\":1"));

                    out.println();
                    out.println("── 8. GET /batches/{batchId}/reconcile（对端只认了第 1 条 → 差集缺 1 条）");
                    HttpResponse<String> reconcile = get(client,
                            base + "/batches/" + BATCH_ID + "/reconcile", API_TOKEN);
                    out.println("   HTTP " + reconcile.statusCode() + "  " + reconcile.body());
                    expect(out, failures, "对账 200", reconcile.statusCode() == 200);
                    expect(out, failures, "对账缺 1 条", reconcile.body().contains("\"missingCount\":1"));

                    out.println();
                    out.println("台账：ledgerId(first)=" + first + " ledgerId(second)=" + second
                            + " ledgerId(rejected)=" + rejected);
                }
            }
        } catch (Exception e) {
            out.println("自检中断：" + e);
            failures.add("异常中断：" + e);
        }

        out.println();
        if (failures.isEmpty()) {
            out.println("API 自检 PASS：令牌校验、409 语义、操作人取自令牌、四态统计与对账差集均符合契约");
            return 0;
        }
        out.println("API 自检 FAIL（" + failures.size() + " 项）：");
        failures.forEach(f -> out.println("  - " + f));
        return 1;
    }

    // =====================================================================
    // 内部
    // =====================================================================

    private static void expect(java.io.PrintStream out, List<String> failures,
                               String what, boolean ok) {
        out.println("   [" + (ok ? "PASS" : "FAIL") + "] " + what);
        if (!ok) {
            failures.add(what);
        }
    }

    private static Connection openConnection() throws SQLException {
        return DriverManager.getConnection(H2_URL, "sa", "");
    }

    private static long ingest(ArchiveIngestService ingest, int n) throws Exception {
        return ingest.ingest(message(n).getBytes(StandardCharsets.UTF_8)).ledgerId();
    }

    private static long ingestMissingControlId(ArchiveIngestService ingest) throws Exception {
        String er7 = String.join("\r",
                "MSH|^~\\&|REGIONAL_ARCHIVE|FACILITY_A|ARCHIVE_IN|FACILITY_A|20261020090000"
                        + "||ADT^A01^ADT_A01||P|2.5.1|||AL|NE|UNICODE UTF-8",
                "PID|1||PAT-0003^^^FACILITY_A^MR||王^五||19820304|F",
                "");
        return ingest.ingest(er7.getBytes(StandardCharsets.UTF_8)).ledgerId();
    }

    private static String message(int n) {
        String ctrl = String.format("ARCHIVE-%04d", n);
        return String.join("\r",
                "MSH|^~\\&|REGIONAL_ARCHIVE|FACILITY_A|ARCHIVE_IN|FACILITY_A|20261020090000"
                        + "||ADT^A01^ADT_A01|" + ctrl + "|P|2.5.1|||AL|NE|UNICODE UTF-8",
                "PID|1||PAT-000" + n + "^^^FACILITY_A^MR||样^例" + n + "||1980010" + n + "|M",
                "");
    }

    private static HttpResponse<String> get(HttpClient client, String url, String token)
            throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10)).GET();
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static HttpResponse<String> post(HttpClient client, String url, String authorization,
                                             String body)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", authorization)
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /**
     * 自检里的出站写端口：不发 HTTP。
     *
     * <p><b>理由必须写清楚</b>：本命令不读 {@code HIS_MPI_ENDPOINT}，也没有 HIS 可打；
     * 若在这里偷偷起一个本地 HIS 替身并称为「已接通」，那正是本项目最禁止的那种表述。
     * 这里只把「确认是否触发了出站写」这个计数留在日志里，真实出站在 T31 的
     * {@code HisPatientIndexClient} 契约测试里验证。
     */
    private static final class SilentPatientIndexWriter
            implements cn.hospital.region.archive.identity.PatientIndexWriter {

        @Override
        public void put(String patientRef, String localMrn, String sourceBatch) {
            java.util.logging.Logger.getLogger(ApiSmoke.class.getName()).log(
                    java.util.logging.Level.INFO,
                    "自检模式：本次确认触发了院内患者主索引写入 "
                            + "patientRef={0} localMrn={1} sourceBatch={2}（自检不发真实 HTTP）",
                    new Object[]{patientRef, localMrn, sourceBatch});
        }
    }

    /**
     * 对端回执列表的本地对端进程（真实 HTTP + 正式字段规范）。
     *
     * <p>它只实现 {@code GET /batches/{batchId}/acks} 这一条路径，返回
     * {@code {batchId, declaredAckCount, acks:[{controlId,messageType,msaCode,receivedAt}]}}，
     * 与 {@code BatchReconcilerClient} 声明的正式字段规范逐字一致。
     * <b>自检里明确标注这是本地对端</b>，不冒充区域平台。
     */
    private static final class AckStub implements AutoCloseable {

        private final com.sun.net.httpserver.HttpServer server;
        private final String baseUrl;

        AckStub(String batchId, List<String> ackedControlIds) throws IOException {
            server = com.sun.net.httpserver.HttpServer.create(
                    new InetSocketAddress("127.0.0.1", 0), 8);
            List<String> acks = new ArrayList<>();
            for (String ctrl : ackedControlIds) {
                acks.add("{\"controlId\":\"" + ctrl + "\",\"messageType\":\"ADT^A01^ADT_A01\","
                        + "\"msaCode\":\"AA\",\"receivedAt\":\"2026-10-20T01:05:00Z\"}");
            }
            String body = "{\"batchId\":\"" + batchId + "\",\"declaredAckCount\":" + acks.size()
                    + ",\"acks\":[" + String.join(",", acks) + "]}";
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
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
