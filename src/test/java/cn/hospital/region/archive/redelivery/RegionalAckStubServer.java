package cn.hospital.region.archive.redelivery;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/**
 * 本地区域平台回执查询<b>协议替身</b>：真实 HTTP + 真实 JSON，<b>不用任何 mock 库</b>。
 *
 * <p>它起的是一个真的 {@link HttpServer}，走真的 TCP，真的收 UTF-8 JSON。
 * 要验的是「本端发出的方法、路径、鉴权头与响应解析是否符合正式对接规范」——
 * 用 mock 拦截器的话，被验的就变成了 mock 的行为而不是 HTTP 的行为，
 * 而「路径少了 {@code /acks}」「Bearer 没带上」「批次号没做路径段编码」这类问题
 * 恰恰只有真的发一次请求才暴露得出来。
 *
 * <p><b>它只是测试数据层</b>：产品代码里的 {@code ARCHIVE_ACK_API_BASE_URL} 在院内指向真正的
 * 区域平台前置机，这串 {@code 127.0.0.1} 只存在于测试源码，不是产品的兜底路径
 * （产品侧缺端点即启动非零退出）。
 *
 * <p>替身兼作失败响应来源：{@link #setHttpStatus(int)} 造非 2xx，
 * {@link #setResponseDelayMillis(long)} 造读超时，
 * {@link #setRequireBearer(String)} 造令牌不对被拒（401），
 * {@link #setRawBody(String)} 造不符合字段规范的响应体。
 */
final class RegionalAckStubServer implements AutoCloseable {

    /** 一次收到的请求的完整留痕。 */
    record ReceivedRequest(String method, String path, String rawPath,
                           String authorization, String accept) {
    }

    private final HttpServer server;
    private final Map<String, List<Map<String, Object>>> acksByBatch = new LinkedHashMap<>();
    private final Map<String, Integer> declaredCounts = new LinkedHashMap<>();
    private final List<ReceivedRequest> received = new CopyOnWriteArrayList<>();

    private volatile String requireBearer;
    private volatile int httpStatus = 200;
    private volatile long responseDelayMillis = 0L;
    private volatile String rawBodyOverride;

    private RegionalAckStubServer(HttpServer server) {
        this.server = server;
    }

    /** 在回环地址的随机空闲端口上起一个替身，基址为 {@code http://127.0.0.1:<port>}。 */
    static RegionalAckStubServer start() throws IOException {
        return start(null);
    }

    /**
     * 起替身并（可选）要求鉴权。
     *
     * @param expectedBearer 期望的 {@code Bearer} 令牌；{@code null} 表示不校验
     */
    static RegionalAckStubServer start(String expectedBearer) throws IOException {
        RegionalAckStubServer stub = new RegionalAckStubServer(HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0));
        stub.requireBearer = expectedBearer;
        stub.server.createContext("/batches", stub::handleBatches);
        stub.server.setExecutor(Executors.newFixedThreadPool(2));
        stub.server.start();
        return stub;
    }

    /** 替身基址（对应产品侧的 {@code ARCHIVE_ACK_API_BASE_URL}）。 */
    String baseUrl() {
        return "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort();
    }

    /** 登记一个批次的对端回执列表（按正式字段规范）。 */
    RegionalAckStubServer withAcks(String batchId, List<Map<String, Object>> acks) {
        acksByBatch.put(batchId, List.copyOf(acks));
        declaredCounts.put(batchId, acks.size());
        return this;
    }

    /** 只声明 {@code ackCount}、与数组长度不一致 —— 造「对端口径差异」。 */
    RegionalAckStubServer withDeclaredCount(String batchId, int declaredCount) {
        declaredCounts.put(batchId, declaredCount);
        return this;
    }

    /** 不回报 {@code ackCount} 字段。 */
    RegionalAckStubServer withoutDeclaredCount(String batchId) {
        declaredCounts.remove(batchId);
        return this;
    }

    void setHttpStatus(int status) {
        this.httpStatus = status;
    }

    void setResponseDelayMillis(long delayMillis) {
        this.responseDelayMillis = delayMillis;
    }

    void setRequireBearer(String bearer) {
        this.requireBearer = bearer;
    }

    /** 造不符合字段规范的响应体（原样返回，不做任何包装）。 */
    void setRawBody(String body) {
        this.rawBodyOverride = body;
    }

    List<ReceivedRequest> receivedRequests() {
        return List.copyOf(received);
    }

    ReceivedRequest lastRequest() {
        if (received.isEmpty()) {
            throw new IllegalStateException("替身没收到任何请求");
        }
        return received.get(received.size() - 1);
    }

    // =====================================================================
    // 处理
    // =====================================================================

    private void handleBatches(HttpExchange exchange) throws IOException {
        try {
            received.add(new ReceivedRequest(exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    exchange.getRequestURI().getRawPath(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("Accept")));

            if (responseDelayMillis > 0) {
                Thread.sleep(responseDelayMillis);
            }

            if (requireBearer != null) {
                String expected = "Bearer " + requireBearer;
                if (!expected.equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                    respond(exchange, 401, "{\"error\":\"invalid_token\"}");
                    return;
                }
            }

            if (httpStatus / 100 != 2) {
                respond(exchange, httpStatus, "{\"error\":\"stub_failure\",\"detail\":\"内部路径不外传\"}");
                return;
            }

            if (rawBodyOverride != null) {
                respond(exchange, 200, rawBodyOverride);
                return;
            }

            String batchId = batchIdOf(exchange.getRequestURI().getRawPath());
            List<Map<String, Object>> acks = acksByBatch.get(batchId);
            if (acks == null) {
                respond(exchange, 404, "{\"error\":\"batch_not_found\"}");
                return;
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("batchId", batchId);
            Integer declared = declaredCounts.get(batchId);
            if (declared != null) {
                body.put("ackCount", declared);
            }
            body.put("acks", acks);
            respond(exchange, 200, cn.hospital.region.archive.model.Json.write(body));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            respond(exchange, 500, "{\"error\":\"interrupted\"}");
        } finally {
            exchange.close();
        }
    }

    /** 从原始路径 {@code /batches/{batchId}/acks} 里取批次号（先 URL 解码）。 */
    private static String batchIdOf(String rawPath) {
        String rest = rawPath.substring("/batches/".length());
        if (rest.endsWith("/acks")) {
            rest = rest.substring(0, rest.length() - "/acks".length());
        }
        return java.net.URLDecoder.decode(rest, StandardCharsets.UTF_8);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** 造一条对端回执条目（按正式字段规范）。 */
    static Map<String, Object> ack(String controlId, String msaCode, String receivedAt) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("controlId", controlId);
        item.put("messageType", "ADT^A01^ADT_A01");
        item.put("processingId", "P");
        item.put("msaCode", msaCode);
        if (receivedAt != null) {
            item.put("receivedAt", receivedAt);
        }
        return item;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}