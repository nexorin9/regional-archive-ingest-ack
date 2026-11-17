package cn.hospital.region.archive.mllp;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 接入口的<b>收帧 / 处理解耦</b> —— 区域平台推一个批次几百条报文时，监听线程不能被业务处理拖住。
 *
 * <h2>为什么必须解耦</h2>
 * <p>一条报文的业务处理要开两次数据库连接、入两行账（台账 + 审计），单条实测在毫秒量级。
 * 如果监听线程<b>边收边处理</b>，那么平台一次推 500 条就意味着监听线程被业务处理占住几百毫秒到
 * 几秒：表现是平台侧写 socket 阻塞、传输级 {@code 0x06} 也迟迟不回，对端按超时策略开始重推，
 * 于是监听口被自己制造的重投进一步拖慢——<b>越堵越推，越推越堵</b>。
 * 解耦之后监听线程只做「读字节、切帧、回传输级确认、入队」，处理在固定大小的池子上并行跑，
 * 队列深度成为唯一需要盯的背压信号。
 *
 * <h2>三条纪律</h2>
 * <ol>
 *   <li><b>背压显式、有上限</b>：队列满了<b>不阻塞</b>监听线程（阻塞等于把解耦白做），
 *       也<b>不静默丢弃</b>（静默丢弃等于回执条数与台账条数对不上，次日对账才发现）。
 *       走 {@link #rejectedFrames()} 计数 + ERROR 级日志，该帧没有应用级回执，
 *       平台自然会重投它——<b>这正是应当的行为</b>，比本端悄悄吞掉可查得多。</li>
 *   <li><b>队列深度是观测指标，不是控制流</b>：{@link #maxObservedDepth()} 只用于容量基准与
 *       告警，绝不参与任何业务判定。</li>
 *   <li><b>任务异常不拖垮池子</b>：一条报文处理失败只记 ERROR 并计数，工作线程继续服务下一帧。
 *       应用级回执由接入主路径自己判定（AE/AR），处理链异常时按「不回应用级回执」处理。</li>
 * </ol>
 *
 * <p><b>本类不参与业务判定</b>：它不知道什么叫 AA/AE/AR，也不知道台账有几态，只负责
 * 「把帧尽快交给处理链，并把排队这件事量出来」。
 *
 * <h2>与同步模式的取舍</h2>
 * <p>{@link MllpServer} 不装配本类时保持<b>逐帧同步</b>处理——契约测试要的是「发一帧立刻读到一帧回执」
 * 这种确定性断言，解耦后那种断言会变成竞态。生产装配走解耦，测试里需要确定性时走同步。
 * 两者共用同一个 {@link MllpServer.MessageHandler}，处理链只有一份。
 */
public final class InboundFramePipeline implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(InboundFramePipeline.class.getName());

    /** 默认工作线程数：等于 CPU 核数（至少 2），因为处理是 I/O 与事务混合的等待型负载。 */
    public static final int DEFAULT_WORKER_THREADS = Math.max(2, Runtime.getRuntime().availableProcessors());

    /** 默认队列容量：够摊平一次批量推送的抖动，又不至于把内存吃穿。 */
    public static final int DEFAULT_QUEUE_CAPACITY = 4096;

    private static final AtomicInteger THREAD_SEQ = new AtomicInteger();

    private final ThreadPoolExecutor executor;
    private final int workerThreads;
    private final int queueCapacity;

    private final AtomicLong submitted = new AtomicLong();
    private final AtomicLong completed = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicInteger maxObservedDepth = new AtomicInteger();
    private final AtomicInteger inFlight = new AtomicInteger();

    public InboundFramePipeline() {
        this(DEFAULT_WORKER_THREADS, DEFAULT_QUEUE_CAPACITY);
    }

    public InboundFramePipeline(int workerThreads, int queueCapacity) {
        if (workerThreads < 1) {
            throw new IllegalArgumentException("工作线程数至少为 1，收到 " + workerThreads);
        }
        if (queueCapacity < 1) {
            throw new IllegalArgumentException("队列容量至少为 1，收到 " + queueCapacity);
        }
        this.workerThreads = workerThreads;
        this.queueCapacity = queueCapacity;
        this.executor = new ThreadPoolExecutor(workerThreads, workerThreads, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), namedThreadFactory(),
                (task, pool) -> {
                    // 队列满：计数 + 响亮告警，不阻塞监听线程、不静默丢弃。
                    // 这一帧没有应用级回执，平台按自己的重推策略会再来一次——这比
                    // 「本端悄悄吞掉、随后次日对账数字对不上」可查得多。
                    rejected.incrementAndGet();
                    LOG.severe("入站队列已满（容量 " + pool.getQueue().size() + "/" + queueCapacity
                            + "），本帧未入队、不出应用级回执，等待对端重推。累计被拒帧数 "
                            + rejected.get());
                    throw new RejectedExecutionException("入站队列已满");
                });
        this.executor.allowCoreThreadTimeOut(false);
    }

    /**
     * 提交一帧处理任务。
     *
     * @return {@code true} 已入队；{@code false} 队列已满、本帧被拒（已计数并记 ERROR）
     */
    public boolean submit(Runnable task) {
        Objects.requireNonNull(task, "处理任务不得为 null");
        submitted.incrementAndGet();
        int depth;
        try {
            executor.execute(() -> {
                inFlight.incrementAndGet();
                try {
                    task.run();
                    completed.incrementAndGet();
                } catch (RuntimeException e) {
                    // 处理链异常不拖垮工作线程；但必须留痕，否则这条报文去哪了没人知道
                    failed.incrementAndGet();
                    LOG.log(Level.SEVERE, "帧处理失败（不影响后续帧）: {0}",
                            String.valueOf(e.getMessage()));
                } finally {
                    inFlight.decrementAndGet();
                }
            });
        } catch (RejectedExecutionException e) {
            submitted.decrementAndGet();
            return false;
        }
        depth = pending();
        maxObservedDepth.accumulateAndGet(depth, Math::max);
        return true;
    }

    /**
     * 当前积压量 = 队列里排队的 + 正在处理的。
     *
     * <p>这就是「队列长度」这个说法在本产品里的准确含义：只看队列会漏掉正在跑的那些帧，
     * 而正在跑的那些正是最接近系统吞吐上限的。
     */
    public int pending() {
        return executor.getQueue().size() + inFlight.get();
    }

    /** 有生以来观察到的最大积压量——容量基准用它证明「收帧确实跑在处理前面」。 */
    public int maxObservedDepth() {
        return maxObservedDepth.get();
    }

    /** 累计提交帧数（含被拒的，随后会被减掉——见返回值口径）。 */
    public long submittedFrames() {
        return submitted.get();
    }

    /** 累计处理完成帧数。 */
    public long completedFrames() {
        return completed.get();
    }

    /** 累计处理抛异常的帧数。 */
    public long failedFrames() {
        return failed.get();
    }

    /** 累计因队列满被拒的帧数；<b>必须为 0</b>，否则说明背压没兜住。 */
    public long rejectedFrames() {
        return rejected.get();
    }

    public int workerThreads() {
        return workerThreads;
    }

    public int queueCapacity() {
        return queueCapacity;
    }

    /**
     * 等到积压清零，供「一批处理完了再统计」使用。
     *
     * @return 是否在给定时间内清零
     */
    public boolean awaitDrained(Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout 不得为 null");
        long deadline = System.nanoTime() + timeout.toNanos();
        while (pending() > 0) {
            if (System.nanoTime() > deadline) {
                return false;
            }
            Thread.sleep(2L);
        }
        return true;
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static ThreadFactory namedThreadFactory() {
        return runnable -> {
            Thread t = new Thread(runnable,
                    "mllp-process-" + THREAD_SEQ.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}