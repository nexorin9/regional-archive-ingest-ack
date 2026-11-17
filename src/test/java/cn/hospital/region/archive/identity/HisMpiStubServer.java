package cn.hospital.region.archive.identity;

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
 * 本地院内 HIS 患者主索引<b>协议替身</b>：真实 HTTP + 真实 JSON，<b>不用任何 mock 库</b>。
 *
 * <p>它起的是一个真的 {@link HttpServer}，走真的 TCP，真的收 UTF-8 JSON 请求体。
 * 要验的是「本端发出的方法、路径、鉴权头与请求体字段是否符合正式对接规范」，
 * 用 mock 拦截器的话，被验的就变成了 mock 的行为而不是 HTTP 的行为 ——
 * 而「路径写错了一段」「Authorization 没带上」这类问题恰恰只有真的发一次请求才暴露得出来。
 *
 * <p><b>它只是测试数据层</b>：产品代码里的 {@code HIS_MPI_ENDPOINT} 在院内指向真正的
 * 前置机 / HIS 服务，这里那串 {@code 127.0.0.1} 只存在于测试源码，
 * 不是产品的兜底路径（产品侧缺端点即启动非零退出）。
 *
 * <p>替身兼作失败响应来源：{@link #setHttpStatus(int)} 造非 2xx，
 * {@link #setResponseDelayMillis(long)} 造读超时，
 * {@link #setRequireBearer(String)} 造令牌不对被拒（401）。
 */
final class HisMpiStubServer implements AutoCloseable {

    /** 一次收到的请求的完整留痕。 */
    record ReceivedRequest(String method, String path, String authorization,
                           String contentType, String idempotencyKey, String body) {

        /** 按正式字段规范解析请求体三字段。 */
        Map<String, Object> json() {
            return cn.hospital.region.archive.model.Json.asObject(
                    cn.hospital.region.archive.model.Json.parse(body), "替身收到的请求体");
        }

        String field(String name) {
            return cn.hospital.region.archive.model.Json.asString(json().get(name), name);
        }
    }

    private final HttpServer server;
    private final String contextPath;
    private final List<ReceivedRequest> received = new CopyOnWriteArrayList<>();
    private final AtomicInteger sequence = new AtomicInteger();

    private volatile String requireBearer;
    private volatile int httpStatus = 200;
    private volatile long responseDelayMillis = 0L;

    private HisMpiStubServer(HttpServer server, String contextPath) {
        this.server = server;
        this.contextPath = contextPath;
    }

    /** 在回环地址的随机空闲端口上起一个替身，端点前缀为 {@code http://127.0.0.1:<port>}。 */
    static HisMpiStubServer start() throws IOException {
        return start("", null);
    }

    /**
     * 起替身并要求鉴权。
     *
     * @param contextPath    端点的路径前缀；传 {@code ""} 表示挂在根上
     * @param expectedBearer 期望的 {@code Bearer} 令牌；{@code null} 表示不校验
     */
    static HisMpiStubServer start(String contextPath, String expectedBearer) throws IOException {
        String prefix = contextPath == null ? "" : contextPath;
        if (!prefix.isEmpty() && !prefix.startsWith("/")) {
            prefix = "/" + prefix;
        }
        while (prefix.endsWith("/")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        HttpServer http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        HisMpiStubServer stub = new HisMpiStubServer(http, prefix);
        http.createContext(prefix + HisPatientIndexClient.PATH_SUFFIX, stub::handle);
        http.setExecutor(Executors.newFixedThreadPool(2));
        http.start();
        stub.requireBearer = expectedBearer;
        return stub;
    }

    /** 端点基址，恰好是 {@code HIS_MPI_ENDPOINT} 环境变量该填的那个值。 */
    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + contextPath;
    }

    /** 已收到的请求条数 —— 「同一条报文重发 100 次，HIS 侧收到了几次」就看这个数。 */
    int requestCount() {
        return received.size();
    }

    List<ReceivedRequest> requests() {
        return List.copyOf(received);
    }

    ReceivedRequest lastRequest() {
        List<ReceivedRequest> snapshot = new ArrayList<>(received);
        return snapshot.isEmpty() ? null : snapshot.get(snapshot.size() - 1);
    }

    /** 覆盖响应状态码（用于构造非 2xx）。 */
    void setHttpStatus(int status) {
        this.httpStatus = status;
    }

    /** 响应前先睡指定毫秒数 —— 用来构造「连上了但迟迟不返回」的真实读超时。 */
    void setResponseDelayMillis(long millis) {
        this.responseDelayMillis = millis;
    }

    void setRequireBearer(String bearer) {
        this.requireBearer = bearer;
    }

    /** 恢复到「全健康」：清掉故障开关与已收请求。 */
    void reset() {
        httpStatus = 200;
        responseDelayMillis = 0L;
        received.clear();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body;
        try (InputStream in = exchange.getRequestBody()) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String auth = header(exchange, "Authorization");
        String idempotency = header(exchange, "Idempotency-Key");
        received.add(new ReceivedRequest(exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(), auth, header(exchange, "Content-Type"),
                idempotency, body));

        // 延迟放在「收完请求」之后：构造的是「请求到了、服务端迟迟不回」，
        // 这才是读超时的真实形态；放在收请求之前的话，请求根本到不了对端。
        if (responseDelayMillis > 0) {
            try {
                Thread.sleep(responseDelayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        int status = httpStatus;
        String payload;
        if (requireBearer != null && !("Bearer " + requireBearer).equals(auth)) {
            status = 401;
            payload = "{\"error\":\"invalid token\"}";
        } else if (status / 100 == 2) {
            payload = "{\"result\":\"ok\",\"seq\":" + sequence.incrementAndGet() + "}";
        } else {
            // 非 2xx 的响应体刻意带院内内部措辞：用来验「响应体不会进异常消息与日志」。
            payload = "{\"error\":\"患者主索引服务内部错误，节点 ip-10-2-33-7 校验未通过\"}";
        }

        byte[] out = payload.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, out.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(out);
        }
    }

    private static String header(HttpExchange exchange, String name) {
        String value = exchange.getRequestHeaders().getFirst(name);
        return value == null ? "" : value;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}