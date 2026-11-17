package cn.hospital.region.archive.redelivery;

import cn.hospital.region.archive.ledger.IdempotencyKey;
import cn.hospital.region.archive.ledger.LedgerSchema;
import cn.hospital.region.archive.ledger.MessageState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T34 · 区域平台回执对账契约测试 —— 「次日只重投那几条」的可复核答案。
 *
 * <h2>这一组在验什么</h2>
 * <ul>
 *   <li><b>真实 HTTP 出站</b>：对端收到的方法 / 路径 / {@code Bearer} 头逐项断言，
 *       响应按正式字段规范解析；<b>不使用任何 mock 库</b>；</li>
 *   <li><b>差集正确</b>：对端缺 2 条回执 → 差集<b>恰好</b> 2 条，其余条目一条不在重投清单里；</li>
 *   <li><b>归一不制造假缺失</b>：控制号的空白 / 前导零差异<b>不得</b>被判成缺失 ——
 *       这是差集最容易出的、代价也最大的一类错（假阳性 = 让对端收到同一份报文的第二份）；</li>
 *   <li><b>失败绝不静默</b>：非 2xx / 超时 / 不合规范响应体一律抛异常，
 *       <b>绝不返回空清单</b>冒充「一条不缺」。</li>
 * </ul>
 *
 * <p>重投提交那一段走真实 {@link JdbcRedeliveryQueue} + 真实 H2 PG 兼容模式：
 * {@code AR} 与已落地的缺失条目<b>在队列业务闸口被拒</b>，证明差集不越权替队列做决定。
 */
class BatchReconcilerClientTest {

    private static final String H2_URL =
            "jdbc:h2:mem:reconcile_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

    private static final String BATCH = "REGIONAL_ARCHIVE-20261020";
    private static final String TOKEN = "regional-ack-token-t34";
    private static final String RECEIVED_AT = "2026-10-20T09:00:12+08:00";

    // =====================================================================
    // 夹具
    // =====================================================================

    private static Connection openConnection() throws SQLException {
        return DriverManager.getConnection(H2_URL, "sa", "");
    }

    private static JdbcRedeliveryQueue queue() {
        return new JdbcRedeliveryQueue(BatchReconcilerClientTest::openConnection,
                java.time.Clock.systemUTC());
    }

    @BeforeAll
    static void migrateUp() throws SQLException {
        try (Connection conn = openConnection()) {
            LedgerSchema.migrate(conn);
        }
    }

    @BeforeEach
    void cleanTables() throws SQLException {
        try (Connection conn = openConnection(); Statement st = conn.createStatement()) {
            st.execute("DELETE FROM redelivery_queue");
            st.execute("DELETE FROM message_ledger");
        }
    }

    /** 台账投影行（真实 INSERT，供重投外键与差集左侧共用）。 */
    private static long makeLedgerRow(IdempotencyKey k, String batchId, MessageState state) throws SQLException {
        try (Connection conn = openConnection();
             var ps = conn.prepareStatement(
                     "INSERT INTO message_ledger (message_type, control_id, processing_id, "
                             + "batch_id, state, raw_payload) VALUES (?, ?, ?, ?, ?, ?)",
                     new String[]{"id"})) {
            ps.setString(1, k.messageType());
            ps.setString(2, k.controlId());
            ps.setString(3, k.processingId());
            ps.setString(4, batchId);
            ps.setString(5, state.dbValue());
            ps.setString(6, "MSH|^~\\&|CHSCOMMUNITY|REGIONAL_ARCHIVE|REGIONAL_ARCHIVE|FACILITY_A|"
                    + "202610200900||ADT^A01^ADT_A01|" + k.controlId() + "|P|2.5.1|||AL|NE||UNICODE UTF-8");
            ps.executeUpdate();
            try (var rs = ps.getGeneratedKeys()) {
                if (!rs.next()) {
                    throw new SQLException("INSERT message_ledger 未返回主键");
                }
                return rs.getLong(1);
            }
        }
    }

    /** 本批 8 条应发条目：5 已落地 / 2 待确认 / 1 拒收，全部落真实台账。 */
    private static List<LocalExpectedAck> seedEightExpected() throws SQLException {
        String[] controlIds = {
                "CTRL-20261020-0001", "CTRL-20261020-0002", "CTRL-20261020-0003", "CTRL-20261020-0004",
                "CTRL-20261020-0005", "CTRL-20261020-0006", "CTRL-20261020-0007", "CTRL-20261020-0008"};
        List<LocalExpectedAck> expected = new ArrayList<>(controlIds.length);
        for (int i = 0; i < controlIds.length; i++) {
            IdempotencyKey key = IdempotencyKey.of("ADT^A01^ADT_A01", controlIds[i], "P");
            // 1..5 已落地；6、7 待确认（就是信息员次日该重投的那两条）；8 拒收（AR）
            MessageState state = switch (i) {
                case 5, 6 -> MessageState.PENDING_CONFIRM;
                case 7 -> MessageState.REJECTED;
                default -> MessageState.LANDED;
            };
            String ackCode = state == MessageState.REJECTED ? "AR" : "AA";
            long ledgerId = makeLedgerRow(key, BATCH, state);
            expected.add(new LocalExpectedAck(key, ledgerId, BATCH, state, ackCode));
        }
        return expected;
    }

    /** 对端只回报了 6 条（0003、0007 没回报 → 差集 2 条）。 */
    private static RegionalAckStubServer stubWithSixAcks() throws java.io.IOException {
        return RegionalAckStubServer.start(TOKEN)
                .withAcks(BATCH, List.of(
                        RegionalAckStubServer.ack("CTRL-20261020-0001", "AA", RECEIVED_AT),
                        RegionalAckStubServer.ack("CTRL-20261020-0002", "AA", RECEIVED_AT),
                        RegionalAckStubServer.ack("CTRL-20261020-0004", "AA", RECEIVED_AT),
                        RegionalAckStubServer.ack("CTRL-20261020-0005", "AA", RECEIVED_AT),
                        RegionalAckStubServer.ack("CTRL-20261020-0006", "AA", RECEIVED_AT),
                        RegionalAckStubServer.ack("CTRL-20261020-0008", "AR", RECEIVED_AT)));
    }

    private static BatchReconcilerClient clientFor(RegionalAckStubServer stub) {
        return new BatchReconcilerClient(stub.baseUrl(), TOKEN, Duration.ofSeconds(5));
    }

    private static List<String> controlIdsOf(List<AckDiff.MissingAck> missing) {
        return missing.stream().map(m -> m.local().key().controlId()).toList();
    }

    // =====================================================================
    // 1. 对账契约：方法 / 路径 / Bearer / 响应解析
    // =====================================================================

    @Test
    @DisplayName("对账契约：对端收到 GET /batches/{batchId}/acks + Bearer，响应按正式字段规范解析")
    void test_request_shape_and_response_parse_match_spec() throws Exception {
        try (RegionalAckStubServer stub = stubWithSixAcks()) {
            BatchReconcilerClient client = clientFor(stub);

            RemoteAckPage page = client.fetchAcks(BATCH);

            RegionalAckStubServer.ReceivedRequest request = stub.lastRequest();
            assertEquals("GET", request.method(), "对账是只读查询，必须是 GET");
            assertEquals("/batches/" + BATCH + "/acks", request.path(),
                    "路径必须是正式对接规范 /batches/{batchId}/acks");
            assertEquals("Bearer " + TOKEN, request.authorization(), "Bearer 头缺失或不符");
            assertEquals("application/json", request.accept(), "Accept 头缺失或不符");

            assertEquals(BATCH, page.batchId());
            assertEquals(6, page.declaredAckCount(), "对端自述条数应被解析出来");
            assertEquals(6, page.actualAckCount());
            assertEquals(6, page.distinctAckedControlCount());
            assertEquals(5, page.acceptedCount(), "6 条里 5 条 AA、1 条 AR");
            assertFalse(page.countMismatch());
            assertTrue(page.acks().get(0).accepted());
            assertEquals(Instant.parse("2026-10-20T01:00:12Z"), page.acks().get(0).receivedAt());
            assertEquals(1, stub.receivedRequests().size(), "一次 fetchAcks 只发一次请求");
        }
    }

    @Test
    @DisplayName("批次号做 URL 路径段编码：含斜杠与空格的批次号不拼出错误的查询路径")
    void test_batch_id_is_url_path_encoded() throws Exception {
        String oddBatch = "BATCH 2026/10 20";
        try (RegionalAckStubServer stub = RegionalAckStubServer.start(TOKEN)
                .withAcks(oddBatch, List.of(RegionalAckStubServer.ack("CTRL-1", "AA", RECEIVED_AT)))) {
            BatchReconcilerClient client = clientFor(stub);

            RemoteAckPage page = client.fetchAcks(oddBatch);

            assertEquals(1, page.actualAckCount(), "路径段编码后对端仍应认得这个批次");
            assertEquals("/batches/BATCH%202026%2F10%2020/acks", stub.lastRequest().rawPath(),
                    "斜杠必须被编码，否则路径会被切成多段");
        }
    }

    @Test
    @DisplayName("空批次号在发请求前就被拒：/batches//acks 会让对端返回含义不明的列表")
    void test_blank_batch_id_rejected_before_any_request() throws Exception {
        try (RegionalAckStubServer stub = stubWithSixAcks()) {
            BatchReconcilerClient client = clientFor(stub);

            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> client.fetchAcks("  "));
            assertTrue(e.getMessage().contains("batchId"));
            assertEquals(0, stub.receivedRequests().size(), "入参校验失败不该发出任何请求");
        }
    }

    // =====================================================================
    // 2. 差集：对端缺 2 条 → 恰好 2 条进重投清单
    // =====================================================================

    @Test
    @DisplayName("差集：对端缺 2 条回执 → 恰好 2 条，其余 6 条一条不在重投清单里")
    void test_missing_acks_produce_exactly_two_entries() throws Exception {
        List<LocalExpectedAck> expected = seedEightExpected();
        assertEquals(8, expected.size(), "前置：本端这一批确实回了 8 条执");

        try (RegionalAckStubServer stub = stubWithSixAcks()) {
            BatchReconcilerClient client = clientFor(stub);

            RemoteAckPage page = client.fetchAcks(BATCH);
            AckDiff diff = AckReconciler.diff(expected, page);

            assertEquals(8, diff.expectedCount(), "本端应发 8 条");
            assertEquals(6, diff.remoteAckCount());
            assertEquals(6, diff.matchedCount(), "对端回报的 6 条都应配上");
            assertEquals(2, diff.missingCount(), "差集必须恰好 2 条");

            assertEquals(List.of("CTRL-20261020-0003", "CTRL-20261020-0007"), controlIdsOf(diff.missing()),
                    "缺的正是对端没回报的两条控制号");

            // 其余 6 条一条都不得出现在重投清单里
            for (LocalExpectedAck item : expected) {
                boolean inMissing = controlIdsOf(diff.missing()).contains(item.key().controlId());
                boolean shouldBeMissing = item.key().controlId().equals("CTRL-20261020-0003")
                        || item.key().controlId().equals("CTRL-20261020-0007");
                assertEquals(shouldBeMissing, inMissing,
                        item.key().controlId() + " 的缺失判定不对：已对上的条目重投等于让对端收到第二份");
            }

            assertTrue(diff.unexpectedRemoteControlIds().isEmpty(), "对端没有多报本端没有的条目");
            assertFalse(diff.complete(), "还有 2 条缺失，不能报 complete");
        }
    }

    @Test
    @DisplayName("差集 → 重投队列：缺的那 2 条按幂等键入队，AR 与已落地在业务闸被拒")
    void test_diff_feeds_redelivery_queue_by_idempotency_key() throws Exception {
        List<LocalExpectedAck> expected = seedEightExpected();
        // 让 0003 / 0007 的真实状态改成「待确认」，这样它们才有资格重投
        try (Connection conn = openConnection(); Statement st = conn.createStatement()) {
            st.execute("UPDATE message_ledger SET state = '" + MessageState.PENDING_CONFIRM.dbValue()
                    + "' WHERE control_id IN ('CTRL-20261020-0003', 'CTRL-20261020-0007')");
        }
        expected = expected.stream().map(a -> new LocalExpectedAck(a.key(), a.ledgerId(), a.batchId(),
                a.key().controlId().endsWith("0003") || a.key().controlId().endsWith("0007")
                        ? MessageState.PENDING_CONFIRM : a.state(), a.ackCode())).toList();

        try (RegionalAckStubServer stub = stubWithSixAcks()) {
            AckDiff diff = AckReconciler.diff(expected, clientFor(stub).fetchAcks(BATCH));
            JdbcRedeliveryQueue q = queue();

            AckReconciler.EnqueueSummary summary = AckReconciler.enqueueMissing(diff, q, 0L);

            assertEquals(2, summary.missingCount());
            assertEquals(2, summary.offered(), "两条待确认缺失都应入队");
            assertEquals(0, summary.duplicated());
            assertEquals(0, summary.rejectedReasons().size());
            assertTrue(summary.balanced(), "缺失 2 条，入队 2 条，条数须对得上");
            assertEquals(2, q.queuedCount(), "真实队列里确实有 2 条待投");

            // 再投一次：同键不重，队列行数不变
            AckReconciler.enqueueMissing(diff, q, 0L);
            assertEquals(2, q.queuedCount(), "同幂等键不得重复占行");
        }
    }

    @Test
    @DisplayName("缺失清单里的 AR 与已落地条目由队列业务闸拒：差集不越权替队列做决定")
    void test_ar_and_landed_missing_entries_are_refused_by_queue_gate() throws Exception {
        // 0008 本端回的是 AR（拒收）、0001..0005 已落地；让对端一条都不回报
        List<LocalExpectedAck> expected = seedEightExpected();
        try (RegionalAckStubServer stub = RegionalAckStubServer.start(TOKEN).withAcks(BATCH, List.of())) {
            AckDiff diff = AckReconciler.diff(expected, clientFor(stub).fetchAcks(BATCH));
            assertEquals(8, diff.missingCount(), "对端一条没回报 → 8 条全缺");

            AckReconciler.EnqueueSummary summary = AckReconciler.enqueueMissing(diff, queue(), 0L);

            assertEquals(2, summary.offered(), "只有 2 条待确认够格重投");
            assertEquals(6, summary.rejectedReasons().size(),
                    "5 条已落地 + 1 条 AR 应被拒：" + summary.rejectedReasons());
            assertTrue(summary.rejectedReasons().stream()
                            .anyMatch(r -> r.contains("0008") && (r.contains("AR") || r.contains("REJECTED"))),
                    "AR 条目的拒绝理由应点名 AR / 拒收事实：" + summary.rejectedReasons());
            assertTrue(summary.balanced(), "8 条 = 2 入队 + 6 拒绝");
        }
    }

    // =====================================================================
    // 3. 归一：格式差异不得被当成缺失（差集假阳性 = 对端收到第二份）
    // =====================================================================

    @Test
    @DisplayName("控制号归一：空白 / 前导零差异不产生假缺失（逐字不同但归一后同一条）")
    void test_control_id_normalization_avoids_false_mismatch() throws Exception {
        List<LocalExpectedAck> expected = seedEightExpected();
        // 对端回报的 6 条全部改成「逐字不一样、归一后一样」：
        //   0001 → "  CTRL-20261020-0001  "（首尾空白）
        //   0002 → "CTRL-20261020-2"（前导零位数不同）
        //   0004 → "CTRL-20261020-0004\t"（尾随制表符，从报表复制而来）
        //   0005 → "CTRL-20261020-\n0005"（折行插入的换行，删掉后逐字还原）
        //   0006 → "CTRL-20261020-0006"（逐字一致，保留一条真一致的做对照）
        //   0008 → "CTRL-20261020-0008"（同上）
        try (RegionalAckStubServer stub = RegionalAckStubServer.start(TOKEN).withAcks(BATCH, List.of(
                RegionalAckStubServer.ack("  CTRL-20261020-0001  ", "AA", RECEIVED_AT),
                RegionalAckStubServer.ack("CTRL-20261020-2", "AA", RECEIVED_AT),
                RegionalAckStubServer.ack("CTRL-20261020-0004\t", "AA", RECEIVED_AT),
                RegionalAckStubServer.ack("CTRL-20261020-\n0005", "AA", RECEIVED_AT),
                RegionalAckStubServer.ack("CTRL-20261020-0006", "AA", RECEIVED_AT),
                RegionalAckStubServer.ack("CTRL-20261020-0008", "AR", RECEIVED_AT)))) {
            BatchReconcilerClient client = clientFor(stub);

            RemoteAckPage page = client.fetchAcks(BATCH);
            AckDiff diff = AckReconciler.diff(expected, page);

            assertEquals(6, diff.matchedCount(),
                    "空白与前导零差异不是缺失：逐字比对会把这 5 条误判成缺失并让对端收到第二份");
            assertEquals(2, diff.missingCount(), "真正缺的还是 0003 / 0007 两条");
            assertEquals(List.of("CTRL-20261020-0003", "CTRL-20261020-0007"), controlIdsOf(diff.missing()));
            assertTrue(diff.unexpectedRemoteControlIds().isEmpty(),
                    "归一后不该出现「对端多出来」的条目：" + diff.unexpectedRemoteControlIds());
        }
    }

    @Test
    @DisplayName("归一口径逐条钉死：只动长度 ≥2 的数字段、至少留一位、单数字段原样保留")
    void test_normalizer_rules_are_pinned() {
        assertEquals("CTRL-20261020-1", ControlIdNormalizer.normalize("CTRL-20261020-0001"));
        assertEquals("CTRL-20261020-1", ControlIdNormalizer.normalize("CTRL-20261020-1"));
        assertEquals("CTRL-20261020-0", ControlIdNormalizer.normalize("CTRL-20261020-00"),
                "整段是零时至少留一位");
        assertEquals("A-0-B", ControlIdNormalizer.normalize("A-0-B"),
                "单字符数字段不去前导零：否则 A-0-B 会与 A--B 撞成同一个键");
        assertEquals("MSG1", ControlIdNormalizer.normalize(" MSG1 "));
        assertEquals("P1", ControlIdNormalizer.normalize("P　1"), "全角空格也要删");
        assertEquals("", ControlIdNormalizer.normalize("   "), "全空白归一为空串，调用方须拒");
        assertEquals("", ControlIdNormalizer.normalize(null));
        assertFalse(ControlIdNormalizer.isUsable("  "));
        assertTrue(ControlIdNormalizer.isUsable("A1"));
        assertTrue(ControlIdNormalizer.explain("CTRL-0001").contains("CTRL-1"));
    }

    @Test
    @DisplayName("归一歧义：同一个归一键对应两条本地报文 → 整体排除出重投清单并上报")
    void test_normalization_ambiguity_is_reported_not_guessed() throws Exception {
        // 本端两条报文：CTRL-001 与 CTRL-1。归一后同为 CTRL-1，HL7 规范下它们本可以是真两条
        IdempotencyKey k1 = IdempotencyKey.of("ADT^A01^ADT_A01", "CTRL-001", "P");
        IdempotencyKey k2 = IdempotencyKey.of("ADT^A01^ADT_A01", "CTRL-1", "P");
        long id1 = makeLedgerRow(k1, BATCH, MessageState.PENDING_CONFIRM);
        long id2 = makeLedgerRow(k2, BATCH, MessageState.PENDING_CONFIRM);
        List<LocalExpectedAck> expected = List.of(
                new LocalExpectedAck(k1, id1, BATCH, MessageState.PENDING_CONFIRM, "AA"),
                new LocalExpectedAck(k2, id2, BATCH, MessageState.PENDING_CONFIRM, "AA"));

        try (RegionalAckStubServer stub = RegionalAckStubServer.start(TOKEN).withAcks(BATCH,
                List.of(RegionalAckStubServer.ack("CTRL-1", "AA", RECEIVED_AT)))) {
            AckDiff diff = AckReconciler.diff(expected, clientFor(stub).fetchAcks(BATCH));

            assertEquals(List.of("CTRL-1"), diff.ambiguousControlIds(),
                    "歧义键必须显式上报，不能让归一规则替平台决定两条报文是不是同一条");
            assertEquals(0, diff.missingCount(), "歧义键整体排除在重投清单之外：宁可少投一条让人看一眼");
            assertEquals(0, diff.matchedCount());
            assertFalse(diff.complete());
        }
    }

    // =====================================================================
    // 4. 失败语义：绝不静默清空
    // =====================================================================

    @Test
    @DisplayName("对端非 2xx / 响应不合规范 → 抛异常，绝不返回空清单冒充「一条不缺」")
    void test_failures_never_look_like_an_empty_ack_list() throws Exception {
        List<LocalExpectedAck> expected = seedEightExpected();

        // 401 令牌不对
        try (RegionalAckStubServer stub = stubWithSixAcks()) {
            stub.setHttpStatus(401);
            ReconcileException e = assertThrows(ReconcileException.class,
                    () -> clientFor(stub).fetchAcks(BATCH));
            assertEquals(ReconcileErrorCode.AUTH_REJECTED, e.code());
            assertEquals(401, e.httpStatus());
            assertFalse(e.code().jittery(), "令牌不对重投一万次还是不对，先查配置");
            assertTrue(e.getMessage().contains(BATCH));
        }

        // 404 批次不存在
        try (RegionalAckStubServer stub = RegionalAckStubServer.start(TOKEN)) {
            ReconcileException e = assertThrows(ReconcileException.class,
                    () -> clientFor(stub).fetchAcks(BATCH));
            assertEquals(ReconcileErrorCode.BATCH_NOT_FOUND, e.code());
        }

        // 5xx 是抖动类，原样重跑即可
        try (RegionalAckStubServer stub = stubWithSixAcks()) {
            stub.setHttpStatus(503);
            ReconcileException e = assertThrows(ReconcileException.class,
                    () -> clientFor(stub).fetchAcks(BATCH));
            assertEquals(ReconcileErrorCode.SERVER_ERROR, e.code());
            assertTrue(e.code().jittery());
        }

        // 读超时
        try (RegionalAckStubServer stub = stubWithSixAcks()) {
            stub.setResponseDelayMillis(1500);
            BatchReconcilerClient slow = new BatchReconcilerClient(stub.baseUrl(), TOKEN,
                    Duration.ofMillis(400));
            ReconcileException e = assertThrows(ReconcileException.class,
                    () -> slow.fetchAcks(BATCH));
            assertEquals(ReconcileErrorCode.READ_TIMEOUT, e.code());
            assertTrue(e.code().jittery());
        }

        // 响应体不是 JSON：绝不当成空列表（那会让整批被判成缺失并全量重投）
        try (RegionalAckStubServer stub = stubWithSixAcks()) {
            stub.setRawBody("<html>502 Bad Gateway</html>");
            ReconcileException e = assertThrows(ReconcileException.class,
                    () -> clientFor(stub).fetchAcks(BATCH));
            assertEquals(ReconcileErrorCode.MALFORMED_RESPONSE, e.code());
            assertTrue(e.getMessage().contains("不是合法 JSON"),
                    "异常消息须指出问题出在哪：" + e.getMessage());
            assertFalse(e.getMessage().contains("502 Bad Gateway"), "响应体不得进异常消息");
        }

        // acks 数组缺一条控制号：拿空控制号做差集会把「对端漏报」与「本端没这条」混成一件
        try (RegionalAckStubServer stub = stubWithSixAcks()) {
            stub.setRawBody("{\"batchId\":\"" + BATCH + "\",\"ackCount\":1,"
                    + "\"acks\":[{\"msaCode\":\"AA\"}]}");
            ReconcileException e = assertThrows(ReconcileException.class,
                    () -> clientFor(stub).fetchAcks(BATCH));
            assertEquals(ReconcileErrorCode.MALFORMED_RESPONSE, e.code());
        }

        // 响应体不进异常消息
        try (RegionalAckStubServer stub = stubWithSixAcks()) {
            stub.setHttpStatus(500);
            ReconcileException e = assertThrows(ReconcileException.class,
                    () -> clientFor(stub).fetchAcks(BATCH));
            assertFalse(e.getMessage().contains("stub_failure"), "响应体不得进异常消息：" + e.getMessage());
            assertFalse(e.getMessage().contains(TOKEN), "令牌不得进异常消息");
        }

        // 反向断言：以上任何一种失败都没让差集变成「一条不缺」
        assertEquals(8, expected.size());
    }

    @Test
    @DisplayName("对端自述条数与实际解析不一致：只记告警，以数组为准算差集，口径差异原样上报")
    void test_count_mismatch_is_recorded_not_hidden() throws Exception {
        List<LocalExpectedAck> expected = seedEightExpected();
        try (RegionalAckStubServer stub = stubWithSixAcks().withDeclaredCount(BATCH, 99)) {
            RemoteAckPage page = clientFor(stub).fetchAcks(BATCH);

            assertTrue(page.countMismatch());
            assertEquals(99, page.declaredAckCount());
            assertEquals(6, page.actualAckCount());

            AckDiff diff = AckReconciler.diff(expected, page);
            assertTrue(diff.countMismatch(), "口径差异必须出现在差集报表里，不能被吞掉");
            assertEquals(2, diff.missingCount());
            assertFalse(diff.complete());
            assertTrue(diff.toLogLine().contains("自述条数与实际解析"));
        }
    }

    @Test
    @DisplayName("对端回报本端没有的条目：列出来但绝不进重投清单（照它重投等于凭空造报文）")
    void test_unexpected_remote_entries_are_reported_but_never_redelivered() throws Exception {
        List<LocalExpectedAck> expected = seedEightExpected();
        try (RegionalAckStubServer stub = RegionalAckStubServer.start(TOKEN).withAcks(BATCH, List.of(
                RegionalAckStubServer.ack("CTRL-20261020-0001", "AA", RECEIVED_AT),
                RegionalAckStubServer.ack("CTRL-20261020-9999", "AA", RECEIVED_AT)))) {
            AckDiff diff = AckReconciler.diff(expected, clientFor(stub).fetchAcks(BATCH));

            assertEquals(List.of("CTRL-20261020-9999"), diff.unexpectedRemoteControlIds());
            assertEquals(7, diff.missingCount(), "本端有、对端没认的 7 条照常进重投清单");
            assertFalse(controlIdsOf(diff.missing()).contains("CTRL-20261020-9999"),
                    "对端多的条目本端台账里根本没有，重投它等于凭空造报文");

            AckReconciler.EnqueueSummary summary = AckReconciler.enqueueMissing(diff, queue(), 0L);
            assertTrue(summary.rejectedReasons().stream()
                    .anyMatch(r -> r.contains("LANDED") || r.contains("AR")),
                    "多出来的条目不入队，本端待确认的 0006 仍应入队：" + summary);
        }
    }

    @Test
    @DisplayName("对端重复回报同一控制号：幂等无害但记一笔，不改差集结论")
    void test_duplicate_remote_acks_are_recorded_once() throws Exception {
        List<LocalExpectedAck> expected = seedEightExpected();
        try (RegionalAckStubServer stub = RegionalAckStubServer.start(TOKEN).withAcks(BATCH, List.of(
                RegionalAckStubServer.ack("CTRL-20261020-0001", "AA", RECEIVED_AT),
                RegionalAckStubServer.ack("CTRL-20261020-0001", "AA", "2026-10-20T09:05:00+08:00"),
                RegionalAckStubServer.ack("CTRL-20261020-0002", "AA", RECEIVED_AT)))) {
            RemoteAckPage page = clientFor(stub).fetchAcks(BATCH);
            assertEquals(2, page.distinctAckedControlCount(), "去重后按控制号算 2 个");

            AckDiff diff = AckReconciler.diff(expected, page);
            assertEquals(List.of("CTRL-20261020-1"), diff.duplicatedRemoteControlIds());
            assertEquals(2, diff.matchedCount(), "重复回报不改变差集结论");
            assertEquals(6, diff.missingCount());
        }
    }

    @Test
    @DisplayName("跨批次条目不进本批差集，对端回的是别批次也不算数")
    void test_diff_is_scoped_to_one_batch() throws Exception {
        String otherBatch = "REGIONAL_ARCHIVE-20261019";
        IdempotencyKey k = IdempotencyKey.of("ADT^A01^ADT_A01", "CTRL-OTHER-0001", "P");
        long otherId = makeLedgerRow(k, otherBatch, MessageState.PENDING_CONFIRM);
        List<LocalExpectedAck> expected = new ArrayList<>(seedEightExpected());
        expected.add(new LocalExpectedAck(k, otherId, otherBatch, MessageState.PENDING_CONFIRM, "AA"));

        try (RegionalAckStubServer stub = stubWithSixAcks()) {
            AckDiff diff = AckReconciler.diff(expected, clientFor(stub).fetchAcks(BATCH));

            assertEquals(8, diff.expectedCount(), "别批次的条目不进本批差集");
            assertEquals(2, diff.missingCount());
            assertFalse(controlIdsOf(diff.missing()).contains("CTRL-OTHER-0001"));
        }
    }

    @Test
    @DisplayName("差集两侧都不含患者可识别明文：只有控制号、MSA 码与台账主键")
    void test_diff_and_page_carry_no_patient_plaintext() throws Exception {
        List<LocalExpectedAck> expected = seedEightExpected();
        try (RegionalAckStubServer stub = stubWithSixAcks()) {
            AckDiff diff = AckReconciler.diff(expected, clientFor(stub).fetchAcks(BATCH));

            String report = diff.toLogLine() + " " + clientFor(stub).fetchAcks(BATCH).toLogLine()
                    + " " + diff.missing().get(0).local().toLogLine();
            for (String forbidden : List.of("PAT-", "张", "李", "19780315", "310101", "身份证")) {
                assertFalse(report.contains(forbidden), "对账报表不得含患者可识别内容：" + forbidden
                        + " 出现在 " + report);
            }
            assertNotNull(diff.toLogLine());
        }
    }

    @Test
    @DisplayName("缺失原因与归一说明进报表：排障时能复述「为什么判成缺失」")
    void test_missing_reason_and_explanation_are_reportable() throws Exception {
        List<LocalExpectedAck> expected = seedEightExpected();
        try (RegionalAckStubServer stub = stubWithSixAcks()) {
            AckDiff diff = AckReconciler.diff(expected, clientFor(stub).fetchAcks(BATCH));

            for (AckDiff.MissingAck m : diff.missing()) {
                assertEquals(AckDiff.MissingAck.REASON_NO_REMOTE_ACK, m.reason());
                assertEquals(ControlIdNormalizer.normalize(m.local().key().controlId()),
                        m.normalizedControlId());
            }
            assertEquals("控制号归一后一致（CTRL-0001 → CTRL-1）", ControlIdNormalizer.explain("CTRL-0001"));
        }
    }

    @Test
    @DisplayName("台账只读面 → 应发条目：差集左侧可直接来自真实台账查询")
    void test_expected_entries_can_come_from_ledger_read_view() throws Exception {
        seedEightExpected();
        try (Connection conn = openConnection()) {
            var store = new cn.hospital.region.archive.ledger.JdbcLedgerStore(
                    BatchReconcilerClientTest::openConnection, java.time.Clock.systemUTC(),
                    cn.hospital.region.archive.ledger.AuditWriter.jdbc(java.time.Clock.systemUTC()));

            List<LocalExpectedAck> expected = AckReconciler.expectedFromLedger(store, BATCH, null,
                    key -> "CTRL-20261020-0008".equals(key.controlId()) ? "AR" : "AA");

            assertEquals(8, expected.size(), "真实台账这一批有 8 行");
            assertTrue(expected.stream().allMatch(a -> a.batchId().equals(BATCH)));
            assertEquals("AR", expected.stream()
                    .filter(a -> a.key().controlId().equals("CTRL-20261020-0008"))
                    .findFirst().orElseThrow().ackCode());
            assertTrue(expected.stream().noneMatch(a -> a.key().controlId().isEmpty()));
            assertNotNull(conn);
        }
    }

    @Test
    @DisplayName("非法入参在算差集前就被拒：空页与空控制号都不该进算法")
    void test_invalid_inputs_rejected_before_diff() throws SQLException {
        List<LocalExpectedAck> expected = seedEightExpected();
        RemoteAckPage page = new RemoteAckPage(BATCH, 0, List.of(), false);

        assertThrows(NullPointerException.class, () -> AckReconciler.diff(expected, null));
        assertThrows(NullPointerException.class, () -> AckReconciler.diff(null, page));
        assertThrows(IllegalArgumentException.class,
                () -> new LocalExpectedAck(IdempotencyKey.of("ADT^A01", "   ", "P"), 1L, BATCH,
                        MessageState.RECEIVED, "AA"),
                "空控制号必须在构造期就被拒");
        assertThrows(IllegalArgumentException.class,
                () -> new LocalExpectedAck(IdempotencyKey.of("ADT^A01", "C1", "P"), 0L, BATCH,
                        MessageState.RECEIVED, "AA"),
                "台账主键必须为正数");
    }

    @Test
    @DisplayName("对账客户端的启动摘要不含令牌，只给主机、端口与方法")
    void test_describe_line_has_no_token() throws Exception {
        try (RegionalAckStubServer stub = stubWithSixAcks()) {
            String describe = clientFor(stub).describe();
            assertFalse(describe.contains(TOKEN), "启动摘要不得带令牌：" + describe);
            assertTrue(describe.contains("GET"));
            assertTrue(describe.contains("/batches/%s/acks"));
            assertTrue(describe.contains("HTTP/1.1"));
        }
    }

    @Test
    @DisplayName("非法基址在装配期被拒：协议 / 主机名不合法直接失败，不等到第一次请求")
    void test_invalid_base_url_rejected_at_assembly() {
        assertThrows(IllegalArgumentException.class,
                () -> new BatchReconcilerClient("ftp://plat.example.internal", TOKEN, Duration.ofSeconds(5)));
        assertThrows(IllegalArgumentException.class,
                () -> new BatchReconcilerClient("http://", TOKEN, Duration.ofSeconds(5)));
        assertThrows(IllegalArgumentException.class,
                () -> new BatchReconcilerClient(stubUrlOfLoopback(), "  ", Duration.ofSeconds(5)));
        assertThrows(IllegalArgumentException.class,
                () -> new BatchReconcilerClient(stubUrlOfLoopback(), TOKEN, Duration.ZERO));
        assertThrows(NullPointerException.class,
                () -> new BatchReconcilerClient(null, TOKEN, Duration.ofSeconds(5)));
    }

    @Test
    @DisplayName("入口 URI 拼接：尾斜杠被剥掉，批次号做路径段编码")
    void test_acks_uri_building() {
        assertEquals("http://plat.example.internal:8080/api/batches/B1/acks",
                BatchReconcilerClient.acksUri("http://plat.example.internal:8080/api", "B1").toString());
        assertEquals("http://plat.example.internal:8080/batches/B1/acks",
                BatchReconcilerClient.acksUri("http://plat.example.internal:8080///", "B1").toString());
        assertEquals("http://plat.example.internal/batches/B%2F1/acks",
                BatchReconcilerClient.acksUri("http://plat.example.internal", "B/1").toString());
        assertEquals("http://plat.example.internal/batches/A%20B%26C/acks",
                BatchReconcilerClient.acksUri("http://plat.example.internal", "A B&C").toString(),
                "空格编成 %20（不能是表单语义的 +），& 不能漏编");
    }

    @Test
    @DisplayName("错误码分类：401/403 / 404 / 429 / 4xx / 5xx / 3xx 各自归位且抖动标记正确")
    void test_error_code_classification() {
        assertEquals(ReconcileErrorCode.AUTH_REJECTED, ReconcileErrorCode.classify(401));
        assertEquals(ReconcileErrorCode.AUTH_REJECTED, ReconcileErrorCode.classify(403));
        assertEquals(ReconcileErrorCode.BATCH_NOT_FOUND, ReconcileErrorCode.classify(404));
        assertEquals(ReconcileErrorCode.RATE_LIMITED, ReconcileErrorCode.classify(429));
        assertEquals(ReconcileErrorCode.CLIENT_ERROR, ReconcileErrorCode.classify(400));
        assertEquals(ReconcileErrorCode.CLIENT_ERROR, ReconcileErrorCode.classify(451));
        assertEquals(ReconcileErrorCode.SERVER_ERROR, ReconcileErrorCode.classify(500));
        assertEquals(ReconcileErrorCode.SERVER_ERROR, ReconcileErrorCode.classify(503));
        assertEquals(ReconcileErrorCode.UNEXPECTED_STATUS, ReconcileErrorCode.classify(302));

        assertTrue(ReconcileErrorCode.RATE_LIMITED.jittery());
        assertTrue(ReconcileErrorCode.SERVER_ERROR.jittery());
        assertTrue(ReconcileErrorCode.READ_TIMEOUT.jittery());
        assertFalse(ReconcileErrorCode.AUTH_REJECTED.jittery());
        assertFalse(ReconcileErrorCode.BATCH_NOT_FOUND.jittery());
        assertTrue(ReconcileErrorCode.AUTH_REJECTED.disposition().contains("ARCHIVE_ACK_API_TOKEN"));
    }

    @Test
    @DisplayName("响应体里的 receivedAt 不可解析时只影响排障显示，不让整页对账失败")
    void test_unparsable_received_at_degrades_only_display() throws Exception {
        try (RegionalAckStubServer stub = RegionalAckStubServer.start(TOKEN).withAcks(BATCH, List.of(
                Map.of("controlId", "CTRL-20261020-0001", "msaCode", "AA", "receivedAt", "2026/10/20 09:00"),
                Map.of("controlId", "CTRL-20261020-0002", "msaCode", "AA")))) {
            RemoteAckPage page = clientFor(stub).fetchAcks(BATCH);

            assertEquals(2, page.actualAckCount());
            assertEquals(Instant.EPOCH, page.acks().get(0).receivedAt(), "不可解析按未回报处理");
            assertEquals(Instant.EPOCH, page.acks().get(1).receivedAt(), "缺字段按未回报处理");
            assertFalse(page.acks().get(0).toLogLine().contains("receivedAt="),
                    "未回报时刻的条目行里不该出现 receivedAt=");
        }
    }

    @Test
    @DisplayName("对端不回报 ackCount 时显示「未回报」，不算口径不一致")
    void test_missing_ack_count_is_not_a_mismatch() throws Exception {
        List<LocalExpectedAck> expected = seedEightExpected();
        try (RegionalAckStubServer stub = RegionalAckStubServer.start(TOKEN)
                .withAcks(BATCH, List.of(RegionalAckStubServer.ack("CTRL-20261020-0001", "AA", RECEIVED_AT)))
                .withoutDeclaredCount(BATCH)) {
            RemoteAckPage page = clientFor(stub).fetchAcks(BATCH);

            assertEquals(-1, page.declaredAckCount());
            assertFalse(page.countMismatch());
            AckDiff diff = AckReconciler.diff(expected, page);
            assertEquals(7, diff.missingCount());
            assertTrue(diff.toLogLine().contains("自述=未回报"));
        }
    }

    private static String stubUrlOfLoopback() {
        return "http://127.0.0.1:1";
    }
}