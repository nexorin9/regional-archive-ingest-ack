package cn.hospital.region.archive.mllp;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MLLP 监听口：真实 {@link ServerSocket}，一条连接一个线程，逐帧处理逐帧回写。
 *
 * <p>每帧的时序照参考实现 {@code MLLPConnection} 的顺序来：
 * <ol>
 *   <li>{@code readMessage()} 读到一帧 → 若为 MLLP2 则<b>先</b> {@code writeACK()} 发传输级 {@code 0x06}；</li>
 *   <li>业务处理链算出应用级 ACK 报文；</li>
 *   <li>{@code writeMessage()} 把 ACK 加帧 {@code 0x0B ... 0x1C 0x0D} 写回同一条连接。</li>
 * </ol>
 * 传输级确认与应用级 MSA <b>分层</b>：前者只说明「字节收全了」，后者才是能与本地台账
 * 逐条对账的业务结果。把 {@code 0x06} 当成「已落地」是本产品最容易犯、也最贵的错，
 * 所以两者在这里刻意分成两个写动作。
 *
 * <p>传输级确认的字节形状由 {@link AckDialect} 决定（{@code ARCHIVE_ACK_MODE}）：带帧
 * {@code 0B 06 1C 0D} 或裸 {@code 0x06}。切换方言只改变这一层包装，
 * 应用级 ACK 报文逐字节不变——两种方言共用同一个处理链产物。
 */
public final class MllpServer implements Closeable {

    /** 业务处理链：收到一帧报文，返回一条应用级 ACK 报文（<b>未加帧</b>）；返回 {@code null} 表示不回。 */
    public interface MessageHandler {
        byte[] handle(byte[] payload) throws Exception;
    }

    /**
     * 半帧处理链：连接结束或读超时后，把那条没等到帧尾的字节流交给它。
     *
     * <p><b>为什么半帧也要回一条应用级回执</b>：半帧意味着本端收下了这条报文的<b>开头</b>
     * （MSH 段通常完整，控制号读得出来），却没收到结尾。若就此静默丢弃，区域平台
     * 只会看到「发出去没有回执」，于是要么一直等到超时、要么在次日对账时发现少一条，
     * 而本端台账里<b>连这一行都没有</b>——现场查不出少的是哪条、也不知道本端收了多少。
     * 回一条 {@code MSA|AE} + {@code ERR}，对端能立刻知道「这条是半条、可重投」，
     * 本端也能把断流这件事写进日志。
     *
     * <p>装配 {@code null} 则回到旧行为（只记一行诊断、不回应用级回执）。
     */
    public interface PartialFrameHandler {
        byte[] handle(byte[] partialPayload, String cause) throws Exception;
    }

    private static final int READ_CHUNK = 8192;
    private static final int DEFAULT_SOCKET_TIMEOUT_MS = 0;

    /** 连接关闭后等待本连接在途帧处理完的上限；超时只记一行诊断，不无限等。 */
    private static final long CONNECTION_DRAIN_TIMEOUT_MS = 30_000L;

    private final String bindHost;
    private final int requestedPort;
    private final MessageHandler handler;
    private final int socketTimeoutMs;
    private final AckDialect ackDialect;

    /** 半帧（未收到帧尾就断流）处理链；{@code null} = 只记诊断、不出应用级回执。 */
    private final PartialFrameHandler partialFrameHandler;

    /**
     * 业务处理流水线；{@code null} = 逐帧同步处理（契约测试与冒烟用）。
     *
     * <p>生产装配走非 null 的解耦模式：监听线程只读帧、切帧、回传输级确认、入队，
     * 业务处理在 {@link InboundFramePipeline} 的工作池上并行跑。见该类注释里
     * 「边收边处理」为什么会把监听口自己堵死。
     */
    private final InboundFramePipeline pipeline;

    private final AtomicLong receivedCount = new AtomicLong();
    private final AtomicLong ackedCount = new AtomicLong();

    private volatile ServerSocket serverSocket;
    private volatile boolean running;
    private Thread acceptThread;

    public MllpServer(String bindHost, int port, MessageHandler handler) {
        this(bindHost, port, handler, DEFAULT_SOCKET_TIMEOUT_MS);
    }

    public MllpServer(String bindHost, int port, MessageHandler handler, int socketTimeoutMs) {
        this(bindHost, port, handler, socketTimeoutMs, AckDialect.BARE_ACK);
    }

    /**
     * 指定回执方言的完整构造。
     *
     * <p>方言只决定传输级确认字节的形状（裸 {@code 0x06} 还是 {@code 0B 06 1C 0D}），
     * 应用级 ACK 报文始终加 MLLP 帧发出，两者不互相影响。
     */
    public MllpServer(String bindHost, int port, MessageHandler handler, int socketTimeoutMs,
                      AckDialect ackDialect) {
        this(bindHost, port, handler, socketTimeoutMs, ackDialect, null);
    }

    /**
     * <b>解耦装配</b>：监听线程只负责收帧与传输级确认，业务处理交给 {@link InboundFramePipeline}。
     *
     * <p>生产接入口应当走这个构造。传 {@code null} 则回到逐帧同步处理，供需要
     * 「发一帧立刻读到一帧回执」这种确定性断言的契约测试使用。
     */
    public MllpServer(String bindHost, int port, MessageHandler handler, int socketTimeoutMs,
                      AckDialect ackDialect, InboundFramePipeline pipeline) {
        this(bindHost, port, handler, socketTimeoutMs, ackDialect, pipeline, null);
    }

    /**
     * 带半帧处理链的完整构造（生产装配）。
     *
     * <p>{@code partialFrames} 传 {@code null} 时，断流后只记一行诊断，不出应用级回执。
     */
    public MllpServer(String bindHost, int port, MessageHandler handler, int socketTimeoutMs,
                      AckDialect ackDialect, InboundFramePipeline pipeline,
                      PartialFrameHandler partialFrames) {
        this.bindHost = bindHost;
        this.requestedPort = port;
        this.handler = handler;
        this.socketTimeoutMs = socketTimeoutMs;
        this.ackDialect = ackDialect == null ? AckDialect.BARE_ACK : ackDialect;
        this.pipeline = pipeline;
        this.partialFrameHandler = partialFrames;
    }

    /** 当前处理流水线；{@code null} 表示同步处理。 */
    public InboundFramePipeline pipeline() {
        return pipeline;
    }

    /** 当前积压帧数；同步模式下恒为 0。 */
    public int pendingFrames() {
        return pipeline == null ? 0 : pipeline.pending();
    }

    /** 观察到的最大积压帧数；同步模式下恒为 0。 */
    public int maxObservedQueueDepth() {
        return pipeline == null ? 0 : pipeline.maxObservedDepth();
    }

    /** 因队列满被拒、因而没有应用级回执的帧数；同步模式下恒为 0。 */
    public long overflowedFrames() {
        return pipeline == null ? 0L : pipeline.rejectedFrames();
    }

    /** 当前回执方言，供启动摘要与只读运维输出。 */
    public AckDialect ackDialect() {
        return ackDialect;
    }

    /**
     * 绑定端口并开始收帧。端口传 0 时由内核分配空闲端口（契约测试与冒烟都用这个）。
     *
     * @return 实际监听端口
     */
    public int start() throws IOException {
        ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(InetAddress.getByName(bindHost), requestedPort));
        this.serverSocket = socket;
        this.running = true;
        this.acceptThread = new Thread(this::acceptLoop, "mllp-accept");
        this.acceptThread.setDaemon(true);
        this.acceptThread.start();
        return socket.getLocalPort();
    }

    public int port() {
        ServerSocket socket = serverSocket;
        return socket == null ? -1 : socket.getLocalPort();
    }

    /** 累计收到的帧数。 */
    public long receivedCount() {
        return receivedCount.get();
    }

    /** 累计已回应用级回执的帧数。 */
    public long ackedCount() {
        return ackedCount.get();
    }

    private void acceptLoop() {
        while (running) {
            Socket client;
            try {
                client = serverSocket.accept();
            } catch (IOException e) {
                if (running) {
                    System.err.println("[mllp] accept 失败：" + e.getMessage());
                }
                return;
            }
            Thread worker = new Thread(() -> handleConnection(client), "mllp-conn");
            worker.setDaemon(true);
            worker.start();
        }
    }

    private void handleConnection(Socket client) {
        // 同一条连接上，监听线程写传输级确认、工作线程写应用级回执，两者共用一条 TCP 连接：
        // 写必须串行化，否则两帧字节会在 socket 上交叉，对端的读包器就废了。
        Object writeLock = new Object();
        java.util.concurrent.atomic.AtomicInteger inFlight =
                new java.util.concurrent.atomic.AtomicInteger();
        try (Socket socket = client) {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(socketTimeoutMs);
            InputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = new BufferedOutputStream(socket.getOutputStream());
            MllpFrameCodec.FrameBuffer buffer = new MllpFrameCodec.FrameBuffer();
            byte[] chunk = new byte[READ_CHUNK];
            try {
                int read;
                while ((read = in.read(chunk)) != -1) {
                    for (byte[] payload : buffer.feed(Arrays.copyOf(chunk, read))) {
                        receivedCount.incrementAndGet();
                        // 传输级确认：只说明字节收全了，不含任何业务含义。字节形状由方言决定
                        // （裸 0x06 或 0B 06 1C 0D），但无论哪种方言都不改变下面那条应用级回执。
                        // 解耦模式下这一步<b>必须留在监听线程</b>：它对端只关心「字节收到了没有」，
                        // 业务处理慢不该拖住这一声应答。
                        byte[] transportAck = ackDialect.transportAckBytes();
                        synchronized (writeLock) {
                            out.write(transportAck);
                            out.flush();
                        }
                        if (pipeline == null) {
                            writeApplicationAck(writeLock, out, payload);
                        } else {
                            inFlight.incrementAndGet();
                            boolean queued = pipeline.submit(() -> {
                                try {
                                    writeApplicationAck(writeLock, out, payload);
                                } finally {
                                    inFlight.decrementAndGet();
                                }
                            });
                            if (!queued) {
                                // 队列满：这一帧没有应用级回执，平台按重推策略会再来一次。
                                // 这里不重试也不阻塞监听线程——阻塞等于把解耦白做，
                                // 而让平台重推本来就是背压生效时正确的业务行为。
                                inFlight.decrementAndGet();
                            }
                        }
                    }
                }
            } catch (IOException e) {
                // 读超时（SO_TIMEOUT）或对端复位。必须在这里停下来处理半帧，
                // 否则下面那句「连接结束」一打，这条报文就凭空消失了。
                if (running) {
                    System.err.println("[mllp] 连接结束：" + e.getMessage());
                }
            }
            // 半条报文：这条连接结束（或读超时）时缓冲区里还留着没等到帧尾的字节。
            drainPartialFrame(buffer, writeLock, out);
            awaitConnectionDrained(inFlight);
        } catch (IOException e) {
            if (running) {
                System.err.println("[mllp] 连接结束：" + e.getMessage());
            }
        }
    }

    /** 写一条应用级回执；处理链判成「本帧不回」时（连 MSH 都读不出）只记计数不写字节。 */
    private void writeApplicationAck(Object writeLock, OutputStream out, byte[] payload) {
        byte[] ack = dispatch(payload);
        if (ack == null) {
            return;
        }
        try {
            synchronized (writeLock) {
                out.write(ackDialect.frameApplicationAck(ack));
                out.flush();
            }
            ackedCount.incrementAndGet();
        } catch (IOException e) {
            // 对端已断开：这一帧的回执送不出去了。平台会按重推策略再来，
            // 台账里的行与幂等键都在（见 ArchiveIngestService），不会重复入账。
            System.err.println("[mllp] 应用级回执写出失败（" + e.getMessage() + "），等待对端重推");
        }
    }

    /**
     * 连接结束时按半条处理缓冲区里残留的字节：出一条 {@code AE + ERR}，然后清空。
     *
     * <p>三件事的顺序不能换：<b>先回执、再清缓冲、最后才关连接</b>。
     * 先清缓冲就回不出去了（回执需要读半帧里的 MSH 控制号）；
     * 先关连接则写不出去，而写不出去的表现是对端「发出去没有回执」——
     * 那正是加这一段要消灭的现象。
     *
     * <p>这一段只处理<b>本连接</b>的半帧。监听口本身不受影响：accept 循环在另一个线程上，
     * 本连接结束后它照样收下一条新连接，因此「一条坏报文」不会变成「监听口挂了」。
     */
    private void drainPartialFrame(MllpFrameCodec.FrameBuffer buffer, Object writeLock, OutputStream out) {
        if (!buffer.hasPartialFrame()) {
            return;
        }
        byte[] partial = buffer.partialPayload();
        buffer.reset();
        if (partialFrameHandler == null) {
            System.err.println("[mllp] 连接结束时缓冲区残留 " + partial.length
                    + " 字节未遇帧尾，按半条丢弃（未装配半帧处理链，不出应用级回执）");
            return;
        }
        byte[] ack;
        try {
            ack = partialFrameHandler.handle(partial, "连接结束或读超时，缓冲区残留未遇帧尾 0x1C 0x0D");
        } catch (Exception e) {
            System.err.println("[mllp] 半帧回执组装失败（" + e.getMessage() + "），本条不出应用级回执");
            return;
        }
        if (ack == null) {
            System.err.println("[mllp] 半帧连 MSH 都读不出，出不了可指向的回执，本条不出应用级回执");
            return;
        }
        try {
            synchronized (writeLock) {
                out.write(ackDialect.frameApplicationAck(ack));
                out.flush();
            }
            ackedCount.incrementAndGet();
            System.err.println("[mllp] 半帧已按 MSA=AE 回执（" + partial.length + " 字节），等待对端重投");
        } catch (IOException e) {
            System.err.println("[mllp] 半帧回执写出失败（" + e.getMessage() + "）；该条未入账，等待对端重投");
        }
    }

    /**
     * 连接关闭后等本连接的在途帧处理完，再关输出流。
     *
     * <p>不等的后果很具体：try-with-resources 会先关 socket，工作线程随后往已关闭的流写，
     * 每条在途帧都记一条「回执写出失败」，而这些帧其实<b>已经入账了</b>——
     * 次日对账看到的是「回执条数比台账条数少」，现场却查不出是哪几条。
     */
    private void awaitConnectionDrained(java.util.concurrent.atomic.AtomicInteger inFlight) {
        if (pipeline == null || inFlight.get() == 0) {
            return;
        }
        long deadline = System.nanoTime() + CONNECTION_DRAIN_TIMEOUT_MS * 1_000_000L;
        while (inFlight.get() > 0 && System.nanoTime() < deadline) {
            try {
                Thread.sleep(2L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        if (inFlight.get() > 0) {
            System.err.println("[mllp] 连接关闭时仍有 " + inFlight.get()
                    + " 帧在途（超过 " + CONNECTION_DRAIN_TIMEOUT_MS + "ms），这些帧已入账但回执未送出，"
                    + "次日对账时按控制号到台账里核对，不要按回执条数当成没收到");
        }
    }

    /**
     * 处理链异常不拖垮连接：一条坏报文不能带崩整批。
     *
     * <p>此处只记一行诊断并跳过该帧——真正的 AE / AR 回执由后续回执任务在本方法内补齐，
     * 那时才有足够信息指出是哪一段哪个字段出的问题。
     */
    private byte[] dispatch(byte[] payload) {
        try {
            return handler.handle(payload);
        } catch (Exception e) {
            System.err.println("[mllp] 报文处理失败（" + e.getMessage() + "），本帧不回应用级回执");
            return null;
        }
    }

    @Override
    public void close() {
        running = false;
        ServerSocket socket = serverSocket;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // 关闭失败不影响进程退出
            }
        }
    }
}