package cn.hospital.region.archive.model;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 本地 OpenAI-compatible <b>协议替身</b>：真实 HTTP + 真实 JSON，<b>不用任何 mock 库</b>。
 *
 * <p>它起的是一个真的 {@link HttpServer}，走真的 TCP，真的收发 UTF-8 JSON。
 * 要验的是「网关发出的报文形状对不对、收到的报文能不能落成结构化对象」，
 * 用 mock 拦截器的话，被验的就变成了 mock 的行为而不是 HTTP 的行为。
 *
 * <p><b>它只是测试数据层</b>：产品代码里的 {@code MODEL_BASE_URL} 在院内指向自建端点或云服务，
 * 这里那串 {@code 127.0.0.1} 只存在于测试源码，不是产品的兜底路径。
 *
 * <p>替身兼作 T21/T22 的失败响应来源：{@link #setHttpStatus(int)}、
 * {@link #setContentOverride(String)}、{@link #setFencedContent(boolean)}、
 * {@link #setResponseDelayMillis(long)} 覆盖「非 2xx」「不可解析」「结构不符」
 * 「迟迟不返回」四类真实会遇到的响应。
 */
final class OpenAiCompatibleStubServer implements AutoCloseable {

    /** 端点路径：{@code baseUrl} 由 {@link #baseUrl()} 给出，这里补上最后一段。 */
    private static final String PATH = "/v1/chat/completions";

    private final HttpServer server;
    private final String expectedBearer;
    private final AtomicInteger sequence = new AtomicInteger();

    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private volatile String lastAuthorization = "";
    private volatile String lastBody = "";
    private volatile int httpStatus = 200;
    private volatile boolean fencedContent = false;
    private volatile String contentOverride = null;
    private volatile String envelopeOverride = null;
    private volatile long responseDelayMillis = 0L;

    private OpenAiCompatibleStubServer(HttpServer server, String expectedBearer) {
        this.server = server;
        this.expectedBearer = expectedBearer;
    }

    /** 在回环地址的随机空闲端口上起一个替身。 */
    static OpenAiCompatibleStubServer start(String expectedBearer) throws IOException {
        HttpServer http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        OpenAiCompatibleStubServer stub = new OpenAiCompatibleStubServer(http, expectedBearer);
        http.createContext(PATH, stub::handle);
        http.setExecutor(Executors.newFixedThreadPool(2));
        http.start();
        return stub;
    }

    /** 形如 {@code http://127.0.0.1:54321/v1}，网关会在其后补 {@code /chat/completions}。 */
    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    String lastBody() {
        return lastBody;
    }

    String lastAuthorization() {
        return lastAuthorization;
    }

    int requestCount() {
        return bodies.size();
    }

    /** 覆盖响应内容（用于构造结构不符、字段缺失等失败响应）。 */
    void setContentOverride(String content) {
        this.contentOverride = content;
    }

    /** 覆盖 HTTP 状态码（用于构造非 2xx）。 */
    void setHttpStatus(int status) {
        this.httpStatus = status;
    }

    /** 恢复到「全健康」：清掉此前布置过的故障开关与已收请求。 */
    void reset() {
        this.httpStatus = 200;
        this.fencedContent = false;
        this.contentOverride = null;
        this.envelopeOverride = null;
        this.responseDelayMillis = 0L;
        bodies.clear();
        lastBody = "";
        lastAuthorization = "";
    }

    /**
     * 覆盖<b>整个响应信封</b>（用于构造信封层故障）。
     *
     * <p>{@link #setContentOverride(String)} 只换 {@code choices[0].message.content} 里的那一段，
     * 换不动信封本身；而真实端点的故障恰恰经常发生在信封层：
     * 负载均衡挂掉时回一段 HTML 错误页、网关把模型名拼错时 {@code choices} 为空数组、
     * 某些推理引擎不吐 {@code usage}。这些都得能造出来，否则结构校验只验到了一半。
     *
     * @param raw 整个 HTTP 响应体原文（不经过 JSON 转义），传 {@code null} 恢复由内容生成的正常信封
     */
    void setEnvelopeOverride(String raw) {
        this.envelopeOverride = raw;
    }

    /** 是否用 Markdown 代码块包裹 {@code choices[0].message.content}。 */
    void setFencedContent(boolean fenced) {
        this.fencedContent = fenced;
    }

    /**
     * 响应前先睡指定毫秒数 —— 用来构造<b>读超时</b>这类「连上了但不返回」的真实故障。
     *
     * <p>这类故障用「关掉端点」是造不出来的：连不上归 CONNECT_TIMEOUT，
     * 与「连上了但模型迟迟不给响应」在错误分类、重试策略、处置建议上都是两回事。
     */
    void setResponseDelayMillis(long millis) {
        this.responseDelayMillis = millis;
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body;
        try (InputStream in = exchange.getRequestBody()) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        bodies.add(body);
        lastBody = body;
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        lastAuthorization = auth == null ? "" : auth;

        // 延迟放在「收完请求」之后：构造的是「请求到了、服务端迟迟不回」，
        // 这才是读超时的真实形态。放在收请求之前的话，替身连请求都没读到，
        // 断言「重试几次就真的打到了端点上几次」就无从验证了。
        if (responseDelayMillis > 0) {
            try {
                Thread.sleep(responseDelayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        byte[] out;
        int status = httpStatus;
        if (!("Bearer " + expectedBearer).equals(lastAuthorization)) {
            status = 401;
            out = Json.write(Map.of("error", Map.of("message", "invalid api key"))).getBytes(StandardCharsets.UTF_8);
        } else {
            out = respond(body).getBytes(StandardCharsets.UTF_8);
        }
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, out.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(out);
        }
    }

    /** 按请求里声明的 schema 名产出对应内容——真实模型端点的行为就是这样。 */
    private String respond(String requestBody) {
        Map<String, Object> request;
        try {
            request = Json.asObject(Json.parse(requestBody), "替身收到的请求");
        } catch (Json.JsonException e) {
            return envelope("替身未能解析请求体", "");
        }
        Object rf = request.get("response_format");
        String schemaName = "";
        if (rf instanceof Map) {
            Object js = ((Map<?, ?>) rf).get("json_schema");
            if (js instanceof Map) {
                schemaName = Json.asStringOrDefault(((Map<?, ?>) js).get("name"), "schema.name", "");
            }
        }
        String content = contentOverride != null
                ? contentOverride
                : (OpenAiCompatibleGateway.OP_IDENTITY_CANDIDATES.equals(schemaName)
                        ? identityCandidatesContent()
                        : zFieldMapContent(requestedZFields(request)));

        if (fencedContent) {
            content = "```json\n" + content + "\n```";
        }
        if (envelopeOverride != null) {
            return envelopeOverride;
        }
        return envelope(content, Json.asStringOrDefault(request.get("model"), "model", "stub-model"));
    }

    /**
     * 从请求里取回被问到的 Z 字段定位串 —— 真实模型只答被问的那些，
     * 替身照着做，才不会把没问的字段也塞回响应里让断言失去意义。
     */
    private List<String> requestedZFields(Map<String, Object> request) {
        List<Object> messages = request.get("messages") instanceof List<?> list ? new ArrayList<>(list) : List.of();
        for (Object item : messages) {
            if (!(item instanceof Map)) {
                continue;
            }
            Object content = ((Map<?, ?>) item).get("content");
            if (!(content instanceof String text) || !text.contains("z_segments")) {
                continue;
            }
            Map<String, Object> payload = Json.asObject(Json.parse(text), "user 消息");
            List<Object> segments = Json.asArray(payload.get("z_segments"), "z_segments");
            List<String> out = new ArrayList<>();
            for (Object seg : segments) {
                out.add(Json.asString(Json.asObject(seg, "segment").get("z_field"), "z_field"));
            }
            return out;
        }
        return List.of();
    }

    /**
     * Z 段对齐样例表：<b>只对被问到的 Z 字段作答</b>。
     *
     * <p>{@code ZX3.3} 故意给「判断不了」（local_field 留空），
     * 用来验网关会跳过这条而不是硬凑一个字段名。
     */
    private static final Map<String, Object[]> Z_FIELD_ANSWERS = Map.of(
            "ZX1.3", new Object[]{"pastHistorySummary", 0.82,
                    "平台字段名为既往病史摘要，字段值为高血压随访与2型糖尿病，对应院内既往病史摘要"},
            "ZX2.3", new Object[]{"nextFollowUpDate", 0.77,
                    "平台字段名为下次随访时间，取值形如 20270115，与院内下次随访时间一致"},
            "ZX3.3", new Object[]{"", 0.2,
                    "平台字段名为责任医生姓名，但本次档案包里没有医生信息，判断不了"});

    private String zFieldMapContent(List<String> requested) {
        List<Object> mappings = new ArrayList<>();
        for (String zField : requested) {
            Object[] answer = Z_FIELD_ANSWERS.get(zField);
            if (answer == null) {
                continue;
            }
            Map<String, Object> mapping = Json.object();
            mapping.put("z_field", zField);
            mapping.put("local_field", answer[0]);
            mapping.put("confidence", answer[1]);
            mapping.put("rationale", answer[2]);
            mappings.add(mapping);
        }
        return Json.write(Map.of("mappings", mappings));
    }

    /** 身份候选样例：故意把高分的一条排在后面，用来验「网关负责按置信度排序」。 */
    private String identityCandidatesContent() {
        Map<String, Object> low = Json.object();
        low.put("local_mrn", "LOCMRN-0002");
        low.put("confidence", 0.41);
        low.put("evidence", List.of(
                evidence("name", "平台写的是王芳，院内这位也是王芳，但出生日期不同"),
                evidence("birth_date", "出生日期不一致")));

        Map<String, Object> high = Json.object();
        high.put("local_mrn", "LOCMRN-0001");
        high.put("confidence", 0.74);
        high.put("evidence", List.of(
                evidence("name", "平台写的是繁体姓名，与院内简体写法指向同一人"),
                evidence("birth_date", "出生日期一致")));

        return Json.write(Map.of("candidates", List.of(low, high)));
    }

    private static Map<String, Object> evidence(String field, String detail) {
        Map<String, Object> e = Json.object();
        e.put("field", field);
        e.put("detail", detail);
        return e;
    }

    /** OpenAI-compatible 响应信封。 */
    private String envelope(String content, String model) {
        Map<String, Object> message = Json.object();
        message.put("role", "assistant");
        message.put("content", content);

        Map<String, Object> choice = Json.object();
        choice.put("index", 0);
        choice.put("message", message);
        choice.put("finish_reason", "stop");

        Map<String, Object> usage = Json.object();
        usage.put("prompt_tokens", 21);
        usage.put("completion_tokens", 13);
        usage.put("total_tokens", 34);

        Map<String, Object> root = Json.object();
        root.put("id", "chatcmpl-stub-" + String.format("%04d", sequence.incrementAndGet()));
        root.put("model", model);
        root.put("choices", List.of(choice));
        root.put("usage", usage);
        return Json.write(root);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}