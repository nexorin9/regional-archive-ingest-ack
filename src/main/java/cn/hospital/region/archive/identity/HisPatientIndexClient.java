package cn.hospital.region.archive.identity;

import cn.hospital.region.archive.config.AppConfig;
import cn.hospital.region.archive.model.Json;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 院内患者主索引写回 —— <b>本产品院内出站的真实实现</b>（T16 端口的唯一正式实现）。
 *
 * <h2>正式对接规范</h2>
 * <pre>
 *   PUT {HIS_MPI_ENDPOINT}/patient-index
 *   Authorization: Bearer {HIS_MPI_TOKEN}
 *   Content-Type: application/json; charset=UTF-8
 *   Accept: application/json
 *   Idempotency-Key: his-{三字段的 SHA-256 前 32 位十六进制}
 *
 *   {"patientRef":"PAT-0001","localMrn":"LOCMRN-0001","sourceBatch":"REGIONAL_ARCHIVE-20261020"}
 * </pre>
 * 端点与令牌<b>只从环境变量读</b>（{@code HIS_MPI_ENDPOINT} / {@code HIS_MPI_TOKEN}），
 * 缺任一即启动期 {@code ConfigException} 非零退出 —— 本类<b>没有</b>任何默认地址或兜底替身，
 * 写不出「静默改成打本地」的路径。
 *
 * <h2>三个字段为什么就是这三个</h2>
 * <p>{@code patientRef} 是区域平台主键（PID-3 第一组件），{@code localMrn} 是院内主键，
 * {@code sourceBatch} 是台账批次号。三者缺一，院内侧就答不上「这条是谁、从哪一批来的」：
 * 只有主键，对不上号时无法回查；只有批次，无法定位到具体是哪位居民。
 * <b>两套主键必须分列</b>，绝不合成一个字段 —— spec.md 锁定的「双主键分离」在这里是字面要求。</p>
 *
 * <h2>失败语义：绝不静默改数据</h2>
 * <p>非 2xx、超时、连不上，一律抛 {@link HisPatientIndexWriteException}（{@code replayable=true}），
 * 由 {@code ArchiveIngestService.land} 把行退回待确认、保留原文与幂等键。
 * <b>任何一类失败都不允许把行留在已落地</b> —— 那正是「回执说落地了、HIS 里查无此人」，
 * 且次日对账时数字对不上、现场查不出是哪一步错的。</p>
 *
 * <p>响应体<b>不记入异常消息也不打日志</b>：非 2xx 的响应体常带院内侧内部信息，
 * 甚至带可识别内容；状态码与失败分类码已经够定位。</p>
 *
 * <h2>为什么额外发一个 Idempotency-Key 头</h2>
 * <p>本端已经用「乐观锁认领 + 状态机」保证<b>只发一次</b>出站写（T16/T30 的断言）。
 * 这个头是<b>对端侧的额外一层</b>：院内 HIS 与前置机之间若因网络抖动重发同一条 PUT，
 * 对端靠这个头自行识别为同一笔写、不会在主索引里插两条。
 * 它取三个字段的 SHA-256 前 32 位十六进制 —— 主键<b>不出头</b>，对端日志里也不留明文主键。</p>
 *
 * <h2>为什么固定 HTTP/1.1</h2>
 * <p>院内前置机与 HIS 患者主索引服务大多只实现 HTTP/1.1，{@link HttpClient} 默认会对 https 端点
 * 尝试 HTTP/2 升级。这里显式钉在 1.1：升级失败会表现为「同样一份报文忽然开始报传输层错误」，
 * 而这类环境问题极难在事后从日志里分辨到底是协议还是对端挂了。</p>
 */
public final class HisPatientIndexClient implements PatientIndexWriter {

    private static final Logger LOG = Logger.getLogger(HisPatientIndexClient.class.getName());

    /** 正式对接规范的路径段：{@code PUT {HIS_MPI_ENDPOINT}/patient-index}。 */
    public static final String PATH_SUFFIX = "/patient-index";

    /** 读超时默认值：院内同机房调用，10 秒足够；再久只会拖住整个确认队列。 */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    /** 连接超时上限：连不上要在秒级失败，不能把一批确认全卡住。 */
    private static final Duration MAX_CONNECT_TIMEOUT = Duration.ofSeconds(5);

    /** {@code Idempotency-Key} 头的取值长度（十六进制字符数）。 */
    private static final int IDEMPOTENCY_DIGEST_CHARS = 32;

    /**
     * 拼接分隔符：U+001F 单元分隔符。
     *
     * <p>与 {@code ledger.IdempotencyKey} 同口径。三个字段直接用 "-" 或 "|" 拼会出二义：
     * 批次号本身可能含连字符（{@code REGIONAL_ARCHIVE-20261020}），
     * 拼出来 {@code A-B + C} 与 {@code A + B-C} 会算出同一个摘要。
     */
    private static final char IDEMPOTENCY_SEPARATOR = '\u001F';

    private final HttpClient http;
    private final URI patientIndexUri;
    private final String token;
    private final Duration readTimeout;

    /** 生产装配：端点与令牌取自启动配置（环境变量）。 */
    public HisPatientIndexClient(AppConfig config) {
        this(Objects.requireNonNull(config, "启动配置不得为 null").hisMpiEndpoint(),
                config.hisMpiToken(), DEFAULT_TIMEOUT);
    }

    /** 显式装配（契约测试与离线核对用同一套构造逻辑）。 */
    public HisPatientIndexClient(String endpoint, String token, Duration timeout) {
        this(defaultHttpClient(timeout), patientIndexUri(endpoint), requireToken(token),
                requireTimeout(timeout));
    }

    /** 注入 HTTP 客户端：院内前置机走代理、走自定义 TLS 时用得到。 */
    public HisPatientIndexClient(HttpClient http, URI patientIndexUri, String token, Duration readTimeout) {
        this.http = Objects.requireNonNull(http, "HttpClient 不得为 null");
        this.patientIndexUri = Objects.requireNonNull(patientIndexUri, "写回 URI 不得为 null");
        this.token = requireToken(token);
        this.readTimeout = requireTimeout(readTimeout);
    }

    // =====================================================================
    // 端口实现：本产品院内出站的唯一调用点
    // =====================================================================

    /**
     * 向院内 HIS 患者主索引写入（或幂等更新）一条主索引映射。
     *
     * <p><b>本方法是本产品唯一定义院内出站报文形状的地方</b>：路径、方法、请求头、
     * JSON 三个字段都在这里成型，验收时对端收到的每一个字节都能追到这一行。</p>
     *
     * @throws IllegalArgumentException 任一字段为空白 —— 入参校验，<b>不发请求</b>
     * @throws HisPatientIndexWriteException 对端非 2xx、超时或连不上；
     *                                        调用方须把行退回待确认，<b>不得置已落地</b>
     */
    @Override
    public void put(String patientRef, String localMrn, String sourceBatch)
            throws PatientIndexWriteException {
        requireText(patientRef, "patientRef");
        requireText(localMrn, "localMrn");
        requireText(sourceBatch, "sourceBatch");

        Map<String, Object> payload = Json.object();
        payload.put("patientRef", patientRef);
        payload.put("localMrn", localMrn);
        payload.put("sourceBatch", sourceBatch);

        // ---- 出站调用点：PUT {HIS_MPI_ENDPOINT}/patient-index ----
        HttpRequest request = HttpRequest.newBuilder(patientIndexUri)
                .timeout(readTimeout)
                .header("Content-Type", "application/json; charset=UTF-8")
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", idempotencyKeyOf(patientRef, localMrn, sourceBatch))
                .PUT(HttpRequest.BodyPublishers.ofString(Json.write(payload), StandardCharsets.UTF_8))
                .build();
        // ---- 出站调用点结束 ----

        long startedNanos = System.nanoTime();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpConnectTimeoutException e) {
            throw failure(HisWriteErrorCode.CONNECT_TIMEOUT, null, startedNanos, patientRef, e);
        } catch (HttpTimeoutException e) {
            throw failure(HisWriteErrorCode.READ_TIMEOUT, null, startedNanos, patientRef, e);
        } catch (IOException e) {
            throw failure(HisWriteErrorCode.TRANSPORT_FAILURE, null, startedNanos, patientRef, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw failure(HisWriteErrorCode.INTERRUPTED, null, startedNanos, patientRef, e);
        }

        int status = response.statusCode();
        if (status / 100 != 2) {
            throw failure(HisWriteErrorCode.classify(status), status, startedNanos, patientRef, null);
        }

        LOG.log(Level.INFO, "写院内患者主索引成功: patientRef={0} localMrn={1} sourceBatch={2} "
                        + "endpoint={3} status={4} 耗时={5}ms",
                new Object[]{patientRef, localMrn, sourceBatch, describeEndpoint(),
                        status, elapsedMillis(startedNanos)});
    }

    // =====================================================================
    // 内部
    // =====================================================================

    /** 失败抛出前的统一出口：分类、留痕、上抛。日志与异常消息都不含令牌与响应体。 */
    private HisPatientIndexWriteException failure(HisWriteErrorCode code, Integer status,
                                                   long startedNanos, String patientRef, Throwable cause) {
        StringBuilder sb = new StringBuilder("写院内患者主索引失败：");
        sb.append(code.zhLabel());
        if (status != null) {
            sb.append("（HTTP ").append(status).append('）');
        }
        sb.append("，目标 ").append(describeEndpoint());
        sb.append("，耗时 ").append(elapsedMillis(startedNanos)).append("ms");
        sb.append("，处置=").append(code.jittery() ? "原样重投即可恢复" : "先查配置与主索引冲突，再决定是否人工放行");
        sb.append("（响应体不记入日志，可能含可识别内容）");

        String message = sb.toString();
        if (cause == null) {
            LOG.log(Level.WARNING, message + ", patientRef=" + patientRef);
        } else {
            LOG.log(Level.WARNING, message + ", patientRef=" + patientRef
                    + ", 原因=" + cause.getClass().getSimpleName());
        }
        return new HisPatientIndexWriteException(message, code, status, cause);
    }

    /**
     * 对端侧的幂等键：三个字段的 SHA-256 前 32 位十六进制，加 {@code his-} 前缀。
     *
     * <p>取摘要而不是明文拼接有两个原因：一是主键不出请求头，院内前置机的访问日志里不会
     * 留下患者标识的明文；二是长度有界，不同批次不同主键算出来的键长度一致，
     * 对端按它建去重表时不会碰到超长值截断。</p>
     */
    private static String idempotencyKeyOf(String patientRef, String localMrn, String sourceBatch) {
        String material = patientRef + IDEMPOTENCY_SEPARATOR + localMrn + IDEMPOTENCY_SEPARATOR + sourceBatch;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            return "his-" + HexFormat.of().formatHex(digest, 0, IDEMPOTENCY_DIGEST_CHARS / 2);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 规范要求必须实现的算法；真拿不到说明运行环境残缺，直接失败比
            // 悄悄退化成「不带幂等键发出去」安全 —— 后者会让对端无从去重。
            throw new IllegalStateException("运行环境缺少 SHA-256，无法生成对端幂等键", e);
        }
    }

    /** 端点 + 固定路径段。端点带基础路径（如 {@code http://前置机:8080/mpi}）时原样保留前缀。 */
    static URI patientIndexUri(String endpoint) {
        Objects.requireNonNull(endpoint, "HIS_MPI_ENDPOINT 不得为 null");
        String trimmed = endpoint.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("HIS_MPI_ENDPOINT 不得为空白");
        }
        URI base;
        try {
            base = URI.create(trimmed);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("HIS_MPI_ENDPOINT 不是合法 URL：" + trimmed, e);
        }
        String scheme = base.getScheme();
        if (scheme == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException("HIS_MPI_ENDPOINT 协议必须是 http 或 https：" + trimmed);
        }
        if (base.getHost() == null || base.getHost().isBlank()) {
            throw new IllegalArgumentException("HIS_MPI_ENDPOINT 缺少主机名：" + trimmed);
        }
        return URI.create(trimmed + PATH_SUFFIX);
    }

    private static HttpClient defaultHttpClient(Duration timeout) {
        return HttpClient.newBuilder()
                // 连接超时取读超时与 5 秒中的较小者：对端不可达时要秒级失败
                .connectTimeout(min(timeout, MAX_CONNECT_TIMEOUT))
                // 显式钉 HTTP/1.1：院内前置机与 HIS 主索引服务大多只实现 1.1（见类注释）
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    /** 日志里的对端描述：只给主机与端口，路径与查询串不带（可能含机构内部路由信息）。 */
    private String describeEndpoint() {
        String host = patientIndexUri.getHost();
        int port = patientIndexUri.getPort();
        return port < 0 ? host : host + ":" + port;
    }

    private static long elapsedMillis(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    private static String requireToken(String value) {
        Objects.requireNonNull(value, "HIS_MPI_TOKEN 不得为 null");
        if (value.isBlank()) {
            // 令牌为空还照发一次请求，等于拿一个必然 401 的请求去消耗对端的鉴权配额；
            // 在这里失败，问题在启动摘要里就能看出来（缺的是 HIS_MPI_TOKEN 这个名字）。
            throw new IllegalArgumentException("HIS_MPI_TOKEN 不得为空白");
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

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不得为空白：院内出站写必须能追溯到"
                    + "「区域主键 + 院内主键 + 来源批次」三件套");
        }
    }

    /** 当前配置的单行摘要（不含令牌）。 */
    public String describe() {
        return "院内患者主索引写回: PUT " + describeEndpoint() + PATH_SUFFIX
                + " 超时=" + readTimeout.toMillis() + "ms 协议=HTTP/1.1";
    }
}