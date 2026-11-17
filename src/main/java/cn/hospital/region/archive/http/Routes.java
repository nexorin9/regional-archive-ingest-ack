package cn.hospital.region.archive.http;

import cn.hospital.region.archive.identity.ConfirmDecision;
import cn.hospital.region.archive.identity.ConfirmGate;
import cn.hospital.region.archive.identity.ConfirmOutcome;
import cn.hospital.region.archive.identity.IdentityKeyBinding;
import cn.hospital.region.archive.ledger.BatchStats;
import cn.hospital.region.archive.ledger.ConcurrentLedgerUpdateException;
import cn.hospital.region.archive.ledger.IdempotencyKey;
import cn.hospital.region.archive.ledger.IllegalStateTransitionException;
import cn.hospital.region.archive.ledger.LedgerReadView;
import cn.hospital.region.archive.ledger.LedgerRow;
import cn.hospital.region.archive.ledger.LedgerStateConflictException;
import cn.hospital.region.archive.ledger.LedgerStore;
import cn.hospital.region.archive.ledger.MessageState;
import cn.hospital.region.archive.ledger.RevertNotAllowedException;
import cn.hospital.region.archive.ledger.RevertService;
import cn.hospital.region.archive.model.Json;
import cn.hospital.region.archive.redelivery.AckDiff;
import cn.hospital.region.archive.redelivery.BatchReconcilerClient;
import cn.hospital.region.archive.redelivery.AckReconciler;
import cn.hospital.region.archive.redelivery.ControlIdNormalizer;
import cn.hospital.region.archive.redelivery.LocalExpectedAck;
import cn.hospital.region.archive.redelivery.RemoteAckPage;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Clock;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 院内 HTTP API 的<b>全部路由与业务编排</b> —— 本轮交付形态就落在这里。
 *
 * <h2>为什么是「无界面 API」而不是一屏看板</h2>
 * <p>确认动作本就由<b>院内既有系统</b>承担（区域健康档案平台 → 本产品 → HIS，本产品是中间一段），
 * 再做一屏界面等于让同一个动作有两处入口，而两处入口迟早会在「谁写的院内主键」上分叉。
 * 所以这里只提供接口；产出物是<b>字节级回执报文</b>与<b>可核对的台账查询</b>，不是给人看的画面。
 *
 * <h2>端点（{@code /healthz} 之外四个业务端点全部过令牌）</h2>
 * <table border="1">
 *   <caption>路由表</caption>
 *   <tr><th>方法</th><th>路径</th><th>回答什么问题</th></tr>
 *   <tr><td>GET</td><td>{@code /healthz}</td><td>进程活没活（<b>不要令牌</b>，探针没有凭据）</td></tr>
 *   <tr><td>POST</td><td>{@code /batches/{batchId}/confirm}</td><td>「我确认这一条属于哪位居民」</td></tr>
 *   <tr><td>POST</td><td>{@code /batches/{batchId}/revert}</td><td>「这一条合错了，退回待确认重走」</td></tr>
 *   <tr><td>GET</td><td>{@code /messages?state=&batchId=&limit=}</td><td>「现在有几条停在哪一档」</td></tr>
 *   <tr><td>GET</td><td>{@code /batches/{batchId}/stats}</td><td>「这一批四态分布如何、拒收原因占多少」</td></tr>
 *   <tr><td>GET</td><td>{@code /batches/{batchId}/reconcile}</td><td>「对端还差哪几条没认」</td></tr>
 * </table>
 *
 * <h2>409 是这里最重要的一档</h2>
 * <p>「已落地的条目被重复确认」「拒收条目被确认」「两个信息员同时点同一条」这三件事，
 * 写成 200 会让界面显示成功而系统什么都没做，写成 500 会让院内系统以为要回滚整个批次。
 * 它们统一走 {@link ApiErrorCode#STATE_CONFLICT}（409），且<b>响应体带当前状态</b> ——
 * 调用方据此把该条从待办列表里去掉，而不是重试。
 *
 * <h2>确认端点的批次归属校验</h2>
 * <p>{@code POST /batches/{batchId}/confirm} 里的 {@code ledgerId} 必须<b>属于这个批次</b>。
 * 少了这一步，界面把 A 批的条目拿到 B 批的路径上提交也能确认成功，
 * 而批次号正是事后跟区域平台对账的口径——错批次的确认会让对账数字永久对不上。
 *
 * <h2>对账端点只读，不入队</h2>
 * <p>{@code GET .../reconcile} 只做<b>取回执 + 算差集</b>，绝不把缺失条目塞进重投队列：
 * 入队是一个写动作，挂在 GET 上意味着「刷新页面就会重投一批」，且各家的网关会预取、
 * 探针会重试。本端只给清单，<b>入队由 T33 的重投调度按退避策略决定</b>。
 */
public final class Routes {

    private static final Logger LOG = Logger.getLogger(Routes.class.getName());

    /** 本院 API 版本；进 {@code /healthz}，T39 的 OpenAPI 对齐以它为准。 */
    public static final String API_VERSION = "v1";

    /** 服务名；进 {@code /healthz}。 */
    public static final String SERVICE_NAME = "regional-archive-ingest-ack";

    /**
     * 暴露给 T39 的 {@code OpenApiConsistencyTest}：
     * 「实际代码注册的路由」必须与 {@code openapi.yaml} 双向相等。
     *
     * <p>每项给出（方法, 模板路径）：
     * <ol>
     *   <li>{@code GET /healthz} —— 探针，**不带令牌**；</li>
     *   <li>{@code POST /batches/{batchId}/confirm} —— 唯一落地通道；</li>
     *   <li>{@code POST /batches/{batchId}/revert} —— 撤回；</li>
     *   <li>{@code GET /messages} —— 按 state 列出（state 为必填）；</li>
     *   <li>{@code GET /batches/{batchId}/stats} —— 批次四态分布；</li>
     *   <li>{@code GET /batches/{batchId}/reconcile} —— 与对端回执对账。</li>
     * </ol>
     */
    public record DeclaredRoute(String method, String pathTemplate) {
        @Override public String toString() { return method + " " + pathTemplate; }
    }

    /**
     * 实际代码注册过的路由 —— 严格与 {@link #dispatch} 的分发分支一一对应。
     * OpenAPI 文档的 paths 块必须与这份集合**双向相等**，否则 {@code OpenApiConsistencyTest} 立刻炸。
     */
    public static List<DeclaredRoute> declaredRoutes() {
        return List.of(
                new DeclaredRoute("GET", "/healthz"),
                new DeclaredRoute("POST", "/batches/{batchId}/confirm"),
                new DeclaredRoute("POST", "/batches/{batchId}/revert"),
                new DeclaredRoute("GET", "/messages"),
                new DeclaredRoute("GET", "/batches/{batchId}/stats"),
                new DeclaredRoute("GET", "/batches/{batchId}/reconcile"));
    }

    /** 确认请求体里 {@code decision.source} 的缺省值：调用方没说是从哪挑的，就记「未说明」。 */
    private static final String DEFAULT_CANDIDATE_SOURCE = "api-call";

    /** 响应里的时间格式：与全院口径一致（{@code yyyy-MM-dd HH:mm}），不用 ISO 的 T/Z。 */
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final ApiTokenGuard tokens;
    private final ConfirmGate gate;
    private final LedgerReadView ledger;
    private final LedgerStore store;
    private final BatchReconcilerClient reconciler;
    private final Function<IdempotencyKey, String> ackCodeLookup;
    private final String localAet;
    private final Clock clock;
    private final String schemaVersion;

    /**
     * @param schemaVersion 当前台账 schema 版本（{@code V5} 之类），只用于自述
     */
    public Routes(ApiTokenGuard tokens,
                  ConfirmGate gate,
                  LedgerReadView ledger,
                  LedgerStore store,
                  BatchReconcilerClient reconciler,
                  Function<IdempotencyKey, String> ackCodeLookup,
                  String localAet,
                  Clock clock,
                  String schemaVersion) {
        this.tokens = Objects.requireNonNull(tokens, "令牌校验器不得为 null");
        this.gate = Objects.requireNonNull(gate, "确认门不得为 null");
        this.ledger = Objects.requireNonNull(ledger, "台账只读面不得为 null");
        this.store = Objects.requireNonNull(store, "台账存储不得为 null");
        this.reconciler = Objects.requireNonNull(reconciler, "回执对账客户端不得为 null");
        this.ackCodeLookup = ackCodeLookup == null ? key -> null : ackCodeLookup;
        this.localAet = Objects.requireNonNull(localAet, "本院 AE Title 不得为 null");
        this.clock = Objects.requireNonNull(clock, "clock 不得为 null");
        this.schemaVersion = Objects.requireNonNull(schemaVersion, "schema 版本不得为 null");
    }

    // =====================================================================
    // 路由
    // =====================================================================

    /**
     * 分派一次请求。
     *
     * <p><b>本方法不抛异常</b>：所有失败都变成带 {@code errorCode} 的响应体。
     * 传输层（{@link HttpApiServer}）因此只需要「拿到响应就写回去」这一条路径，
     * 不会出现「handler 抛了但连接已经被读过一半」的半截响应。
     */
    public ApiResponse dispatch(ApiRequest request) {
        try {
            if (request.isMethod("GET") && "/healthz".equals(normalizePath(request.path()))) {
                return healthz();
            }
            ApiTokenGuard.Principal principal = tokens.authenticate(request);
            String path = normalizePath(request.path());
            if (request.isMethod("POST")) {
                if (path.endsWith("/confirm")) {
                    return confirm(request, principal, path);
                }
                if (path.endsWith("/revert")) {
                    return revert(request, principal, path);
                }
                throw new ApiException(ApiErrorCode.ROUTE_NOT_FOUND,
                        "没有这个接口：" + request.method() + " " + request.path(),
                        Map.of("supportedPostPaths",
                                List.of("/batches/{batchId}/confirm", "/batches/{batchId}/revert")));
            }
            if (request.isMethod("GET")) {
                if ("/messages".equals(path)) {
                    return messages(request);
                }
                if (path.endsWith("/stats")) {
                    return stats(path);
                }
                if (path.endsWith("/reconcile")) {
                    return reconcile(path);
                }
            }
            throw new ApiException(ApiErrorCode.ROUTE_NOT_FOUND,
                    "没有这个接口：" + request.method() + " " + request.path(), null);
        } catch (ApiException e) {
            LOG.log(Level.WARNING, "接口返回 {0}：method={1} path={2} code={3} msg={4}",
                    new Object[]{e.code().httpStatus(), request.method(), request.path(),
                            e.code().name(), e.getMessage()});
            return ApiResponse.error(e.code(), e.getMessage(), request.path(), e.detail());
        } catch (Exception e) {
            // 兜底：任何漏网的异常都不许把栈或 SQL 片段发给院内系统。
            LOG.log(Level.SEVERE, "接口内部错误 method=" + request.method() + " path=" + request.path(), e);
            return ApiResponse.error(ApiErrorCode.INTERNAL_ERROR, null, request.path(), null);
        }
    }

    // =====================================================================
    // GET /healthz
    // =====================================================================

    private ApiResponse healthz() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("service", SERVICE_NAME);
        body.put("apiVersion", API_VERSION);
        body.put("localAet", localAet);
        body.put("schemaVersion", schemaVersion);
        body.put("time", TIMESTAMP.format(clock.instant()));
        return ApiResponse.ok(body);
    }

    // =====================================================================
    // POST /batches/{batchId}/confirm
    // =====================================================================

    private ApiResponse confirm(ApiRequest request, ApiTokenGuard.Principal principal, String path)
            throws Exception {
        String batchId = batchIdOf(path, "confirm");
        Map<String, Object> body = parseBodyObject(request);
        long ledgerId = requireLedgerId(body);

        // 批次归属：不在这一批里的行一律 404，不给「确认成功」。
        LedgerRow row = store.findById(ledgerId).orElseThrow(() -> new ApiException(
                ApiErrorCode.LEDGER_ROW_NOT_FOUND,
                "台账里没有 id=" + ledgerId + " 这一条", Map.of("ledgerId", ledgerId)));
        if (!row.batchId().equals(batchId)) {
            throw new ApiException(ApiErrorCode.LEDGER_ROW_NOT_FOUND,
                    "这条不属于批次 " + batchId + "（实际属于 " + row.batchId() + "）",
                    Map.of("ledgerId", ledgerId, "requestedBatchId", batchId,
                            "actualBatchId", row.batchId()));
        }

        // 操作人只从令牌来：请求体里就算带了 operator 也不采信 ——
        // 否则「谁点的」就是调用方自己说了算，审计行形同虚设。
        ConfirmDecision decision = new ConfirmDecision(
                principal.name(),
                IdentityKeyBinding.bound(
                        requireText(body, "patientRef", "区域平台主键，取报文 PID-3，不得为空"),
                        requireText(body, "localMrn", "院内患者主键，信息员挑的那一位")),
                optionalText(body, "candidateSource", DEFAULT_CANDIDATE_SOURCE),
                optionalText(body, "note", ""));

        ConfirmOutcome outcome = invokeConfirm(ledgerId, decision, batchId, path);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("confirmed", outcome.confirmed());
        result.put("ledgerId", ledgerId);
        result.put("batchId", batchId);
        result.put("operator", principal.name());
        result.put("humanClaimed", principal.humanClaimed());
        result.put("state", outcome.state() == null ? null : outcome.state().dbValue());
        result.put("wrotePatientIndex", outcome.wrotePatientIndex());
        result.put("patientRef", outcome.patientRef());
        result.put("localMrn", outcome.localMrn());
        result.put("note", outcome.note());

        if (outcome.refused()) {
            result.put("refusal", outcome.refusal().name());
            return ApiResponse.error(ApiErrorCode.STATE_CONFLICT, outcome.note(), path, result);
        }
        LOG.log(Level.INFO, "接口确认生效：ledgerId={0} batch={1} operator={2} 写院内主索引={3}",
                new Object[]{ledgerId, batchId, principal.name(), outcome.wrotePatientIndex()});
        return ApiResponse.confirmed(result);
    }

    /**
     * 调确认门，并把它的异常翻成状态码。
     *
     * <p><b>两处分界</b>：①{@link cn.hospital.region.archive.identity.PatientIndexWriteException}
     * 是「HIS 暂时写不进去」→ 503，该条<b>仍停在待确认</b>，报文原文与幂等键都还在台账里，
     * 人重试即可落地；②{@link cn.hospital.region.archive.identity.KeyBindingConflictException}
     * 是「你认的这位居民和台账里已绑的不一样」→ 409，且必须把冲突原因原样带出去，
     * 否则信息员只会看到一句「冲突」，没法判断是自己点错行还是台账被人动过。
     *
     * <p>「已落地重复确认」这类冲突不走异常——确认门把它作为<b>正常结论</b>返回
     * （{@link ConfirmOutcome#refused()}）：那是界面重复提交时的常见路径，
     * 不是该被错误栈盖住的事。
     */
    private ConfirmOutcome invokeConfirm(long ledgerId, ConfirmDecision decision,
                                         String batchId, String path) throws SQLException {
        try {
            return gate.confirm(ledgerId, decision);
        } catch (cn.hospital.region.archive.identity.PatientIndexWriteException e) {
            LOG.log(Level.WARNING, "确认已过闸但写院内主索引失败：ledgerId={0} msg={1}",
                    new Object[]{ledgerId, e.getMessage()});
            throw new ApiException(ApiErrorCode.HIS_WRITE_FAILED,
                    "院内患者主索引写入失败：" + e.getMessage()
                            + "（该条仍停在待确认，报文与幂等键都在台账里，可重试）",
                    Map.of("ledgerId", ledgerId, "batchId", batchId,
                            "state", MessageState.PENDING_CONFIRM.dbValue()));
        } catch (cn.hospital.region.archive.identity.KeyBindingConflictException e) {
            throw new ApiException(ApiErrorCode.STATE_CONFLICT, e.getMessage(),
                    Map.of("ledgerId", ledgerId, "batchId", batchId));
        }
    }

    /** 冲突类异常的文案：带上异常自己的说明，但不给堆栈。 */
    private static String conflictMessage(Exception e) {
        return "当前状态不允许这一步操作：" + e.getMessage();
    }

    // =====================================================================
    // POST /batches/{batchId}/revert
    // =====================================================================

    private ApiResponse revert(ApiRequest request, ApiTokenGuard.Principal principal, String path)
            throws Exception {
        String batchId = batchIdOf(path, "revert");
        Map<String, Object> body = parseBodyObject(request);
        long ledgerId = requireLedgerId(body);
        String reason = requireText(body, "reason", "撤回理由（进审计行，必须能看出为什么撤）");

        LedgerRow row = store.findById(ledgerId).orElseThrow(() -> new ApiException(
                ApiErrorCode.LEDGER_ROW_NOT_FOUND,
                "台账里没有 id=" + ledgerId + " 这一条", Map.of("ledgerId", ledgerId)));
        if (!row.batchId().equals(batchId)) {
            throw new ApiException(ApiErrorCode.LEDGER_ROW_NOT_FOUND,
                    "这条不属于批次 " + batchId + "（实际属于 " + row.batchId() + "）",
                    Map.of("ledgerId", ledgerId, "requestedBatchId", batchId,
                            "actualBatchId", row.batchId()));
        }

        var outcome = invokeRevert(ledgerId, principal.name(), reason, batchId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reverted", outcome.reverted());
        result.put("ledgerId", ledgerId);
        result.put("batchId", batchId);
        result.put("operator", principal.name());
        result.put("fromState", outcome.from() == null ? null : outcome.from().dbValue());
        result.put("state", outcome.state() == null ? null : outcome.state().dbValue());
        result.put("note", outcome.note());
        if (!outcome.reverted()) {
            return ApiResponse.error(ApiErrorCode.STATE_CONFLICT, outcome.note(), path, result);
        }
        return ApiResponse.ok(result);
    }

    /**
     * 撤回并把「当前状态不允许撤回」翻成 409。
     *
     * <p>{@link RevertNotAllowedException} 与「边非法」的 {@link IllegalStateTransitionException}
     * 分开处理：前者是<b>业务闸</b>（拒收条目要退回只能走重投队列），后者是<b>状态机</b>。
     * 两者都是 409，但文案要分开——前者要告诉人「该走哪条路」，后者只说明「这条边不存在」。
     */
    private RevertService.RevertOutcome invokeRevert(long ledgerId, String operator, String reason,
                                                 String batchId) throws SQLException {
        try {
            return gate.revert(ledgerId, operator, reason);
        } catch (RevertNotAllowedException e) {
            throw new ApiException(ApiErrorCode.STATE_CONFLICT, e.getMessage(),
                    Map.of("ledgerId", ledgerId, "batchId", batchId, "operator", operator));
        } catch (IllegalStateTransitionException | LedgerStateConflictException
                 | ConcurrentLedgerUpdateException e) {
            throw new ApiException(ApiErrorCode.STATE_CONFLICT, conflictMessage(e),
                    Map.of("ledgerId", ledgerId, "batchId", batchId, "operator", operator));
        }
    }

    // =====================================================================
    // GET /messages
    // =====================================================================

    private ApiResponse messages(ApiRequest request) throws SQLException {
        String rawState = request.rawQuery("state");
        if (rawState.isBlank()) {
            throw ApiException.missingField("state", "只查某条消息而不给状态会让响应体随批次膨胀；"
                    + "合法取值：" + stateOptions());
        }
        MessageState state = parseState(rawState);
        String batchId = request.query("batchId");
        int limit = parseLimit(request.rawQuery("limit"));

        List<LedgerReadView.ExportRow> rows = ledger.listMessages(state, batchId, limit);
        List<Object> items = new ArrayList<>(rows.size());
        for (LedgerReadView.ExportRow row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("ledgerId", row.id());
            item.put("batchId", row.batchId());
            item.put("messageType", row.key().messageType());
            item.put("controlId", row.key().controlId());
            item.put("processingId", row.key().processingId());
            item.put("state", row.state().dbValue());
            item.put("degraded", row.degraded());
            item.put("retryCount", row.retryCount());
            item.put("version", row.version());
            item.put("localMrn", row.localMrn());
            item.put("rejectionCode", row.rejectionCode());
            item.put("createdAt", TIMESTAMP.format(row.createdAt()));
            item.put("updatedAt", TIMESTAMP.format(row.updatedAt()));
            items.add(item);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("state", state.dbValue());
        body.put("batchId", batchId.isEmpty() ? null : batchId);
        body.put("limit", limit);
        body.put("total", items.size());
        body.put("items", items);
        return ApiResponse.ok(body);
    }

    // =====================================================================
    // GET /batches/{batchId}/stats
    // =====================================================================

    private ApiResponse stats(String path) throws SQLException {
        String batchId = batchIdOf(path, "stats");
        BatchStats stats = BatchStats.of(ledger, batchId);
        Map<String, Object> counts = new LinkedHashMap<>();
        stats.counts().forEach((state, n) -> counts.put(state.dbValue(), n));
        Map<String, Object> retry = new LinkedHashMap<>();
        stats.retryStates().forEach((state, n) -> retry.put(state.dbValue(), n));
        Map<String, Object> reasons = new LinkedHashMap<>();
        stats.rejectionReasons().forEach(reasons::put);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("batchId", stats.batchId());
        body.put("total", stats.total());
        body.put("landed", stats.landed());
        body.put("pendingConfirm", stats.pendingConfirm());
        body.put("received", stats.received());
        body.put("rejected", stats.rejected());
        body.put("lowConfidencePending", stats.lowConfidencePending());
        body.put("counts", counts);
        body.put("retryStates", retry);
        body.put("rejectionReasons", reasons);
        body.put("headline", stats.headline());
        return ApiResponse.ok(body);
    }

    // =====================================================================
    // GET /batches/{batchId}/reconcile
    // =====================================================================

    private ApiResponse reconcile(String path) {
        String batchId = batchIdOf(path, "reconcile");
        RemoteAckPage page;
        try {
            page = reconciler.fetchAcks(batchId);
        } catch (cn.hospital.region.archive.redelivery.ReconcileException e) {
            // 对端不可用 ≠ 本端坏了：502/503 而不是 500，院内系统据此只重试、不回滚。
            ApiErrorCode code = e.code() == cn.hospital.region.archive.redelivery.ReconcileErrorCode.RATE_LIMITED
                    || e.code() == cn.hospital.region.archive.redelivery.ReconcileErrorCode.CONNECT_TIMEOUT
                    || e.code() == cn.hospital.region.archive.redelivery.ReconcileErrorCode.READ_TIMEOUT
                    || e.code() == cn.hospital.region.archive.redelivery.ReconcileErrorCode.TRANSPORT_FAILURE
                    ? ApiErrorCode.REMOTE_UNAVAILABLE
                    : ApiErrorCode.REMOTE_REJECTED;
            LOG.log(Level.WARNING, "对账失败：batch={0} code={1} msg={2}",
                    new Object[]{batchId, e.code(), e.getMessage()});
            return ApiResponse.error(code,
                    "取区域平台回执失败：" + e.getMessage() + "（本端台账未受影响，可稍后重试）",
                    path, Map.of("batchId", batchId, "remoteErrorCode", e.code().name()));
        }

        try {
            List<LedgerReadView.ExportRow> rows = ledger.listForExport(batchId, null);
            List<LocalExpectedAck> comparable = new ArrayList<>(rows.size());
            List<Object> uncomparable = new ArrayList<>();
            for (LedgerReadView.ExportRow row : rows) {
                if (ControlIdNormalizer.isUsable(row.key().controlId())) {
                    comparable.add(LocalExpectedAck.fromExportRow(row,
                            ackCodeLookup.apply(row.key())));
                } else {
                    uncomparable.add(renderUncomparable(row));
                }
            }
            AckDiff diff = AckReconciler.diff(comparable, page);
            Map<String, Object> body = renderDiff(diff);
            body.put("uncomparableControlIds", uncomparable);
            body.put("uncomparableCount", uncomparable.size());
            if (!uncomparable.isEmpty()) {
                LOG.log(Level.WARNING, "批次 {0} 有 {1} 条控制号为空，无法参与差集比对（已单列，未混入重投清单）",
                        new Object[]{batchId, uncomparable.size()});
            }
            return ApiResponse.ok(body);
        } catch (SQLException e) {
            LOG.log(Level.SEVERE, "对账读取台账失败 batch=" + batchId, e);
            return ApiResponse.error(ApiErrorCode.INTERNAL_ERROR, null, path,
                    Map.of("batchId", batchId));
        }
    }

    /**
     * 「控制号为空、无法参与差集比对」的一行。
     *
     * <p><b>为什么不把它直接跳过</b>：这一批里<b>必然</b>会出现控制号为空的报文 ——
     * 控制号缺失本身就是拒收原因之一（{@code control-id-missing}），于是「因为没有控制号
     * 而被拒收的报文」必然出现在这一批里。跳过它 = 对账条数比台账条数少一条，
     * 而信息员正是靠两个数字对账来发现问题的；让它把整页对账打成 500 更糟。
     * 所以单列一栏：台账有几条、对上几条、缺失几条、<b>无法比对几条</b>，四个数字加起来才等于总数。
     */
    private static Map<String, Object> renderUncomparable(LedgerReadView.ExportRow row) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("ledgerId", row.id());
        item.put("state", row.state().dbValue());
        item.put("rejectionCode", row.rejectionCode());
        item.put("reason", "控制号为空，无法与对端回执按控制号比对（本端已按拒收处理，"
                + "不会因缺回执而重投）");
        return item;
    }

    /** 差集渲染；字段名与 {@code AckDiff} 一一对应，T39 写 OpenAPI 时按这张表对齐。 */
    private static Map<String, Object> renderDiff(AckDiff diff) {
        List<Object> matched = new ArrayList<>();
        for (AckDiff.MatchedAck m : diff.matched()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("ledgerId", m.local().ledgerId());
            item.put("controlId", m.local().key().controlId());
            item.put("state", m.local().state().dbValue());
            item.put("remoteAckCode", m.remote() == null ? "" : m.remote().msaCode());
            matched.add(item);
        }
        List<Object> missing = new ArrayList<>();
        for (AckDiff.MissingAck m : diff.missing()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("ledgerId", m.local().ledgerId());
            item.put("controlId", m.local().key().controlId());
            item.put("state", m.local().state().dbValue());
            item.put("normalizedControlId", m.normalizedControlId());
            item.put("reason", m.reason());
            missing.add(item);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("batchId", diff.batchId());
        body.put("expectedCount", diff.expectedCount());
        body.put("declaredAckCount", diff.declaredAckCount());
        body.put("remoteAckCount", diff.remoteAckCount());
        body.put("matchedCount", diff.matchedCount());
        body.put("missingCount", diff.missingCount());
        body.put("complete", diff.complete());
        body.put("countMismatch", diff.countMismatch());
        body.put("matched", matched);
        body.put("missing", missing);
        body.put("unexpectedRemoteControlIds", diff.unexpectedRemoteControlIds());
        body.put("ambiguousControlIds", diff.ambiguousControlIds());
        body.put("duplicatedRemoteControlIds", diff.duplicatedRemoteControlIds());
        body.put("note", "本接口只出清单，不入重投队列；入队由重投调度按退避策略执行；"
                + "对不上 + 缺失 + 无法比对 = 台账本批总条数");
        return body;
    }

    // =====================================================================
    // 入参解析
    // =====================================================================

    /** 尾斜杠归一：{@code /healthz/} 与 {@code /healthz} 是同一个接口，不该 404。 */
    private static String normalizePath(String path) {
        if (path == null || path.isEmpty()) {
            return "/";
        }
        String p = path;
        while (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    /**
     * 从 {@code /batches/{batchId}/{suffix}} 里取批次号。
     *
     * <p>批次号做 URL 解码：区域平台给的批次号带斜杠或空格时，调用方会 percent-encode，
     * 不解码就会把一个批次当成两个路径段。
     */
    private static String batchIdOf(String path, String suffix) {
        String prefix = "/batches/";
        String tail = "/" + suffix;
        if (!path.startsWith(prefix) || !path.endsWith(tail)) {
            throw new ApiException(ApiErrorCode.ROUTE_NOT_FOUND, "没有这个接口：" + path, null);
        }
        String encoded = path.substring(prefix.length(), path.length() - tail.length());
        String batchId = urlDecode(encoded).trim();
        if (batchId.isEmpty()) {
            throw ApiException.missingField("batchId", "路径形如 /batches/{batchId}/" + suffix);
        }
        return batchId;
    }

    private static String urlDecode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw ApiException.invalidParameter("batchId", value,
                    "不是合法的 URL 编码（非法百分号转义或字符集不可用）");
        }
    }

    private static Map<String, Object> parseBodyObject(ApiRequest request) {
        String body = request.body();
        if (body.isBlank()) {
            throw ApiException.missingField("body",
                    "请求体为空：确认必须显式带上 ledgerId 与两个主键，"
                            + "「什么都不传等于什么都不改」不是本接口支持的语义");
        }
        Object parsed;
        try {
            parsed = Json.parse(body);
        } catch (Json.JsonException e) {
            throw new ApiException(ApiErrorCode.MALFORMED_JSON,
                    "请求体不是合法 JSON：" + e.getMessage(), null);
        }
        if (!(parsed instanceof Map)) {
            throw new ApiException(ApiErrorCode.MALFORMED_JSON,
                    "请求体必须是 JSON 对象，实际是 " + describeJsonType(parsed), null);
        }
        return Json.asObject(parsed, "body");
    }

    private static String describeJsonType(Object parsed) {
        if (parsed == null) {
            return "null";
        }
        if (parsed instanceof List) {
            return "数组";
        }
        if (parsed instanceof String) {
            return "字符串";
        }
        if (parsed instanceof Boolean) {
            return "布尔值";
        }
        return "数字";
    }

    private static long requireLedgerId(Map<String, Object> body) {
        Object raw = body.get("ledgerId");
        if (raw == null) {
            throw ApiException.missingField("ledgerId", "台账行主键，确认动作的定位键");
        }
        if (raw instanceof Number n) {
            long v = n.longValue();
            if (v != n.doubleValue() || v <= 0) {
                throw ApiException.invalidParameter("ledgerId", String.valueOf(raw),
                        "必须是正整数");
            }
            return v;
        }
        throw ApiException.invalidParameter("ledgerId", String.valueOf(raw), "必须是数字");
    }

    private static String requireText(Map<String, Object> body, String field, String why) {
        Object raw = body.get(field);
        String v = raw == null ? "" : String.valueOf(raw).trim();
        if (v.isEmpty()) {
            throw ApiException.missingField(field, why);
        }
        return v;
    }

    private static String optionalText(Map<String, Object> body, String field, String fallback) {
        Object raw = body.get(field);
        if (raw == null) {
            return fallback;
        }
        String v = String.valueOf(raw).trim();
        return v.isEmpty() ? fallback : v;
    }

    private static MessageState parseState(String raw) {
        try {
            return MessageState.fromDbValue(raw.trim());
        } catch (IllegalArgumentException e) {
            throw ApiException.invalidParameter("state", raw, "合法取值：" + stateOptions());
        }
    }

    private static int parseLimit(String raw) {
        if (raw == null || raw.isBlank()) {
            return 50;
        }
        int v;
        try {
            v = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw ApiException.invalidParameter("limit", raw, "必须是整数");
        }
        if (v <= 0) {
            throw ApiException.invalidParameter("limit", raw, "必须为正整数");
        }
        return Math.min(v, LedgerReadView.MAX_PAGE_SIZE);
    }

    private static String stateOptions() {
        List<String> names = new ArrayList<>();
        for (MessageState s : MessageState.values()) {
            names.add(s.dbValue());
        }
        return String.join(" / ", names);
    }
}