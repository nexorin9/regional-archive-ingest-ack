package cn.hospital.region.archive.model;

import cn.hospital.region.archive.config.AppConfig;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * OpenAI-compatible 模型网关 —— 唯一发模型请求的地方。
 *
 * <h2>协议形状</h2>
 * <pre>
 *   POST {MODEL_BASE_URL}/chat/completions
 *   Authorization: Bearer {MODEL_API_KEY}
 *   Content-Type: application/json; charset=UTF-8
 *
 *   {"model":"…","temperature":0,
 *    "messages":[{"role":"system",…},{"role":"user",…}],
 *    "response_format":{"type":"json_schema",
 *                       "json_schema":{"name":"z_field_map","strict":true,"schema":{…}}}}
 * </pre>
 * 响应取 {@code choices[0].message.content}，按<b>固定 JSON Schema</b>校验后映射成 record。
 * 医院自建的本地模型端点、院内私有化部署、云服务都能提供这个形状，
 * 所以换厂商只改 {@code MODEL_*} 变量与路径，业务层一行不动。
 *
 * <h2>固定 schema，不是「请返回 JSON」</h2>
 * <p>把 schema 随请求发过去有两个好处：一是本地模型端点能据此约束解码；
 * 二是<b>响应的形状是我们自己定的</b>，解析失败就一定是「没照 schema 来」，
 * 而不是「我们猜错了格式」。后者那种模糊正是把一次失败读成一次成功的开始。
 *
 * <h2>送出去什么、不送什么</h2>
 * <p>送：Z 段字段名与字段值、平台侧姓名与出生日期、院内候选切片（主键 + 姓名 + 出生日期 + 身份证摘要）。
 * <b>不送</b>：身份证明文（只送不可逆摘要，硬校验走确定性代码）、区域平台患者主键 {@code PID-3}
 * （比对用不到，而它是跨院标识，出去了就是一次无谓的身份外泄）。
 * 这一点由 {@code ModelGatewayContractTest} 对真实出站报文做断言，不是靠注释自觉。
 *
 * <h2>失败与降级</h2>
 * <p>本类只负责<b>把失败分类</b>：非 2xx / 传输失败 / 响应不可解析 / 结构不符各自落到
 * {@link ModelErrorCode} 的五类之一，然后抛 {@link ModelCallException}。
 * 「要不要重试」「降级成什么」由 {@link ResilientModelGateway} 这一条单一路径决定，
 * 本类不掺和——两处各写一半的必然结果是某个调用点走了另写的那一半。
 * 无论成功失败，本类都<b>不推进任何业务状态</b>：落地只走确认门（T30）。
 */
public final class OpenAiCompatibleGateway implements ModelGateway {

    private static final Logger LOG = Logger.getLogger(OpenAiCompatibleGateway.class.getName());

    /** 操作名一：Z 段语义对齐。 */
    public static final String OP_Z_FIELD_MAP = "z_field_map";

    /** 操作名二：身份候选。 */
    public static final String OP_IDENTITY_CANDIDATES = "identity_candidates";

    /**
     * 最多返回几条候选。
     *
     * <p>确认台一次摆不下二十行，全给反而让人无从下手；而且候选越多，
     * 「本批次 500 条要人工复核多少」这个工作量口径就越失真（T28 靠它出分桶统计）。
     */
    public static final int MAX_CANDIDATES = 5;

    /** 与 {@link #zFieldMapSchema()} 的 {@code additionalProperties=false} 同步。 */
    private static final Set<String> Z_MAPPING_KEYS =
            Set.of("z_field", "local_field", "confidence", "rationale");

    /** 与 {@link #identityCandidatesSchema()} 的 {@code additionalProperties=false} 同步。 */
    private static final Set<String> CANDIDATE_KEYS = Set.of("local_mrn", "confidence", "evidence");

    /** 依据条目的允许字段。 */
    private static final Set<String> EVIDENCE_KEYS = Set.of("field", "detail");

    /**
     * 站点初始字段字典：院内本地字段名 → 中文说明。
     *
     * <p>这是<b>给模型的取值范围</b>，不是业务字典的唯一真源（真实字典随院内系统版本走）。
     * 放在这里是为了让提示词自带约束：模型只能从这里取值，编不出 {@code allergyHistory}
     * 这种听着像、其实不存在的字段。
     */
    private static final Map<String, String> LOCAL_FIELD_DICTIONARY = buildDictionary();

    private static final String SYSTEM_PROMPT = """
            你是区域健康档案平台的字段对齐助手，只服务两类任务：
            一、把平台下发的 Z 段自由文本字段，对齐到院内本地字段名；
            二、在给定的院内候选里，判断哪一位居民最可能是同一个人。
            硬性约束：
            1. 禁止输出诊断、治疗建议或任何临床结论；只描述数据流与字段对应关系。
            2. 禁止编造输入里没有出现的字段名、主键或证件号；本地字段名只能取自给定字典。
            3. 判断不了就把 confidence 压低并在 rationale 里写清缺什么证据；不要给高分凑数。
            4. 严格按给定 JSON Schema 输出 JSON 对象本身，不要输出解释文字或 Markdown 代码块。
            """;

    private final ModelGatewayConfig config;
    private final ModelCallAudit audit;
    private final HttpClient http;
    private final Clock clock;

    /** 用启动配置装配（生产路径）。 */
    public OpenAiCompatibleGateway(AppConfig appConfig, ModelCallAudit audit) {
        this(ModelGatewayConfig.from(appConfig), audit);
    }

    public OpenAiCompatibleGateway(ModelGatewayConfig config, ModelCallAudit audit) {
        this(config, audit, defaultHttpClient(config), Clock.systemUTC());
    }

    /** 显式注入 HTTP 客户端与时钟（契约测试要控制这两样）。 */
    public OpenAiCompatibleGateway(ModelGatewayConfig config, ModelCallAudit audit,
                                   HttpClient httpClient, Clock clock) {
        this.config = Objects.requireNonNull(config, "模型配置不得为 null");
        this.audit = Objects.requireNonNull(audit, "调用审计出口不得为 null（缺了它，模型调了也没人查得到）");
        this.http = Objects.requireNonNull(httpClient, "HttpClient 不得为 null");
        this.clock = Objects.requireNonNull(clock, "Clock 不得为 null");
    }

    private static HttpClient defaultHttpClient(ModelGatewayConfig config) {
        // 连接超时取「读超时与 5 秒中的较小者」：模型端点不可达时要在秒级失败，
        // 不能让一个坏配置把整批报文卡满 timeoutSeconds。
        int connectSeconds = Math.min(5, config.timeoutSeconds());
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(connectSeconds))
                .build();
    }

    // ================= 对外两个方法 =================

    @Override
    public ZFieldMap alignZFields(List<ZSegment> zSegments) {
        Objects.requireNonNull(zSegments, "Z 段集合不得为 null");
        if (zSegments.isEmpty()) {
            return ZFieldMap.empty();
        }

        Map<String, Object> payload = Json.object();
        payload.put("local_field_dictionary", dictionaryRows());

        List<Object> segments = Json.array();
        Map<String, String> summaryValues = new LinkedHashMap<>();
        for (ZSegment z : zSegments) {
            Map<String, Object> one = Json.object();
            one.put("z_field", z.locate(3));
            // 单字段按码点截到上限再送：一条超长字段会让端点直接 4xx，或者把本批次
            // 的 token 预算吃光，连带后面几十条正常报文一起降级。截断由
            // ModelInputPolicy 统一口径，本机侧（InboundIdentity）用的是同一个常量。
            one.put("platform_label", ModelInputPolicy.clampForModel(z.label(), "ZX-2 平台字段名"));
            one.put("platform_value", ModelInputPolicy.clampForModel(z.value(), "ZX-3 字段值"));
            segments.add(one);
            summaryValues.put(z.locate(2), z.label());
            summaryValues.put(z.locate(3), z.value());
        }
        payload.put("z_segments", segments);

        return call(OP_Z_FIELD_MAP, zFieldMapSchema(), Json.write(payload),
                PromptSummary.of(OP_Z_FIELD_MAP, summaryValues), OpenAiCompatibleGateway::toZFieldMap);
    }

    @Override
    public Candidates identityCandidates(String pid3, String pid5, String pid7, LocalPatientIndex localIndex) {
        Objects.requireNonNull(localIndex, "院内候选切片不得为 null");
        if (localIndex.isEmpty()) {
            return Candidates.empty();
        }

        Map<String, Object> payload = Json.object();
        // 平台侧与院内侧都过同一道长度闸：院内主索引里的姓名同样可能是脏数据，
        // 两边口径不一致的话模型会在「平台说 500 字、院内说 2 字」这种对比上给出荒唐结论。
        payload.put("incoming_name", ModelInputPolicy.clampForModel(pid5, "PID-5 姓名"));
        payload.put("incoming_birth_date", ModelInputPolicy.clampForModel(pid7, "PID-7 出生日期"));

        List<Object> candidates = Json.array();
        for (LocalPatientIndex.LocalPatient p : localIndex.patients()) {
            Map<String, Object> one = Json.object();
            one.put("local_mrn", p.localMrn());
            one.put("name", ModelInputPolicy.clampForModel(p.name(), "院内候选姓名"));
            one.put("birth_date", ModelInputPolicy.clampForModel(p.birthDate(), "院内候选出生日期"));
            one.put("id_card_digest8", p.idCardDigest8());
            candidates.add(one);
        }
        payload.put("candidates", candidates);

        Map<String, String> summaryValues = new LinkedHashMap<>();
        summaryValues.put("pid5", pid5);
        summaryValues.put("pid7", pid7);
        // PID-3 刻意不送出比对人：审计里记成 (missing)，是为了让「没送」这件事本身可查，
        // 而不是靠读代码的人记得有这么一条规矩。
        summaryValues.put("pid3", null);

        Candidates parsed = call(OP_IDENTITY_CANDIDATES, identityCandidatesSchema(),
                Json.write(payload), PromptSummary.of(OP_IDENTITY_CANDIDATES, summaryValues),
                OpenAiCompatibleGateway::toCandidates);
        // 候选主键必须来自给定切片：模型偶尔会「顺手」编一个不存在的院内主键，
        // 那种条目进了确认台就等于把一个人凭空造出来，直接剔掉并留告警。
        List<IdentityCandidate> kept = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        for (IdentityCandidate candidate : parsed.candidates()) {
            if (localIndex.byMrn(candidate.localMrn()) == null) {
                dropped.add(candidate.localMrn());
            } else {
                kept.add(candidate);
            }
        }
        if (!dropped.isEmpty()) {
            LOG.warning("模型返回了候选切片之外的院内主键，已剔除并继续: " + dropped);
        }
        return new Candidates(kept).top(MAX_CANDIDATES);
    }

    // ================= 一次调用 =================

    /**
     * 发一次请求，把 {@code choices[0].message.content} 校验后转成结构化对象。
     *
     * <p><b>本方法只负责「把失败分类」</b>：五类错误码各自对应抛点，
     * 之后怎么办（要不要重试、降级成什么）一律不在这层决定 —— 那是
     * {@link ModelRetryPolicy} 与 {@link ResilientModelGateway} 的职责。
     *
     * <p>审计行<b>成功失败各写一行</b>（失败行 {@code degraded=true} 且带错误码）：
     * 「今天模型挂了没有、挂的哪一类」是每天要回答的问题，
     * 只有成功调用留痕的话，挂掉的那些天里一个字都答不出来。
     *
     * <p><b>结构校验（{@code converter}）必须发生在写成功审计行之前</b>：
     * 响应「是合法 JSON 但不符合固定 Schema」照样是一次失败的调用，
     * 若先记成功再校验，审计里就会留下一行「本次调用正常」——
     * 恰好在模型正产出垃圾数据的那几天把账做平，而这正是要留痕的那几天。
     */
    private <T> T call(String operation, Map<String, Object> schema, String userContent,
                       PromptSummary summary, Function<Map<String, Object>, T> converter) {
        long startedNanos = System.nanoTime();
        try {
            return callOnce(operation, schema, userContent, summary, startedNanos, converter);
        } catch (ModelCallException e) {
            writeAudit(operation, summary, startedNanos, 0, 0, ModelCallRecord.NO_REQUEST_ID, e.errorCode());
            throw e;
        }
    }

    /** 一次尝试的主体；校验过了才写成功审计行，失败时异常上抛由 {@link #call} 统一记。 */
    private <T> T callOnce(String operation, Map<String, Object> schema,
                           String userContent, PromptSummary summary,
                           long startedNanos, Function<Map<String, Object>, T> converter) {
        String body = Json.write(requestBody(operation, schema, userContent));

        HttpRequest request = HttpRequest.newBuilder(config.chatCompletionsUri())
                .timeout(Duration.ofSeconds(config.timeoutSeconds()))
                .header("Content-Type", "application/json; charset=UTF-8")
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + config.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (java.net.http.HttpConnectTimeoutException e) {
            throw new ModelCallException(ModelErrorCode.CONNECT_TIMEOUT,
                    "连接语义对齐服务超时：" + config.chatCompletionsUri().getHost(), e);
        } catch (java.net.http.HttpTimeoutException e) {
            throw new ModelCallException(ModelErrorCode.READ_TIMEOUT,
                    "读取语义对齐响应超时（" + config.timeoutSeconds() + "s）", e);
        } catch (IOException e) {
            // 连接被拒、主机不可达、DNS 失败……在「能不能连上」这一层是同一件事：
            // 都归 CONNECT_TIMEOUT，都值得按预算重试。
            throw new ModelCallException(ModelErrorCode.CONNECT_TIMEOUT,
                    "连不上语义对齐服务：" + e.getClass().getSimpleName()
                            + "，目标 " + config.chatCompletionsUri().getHost(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelCallException(ModelErrorCode.READ_TIMEOUT, "模型调用被中断", e);
        }
        long elapsedMillis = Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);

        if (response.statusCode() / 100 != 2) {
            // 响应体不记入异常消息：非 2xx 的响应体常带端点内部信息，甚至带可识别内容。
            throw new ModelCallException(ModelErrorCode.NON_2XX,
                    "模型调用失败：HTTP " + response.statusCode()
                            + "（响应体不记入日志，可能含可识别内容）",
                    response.statusCode(), null);
        }

        Map<String, Object> envelope = asObject(parseDocument(response.body(), operation + " 响应体"),
                operation + " 响应体");
        String requestId;
        try {
            requestId = Json.asStringOrDefault(envelope.get("id"), operation + ".id",
                    ModelCallRecord.NO_REQUEST_ID);
        } catch (Json.JsonException e) {
            throw new ModelCallException(ModelErrorCode.SCHEMA_INVALID,
                    "模型响应结构校验失败（" + operation + ".id）：" + e.getMessage(), e);
        }
        int promptTokens = 0;
        int completionTokens = 0;
        Object usage = envelope.get("usage");
        if (usage != null) {
            Map<String, Object> u = parseJsonObject(usage, operation + ".usage");
            promptTokens = Json.asIntOrDefault(u.get("prompt_tokens"), operation + ".usage.prompt_tokens", 0);
            completionTokens = Json.asIntOrDefault(u.get("completion_tokens"), operation + ".usage.completion_tokens", 0);
        }

        String content = extractContent(operation, envelope);
        Map<String, Object> parsed = asObject(parseDocument(content, operation + " 响应内容"),
                operation + " 响应内容");
        T value = converter.apply(parsed);

        writeAudit(operation, summary, startedNanos, promptTokens, completionTokens, requestId, null);
        LOG.log(Level.FINE, () -> "模型调用完成: " + operation + " 耗时=" + elapsedMillis + "ms");
        return value;
    }

    /**
     * 写一行调用审计。<b>审计写不进去不改变调用结果</b> ——
     * 那是运维问题（表满、连接断），把它变成「报文进不来」才是本末倒置。
     *
     * @param errorCode {@code null} 表示本次调用成功；非空即降级行
     */
    private void writeAudit(String operation, PromptSummary summary, long startedNanos,
                            int promptTokens, int completionTokens, String requestId,
                            ModelErrorCode errorCode) {
        long elapsedMillis = Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
        try {
            audit.record(new ModelCallRecord(clock.instant(), config.model(), operation, elapsedMillis,
                    promptTokens, completionTokens, requestId, errorCode != null, summary.line(), errorCode));
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "模型调用审计写入失败，本次调用结果照常返回: " + e.getMessage());
        }
    }

    private Map<String, Object> requestBody(String operation, Map<String, Object> schema, String userContent) {
        Map<String, Object> body = Json.object();
        body.put("model", config.model());
        // temperature=0：同一段 Z 文本要给出可复现的对齐结论，否则 T26 的命中率对照就没有意义。
        body.put("temperature", 0);
        body.put("messages", List.of(
                obj("role", "system", "content", SYSTEM_PROMPT),
                obj("role", "user", "content", userContent)));
        body.put("response_format", obj("type", "json_schema",
                "json_schema", obj("name", operation, "strict", Boolean.TRUE, "schema", schema)));
        return body;
    }

    private static String extractContent(String operation, Map<String, Object> envelope) {
        List<Object> choices = parseJsonArray(envelope.get("choices"), operation + ".choices");
        if (choices.isEmpty()) {
            throw new ModelCallException(ModelErrorCode.SCHEMA_INVALID,
                    "模型响应里 choices 为空，无内容可解析");
        }
        Map<String, Object> first = parseJsonObject(choices.get(0), operation + ".choices[0]");
        Map<String, Object> message = parseJsonObject(first.get("message"), operation + ".choices[0].message");
        String content;
        try {
            content = Json.asStringOrDefault(message.get("content"),
                    operation + ".choices[0].message.content", "");
        } catch (Json.JsonException e) {
            throw new ModelCallException(ModelErrorCode.SCHEMA_INVALID,
                    "模型响应结构校验失败（" + operation + ".choices[0].message.content）：" + e.getMessage(), e);
        }
        if (content.isBlank()) {
            throw new ModelCallException(ModelErrorCode.SCHEMA_INVALID,
                    "模型响应内容为空（" + operation + ".choices[0].message.content）");
        }
        return stripCodeFence(content);
    }

    /**
     * 去掉 Markdown 代码块围栏。
     *
     * <p>即便请求里带了 {@code response_format}，本地模型端点也常把 JSON 包在
     * {@code ```json … ```} 里发回来。这类响应<b>不是失败</b>——内容是对的，
     * 只是多了两行围栏；把它当结构失败丢进降级路径，等于每天白白吃掉一批本该能对齐的报文。
     * 反过来，剥围栏只认「开头是 ``` 且后面有换行」，半截围栏或正文里的 ``` 不动，
     * 免得把真实内容削掉一块。
     */
    static String stripCodeFence(String content) {
        String text = content.trim();
        if (!text.startsWith("```")) {
            return text;
        }
        int firstNewline = text.indexOf('\n');
        if (firstNewline < 0) {
            return text;
        }
        String body = text.substring(firstNewline + 1);
        int lastFence = body.lastIndexOf("```");
        if (lastFence >= 0) {
            body = body.substring(0, lastFence);
        }
        return body.trim();
    }

    // ================= 响应 → 结构化对象 =================

    private static ZFieldMap toZFieldMap(Map<String, Object> content) {
        try {
            return toZFieldMapChecked(content);
        } catch (Json.JsonException e) {
            throw new ModelCallException(ModelErrorCode.SCHEMA_INVALID,
                    "模型响应结构校验失败（z_field_map）：" + e.getMessage(), e);
        }
    }

    private static ZFieldMap toZFieldMapChecked(Map<String, Object> content) {
        rejectUnknownKeys(content, "z_field_map", Set.of("mappings"));
        List<Object> mappings = parseJsonArray(content.get("mappings"), "z_field_map.mappings");
        Map<String, ZFieldMap.LocalFieldBinding> bindings = new LinkedHashMap<>();
        for (int i = 0; i < mappings.size(); i++) {
            String where = "z_field_map.mappings[" + i + "]";
            Map<String, Object> m = parseJsonObject(mappings.get(i), where);
            rejectUnknownKeys(m, where, Z_MAPPING_KEYS);
            String zField = Json.asStringOrDefault(m.get("z_field"), where + ".z_field", "").trim();
            if (zField.isEmpty()) {
                throw new ModelCallException(ModelErrorCode.SCHEMA_INVALID,
                        "响应结构校验失败：" + where + ".z_field 为空");
            }
            String localField = Json.asStringOrDefault(m.get("local_field"), where + ".local_field", "").trim();
            if (localField.isEmpty()) {
                // 模型自己说「这个字段对不上」：不是错误，但也不能悄悄吞掉。
                LOG.fine("模型未给出 " + zField + " 的本地字段，已跳过该绑定");
                continue;
            }
            double confidence = Json.asNumber(m.get("confidence"), where + ".confidence");
            String rationale = Json.asStringOrDefault(m.get("rationale"), where + ".rationale", "");
            try {
                ZFieldMap.LocalFieldBinding binding =
                        new ZFieldMap.LocalFieldBinding(localField, confidence, rationale);
                ZFieldMap.LocalFieldBinding previous = bindings.put(zField, binding);
                // 同一个 Z 段被映射到两个不同的本地字段：这不是「取哪一条」的问题，
                // 而是模型自己在两个答案之间摇摆，业务层没有依据判定该听谁的。
                // 固定 JSON Schema 管得住形状、管不住取值，z_field 只是普通字符串，
                // 重复与冲突都要在这里显式拒掉——任选其一就是拿一条档案字段赌一次。
                if (previous != null && !previous.localField().equals(binding.localField())) {
                    throw new ModelCallException(ModelErrorCode.SCHEMA_INVALID,
                            "响应结构校验失败（" + where + "）：同一个 Z 段 " + zField
                                    + " 被映射到两个本地字段（" + previous.localField()
                                    + " 与 " + binding.localField() + "），无法判定该听哪一个，整条降级");
                }
            } catch (IllegalArgumentException e) {
                throw new ModelCallException(ModelErrorCode.SCHEMA_INVALID,
                        "响应结构校验失败（" + where + "）：" + e.getMessage(), e);
            }
        }
        return new ZFieldMap(bindings);
    }

    private static Candidates toCandidates(Map<String, Object> content) {
        try {
            return toCandidatesChecked(content);
        } catch (Json.JsonException e) {
            throw new ModelCallException(ModelErrorCode.SCHEMA_INVALID,
                    "模型响应结构校验失败（identity_candidates）：" + e.getMessage(), e);
        }
    }

    private static Candidates toCandidatesChecked(Map<String, Object> content) {
        rejectUnknownKeys(content, "identity_candidates", Set.of("candidates"));
        List<Object> items = parseJsonArray(content.get("candidates"), "identity_candidates.candidates");
        // 同一主键出现两次时保留置信度更高的一条：模型偶尔会把两个候选合成一条重复列出。
        Map<String, IdentityCandidate> byMrn = new LinkedHashMap<>();
        for (int i = 0; i < items.size(); i++) {
            String where = "identity_candidates.candidates[" + i + "]";
            Map<String, Object> m = parseJsonObject(items.get(i), where);
            rejectUnknownKeys(m, where, CANDIDATE_KEYS);
            String localMrn = Json.asStringOrDefault(m.get("local_mrn"), where + ".local_mrn", "").trim();
            if (localMrn.isEmpty()) {
                throw new ModelCallException(ModelErrorCode.SCHEMA_INVALID,
                        "响应结构校验失败：" + where + ".local_mrn 为空");
            }
            double confidence = Json.asNumber(m.get("confidence"), where + ".confidence");
            List<IdentityCandidate.Evidence> evidence = new ArrayList<>();
            for (Object e : parseJsonArray(m.get("evidence"), where + ".evidence")) {
                String evidenceWhere = where + ".evidence[]";
                Map<String, Object> em = parseJsonObject(e, evidenceWhere);
                rejectUnknownKeys(em, evidenceWhere, EVIDENCE_KEYS);
                evidence.add(new IdentityCandidate.Evidence(
                        Json.asStringOrDefault(em.get("field"), evidenceWhere + ".field", "(未标注字段)"),
                        Json.asStringOrDefault(em.get("detail"), evidenceWhere + ".detail", "")));
            }
            IdentityCandidate candidate;
            try {
                candidate = new IdentityCandidate(localMrn, confidence, evidence);
            } catch (IllegalArgumentException ex) {
                throw new ModelCallException(ModelErrorCode.SCHEMA_INVALID,
                        "响应结构校验失败（" + where + "）：" + ex.getMessage(), ex);
            }
            byMrn.merge(localMrn, candidate,
                    (a, b) -> a.confidence() >= b.confidence() ? a : b);
        }
        return new Candidates(new ArrayList<>(byMrn.values()));
    }

    /**
     * 拒绝固定 Schema 之外的键 —— {@code additionalProperties=false} 得真的生效。
     *
     * <p>只声明不执行，等于把「模型多说了什么」完全放过。而多出来的键恰恰是模型
     * <b>顺手夹带</b>的地方：字段对齐时夹一条 {@code diagnosis}，候选里夹一句 {@code suggestion}。
     * 本系统只要一个字段名对上就算过，多出来的诊断与建议会一路混进确认台，
     * 而确认台离「人点一下就写进院内系统」只隔一步。
     *
     * <p>宁可整份降级（人来看一眼），也不要把带夹带的东西当合格结果收下。
     *
     * <p>只管<b>内容对象</b>，不管响应信封：信封里的 {@code object} / {@code created} /
     * {@code system_fingerprint} 是各端点都有的正常字段，照单拒绝等于和所有真实端点为敌。
     */
    private static void rejectUnknownKeys(Map<String, Object> value, String where, Set<String> allowed) {
        List<String> unknown = value.keySet().stream()
                .filter(k -> !allowed.contains(k))
                .sorted()
                .toList();
        if (!unknown.isEmpty()) {
            throw new ModelCallException(ModelErrorCode.SCHEMA_INVALID,
                    "响应结构校验失败（" + where + "）：出现固定 Schema 之外的字段 "
                            + unknown + "，允许的字段为 " + allowed.stream().sorted().toList());
        }
    }

    // ================= 固定 schema =================

    private static Map<String, Object> zFieldMapSchema() {
        Map<String, Object> binding = obj(
                "type", "object",
                "additionalProperties", false,
                "required", List.of("z_field", "local_field", "confidence", "rationale"),
                "properties", obj(
                        "z_field", obj("type", "string", "description", "Z 段内定位串，形如 ZX1.3"),
                        "local_field", obj("type", "string",
                                "description", "院内本地字段名，只能取自 local_field_dictionary；对不上就留空字符串"),
                        "confidence", obj("type", "number", "minimum", 0, "maximum", 1,
                                "description", "0 到 1 的置信度；判断不了就给低分"),
                        "rationale", obj("type", "string",
                                "description", "一句中文依据，说明平台字段名与取值为何对应这个本地字段")));
        return obj(
                "type", "object",
                "additionalProperties", false,
                "required", List.of("mappings"),
                "properties", obj("mappings", obj("type", "array", "items", binding)));
    }

    private static Map<String, Object> identityCandidatesSchema() {
        Map<String, Object> evidence = obj(
                "type", "object",
                "additionalProperties", false,
                "required", List.of("field", "detail"),
                "properties", obj(
                        "field", obj("type", "string", "description", "依据字段，如 name / birth_date / id_card_digest8"),
                        "detail", obj("type", "string", "description", "一句中文说明：该字段支持还是反对这条候选")));
        Map<String, Object> candidate = obj(
                "type", "object",
                "additionalProperties", false,
                "required", List.of("local_mrn", "confidence", "evidence"),
                "properties", obj(
                        "local_mrn", obj("type", "string", "description", "院内主键，只能取自给定候选"),
                        "confidence", obj("type", "number", "minimum", 0, "maximum", 1,
                                "description", "0 到 1；简繁/同音/空格变体按相似程度给分"),
                        "evidence", obj("type", "array", "items", evidence)));
        return obj(
                "type", "object",
                "additionalProperties", false,
                "required", List.of("candidates"),
                "properties", obj("candidates", obj("type", "array", "items", candidate)));
    }

    private static List<Object> dictionaryRows() {
        List<Object> rows = Json.array();
        for (Map.Entry<String, String> e : LOCAL_FIELD_DICTIONARY.entrySet()) {
            Map<String, Object> row = Json.object();
            row.put("local_field", e.getKey());
            row.put("说明", e.getValue());
            rows.add(row);
        }
        return rows;
    }

    private static Map<String, String> buildDictionary() {
        // 字典本体在 LocalFieldDictionary：提示词里的字典与 T23 对齐后的复读校验
        // 必须共用同一份，各写一份的话迟早漂移，而漂移的表现是
        // 「模型对齐得好好的，这条却被校验拒了」，排查起来两头都不认账。
        return LocalFieldDictionary.standard();
    }

    // ================= 小工具 =================

    /** 键值对构造器：schema 那几行用不起眼但读得懂的写法写。 */
    private static Map<String, Object> obj(Object... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("键值必须成对出现，实际参数个数 " + keyValues.length);
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    /**
     * 解析成「任意 JSON 值」；<b>语法不合法</b>才算 {@link ModelErrorCode#UNPARSABLE}。
     *
     * <p>拆成两步（先 parse 再 asObject）是为了让两类失败可区分：
     * 「压根不是 JSON」（被网关截断、被换成 HTML 错误页）重试无望；
     * 「是 JSON 但形状不对」也重试无望，但它是<b>另一类现场问题</b>——
     * 前者要去查端点配错没，后者要去查模型有没有按 Schema 输出。两者的处置建议不同。
     */
    private static Object parseDocument(String text, String where) {
        try {
            return Json.parse(text);
        } catch (Json.JsonException e) {
            throw new ModelCallException(ModelErrorCode.UNPARSABLE,
                    "模型响应不可解析（" + where + "）：" + e.getMessage(), e);
        }
    }

    /** JSON 合法但不是对象：归 {@link ModelErrorCode#SCHEMA_INVALID}。 */
    private static Map<String, Object> asObject(Object value, String where) {
        try {
            return Json.asObject(value, where);
        } catch (Json.JsonException e) {
            throw new ModelCallException(ModelErrorCode.SCHEMA_INVALID,
                    "模型响应结构校验失败（" + where + "）：" + e.getMessage(), e);
        }
    }

    private static Map<String, Object> parseJsonObject(Object value, String where) {
        return asObject(value, where);
    }

    private static List<Object> parseJsonArray(Object value, String where) {
        try {
            return Json.asArray(value, where);
        } catch (Json.JsonException e) {
            throw new ModelCallException(ModelErrorCode.SCHEMA_INVALID,
                    "模型响应结构校验失败（" + where + "）：" + e.getMessage(), e);
        }
    }

    /** 当前调用配置的单行摘要（不含密钥）。 */
    public String describe() {
        return config.summaryLine();
    }
}