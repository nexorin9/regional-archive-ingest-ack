package cn.hospital.region.archive.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 院内 HTTP API 的<b>传输层</b> —— JDK 内置 {@code com.sun.net.httpserver.HttpServer}。
 *
 * <h2>为什么零 Web 框架</h2>
 * <p>本产品要对外交付的是「六个端点 + 一份 OpenAPI」，不是一个需要路由、序列化、
 * 依赖注入、拦截器链和容器生命周期的东西。引一个框架要多一份离线构建风险
 * （院内前置机常常没有外网），而 JDK 自带的 server 恰好够用：真实 socket、真实 HTTP/1.1、
 * 真实并发。<b>框架能省的那点样板，在这六个端点上一共不到一百行</b>，
 * 换来的是「装一个 jar 就能起」与「依赖面维持四个」这两条对院内部署更重要的性质。
 *
 * <h2>只注册一个 {@code "/"} 上下文</h2>
 * <p>注册 {@code /healthz}、{@code /batches/...} 等多个 context 会让 JDK server 自己做前缀匹配，
 * 于是「路径写错」会得到 JDK 默认的 HTML 404 页或 405 页——那是给人看的浏览器页面，
 * 院内系统拿到它只能靠正则猜错误。<b>统一收口到 {@link Routes#dispatch}</b>：
 * 任何路径（包括没注册的）都得到同一个 JSON 错误体，含 {@code errorCode}。
 *
 * <h2>线程模型</h2>
 * <p>固定大小的工作线程池（默认 8），<b>每请求一线程</b>。确认动作要落库、要调 HIS，
 * 单请求可能耗时上百毫秒；池太小会让院内系统的并发确认排队（本产品实测容量是
 * 单批 500 条、P99 单条 &lt; 200ms，池 8 与之匹配）。线程名带前缀 {@code archive-api-}，
 * 现场 {@code jstack} 一眼能认出是本服务的。
 */
public final class HttpApiServer implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(HttpApiServer.class.getName());

    /** 工作线程数：一个在处理确认请求时其余仍可服务只读查询。 */
    public static final int DEFAULT_WORKER_THREADS = 8;

    /** 积压队列长度：JDK server 在 accept 之前的排队上限。 */
    private static final int BACKLOG = 64;

    private final HttpServer server;
    private final ExecutorService workers;
    private final Routes routes;

    private HttpApiServer(HttpServer server, ExecutorService workers, Routes routes) {
        this.server = server;
        this.workers = workers;
        this.routes = routes;
    }

    /**
     * 绑定端口并开始服务。
     *
     * @param host    绑定地址；生产传 {@code 127.0.0.1}（前置机上只给院内系统访问），
     *                测试传 {@code 127.0.0.1}
     * @param port    监听端口；传 {@code 0} 表示由系统分配（测试用，取 {@link #port()}）
     * @throws IOException 端口被占用或地址不可绑 —— <b>直接失败，不降级</b>
     */
    public static HttpApiServer start(String host, int port, Routes routes) throws IOException {
        return start(host, port, routes, DEFAULT_WORKER_THREADS);
    }

    /** 同 {@link #start(String, int, Routes)}，但可指定工作线程数。 */
    public static HttpApiServer start(String host, int port, Routes routes, int workerThreads)
            throws IOException {
        Objects.requireNonNull(routes, "路由不得为 null");
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("端口必须在 0..65535，实际=" + port);
        }
        if (workerThreads <= 0) {
            throw new IllegalArgumentException("工作线程数必须为正数，实际=" + workerThreads);
        }
        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), BACKLOG);
        ExecutorService workers = Executors.newFixedThreadPool(workerThreads, apiThreadFactory());
        server.setExecutor(workers);
        server.createContext("/", exchange -> handle(exchange, routes));
        server.start();
        LOG.log(Level.INFO, "院内 API 已监听：{0}:{1} 端点数={2}",
                new Object[]{host, server.getAddress().getPort(), Routes.API_VERSION});
        return new HttpApiServer(server, workers, routes);
    }

    /** 实际监听端口（传 0 时由系统分配，取这个）。 */
    public int port() {
        return server.getAddress().getPort();
    }

    /** 底层 server（测试要断言 stop 行为时用；产品代码不需要）。 */
    public HttpServer raw() {
        return server;
    }

    public Routes routes() {
        return routes;
    }

    /** 停止服务并回收工作线程。可重复调用。 */
    @Override
    public void close() {
        server.stop(0);
        workers.shutdownNow();
        try {
            if (!workers.awaitTermination(2, TimeUnit.SECONDS)) {
                LOG.log(Level.WARNING, "API 工作线程未在 2 秒内退出，强制结束");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // =====================================================================
    // HttpExchange → ApiRequest → ApiResponse
    // =====================================================================

    private static void handle(HttpExchange exchange, Routes routes) {
        ApiResponse response;
        String path = exchange.getRequestURI().getRawPath();
        try {
            ApiRequest request = toRequest(exchange);
            response = request == null
                    ? ApiResponse.error(ApiErrorCode.INVALID_PARAMETER, "请求行无法解析", path)
                    : routes.dispatch(request);
        } catch (ApiRequestTooLargeException e) {
            response = ApiResponse.error(ApiErrorCode.INVALID_PARAMETER, e.getMessage(), path);
        } catch (Exception e) {
            // 兜底：读请求体阶段就炸的情形（socket 半关、编码错）。同样不许把栈发出去。
            LOG.log(Level.SEVERE, "读取请求失败 path=" + path, e);
            response = ApiResponse.error(ApiErrorCode.INTERNAL_ERROR, null, path);
        }
        try {
            write(exchange, response);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "写响应失败 path=" + path + "（客户端可能已断开）：" + e.getMessage());
        } finally {
            exchange.close();
        }
    }

    private static ApiRequest toRequest(HttpExchange exchange) throws IOException {
        URI uri = exchange.getRequestURI();
        String body = readBody(exchange);
        Map<String, String> headers = new LinkedHashMap<>();
        exchange.getRequestHeaders().forEach((name, values) -> {
            if (name != null && values != null && !values.isEmpty()) {
                headers.put(name.toLowerCase(Locale.ROOT), values.get(0));
            }
        });
        return new ApiRequest(exchange.getRequestMethod(), uri.getRawPath(),
                parseQuery(uri.getRawQuery()), headers, body);
    }

    /**
     * 读请求体，按 UTF-8 解码，超过 {@link ApiRequest#MAX_BODY_BYTES} 直接拒。
     *
     * <p><b>先看 Content-Length 再决定要不要读</b>：JDK server 在 handler 返回后仍可能继续
     * 把请求体留在 socket 缓冲里，不读完就返回 413 会让下一个请求解析到残留字节。
     */
    private static String readBody(HttpExchange exchange) throws IOException {
        String contentLength = exchange.getRequestHeaders().getFirst("Content-Length");
        if (contentLength != null) {
            try {
                if (Long.parseLong(contentLength.trim()) > ApiRequest.MAX_BODY_BYTES) {
                    throw new ApiRequestTooLargeException("请求体超过 " + ApiRequest.MAX_BODY_BYTES
                            + " 字节上限：本接口只收 ledgerId 与两个主键");
                }
            } catch (NumberFormatException ignored) {
                // Content-Length 不是合法数字：交给底层按实际字节数判断，不在这里下结论。
            }
        }
        try (InputStream in = exchange.getRequestBody()) {
            byte[] bytes = in.readNBytes(ApiRequest.MAX_BODY_BYTES + 1);
            if (bytes.length > ApiRequest.MAX_BODY_BYTES) {
                throw new ApiRequestTooLargeException("请求体超过 " + ApiRequest.MAX_BODY_BYTES
                        + " 字节上限：本接口只收 ledgerId 与两个主键");
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    /** 查询串解析；同名多值用逗号连接（{@code state=A&state=B} → {@code "A,B"}）。 */
    static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> out = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return out;
        }
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String name = decode(eq < 0 ? pair : pair.substring(0, eq));
            String value = eq < 0 ? "" : decode(pair.substring(eq + 1));
            out.merge(name, value, (a, b) -> a + "," + b);
        }
        return out;
    }

    private static String decode(String value) {
        try {
            return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("查询参数不是合法 URL 编码：" + value, e);
        }
    }

    private static void write(HttpExchange exchange, ApiResponse response) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        byte[] payload = response.json() == null
                ? new byte[0]
                : response.json().getBytes(StandardCharsets.UTF_8);
        // 204/304 不许带 body：JDK 会拒绝，自带 Content-Length 也拦不住「body 非空」。
        if (response.status() == 204 || response.status() == 304) {
            exchange.sendResponseHeaders(response.status(), -1);
            return;
        }
        exchange.sendResponseHeaders(response.status(), payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }

    private static ThreadFactory apiThreadFactory() {
        AtomicInteger seq = new AtomicInteger();
        return runnable -> {
            Thread t = new Thread(runnable, "archive-api-" + seq.incrementAndGet());
            t.setDaemon(false);
            return t;
        };
    }

    /** 请求体超限；只在传输层出现（路由层拿不到超限的体）。 */
    private static final class ApiRequestTooLargeException extends RuntimeException {
        ApiRequestTooLargeException(String message) {
            super(message, null, false, false);
        }
    }

    /** 端点清单，供 {@code --help} 与 README 生成用；不参与路由判定。 */
    public static List<String> endpointSummaries() {
        return List.of(
                "GET  /healthz                                  进程存活（不要令牌）",
                "POST /batches/{batchId}/confirm                确认一条待确认报文（Bearer <操作人>:<令牌>）",
                "POST /batches/{batchId}/revert                 撤回一条已落地报文，退回待确认",
                "GET  /messages?state=&batchId=&limit=          按状态分页查待办",
                "GET  /batches/{batchId}/stats                  批次四态分布与拒收原因",
                "GET  /batches/{batchId}/reconcile              与区域平台回执做差集（只读，不入队）");
    }
}