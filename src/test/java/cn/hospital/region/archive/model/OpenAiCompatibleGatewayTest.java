package cn.hospital.region.archive.model;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T22 · 协议兼容性测试 —— 「换个模型端点还能不能跑」的可复核答案。
 *
 * <h2>这一组在验什么</h2>
 * <ul>
 *   <li><b>协议形状</b>：出站请求体带 {@code model}/{@code messages}/{@code temperature}，
 *       带随请求下发的固定 JSON Schema，{@code Authorization} 来自 {@code MODEL_API_KEY}；
 *       超时与重试预算按配置生效（不是写死的常数）。</li>
 *   <li><b>五类失败响应</b>：非 2xx / 读超时 / 不可解析 / 结构不符 / 连不上，
 *       每一类都从<b>真实 HTTP</b> 里分类出来，各自落到对应 {@link ModelErrorCode}。</li>
 *   <li><b>结构校验从严</b>：少字段、类型错、多余字段一律降级，<b>不做尽力解析</b> ——
 *       「尽力解析」会把一次没通过校验的响应读成半份成功，那正是模型输出最危险的去处。</li>
 *   <li><b>审计留痕</b>：五类失败各写一行带错误码与耗时的审计行，且不含原始报文。</li>
 * </ul>
 *
 * <p><b>不用任何 mock 库</b>：协议替身是真实 {@link com.sun.net.httpserver.HttpServer}
 * （真实 TCP、真实 UTF-8 JSON、真实 HTTP 状态码）。要验的是「协议协商得像不像」，
 * 用拦截器的话，被验的就变成了拦截器的行为而不是 HTTP 的行为。
 *
 * <p><b>替身只是测试数据层</b>：产品代码里的 {@code MODEL_BASE_URL} 在院内指向自建端点或云服务，
 * 这里那串回环地址只存在于测试源码，不是产品的兜底路径。
 */
class OpenAiCompatibleGatewayTest {

    private static final String API_KEY = "sk-local-stub-key";
    private static final String MODEL_NAME = "stub-model-local";

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-10-20T06:00:00Z"), ZoneId.of("Asia/Shanghai"));

    /** 两条 Z 段，正好命中替身答案表里的 ZX1.3 / ZX2.3。 */
    private static final List<ZSegment> Z_SEGMENTS = List.of(
            ZSegment.of("ZX1", new LinkedHashMap<>(Map.of(2, "既往病史摘要", 3, "高血压随访;2型糖尿病"))),
            ZSegment.of("ZX2", new LinkedHashMap<>(Map.of(2, "下次随访时间", 3, "20270115"))));

    private OpenAiCompatibleStubServer stub;
    private InMemoryModelCallAudit audit;

    @BeforeEach
    void startStub() throws IOException {
        stub = OpenAiCompatibleStubServer.start(API_KEY);
        audit = new InMemoryModelCallAudit();
    }

    @AfterEach
    void stopStub() {
        if (stub != null) {
            stub.close();
        }
    }

    // ================= 协议兼容：请求形状 =================

    @Test
    @DisplayName("协议形状：请求体带 model/messages/temperature，system 提示带临床边界约束")
    void test_request_body_carries_model_messages_and_temperature() {
        gateway(stub.baseUrl(), 3, 0).alignZFields(Z_SEGMENTS);

        Map<String, Object> request = lastRequest();
        assertEquals(MODEL_NAME, request.get("model"), "model 须来自 MODEL_NAME 配置");
        assertEquals(0, ((Number) request.get("temperature")).intValue(),
                "temperature=0：同一段 Z 文本必须给出可复现的结论，否则命中率对照没有意义");

        List<?> messages = Json.asArray(request.get("messages"), "messages");
        assertEquals(2, messages.size(), "固定 system + user 两条");
        Map<String, Object> system = Json.asObject(messages.get(0), "messages[0]");
        assertEquals("system", system.get("role"));
        assertTrue(((String) system.get("content")).contains("禁止输出诊断"),
                "system 提示须带临床边界约束，否则模型可能在对齐字段时顺手给出诊断结论");

        Map<String, Object> user = Json.asObject(messages.get(1), "messages[1]");
        assertEquals("user", user.get("role"));
        Map<String, Object> payload = Json.asObject(Json.parse((String) user.get("content")), "user 消息");
        assertEquals(2, Json.asArray(payload.get("z_segments"), "z_segments").size(), "只把被问的 Z 段送出去");
        assertTrue(payload.containsKey("local_field_dictionary"),
                "须带上本地字段字典，否则模型能编出院内不存在的字段名");
    }

    @Test
    @DisplayName("协议形状：两个操作各带一套固定 JSON Schema，且 strict=true")
    void test_each_operation_ships_its_own_fixed_json_schema() {
        gateway(stub.baseUrl(), 3, 0).alignZFields(Z_SEGMENTS);
        Map<String, Object> zSchema = namedSchema(stub.lastBody());
        assertEquals(OpenAiCompatibleGateway.OP_Z_FIELD_MAP, zSchema.get("name"));
        assertEquals(Boolean.TRUE, zSchema.get("strict"), "strict=true：端点据此约束解码，响应的形状是我们自己定的");

        Map<String, Object> zProperties = Json.asObject(
                Json.asObject(zSchema.get("schema"), "z schema").get("properties"), "z properties");
        assertTrue(zProperties.containsKey("mappings"), "z_field_map 的 schema 只认 mappings");

        gateway(stub.baseUrl(), 3, 0).identityCandidates(
                "PAT-0001^^^FACILITY_A^MR", "張偉", "19850512", sampleIndex());
        Map<String, Object> cSchema = namedSchema(stub.lastBody());
        assertEquals(OpenAiCompatibleGateway.OP_IDENTITY_CANDIDATES, cSchema.get("name"),
                "两个操作各带各的 schema 名，端点据此选约束");
        Map<String, Object> cProperties = Json.asObject(
                Json.asObject(cSchema.get("schema"), "c schema").get("properties"), "c properties");
        assertTrue(cProperties.containsKey("candidates"), "identity_candidates 的 schema 只认 candidates");
    }

    @Test
    @DisplayName("协议兼容：Authorization 头逐字来自 MODEL_API_KEY，配错密钥归 NON_2XX")
    void test_authorization_header_comes_from_api_key() {
        gateway(stub.baseUrl(), 3, 0).alignZFields(Z_SEGMENTS);
        assertEquals("Bearer " + API_KEY, stub.lastAuthorization());

        // 配了另一把密钥 → 替身按真实端点的做法回 401，网关须据此归 NON_2XX，
        // 而不是照样把请求当成功处理。
        OpenAiCompatibleGateway wrongKey = gateway(stub.baseUrl(), 3, 0, "sk-wrong-key");
        ModelCallException e = assertThrows(ModelCallException.class, () -> wrongKey.alignZFields(Z_SEGMENTS));
        assertEquals(ModelErrorCode.NON_2XX, e.errorCode());
    }

    @Test
    @DisplayName("协议兼容：非 2xx 的响应体不进异常消息（可能含可识别内容）")
    void test_non_2xx_error_message_excludes_response_body() {
        stub.setHttpStatus(500);
        ModelCallException e = assertThrows(ModelCallException.class,
                () -> gateway(stub.baseUrl(), 3, 0).alignZFields(Z_SEGMENTS));
        assertEquals(ModelErrorCode.NON_2XX, e.errorCode());
        assertTrue(e.getMessage().contains("HTTP 500"), "须带上状态码才排得动障");
        assertFalse(e.getMessage().contains("高血压"), "响应体不进异常消息：它可能带端点内部信息或可识别内容");
    }

    @Test
    @DisplayName("协议兼容：超时与重试预算按配置生效，不是写死的常数")
    void test_timeout_and_retry_budget_come_from_config() {
        // 读超时：timeoutSeconds=1，而替身收完请求后睡 3 秒 —— 超时参数确实随请求生效。
        stub.setResponseDelayMillis(3_000L);
        ModelCallException timedOut = assertThrows(ModelCallException.class,
                () -> gateway(stub.baseUrl(), 1, 0).alignZFields(Z_SEGMENTS));
        assertEquals(ModelErrorCode.READ_TIMEOUT, timedOut.errorCode(),
                "连上了但不返回是读超时，与连不上（CONNECT_TIMEOUT）是两回事");
        assertTrue(timedOut.getMessage().contains("1s"), "异常消息须带出实际生效的超时值");

        // 重试预算 maxRetries=0：端点只应收到 1 次请求（用替身数请求数，不靠异常里的数字自证）。
        stub.reset();
        stub.setHttpStatus(503);
        InMemoryModelCallAudit noRetryAudit = new InMemoryModelCallAudit();
        ResilientModelGateway noRetry = new ResilientModelGateway(
                gatewayFor(noRetryAudit, stub.baseUrl(), 3, 0),
                new ModelRetryPolicy(0, ModelRetryPolicy.systemSleeper()));
        ModelOutcome<ZFieldMap> outcome = noRetry.alignZFields(Z_SEGMENTS);
        assertTrue(outcome.degraded(), "非 2xx 须降级");
        assertEquals(1, stub.requestCount(), "maxRetries=0：非 2xx 不重试，端点只应收到 1 次请求");
        assertEquals(1, noRetryAudit.count(), "每次尝试留一行审计");

        // 重试预算 maxRetries=1：非 2xx 仍不重试（重试只花在两类超时上），但两类超时按预算重试。
        // 这里用「连不上的死端口」构造可重试类，端点收不到请求，改看审计行数 = 尝试次数。
        InMemoryModelCallAudit timeoutAudit = new InMemoryModelCallAudit();
        ResilientModelGateway oneRetry = new ResilientModelGateway(
                gatewayFor(timeoutAudit, deadBaseUrl(), 2, 0), new ModelRetryPolicy(1, ModelRetryPolicy.systemSleeper()));
        ModelOutcome<ZFieldMap> down = oneRetry.alignZFields(Z_SEGMENTS);
        assertTrue(down.degraded(), "连不上须降级而不是挂住监听口");
        assertEquals(ModelErrorCode.CONNECT_TIMEOUT, down.degradedResult().orElseThrow().errorCode());
        assertEquals(2, timeoutAudit.count(), "maxRetries=1：可重试类应留下 1 次首发 + 1 次重试两行审计");
        assertEquals(2, down.degradedResult().orElseThrow().attempts(), "尝试次数须为 2");
    }

    // ================= 五类失败响应 =================

    @Test
    @DisplayName("五类失败响应：各自落到对应 ModelErrorCode，且各写一行带错误码的审计")
    void test_five_failure_classes_each_map_to_one_error_code_and_one_audit_row() {
        for (FailureCase fc : FailureCase.all()) {
            audit.clear();
            stub.reset();
            fc.arm(stub);

            InMemoryModelCallAudit rowAudit = new InMemoryModelCallAudit();
            OpenAiCompatibleGateway gateway = fc.unreachable()
                    ? gatewayFor(rowAudit, deadBaseUrl(), 3, 0)
                    : gatewayFor(rowAudit, stub.baseUrl(), fc.timeoutSeconds(), 0);

            ModelCallException e = assertThrows(ModelCallException.class,
                    () -> gateway.alignZFields(Z_SEGMENTS), fc.label() + " 须作为模型调用失败上抛");
            assertEquals(fc.expected(), e.errorCode(), fc.label() + " 的错误分类不符 cause=" + e.getCause());
            assertEquals(fc.retryable(), e.errorCode().retryable(),
                    fc.label() + " 的「要不要重试」标记须与错误分类一致");

            assertEquals(1, rowAudit.count(), fc.label() + " 一次尝试应留一行审计");
            ModelCallRecord row = rowAudit.latest();
            assertEquals(fc.expected(), row.errorCode(), fc.label() + " 的审计行须带错误码");
            assertTrue(row.degraded(), fc.label() + " 的审计行须标降级");
            assertTrue(row.elapsedMillis() >= 0, fc.label() + " 的审计行须带耗时");
            assertEquals(OpenAiCompatibleGateway.OP_Z_FIELD_MAP, row.operation(), fc.label() + " 的审计行须记操作名");
            assertEquals(0, row.totalTokens(), fc.label() + "：失败调用没有 token 用量，记 0 而不是编一个");
            assertEquals(FIXED_CLOCK.instant(), row.at(), fc.label() + "：审计时间取注入时钟，便于对时排障");
            assertNoRawMessage(row, fc.label());
        }
    }

    @Test
    @DisplayName("五类失败响应：全部收敛到降级，不向上抛、不推进业务状态")
    void test_all_five_failure_classes_degrade_through_the_single_path() {
        for (FailureCase fc : FailureCase.all()) {
            stub.reset();
            fc.arm(stub);

            OpenAiCompatibleGateway gateway = fc.unreachable()
                    ? gatewayFor(audit, deadBaseUrl(), 3, 0)
                    : gatewayFor(audit, stub.baseUrl(), fc.timeoutSeconds(), 0);
            ResilientModelGateway resilient = new ResilientModelGateway(gateway,
                    new ModelRetryPolicy(0, ModelRetryPolicy.systemSleeper()));

            ModelOutcome<ZFieldMap> outcome = resilient.alignZFields(Z_SEGMENTS);
            assertTrue(outcome.degraded(), fc.label() + " 须降级");
            assertFalse(outcome instanceof ModelOutcome.Succeeded, fc.label() + " 不得产出值");
            assertThrows(IllegalStateException.class, outcome::value,
                    fc.label() + "：降级结果不给「当空值用」的口子");
            DegradedResult result = outcome.degradedResult().orElseThrow();
            assertEquals(fc.expected(), result.errorCode(), fc.label() + " 的降级原因须带错误码");
            assertEquals(OpenAiCompatibleGateway.OP_Z_FIELD_MAP, result.operation(), fc.label() + " 须带操作名");
            assertTrue(result.ledgerReason().contains("model-degraded"),
                    fc.label() + "：降级原因要能直接写进台账审计行");
            assertFalse(result.detail().isBlank(), fc.label() + "：降级须留下可读的细节，不能只给一个错误码");
        }
    }

    // ================= 结构校验从严 =================

    @Test
    @DisplayName("结构不符一律降级，不做尽力解析：少字段 / 类型错 / 多余字段 / 信封层缺内容 / 非 JSON")
    void test_schema_violation_degrades_without_partial_parse() {
        // 少字段：confidence 缺失
        stub.setContentOverride("{\"mappings\":[{\"z_field\":\"ZX1.3\",\"local_field\":\"pastHistorySummary\"}]}");
        assertSchemaDegrades("少字段（confidence 缺失）");

        // 类型错：confidence 是字符串而不是数字
        stub.setContentOverride("{\"mappings\":[{\"z_field\":\"ZX1.3\",\"local_field\":\"pastHistorySummary\","
                + "\"confidence\":\"较高\",\"rationale\":\"字段名一致\"}]}");
        assertSchemaDegrades("类型错（confidence 为字符串）");

        // 多余字段：schema 声明 additionalProperties=false，多出来的键一律不认
        stub.setContentOverride("{\"mappings\":[{\"z_field\":\"ZX1.3\",\"local_field\":\"pastHistorySummary\","
                + "\"confidence\":0.9,\"rationale\":\"字段名一致\",\"diagnosis\":\"高血压\"}]}");
        assertSchemaDegrades("多余字段（夹带 diagnosis）");

        // 信封层：choices 为空数组（推理引擎没吐内容时的真实形态）
        stub.setContentOverride(null);
        stub.setEnvelopeOverride("{\"id\":\"chatcmpl-x\",\"choices\":[],\"usage\":{}}");
        assertSchemaDegrades("信封层 choices 为空");

        // 信封层：整段不是 JSON（负载均衡挂掉时回的 HTML 错误页）——归 UNPARSABLE 而非 SCHEMA_INVALID
        stub.setEnvelopeOverride("<html><body>502 Bad Gateway</body></html>");
        InMemoryModelCallAudit htmlAudit = new InMemoryModelCallAudit();
        ModelCallException html = assertThrows(ModelCallException.class,
                () -> gatewayFor(htmlAudit, stub.baseUrl(), 3, 0).alignZFields(Z_SEGMENTS));
        assertEquals(ModelErrorCode.UNPARSABLE, html.errorCode(),
                "压根不是 JSON 归 UNPARSABLE：它与「是 JSON 但形状不对」是两类现场问题，处置建议不同");
    }

    private void assertSchemaDegrades(String what) {
        InMemoryModelCallAudit rowAudit = new InMemoryModelCallAudit();
        ModelCallException e = assertThrows(ModelCallException.class,
                () -> gatewayFor(rowAudit, stub.baseUrl(), 3, 0).alignZFields(Z_SEGMENTS),
                what + " 须判为结构不符");
        assertEquals(ModelErrorCode.SCHEMA_INVALID, e.errorCode(), what + " 应归 SCHEMA_INVALID");
        assertEquals(1, rowAudit.count(), what + " 留一行降级审计");
        assertEquals(ModelErrorCode.SCHEMA_INVALID, rowAudit.latest().errorCode());
        assertTrue(rowAudit.latest().degraded(), what + " 不得被记成成功");
    }

    @Test
    @DisplayName("结构校验发生在入库之前：一条坏绑定让整份映射降级，不产出半份结果")
    void test_one_bad_binding_discards_the_whole_mapping() {
        // 第一条合法、第二条缺 confidence：整体降级，而不是「把第一条先收了」。
        stub.setContentOverride("{\"mappings\":["
                + "{\"z_field\":\"ZX1.3\",\"local_field\":\"pastHistorySummary\",\"confidence\":0.82,\"rationale\":\"字段名一致\"},"
                + "{\"z_field\":\"ZX2.3\",\"local_field\":\"nextFollowUpDate\"}]}");

        InMemoryModelCallAudit rowAudit = new InMemoryModelCallAudit();
        ResilientModelGateway resilient = new ResilientModelGateway(
                gatewayFor(rowAudit, stub.baseUrl(), 3, 0),
                new ModelRetryPolicy(0, ModelRetryPolicy.systemSleeper()));

        ModelOutcome<ZFieldMap> outcome = resilient.alignZFields(Z_SEGMENTS);
        assertTrue(outcome.degraded(), "半份结果不是结果：一条不过就整份不采信");
        assertEquals(ModelErrorCode.SCHEMA_INVALID, outcome.degradedResult().orElseThrow().errorCode());
        assertThrows(IllegalStateException.class, outcome::value, "降级不给值，杜绝「尽力解析」那条路");
        assertEquals(1, rowAudit.count(), "只留一行降级审计，不记任何绑定产出");
    }

    // ================= 成功路径回归 =================

    @Test
    @DisplayName("协议兼容：健康响应按固定 schema 落成结构化对象并留一行成功审计")
    void test_healthy_response_round_trips_into_structured_objects() {
        ZFieldMap map = gateway(stub.baseUrl(), 3, 0).alignZFields(Z_SEGMENTS);
        assertEquals(2, map.size(), "两条 Z 段各出一条绑定");
        ZFieldMap.LocalFieldBinding binding = map.bindingOf("ZX1.3").orElseThrow();
        assertEquals("pastHistorySummary", binding.localField());
        assertEquals(0.82, binding.confidence(), 1e-9);
        assertTrue(binding.rationale().contains("既往病史摘要"));

        assertEquals(1, audit.count());
        ModelCallRecord row = audit.latest();
        assertFalse(row.degraded(), "成功调用不得记成降级");
        assertTrue(row.requestId().startsWith("chatcmpl-stub-"), "请求号取自响应信封，实际 " + row.requestId());
        assertEquals(34, row.totalTokens(), "usage 逐字取自响应信封");
        assertTrue(row.elapsedMillis() >= 0);
        assertNoRawMessage(row, "成功调用");
    }

    @Test
    @DisplayName("协议兼容：替身回了 Markdown 围栏，内容仍按 schema 落成结构化对象（不是失败）")
    void test_code_fenced_response_is_still_parsed_as_structured() {
        stub.setFencedContent(true);
        ZFieldMap map = gateway(stub.baseUrl(), 3, 0).alignZFields(Z_SEGMENTS);
        assertEquals(2, map.size(), "围栏只是包装，内容对就是对");
        assertEquals(1, audit.count());
        assertFalse(audit.latest().degraded());
    }

    // ================= 小工具 =================

    private OpenAiCompatibleGateway gateway(String baseUrl, int timeoutSeconds, int maxRetries) {
        return gatewayFor(audit, baseUrl, timeoutSeconds, maxRetries, API_KEY);
    }

    private OpenAiCompatibleGateway gateway(String baseUrl, int timeoutSeconds, int maxRetries, String key) {
        return gatewayFor(audit, baseUrl, timeoutSeconds, maxRetries, key);
    }

    private static OpenAiCompatibleGateway gatewayFor(InMemoryModelCallAudit target,
                                                      String baseUrl, int timeoutSeconds, int maxRetries) {
        return gatewayFor(target, baseUrl, timeoutSeconds, maxRetries, API_KEY);
    }

    private static OpenAiCompatibleGateway gatewayFor(InMemoryModelCallAudit target, String baseUrl,
                                                      int timeoutSeconds, int maxRetries, String key) {
        ModelGatewayConfig config = new ModelGatewayConfig(baseUrl, key, MODEL_NAME, timeoutSeconds, maxRetries);
        return new OpenAiCompatibleGateway(config, target,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(), FIXED_CLOCK);
    }

    private Map<String, Object> lastRequest() {
        return Json.asObject(Json.parse(stub.lastBody()), "出站请求体");
    }

    /** 取出 {@code response_format.json_schema} 这一层。 */
    private static Map<String, Object> namedSchema(String requestBody) {
        Map<String, Object> request = Json.asObject(Json.parse(requestBody), "出站请求体");
        Map<String, Object> responseFormat = Json.asObject(request.get("response_format"), "response_format");
        assertEquals("json_schema", responseFormat.get("type"), "须用 json_schema 而非「请返回 JSON」");
        return Json.asObject(responseFormat.get("json_schema"), "json_schema");
    }

    private static LocalPatientIndex sampleIndex() {
        return new LocalPatientIndex(List.of(
                new LocalPatientIndex.LocalPatient("LOCMRN-0001", "张伟", "19850512", "a1b2c3d4")));
    }

    /** 取一个当前没人监听的端口：真实的「连不上」造得出来，不必靠改 DNS。 */
    private static String deadBaseUrl() {
        try (ServerSocket s = new ServerSocket()) {
            s.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            String url = "http://127.0.0.1:" + s.getLocalPort() + "/v1";
            s.close();
            return url;
        } catch (IOException e) {
            throw new IllegalStateException("取一个没人监听的端口失败", e);
        }
    }

    /** 审计行里不得出现原始报文的字段值（患者姓名、Z 段取值、定位串）。 */
    private static void assertNoRawMessage(ModelCallRecord row, String what) {
        String text = row.inputSummary() + " | " + row.summaryLine();
        for (String secret : List.of("高血压", "2型糖尿病", "20270115")) {
            assertFalse(text.contains(secret), what + "：审计行不得落字段原文或值，实际落的是 " + text);
        }
    }

    /** 五类失败响应：每类一个布置，跑在真实 HTTP 上。 */
    private enum FailureCase {
        /** 连不上：指向一个没人监听的端口。 */
        CONNECT_TIMEOUT(ModelErrorCode.CONNECT_TIMEOUT, "连不上端点", true, Endpoint.DEAD_PORT, 3),
        /** 连上了但迟迟不返回：替身收完请求后睡 3 秒，端点超时配 1 秒。 */
        READ_TIMEOUT(ModelErrorCode.READ_TIMEOUT, "连上了但不返回", true, Endpoint.LIVE_STUB, 1),
        /** 端点明确报错。 */
        NON_2XX(ModelErrorCode.NON_2XX, "端点回非 2xx", false, Endpoint.LIVE_STUB, 3),
        /** 响应压根不是 JSON（负载均衡挂掉时回的 HTML 错误页）。 */
        UNPARSABLE(ModelErrorCode.UNPARSABLE, "响应不是合法 JSON", false, Endpoint.LIVE_STUB, 3),
        /** JSON 合法但不符合固定 Schema。 */
        SCHEMA_INVALID(ModelErrorCode.SCHEMA_INVALID, "JSON 合法但结构不符", false, Endpoint.LIVE_STUB, 3);

        private final ModelErrorCode expected;
        private final String label;
        private final boolean retryable;
        private final Endpoint endpoint;
        private final int timeoutSeconds;

        FailureCase(ModelErrorCode expected, String label, boolean retryable,
                    Endpoint endpoint, int timeoutSeconds) {
            this.expected = expected;
            this.label = label;
            this.retryable = retryable;
            this.endpoint = endpoint;
            this.timeoutSeconds = timeoutSeconds;
        }

        String label() {
            return label;
        }

        ModelErrorCode expected() {
            return expected;
        }

        boolean retryable() {
            return retryable;
        }

        boolean unreachable() {
            return endpoint == Endpoint.DEAD_PORT;
        }

        int timeoutSeconds() {
            return timeoutSeconds;
        }

        void arm(OpenAiCompatibleStubServer s) {
            switch (this) {
                case CONNECT_TIMEOUT -> {
                    // 无需布置：baseUrl 指向一个没人监听的端口。
                }
                case READ_TIMEOUT -> s.setResponseDelayMillis(3_000L);
                case NON_2XX -> s.setHttpStatus(500);
                case UNPARSABLE -> s.setEnvelopeOverride("<html><body>502 Bad Gateway</body></html>");
                case SCHEMA_INVALID -> s.setContentOverride(
                        "{\"mappings\":[{\"z_field\":\"ZX1.3\",\"local_field\":\"pastHistorySummary\"}]}");
                default -> throw new IllegalStateException("未覆盖的失败类：" + this);
            }
        }

        static List<FailureCase> all() {
            return List.of(values());
        }
    }

    /** 端点从哪儿取：死端口与活替身。把这个做成枚举而不是两个布尔，就是防它们再写反。 */
    private enum Endpoint {
        DEAD_PORT,
        LIVE_STUB
    }
}
