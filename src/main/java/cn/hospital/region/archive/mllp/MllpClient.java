package cn.hospital.region.archive.mllp;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 出站 MLLP 客户端 —— 发一帧报文、读回一条应用级回执，<b>连接中途断了自动重建再试同一操作</b>。
 *
 * <h2>主路径：从源产品搬过来的那条链</h2>
 * <p>本类照搬参考实现 {@code dcm4che-conf-ldap} 的 {@code ReconnectDirContext}：
 * <pre>
 *   try   { return ctx.getAttributes(name); }
 *   catch (NamingException e) {
 *       if (!isLdap_connection_has_been_closed(e)) throw e;   // ① 只认「连接没了」这一类
 *       reconnect();                                            // ② 记日志 → close() → 重建
 *       return ctx.getAttributes(name);                         // ③ 重试同一个操作
 *   }
 * </pre>
 * 三段语义逐段落地到 socket 上：
 * <ol>
 *   <li><b>分类</b> —— {@link #isConnectionClosed(Throwable)} 判「这是连接断了」还是「这操作本身
 *       就不该做」。前者才重连，后者原样上抛（重连一百次也是同一结果）；</li>
 *   <li><b>重建</b> —— {@code reconnect()}：记一行 INFO（对齐源实现的
 *       {@code "Connection to {} broken - reconnect"}）、关掉旧 socket、建一条新的；</li>
 *   <li><b>重试同一操作</b> —— 退避后从头再发一遍<b>同一份字节</b>，不是「继续上次那半截」。</li>
 * </ol>
 * 源产品只在 LDAP 目录上下文上实现了这套重连，MLLP 侧原本只有「瞬时重试一次」的
 * {@code readACK()}；本轮把同一套语义补到 MLLP 出站上，因为院内前置机与区域平台之间的
 * TCP 断开是真会发生的常态（对方夜里重启、网络设备老化、端口被网关回收）。
 *
 * <h2>耗尽不静默</h2>
 * <p>用满 {@link MllpReconnectPolicy#maxAttempts()} 仍不成功，抛
 * {@link MllpReconnectExhaustedException}（受检）。报文原文仍留在台账里，条目仍在重投队列里，
 * 交由退避重投与重启后的恢复扫描接手 —— 本类绝不把它当成「已经投出去了」。
 *
 * <h2>两种对端回执方言都读</h2>
 * <p>对端可能先发传输级 {@code 0x06} 再发带帧的应用级回执，也可能（{@code bare_ack} 方言）
 * 直接把应用级回执打进帧里。本类两种都读；首字节是 {@code 0x15}（传输级 NAK）时返回
 * {@code null}，表示「收到了一个否定答复」——那是<b>答复</b>，不是异常，不触发重连。
 *
 * <h2>线程安全</h2>
 * <p>本类<b>无状态</b>：每次 {@link #exchange(byte[])} 自带一条短生命周期连接，
 * 可安全地被多个线程同时调用。计数器仅供运行指标与测试断言。
 */
public final class MllpClient {

    private static final Logger LOG = Logger.getLogger(MllpClient.class.getName());

    /** 退避的注入口：生产用 {@link Thread#sleep(long)}，测试用「记账不真睡」。 */
    @FunctionalInterface
    public interface BackoffSleeper {

        void sleep(long millis) throws InterruptedException;

        static BackoffSleeper real() {
            return millis -> {
                if (millis > 0) {
                    Thread.sleep(millis);
                }
            };
        }

        /** 不真睡，只把每次退避时长记进返回值（测试用，避免用例挂在真实退避上）。 */
        static BackoffSleeper recording(java.util.List<Long> recorded) {
            return recorded::add;
        }
    }

    private final String host;
    private final int port;
    private final MllpReconnectPolicy policy;
    private final BackoffSleeper sleeper;

    /** 连接重建次数（成功路径恒为 0）。 */
    private final AtomicInteger rebuildCount = new AtomicInteger();
    /** 最近一次 {@link #exchange(byte[])} 实际用掉的尝试次数。 */
    private final AtomicInteger lastAttemptCount = new AtomicInteger();

    public MllpClient(String host, int port, MllpReconnectPolicy policy) {
        this(host, port, policy, BackoffSleeper.real());
    }

    public MllpClient(String host, int port, MllpReconnectPolicy policy, BackoffSleeper sleeper) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("MLLP 对端主机不得为空（来自 ARCHIVE_REDELIVERY_HOST）");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("MLLP 对端端口须在 1..65535，实际=" + port);
        }
        this.host = host.trim();
        this.port = port;
        this.policy = Objects.requireNonNull(policy, "重连策略不得为 null");
        this.sleeper = Objects.requireNonNull(sleeper, "退避实现不得为 null");
    }

    /** 本端连的是谁（进日志与 {@code --check-config} 摘要，不含凭据）。 */
    public String endpoint() {
        return host + ":" + port;
    }

    public MllpReconnectPolicy policy() {
        return policy;
    }

    /** 累计重建连接的次数。 */
    public int rebuildCount() {
        return rebuildCount.get();
    }

    /** 最近一次调用的尝试次数（含首次）。 */
    public int lastAttemptCount() {
        return lastAttemptCount.get();
    }

    // =====================================================================
    // 主路径：发一帧 → 读回执，断了就重建再来一遍
    // =====================================================================

    /**
     * 把一段 ER7 报文按 MLLP 帧发给对端，读回对端的应用级回执。
     *
     * @param payload 报文载荷字节（字符集已由调用方按 MSH-18 处理好）
     * @return 对端回执报文帧内字节；<b>只有传输级 NAK（0x15）返回 {@code null}</b>
     *         （那是明确答复，不是失败）；对端一声不吭就关连接算断线，触发重连
     * @throws MllpReconnectExhaustedException 重试上限内始终没能完成这一次交换
     * @throws MllpProtocolException          对端首字节不是 ACK/NAK/帧起始 —— 重连也没用
     * @throws IOException                    其它传输错误（含不可重连的调用方错误）
     */
    public byte[] exchange(byte[] payload) throws IOException {
        Objects.requireNonNull(payload, "payload 不得为 null");
        byte[] framed = MllpFrameCodec.encode(payload);
        int attempt = 0;
        int rebuildsThisCall = 0;
        IOException lastFailure = null;

        while (attempt < policy.maxAttempts()) {
            attempt++;
            try (Socket socket = open()) {
                socket.setSoTimeout(policy.readTimeoutMs());
                OutputStream out = socket.getOutputStream();
                out.write(framed);
                out.flush();
                byte[] ack = readPeerResponse(socket.getInputStream());
                lastAttemptCount.set(attempt);
                return ack;
            } catch (IOException e) {
                if (!isConnectionClosed(e)) {
                    // 不是「连接断了」：原样上抛，不烧重试预算（对齐源实现的分类判定）
                    lastAttemptCount.set(attempt);
                    throw e;
                }
                lastFailure = e;
                lastAttemptCount.set(attempt);
                if (attempt >= policy.maxAttempts()) {
                    break;
                }
                reconnect(attempt, e);
                rebuildsThisCall++;
            }
        }
        throw new MllpReconnectExhaustedException(endpoint(), policy.maxAttempts(),
                rebuildsThisCall, lastFailure);
    }

    /**
     * 源实现 {@code reconnect()} 的等价体：记一行 → 关旧连接 → 建新连接 → 退避。
     *
     * <p>顺序与源实现一致（{@code close()} 在重建之前），且退避放在重建<b>之前</b>：
     * 对端刚重启时立刻重连大概率再被拒一次，等一下更有用。
     */
    private void reconnect(int failedAttempt, IOException cause) {
        LOG.log(Level.INFO, "Connection to {} broken - reconnect（第 {1}/{2} 次失败后重建）: {3}",
                new Object[]{endpoint(), failedAttempt, policy.maxAttempts(),
                        cause.getClass().getSimpleName()});
        rebuildCount.incrementAndGet();
        long delay = policy.backoffDelayMs(failedAttempt);
        try {
            sleeper.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("重连退避被中断，放弃本次重投: " + endpoint(), e);
        }
    }

    /** 建一条到对端的新连接。 */
    private Socket open() throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), policy.connectTimeoutMs());
            socket.setTcpNoDelay(true);
            return socket;
        } catch (IOException e) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // 建连失败的 socket 关闭异常不影响结论
            }
            throw e;
        }
    }

    /**
     * 读对端的应用级回执（两种方言都兼容）。
     *
     * <p><b>NAK 与 EOF 必须分开</b>：{@code 0x15} 是对端明确给的否定<b>答复</b>
     * （重发同一份字节只会再被拒一次），而「一个字节都没回就读到了文件尾」是<b>连接断了</b>
     * （重建后重发很可能就成功了）。把后者也当成答复返回 {@code null}，
     * 断线自愈就永远等不到触发条件 —— 这是本任务落地时实测抓到的真实缺陷。
     *
     * @return 回执报文帧内字节；<b>只有传输级 NAK 返回 {@code null}</b>
     * @throws EOFException        对端在给出任何答复前就关闭了连接（可重连）
     * @throws MllpProtocolException 首字节不是 ACK/NAK/帧起始（不可重连）
     */
    public static byte[] readPeerResponse(InputStream in) throws IOException {
        byte[] first = in.readNBytes(1);
        if (first.length == 0) {
            throw new EOFException("对端在建连后未回任何字节，连接已被对端关闭");
        }
        int head = first[0] & 0xFF;
        if (head == MllpFrameCodec.NAK) {
            LOG.log(Level.WARNING, "收到传输级 NAK（0x15）");
            return null;
        }
        if (head == MllpFrameCodec.ACK) {
            // 规范方言：0x06 之后跟一帧带帧的应用级回执
            return requireFrame(MllpFrameCodec.readFrame(in), "传输级 ACK 之后");
        }
        if (head == MllpFrameCodec.START_BLOCK) {
            // bare_ack 方言：对端直接把应用级回执打进帧里，前面没有 0x06
            return readFrameStartingWith(in, first);
        }
        throw new MllpProtocolException(
                "对端首字节既不是 ACK(0x06) 也不是帧起始(0x0B)：0x" + Integer.toHexString(head));
    }

    /** 首字节已经是 {@code 0x0B} 时，把这一帧读完。 */
    private static byte[] readFrameStartingWith(InputStream in, byte[] firstBlock) throws IOException {
        MllpFrameCodec.FrameBuffer buffer = new MllpFrameCodec.FrameBuffer();
        List<byte[]> frames = buffer.feed(firstBlock);
        while (frames.isEmpty()) {
            byte[] more = in.readNBytes(1);
            if (more.length == 0) {
                throw new EOFException("对端回执帧尚未收全就关闭了连接");
            }
            frames = buffer.feed(more);
        }
        return frames.get(0);
    }

    private static byte[] requireFrame(byte[] frame, String where) throws EOFException {
        if (frame == null) {
            throw new EOFException("对端在" + where + "关闭了连接，回执没读完");
        }
        return frame;
    }

    // =====================================================================
    // 「连接断了」判定 —— 对齐源实现的 isLdap_connection_has_been_closed
    // =====================================================================

    /**
     * 这个异常是不是「连接没了，重建还有用」。
     *
     * <p>判为<b>可重连</b>的：
     * <ul>
     *   <li>{@link ConnectException} / {@link SocketException} —— 连不上、被拒、连接被复位
     *       （{@code Broken pipe} 落在这一类：写报文时对端刚好关连接）；</li>
     *   <li>{@link EOFException} —— 对端在帧边界上干净地关了连接（触发语义取自参考实现
     *       {@code MLLPConnection} 的 “Connection closed by receiver”）；</li>
     *   <li>{@link SocketTimeoutException} —— 建连或读回执超时，属于网络抖动；</li>
     *   <li>{@link MllpFrameCodec.MllpFrameException} —— 帧中途断流，重连重发整帧即可。</li>
     * </ul>
     * <p>其余一律判为<b>不可重连</b>（含 {@link MllpProtocolException}）：
     * 重发同一份字节只会得到同一个结果，不如把预算留给真正的抖动。
     */
    public static boolean isConnectionClosed(Throwable t) {
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            if (cur instanceof ConnectException
                    || cur instanceof SocketException
                    || cur instanceof EOFException
                    || cur instanceof SocketTimeoutException
                    || cur instanceof MllpFrameCodec.MllpFrameException) {
                return true;
            }
            if (cur instanceof MllpProtocolException) {
                return false;
            }
            if (cur.getCause() == cur) {
                break;
            }
        }
        return false;
    }
}
