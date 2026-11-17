package cn.hospital.region.archive.mllp;

import cn.hospital.region.archive.ack.AckEchoHandler;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * 主干冒烟：真起一个监听口、真打一帧真实字节、真读回一条回执。
 *
 * <p>存在的理由是「主干端到端可跑」不能只靠单测断言：这条命令会打印
 * {@code received=1 acked=1}，运维在接入机上敲一遍就知道监听口和回执链路是通的，
 * 不必先配齐整套环境变量。不需要任何凭据，也不连任何真实院内系统。
 */
public final class MllpSmoke {

    private static final String LOCAL_AET = "CHSCOMMUNITY";
    private static final String REMOTE_AET = "REGIONAL_ARCHIVE";

    /** 一条形状完整的入站档案变更报文（MSH + PID），控制号固定便于复现比对。 */
    static final String SAMPLE_ER7 = String.join("\r",
            "MSH|^~\\&|REGIONAL_ARCHIVE|FACILITY_A|ARCHIVE_IN|FACILITY_A|20261020090000||ADT^A01^ADT_A01|SMOKE-CTRL-0001|P|2.5.1|||AL|NE||UNICODE UTF-8",
            "PID|1||PAT-0001^^^FACILITY_A^MR||张^三||19780315|M",
            "");

    private MllpSmoke() {
    }

    public static void main(String[] args) {
        System.exit(run(System.out));
    }

    /**
     * 跑一次「起监听口 → 发一帧 → 收回执」。
     *
     * @return 0 成功；1 失败
     */
    public static int run(PrintStream out) {
        MllpServer server = new MllpServer("127.0.0.1", 0, new AckEchoHandler(LOCAL_AET, REMOTE_AET));
        try {
            int port = server.start();
            out.println("[smoke] MLLP 监听口已起：127.0.0.1:" + port
                    + " localAet=" + LOCAL_AET + " remoteAet=" + REMOTE_AET);
            byte[] response = exchange(port, SAMPLE_ER7.getBytes(StandardCharsets.UTF_8));
            if (response == null) {
                out.println("[smoke] FAIL：未收到任何回执字节");
                return 1;
            }
            String ackEr7 = new String(response, StandardCharsets.UTF_8).replace('\r', '\n');
            out.println("[smoke] 回执报文：");
            out.print(ackEr7);
            out.println("[smoke] received=" + server.receivedCount() + " acked=" + server.ackedCount());
            return server.ackedCount() == 1 ? 0 : 1;
        } catch (Exception e) {
            out.println("[smoke] FAIL：" + e.getMessage());
            return 1;
        } finally {
            server.close();
        }
    }

    /**
     * 打一帧、读回一条应用级回执。
     *
     * <p>读法与监听口写法定序对应：先一个裸 {@code 0x06} 传输级确认，再一帧 MLLP 包着的 ACK 报文。
     *
     * @return ACK 报文的载荷字节；没读到返回 {@code null}
     */
    private static byte[] exchange(int port, byte[] payload) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 5000);
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            out.write(MllpFrameCodec.encode(payload));
            out.flush();

            InputStream in = socket.getInputStream();
            int transportAck = in.read();
            if (transportAck != MllpFrameCodec.ACK) {
                throw new IllegalStateException("传输级确认字节不是 0x06，实际是 0x"
                        + Integer.toHexString(transportAck));
            }
            return MllpFrameCodec.readFrame(in);
        }
    }
}