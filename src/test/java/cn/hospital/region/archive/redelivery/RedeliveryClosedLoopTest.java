package cn.hospital.region.archive.redelivery;

import cn.hospital.region.archive.ack.AckComposer;
import cn.hospital.region.archive.identity.ConfirmDecision;
import cn.hospital.region.archive.identity.ConfirmGate;
import cn.hospital.region.archive.identity.ConfirmOutcome;
import cn.hospital.region.archive.identity.CountingPatientIndexWriter;
import cn.hospital.region.archive.ledger.ArchiveIngestService;
import cn.hospital.region.archive.ledger.AuditWriter;
import cn.hospital.region.archive.ledger.IdempotencyGuard;
import cn.hospital.region.archive.ledger.IdempotencyKey;
import cn.hospital.region.archive.ledger.JdbcLedgerStore;
import cn.hospital.region.archive.ledger.LedgerRow;
import cn.hospital.region.archive.ledger.LedgerSchema;
import cn.hospital.region.archive.ledger.MessageState;
import cn.hospital.region.archive.ledger.RevertService;
import cn.hospital.region.archive.mllp.MllpFrameCodec;
import cn.hospital.region.archive.mllp.MllpServer;
import cn.hospital.region.archive.model.Candidates;
import cn.hospital.region.archive.model.LocalPatientIndex;
import cn.hospital.region.archive.model.ModelCallException;
import cn.hospital.region.archive.model.ModelErrorCode;
import cn.hospital.region.archive.model.ModelGateway;
import cn.hospital.region.archive.model.ModelRetryPolicy;
import cn.hospital.region.archive.model.ResilientModelGateway;
import cn.hospital.region.archive.model.ZFieldMap;
import cn.hospital.region.archive.model.ZSegment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T36 · 重投闭环端到端验收 —— 把 spec §3 的验收故事做成可复核的断言：
 *
 * <pre>
 *   区域平台推 8 条 → 逐条四态（已落地 3 / 待确认 2 / 降级 AE 2 / 拒收 AR 1）
 *     → 次日平台追问，缺 2 条回执 → 差集恰为那 2 条
 *     → 只重投那 2 条 → 平台回 AA → 台账推进 RETRY_CONFIRMED + 写审计
 *     → 其余 6 条一个字节没动，院内患者主索引出站写仍恰好 3 次
 *     → 回执报文条数与待确认台账条数对得上
 * </pre>
 *
 * <h2>不用任何 mock 库</h2>
 * <ul>
 *   <li>入站：真实 {@link MllpServer} + 真实帧字节 {@code 0x0B ... 0x1C 0x0D} + 真实 socket；</li>
 *   <li>台账：真实 H2 PG 兼容模式 + 真实 JDBC 事务（生产指向 PostgreSQL，SQL 不分叉）；</li>
 *   <li>对端：真实 {@link RegionalPlatformMllpStub}（{@link java.net.ServerSocket}），
 *       回执由生产同一套 {@link AckComposer} 组装，不是自造字符串；</li>
 *   <li>重投：真实出站 socket，逐帧收发，结论只看 MSA-1。</li>
 * </ul>
 *
 * <h2>两条 AE 是怎么来的</h2>
 * <p>降级路径：模型环节<b>真的被调用</b>（报文带 Z 段），网关不可达 → 收敛为
 * {@link cn.hospital.region.archive.model.DegradedResult} → 该条入账为「待确认 + 降级」，
 * 回 {@code AE}（可重投）。这正是本产品承诺的行为：<b>模型挂了一条都不少回执</b>。
 *
 * <h2>回执码从哪来</h2>
 * <p>逐条取<b>线上真实读到的 MSA 帧</b>（{@code msa1Of}），不从业务对象里推断：
 * 断言「这条回的是 AE」必须以对端真正收到的那条回执为准。
 */
class RedeliveryClosedLoopTest {

    private static final String H2_URL =
            "jdbc:h2:mem:redelivery_closed_loop;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

    /** 固定时钟：批次日 2026-10-20 的次日 09:00（+08:00）。 */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-10-21T01:00:00Z"), ZoneId.of("Asia/Shanghai"));

    private static final String LOCAL_AET = "CHSCOMMUNITY";
    private static final String MESSAGE_TYPE = "ADT^A08^ADT_A08";

    /** 批次号口径与 BatchIdResolver.sendingAppAndDay() 一致（发送应用 + MSH-7 前 8 位）。 */
    private static final String BATCH_ID = "REGIONAL_ARCHIVE-20261020";

    private JdbcLedgerStore store;
    private IdempotencyGuard guard;
    private CountingPatientIndexWriter patientIndex;
    private ArchiveIngestService ingest;
    private JdbcRedeliveryQueue queue;
    private ConfirmGate confirmGate;

    /** 本端这一批回过的 MSA 码（控制号 → AA/AE/AR）；对账左侧补码用。 */
    private final Map<String, String> localAckCodes = new LinkedHashMap<>();

    private RegionalPlatformMllpStub platform;

    private static Connection openConnection() throws SQLException {
        return DriverManager.getConnection(H2_URL, "sa", "");
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
    void setUp() throws Exception {
        clearTables();
        store = new JdbcLedgerStore(RedeliveryClosedLoopTest::openConnection, FIXED_CLOCK,
                AuditWriter.jdbc(FIXED_CLOCK));
        guard = new IdempotencyGuard(store);
        patientIndex = new CountingPatientIndexWriter();
        // 装配模型环节：真调用、真降级（本类要用降级造出 2 条可重投的 AE）
        ResilientModelGateway model = new ResilientModelGateway(new AlwaysFailingGateway(),
                new ModelRetryPolicy(0));
        ingest = new ArchiveIngestService(new AckComposer(LOCAL_AET, "REGIONAL_ARCHIVE", FIXED_CLOCK),
                store, guard, patientIndex, ArchiveIngestService.BatchIdResolver.sendingAppAndDay(),
                model);
        queue = new JdbcRedeliveryQueue(RedeliveryClosedLoopTest::openConnection, FIXED_CLOCK);
        confirmGate = new ConfirmGate(store, ingest, new RevertService(store));
        platform = RegionalPlatformMllpStub.start(FIXED_CLOCK);
    }

    @AfterEach
    void tearDown() {
        if (platform != null) {
            platform.close();
        }
    }

    private static void clearTables() throws SQLException {
        for (String table : List.of("redelivery_queue", "message_audit", "message_ledger")) {
            try (Connection conn = openConnection();
                 PreparedStatement ps = conn.prepareStatement("DELETE FROM " + table)) {
                ps.executeUpdate();
            }
        }
    }

    // =====================================================================
    // 语料：8 条一批
    // =====================================================================

    /** 一条合法报文；{@code withZSegment} 决定是否真的触发模型调用。 */
    private static String er7(int index, boolean withZSegment) {
        StringBuilder sb = new StringBuilder();
        sb.append("MSH|^~\\&|REGIONAL_ARCHIVE|FACILITY_A|ARCHIVE_IN|FACILITY_A|20261020090000")
                .append("||").append(MESSAGE_TYPE).append("|CTRL-20261020-")
                .append(String.format("%04d", index))
                .append("|P|2.5.1|||AL|NE||UNICODE UTF-8").append('\r');
        sb.append("PID|1||PAT-20261020").append(String.format("%03d", index))
                .append("^^^FACILITY_A^MR||李^").append((char) ('甲' + index))
                .append("||19").append(70 + index).append("0315|M").append('\r');
        if (withZSegment) {
            // Z 段 = 模型对齐的输入；没有它网关直接短路、不会产生降级
            sb.append("ZX|1|随访提示：本季度高血压随访待完成|").append('\r');
        }
        return sb.toString();
    }

    /** 字符集声明为 MARC-8 的一条报文：解码阶段即判 AR（确定性事实，不进重投）。 */
    private static String arEr7() {
        return ("MSH|^~\\&|REGIONAL_ARCHIVE|FACILITY_A|ARCHIVE_IN|FACILITY_A|20261020090000"
                + "||" + MESSAGE_TYPE + "|CTRL-20261020-0008"
                + "|P|2.5.1|||AL|NE||MARC-8\r"
                + "PID|1||PAT-20261020-008^^^FACILITY_A^MR||王^五||19680315|M\r");
    }

    private static byte[] bytes(String er7) {
        return er7.getBytes(StandardCharsets.UTF_8);
    }

    private static String controlIdOf(String er7) {
        return er7.split("\r")[0].split("\\|", -1)[9];
    }

    /** 一条报文入站后的落点：台账行 + 线上真实回执码。 */
    private record Arrival(long ledgerId, String controlId, String ackCode, MessageState state) {
    }

    /** 8 条批次及其逐条回执码。 */
    private record Batch(List<Arrival> arrivals, List<String> wireAckCodes) {

        Arrival byIndex(int oneBased) {
            return arrivals.get(oneBased - 1);
        }
    }

    /**
     * 把 8 条一批经<b>真实 MLLP 帧</b>打进监听口，返回逐条落点与线上真实回执码。
     *
     * <p>回执码取自 socket 上真读到的 MSA 帧，而不是业务对象的推断值 ——
     * 断言「这条回的是 AE」必须以对端真正收到的那条为准。
     */
    private Batch pushEightMessages() throws Exception {
        List<String> er7s = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            er7s.add(er7(i, false));              // 5 条：结构正常，人工确认其中 3 条
        }
        er7s.add(er7(6, true));                    // 1 条：带 Z 段 → 模型不可达 → 降级 AE
        er7s.add(er7(7, true));                    // 1 条：同上
        er7s.add(arEr7());                         // 1 条：字符集不支持 → 拒收 AR

        List<Arrival> arrivals = new ArrayList<>();
        List<String> ackCodes = new ArrayList<>();
        try (MllpServer server = new MllpServer("127.0.0.1", 0, ingest, 10000);
             Socket client = new Socket()) {
            int port = server.start();
            client.connect(new InetSocketAddress("127.0.0.1", port), 5000);
            client.setSoTimeout(20000);
            OutputStream out = client.getOutputStream();
            InputStream in = client.getInputStream();

            for (String er7 : er7s) {
                out.write(MllpFrameCodec.encode(bytes(er7)));
                out.flush();
                assertEquals(MllpFrameCodec.ACK, in.read(), "每帧都应有传输级确认 0x06");
                byte[] ack = MllpFrameCodec.readFrame(in);
                assertNotNull(ack, "每帧都应有一条应用级回执");
                String msa1 = msa1Of(new String(ack, StandardCharsets.UTF_8));
                ackCodes.add(msa1);

                String controlId = controlIdOf(er7);
                LedgerRow row = store.findByIdempotencyKey(
                        IdempotencyKey.of(MESSAGE_TYPE, controlId, "P")).orElseThrow(
                        () -> new AssertionError("报文未入账: " + controlId));
                localAckCodes.put(controlId, msa1);
                arrivals.add(new Arrival(row.id(), controlId, msa1, row.state()));
            }
            assertEquals(8, server.receivedCount(), "监听口应收到 8 帧");
            assertEquals(8, server.ackedCount(), "8 帧都应各回一条应用级回执");
        }
        return new Batch(arrivals, ackCodes);
    }

    private static String msa1Of(String ackEr7) {
        for (String line : ackEr7.split("\r")) {
            if (line.startsWith("MSA")) {
                return line.split("\\|", -1)[1];
            }
        }
        return "";
    }

    /** 信息员确认前 3 条（每条一次院内患者主索引出站写）。 */
    private void confirmFirstThree(Batch batch) throws Exception {
        for (int i = 1; i <= 3; i++) {
            ConfirmOutcome outcome = confirmGate.confirm(batch.byIndex(i).ledgerId(),
                    ConfirmDecision.manual("operator-001", patientRef(i), localMrn(i), "已核对居民身份证"));
            assertTrue(outcome.confirmed(), "第 " + i + " 条确认应生效，拒绝原因=" + outcome.refusal());
            assertTrue(outcome.wrotePatientIndex());
        }
        assertEquals(3, patientIndex.invocationCount(), "3 条确认 → 出站写恰好 3 次");
    }

    private static String patientRef(int index) {
        return "PAT-20261020" + String.format("%03d", index);
    }

    private static String localMrn(int index) {
        return "MRN-2026" + String.format("%06d", index);
    }

    // =====================================================================
    // 验收用例
    // =====================================================================

    @Test
    @DisplayName("8 条批次端到端：四态分布为 已落地 3 / 待确认 2 / 降级 AE 2 / 拒收 AR 1")
    void test_batch_of_eight_yields_expected_four_state_distribution() throws Exception {
        Batch batch = pushEightMessages();

        assertEquals(List.of("AA", "AA", "AA", "AA", "AA", "AE", "AE", "AR"), batch.wireAckCodes(),
                "线上 8 条回执码：前 5 条 AA、2 条降级 AE、1 条字符集不支持 AR");
        assertEquals(MessageState.PENDING_CONFIRM, batch.byIndex(4).state());
        assertEquals(MessageState.PENDING_CONFIRM, batch.byIndex(6).state(),
                "降级不是第三种终局：仍停在待确认，只是 degraded 标记 + AE");
        assertEquals(MessageState.REJECTED, batch.byIndex(8).state());
        assertTrue(store.findById(batch.byIndex(6).ledgerId()).orElseThrow().degraded(),
                "降级条目在台账里应带 degraded 标记");

        confirmFirstThree(batch);

        Map<MessageState, Long> dist = store.countByState(BATCH_ID);
        assertEquals(3L, dist.get(MessageState.LANDED), "已落地 3");
        assertEquals(4L, dist.get(MessageState.PENDING_CONFIRM), "待确认 4（2 普通 + 2 降级）");
        assertEquals(1L, dist.get(MessageState.REJECTED), "拒收 1");
        assertEquals(8L, store.listByBatch(BATCH_ID, null).size(), "台账恰好 8 行");
    }

    @Test
    @DisplayName("对端缺 2 条回执 → 差集恰为那 2 条，其余 6 条全部对上")
    void test_reconcile_diff_is_exactly_the_two_missing() throws Exception {
        Batch batch = pushEightMessages();
        confirmFirstThree(batch);

        RemoteAckPage page = remotePageMissing(batch, 6, 7);
        assertEquals(6, page.actualAckCount());

        AckDiff diff = AckReconciler.diff(expectedOf(batch), page);
        assertEquals(2, diff.missingCount(), "缺失恰为 2 条");
        assertEquals(List.of("CTRL-20261020-0006", "CTRL-20261020-0007"),
                diff.missing().stream().map(m -> m.local().key().controlId()).toList(),
                "缺失清单就是那 2 条降级 AE");
        assertEquals(6, diff.matchedCount(), "其余 6 条全部对上");
        assertTrue(diff.unexpectedRemoteControlIds().isEmpty());
    }

    @Test
    @DisplayName("只重投那 2 条：对端回 AA → 台账 RETRY_CONFIRMED + 审计，院内主索引出站写仍为 3")
    void test_redeliver_only_missing_two_and_confirm_on_aa() throws Exception {
        Batch batch = pushEightMessages();
        confirmFirstThree(batch);
        long landedVersion = versionOf(batch.byIndex(1).ledgerId());

        RedeliveryCoordinator coordinator = coordinatorFor(platform);
        RedeliveryCoordinator.ReconcileOutcome outcome = coordinator.reconcile(BATCH_ID, remotePageMissing(batch, 6, 7));
        assertTrue(outcome.balanced(), "对账口径应自洽：" + outcome.toLogLine());
        assertEquals(2, outcome.enqueue().offered(), "2 条降级 AE 都应入队");
        assertEquals(0, outcome.enqueue().rejectedReasons().size());
        assertEquals(2L, queue.queuedCount());

        // 入队即把台账推进到「待重投」，两次重投都经真实出站 socket
        assertEquals(MessageState.PENDING_RETRY, stateOf(batch.byIndex(6).ledgerId()));
        assertEquals(MessageState.PENDING_RETRY, stateOf(batch.byIndex(7).ledgerId()));

        long now = FIXED_CLOCK.millis();
        for (int i = 0; i < 2; i++) {
            Optional<RedeliveryEntry> ticked = coordinator.tickAt(now);
            assertTrue(ticked.isPresent(), "第 " + (i + 1) + " 次调度应取到一条到期条目");
        }

        assertEquals(2, platform.receivedCount(), "平台侧真实收到 2 条重投");
        assertEquals(2, platform.connectionCount(), "每条重投一次建连");
        assertEquals(List.of("CTRL-20261020-0006", "CTRL-20261020-0007"),
                controlIdsInOrder(platform.receivedPayloads()),
                "重投出去的正是缺的那 2 条，且不改内容");

        for (int i = 6; i <= 7; i++) {
            assertEquals(MessageState.RETRY_CONFIRMED, stateOf(batch.byIndex(i).ledgerId()),
                    "重投成功的第 " + i + " 条应推进到 RETRY_CONFIRMED");
            List<String> chain = auditChain(batch.byIndex(i).ledgerId());
            assertTrue(chain.contains("PENDING_RETRY>RETRYING"),
                    "应有 待重投→重投中 审计：" + chain);
            assertTrue(chain.contains("RETRYING>RETRY_CONFIRMED"),
                    "应有 重投中→已确认 审计：" + chain);
        }
        assertEquals(0L, queue.queuedCount(), "两条都结算完毕，无残留待投");

        // 反例：其余 6 条一个字节没动
        for (int i = 1; i <= 5; i++) {
            Arrival a = batch.byIndex(i);
            MessageState expectedState = i <= 3 ? MessageState.LANDED : MessageState.PENDING_CONFIRM;
            assertEquals(expectedState, stateOf(a.ledgerId()),
                    "第 " + i + " 条状态不应被重投改动");
        }
        assertEquals(MessageState.REJECTED, stateOf(batch.byIndex(8).ledgerId()));
        assertEquals(landedVersion, versionOf(batch.byIndex(1).ledgerId()),
                "已落地行的版本号不应变化");
        assertEquals(3, patientIndex.invocationCount(),
                "重投不写院内患者主索引：确认过 3 条就应当恰好 3 次");
    }

    @Test
    @DisplayName("重投闭环：回执报文条数与待确认台账条数对得上（spec §3 验收故事）")
    void test_ack_count_matches_pending_ledger_count() throws Exception {
        Batch batch = pushEightMessages();
        confirmFirstThree(batch);

        RedeliveryCoordinator coordinator = coordinatorFor(platform);
        RedeliveryCoordinator.ReconcileOutcome outcome = coordinator.reconcile(BATCH_ID, remotePageMissing(batch, 6, 7));
        assertTrue(outcome.balanced(), "对账口径应自洽：" + outcome.toLogLine());

        long now = FIXED_CLOCK.millis();
        assertTrue(coordinator.tickAt(now).isPresent());
        assertTrue(coordinator.tickAt(now).isPresent());

        // ① 本端按批次发出的回执报文条数（线上真实收到 8 条 MSA） == 台账行数
        assertEquals(8, batch.wireAckCodes().size());
        assertEquals(8, store.listByBatch(BATCH_ID, null).size());

        // ② 对端视角：对上 6 + 缺失 2 == 本端发出的 8 条，一条不多一条不少
        AckDiff diff = AckReconciler.diff(expectedOf(batch), remotePageMissing(batch, 6, 7));
        assertEquals(8, diff.matchedCount() + diff.missingCount());
        assertEquals(batch.wireAckCodes().size(), diff.matchedCount() + diff.missingCount(),
                "回执报文条数与本端应发条数必须相等");

        // ③ 待确认台账条数 = 发出回执条数 − 已落地 − 拒收
        Map<MessageState, Long> dist = store.countByState(BATCH_ID);
        long pending = dist.getOrDefault(MessageState.PENDING_CONFIRM, 0L)
                + dist.getOrDefault(MessageState.PENDING_RETRY, 0L)
                + dist.getOrDefault(MessageState.RETRYING, 0L)
                + dist.getOrDefault(MessageState.RETRY_CONFIRMED, 0L)
                + dist.getOrDefault(MessageState.RETRY_PERMANENT_FAILED, 0L);
        long landed = dist.getOrDefault(MessageState.LANDED, 0L);
        long rejected = dist.getOrDefault(MessageState.REJECTED, 0L);
        assertEquals(batch.wireAckCodes().size(), landed + rejected + pending,
                "已落地 " + landed + " + 拒收 " + rejected + " + 待确认 " + pending
                        + " 应等于回执条数");
        assertEquals(4L, pending, "4 条仍待人工处理（2 普通 + 2 已重投待确认落地）");
        assertEquals(3L, landed);
        assertEquals(1L, rejected);
    }

    // =====================================================================
    // 失败路径：闭环不能把失败吞掉
    // =====================================================================

    @Test
    @DisplayName("对端回 AR：重投收敛为永久失败，台账 RETRY_PERMANENT_FAILED，队列不再自动重投")
    void test_remote_ar_becomes_permanent_failure() throws Exception {
        Batch batch = pushEightMessages();
        confirmFirstThree(batch);

        platform.setMsaCode(AckComposer.CODE_AR);
        RedeliveryCoordinator coordinator = coordinatorFor(platform);
        coordinator.reconcile(BATCH_ID, remotePageMissing(batch, 6, 7));
        coordinator.tickAt(FIXED_CLOCK.millis());
        coordinator.tickAt(FIXED_CLOCK.millis());

        for (int i = 6; i <= 7; i++) {
            assertEquals(MessageState.RETRY_PERMANENT_FAILED, stateOf(batch.byIndex(i).ledgerId()),
                    "对端回 AR → 永久失败，等人工放行");
            assertTrue(auditChain(batch.byIndex(i).ledgerId())
                            .contains("RETRYING>RETRY_PERMANENT_FAILED"),
                    "应有 重投中→永久失败 审计");
        }
        assertEquals(0L, queue.queuedCount(), "AR 不重投：队列里不该再有可投条目");
        assertEquals(3, patientIndex.invocationCount());

        // 人工放行 → 台账退回待重投，队列重新有 1 条
        RedeliveryEntry entry = queue.listByLedger(batch.byIndex(6).ledgerId()).get(0);
        coordinator.manualRelease(entry.id(), entry.ledgerId(), FIXED_CLOCK.millis(), "operator-002");
        assertEquals(MessageState.PENDING_RETRY, stateOf(batch.byIndex(6).ledgerId()));
        assertEquals(1L, queue.queuedCount());
    }

    @Test
    @DisplayName("传输级 NAK：重投按可重试处理，退避后回到待重投，不会被算成已确认")
    void test_transport_nak_is_retryable_and_ledger_returns_to_pending_retry() throws Exception {
        Batch batch = pushEightMessages();
        confirmFirstThree(batch);

        platform.setNakTransport(true);
        RedeliveryCoordinator coordinator = coordinatorFor(platform);
        coordinator.reconcile(BATCH_ID, remotePageMissing(batch, 6, 7));
        coordinator.tickAt(FIXED_CLOCK.millis());

        assertEquals(MessageState.PENDING_RETRY, stateOf(batch.byIndex(6).ledgerId()),
                "NAK 是可重试：台账退回待重投，不得停在重投中");
        assertTrue(auditChain(batch.byIndex(6).ledgerId()).contains("RETRYING>PENDING_RETRY"),
                "应有 重投中→待重投 审计（否则队列与台账劈叉）");
        assertEquals(2L, queue.queuedCount(), "两条都还在队列：entry 6 被退避后重新入队（attempt+1、未来到期），entry 7 还没轮到调度");

        // 平台恢复回 AA 后，下一次调度成功。
        // 队列里两条都到期，tickAt 只取一条（队首）；如果 NAK 那条（entry 6）这次先取到则它进 RETRY_CONFIRMED，
        // 否则 entry 7 先取到、entry 6 仍待 retry。但「NAK 不锁死成永久失败」这条已由上述断言验收。
        platform.setNakTransport(false);
        coordinator.tickAt(FIXED_CLOCK.millis() + 60_000L);
        // 至少有一条已落地（具体哪条取决于调度序），证明 NAK 不再锁成永久失败
        long landed6 = stateOf(batch.byIndex(6).ledgerId()) == MessageState.RETRY_CONFIRMED ? 1L : 0L;
        long landed7 = stateOf(batch.byIndex(7).ledgerId()) == MessageState.RETRY_CONFIRMED ? 1L : 0L;
        assertEquals(1L, landed6 + landed7, "NAK 解除后至少有一条重投成功转为已确认");
    }

    @Test
    @DisplayName("对端裸方言（帧里直接是应用级回执、无传输级 0x06）同样判得出 AA")
    void test_bare_ack_dialect_peer_is_understood() throws Exception {
        Batch batch = pushEightMessages();
        confirmFirstThree(batch);

        platform.setDialect(RegionalPlatformMllpStub.Dialect.FRAME_ONLY);
        RedeliveryCoordinator coordinator = coordinatorFor(platform);
        coordinator.reconcile(BATCH_ID, remotePageMissing(batch, 6, 7));
        coordinator.tickAt(FIXED_CLOCK.millis());

        assertEquals(MessageState.RETRY_CONFIRMED, stateOf(batch.byIndex(6).ledgerId()),
                "裸方言回执也应被解析出 MSA-1=AA");
    }

    @Test
    @DisplayName("条目在待重投期间：确认门按当前态拒绝，重投路径照常推进，两条路不会同时推进同一条")
    void test_confirm_is_refused_while_entry_sits_in_retry_path() throws Exception {
        Batch batch = pushEightMessages();
        RedeliveryCoordinator coordinator = coordinatorFor(platform);
        coordinator.reconcile(BATCH_ID, remotePageMissing(batch, 6, 7));
        assertEquals(MessageState.PENDING_RETRY, stateOf(batch.byIndex(6).ledgerId()));

        ConfirmOutcome outcome = confirmGate.confirm(batch.byIndex(6).ledgerId(),
                ConfirmDecision.manual("operator-001", patientRef(6), localMrn(6), "已核对"));
        assertFalse(outcome.confirmed(), "待重投不是待确认，确认门应拒绝");
        assertEquals(ConfirmGate.Refusal.NOT_PENDING_CONFIRM, outcome.refusal());
        assertEquals(0, patientIndex.invocationCount(), "被拒的确认不得触发出站写");

        coordinator.tickAt(FIXED_CLOCK.millis());
        assertEquals(MessageState.RETRY_CONFIRMED, stateOf(batch.byIndex(6).ledgerId()),
                "重投路径不受影响：平台回 AA 后仍推进到已确认");
    }

    // =====================================================================
    // 装配与断言辅助
    // =====================================================================

    private RedeliveryCoordinator coordinatorFor(RegionalPlatformMllpStub peer) {
        RedeliveryAction action = new MllpRedeliveryAction(
                MllpRedeliveryAction.PayloadSource.fromLedger(store), peer.host(), peer.port(),
                3000, 5000);
        return new RedeliveryCoordinator(store, store, queue, action,
                new ExponentialBackoff(1000L, 60_000L, 0.0D, 42L), 3, FIXED_CLOCK,
                key -> localAckCodes.getOrDefault(key.controlId(), ""));
    }

    private List<LocalExpectedAck> expectedOf(Batch batch) throws SQLException {
        List<LocalExpectedAck> expected = new ArrayList<>();
        for (Arrival a : batch.arrivals()) {
            LedgerRow row = store.findById(a.ledgerId()).orElseThrow();
            expected.add(new LocalExpectedAck(row.key(), row.id(), row.batchId(), row.state(),
                    localAckCodes.getOrDefault(a.controlId(), "")));
        }
        return expected;
    }

    /** 造一页对端回执：{@code missingIndexes} 里的条目不在其中，其余全在。 */
    private RemoteAckPage remotePageMissing(Batch batch, int... missingIndexes) {
        List<String> missing = new ArrayList<>();
        for (int idx : missingIndexes) {
            missing.add(batch.byIndex(idx).controlId());
        }
        List<RemoteAck> acks = new ArrayList<>();
        for (Arrival a : batch.arrivals()) {
            if (!missing.contains(a.controlId())) {
                acks.add(new RemoteAck(a.controlId(), MESSAGE_TYPE, a.ackCode(), FIXED_CLOCK.instant()));
            }
        }
        return new RemoteAckPage(BATCH_ID, acks.size(), acks, false);
    }

    /** 平台侧收到的重投报文按序取控制号。 */
    private static List<String> controlIdsInOrder(List<String> payloads) {
        List<String> ids = new ArrayList<>();
        for (String er7 : payloads) {
            ids.add(er7.split("\r")[0].split("\\|", -1)[9]);
        }
        return ids;
    }

    private MessageState stateOf(long ledgerId) throws SQLException {
        return store.findById(ledgerId).orElseThrow().state();
    }

    private long versionOf(long ledgerId) throws SQLException {
        return store.findById(ledgerId).orElseThrow().version();
    }

    /** 审计链的 from>to 序列（按写入顺序）。 */
    private List<String> auditChain(long ledgerId) throws SQLException {
        List<String> chain = new ArrayList<>();
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT from_state, to_state FROM message_audit WHERE ledger_id = ? ORDER BY id")) {
            ps.setLong(1, ledgerId);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    chain.add(rs.getString(1) + ">" + rs.getString(2));
                }
            }
        }
        return chain;
    }

    /** 模型环节的替身：一定会失败，用来造真实的降级路径（不是 mock 框架）。 */
    private static final class AlwaysFailingGateway implements ModelGateway {
        @Override
        public ZFieldMap alignZFields(List<ZSegment> zSegments) {
            throw new ModelCallException(ModelErrorCode.CONNECT_TIMEOUT, "测试：模型端点不可达");
        }

        @Override
        public Candidates identityCandidates(String pid3, String pid5, String pid7,
                                             LocalPatientIndex localIndex) {
            throw new ModelCallException(ModelErrorCode.CONNECT_TIMEOUT, "测试：模型端点不可达");
        }
    }
}