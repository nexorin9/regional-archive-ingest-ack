package cn.hospital.region.archive.mllp;

import cn.hospital.region.archive.hl7.FrameDecoder;
import cn.hospital.region.archive.hl7.Hl7Message;
import cn.hospital.region.archive.obs.CapacityMetrics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T18 · 单批 500 条容量基准与 P99 阈值 —— 「这条接入口一天扛一个批次扛不扛得住」的可复核答案。
 *
 * <h2>这一组在验什么</h2>
 * <ul>
 *   <li><b>真实规模</b>：500 帧真实 MLLP 字节经真实 socket 打进真实监听口，逐条拆帧、解析、
 *       状态迁移、落库，每条回一条 MSA；台账行数与各状态条数逐项对上；</li>
 *   <li><b>P99 阈值</b>：单条处理耗时的 P99 &lt; 200ms（spec 容量验收口径），并打印完整分布
 *       与最慢 5 条的定位信息；</li>
 *   <li><b>收帧与业务处理解耦</b>：收帧期间处理跟不上，队列长度上升，但<b>接收不阻塞</b>；</li>
 *   <li><b>背压</b>：队列满时不阻塞监听线程也不静默丢弃，而是计数 + 响亮告警，
 *       让对端重推成为可查的路径。</li>
 * </ul>
 *
 * <p><b>0 个 mock 库</b>：真实 ServerSocket、真实帧字节、真实 H2 PG 兼容模式 + 真实 JDBC 事务。
 * 容量数字用替身量出来的没有意义——那量的是替身有多快。
 *
 * <h2>两轮基准的关系</h2>
 * <p>{@code workerThreads=1} 的那轮专门用来验证解耦：单线程处理必然跟不上收帧，
 * 于是「积压峰值 &gt; 0」这个断言是<b>结构性成立</b>的，不靠调度运气。
 * 默认线程数那轮才用来读 P99。
 */
class CapacityBenchmarkTest {

    /** 语料规模：spec 的容量验收口径。 */
    private static final int BATCH = CapacityCorpus.DEFAULT_BATCH_SIZE;

    // =====================================================================
    // 1. 500 条批次端到端 + P99 阈值
    // =====================================================================

    @Test
    @DisplayName("单批 500 条端到端：P99 < 200ms，台账 470 行 / 待确认 450 / 拒收 20，队列被拒 0 帧")
    void test_500_message_batch_p99_under_200ms() throws Exception {
        CapacityBenchmark.Result r = new CapacityBenchmark(BATCH,
                InboundFramePipeline.DEFAULT_WORKER_THREADS).run();

        System.out.println("=== 容量基准 ===");
        System.out.println(CapacityMetrics.summary(r.metrics()));
        System.out.println(CapacityMetrics.rawData(r.metrics()));
        System.out.println("收帧/回执最大积压=" + r.maxBacklog() + " 帧，收齐回执墙钟="
                + r.receiverElapsedMs() + "ms");

        // —— 端到端完整性：500 帧进、500 条回执出 ——
        assertEquals(BATCH, r.receivedFrames(), "监听口应收齐 500 帧");
        assertEquals(BATCH, r.ackedFrames(), "每帧都应回一条应用级回执");
        assertEquals(BATCH, r.framesBackFromClient(), "客户端应读到 500 帧回执");
        assertEquals(0L, r.handlerFailures(), "处理链不该有异常帧");

        // —— 台账构成：500 帧 − 30 条重复 = 470 行 ——
        CapacityBenchmark.LedgerCounts c = r.counts();
        assertEquals(470L, c.ledgerRows(), "台账行数应为 470（重复控制号不重复入账）");
        assertEquals(470L, c.auditRows(), "每行恰好一次状态迁移、恰好一条审计行");
        assertEquals(450L, c.byState().getOrDefault("PENDING_CONFIRM", 0L),
                "330 条 UTF-8 + 120 条 GB18030 应全部进待确认");
        assertEquals(20L, c.byState().getOrDefault("REJECTED", 0L),
                "12 条缺 PID-3 + 8 条缺 MSH-10 应全部进拒收");
        assertEquals(1, c.batchIds(), "同一批语料应落在同一个批次号下");

        // —— 阈值：P99 是验收口径，均值不是 ——
        CapacityMetrics.LatencyProfile p = r.metrics().latency();
        assertTrue(p.p99Nanos() < CapacityMetrics.P99_THRESHOLD_MS * 1_000_000L,
                "P99 应小于 " + CapacityMetrics.P99_THRESHOLD_MS + "ms，实测 "
                        + p.p99Nanos() / 1_000_000.0 + "ms");
        assertEquals(BATCH, p.count(), "每条到达都应留下一个耗时样本");
        assertTrue(p.p50Nanos() <= p.p95Nanos() && p.p95Nanos() <= p.p99Nanos()
                        && p.p99Nanos() <= p.maxNanos(),
                "分位数必须单调不降: p50=" + p.p50Nanos() + " p95=" + p.p95Nanos()
                        + " p99=" + p.p99Nanos() + " max=" + p.maxNanos());
        assertEquals(CapacityMetrics.DEFAULT_SLOWEST_REPORTED, r.metrics().slowest().size(),
                "最慢 5 条的定位信息必须打出来，否则 P99 超阈时无从下手");

        // —— 背压与连接占用 ——
        assertEquals(0L, r.metrics().rejectedFrames(),
                "500 条远小于队列容量，不该有帧被拒");
        assertTrue(r.metrics().maxQueueDepth() > 0,
                "队列深度必须曾经大于 0 —— 这是收帧跑在处理前面的直接证据");
        assertTrue(r.metrics().peakDbConnections() <= InboundFramePipeline.DEFAULT_WORKER_THREADS,
                "台账连接并发峰值不应超过工作线程数，实测 "
                        + r.metrics().peakDbConnections());
        assertTrue(r.metrics().dbConnectionsOpened() >= 2L * c.ledgerRows(),
                "每条报文至少两条连接（入账 + 迁移），实测开出 "
                        + r.metrics().dbConnectionsOpened() + " 条");
    }

    // =====================================================================
    // 2. 收帧与业务处理解耦
    // =====================================================================

    @Test
    @DisplayName("单工作线程处理时收帧不被阻塞：500 帧先收完、处理在后面追，积压峰值 > 0")
    void test_receiver_not_blocked_while_processing() throws Exception {
        // 单工作线程是刻意的：一个处理线程面对 500 条真实报文必然落后于收帧，
        // 于是「积压出现过」是结构性结论，不依赖调度运气。
        CapacityBenchmark.Result r = new CapacityBenchmark(BATCH, 1).run();

        System.out.println("单工作线程轮：" + CapacityMetrics.summary(r.metrics()));
        System.out.println("积压峰值=" + r.maxBacklog() + " 帧，队列峰值="
                + r.metrics().maxQueueDepth() + " 帧，收齐回执墙钟=" + r.receiverElapsedMs() + "ms");

        assertEquals(BATCH, r.receivedFrames(), "500 帧一条不少");
        assertEquals(BATCH, r.ackedFrames(), "解耦只是换了个线程处理，不许丢回执");

        // 解耦的直接证据：收帧与回执之间出现过积压。
        // 不解耦（监听线程边收边处理）时这个值恒为 0 或 1。
        assertTrue(r.maxBacklog() > 1,
                "收帧与回执之间的积压峰值应远大于 1（单线程处理必然落后），实测 "
                        + r.maxBacklog());
        assertTrue(r.metrics().maxQueueDepth() > 1,
                "处理队列深度应显著大于 1，实测 " + r.metrics().maxQueueDepth());

        // 积压堆到多少不算错——单工作线程面对 500 条本来就该整批堆在队列里等处理。
        // 错的是「堆到超过队列容量还不算数」，那说明背压没兜住、有帧连回执都没拿到。
        assertEquals(0L, r.metrics().rejectedFrames(),
                "队列容量按批次的 2 倍配，500 帧不该被拒");
        assertTrue(r.metrics().maxQueueDepth() <= BATCH,
                "队列深度不应超过本批帧数（容量为批次 2 倍），实测 "
                        + r.metrics().maxQueueDepth());
    }

    @Test
    @DisplayName("处理慢 5ms/帧时收帧仍不排队：收到 200 帧时尚未回完第 1 批，200 条回执最终一条不少")
    void test_slow_handler_does_not_stall_the_receive_loop() throws Exception {
        int frames = 200;
        CountDownLatch release = new CountDownLatch(1);
        AtomicLong handled = new AtomicLong();
        AtomicInteger concurrentHandlers = new AtomicInteger();
        AtomicBoolean sawConcurrency = new AtomicBoolean();

        // 故意慢的处理链：每帧睡 5ms，200 帧至少 1 秒。解耦失效时，监听线程会被这 5ms 拖住，
        // 客户端写 socket 就会跟着慢下来——那正是「越堵越推、越推越堵」的开端。
        MllpServer.MessageHandler slow = payload -> {
            int now = concurrentHandlers.incrementAndGet();
            if (now > 1) {
                sawConcurrency.set(true);
            }
            try {
                Thread.sleep(5L);
                handled.incrementAndGet();
            } finally {
                concurrentHandlers.decrementAndGet();
            }
            // 回应一帧最小合法应用级回执（连 MSH 都读不出时服务端的约定：本帧不回应用级回执），
            // 这里返回一个非空字节序列以计入回执条数。
            return payload;
        };

        try (InboundFramePipeline pipeline = new InboundFramePipeline(1, 1024);
             MllpServer server = new MllpServer("127.0.0.1", 0, slow, 30_000,
                     AckDialect.BARE_ACK, pipeline)) {
            int port = server.start();
            try (Socket client = new Socket()) {
                client.connect(new InetSocketAddress("127.0.0.1", port), 5_000);
                client.setSoTimeout(30_000);
                CapacityBenchmark.ClientDrain drain =
                        new CapacityBenchmark.ClientDrain(client.getInputStream());
                Thread drainThread = new Thread(drain, "slow-drain");
                drainThread.setDaemon(true);
                drainThread.start();

                long start = System.nanoTime();
                OutputStream out = client.getOutputStream();
                List<CapacityCorpus.Frame> corpus = CapacityCorpus.generate(20);
                for (int i = 0; i < frames; i++) {
                    out.write(corpus.get(i % corpus.size()).framed());
                }
                out.flush();
                long writeDone = System.nanoTime();

                // write() 返回不等于服务端已经读完 —— 最后一帧可能还在内核缓冲里。
                // 这里等的是「收齐」，等不到才是真问题（那说明监听口被别的东西堵住了）。
                long recvDeadline = System.nanoTime()
                        + java.time.Duration.ofSeconds(10).toNanos();
                while (server.receivedCount() < frames && System.nanoTime() < recvDeadline) {
                    Thread.sleep(1L);
                }
                long allReceived = System.nanoTime();

                // 收帧已经全部完成，而处理才刚开始 —— 这是解耦的核心断言。
                assertEquals(frames, server.receivedCount(),
                        "写入后 10s 内服务端应收齐 " + frames + " 帧");
                assertTrue(server.ackedCount() < frames,
                        "收齐瞬间处理不该已经追完（每帧 5ms × " + frames
                                + " 帧不可能瞬间跑完），实测已回 " + server.ackedCount());
                assertTrue(pipeline.pending() > 0,
                        "此刻应有帧在队列里排队，实测 pending=" + pipeline.pending());

                assertTrue(pipeline.awaitDrained(java.time.Duration.ofSeconds(60)),
                        "处理队列应在 60s 内清零");
                drain.awaitAtLeast(frames, java.time.Duration.ofSeconds(30));
                long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
                long writeMs = (writeDone - start) / 1_000_000L;
                long receiveAllMs = (allReceived - start) / 1_000_000L;

                System.out.println("慢处理轮：写完 " + frames + " 帧耗时 " + writeMs
                        + "ms，收齐 " + receiveAllMs + "ms，整轮 " + elapsedMs
                        + "ms，收齐回执 " + drain.framesSeen()
                        + " 帧，队列峰值 " + server.maxObservedQueueDepth() + " 帧");

                assertEquals(frames, handled.get(), "200 帧都要处理完");
                assertEquals(frames, server.ackedCount(), "200 条回执一条不少");
                assertEquals(frames, drain.framesSeen(), "客户端应读到 200 帧回执");
                // 收齐 200 帧必须远快于处理完 200 帧（200 × 5ms = 1000ms）。
                // 不解耦时这两个数会几乎相等。
                assertTrue(receiveAllMs * 4 < elapsedMs,
                        "收齐 " + frames + " 帧（" + receiveAllMs + "ms）应远快于处理完它们（"
                                + elapsedMs + "ms），说明监听线程没被业务处理拖住");
                assertTrue(server.maxObservedQueueDepth() > 1,
                        "队列深度应显著大于 1，实测 " + server.maxObservedQueueDepth());
            }
            release.countDown();
        }
    }

    // =====================================================================
    // 3. 背压：队列满时既不阻塞监听线程，也不静默丢弃
    // =====================================================================

    @Test
    @DisplayName("队列满时提交方立即返回 false 且不阻塞：被拒帧被计数并留痕，等对端重推")
    void test_pipeline_rejects_when_full_without_blocking() throws Exception {
        try (InboundFramePipeline pipeline = new InboundFramePipeline(1, 2)) {
            CountDownLatch occupied = new CountDownLatch(1);
            CountDownLatch hold = new CountDownLatch(1);

            // 1 个工作线程先被占住；队列容量 2 → 之后再提交必然入不了队。
            assertTrue(pipeline.submit(() -> {
                occupied.countDown();
                await(hold);
            }));
            occupied.await(5, TimeUnit.SECONDS);
            assertTrue(pipeline.submit(() -> { }));
            assertTrue(pipeline.submit(() -> { }));

            long start = System.nanoTime();
            boolean accepted = pipeline.submit(() -> { });
            long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

            assertEquals(false, accepted, "队列已满时必须拒绝而不是无限等");
            assertTrue(elapsedMs < 500L,
                    "拒绝必须立刻返回（实测 " + elapsedMs + "ms）；阻塞等于把解耦白做");
            assertEquals(1L, pipeline.rejectedFrames(), "被拒帧必须计数");
            assertTrue(pipeline.maxObservedDepth() > 0, "队列深度必须可观测");

            hold.countDown();
            assertTrue(pipeline.awaitDrained(java.time.Duration.ofSeconds(10)));
            assertEquals(3L, pipeline.completedFrames(), "放行后三帧都应处理完");
        }
    }

    @Test
    @DisplayName("单帧处理抛异常只影响那一帧：工作线程继续服务后续帧，异常被计数")
    void test_pipeline_isolates_handler_failure() throws Exception {
        try (InboundFramePipeline pipeline = new InboundFramePipeline(2, 16)) {
            for (int i = 0; i < 5; i++) {
                final int idx = i;
                assertTrue(pipeline.submit(() -> {
                    if (idx % 2 == 0) {
                        throw new IllegalStateException("第 " + idx + " 帧炸了");
                    }
                }));
            }
            assertTrue(pipeline.awaitDrained(java.time.Duration.ofSeconds(10)),
                    "异常帧不该让队列卡住");
            assertEquals(5L, pipeline.submittedFrames());
            assertEquals(5L, pipeline.completedFrames() + pipeline.failedFrames(),
                    "五帧要么完成要么失败，一条都不能凭空消失");
            assertEquals(3L, pipeline.failedFrames(), "偶数下标三帧应失败");
            assertEquals(2L, pipeline.completedFrames(), "奇数下标两帧应成功");
        }
    }

    // =====================================================================
    // 4. 语料构成：三种输入形态真的混进去了
    // =====================================================================

    @Test
    @DisplayName("语料构成 330 UTF-8 / 120 GB18030 / 30 重复 / 12 缺 PID-3 / 8 缺 MSH-10，各形态逐项可验")
    void test_corpus_covers_three_input_shapes() throws Exception {
        List<CapacityCorpus.Frame> frames = CapacityCorpus.generate(BATCH);
        assertEquals(BATCH, frames.size());
        assertEquals(330, CapacityCorpus.countOf(frames, CapacityCorpus.Kind.UTF8_VALID));
        assertEquals(120, CapacityCorpus.countOf(frames, CapacityCorpus.Kind.GB18030_VALID));
        assertEquals(30, CapacityCorpus.countOf(frames, CapacityCorpus.Kind.DUPLICATE));
        assertEquals(12, CapacityCorpus.countOf(frames, CapacityCorpus.Kind.MISSING_PID3));
        assertEquals(8, CapacityCorpus.countOf(frames, CapacityCorpus.Kind.MISSING_MSH10));

        // 重复控制号必须真的重复（否则 30 条「重复」会变成 30 条新报文，行数口径就错了）
        Set<String> validControlIds = new HashSet<>();
        for (CapacityCorpus.Frame f : frames) {
            String controlId = controlIdOf(f);
            if (f.kind() == CapacityCorpus.Kind.DUPLICATE) {
                assertTrue(validControlIds.contains(controlId),
                        "重复帧 " + controlId + " 必须在前面出现过，否则它不重复");
            } else if (f.kind() == CapacityCorpus.Kind.MISSING_MSH10) {
                assertEquals("", controlId, "缺 MSH-10 的帧控制号必须为空");
            } else {
                validControlIds.add(controlId);
            }
        }

        // GB18030 与 UTF-8 两类都必须能解出同一个 PID-5 —— 国内区域平台下发的是 GB18030
        for (CapacityCorpus.Frame f : frames) {
            if (f.kind() != CapacityCorpus.Kind.GB18030_VALID) {
                continue;
            }
            Hl7Message msg = FrameDecoder.decode(unframe(f)).message;
            assertTrue(msg.field("PID", 5).startsWith("居民^"),
                    "GB18030 帧解码后 PID-5 应还原出中文，得到 " + msg.field("PID", 5));
            break;
        }

        // 缺 PID-3 的帧必须整个字段为空 —— 写成 "^PAT-xxx^F^MR"（首组件空、字段非空）
        // 会让必填校验放它过去，那样这 12 条就白造了（本任务真踩过一次）
        for (CapacityCorpus.Frame f : frames) {
            if (f.kind() == CapacityCorpus.Kind.MISSING_PID3) {
                assertEquals("", FrameDecoder.decode(unframe(f)).message.field("PID", 3),
                        "缺 PID-3 的帧该字段必须整段为空");
                break;
            }
        }
    }

    // =====================================================================
    // 5. 指标本身：分位数必须落在真实观测到的样本上
    // =====================================================================

    @Test
    @DisplayName("分位数取最近秩：P99 等于某条真实发生过的耗时，不做插值造出没发生过的数字")
    void test_percentiles_are_real_observations() {
        List<CapacityMetrics.Sample> samples = new ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            samples.add(new CapacityMetrics.Sample("s" + i, i * 1_000_000L));
        }
        CapacityMetrics.BatchMetrics m = CapacityMetrics.of("单测", samples,
                100_000_000L, 7, 0L, 4, 40L);

        assertEquals(100L, m.latency().count());
        assertEquals(50L, m.latency().p50Nanos() / 1_000_000L, "100 个样本的 P50 取第 50 个");
        assertEquals(95L, m.latency().p95Nanos() / 1_000_000L, "P95 取第 95 个");
        assertEquals(99L, m.latency().p99Nanos() / 1_000_000L, "P99 取第 99 个");
        assertEquals(100L, m.latency().maxNanos() / 1_000_000L);
        assertEquals(50.5, m.latency().meanNanos() / 1_000_000.0, 1e-9);

        // 最慢 5 条从慢到快给出，且确实是最大的那 5 个样本
        assertEquals(5, m.slowest().size());
        assertEquals("s100", m.slowest().get(0).label());
        assertEquals("s96", m.slowest().get(4).label());

        // 原始数据两轮之间可差分：键必须齐、单位必须是毫秒
        String raw = CapacityMetrics.rawData(m);
        assertTrue(raw.contains("latency.p99_ms=99.000"), "原始数据缺 p99 毫秒值：" + raw);
        assertTrue(raw.contains("pipeline.max_queue_depth=7"), "原始数据缺队列深度：" + raw);
        assertTrue(raw.contains("db.peak_connections=4"), "原始数据缺连接峰值：" + raw);
        assertTrue(raw.contains("latency.p99_threshold_ms=200"), "原始数据缺阈值：" + raw);
    }

    @Test
    @DisplayName("空样本集得到空的零分布而不是抛异常：一批还没跑到时不许把统计层炸掉")
    void test_empty_samples_give_zero_profile() {
        CapacityMetrics.BatchMetrics m = CapacityMetrics.of("空批", List.of(), 0L, 0, 0L, 0, 0L);
        assertEquals(0L, m.latency().count());
        assertEquals(0L, m.latency().p99Nanos());
        assertEquals(0.0, m.throughputPerSec());
        assertTrue(m.slowest().isEmpty());
        assertNotNull(CapacityMetrics.summary(m));
    }

    // =====================================================================
    // 6. 基准内不允许有出站写
    // =====================================================================

    @Test
    @DisplayName("容量基准内的患者主索引端口一调就炸：出站往返混进 P99 会让阈值失去意义")
    void test_benchmark_blocks_outbound_patient_index_write() {
        CapacityBenchmark.OutboundWriteBlocked writer = new CapacityBenchmark.OutboundWriteBlocked();
        var e = org.junit.jupiter.api.Assertions.assertThrows(
                cn.hospital.region.archive.identity.PatientIndexWriteException.class,
                () -> writer.put("PAT-CAP-00001", "MRN-000001", "ARCHIVE_IN-20261020"));
        assertTrue(e.getMessage().contains("容量基准内不允许写院内患者主索引"),
                "报错要说清为什么：得到 " + e.getMessage());
        assertTrue(e.getMessage().contains("PAT-CAP-00001"),
                "报错要带出是哪条，避免只有一个说不清的失败");
    }

    // =====================================================================
    // 辅助
    // =====================================================================

    /** 剥掉 MLLP 帧，还原为 ER7 字节。 */
    private static byte[] unframe(CapacityCorpus.Frame f) {
        byte[] framed = f.framed();
        byte[] payload = new byte[framed.length - 4];
        System.arraycopy(framed, 1, payload, 0, payload.length);
        return payload;
    }

    /** 从帧里取 MSH-10 控制号。 */
    private static String controlIdOf(CapacityCorpus.Frame f) {
        String er7 = new String(unframe(f), f.charset());
        return er7.split("\r")[0].split("\\|", -1)[9];
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}