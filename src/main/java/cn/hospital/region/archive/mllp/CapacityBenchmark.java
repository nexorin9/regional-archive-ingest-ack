package cn.hospital.region.archive.mllp;

import cn.hospital.region.archive.ack.AckComposer;
import cn.hospital.region.archive.ack.InboundHeader;
import cn.hospital.region.archive.identity.PatientIndexWriteException;
import cn.hospital.region.archive.identity.PatientIndexWriter;
import cn.hospital.region.archive.ledger.AuditWriter;
import cn.hospital.region.archive.ledger.IdempotencyGuard;
import cn.hospital.region.archive.ledger.JdbcLedgerStore;
import cn.hospital.region.archive.ledger.LedgerSchema;
import cn.hospital.region.archive.ledger.ArchiveIngestService;
import cn.hospital.region.archive.obs.CapacityMetrics;

import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单批容量基准 —— 用<b>真实 socket、真实帧字节、真实 JDBC 事务</b>跑完一整批，
 * 把「这条接入口一天扛一个批次扛不扛得住」变成几个可以复核的数字。
 *
 * <h2>量的是什么</h2>
 * <pre>
 *   客户端 socket ──500 帧 MLLP 字节──▶ MllpServer 监听线程（切帧 + 回传输级确认 + 入队）
 *                                            │ InboundFramePipeline（有界队列 + 工作池）
 *                                            ▼
 *                                    ArchiveIngestService.ingest
 *                                      读 MSH 分隔符 → 按 MSH-18 解码 → 定位缺段缺字段
 *                                      → 幂等占用 → 台账入账 + 状态迁移 + 审计同事务落库
 *                                            │
 *   客户端 socket ◀──每帧一条 MSA 回执──┘
 * </pre>
 *
 * <h2>三处刻意的取舍</h2>
 * <ol>
 *   <li><b>计时刻度包在业务处理外面，不含 socket 读写。</b>含 socket 量的是本机回环口的快慢，
 *       量不到本产品该背的那段；socket 的账另有「收帧墙钟耗时」这一项。</li>
 *   <li><b>基准里不允许有出站写。</b>患者主索引端口在这里接的是「一旦被调就炸」的闸门
 *       （见 {@link OutboundWriteBlocked}）。真接上院内 HTTP 之后，出站 RTT 会混进 P99，
 *       届时 P99 &lt; 200ms 这个阈值就变成在量 HIS 的网络延迟，而不是量接入口了。</li>
 *   <li><b>用 H2 PG 兼容模式而不是内存 Map。</b>台账的价值全在唯一约束、事务与乐观锁上，
 *       拿 Map 跑出来的 P99 与生产没有关系。生产指向 PostgreSQL，同一份 SQL 不分叉。</li>
 * </ol>
 *
 * <h2>结论怎么读</h2>
 * <p>验收口径是 {@link CapacityMetrics#P99_THRESHOLD_MS}；{@code maxQueueDepth &gt; 0}
 * 说明收帧确实跑在处理前面（解耦生效的直接证据）；{@code rejectedFrames} 必须为 0，
 * 不为 0 说明背压没兜住、有帧连应用级回执都没拿到。
 */
public final class CapacityBenchmark {

    private static final String LOCAL_AET = "CHSCOMMUNITY";
    private static final String REMOTE_AET = "REGIONAL_ARCHIVE";

    /**
     * 基准库的库名前缀。
     *
     * <p>每轮基准用<b>独立库名</b>：H2 内存库带 {@code DB_CLOSE_DELAY=-1}、同一 JVM 内跨调用存活，
     * 而 V1 迁移不是幂等的（重复 CREATE TABLE 会炸）。同名复用会让第二轮基准直接失败，
     * 或者更糟——复用上一轮的行数，让「台账 470 行」这条断言变成在数上一轮的账。
     */
    private static final AtomicLong DB_SEQ = new AtomicLong();

    /** 跑基准用的内存库 URL；与生产共用同一份 V1 脚本。 */
    static String benchDbUrl() {
        return "jdbc:h2:mem:capacity_benchmark_" + DB_SEQ.incrementAndGet()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
    }

    /** 固定时钟：基准的结论只跟吞吐与延迟有关，created_at/updated_at 必须可复现。 */
    public static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-10-20T01:00:00Z"), ZoneId.of("Asia/Shanghai"));

    /** 批次墙钟超时的上限；超时算基准失败，不产出「看起来跑完了」的半截数字。 */
    private static final Duration BATCH_TIMEOUT = Duration.ofMinutes(3);

    /**
     * 台账最终状态分布。
     *
     * @param ledgerRows  台账总行数（= 500 − 重复命中数）
     * @param auditRows   审计行数（每次状态迁移一行）
     * @param byState     各状态条数（未出现的状态不出现在本 map 里）
     * @param batchIds    出现过的批次号个数
     */
    public record LedgerCounts(long ledgerRows, long auditRows, Map<String, Long> byState,
                               int batchIds) {
    }

    /**
     * 一轮基准的完整结果。
     *
     * @param metrics     容量指标（分位数、吞吐、队列深度、连接占用）
     * @param counts      台账最终状态分布
     * @param receivedFrames 服务端收到的帧数
     * @param ackedFrames    服务端已回应用级回执的帧数
     * @param framesBackFromClient 客户端读到回来的应用级回执帧数
     * @param maxBacklog   收帧与回执之间出现过的最大积压（&gt;0 即解耦生效）
     * @param receiverElapsedMs 客户端把全部帧写完并收到全部回执的墙钟耗时
     * @param handlerFailures 处理链抛异常的帧数
     */
    public record Result(CapacityMetrics.BatchMetrics metrics, LedgerCounts counts,
                         long receivedFrames, long ackedFrames, long framesBackFromClient,
                         long maxBacklog, long receiverElapsedMs, long handlerFailures) {
    }

    private final int batchSize;
    private final int workerThreads;

    public CapacityBenchmark(int batchSize, int workerThreads) {
        if (batchSize < 20) {
            throw new IllegalArgumentException("批次至少 20 帧，收到 " + batchSize);
        }
        if (workerThreads < 1) {
            throw new IllegalArgumentException("工作线程至少 1，收到 " + workerThreads);
        }
        this.batchSize = batchSize;
        this.workerThreads = workerThreads;
    }

    // =====================================================================
    // 跑一轮
    // =====================================================================

    /** 跑一轮基准，返回完整结果。 */
    public Result run() throws Exception {
        String dbUrl = benchDbUrl();
        CountingConnectionProvider connections = new CountingConnectionProvider(dbUrl);
        try (Connection migrate = DriverManager.getConnection(dbUrl, "sa", "")) {
            migrate.setAutoCommit(false);
            LedgerSchema.migrate(migrate);
            migrate.commit();
        }

        JdbcLedgerStore store = new JdbcLedgerStore(connections, FIXED_CLOCK,
                AuditWriter.jdbc(FIXED_CLOCK));
        IdempotencyGuard guard = new IdempotencyGuard(store);
        ArchiveIngestService ingest = new ArchiveIngestService(
                new AckComposer(LOCAL_AET, REMOTE_AET, FIXED_CLOCK), store, guard,
                new OutboundWriteBlocked());

        List<CapacityCorpus.Frame> frames = CapacityCorpus.generate(batchSize);
        TimedHandler timed = new TimedHandler(ingest);

        InboundFramePipeline pipeline = new InboundFramePipeline(workerThreads,
                Math.max(64, batchSize * 2));
        long maxBacklog;
        try (MllpServer server = new MllpServer("127.0.0.1", 0, timed, 30_000,
                AckDialect.BARE_ACK, pipeline)) {
            int port = server.start();
            long batchStart = System.nanoTime();

            BacklogSampler sampler = new BacklogSampler(server);
            Thread samplerThread = sampler.startThread();

            ClientResult client;
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 5_000);
                socket.setTcpNoDelay(true);
                socket.setSoTimeout(30_000);
                ClientDrain drain = new ClientDrain(socket.getInputStream());
                Thread drainThread = new Thread(drain, "bench-client-drain");
                drainThread.setDaemon(true);
                drainThread.start();

                long writeStart = System.nanoTime();
                OutputStream out = socket.getOutputStream();
                for (CapacityCorpus.Frame f : frames) {
                    out.write(f.framed());
                }
                out.flush();

                drain.awaitAtLeast((long) frames.size(), BATCH_TIMEOUT);
                boolean drained = pipeline.awaitDrained(BATCH_TIMEOUT);
                sampler.stop();
                drainThread.join(2_000);
                long batchEnd = System.nanoTime();
                if (!drained) {
                    throw new IllegalStateException("批次超时：处理队列在 "
                            + BATCH_TIMEOUT.toSeconds() + "s 内未清零，剩余 "
                            + pipeline.pending() + " 帧——基准不接受半截数字");
                }
                client = new ClientResult(drain.framesSeen(),
                        (batchEnd - writeStart) / 1_000_000L);
            }
            samplerThread.join(2_000);
            maxBacklog = sampler.maxBacklog();

            long elapsed = System.nanoTime() - batchStart;
            CapacityMetrics.BatchMetrics metrics = CapacityMetrics.of(
                    "容量基准-" + batchSize + "条/" + workerThreads + "工作线程",
                    timed.samples(), elapsed, server.maxObservedQueueDepth(),
                    server.overflowedFrames(), connections.peak(), connections.opened());
            LedgerCounts counts = readLedgerCounts(dbUrl);
            return new Result(metrics, counts, server.receivedCount(), server.ackedCount(),
                    client.framesSeen(), maxBacklog, client.elapsedMs(), timed.failures());
        } finally {
            pipeline.close();
            connections.closeAll();
        }
    }

    /** CLI 入口：打印一行结论 + 逐行原始数据（供 T40 汇总与两轮差分）。 */
    public static int runCli(PrintStream out) {
        try {
            int batchSize = CapacityCorpus.DEFAULT_BATCH_SIZE;
            Result r = new CapacityBenchmark(batchSize, InboundFramePipeline.DEFAULT_WORKER_THREADS).run();
            out.println(CapacityMetrics.summary(r.metrics()));
            out.println();
            out.println(CapacityMetrics.rawData(r.metrics()));
            out.println();
            out.println("服务端收到 " + r.receivedFrames() + " 帧 / 回应用级回执 " + r.ackedFrames()
                    + " 帧 / 客户端读到回执 " + r.framesBackFromClient() + " 帧"
                    + " / 收帧与回执最大积压 " + r.maxBacklog() + " 帧"
                    + " / 收齐回执墙钟 " + r.receiverElapsedMs() + "ms"
                    + " / 处理链异常 " + r.handlerFailures() + " 帧");
            out.println("台账：行数 " + r.counts().ledgerRows() + "，审计行 " + r.counts().auditRows()
                    + "，批次 " + r.counts().batchIds() + " 个，状态分布 " + r.counts().byState());
            out.println();

            List<CapacityCorpus.Frame> corpus = CapacityCorpus.generate(batchSize);
            StringBuilder mix = new StringBuilder();
            for (CapacityCorpus.Kind kind : CapacityCorpus.Kind.values()) {
                if (mix.length() > 0) {
                    mix.append(" / ");
                }
                mix.append(kind).append(' ').append(CapacityCorpus.countOf(corpus, kind));
            }
            out.println("语料构成（" + batchSize + " 帧，合成数据，非真实居民档案）：" + mix);

            double p99Ms = r.metrics().latency().p99Nanos() / 1_000_000.0;
            boolean underThreshold = r.metrics().latency().p99Nanos()
                    < CapacityMetrics.P99_THRESHOLD_MS * 1_000_000L;
            out.println();
            out.println("P99 阈值 " + CapacityMetrics.P99_THRESHOLD_MS + "ms → "
                    + (underThreshold ? "通过（实测 " + String.format(java.util.Locale.ROOT, "%.2f", p99Ms) + "ms）"
                    : "未通过（实测 " + String.format(java.util.Locale.ROOT, "%.2f", p99Ms) + "ms）"));
            return underThreshold ? 0 : 1;
        } catch (Exception e) {
            System.err.println("容量基准失败：" + e.getMessage());
            return 1;
        }
    }

    // =====================================================================
    // 计时刻度：包在业务处理外面，不含 socket 读写
    // =====================================================================

    /** 在处理链外面计时；标签取控制号一类可回查的定位信息，不含姓名等患者可识别明文。 */
    static final class TimedHandler implements MllpServer.MessageHandler {

        private final MllpServer.MessageHandler delegate;
        private final ConcurrentLinkedQueue<CapacityMetrics.Sample> samples = new ConcurrentLinkedQueue<>();
        private final AtomicLong failures = new AtomicLong();

        TimedHandler(MllpServer.MessageHandler delegate) {
            this.delegate = delegate;
        }

        @Override
        public byte[] handle(byte[] payload) {
            // 标签提取放在计时之外：它是观测手段，不该算进被观测的耗时。
            String label = labelOf(payload);
            long started = System.nanoTime();
            byte[] ack;
            try {
                ack = delegate.handle(payload);
            } catch (Exception e) {
                failures.incrementAndGet();
                throw new IllegalStateException("处理链异常: " + e.getMessage(), e);
            } finally {
                samples.add(new CapacityMetrics.Sample(label, System.nanoTime() - started));
            }
            return ack;
        }

        List<CapacityMetrics.Sample> samples() {
            return new ArrayList<>(samples);
        }

        long failures() {
            return failures.get();
        }

        private static String labelOf(byte[] payload) {
            try {
                return "controlId=" + InboundHeader.parse(new String(payload, StandardCharsets.US_ASCII))
                        .controlId();
            } catch (RuntimeException e) {
                return "controlId=(不可解析)";
            }
        }
    }

    // =====================================================================
    // 背压采样：收帧与回执之间出现过的最大积压
    // =====================================================================

    /**
     * 自旋采样 {@code received − acked} 的峰值。
     *
     * <p>这一个数就是「监听线程有没有被业务处理堵住」的直接证据：解耦失效时，
     * 收帧和回执会在同一条代码路径上前后脚完成，积压峰值会恒为 0 或 1。
     * 这里刻意不用 {@code sleep}——睡眠间隔会把最短暂的峰值抹平。
     */
    static final class BacklogSampler {

        private final MllpServer server;
        private final AtomicLong max = new AtomicLong();
        private volatile boolean running = true;

        BacklogSampler(MllpServer server) {
            this.server = server;
        }

        Thread startThread() {
            Thread t = new Thread(() -> {
                while (running) {
                    long backlog = server.receivedCount() - server.ackedCount();
                    if (backlog > max.get()) {
                        max.accumulateAndGet(backlog, Math::max);
                    }
                    Thread.onSpinWait();
                }
            }, "bench-backlog-sampler");
            t.setDaemon(true);
            t.start();
            return t;
        }

        void stop() {
            running = false;
        }

        long maxBacklog() {
            return max.get();
        }
    }

    // =====================================================================
    // 客户端：一条连接打完一整批，回执由独立线程排空
    // =====================================================================

    private record ClientResult(long framesSeen, long elapsedMs) {
    }

    /**
     * 排空回执的客户端读线程。
     *
     * <p>必须有它：如果客户端只写不读，几百条应用级回执很快把 socket 发送缓冲写满，
     * 服务端写回执就会阻塞——那时量到的「收帧耗时」其实是 socket 背压，不是产品处理能力。
     * 帧数按 {@code 0x0B} 起始字节计（BARE 方言下传输级 {@code 0x06} 是裸字节，不会被误计成帧）。
     */
    static final class ClientDrain implements Runnable {

        private final java.io.InputStream in;
        private final AtomicLong framesSeen = new AtomicLong();
        private final AtomicLong bytesRead = new AtomicLong();

        ClientDrain(java.io.InputStream in) {
            this.in = in;
        }

        @Override
        public void run() {
            byte[] chunk = new byte[8192];
            try {
                int read;
                while ((read = in.read(chunk)) != -1) {
                    bytesRead.addAndGet(read);
                    for (int i = 0; i < read; i++) {
                        if (chunk[i] == MllpFrameCodec.START_BLOCK) {
                            framesSeen.incrementAndGet();
                        }
                    }
                }
            } catch (java.io.IOException e) {
                // 服务端关闭或读超时：属于正常收尾，帧数由调用方据此判断
            }
        }

        long framesSeen() {
            return framesSeen.get();
        }

        long bytesRead() {
            return bytesRead.get();
        }

        void awaitAtLeast(long expected, Duration timeout) throws InterruptedException {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (framesSeen.get() < expected && System.nanoTime() < deadline) {
                Thread.sleep(2L);
            }
        }
    }

    // =====================================================================
    // 数据库连接占用
    // =====================================================================

    /**
     * 台账连接提供者 + 连接占用计量。
     *
     * <p>接入口<b>每条报文自成一个事务</b>（入账一次、迁移一次，各开一条连接），
     * 所以「一个批次开出多少条连接、峰值并发几条」是运维必须先知道的口径。
     * 借 {@link Proxy} 在 {@code close()} 处减计数，是为了不去手抄 {@link Connection} 的
     * 上百个方法——代理只包这一个行为，其余全部原样转发。
     */
    static final class CountingConnectionProvider implements JdbcLedgerStore.ConnectionProvider {

        private final String url;
        private final AtomicInteger inUse = new AtomicInteger();
        private final AtomicInteger peak = new AtomicInteger();
        private final AtomicLong opened = new AtomicLong();
        private final List<Connection> live = new ArrayList<>();

        CountingConnectionProvider(String url) {
            this.url = url;
        }

        @Override
        public Connection open() throws SQLException {
            Connection real = DriverManager.getConnection(url, "sa", "");
            opened.incrementAndGet();
            int now = inUse.incrementAndGet();
            peak.accumulateAndGet(now, Math::max);
            AtomicBoolean closed = new AtomicBoolean();
            Connection counted = (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        try {
                            Object result = method.invoke(real, args);
                            if ("close".equals(method.getName()) && closed.compareAndSet(false, true)) {
                                inUse.decrementAndGet();
                            }
                            return result;
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
            synchronized (live) {
                live.add(counted);
            }
            return counted;
        }

        int peak() {
            return peak.get();
        }

        long opened() {
            return opened.get();
        }

        /** 关掉还开着的连接；台账每次调用自己关连接，本方法只是兜底。 */
        void closeAll() {
            synchronized (live) {
                for (Connection c : live) {
                    try {
                        c.close();
                    } catch (SQLException ignored) {
                        // 关闭失败不影响进程退出
                    }
                }
                live.clear();
            }
        }
    }

    // =====================================================================
    // 结果读取
    // =====================================================================

    private static LedgerCounts readLedgerCounts(String dbUrl) throws SQLException {
        try (Connection conn = DriverManager.getConnection(dbUrl, "sa", "");
             Statement st = conn.createStatement()) {
            Map<String, Long> byState = new LinkedHashMap<>();
            long rows = 0;
            try (ResultSet rs = st.executeQuery(
                    "SELECT state, COUNT(*) FROM message_ledger GROUP BY state")) {
                while (rs.next()) {
                    byState.put(rs.getString(1), rs.getLong(2));
                    rows += rs.getLong(2);
                }
            }
            long auditRows;
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM message_audit")) {
                rs.next();
                auditRows = rs.getLong(1);
            }
            int batches;
            try (ResultSet rs = st.executeQuery(
                    "SELECT COUNT(DISTINCT batch_id) FROM message_ledger")) {
                rs.next();
                batches = rs.getInt(1);
            }
            return new LedgerCounts(rows, auditRows, Map.copyOf(byState), batches);
        }
    }

    /**
     * 基准里的患者主索引端口 —— <b>故意一调就炸</b>。
     *
     * <p>容量基准只跑接收与入账，不触发出站写。接上真实院内 HTTP 之后，出站 RTT 会混进
     * P99，阈值就变成在量 HIS 的网络延迟而不是量接入口。所以这里不给「静默成功的空实现」，
     * 给一个炸掉的闸门：谁误把确认动作接进基准，基准立刻失败并告诉他为什么。
     */
    static final class OutboundWriteBlocked implements PatientIndexWriter {
        @Override
        public void put(String patientRef, String localMrn, String sourceBatch)
                throws PatientIndexWriteException {
            throw new PatientIndexWriteException(
                    "容量基准内不允许写院内患者主索引（patientRef=" + patientRef
                            + "）：出站往返会混进 P99，使阈值不再是接入口的容量指标", false);
        }
    }
}