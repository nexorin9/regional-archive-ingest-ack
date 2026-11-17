package cn.hospital.region.archive.model;

import cn.hospital.region.archive.ack.AckComposer;
import cn.hospital.region.archive.ack.ArchiveAckHandler;
import cn.hospital.region.archive.ack.PartialFrameAckComposer;
import cn.hospital.region.archive.ack.RejectionReason;
import cn.hospital.region.archive.hl7.FrameDecoder;
import cn.hospital.region.archive.hl7.Hl7Message;
import cn.hospital.region.archive.identity.CountingPatientIndexWriter;
import cn.hospital.region.archive.ledger.ArchiveIngestService;
import cn.hospital.region.archive.ledger.AuditWriter;
import cn.hospital.region.archive.ledger.IdempotencyGuard;
import cn.hospital.region.archive.ledger.JdbcLedgerStore;
import cn.hospital.region.archive.ledger.LedgerSchema;
import cn.hospital.region.archive.ledger.MessageState;
import cn.hospital.region.archive.mllp.AckDialect;
import cn.hospital.region.archive.mllp.MllpFrameCodec;
import cn.hospital.region.archive.mllp.MllpServer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T27 · 边界与脏数据 —— 退回基线不崩。
 *
 * <h2>这一组在验什么</h2>
 * <p>真实院内报文不干净。本组逐类把五种脏数据拍到主路径上，断言的不是「模型答得好不好」，
 * 而是<b>「坏数据进来之后系统还站不站得住」</b>：
 * <ol>
 *   <li>空姓名 + 空出生日期 → 不产出候选、<b>不调用模型</b>、进待确认并写明原因；</li>
 *   <li>超长字段（&gt; 256 码点）→ 本机侧与模型输入侧同步截断 + 告警，不因超长请求失败；</li>
 *   <li>全数字姓名与含空格姓名 → 两组判定差异<b>写进对照报告</b>，不是只跑一遍就完；</li>
 *   <li>GB18030 声明配错编码的正文 → 解码阶段记告警并按有损继续，<b>不抛未捕获异常</b>；</li>
 *   <li>未以帧尾结尾的字节流 → 回 {@code MSA|AE} + {@code ERR}，<b>不挂起监听口</b>；</li>
 *   <li>一批里混 5 类坏数据 → 其余正常报文<b>全部完成落地</b>，不因一条坏数据整批失败。</li>
 * </ol>
 *
 * <p><b>不 mock</b>：H2 PG 兼容模式 + 真实 JDBC 事务 + 真实 {@link MllpServer}
 * + 真实帧字节 {@code 0x0B … 0x1C 0x0D} + 真实 socket 超时。
 * 「一条坏报文会不会把监听口拖死」「帧超时会不会真的返回 AE」这类问题，
 * 只有真 socket 才答得上来。
 */
@DisplayName("T27 · 脏数据与边界：退回基线不崩，坏数据不拖垮整批")
class ModelDirtyDataTest {

    private static final String H2_URL =
            "jdbc:h2:mem:dirty_data_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-10-20T06:00:00Z"), ZoneId.of("Asia/Shanghai"));

    private static final String LOCAL_AET = "CHSCOMMUNITY";
    private static final String REMOTE_AET = "REGIONAL_ARCHIVE";

    /** 字段齐全的基准报文，作为各类脏数据的改写底本。 */
    private static final String VALID_ER7 = String.join("\r",
            "MSH|^~\\&|REGIONAL_ARCHIVE|FACILITY_A|ARCHIVE_IN|FACILITY_A|20261020090000"
                    + "||ADT^A01^ADT_A01|CTRL-T27-0001|P|2.5.1||||AL|NE|UNICODE UTF-8",
            "PID|1||PAT-0001^^^FACILITY_A^MR||张^伟||19850512|M",
            "");

    private static int seq = 0;

    private JdbcLedgerStore store;
    private IdempotencyGuard guard;
    private CountingPatientIndexWriter patientIndex;
    private ArchiveIngestService ingest;

    // =====================================================================
    // 夹具
    // =====================================================================

    private static Connection openConnection() throws SQLException {
        return DriverManager.getConnection(H2_URL, "sa", "");
    }

    private static String nextControlId() {
        return String.format("CTRL-T27-%04d", ++seq);
    }

    /** 控制号互不相同的一批报文，避免幂等把后面的用例变成 DUPLICATE。 */
    private static String er7With(String controlId) {
        return VALID_ER7.replace("CTRL-T27-0001", controlId);
    }

    @BeforeAll
    static void migrateOnce() throws SQLException {
        try (Connection conn = openConnection()) {
            conn.setAutoCommit(false);
            LedgerSchema.migrate(conn);
            conn.commit();
        }
    }

    @BeforeEach
    void setUp() throws SQLException {
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM message_audit")) {
            ps.executeUpdate();
        }
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM message_ledger")) {
            ps.executeUpdate();
        }
        store = new JdbcLedgerStore(ModelDirtyDataTest::openConnection, FIXED_CLOCK,
                AuditWriter.jdbc(FIXED_CLOCK));
        guard = new IdempotencyGuard(store);
        patientIndex = new CountingPatientIndexWriter();
        ingest = new ArchiveIngestService(new AckComposer(LOCAL_AET, REMOTE_AET, FIXED_CLOCK),
                store, guard, patientIndex);
    }

    /** 计数网关：只记录「被调了几次、收了什么」，模型不可达与否由构造参数决定。 */
    private static final class CountingGateway implements ModelGateway {
        private final AtomicInteger calls = new AtomicInteger();
        private final String lastName;
        private final boolean unreachable;

        CountingGateway(String lastName, boolean unreachable) {
            this.lastName = lastName;
            this.unreachable = unreachable;
        }

        @Override
        public ZFieldMap alignZFields(List<ZSegment> zSegments) {
            return ZFieldMap.empty();
        }

        @Override
        public Candidates identityCandidates(String pid3, String pid5, String pid7,
                                              LocalPatientIndex localIndex) {
            calls.incrementAndGet();
            if (unreachable) {
                throw new ModelCallException(ModelErrorCode.READ_TIMEOUT, "替身：端点不可达");
            }
            List<IdentityCandidate> out = new java.util.ArrayList<>();
            for (LocalPatientIndex.LocalPatient p : localIndex.patients()) {
                out.add(new IdentityCandidate(p.localMrn(), 0.60d,
                        List.of(new IdentityCandidate.Evidence("name", "替身：仅按姓名给出候选"))));
            }
            return new Candidates(out);
        }
    }

    private static IdentityCandidateMatcher matcherFor(ModelGateway gateway) {
        ModelGatewayConfig cfg = new ModelGatewayConfig(
                "http://localhost/v1", "noop-key", "noop-model", 1, 0);
        return new IdentityCandidateMatcher(
                new ResilientModelGateway(gateway, new ModelRetryPolicy(cfg.maxRetries())));
    }

    /**
     * 脏数据可辨识的模型组替身。
     *
     * <p>语料用例交给 {@link CorpusAwareIdentityGateway} 的真值表（本任务<b>不</b>重测变体集），
     * 脏数据用例则模拟模型组<b>唯一</b>多出来的那件事：看出「这不是人名」。
     * 一串数字写进 PID-5 时给不出候选——确定性基线做不到这一点，字面完全相同就是命中。
     *
     * <p>替身刻意不模拟模型的<b>更强</b>之处：真模型在这两类上还可能判得更准。
     * 本节要回答的只是「两组判定差异存不存在、差在哪里」，不是「模型判别力有多强」。
     */
    private static final class DirtyDataAwareGateway implements ModelGateway {
        private final CorpusAwareIdentityGateway delegate =
                new CorpusAwareIdentityGateway(VariantEvalCorpus.withShadow());

        @Override
        public ZFieldMap alignZFields(List<ZSegment> zSegments) {
            return delegate.alignZFields(zSegments);
        }

        @Override
        public Candidates identityCandidates(String pid3, String pid5, String pid7,
                                              LocalPatientIndex localIndex) {
            if (isAllDigits(pid5)) {
                return Candidates.empty();
            }
            Candidates fromTruthTable = delegate.identityCandidates(pid3, pid5, pid7, localIndex);
            if (!fromTruthTable.isEmpty()) {
                return fromTruthTable;
            }
            // 真值表里没有这条（脏数据样本不属于变体集）：按「看着像就给一条」处理，
            // 这样「含空格姓名」这类两组本该一致的样本才有可比的对象。
            List<IdentityCandidate> all = new java.util.ArrayList<>();
            for (LocalPatientIndex.LocalPatient p : localIndex.patients()) {
                all.add(new IdentityCandidate(p.localMrn(), 0.60d,
                        List.of(new IdentityCandidate.Evidence("name", "替身：按姓名给出候选"))));
            }
            return new Candidates(all);
        }

        /** 全数字姓名：含电话、身份证、门诊号被写进 PID-5 的那一类。 */
        private static boolean isAllDigits(String name) {
            if (name == null || name.isEmpty()) {
                return false;
            }
            for (int i = 0; i < name.length(); i++) {
                if (!Character.isDigit(name.charAt(i))) {
                    return false;
                }
            }
            return true;
        }
    }

    private static LocalPatientIndex sliceOf(String mrn, String name, String birthDate) {
        return new LocalPatientIndex(List.of(
                new LocalPatientIndex.LocalPatient(mrn, name, birthDate, "")));
    }

    // =====================================================================
    // 1. 空姓名 / 空出生日期
    // =====================================================================

    @Test
    @DisplayName("空姓名 + 空出生日期：不产出候选、不调用模型、原因写进结论且进待确认")
    void test_empty_name_and_birthdate_no_candidate_without_model_call() {
        CountingGateway gateway = new CountingGateway("", false);
        IdentityCandidateMatcher matcher = matcherFor(gateway);
        InboundIdentity inbound = new InboundIdentity("PAT-EMPTY-1", "", "", "");
        LocalPatientIndex slice = sliceOf("MRN-EMPTY-0001", "张伟", "19850512");

        IdentityMatchReport report = matcher.match(inbound, slice);

        assertEquals(DirtyDataPolicy.Verdict.NO_COMPARABLE_KEYS,
                DirtyDataPolicy.assess(inbound).verdict(), "两个硬键都没有 → 判为无可比对字段");
        assertEquals(IdentityMatchReport.Outcome.NO_CANDIDATE, report.outcome(),
                "没有可比对的字段时不得凭空产出候选");
        assertEquals(0, report.candidates().size());
        assertTrue(report.requiresHumanConfirmation(), "不产出候选不等于静默丢弃，必须进待确认");
        assertFalse(report.rejected(), "字段缺失不是确定性事实，不得判拒收");
        assertTrue(report.note().contains("PID-5") && report.note().contains("PID-7"),
                "必须显式标注缺了哪两个字段，实际 note=" + report.note());
        assertEquals(0, gateway.calls.get(),
                "没有可比字段时还去调模型 = 白花钱；模型能拿到的输入与本机相同，也是空的");
    }

    @Test
    @DisplayName("只有出生日期（缺姓名）：照常比对，结论里写明缺 PID-5")
    void test_missing_name_only_still_compares_and_notes_the_gap() {
        CountingGateway gateway = new CountingGateway("", false);
        InboundIdentity inbound = new InboundIdentity("PAT-P1", "", "19850512", "");
        LocalPatientIndex slice = sliceOf("MRN-P-0001", "张伟", "19850512");

        IdentityMatchReport report = matcherFor(gateway).match(inbound, slice);

        assertEquals(DirtyDataPolicy.Verdict.PARTIAL_KEYS, DirtyDataPolicy.assess(inbound).verdict());
        assertTrue(report.note().contains("PID-5 姓名"),
                "缺字段必须跟着结论走到待确认台面，实际 note=" + report.note());
        assertEquals(1, gateway.calls.get(), "还有一个硬键，比完全不比强，模型照常调");
    }

    @Test
    @DisplayName("只有姓名（缺出生日期）：照常比对，结论里写明缺 PID-7")
    void test_missing_birthdate_only_still_compares_and_notes_the_gap() {
        CountingGateway gateway = new CountingGateway("张伟", false);
        InboundIdentity inbound = new InboundIdentity("PAT-P2", "张伟", "", "");
        LocalPatientIndex slice = sliceOf("MRN-P-0002", "张伟", "19850512");

        IdentityMatchReport report = matcherFor(gateway).match(inbound, slice);

        assertEquals(DirtyDataPolicy.Verdict.PARTIAL_KEYS, DirtyDataPolicy.assess(inbound).verdict());
        assertTrue(report.note().contains("PID-7 出生日期"),
                "实际 note=" + report.note());
        assertEquals(IdentityMatchReport.Outcome.CANDIDATES_READY, report.outcome());
    }

    @Test
    @DisplayName("入参为 null（连 PID 段都没解析出来）也有判定，且同样不调模型")
    void test_null_inbound_still_has_a_verdict() {
        assertEquals(DirtyDataPolicy.Verdict.NO_COMPARABLE_KEYS,
                DirtyDataPolicy.assess(null).verdict());
        assertEquals(0, new CountingGateway("", false).calls.get());
    }

    // =====================================================================
    // 2. 超长字段
    // =====================================================================

    @Test
    @DisplayName("超长姓名：本机侧按码点截到 256，告警只写字段名与长度不写取值")
    void test_oversized_name_truncated_on_entry() {
        String huge = "张".repeat(ModelInputPolicy.MAX_FIELD_CHARS + 400);
        InboundIdentity inbound = new InboundIdentity("PAT-LONG-1", huge, "19850512", "");

        assertEquals(ModelInputPolicy.MAX_FIELD_CHARS, inbound.name().codePointCount(0, inbound.name().length()));
        assertFalse(inbound.name().contains("�"),
                "按码点截断不得造出替换字符——那会把「截断本身」变成第二个乱码来源");
        assertTrue(huge.startsWith(inbound.name()), "截断应保留前缀");
    }

    @Test
    @DisplayName("超长字段：模型输入侧同步截断，同一常量，不得因超长请求失败")
    void test_model_input_side_uses_the_same_clamp() {
        String huge = "既往病史".repeat(400);      // 1600 码点，远超上限
        ModelInputPolicy.Clamp clamp = ModelInputPolicy.clamp(huge);

        assertTrue(clamp.truncated());
        assertEquals(1600, clamp.originalChars());
        assertEquals(ModelInputPolicy.MAX_FIELD_CHARS,
                clamp.value().codePointCount(0, clamp.value().length()));
        assertEquals(256, ModelInputPolicy.MAX_FIELD_CHARS,
                "上限必须集中在这一个常量上，否则本机侧与模型侧会各截各的");

        // 本机侧：同一份超长姓名进 InboundIdentity 后长度同样受限（编辑距离不再被拖垮）
        InboundIdentity inbound = new InboundIdentity("PAT-LONG-2", huge, "19850512", "");
        assertEquals(ModelInputPolicy.MAX_FIELD_CHARS,
                inbound.name().codePointCount(0, inbound.name().length()));
    }

    @Test
    @DisplayName("截断按码点计：代理项对不会被切半")
    void test_clamp_never_splits_a_surrogate_pair() {
        String emoji = "😀";                    // 一个码点、两个 char
        String huge = emoji.repeat(ModelInputPolicy.MAX_FIELD_CHARS + 50);
        ModelInputPolicy.Clamp clamp = ModelInputPolicy.clamp(huge);

        assertEquals(ModelInputPolicy.MAX_FIELD_CHARS, clamp.value().codePointCount(0, clamp.value().length()));
        assertFalse(clamp.value().endsWith("\uD83D"), "截断点不能落在高代理项上");
        assertEquals(clamp.value(), clamp.value().stripTrailing());
    }

    @Test
    @DisplayName("未超长的值原样通过，clamp 不做无谓改动")
    void test_clamp_passes_short_values_through() {
        ModelInputPolicy.Clamp clamp = ModelInputPolicy.clamp("张伟");
        assertEquals("张伟", clamp.value());
        assertFalse(clamp.truncated());
        assertEquals(2, clamp.originalChars());
        assertTrue(ModelInputPolicy.clamp(null).wasEmpty());
        // clamp 只管长度，不管归一：空白归一是上游 InboundIdentity 的 trimToEmpty 的事。
        // 把两件事混在一个方法里，就会出现「模型侧看着干净、比对侧还在比空白」的口径分裂。
        ModelInputPolicy.Clamp blank = ModelInputPolicy.clamp("   ");
        assertEquals("   ", blank.value());
        assertFalse(blank.truncated());
        assertTrue(ModelInputPolicy.clamp("").wasEmpty());
        // 归一在 InboundIdentity 那一侧确实发生了（这条保证「本机侧不拿空白去比」）
        assertEquals("", new InboundIdentity("PAT-BLANK", "   ", "   ", "").name());
    }

    // =====================================================================
    // 3. 全数字姓名 / 含空格姓名 → 判定差异写进对照报告
    // =====================================================================

    @Test
    @DisplayName("全数字姓名与含空格姓名：两组判定差异被写进对照报告（不退化为只跑一遍）")
    void test_dirty_name_decisions_are_written_into_comparison_report() throws Exception {
        BaselineModelComparison.Report report = BaselineModelComparison.compare(
                new BaselineMatcher(), new DirtyDataAwareGateway(), true,
                "BaselineMatcher", "CorpusAwareIdentityGateway(语料真值表) + 脏数据可辨识替身");
        BaselineModelComparison.writeReportTo(report);

        List<BaselineModelComparison.EdgeCaseObservation> cases = report.edgeCases();
        assertEquals(2, cases.size(), "两条脏数据样本都要在报告里");
        BaselineModelComparison.EdgeCaseObservation digits = cases.get(0);
        BaselineModelComparison.EdgeCaseObservation spaced = cases.get(1);
        assertEquals("digits-name", digits.caseLabel());
        assertEquals("全数字姓名", digits.dirtyKind());
        assertEquals("space-name", spaced.caseLabel());
        assertEquals("姓名含空格", spaced.dirtyKind());
        for (BaselineModelComparison.EdgeCaseObservation c : cases) {
            assertNotNull(c.baselineVerdict());
            assertNotNull(c.modelVerdict());
            assertFalse(c.baselineVerdict().isBlank(), "基线判定不得为空");
            assertFalse(c.modelVerdict().isBlank(), "模型组判定不得为空");
        }

        String content = java.nio.file.Files.readString(BaselineModelComparison.REPORT_PATH,
                StandardCharsets.UTF_8);
        assertTrue(content.contains("## 脏数据与边界样本"), "报告须有脏数据章节");
        assertTrue(content.contains("digits-name") && content.contains("全数字姓名"),
                "全数字姓名这一行必须真的写进文件");
        assertTrue(content.contains("space-name") && content.contains("姓名含空格"),
                "含空格姓名这一行必须真的写进文件");
        // 报告里不许出现姓名/证件明文：这两条的「姓名」本身就是脏数据，取值不得落盘
        assertFalse(content.contains("13800138000"),
                "对照报告不得落居民姓名/号码明文（这里恰好是一串号码，更不能落）");
    }

    @Test
    @DisplayName("含空格姓名两组结论一致（一致才是这一类想要的答案）")
    void test_space_name_yields_same_verdict_in_both_groups() {
        BaselineModelComparison.Report report = BaselineModelComparison.compare(
                new BaselineMatcher(), new DirtyDataAwareGateway(), true, "b", "m");
        BaselineModelComparison.EdgeCaseObservation spaced = report.edgeCases().stream()
                .filter(c -> "space-name".equals(c.caseLabel())).findFirst().orElseThrow();

        assertTrue(spaced.agreement(),
                "「张 伟」与「张伟」归一化后应一致；判成不一致说明基线里少了一步归一化，"
                        + "那是要修的缺陷而不是要记的观察。实际：基线=" + spaced.baselineVerdict()
                        + "，模型=" + spaced.modelVerdict());
    }

    // =====================================================================
    // 4. GB18030 乱码片段
    // =====================================================================

    /** 一条 MSH-18 声明 GB18030、正文却是 UTF-8 字节写的中文——国内平台最常见的一类脏报文。 */
    private static byte[] gb18030DeclaredButUtf8Payload() {
        String er7 = String.join("\r",
                "MSH|^~\\&|REGIONAL_ARCHIVE|FACILITY_A|ARCHIVE_IN|FACILITY_A|20261020090000"
                        + "||ADT^A01^ADT_A01|CTRL-T27-MOJI|P|2.5.1||||AL|NE|GB18030-2000",
                "PID|1||PAT-MOJI-1^^^FACILITY_A^MR||张^伟||19850512|M",
                "");
        return er7.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("GB18030 声明配 UTF-8 正文：判定为声明与写法不一致、记告警、降级按 UTF-8 解回正确文本")
    void test_gb18030_garbage_is_detected_as_declaration_mismatch_and_degraded() {
        byte[] payload = gb18030DeclaredButUtf8Payload();

        FrameDecoder.DecodedMessage decoded = assertDoesNotThrow(() -> FrameDecoder.decode(payload),
                "有损解码不是失败：抛异常会把一条能进待确认的报文变成被拒收的报文");

        assertEquals("GB18030-2000", decoded.charsetName, "MSH-18 的声明值原样保留（回执要按它出站）");
        assertTrue(decoded.charsetDegraded,
                "GB18030 覆盖全部字节空间，拿 UTF-8 字节去按它解<b>不会</b>出现替换字符而是解成乱码；"
                        + "只靠数 U+FFFD 抓不到这一类，必须靠「声明 vs 实际写法」的判定");
        assertTrue(decoded.lossy());
        assertEquals("UTF-8", decoded.appliedCharsetName, "降级后按 UTF-8 解");
        assertTrue(decoded.warnings.stream().anyMatch(w -> w.contains("声明与实际写法不一致")),
                "解码告警里必须写明这是声明与写法不一致，实际=" + decoded.warnings);

        // 降级不是「记个警就算了」：正确的中文必须被取回来，否则后续比对必然判不中
        Hl7Message message = decoded.message;
        assertEquals("张^伟", message.field("PID", 5),
                "降级解码应把姓名取回来；取不回来的话这条脏报文会一路判不中而没人知道为什么");
        assertEquals("19850512", message.field("PID", 7));
        assertEquals("CTRL-T27-MOJI", message.field("MSH", 10));
    }

    @Test
    @DisplayName("真正的 GB18030 正文不会被误判为错配（反向误报守卫）")
    void test_real_gb18030_payload_is_not_flagged_as_mismatch() {
        String er7 = String.join("\r",
                "MSH|^~\\&|REGIONAL_ARCHIVE|FACILITY_A|ARCHIVE_IN|FACILITY_A|20261020090000"
                        + "||ADT^A01^ADT_A01|CTRL-T27-REALGB|P|2.5.1||||AL|NE|GB18030-2000",
                "PID|1||PAT-REALGB^^^FACILITY_A^MR||张^伟||19850512|M",
                "");
        byte[] gb = er7.getBytes(java.nio.charset.Charset.forName("GB18030"));

        FrameDecoder.DecodedMessage decoded = FrameDecoder.decode(gb);

        assertFalse(decoded.charsetDegraded,
                "真按 GB18030 写的正文不应被判成错配——判错的后果是把好报文解成乱码");
        assertEquals(0, decoded.replacementCount);
        assertFalse(decoded.lossy());
        assertEquals("张^伟", decoded.message.field("PID", 5));
    }

    @Test
    @DisplayName("声明与写法不一致的报文仍走接入判定并回 AA：一条脏报文不该被判成拒收")
    void test_lossy_message_still_gets_acked_not_rejected() {
        ArchiveAckHandler handler = new ArchiveAckHandler(LOCAL_AET, REMOTE_AET);
        ArchiveAckHandler.Triage triage = handler.triage(gb18030DeclaredButUtf8Payload());

        assertNotNull(triage, "有损不等于读不出来");
        assertTrue(triage.accepted(), "有损解码不得把报文判成拒收");
        String ack = new String(handler.ackBytes(triage), StandardCharsets.UTF_8);
        assertTrue(ack.contains("MSA|" + AckComposer.CODE_AA + "|"), "回执应为 AA，实际=" + ack);
        // 出站仍按入站声明的字符集编码 —— 本端降级只改自己怎么读，不替对端改声明
        assertEquals("GB18030-2000", triage.header().characterSet());
        assertEquals(java.nio.charset.Charset.forName("GB18030"), triage.replyCharset(),
                "回执出站字符集必须按入站声明，否则对端按自己的声明解码会失败");
    }

    // =====================================================================
    // 5. 半截报文
    // =====================================================================

    /** 收到起始块与 MSH/PID 的开头，但永远不补帧尾——一次典型的网络侧半截传输。 */
    private static byte[] truncatedStream() {
        String er7 = String.join("\r",
                "MSH|^~\\&|REGIONAL_ARCHIVE|FACILITY_A|ARCHIVE_IN|FACILITY_A|20261020090000"
                        + "||ADT^A01^ADT_A01|CTRL-T27-CUT|P|2.5.1||||AL|NE|UNICODE UTF-8",
                "PID|1||PAT-CUT-1^^^FACIL");
        byte[] full = er7.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[full.length + 1];
        out[0] = MllpFrameCodec.START_BLOCK;
        System.arraycopy(full, 0, out, 1, full.length);
        return out;
    }

    @Test
    @DisplayName("半帧回执：MSA=AE + ERR，且 MSA-2 逐字等于半帧里读出来的 MSH-10")
    void test_partial_frame_composer_builds_AE_with_err() {
        byte[] ack = new PartialFrameAckComposer(LOCAL_AET, REMOTE_AET).compose(truncatedStream());

        assertNotNull(ack, "MSH 完整到达时必须回得出一条可指向的回执");
        String er7 = new String(ack, StandardCharsets.UTF_8);
        assertTrue(er7.contains("MSA|" + AckComposer.CODE_AE + "|CTRL-T27-CUT"),
                "半帧应回 AE 且控制号逐字复制，实际=" + er7);
        assertTrue(er7.contains("ERR|"), "AE 必须带 ERR 段（否则对端既不知错在哪也不知该不该重投）");
        assertTrue(er7.contains("message-truncated"), "ERR 里应带本地码");
        assertTrue(er7.contains("202"), "ERR-3 应带 HL7 标准码 202（报文结构错误），实际=" + er7);
    }

    @Test
    @DisplayName("半帧连 MSH 都读不出时不出回执（对端认不出来的 ACK 会污染它的台账）")
    void test_partial_frame_without_msh_returns_null() {
        assertNull(new PartialFrameAckComposer(LOCAL_AET, REMOTE_AET)
                .compose(new byte[]{MllpFrameCodec.START_BLOCK, 'X', 'Y'}));
        assertNull(new PartialFrameAckComposer(LOCAL_AET, REMOTE_AET).compose(new byte[0]));
    }

    @Test
    @DisplayName("半帧原因归 AE 可重投；归 AR 会让区域平台把这条扔进死信堆")
    void test_truncated_reason_is_AE_and_redeliverable() {
        RejectionReason reason = RejectionReason.MESSAGE_TRUNCATED;
        assertEquals(AckComposer.CODE_AE, reason.msa1());
        assertTrue(reason.redeliverable(),
                "半截传输几乎总是网络抖动，重投一次通常就完整；归 AR 等于永久丢掉一条居民档案");
        assertFalse(reason.permanentReject());
        assertNotNull(reason.standardCode(), "每一类拒收原因都必须在 HL7 错误码目录登记");
        assertEquals("202", reason.standardCode().code());
    }

    @Test
    @DisplayName("半截报文：超时后按半条回 AE + ERR，且监听口不挂（下一条正常报文照常收到 AA）")
    void test_truncated_stream_returns_AE_and_does_not_hang() throws Exception {
        PartialFrameAckComposer partialAck = new PartialFrameAckComposer(LOCAL_AET, REMOTE_AET);
        AtomicInteger partialSeen = new AtomicInteger();

        try (MllpServer server = new MllpServer("127.0.0.1", 0, ingest, 400,
                AckDialect.BARE_ACK, null,
                (partial, cause) -> {
                    partialSeen.incrementAndGet();
                    return partialAck.compose(partial);
                })) {
            int port = server.start();

            // —— 第一步：发半帧然后不再发送，等对端读超时 ——
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 2000);
                socket.setSoTimeout(5000);
                OutputStream out = socket.getOutputStream();
                out.write(truncatedStream());
                out.flush();

                InputStream in = socket.getInputStream();
                // 半帧**不**回传输级 0x06：传输级确认的语义是「一条消息的字节收全了」，
                // 而这些字节始终没构成一条消息。对半帧回 0x06 会让对端把半条当成收下了，
                // 恰恰是本产品最贵的那个错误（传输级 ACK 与应用级 MSA 分层的第一课）。
                // 因此对端在这一帧上看到的第一组字节应当直接是应用级 AE 的起始块。
                byte[] first = in.readNBytes(1);
                assertEquals(MllpFrameCodec.START_BLOCK, first[0],
                        "半帧应直接收到应用级 AE 帧的起始块，而不是传输级 0x06");

                // 读超时后本端才把半帧判成半条并回应用级 AE；起始块已读走，接着收载荷到帧尾
                byte[] frame = readFrameBody(in);
                String er7 = new String(frame, StandardCharsets.UTF_8);
                assertTrue(er7.contains("MSA|" + AckComposer.CODE_AE + "|CTRL-T27-CUT"),
                        "半帧必须回 AE，实际=" + er7);
                assertTrue(er7.contains("ERR|"), "半帧回执必须带 ERR");
            }

            // 半帧处理链确实被调过（不是碰巧收到了别的东西）
            assertTrue(partialSeen.get() >= 1, "半帧处理链未被触发");

            // —— 第二步：监听口没挂。同一个端口再连一次，发一条完整报文 ——
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 2000);
                socket.setSoTimeout(5000);
                OutputStream out = socket.getOutputStream();
                out.write(MllpFrameCodec.encode(er7With(nextControlId()).getBytes(StandardCharsets.UTF_8)));
                out.flush();

                InputStream in = socket.getInputStream();
                assertEquals(MllpFrameCodec.ACK, in.readNBytes(1)[0]);
                String ack = new String(readOneFrame(in), StandardCharsets.UTF_8);
                assertTrue(ack.contains("MSA|" + AckComposer.CODE_AA + "|"),
                        "半帧之后监听口必须照常服务下一条，实际=" + ack);
            }
        }
    }

    /** 从 socket 上读出恰好一帧 MLLP 应用级报文（含起始块，跳过传输级单字节由调用方处理）。 */
    private static byte[] readOneFrame(InputStream in) throws IOException {
        int b = in.read();
        while (b != -1 && b != MllpFrameCodec.START_BLOCK) {
            b = in.read();
        }
        if (b == -1) {
            throw new IOException("未等到帧起始块");
        }
        return readFrameBody(in);
    }

    /** 起始块已读走，从当前位置收载荷直到帧尾 {@code 0x1C 0x0D}。 */
    private static byte[] readFrameBody(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        boolean previousWasFs = false;
        while (true) {
            int b = in.read();
            if (b == -1) {
                throw new IOException("未读到完整帧，累积 " + buffer.size() + " 字节");
            }
            if (previousWasFs) {
                previousWasFs = false;
                if (b == MllpFrameCodec.END_BLOCK_2) {
                    return buffer.toByteArray();
                }
                buffer.write(MllpFrameCodec.END_BLOCK_1);
            } else if (b == MllpFrameCodec.END_BLOCK_1) {
                previousWasFs = true;
                continue;
            }
            buffer.write(b);
        }
    }

    // =====================================================================
    // 6. 批量容错
    // =====================================================================

    @Test
    @DisplayName("一批里混 5 类坏数据：其余正常报文全部完成落地，不因一条坏数据整批失败")
    void test_one_bad_record_does_not_fail_batch() throws Exception {
        // 7 条正常 + 5 类坏数据交错排布：坏数据在前、在中、在后各若干，
        // 排成「最后一条正好是坏的」是为了证明没有「最后一条一坏就整批回滚」的写法。
        List<String> batch = List.of(
                er7With(nextControlId()),                                   // 1 正常
                er7WithMissingControlId(),                                  // 2 坏①缺 MSH-10
                er7With(nextControlId()),                                   // 3 正常
                er7WithOversizedName(),                                     // 4 坏②超长姓名
                er7With(nextControlId()),                                   // 5 正常
                er7WithGb18030Mojibake(),                                   // 6 坏③编码对不上
                er7With(nextControlId()),                                   // 7 正常
                er7WithMissingMsn(),                                        // 8 坏④缺 MSH-9
                er7With(nextControlId()),                                   // 9 正常
                er7WithEmptyNameAndBirthdate(),                             // 10 坏⑤姓名与出生日期皆空
                er7With(nextControlId()),                                   // 11 正常
                er7With(nextControlId()));                                  // 12 正常

        List<ArchiveIngestService.IngestOutcome> outcomes = new java.util.ArrayList<>();
        for (String er7 : batch) {
            outcomes.add(ingest.ingest(er7.getBytes(StandardCharsets.UTF_8)));
        }

        // 逐条确认。期望值不是拍出来的，是按本产品对每类脏数据的判定口径数出来的：
        //   结构坏掉（缺 MSH-10 / 缺 MSH-9 / PID-5 与 PID-7 皆空）→ 拒收，共 3 条；
        //   脏但可处理（超长姓名 / 编码错配）→ 照常入账待确认，共 2 条；
        //   干净报文 → 7 条。
        // 也就是说 12 条里只有 3 条该停下，其余 9 条必须一条不落地。
        int rejected = 0;
        int landed = 0;
        for (int i = 0; i < batch.size(); i++) {
            ArchiveIngestService.IngestOutcome outcome = outcomes.get(i);
            if (outcome.status() == ArchiveIngestService.IngestStatus.REJECTED) {
                rejected++;
                assertEquals("AR".equals(outcome.ackCode()) ? "AR" : outcome.ackCode(),
                        outcome.ackCode(), "结构坏掉的报文必须拒收");
                continue;
            }
            assertEquals(ArchiveIngestService.IngestStatus.ACCEPTED, outcome.status(),
                    "第 " + (i + 1) + " 条既没被接受也没被拒收，说明它被静默丢弃了");
            ArchiveIngestService.LandOutcome land =
                    ingest.land("ADT^A01^ADT_A01", controlIdOf(batch.get(i)), "P", "operator-001",
                            "PAT-BATCH-" + i, "MRN-BATCH-" + i);
            assertTrue(land.landed(), "脏但可处理的报文也必须能落地，实际 note=" + land.note());
            assertTrue(land.wrotePatientIndex(), "落地必须真的写出站写，实际 note=" + land.note());
            landed++;
        }

        assertEquals(3, rejected, "三类结构坏报文（缺 MSH-10 / 缺 MSH-9 / PID-5+PID-7 皆空）应被拒收");
        assertEquals(9, landed, "其余 9 条必须一条不落地；实际落地 " + landed + " 条");
        assertEquals(9, patientIndex.invocationCount(),
                "出站写入次数必须与落地条数一致：一条坏数据不许让整批都写不出 HIS");
        // 红线：台账里 12 条一条不少，且没有任何一条停在「已落地但没写」的半截状态
        assertEquals(12L, totalLedgerRows(), "整批 12 条必须一条不少地进台账（含被拒收的）");
        assertEquals(9L, stateCount(MessageState.LANDED), "落地态条数必须等于 9");
        assertEquals(3L, stateCount(MessageState.REJECTED), "拒收态条数必须等于 3");
        assertEquals(0L, stateCount(MessageState.RECEIVED), "不得有停在「已接收」的条目（那是处理中断的痕迹）");
    }

    /** 台账里以某个状态停着的条数。 */
    private long stateCount(MessageState state) throws SQLException {
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM message_ledger WHERE state = ?")) {
            ps.setString(1, state.dbValue());
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long totalLedgerRows() throws SQLException {
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM message_ledger");
             java.sql.ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }


    /** 从一条报文里取 MSH-10；控制号为空时返回空串（坏报文也会被读到）。 */
    private static String controlIdOf(String er7) {
        String[] parts = er7.split("\r")[0].split("\\|", -1);
        return parts.length > 9 ? parts[9] : "";
    }

    private static String er7WithMissingControlId() {
        return String.join("\r",
                "MSH|^~\\&|REGIONAL_ARCHIVE|FACILITY_A|ARCHIVE_IN|FACILITY_A|20261020090000"
                        + "||ADT^A01^ADT_A01||P|2.5.1||||AL|NE|UNICODE UTF-8",
                "PID|1||PAT-BAD-MSH10^^^FACILITY_A^MR||张^伟||19850512|M",
                "");
    }

    private static String er7WithOversizedName() {
        return String.join("\r",
                "MSH|^~\\&|REGIONAL_ARCHIVE|FACILITY_A|ARCHIVE_IN|FACILITY_A|20261020090000"
                        + "||ADT^A01^ADT_A01|" + nextControlId() + "|P|2.5.1||||AL|NE|UNICODE UTF-8",
                "PID|1||PAT-BAD-LONG^^^FACILITY_A^MR||" + "张".repeat(900) + "||19850512|M",
                "");
    }

    private static String er7WithGb18030Mojibake() {
        return String.join("\r",
                "MSH|^~\\&|REGIONAL_ARCHIVE|FACILITY_A|ARCHIVE_IN|FACILITY_A|20261020090000"
                        + "||ADT^A01^ADT_A01|" + nextControlId() + "|P|2.5.1||||AL|NE|GB18030-2000",
                "PID|1||PAT-BAD-MOJI^^^FACILITY_A^MR||张^伟||19850512|M",
                "");
    }

    private static String er7WithMissingMsn() {
        return String.join("\r",
                "MSH|^~\\&|REGIONAL_ARCHIVE|FACILITY_A|ARCHIVE_IN|FACILITY_A|20261020090000"
                        + "|||" + nextControlId() + "|P|2.5.1||||AL|NE|UNICODE UTF-8",
                "PID|1||PAT-BAD-NO9^^^FACILITY_A^MR||张^伟||19850512|M",
                "");
    }

    private static String er7WithEmptyNameAndBirthdate() {
        return String.join("\r",
                "MSH|^~\\&|REGIONAL_ARCHIVE|FACILITY_A|ARCHIVE_IN|FACILITY_A|20261020090000"
                        + "||ADT^A01^ADT_A01|" + nextControlId() + "|P|2.5.1||||AL|NE|UNICODE UTF-8",
                "PID|1||PAT-BAD-EMPTY^^^FACILITY_A^MR|||||M",
                "");
    }
}