package cn.hospital.region.archive.mllp;

import cn.hospital.region.archive.ack.AckComposer;
import cn.hospital.region.archive.ack.ArchiveAckHandler;
import cn.hospital.region.archive.ack.MessageReceiptBook;
import cn.hospital.region.archive.redelivery.RedeliveryIntentSet;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 回执契约冒烟：一条命令，两条报文，看清「传输级确认」与「应用级回执」各是什么。
 *
 * <p>一次连发两条真实帧（一条字段齐全、一条缺 MSH-10），打印：
 * <ol>
 *   <li>本方言下传输级确认的真实字节（带帧 {@code 0B 06 1C 0D} 或裸 {@code 06}）；</li>
 *   <li>每条报文的 MSA-1 与 MSA-2，以及 AE 那条带的 ERR 定位；</li>
 *   <li>应用级账页的四态分布与可重投条数——<b>已落地恒为 0</b>，因为还没有人点确认。</li>
 * </ol>
 *
 * <p>这条命令不需要任何凭据、不连任何真实院内系统，只在本机回环口上自打自收。
 * 运维在接入机上敲一遍就知道「监听口活着、方言配对了、回执逐条对得上」。
 */
public final class AckContractSmoke {

    private static final String LOCAL_AET = "CHSCOMMUNITY";
    private static final String REMOTE_AET = "REGIONAL_ARCHIVE";

    /** 字段齐全的健康档案变更报文。 */
    static final String VALID_ER7 = String.join("\r",
            "MSH|^~\\&|REGIONAL_ARCHIVE|FACILITY_A|ARCHIVE_IN|FACILITY_A|20261020090000"
                    + "||ADT^A01^ADT_A01|SMOKE-CTRL-0001|P|2.5.1|||AL|NE||UNICODE UTF-8",
            "PID|1||PAT-0001^^^FACILITY_A^MR||张^三||19780315|M",
            "");

    /** 缺 MSH-10 报文控制号：回 AE，并在 ERR 里指出是 MSH 的第 10 个字段。 */
    static final String MISSING_CONTROL_ID_ER7 = String.join("\r",
            "MSH|^~\\&|REGIONAL_ARCHIVE|FACILITY_A|ARCHIVE_IN|FACILITY_A|20261020090000"
                    + "||ADT^A01^ADT_A01||P|2.5.1|||AL|NE||UNICODE UTF-8",
            "PID|1||PAT-0002^^^FACILITY_A^MR||李^四||19820106|F",
            "");

    private AckContractSmoke() {
    }

    public static void main(String[] args) {
        AckDialect dialect = AckDialect.FRAMED_ACK;
        if (args.length > 0) {
            try {
                dialect = AckDialect.fromWireName(args[0]);
            } catch (IllegalArgumentException e) {
                System.err.println(e.getMessage());
                System.exit(64);
                return;
            }
        }
        System.exit(run(dialect, System.out));
    }

    /**
     * 跑一次「起监听口 → 连发两帧 → 收两组回执 → 打印账页」。
     *
     * @param dialect 回执方言
     * @param out     输出流
     * @return 0 全部符合预期；1 有出入
     */
    public static int run(AckDialect dialect, PrintStream out) {
        MessageReceiptBook book = new MessageReceiptBook();
        RedeliveryIntentSet intents = new RedeliveryIntentSet();
        MllpServer server = new MllpServer("127.0.0.1", 0,
                new ArchiveAckHandler(new AckComposer(LOCAL_AET, REMOTE_AET), book, intents),
                5000, dialect);
        try {
            int port = server.start();
            out.println("[smoke] 监听口已起：127.0.0.1:" + port
                    + " 回执方言=" + dialect.wireName()
                    + " localAet=" + LOCAL_AET + " remoteAet=" + REMOTE_AET);
            out.println("[smoke] 传输级确认字节（本方言实测形状）：" + hex(dialect.transportAckBytes()));

            List<String> acks = exchange(port, dialect);
            out.println("[smoke] 回执报文条数=" + acks.size() + "（本轮发 2 帧：一条字段齐全、一条缺 MSH-10）");
            for (String ack : acks) {
                out.println("[smoke]   MSA-1=" + msaField(ack, 1)
                        + " MSA-2=" + display(msaField(ack, 2))
                        + " ERR=" + (hasSegment(ack, "ERR") ? "有（" + errLocation(ack) + "）" : "无"));
            }
            out.println("[smoke] 应用级账页：已接收=" + book.countByState(MessageReceiptBook.AppState.RECEIVED)
                    + " 待确认=" + book.countByState(MessageReceiptBook.AppState.PENDING_CONFIRM)
                    + " 已落地=" + book.landedCount()
                    + " 拒收=" + book.countByState(MessageReceiptBook.AppState.REJECTED)
                    + "（已落地为 0：还没人点确认）");
            out.println("[smoke] 可重投条目=" + intents.size()
                    + "（AR 类不重投） 传输级已确认帧数=" + server.receivedCount());

            boolean ok = acks.size() == 2
                    && AckComposer.CODE_AA.equals(msaField(acks.get(0), 1))
                    && AckComposer.CODE_AE.equals(msaField(acks.get(1), 1))
                    && book.landedCount() == 0
                    && intents.size() == 1;
            out.println(ok ? "[smoke] PASS" : "[smoke] FAIL：回执条数或判定与预期不符");
            return ok ? 0 : 1;
        } catch (Exception e) {
            out.println("[smoke] FAIL：" + e.getMessage());
            return 1;
        } finally {
            server.close();
        }
    }

    /** 一次连发两帧（粘包），按方言读回两组「传输级确认 + 应用级回执」。 */
    private static List<String> exchange(int port, AckDialect dialect) throws Exception {
        ByteArrayOutputStream both = new ByteArrayOutputStream();
        both.writeBytes(MllpFrameCodec.encode(VALID_ER7.getBytes(StandardCharsets.UTF_8)));
        both.writeBytes(MllpFrameCodec.encode(MISSING_CONTROL_ID_ER7.getBytes(StandardCharsets.UTF_8)));

        List<String> acks = new ArrayList<>();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 5000);
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            out.write(both.toByteArray());
            out.flush();

            InputStream in = socket.getInputStream();
            for (int i = 0; i < 2; i++) {
                byte[] transport = in.readNBytes(dialect.transportAckBytes().length);
                if (transport.length != dialect.transportAckBytes().length) {
                    throw new IllegalStateException("第 " + (i + 1) + " 帧的传输级确认字节不足 "
                            + dialect.transportAckBytes().length + " 字节");
                }
                byte[] payload = MllpFrameCodec.readFrame(in);
                if (payload == null) {
                    throw new IllegalStateException("第 " + (i + 1) + " 帧没有读到应用级回执");
                }
                acks.add(new String(payload, StandardCharsets.UTF_8));
            }
        }
        return acks;
    }

    private static String msaField(String ackEr7, int n) {
        for (String line : ackEr7.split("\r")) {
            if (line.startsWith("MSA|")) {
                return line.split("\\|", -1)[n];
            }
        }
        throw new IllegalStateException("回执里没有 MSA 段：" + ackEr7);
    }

    private static boolean hasSegment(String ackEr7, String name) {
        for (String line : ackEr7.split("\r")) {
            if (line.startsWith(name + "|")) {
                return true;
            }
        }
        return false;
    }

    /** ERR-1 的位置引用（码^段^字段），供人一眼看出错在哪。 */
    private static String errLocation(String ackEr7) {
        for (String line : ackEr7.split("\r")) {
            if (line.startsWith("ERR|")) {
                String[] parts = line.split("\\|", -1);
                String[] components = parts[1].split("\\^", -1);
                return components.length >= 3 ? components[1] + "-" + components[2] : components[0];
            }
        }
        return "";
    }

    private static String display(String value) {
        return value == null || value.isEmpty() ? "(空)" : value;
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(String.format("0x%02X", b));
        }
        return sb.toString();
    }
}