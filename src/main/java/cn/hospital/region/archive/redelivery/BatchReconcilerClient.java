package cn.hospital.region.archive.redelivery;

import cn.hospital.region.archive.config.AppConfig;
import cn.hospital.region.archive.model.Json;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 区域平台回执对账 —— <b>本产品对端只读出站的真实实现</b>（次日「只重投那几条」的取数口）。
 *
 * <h2>正式对接规范</h2>
 * <pre>
 *   GET {ARCHIVE_ACK_API_BASE_URL}/batches/{batchId}/acks
 *   Authorization: Bearer {ARCHIVE_ACK_API_TOKEN}
 *   Accept: application/json
 *
 *   200:
 *   {
 *     "batchId": "REGIONAL_ARCHIVE-20261020",
 *     "ackCount": 8,
 *     "acks": [
 *       {"controlId":"CTRL-20261020-0001","messageType":"ADT^A01^ADT_A01",
 *        "processingId":"P","msaCode":"AA","receivedAt":"2026-10-20T09:00:12+08:00"}
 *     ]
 *   }
 * </pre>
 *
 * <p>端点与令牌<b>只从环境变量读</b>（{@code ARCHIVE_ACK_API_BASE_URL} /
 * {@code ARCHIVE_ACK_API_TOKEN}，由 {@link AppConfig} 在启动期校验，缺任一即非零退出）。
 * 本类<b>没有</b>任何默认地址或兜底替身 —— 写不出「静默改成查本地台账」的路径。</p>
 *
 * <h2>字段为什么只有控制号与 MSA 码</h2>
 * <p>差集要回答的只有一个问题：<b>对端到底认了哪几条</b>。因此响应解析只取
 * {@code controlId}（比对的锚）与 {@code msaCode}（对端记下的受理结论）。
 * 刻意<b>不</b>把批次里的患者标识、姓名一类字段读进来：这一页是为「哪几条没收到」服务的，
 * 拿回来的每一行都应能在日志里逐字念出来而不涉及任何居民 —— T40 的全量明文扫描盯的就是这条。
 * {@code messageType / processingId / receivedAt} 保留为可选字段，用于排障时人工核对，
 * 本产品不拿它们参与差集决策。</p>
 *
 * <h2>失败语义：绝不静默清空</h2>
 * <p>取回执失败一律抛 {@link ReconcileException}。<b>本类绝不返回空列表</b>：
 * 空列表在下游与「对端确认一条都没收到」完全同形，一旦把它当成差集输入，
 * 重投清单会被静默清空，而平台次日追问时一条都重投不了、日志里也没有任何异常。
 * 「问不成」与「问成了、结论是 0 条」必须是两件能分开的事。</p>
 *
 * <p>响应体<b>不记入异常消息也不打日志</b>：非 2xx 的响应体常带对端内部路径与机构信息。</p>
 *
 * <h2>为什么固定 HTTP/1.1</h2>
 * <p>与 {@code HisPatientIndexClient} 同一理由：区域平台前置机与院内前置机大多只实现 HTTP/1.1，
 * 让 {@link HttpClient} 自动尝试 HTTP/2 升级，失败会表现为「同一份请求忽然开始报传输层错误」，
 * 而这类问题事后极难从日志里分辨是协议还是对端挂了。</p>
 */
public final class BatchReconcilerClient {

    private static final Logger LOG = Logger.getLogger(BatchReconcilerClient.class.getName());

    /** 正式对接规范的路径模板：{@code GET {BASE}/batches/{batchId}/acks}。 */
    public static final String PATH_TEMPLATE = "/batches/%s/acks";

    /** 读超时默认值：跨机构调用比院内慢，但一批几百条的差集不该等一分钟。 */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);

    /** 连接超时上限：连不上要秒级失败。 */
    private static final Duration MAX_CONNECT_TIMEOUT = Duration.ofSeconds(5);

    private final HttpClient http;
    private final URI baseUri;
    private final String token;
    private final Duration readTimeout;

    /** 生产装配：端点与令牌取自启动配置（环境变量）。 */
    public BatchReconcilerClient(AppConfig config) {
        this(Objects.requireNonNull(config, "启动配置不得为 null").ackApiBaseUrl(),
                config.ackApiToken(), DEFAULT_TIMEOUT);
    }

    /** 显式装配（契约测试与离线核对用同一套构造逻辑）。 */
    public BatchReconcilerClient(String baseUrl, String token, Duration timeout) {
        this(defaultHttpClient(timeout), URI.create(baseUri(baseUrl)), requireToken(token),
                requireTimeout(timeout));
    }

    /** 注入 HTTP 客户端：跨机构调用走代理、走自定义 TLS 时用得到。 */
    public BatchReconcilerClient(HttpClient http, URI baseUri, String token, Duration readTimeout) {
        this.http = Objects.requireNonNull(http, "HttpClient 不得为 null");
        this.baseUri = Objects.requireNonNull(baseUri, "对端基址不得为 null");
        this.token = requireToken(token);
        this.readTimeout = requireTimeout(readTimeout);
    }

    // =====================================================================
    // 端口实现：对端只读出站的唯一调用点
    // =====================================================================

    /**
     * 取一个批次在区域平台侧留下的回执列表。
     *
     * <p><b>本方法是本产品唯一定义对端回执查询报文形状的地方</b>：路径、方法、鉴权头与
     * JSON 字段都在这里成型，验收时对端收到的每一个字节都能追到这一行。</p>
     *
     * @param batchId 台账批次号；不得为空白（空白会拼出 {@code /batches//acks}，对端返回的是
     *                一个含义不明的列表而不是错误，差集会因此全盘失真）
     * @return 对端回执页；<b>绝不返回空列表代表「没取到」</b> —— 取不到就抛异常
     * @throws IllegalArgumentException batchId 为空白 —— 入参校验，<b>不发请求</b>
     * @throws ReconcileException        对端非 2xx、超时、连不上，或响应不符合正式字段规范
     */
    public RemoteAckPage fetchAcks(String batchId) throws ReconcileException {
        if (batchId == null || batchId.isBlank()) {
            throw new IllegalArgumentException("batchId 不得为空白：批次号是回执查询的唯一入参，"
                    + "空批次号拼出的查询路径在多数对端会返回一个含义不明的列表而不是错误");
        }
        URI uri = acksUri(baseUrl(), batchId.trim());

        // ---- 出站调用点：GET {ARCHIVE_ACK_API_BASE_URL}/batches/{batchId}/acks ----
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(readTimeout)
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        // ---- 出站调用点结束 ----

        long startedNanos = System.nanoTime();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpConnectTimeoutException e) {
            throw failure(ReconcileErrorCode.CONNECT_TIMEOUT, null, startedNanos, batchId, e);
        } catch (HttpTimeoutException e) {
            throw failure(ReconcileErrorCode.READ_TIMEOUT, null, startedNanos, batchId, e);
        } catch (IOException e) {
            throw failure(ReconcileErrorCode.TRANSPORT_FAILURE, null, startedNanos, batchId, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw failure(ReconcileErrorCode.INTERRUPTED, null, startedNanos, batchId, e);
        }

        int status = response.statusCode();
        if (status / 100 != 2) {
            throw failure(ReconcileErrorCode.classify(status), status, startedNanos, batchId, null);
        }

        RemoteAckPage page = parsePage(response.body(), batchId.trim());
        LOG.log(Level.INFO, "回执对账成功: {0} 耗时={1}ms", new Object[]{page.toLogLine(),
                elapsedMillis(startedNanos)});
        return page;
    }

    // =====================================================================
    // 响应解析（正式字段规范）
    // =====================================================================

    /**
     * 按正式字段规范解析回执页。
     *
     * <p>字段缺失分两种处置：{@code acks} 数组缺失、或数组内某条缺控制号 → 抛
     * {@link ReconcileErrorCode#MALFORMED_RESPONSE}（<b>不能猜</b>：一个不认识的响应体若被
     * 当成「空列表」，差集就会把整批都判成缺失并全量重投）；
     * {@code ackCount} 缺失 → 记 {@code -1} 并在报表里显示「对端未回报」；
     * {@code receivedAt} 缺失或不可解析 → 取 {@link Instant#EPOCH}（它只用于人工排障，
     * 不参与差集决策，缺了不该让整页失败）。</p>
     */
    static RemoteAckPage parsePage(String body, String requestedBatchId) throws ReconcileException {
        Map<String, Object> root;
        try {
            root = Json.asObject(Json.parse(body), "回执列表响应体");
        } catch (RuntimeException e) {
            throw malformed("响应体不是合法 JSON", requestedBatchId, e);
        }

        Object rawAcks = root.get("acks");
        if (rawAcks == null) {
            throw malformed("响应体缺 acks 数组", requestedBatchId, null);
        }
        List<Object> items;
        try {
            items = Json.asArray(rawAcks, "acks");
        } catch (RuntimeException e) {
            throw malformed("acks 不是数组", requestedBatchId, e);
        }

        int declared = Json.asIntOrDefault(root.get("ackCount"), "ackCount", -1);
        String responseBatchId = Json.asStringOrDefault(root.get("batchId"), "batchId", requestedBatchId);

        List<RemoteAck> acks = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            Map<String, Object> item;
            try {
                item = Json.asObject(items.get(i), "acks[" + i + "]");
            } catch (RuntimeException e) {
                throw malformed("acks[" + i + "] 不是对象", requestedBatchId, e);
            }
            String controlId = Json.asStringOrDefault(item.get("controlId"), "acks[" + i + "].controlId", "");
            String messageType = Json.asStringOrDefault(item.get("messageType"), "acks[" + i + "].messageType", "");
            String msaCode = Json.asStringOrDefault(item.get("msaCode"), "acks[" + i + "].msaCode", "");
            Instant receivedAt = parseInstant(item.get("receivedAt"), "acks[" + i + "].receivedAt");
            try {
                acks.add(RemoteAck.requireUsable(controlId, messageType, msaCode, receivedAt,
                        "acks[" + i + "]"));
            } catch (RuntimeException e) {
                throw malformed("acks[" + i + "] 字段不可解析", requestedBatchId, e);
            }
        }

        return new RemoteAckPage(responseBatchId, declared, acks,
                declared >= 0 && declared != acks.size());
    }

    /** 接收时刻解析失败时退回 {@link Instant#EPOCH} 并记告警，不让整页对账失败。 */
    private static Instant parseInstant(Object value, String where) {
        if (value == null) {
            return Instant.EPOCH;
        }
        String text;
        try {
            text = Json.asString(value, where);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "回执 {0} 的 receivedAt 不是字符串，按未回报处理", where);
            return Instant.EPOCH;
        }
        if (text.isBlank()) {
            return Instant.EPOCH;
        }
        try {
            return Instant.parse(text.trim());
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "回执 {0} 的 receivedAt 不可解析（仅影响排障显示，不影响差集）", where);
            return Instant.EPOCH;
        }
    }

    private static ReconcileException malformed(String reason, String batchId, Throwable cause) {
        ReconcileException e = new ReconcileException(ReconcileErrorCode.MALFORMED_RESPONSE,
                "回执对账失败：" + ReconcileErrorCode.MALFORMED_RESPONSE.zhLabel()
                        + "（" + reason + "），批次=" + batchId
                        + "，处置=" + ReconcileErrorCode.MALFORMED_RESPONSE.disposition()
                        + "。不猜对端的字段口径：把不认识的响应体当成空列表会让整批被判成缺失并全量重投"
                        + "（响应体不记入日志，可能含机构内部信息）",
                null, cause);
        LOG.log(Level.WARNING, e.getMessage());
        return e;
    }

    // =====================================================================
    // 内部
    // =====================================================================

    /** 失败抛出前的统一出口：分类、留痕、上抛。日志与异常消息都不含令牌与响应体。 */
    private ReconcileException failure(ReconcileErrorCode code, Integer status,
                                       long startedNanos, String batchId, Throwable cause) {
        String message = "回执对账失败：" + code.zhLabel()
                + (status == null ? "" : "（HTTP " + status + "）")
                + "，批次=" + batchId
                + "，目标=" + describeEndpoint()
                + "，耗时=" + elapsedMillis(startedNanos) + "ms"
                + "，处置=" + code.disposition()
                + "。本轮不出差集、不清空重投清单（响应体不记入日志，可能含机构内部信息）";
        if (cause == null) {
            LOG.log(Level.WARNING, message);
        } else {
            LOG.log(Level.WARNING, message + ", 原因=" + cause.getClass().getSimpleName());
        }
        return new ReconcileException(code, message, status, cause);
    }

    /** 基址 + 路径模板；{@code batchId} 做 URL 路径段编码。 */
    static URI acksUri(String baseUrl, String batchId) {
        String encoded = URLEncoder.encode(batchId, StandardCharsets.UTF_8)
                // URLEncoder 为表单编码产出 '+'，路径段里它表示字面加号而非空格
                .replace("+", "%20");
        String trimmed = baseUrl;
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return URI.create(trimmed + PATH_TEMPLATE.formatted(encoded));
    }

    /** 校验并归一对端基址：必须是 http/https 且带主机名。 */
    static String baseUri(String baseUrl) {
        Objects.requireNonNull(baseUrl, "ARCHIVE_ACK_API_BASE_URL 不得为 null");
        String trimmed = baseUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("ARCHIVE_ACK_API_BASE_URL 不得为空白");
        }
        URI base;
        try {
            base = URI.create(trimmed);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("ARCHIVE_ACK_API_BASE_URL 不是合法 URL：" + trimmed, e);
        }
        String scheme = base.getScheme();
        if (scheme == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException("ARCHIVE_ACK_API_BASE_URL 协议必须是 http 或 https：" + trimmed);
        }
        if (base.getHost() == null || base.getHost().isBlank()) {
            throw new IllegalArgumentException("ARCHIVE_ACK_API_BASE_URL 缺少主机名：" + trimmed);
        }
        return trimmed;
    }

    private String baseUrl() {
        return baseUri.toString();
    }

    private static HttpClient defaultHttpClient(Duration timeout) {
        return HttpClient.newBuilder()
                .connectTimeout(min(timeout, MAX_CONNECT_TIMEOUT))
                .version(HttpClient.Version.HTTP_1_1)
                // 重定向一律不跟随：对端回执查询是只读接口，跳去别的主机等于把令牌带出可信域
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    /** 日志里的对端描述：只给主机与端口，不带路径（可能含机构内部路由）。 */
    private String describeEndpoint() {
        String host = baseUri.getHost();
        int port = baseUri.getPort();
        return port < 0 ? host : host + ":" + port;
    }

    private static long elapsedMillis(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    private static String requireToken(String value) {
        Objects.requireNonNull(value, "ARCHIVE_ACK_API_TOKEN 不得为 null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("ARCHIVE_ACK_API_TOKEN 不得为空白："
                    + "空令牌等于拿一个必然 401 的请求去消耗对端的鉴权配额");
        }
        return value;
    }

    private static Duration requireTimeout(Duration timeout) {
        Objects.requireNonNull(timeout, "超时不得为 null");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("超时必须为正数，实际 " + timeout);
        }
        return timeout;
    }

    /** 当前配置的单行摘要（不含令牌）。 */
    public String describe() {
        return "区域平台回执对账: GET " + describeEndpoint() + PATH_TEMPLATE
                + " 超时=" + readTimeout.toMillis() + "ms 协议=HTTP/1.1 不跟随重定向";
    }
}