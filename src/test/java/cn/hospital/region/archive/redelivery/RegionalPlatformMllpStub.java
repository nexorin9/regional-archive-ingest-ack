package cn.hospital.region.archive.redelivery;

import cn.hospital.region.archive.ack.AckComposer;
import cn.hospital.region.archive.ack.InboundHeader;
import cn.hospital.region.archive.ack.RejectionReason;
import cn.hospital.region.archive.mllp.MllpFrameCodec;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 区域平台 MLLP 入口的<b>协议替身</b>：真实 {@link ServerSocket} + 真实帧字节，
 * <b>不用任何 mock 库</b>。
 *
 * <p>它是「对端」那一半：收一条按帧发来的报文，用与生产同一套 {@link AckComposer}
 * 组一条应用级回执发回去。这正是参考实现里对端侧监听口做的事，因此回执的
 * 字段位置、分隔符与字符集声明都与真实区域平台一致，而不是本测试自造的字符串。
 *
 * <p><b>它是测试数据层</b>：产品代码里的 {@code ARCHIVE_REDELIVERY_HOST} 在院内指向
 * 区域平台（或院内接口引擎）的真实 MLLP 入口；这串回环地址只存在于测试源码，
 * 不是产品的兜底路径。
 *
 * <p>可造的失败：{@link #setMsaCode} 回 AE/AR、{@link #setNakTransport} 回传输级 NAK、
 * {@link #setSilent} 收下不回（造读超时）、{@link #setGarbageResponse} 回一条没有 MSA 段的报文。
 */
final class RegionalPlatformMllpStub implements AutoCloseable {

    /** 对端回执方言。 */
    enum Dialect {
        /** 规范方言：先回传输级 {@code 0x06}，再回一帧应用级回执。 */
        TRANSPORT_ACK_THEN_FRAME,
        /** 裸回执方言：直接回一帧应用级回执，前面没有 {@code 0x06}。 */
        FRAME_ONLY
    }

    private final ServerSocket server;
    private final AckComposer composer;
    private final List<String> received = new CopyOnWriteArrayList<>();
    private final AtomicInteger connectionCount = new AtomicInteger();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Thread acceptor;

    private volatile Dialect dialect = Dialect.TRANSPORT_ACK_THEN_FRAME;
    private volatile String msaCode = AckComposer.CODE_AA;
    private volatile boolean nakTransport = false;
    private volatile boolean silent = false;
    private volatile boolean garbage = false;
    private volatile String peerAet = "REGIONAL_ARCHIVE";
    private volatile String localAet = "REGIONAL_ARCHIVE";

    private RegionalPlatformMllpStub(ServerSocket server, Clock clock) {
        this.server = server;
        this.composer = new AckComposer("REGIONAL_ARCHIVE", "ARCHIVE_IN", clock);
        this.acceptor = new Thread(this::acceptLoop, "regional-platform-mllp-stub");
        this.acceptor.setDaemon(true);
    }

    /** 在回环地址随机端口上起一个替身平台入口。 */
    static RegionalPlatformMllpStub start(Clock clock) throws IOException {
        ServerSocket server = new ServerSocket();
        server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        RegionalPlatformMllpStub stub = new RegionalPlatformMllpStub(server, clock);
        stub.acceptor.start();
        return stub;
    }

    /** 替身地址（对应产品侧的 {@code ARCHIVE_REDELIVERY_HOST/PORT}）。 */
    String host() {
        return server.getInetAddress().getHostAddress();
    }

    int port() {
        return server.getLocalPort();
    }

    RegionalPlatformMllpStub setDialect(Dialect dialect) {
        this.dialect = dialect;
        return this;
    }

    RegionalPlatformMllpStub setMsaCode(String code) {
        this.msaCode = code;
        return this;
    }

    RegionalPlatformMllpStub setNakTransport(boolean nak) {
        this.nakTransport = nak;
        return this;
    }

    RegionalPlatformMllpStub setSilent(boolean silent) {
        this.silent = silent;
        return this;
    }

    RegionalPlatformMllpStub setGarbageResponse(boolean garbage) {
        this.garbage = garbage;
        return this;
    }

    RegionalPlatformMllpStub setPeerAet(String peerAet) {
        this.peerAet = peerAet;
        return this;
    }

    RegionalPlatformMllpStub setLocalAet(String localAet) {
        this.localAet = localAet;
        return this;
    }

    /** 收到的报文原文（按 MSH-18 声明的字符集解码），按到达顺序。 */
    List<String> receivedPayloads() {
        return List.copyOf(received);
    }

    int receivedCount() {
        return received.size();
    }

    int connectionCount() {
        return connectionCount.get();
    }

    // =====================================================================
    // 接收
    // =====================================================================

    private void acceptLoop() {
        while (!closed.get()) {
            try (Socket socket = server.accept()) {
                connectionCount.incrementAndGet();
                handle(socket);
            } catch (IOException e) {
                if (closed.get()) {
                    return;
                }
                // 单条连接出错不影响替身继续服务下一条
            }
        }
    }

    private void handle(Socket socket) throws IOException {
        socket.setTcpNoDelay(true);
        InputStream in = socket.getInputStream();
        OutputStream out = socket.getOutputStream();
        byte[] payload;
        while ((payload = MllpFrameCodec.readFrame(in)) != null) {
            received.add(decode(payload));
            if (silent) {
                // 收下但不回：制造读超时
                continue;
            }
            if (nakTransport) {
                out.write(MllpFrameCodec.NAK);
                out.flush();
                continue;
            }
            byte[] ack = ackFor(payload);
            if (dialect == Dialect.TRANSPORT_ACK_THEN_FRAME) {
                out.write(MllpFrameCodec.ACK);
                out.flush();
            }
            out.write(MllpFrameCodec.encode(ack));
            out.flush();
        }
    }

    /** 用与生产同一套组装器生成应用级回执（对端侧做的事）。 */
    private byte[] ackFor(byte[] payload) {
        if (garbage) {
            return ("MSH|^~\\&|" + localAet + "|" + peerAet + "|" + peerAet + "|FACILITY_B|20261021090000"
                    + "||ACK|ACK-STUB-0001|P|2.5.1\rZZZ|1\r")
                    .getBytes(StandardCharsets.UTF_8);
        }
        String er7 = decode(payload);
        InboundHeader header = InboundHeader.parse(er7);
        // 拒收必须带 ERR 段（compose 的硬约束），因此 AE/AR 走 composeRejected 这条正路
        if (AckComposer.CODE_AA.equals(msaCode)) {
            return composer.composeAccepted(header).getBytes(StandardCharsets.UTF_8);
        }
        RejectionReason reason = switch (msaCode) {
            case AckComposer.CODE_AE -> RejectionReason.SEMANTIC_ALIGN_FAILED;
            case AckComposer.CODE_AR -> RejectionReason.IDENTITY_CONFLICT;
            default -> throw new IllegalArgumentException("不支持的 MSA-1: " + msaCode);
        };
        return composer.composeRejected(header, reason).getBytes(StandardCharsets.UTF_8);
    }

    /** 按报文自述的 MSH-18 解码；取不到时退回 UTF-8。 */
    private static String decode(byte[] payload) {
        Charset charset = StandardCharsets.UTF_8;
        try {
            charset = cn.hospital.region.archive.hl7.CharsetResolver
                    .resolve(InboundHeader.parse(new String(payload, StandardCharsets.UTF_8))
                            .characterSet()).charset;
        } catch (RuntimeException e) {
            charset = StandardCharsets.UTF_8;
        }
        return new String(payload, charset);
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