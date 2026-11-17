package cn.hospital.region.archive.model;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T20 模型网关契约用例 —— 对着<b>真实 HTTP 的 OpenAI-compatible 替身</b>跑一次成功调用。
 *
 * <p><b>不 mock</b>：起的是真的 {@code com.sun.net.httpserver.HttpServer}，
 * 走真的 TCP 与 UTF-8 JSON。要验的是「报文形状对不对、结构能不能落成对象」，
 * 用拦截器的话，被验的就成了拦截器的行为。
 *
 * <p>三条本任务钦定的断言口径：
 * <ol>
 *   <li>一次成功调用返回<b>结构化对象</b>（不是自由文本）且审计行已落；</li>
 *   <li>出站报文走<b>固定 JSON Schema</b>、带 Bearer 密钥、只发该发的字段；</li>
 *   <li>业务层<b>没有厂商 SDK 依赖</b>（由 {@link NoVendorSdkImportTest} 静态守住）。</li>
 * </ol>
 */
@DisplayName("T20 模型网关契约：真实 HTTP 替身 + 固定 schema + 调用审计")
class ModelGatewayContractTest {

    private static final String API_KEY = "stub-key-20261020";
    private static final String MODEL = "stub-model-local";

    /** 平台侧姓名用繁体「張偉」，院内是简体「张伟」——国内区域平台的常见形态。 */
    private static final String PLATFORM_NAME = "張^偉";
    private static final String PLATFORM_PID3 = "TESTPAT-0001^^^REGIONAL_ARCHIVE^PatientId";
    private static final String BIRTH_DATE = "19850512";
    private static final String ID_CARD = "000000198505120371";

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-10-20T03:20:00Z"), ZoneOffset.UTC);

    private OpenAiCompatibleStubServer stub;
    private InMemoryModelCallAudit audit;
    private OpenAiCompatibleGateway gateway;

    @BeforeEach
    void setUp() throws IOException {
        stub = OpenAiCompatibleStubServer.start(API_KEY);
        audit = new InMemoryModelCallAudit();
        gateway = new OpenAiCompatibleGateway(config(), audit, HttpClient.newHttpClient(), FIXED_CLOCK);
    }

    @AfterEach
    void tearDown() {
        if (stub != null) {
            stub.close();
        }
    }

    /** 配置面全部来自环境变量：这里给的就是 {@code MODEL_*} 五个变量的等价物。 */
    private ModelGatewayConfig config() {
        return new ModelGatewayConfig(stub.baseUrl(), API_KEY, MODEL, 15, 2);
    }

    // ---------- 用例 ----------

    @Test
    @DisplayName("一次成功调用：返回结构化对象，且审计行已落")
    void test_successful_call_returns_structured_object_and_writes_audit() {
        ZFieldMap aligned = gateway.alignZFields(List.of(
                zx1(), zx2(), zx3()));

        assertEquals(2, aligned.size(), "三条 Z 段里两条对齐成功，一条判断不了被跳过");
        ZFieldMap.LocalFieldBinding first = aligned.bindingOf("ZX1.3").orElseThrow();
        assertEquals("pastHistorySummary", first.localField());
        assertEquals(0.82, first.confidence(), 1e-9);
        assertTrue(first.rationale().contains("既往病史摘要"), "依据必须是中文说明，不是空串");
        assertEquals("nextFollowUpDate", aligned.bindingOf("ZX2.3").orElseThrow().localField());
        assertTrue(aligned.bindingOf("ZX3.3").isEmpty(),
                "模型自己说判断不了（local_field 留空）的那条不进绑定集合");

        assertEquals(1, audit.count(), "一次调用恰好一行审计");
        ModelCallRecord record = audit.latest();
        assertEquals("z_field_map", record.operation());
        assertEquals(MODEL, record.model());
        assertTrue(record.elapsedMillis() >= 0, "耗时不得为负");
        assertEquals(21, record.promptTokens(), "usage.prompt_tokens 原样落审计");
        assertEquals(13, record.completionTokens(), "usage.completion_tokens 原样落审计");
        assertEquals(34, record.totalTokens());
        assertEquals("chatcmpl-stub-0001", record.requestId(), "响应 id 落审计，便于和模型侧对账");
        assertFalse(record.degraded(), "本任务只走成功路径");
        assertEquals(Instant.parse("2026-10-20T03:20:00Z"), record.at(), "审计时间取注入的时钟");
        assertTrue(record.inputSummary().contains("ZX1.2(len=6)"), "入参摘要只给字段名与长度");
        assertTrue(record.inputSummary().contains("digest="), "入参摘要带不可逆摘要，便于同批复投关联");
    }

    @Test
    @DisplayName("身份候选：带依据字段，且由网关按置信度降序排序")
    void test_identity_candidates_call_returns_evidence_and_sorted_order() {
        Candidates candidates = gateway.identityCandidates(PLATFORM_PID3, PLATFORM_NAME, BIRTH_DATE, index());

        assertEquals(2, candidates.size());
        assertEquals("LOCMRN-0001", candidates.candidates().get(0).localMrn(),
                "替身故意把低分那条排在前面，网关必须自己排");
        assertEquals(0.74, candidates.candidates().get(0).confidence(), 1e-9);
        assertEquals("LOCMRN-0001", candidates.best().orElseThrow().localMrn());
        assertEquals(2, candidates.candidates().get(0).evidence().size(), "每条候选都要带依据");
        assertEquals("name", candidates.candidates().get(0).evidence().get(0).field());

        ModelCallRecord record = audit.latest();
        assertEquals("identity_candidates", record.operation());
        assertTrue(record.inputSummary().contains("pid3(missing)"),
                "PID-3 刻意不送出，审计里要记成 missing，让「没送」本身可查");
    }

    @Test
    @DisplayName("出站报文：Bearer 密钥 + 指定模型 + 固定 JSON Schema + system 提示")
    void test_request_carries_bearer_token_model_and_fixed_json_schema() {
        gateway.alignZFields(List.of(zx1()));

        assertEquals("Bearer " + API_KEY, stub.lastAuthorization(), "密钥走 Authorization 头");

        Map<String, Object> request = Json.asObject(Json.parse(stub.lastBody()), "出站请求体");
        assertEquals(MODEL, request.get("model"), "模型名来自配置，不写死在代码里");
        assertEquals(0.0, Json.asNumber(request.get("temperature"), "temperature"), 1e-9,
                "temperature 必须为 0，否则同段 Z 文本两次跑出两个结论，命中率对照不可复现");

        Map<String, Object> responseFormat = Json.asObject(request.get("response_format"), "response_format");
        assertEquals("json_schema", responseFormat.get("type"));
        Map<String, Object> jsonSchema = Json.asObject(responseFormat.get("json_schema"), "json_schema");
        assertEquals("z_field_map", jsonSchema.get("name"), "schema 名随操作走");
        assertEquals(Boolean.TRUE, jsonSchema.get("strict"));

        List<Object> messages = Json.asArray(request.get("messages"), "messages");
        assertEquals(2, messages.size(), "system 约束 + user 结构化片段");
        Map<String, Object> system = Json.asObject(messages.get(0), "messages[0]");
        assertEquals("system", system.get("role"));
        assertTrue(Json.asString(system.get("content"), "system.content").contains("禁止输出诊断"),
                "系统提示必须带着临床/越权边界，不是客套话");
    }

    @Test
    @DisplayName("出站报文不含身份证明文，也不含区域平台患者主键 PID-3")
    void test_outbound_request_excludes_id_card_plaintext_and_pid3() {
        gateway.identityCandidates(PLATFORM_PID3, PLATFORM_NAME, BIRTH_DATE, index());

        String body = stub.lastBody();
        // 先证明报文确实有内容（否则「没出现 PID-3」可能只是因为报文是空的）
        assertTrue(body.contains("LOCMRN-0001") && body.contains("張") && body.contains(BIRTH_DATE),
                "出站报文应含院内候选与平台侧姓名/出生日期");
        assertTrue(body.contains("id_card_digest8"), "身份证摘要要给：模型需要判断两条记录是否同一证件");
        assertFalse(body.matches("(?s).*\\d{17}[0-9X].*"),
                "出站报文里出现 18 位证件号形态即失败（硬校验走确定性代码，不需要模型看证件）");
        assertFalse(body.contains(PLATFORM_PID3), "PID-3 是跨院标识，比对用不到，不得出站");
        assertFalse(body.contains("TESTPAT-0001"), "PID-3 的值部分同样不得出站");
    }

    @Test
    @DisplayName("模型把 JSON 包在代码块里发回来，照样落成结构化对象")
    void test_fenced_model_output_is_still_parsed_as_structured() {
        stub.setFencedContent(true);

        ZFieldMap aligned = gateway.alignZFields(List.of(zx1()));

        assertEquals(1, aligned.size(), "围栏是传输噪音不是结构失败，不该把本可对齐的报文丢进降级路径");
        assertEquals("pastHistorySummary", aligned.bindingOf("ZX1.3").orElseThrow().localField());
        assertEquals(1, audit.count());
    }

    @Test
    @DisplayName("审计行里没有姓名等患者可识别明文")
    void test_audit_line_contains_no_patient_plaintext() {
        gateway.identityCandidates(PLATFORM_PID3, PLATFORM_NAME, BIRTH_DATE, index());

        String line = audit.latest().summaryLine();
        assertFalse(line.contains("張"), "审计行不得含平台侧姓名字符");
        assertFalse(line.contains("伟"), "审计行不得含平台侧姓名字符");
        assertFalse(line.contains("张伟"), "审计行不得含院内姓名");
        assertFalse(line.contains(ID_CARD), "审计行不得含身份证明文");
        assertTrue(line.contains("入参["), "审计行应保留入参形态行，便于排障");
    }

    @Test
    @DisplayName("没有 Z 段或没有候选时不发请求，不白烧一次模型调用")
    void test_empty_inputs_short_circuit_without_http_call() {
        assertTrue(gateway.alignZFields(List.of()).isEmpty());
        assertTrue(gateway.identityCandidates(PLATFORM_PID3, PLATFORM_NAME, BIRTH_DATE,
                LocalPatientIndex.empty()).isEmpty());

        assertEquals(0, stub.requestCount(), "空输入不发请求");
        assertEquals(0, audit.count(), "没发生的调用不该留下审计行");
    }

    // ---------- 测试数据 ----------

    /** {@code ZX1||既往病史摘要|高血压随访;2型糖尿病} */
    private static ZSegment zx1() {
        Map<Integer, String> fields = new LinkedHashMap<>();
        fields.put(1, "");
        fields.put(2, "既往病史摘要");
        fields.put(3, "高血压随访;2型糖尿病");
        return new ZSegment("ZX1", fields);
    }

    /** {@code ZX2||下次随访时间|20270115} */
    private static ZSegment zx2() {
        Map<Integer, String> fields = new LinkedHashMap<>();
        fields.put(1, "");
        fields.put(2, "下次随访时间");
        fields.put(3, "20270115");
        return new ZSegment("ZX2", fields);
    }

    /** {@code ZX3||责任医生姓名|（空）} —— 模型对这条会说「判断不了」。 */
    private static ZSegment zx3() {
        Map<Integer, String> fields = new LinkedHashMap<>();
        fields.put(1, "");
        fields.put(2, "责任医生姓名");
        fields.put(3, "");
        return new ZSegment("ZX3", fields);
    }

    /** 院内候选切片：身份证只给不可逆摘要。 */
    private static LocalPatientIndex index() {
        return new LocalPatientIndex(List.of(
                new LocalPatientIndex.LocalPatient("LOCMRN-0001", "张伟", BIRTH_DATE,
                        cn.hospital.region.archive.ledger.LedgerRedaction.digest8(ID_CARD)),
                new LocalPatientIndex.LocalPatient("LOCMRN-0002", "王芳", "19790308", "0a1b2c3d")));
    }

}