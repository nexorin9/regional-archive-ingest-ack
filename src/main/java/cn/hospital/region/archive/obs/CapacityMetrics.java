package cn.hospital.region.archive.obs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 容量指标 —— 「区域平台一天推一个批次，这个接入口扛不扛得住」的<b>可测</b>答案。
 *
 * <h2>为什么要有这一层</h2>
 * <p>spec 的容量验收是「单批 500 条端到端处理，P99 单条处理耗时 &lt; 200ms」。
 * 「扛得住」「不慢」这类说法没法复核，只有落到<b>分位数</b>与<b>阈值</b>上才算数：
 * 平均值会骗人——500 条里 495 条 3ms、5 条 3 秒，平均值看着很好，
 * 而平台侧那 5 条其实早就按超时策略重推了。所以本类只报分位数与最慢的几条，
 * 不报平均值当结论（平均值仍然给出，但明确标注它不是验收口径）。
 *
 * <h2>指标从哪来（都是真量出来的，没有一个是估的）</h2>
 * <ul>
 *   <li><b>单条处理耗时</b>：接入口收帧之后、业务处理包一层计时刻度，<b>不含</b> socket 读写。
 *       含 socket 就变成在量本机回环口的快慢，量不到本产品该背的那段；</li>
 *   <li><b>批次总耗时 / 吞吐</b>：从首帧发出到积压清零的墙钟时间；</li>
 *   <li><b>队列深度</b>：{@code InboundFramePipeline} 观察到的最大积压，
 *       它同时也是「收帧跑在处理前面」这个结论的直接证据；</li>
 *   <li><b>数据库连接占用</b>：台账连接提供者开/关连接的计数与并发峰值。
 *       接入口每条报文自成一个事务（入账一次 + 迁移一次，各开一条连接），
 *       所以「一个批次会开多少条连接、峰值并发几条」是运维必须知道的口径，
 *       不是可以等出事再说的细节。</li>
 * </ul>
 *
 * <p><b>本类只做统计，不做任何判定。</b>阈值（P99 &lt; 200ms）写在验收用例里，
 * 不写在这里：指标要能被别人拿去对不同环境下的两轮数据，判定权留在验收侧。
 *
 * @see cn.hospital.region.archive.mllp.InboundFramePipeline 队列深度与被拒帧数的来源
 */
public final class CapacityMetrics {

    /** 单条处理耗时样本。{@code label} 用于慢条目定位（只放控制号一类可回查的定位信息）。 */
    public record Sample(String label, long nanos) implements Comparable<Sample> {
        public Sample {
            if (nanos < 0) {
                throw new IllegalArgumentException("耗时不得为负数: " + nanos);
            }
        }

        @Override
        public int compareTo(Sample other) {
            return Long.compare(nanos, other.nanos);
        }
    }

    /**
     * 耗时分布。<b>全部字段单位一律为纳秒</b>，换算成毫秒只在展示层做一次
     * （{@link #millis}）——单位换算散落在多个字段上，是「阈值差 1000 倍」的典型来源。
     *
     * @param p50Nanos 中位数
     * @param p95Nanos P95
     * @param p99Nanos P99 —— <b>验收口径就是这个值</b>
     * @param maxNanos 最大值
     * @param meanNanos 平均值；<b>仅供对照，不是验收口径</b>
     */
    public record LatencyProfile(long count, long p50Nanos, long p95Nanos, long p99Nanos,
                                 long maxNanos, double meanNanos) {
    }

    /**
     * 一批的完整容量指标。
     *
     * @param batchLabel       批次标识（批次号或基准名）
     * @param frameCount       本批帧数
     * @param latency          单条处理耗时分布
     * @param batchElapsedMs   批次墙钟总耗时（毫秒）
     * @param throughputPerSec 每秒处理帧数
     * @param maxQueueDepth    观察到的最大积压帧数；&gt;0 说明收帧确实跑在处理前面
     * @param rejectedFrames   因队列满被拒的帧数；<b>必须为 0</b>
     * @param peakDbConnections 台账连接并发峰值
     * @param dbConnectionsOpened 本批共开出的台账连接数
     * @param slowest          最慢的若干条（用于定位，不参与判定）
     */
    public record BatchMetrics(String batchLabel, int frameCount, LatencyProfile latency,
                               long batchElapsedMs, double throughputPerSec,
                               int maxQueueDepth, long rejectedFrames,
                               int peakDbConnections, long dbConnectionsOpened,
                               List<Sample> slowest) {

        public BatchMetrics {
            slowest = List.copyOf(slowest);
        }
    }

    /** 报告里默认列出的最慢条目条数：够定位问题，又不至于把报告刷成流水账。 */
    public static final int DEFAULT_SLOWEST_REPORTED = 5;

    /** P99 验收阈值（毫秒）。与 spec 的容量验收一致。 */
    public static final long P99_THRESHOLD_MS = 200L;

    private CapacityMetrics() {
        // 工具类，禁止实例化
    }

    /**
     * 从样本与批次级计数算出完整指标。
     *
     * @param label             批次标识
     * @param samples           单条处理耗时样本；空列表得到 count=0 的空分布（不抛）
     * @param batchElapsedNanos 批次墙钟总耗时（纳秒）
     * @param maxQueueDepth     观察到的最大积压帧数
     * @param rejectedFrames    被拒帧数
     * @param peakDbConnections 连接并发峰值
     * @param dbConnectionsOpened 本批开出的连接数
     */
    public static BatchMetrics of(String label, List<Sample> samples, long batchElapsedNanos,
                                  int maxQueueDepth, long rejectedFrames,
                                  int peakDbConnections, long dbConnectionsOpened) {
        if (batchElapsedNanos < 0) {
            throw new IllegalArgumentException("批次总耗时不得为负数: " + batchElapsedNanos);
        }
        List<Sample> sorted = new ArrayList<>(samples);
        Collections.sort(sorted);
        int n = sorted.size();
        long sum = 0L;
        for (Sample s : sorted) {
            sum += s.nanos();
        }
        LatencyProfile profile = new LatencyProfile(
                n,
                n == 0 ? 0L : percentileNanos(sorted, n, 0.50),
                n == 0 ? 0L : percentileNanos(sorted, n, 0.95),
                n == 0 ? 0L : percentileNanos(sorted, n, 0.99),
                n == 0 ? 0L : sorted.get(n - 1).nanos(),
                n == 0 ? 0.0 : (double) sum / n);
        double seconds = batchElapsedNanos / 1_000_000_000.0;
        double throughput = seconds <= 0 ? 0.0 : n / seconds;
        List<Sample> slowest = new ArrayList<>(
                sorted.subList(Math.max(0, n - DEFAULT_SLOWEST_REPORTED), n));
        Collections.reverse(slowest);
        return new BatchMetrics(label, n, profile, batchElapsedNanos / 1_000_000L, throughput,
                maxQueueDepth, rejectedFrames, peakDbConnections, dbConnectionsOpened, slowest);
    }

    /**
     * 最近秩法分位数：取升序第 {@code ceil(p × n)} 个样本。
     *
     * <p>不用插值、不用「最近的两个之间取平均」——那种算法会造出一个<b>没被任何一条报文达到过</b>
     * 的数值。P99 这种要拿去和 200ms 阈值比的数字，必须是某条真实发生过的耗时。
     */
    private static long percentileNanos(List<Sample> sorted, int n, double p) {
        int rank = (int) Math.ceil(p * n);
        if (rank < 1) {
            rank = 1;
        }
        if (rank > n) {
            rank = n;
        }
        return sorted.get(rank - 1).nanos();
    }

    /** 人可读的一行结论：给 CLI 与 README 容量小节直接引用。 */
    public static String summary(BatchMetrics m) {
        return String.format(Locale.ROOT,
                "批次 %s：帧数 %d，总耗时 %dms（%.1f 帧/秒），"
                        + "单条处理耗时 P50=%.2fms P95=%.2fms P99=%.2fms MAX=%.2fms（均值 %.2fms，仅供对照），"
                        + "最大积压 %d 帧，被拒 %d 帧，台账连接峰值 %d / 共开 %d 条",
                m.batchLabel(), m.frameCount(), m.batchElapsedMs(), m.throughputPerSec(),
                millis(m.latency().p50Nanos()), millis(m.latency().p95Nanos()),
                millis(m.latency().p99Nanos()), millis(m.latency().maxNanos()),
                millis(m.latency().meanNanos()), m.maxQueueDepth(), m.rejectedFrames(),
                m.peakDbConnections(), m.dbConnectionsOpened());
    }

    /**
     * 逐行原始数据，供 T40 汇总成容量报告（以及两轮之间做差分对比）。
     *
     * <p>刻意做成<b>键=值</b>的纯文本而不是 JSON：本项目的依赖只有 pg / h2 / junit 三个，
     * 为了一个报告引入 JSON 库不值；而 T40 汇总时按键解析同样可靠。
     */
    public static String rawData(BatchMetrics m) {
        StringBuilder sb = new StringBuilder();
        sb.append("batch.label=").append(m.batchLabel()).append('\n');
        sb.append("batch.frames=").append(m.frameCount()).append('\n');
        sb.append("batch.elapsed_ms=").append(m.batchElapsedMs()).append('\n');
        sb.append("batch.throughput_per_sec=").append(String.format(Locale.ROOT, "%.2f",
                m.throughputPerSec())).append('\n');
        sb.append("latency.samples=").append(m.latency().count()).append('\n');
        sb.append("latency.p50_ms=").append(String.format(Locale.ROOT, "%.3f",
                millis(m.latency().p50Nanos()))).append('\n');
        sb.append("latency.p95_ms=").append(String.format(Locale.ROOT, "%.3f",
                millis(m.latency().p95Nanos()))).append('\n');
        sb.append("latency.p99_ms=").append(String.format(Locale.ROOT, "%.3f",
                millis(m.latency().p99Nanos()))).append('\n');
        sb.append("latency.max_ms=").append(String.format(Locale.ROOT, "%.3f",
                millis(m.latency().maxNanos()))).append('\n');
        sb.append("latency.mean_ms=").append(String.format(Locale.ROOT, "%.3f",
                millis(m.latency().meanNanos()))).append('\n');
        sb.append("latency.p99_threshold_ms=").append(P99_THRESHOLD_MS).append('\n');
        sb.append("pipeline.max_queue_depth=").append(m.maxQueueDepth()).append('\n');
        sb.append("pipeline.rejected_frames=").append(m.rejectedFrames()).append('\n');
        sb.append("db.peak_connections=").append(m.peakDbConnections()).append('\n');
        sb.append("db.connections_opened=").append(m.dbConnectionsOpened()).append('\n');
        int rank = 1;
        for (Sample s : m.slowest()) {
            sb.append("slowest.").append(rank).append("=").append(s.label())
                    .append(' ').append(String.format(Locale.ROOT, "%.3f", millis(s.nanos())))
                    .append("ms").append('\n');
            rank++;
        }
        return sb.toString();
    }

    /** 纳秒转毫秒。参数取 {@code double} 是为了让平均值（本身就是 double）也能走同一个出口。 */
    private static double millis(double nanos) {
        return nanos / 1_000_000.0;
    }
}