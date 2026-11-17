package cn.hospital.region.archive.mllp;

import cn.hospital.region.archive.ack.AckComposer;
import cn.hospital.region.archive.ack.InboundHeader;
import cn.hospital.region.archive.config.AppConfig;
import cn.hospital.region.archive.config.ConfigException;
import cn.hospital.region.archive.ledger.AuditWriter;
import cn.hospital.region.archive.ledger.IdempotencyGuard;
import cn.hospital.region.archive.ledger.IdempotencyKey;
import cn.hospital.region.archive.ledger.IllegalStateTransitionException;
import cn.hospital.region.archive.ledger.InsertOutcome;
import cn.hospital.region.archive.ledger.JdbcLedgerStore;
import cn.hospital.region.archive.ledger.LedgerEntry;
import cn.hospital.region.archive.ledger.LedgerStateConflictException;
import cn.hospital.region.archive.ledger.LedgerRow;
import cn.hospital.region.archive.ledger.LedgerSchema;
import cn.hospital.region.archive.ledger.MessageState;
import cn.hospital.region.archive.redelivery.ExponentialBackoff;
import cn.hospital.region.archive.redelivery.JdbcRedeliveryQueue;
import cn.hospital.region.archive.redelivery.MllpRedeliveryAction;
import cn.hospital.region.archive.redelivery.RedeliveryCoordinator;
import cn.hospital.region.archive.redelivery.RedeliveryEntry;
import cn.hospital.region.archive.redelivery.RedeliveryQueue;
import cn.hospital.region.archive.redelivery.RedeliveryStatus;
import cn.hospital.region.archive.startup.RecoveryScan;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T37 · MLLP 连接断线自愈 —— 对端在建连后关掉连接，客户端必须自动重建并<b>重试同一操作</b>。
 *
 * <pre>
 *   源产品链（ReconnectDirContext#reconnect）：
 *     catch (e) { if (!is_connection_closed(e)) throw e;  reconnect();  再来一遍同一操作 }
 *   本轮落到 MLLP socket 上：
 *     exchange(报文) → 对端关连接 → 分类为「连接断了」→ 关旧连接建新连接 → 退避 → 重发同一份字节
 *                     → 对端回 MSA-1=AA（全程无需人工重启）
 *                     → 预算耗尽 → 抛 MllpReconnectExhaustedException（不静默吞）
 * </pre>
 *
 * <h2>全部走真实链路，0 个 mock 库</h2>
 * <ul>
 *   <li>对端：真实 {@link ServerSocket} + 真实帧字节 {@code 0x0B ... 0x1C 0x0D}；
 *       回执由生产同一套 {@link AckComposer} 组装，不是自造字符串；</li>
 *   <li>退避：注入<b>记账不真睡</b>的 {@link MllpClient.BackoffSleeper}，断言退避确实按配置发生
 *       （真睡 200ms 会让用例变慢且掩盖「退避没生效」这类缺陷）；</li>
 *   <li>「持续拒绝连接」：指向一个<b>确实没有监听</b>的端口，拿真实的
 *       {@link ConnectException}，不是造一个假异常；</li>
 *   <li>耗尽后的业务状态：真实 H2 PG 兼容模式 + 真实 JDBC 事务 + 真实
 *       {@link RedeliveryCoordinator} 调度 + 真实 {@link RecoveryScan} 恢复扫描。</li>
 * </ul>
 */
class MllpReconnectTest {

    private static final String H2_URL =
            "jdbc:h2:mem:mllp_reconnect;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-10-21T01:00:00Z"), ZoneId.of("Asia/Shanghai"));

    private static final String MESSAGE_TYPE = "ADT^A08^ADT_A08";
    private static final String BATCH_ID = "REGIONAL_ARCHIVE-20261020";

    private PeerStub peer;
    private final List<Long> sleptBackoffs = new CopyOnWriteArrayList<>();

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
    void setUp() {
        sleptBackoffs.clear();
    }

    @AfterEach
    void tearDown() {
        if (peer != null) {
            peer.close();
        }
    }

    // =====================================================================
    // 一、断线注入：对端建连后立刻关连接
    // =====================================================================

    @Test
    @DisplayName("对端建连后立刻关连接：客户端在重试上限内自动重建并完成一次成功回执，全程无需人工重启")
    void test_reconnects_after_peer_close_without_manual_restart() throws Exception {
        peer = PeerStub.start(FIXED_CLOCK);
        // 第 1 条连接收下就关（模拟对端进程被重启）；第 2 条起正常服务
        peer.closeFirstConnections(1);

        MllpReconnectPolicy policy = new MllpReconnectPolicy(3, 200L, 2_000L, 3_000, 5_000);
        MllpClient client = new MllpClient(peer.host(), peer.port(), policy,
                MllpClient.BackoffSleeper.recording(sleptBackoffs));

        byte[] ackFrame = client.exchange(bytes(er7(1)));

        assertNotNull(ackFrame, "自动重连后必须真的读到一条回执，不能是 null");
        assertEquals(AckComposer.CODE_AA, msa1Of(ackFrame), "对端回的应是 MSA-1=AA");
        assertEquals("CTRL-20261020-0001", msa2Of(ackFrame), "回执 MSA-2 必须逐字等于入站控制号");

        assertEquals(2, peer.connectionCount(), "第 1 条连接被对端关掉后应重开第 2 条");
        assertEquals(1, client.rebuildCount(), "只应重建 1 次");
        assertEquals(2, client.lastAttemptCount(), "首次 + 1 次重连");
        assertEquals(List.of(200L), sleptBackoffs, "重连前应按配置退避基数（200ms）退过一次");
        assertEquals(1, peer.receivedCount(), "只有第 2 条连接上的报文真的被对端读走");
    }

    @Test
    @DisplayName("对端在帧中途断流：同样算「连接断了」，重建后重发整帧成功")
    void test_mid_frame_break_is_reconnectable() throws Exception {
        peer = PeerStub.start(FIXED_CLOCK);
        // 第 1 条连接：读到帧起始块后就发半个报文再断开（不是干净的帧边界关闭）
        peer.breakMidFrameOnFirstConnection();

        MllpClient client = new MllpClient(peer.host(), peer.port(),
                new MllpReconnectPolicy(3, 10L, 100L, 3_000, 5_000),
                MllpClient.BackoffSleeper.recording(sleptBackoffs));

        byte[] ackFrame = client.exchange(bytes(er7(2)));

        assertEquals(AckComposer.CODE_AA, msa1Of(ackFrame));
        assertEquals(1, client.rebuildCount());
        assertEquals(2, peer.connectionCount());
        assertTrue(MllpClient.isConnectionClosed(
                        new MllpFrameCodec.MllpFrameException("对端在帧中途关闭连接")),
                "帧中途断流必须判为可重连，否则重投会在同一个故障上空转");
    }

    @Test
    @DisplayName("一次成功就只开一条连接：不重连、不退避")
    void test_successful_exchange_opens_exactly_one_connection() throws Exception {
        peer = PeerStub.start(FIXED_CLOCK);

        MllpClient client = new MllpClient(peer.host(), peer.port(),
                new MllpReconnectPolicy(3, 10L, 100L, 3_000, 5_000),
                MllpClient.BackoffSleeper.recording(sleptBackoffs));

        assertEquals(AckComposer.CODE_AA, msa1Of(client.exchange(bytes(er7(3)))));
        assertEquals(1, peer.connectionCount());
        assertEquals(0, client.rebuildCount());
        assertTrue(sleptBackoffs.isEmpty(), "没失败就不该退避");
    }

    @Test
    @DisplayName("传输级 NAK 是一个「答复」不是异常：不重连，交给业务层判可重投")
    void test_transport_nak_is_an_answer_not_a_reconnect() throws Exception {
        peer = PeerStub.start(FIXED_CLOCK);
        peer.setNakTransport(true);

        MllpClient client = new MllpClient(peer.host(), peer.port(),
                new MllpReconnectPolicy(3, 10L, 100L, 3_000, 5_000),
                MllpClient.BackoffSleeper.recording(sleptBackoffs));

        assertEquals(null, client.exchange(bytes(er7(4))), "传输级 NAK 应返回 null");
        assertEquals(1, peer.connectionCount(), "NAK 是明确答复，重发同一份字节只会再被 NAK 一次");
        assertEquals(0, client.rebuildCount());
    }

    @Test
    @DisplayName("对端首字节不是 ACK/NAK/帧起始：判为协议不一致，直接失败，不烧重试预算")
    void test_unexpected_first_byte_fails_fast_without_burning_retries() throws Exception {
        peer = PeerStub.start(FIXED_CLOCK);
        peer.setGarbageFirstByte();

        MllpClient client = new MllpClient(peer.host(), peer.port(),
                new MllpReconnectPolicy(3, 10L, 100L, 3_000, 5_000),
                MllpClient.BackoffSleeper.recording(sleptBackoffs));

        MllpProtocolException thrown = assertThrows(MllpProtocolException.class,
                () -> client.exchange(bytes(er7(5))));
        assertTrue(thrown.getMessage().contains("0x7a"), "异常须指出对端实际回的首字节: " + thrown.getMessage());
        assertEquals(1, client.lastAttemptCount(), "协议不一致只试 1 次");
        assertEquals(0, client.rebuildCount(), "协议不一致不重建连接");
        assertFalse(MllpClient.isConnectionClosed(thrown));
    }

    // =====================================================================
    // 二、重试耗尽：不静默吞，业务状态留在待重投
    // =====================================================================

    @Test
    @DisplayName("对端持续拒绝连接：耗尽重试后抛明确异常，该报文仍留在待重投、可被恢复扫描捞回")
    void test_retry_exhaustion_leaves_message_pending_retry() throws Exception {
        // ① 传输层：指向一个确实没有监听的端口，拿真实的 ConnectException
        int deadPort = findClosedPort();
        MllpReconnectPolicy policy = new MllpReconnectPolicy(3, 5L, 50L, 1_000, 1_000);
        MllpClient client = new MllpClient("127.0.0.1", deadPort, policy,
                MllpClient.BackoffSleeper.recording(sleptBackoffs));

        MllpReconnectExhaustedException thrown = assertThrows(MllpReconnectExhaustedException.class,
                () -> client.exchange(bytes(er7(6))));
        assertEquals(3, thrown.maxAttempts());
        assertEquals(2, thrown.rebuildCount(), "3 次尝试之间重建 2 次");
        assertEquals(3, client.lastAttemptCount());
        assertEquals(List.of(5L, 10L), sleptBackoffs, "退避按配置翻倍：5ms → 10ms");
        assertInstanceOf(ConnectException.class, thrown.getCause(), "最后一次失败应是真实的连不上");
        assertTrue(thrown.getMessage().contains("127.0.0.1:" + deadPort));

        // ② 业务层：同一条报文走真实调度器 —— 耗尽 ≠ 投出去了，条目退回待重投
        clearTables();
        JdbcLedgerStore store = newStore();
        JdbcRedeliveryQueue queue = new JdbcRedeliveryQueue(MllpReconnectTest::openConnection, FIXED_CLOCK);
        long ledgerId = insertPendingRetryRow(store, queue);

        MllpRedeliveryAction action = new MllpRedeliveryAction(
                MllpRedeliveryAction.PayloadSource.fromLedger(store), client);
        RedeliveryCoordinator coordinator = new RedeliveryCoordinator(store, store, queue, action,
                new ExponentialBackoff(60_000L, 60_000L, 0.0D, 7L), 3, FIXED_CLOCK, key -> "AE");

        coordinator.tickAt(FIXED_CLOCK.millis());

        LedgerRow row = store.findById(ledgerId).orElseThrow();
        assertEquals(MessageState.PENDING_RETRY, row.state(),
                "耗尽重试不等于已落地：报文必须留在待重投，退避后还能再投");
        RedeliveryEntry entry = queue.listByLedger(ledgerId).get(0);
        assertEquals(RedeliveryStatus.QUEUED, entry.status(), "队列条目应放回 QUEUED 等待下一次调度");
        assertEquals(1, entry.attempt());
        assertTrue(entry.lastError().contains("MLLP_RECONNECT_EXHAUSTED"),
                "lastError 须写明是重连耗尽: " + entry.lastError());
        assertTrue(entry.lastError().contains("Connection refused"),
                "lastError 须带上最后一次失败的原因: " + entry.lastError());

        // ③ 重启续跑：T35 的恢复扫描能重新看到这一条（进程重启也不丢）
        RecoveryScan.RecoveryResult recovery = RecoveryScan.runOnce(store, queue,
                FIXED_CLOCK.millis() + 60_000L, FIXED_CLOCK);
        assertEquals(1, recovery.scanned(), "恢复扫描必须看到这条待重投");
        assertEquals(1, recovery.duplicated(), "条目已在队列中，扫描不应重复入队也不应丢它");
        assertEquals(MessageState.PENDING_RETRY, store.findById(ledgerId).orElseThrow().state(),
                "恢复扫描后状态不变：它只是重新入队，不推进业务状态");
    }

    @Test
    @DisplayName("退避翻倍并封顶：长链条也不会算出溢出或天文数字")
    void test_backoff_doubles_and_is_capped() {
        MllpReconnectPolicy policy = new MllpReconnectPolicy(5, 100L, 1_000L, 1_000, 1_000);
        assertEquals(100L, policy.backoffDelayMs(1));
        assertEquals(200L, policy.backoffDelayMs(2));
        assertEquals(400L, policy.backoffDelayMs(3));
        assertEquals(800L, policy.backoffDelayMs(4));
        assertEquals(1_000L, policy.backoffDelayMs(5), "到封顶就不再翻倍");
        assertEquals(1_000L, policy.backoffDelayMs(50), "极端 attempt 也只到封顶");
        assertEquals(100L, policy.backoffDelayMs(0), "attempt ≤ 0 一律按 1 算");
        assertEquals(100L + 200L + 400L + 800L, policy.worstCaseBackoffMs());
    }

    // =====================================================================
    // 三、配置：重试上限与退避来自环境变量，不硬编码
    // =====================================================================

    @Test
    @DisplayName("重连参数全部来自环境变量；缺项走默认，非法值一律拒绝启动")
    void test_env_drives_retry_cap_and_backoff() {
        Map<String, String> env = baseEnv();
        MllpReconnectPolicy defaults = AppConfig.fromEnv(env).mllpReconnectPolicy();
        assertEquals(MllpReconnectPolicy.DEFAULT_MAX_ATTEMPTS, defaults.maxAttempts());
        assertEquals(MllpReconnectPolicy.DEFAULT_BASE_BACKOFF_MS, defaults.baseBackoffMs());
        assertEquals(MllpReconnectPolicy.DEFAULT_MAX_BACKOFF_MS, defaults.maxBackoffMs());

        env.put("ARCHIVE_MLLP_RECONNECT_MAX_ATTEMPTS", "6");
        env.put("ARCHIVE_MLLP_RECONNECT_BASE_MS", "50");
        env.put("ARCHIVE_MLLP_RECONNECT_MAX_MS", "5000");
        env.put("ARCHIVE_MLLP_CONNECT_TIMEOUT_MS", "1234");
        env.put("ARCHIVE_MLLP_READ_TIMEOUT_MS", "4321");
        MllpReconnectPolicy tuned = AppConfig.fromEnv(env).mllpReconnectPolicy();
        assertEquals(6, tuned.maxAttempts());
        assertEquals(50L, tuned.backoffDelayMs(1));
        assertEquals(400L, tuned.backoffDelayMs(4));
        assertEquals(1234, tuned.connectTimeoutMs());
        assertEquals(4321, tuned.readTimeoutMs());

        // 非法值不得被悄悄改写成别的值
        env.put("ARCHIVE_MLLP_RECONNECT_MAX_ATTEMPTS", "0");
        ConfigException tooFew = assertThrows(ConfigException.class, () -> AppConfig.fromEnv(env));
        assertTrue(tooFew.getMessage().contains("ARCHIVE_MLLP_RECONNECT_MAX_ATTEMPTS"));

        env.put("ARCHIVE_MLLP_RECONNECT_MAX_ATTEMPTS", "abc");
        assertThrows(ConfigException.class, () -> AppConfig.fromEnv(env));

        env.put("ARCHIVE_MLLP_RECONNECT_MAX_ATTEMPTS", "3");
        env.put("ARCHIVE_MLLP_RECONNECT_BASE_MS", "9000");
        env.put("ARCHIVE_MLLP_RECONNECT_MAX_MS", "10");
        ConfigException inverted = assertThrows(ConfigException.class, () -> AppConfig.fromEnv(env));
        assertTrue(inverted.getMessage().contains("ARCHIVE_MLLP_RECONNECT_MAX_MS"),
                "封顶小于基数属配置写反，须指路变量名: " + inverted.getMessage());
    }

    @Test
    @DisplayName("连接类与协议类异常分得清：前者重连，后者原样上抛")
    void test_connection_closed_classification() {
        assertTrue(MllpClient.isConnectionClosed(new ConnectException("Connection refused")));
        assertTrue(MllpClient.isConnectionClosed(
                new java.net.SocketException("Broken pipe")));
        assertTrue(MllpClient.isConnectionClosed(new java.io.EOFException()));
        assertTrue(MllpClient.isConnectionClosed(new java.net.SocketTimeoutException("read timed out")));
        assertTrue(MllpClient.isConnectionClosed(
                new MllpFrameCodec.MllpFrameException("对端在帧中途关闭连接")));
        assertFalse(MllpClient.isConnectionClosed(
                new MllpProtocolException("对端首字节既不是 ACK(0x06) 也不是帧起始(0x0B)：0x7a")));
        assertFalse(MllpClient.isConnectionClosed(new IOException("报文不是 ER7，编不出字节")));
        assertFalse(MllpClient.isConnectionClosed(new IllegalStateException("台账行已不存在")));
    }

    // =====================================================================
    // 装配与语料
    // =====================================================================

    private static JdbcLedgerStore newStore() {
        return new JdbcLedgerStore(MllpReconnectTest::openConnection, FIXED_CLOCK,
                AuditWriter.jdbc(FIXED_CLOCK));
    }

    private static void clearTables() throws SQLException {
        for (String table : List.of("redelivery_queue", "message_audit", "message_ledger")) {
            try (Connection conn = openConnection();
                 PreparedStatement ps = conn.prepareStatement("DELETE FROM " + table)) {
                ps.executeUpdate();
            }
        }
    }

    /**
     * 造一条已入账、待重投、且<b>已在重投队列里</b>的报文。
     *
     * <p>队列条目走的是真实 {@code offer}（与 T36 的对账闭环同一个入口），
     * 不是手工 INSERT —— 否则调度器根本 claim 不到东西，「耗尽后留在待重投」这条断言
     * 会在什么都没跑的情况下假通过。
     */
    private static long insertPendingRetryRow(JdbcLedgerStore store, JdbcRedeliveryQueue queue)
            throws SQLException, IllegalStateTransitionException, LedgerStateConflictException {
        String raw = er7(7);
        IdempotencyKey key = new IdempotencyKey(MESSAGE_TYPE, "CTRL-20261020-0007", "P");
        InsertOutcome outcome = store.insertIfAbsent(LedgerEntry.inbound(key, BATCH_ID, raw));
        long id = ((InsertOutcome.Inserted) outcome).ledgerId();
        store.transition(id, MessageState.RECEIVED, MessageState.PENDING_CONFIRM,
                "system", "identity-candidates-ready");
        store.transition(id, MessageState.PENDING_CONFIRM, MessageState.PENDING_RETRY,
                "system", "redelivery-enqueued:no-ack-next-day");
        RedeliveryQueue.OfferResult offered = queue.offer(key, id, BATCH_ID, "AE",
                MessageState.PENDING_RETRY, RedeliveryQueue.EnqueueTrigger.NO_ACK_NEXT_DAY,
                FIXED_CLOCK.millis());
        assertTrue(offered.isOffered(), "队列应收下这条待重投: " + offered);
        return id;
    }

    /** 找一个确实没有监听的回环端口（先占后放，保证连上去是 ECONNREFUSED）。 */
    private static int findClosedPort() throws IOException {
        try (ServerSocket probe = new ServerSocket()) {
            probe.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            return probe.getLocalPort();
        }
    }

    private static String er7(int index) {
        return ("MSH|^~\\&|ARCHIVE_IN|FACILITY_A|REGIONAL_ARCHIVE|FACILITY_A|20261020090000"
                + "||" + MESSAGE_TYPE + "|CTRL-20261020-" + String.format("%04d", index)
                + "|P|2.5.1|||AL|NE||UNICODE UTF-8\r"
                + "PID|1||PAT-20261020-00" + index + "^^^FACILITY_A^MR||李^"
                + (char) ('甲' + index) + "||197" + (60 + index) + "0315|M\r");
    }

    private static byte[] bytes(String er7) {
        return er7.getBytes(StandardCharsets.UTF_8);
    }

    private static String msa1Of(byte[] ackFrame) {
        return Hl7ParserBridge.field(ackFrame, "MSA", 1);
    }

    private static String msa2Of(byte[] ackFrame) {
        return Hl7ParserBridge.field(ackFrame, "MSA", 2);
    }

    private static Map<String, String> baseEnv() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("ARCHIVE_MLLP_HOST", "0.0.0.0");
        env.put("ARCHIVE_LOCAL_AET", "CHSCOMMUNITY");
        env.put("ARCHIVE_REMOTE_AET", "REGIONAL_ARCHIVE");
        env.put("ARCHIVE_ACK_MODE", "framed_ack");
        env.put("ARCHIVE_REDELIVERY_HOST", "archive-platform.example.internal");
        env.put("ARCHIVE_DB_URL", "jdbc:postgresql://127.0.0.1:5432/regional_archive");
        env.put("ARCHIVE_DB_USERNAME", "archive_app");
        env.put("ARCHIVE_DB_PASSWORD", "REPLACE_ME_DB_PASSWORD");
        env.put("HIS_MPI_ENDPOINT", "http://his-mpi.example.internal:8080");
        env.put("HIS_MPI_TOKEN", "REPLACE_ME_HIS_MPI_TOKEN");
        env.put("ARCHIVE_ACK_API_BASE_URL", "https://archive-platform.example.internal/api/v1");
        env.put("ARCHIVE_ACK_API_TOKEN", "REPLACE_ME_ACK_API_TOKEN");
        env.put("ARCHIVE_API_TOKEN", "REPLACE_ME_API_TOKEN");
        env.put("MODEL_BASE_URL", "https://model-gateway.example.internal/v1");
        env.put("MODEL_API_KEY", "REPLACE_ME_MODEL_API_KEY");
        env.put("MODEL_NAME", "REPLACE_ME_MODEL_NAME");
        return env;
    }

    /** 从回执帧字节里按生产同款解析器取字段。 */
    private static final class Hl7ParserBridge {

        private Hl7ParserBridge() {
        }

        static String field(byte[] frame, String segment, int index) {
            return cn.hospital.region.archive.hl7.Hl7Parser
                    .parse(new String(frame, StandardCharsets.UTF_8))
                    .field(segment, index);
        }
    }

    // =====================================================================
    // 对端替身：真实 ServerSocket + 可注入的断线故障
    // =====================================================================

    /**
     * 区域平台 MLLP 入口替身。与 T36 的 {@code RegionalPlatformMllpStub} 同一套回执组装，
     * 额外提供本任务要的三种断线注入：前 N 条连接立即关闭、帧中途断流、回一个垃圾首字节。
     */
    private static final class PeerStub implements AutoCloseable {

        private final ServerSocket server;
        private final AckComposer composer;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicInteger connectionCount = new AtomicInteger();
        private final List<byte[]> received = new CopyOnWriteArrayList<>();
        private final Thread acceptor;

        private volatile int closeFirstConnections;
        private volatile boolean breakMidFrame;
        private volatile boolean nakTransport;
        private volatile boolean garbageFirstByte;

        private PeerStub(ServerSocket server, Clock clock) {
            this.server = server;
            this.composer = new AckComposer("REGIONAL_ARCHIVE", "ARCHIVE_IN", clock);
            this.acceptor = new Thread(this::acceptLoop, "mllp-reconnect-peer");
            this.acceptor.setDaemon(true);
        }

        static PeerStub start(Clock clock) throws IOException {
            ServerSocket server = new ServerSocket();
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            PeerStub stub = new PeerStub(server, clock);
            stub.acceptor.start();
            return stub;
        }

        String host() {
            return server.getInetAddress().getHostAddress();
        }

        int port() {
            return server.getLocalPort();
        }

        int connectionCount() {
            return connectionCount.get();
        }

        int receivedCount() {
            return received.size();
        }

        PeerStub closeFirstConnections(int n) {
            this.closeFirstConnections = n;
            return this;
        }

        PeerStub breakMidFrameOnFirstConnection() {
            this.breakMidFrame = true;
            return this;
        }

        PeerStub setNakTransport(boolean nak) {
            this.nakTransport = nak;
            return this;
        }

        PeerStub setGarbageFirstByte() {
            this.garbageFirstByte = true;
            return this;
        }

        private void acceptLoop() {
            while (!closed.get()) {
                try (Socket socket = server.accept()) {
                    int seq = connectionCount.incrementAndGet();
                    handle(socket, seq);
                } catch (IOException e) {
                    if (closed.get()) {
                        return;
                    }
                    // 单条连接出错不影响替身继续服务下一条
                }
            }
        }

        private void handle(Socket socket, int seq) throws IOException {
            socket.setTcpNoDelay(true);
            if (seq <= closeFirstConnections) {
                // 对端在建连后立即关闭：一个字节都不写就断
                return;
            }
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            if (breakMidFrame && seq == 1) {
                // 发半个报文（带起始块、无帧尾）再断开
                out.write(MllpFrameCodec.START_BLOCK);
                out.write("MSH|^~\\&|ARCHIVE_IN".getBytes(StandardCharsets.UTF_8));
                out.flush();
                socket.setSoLinger(true, 0);
                return;
            }
            byte[] payload;
            while ((payload = MllpFrameCodec.readFrame(in)) != null) {
                received.add(payload);
                if (garbageFirstByte) {
                    out.write('z');
                    out.flush();
                    continue;
                }
                if (nakTransport) {
                    out.write(MllpFrameCodec.NAK);
                    out.flush();
                    continue;
                }
                byte[] ack = composer.composeAccepted(
                        InboundHeader.parse(new String(payload, StandardCharsets.UTF_8)))
                        .getBytes(StandardCharsets.UTF_8);
                out.write(MllpFrameCodec.ACK);
                out.flush();
                out.write(MllpFrameCodec.encode(ack));
                out.flush();
            }
        }

        @Override
        public void close() {
            closed.set(true);
            try {
                server.close();
            } catch (IOException ignored) {
                // 关闭失败不影响测试结论
            }
            acceptor.interrupt();
        }
    }
}
