package cn.hospital.region.archive.mllp;

import cn.hospital.region.archive.ack.AckEchoHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 主干端到端契约：真监听口、真 socket、真帧字节。<b>不使用任何 mock 库</b>。
 *
 * <p>断言落在对端真正收到的东西上——字节序列与报文内容，而不是「某方法被调用过」。
 * 契约测试一旦通过，就说明「区域平台推一帧过来，这边逐条回一条它认得的回执」这条链路是通的。
 */
class MllpServerContractTest {

    private static final String LOCAL_AET = "CHSCOMMUNITY";
    private static final String REMOTE_AET = "REGIONAL_ARCHIVE";

    private static final String SAMPLE_MSH = "MSH|^~\\&|REGIONAL_ARCHIVE|FACILITY_A|ARCHIVE_IN|FACILITY_A"
            + "|20261020090000||ADT^A01^ADT_A01|CT-0001|P|2.5.1|||AL|NE||UNICODE UTF-8";
    private static final String SAMPLE_PID = "PID|1||PAT-0001^^^FACILITY_A^MR||张^三||19780315|M";

    private MllpServer server;
    private Socket client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MllpServer("127.0.0.1", 0, new AckEchoHandler(LOCAL_AET, REMOTE_AET));
        int port = server.start();
        client = new Socket();
        client.connect(new InetSocketAddress("127.0.0.1", port), 5000);
        client.setSoTimeout(5000);
    }

    @AfterEach
    void tearDown() {
        try {
            client.close();
        } catch (Exception ignored) {
            // 收尾失败不影响断言结论
        }
        server.close();
    }

    private static byte[] er7(String... segments) {
        ByteArrayOutputStream sb = new ByteArrayOutputStream();
        for (String segment : segments) {
            sb.writeBytes(segment.getBytes(StandardCharsets.UTF_8));
            sb.write('\r');
        }
        return sb.toByteArray();
    }

    private void send(byte[] payload) throws Exception {
        OutputStream out = client.getOutputStream();
        out.write(MllpFrameCodec.encode(payload));
        out.flush();
    }

    /** 读一条回执：先一个裸 0x06 传输级确认，再一帧 MLLP 包着的应用级 ACK。 */
    private String readAck() throws Exception {
        InputStream in = client.getInputStream();
        int transportAck = in.read();
        assertEquals(MllpFrameCodec.ACK, transportAck,
                "应用级回执之前必须先有传输级 0x06，实际是 0x" + Integer.toHexString(transportAck));
        byte[] payload = MllpFrameCodec.readFrame(in);
        assertNotNull(payload, "没读到应用级 ACK 帧");
        return new String(payload, StandardCharsets.UTF_8);
    }

    /** 取 MSA-n（MSA 段第 n 个字段；n=1 即回执代码）。 */
    private static String msaField(String ackEr7, int n) {
        String[] lines = ackEr7.split("\r", -1);
        for (String line : lines) {
            if (line.startsWith("MSA|")) {
                String[] parts = line.split("\\|", -1);
                return parts[n];
            }
        }
        throw new AssertionError("回执里没有 MSA 段：" + ackEr7);
    }

    @Test
    @DisplayName("真实帧往返：先回 0x06 再回 MSA-1=AA 的 ACK 报文，控制号与入站 MSH-10 一致")
    void test_real_frame_roundtrip_asserts_MSA_AA() throws Exception {
        send(er7(SAMPLE_MSH, SAMPLE_PID));

        String ack = readAck();

        assertEquals("AA", msaField(ack, 1), "MSA-1 应为 AA，实际回执：" + ack);
        assertEquals("CT-0001", msaField(ack, 2), "MSA-2 必须逐字复制入站 MSH-10");
        assertTrue(ack.startsWith("MSH|^~\\&|" + LOCAL_AET + "|" + REMOTE_AET + "|"),
                "回应 MSH-3/MSH-4 应为本院 AET 与区域平台 AET，实际回执：" + ack);
        assertTrue(ack.contains("|ACK^ACK^HL7_v2.5.1|"), "回应 MSH-9 应为 ACK，实际回执：" + ack);
        assertTrue(ack.contains("|P|2.5.1"), "回应 MSH-11/MSH-12 应复制入站处理标识与版本，实际：" + ack);

        assertEquals(1, server.receivedCount());
        assertEquals(1, server.ackedCount());
    }

    @Test
    @DisplayName("回执逐字复制入站 MSH-10：改了控制号，对端就认不出这条回执")
    void test_ack_msa2_equals_inbound_msh10_for_each_message() throws Exception {
        send(er7(SAMPLE_MSH.replace("CT-0001", "CT-9007"), SAMPLE_PID));

        String ack = readAck();

        assertEquals("CT-9007", msaField(ack, 2));
    }

    @Test
    @DisplayName("粘包：一次写两帧，两帧各回一条回执，互不吞并")
    void test_two_frames_in_one_write_get_two_acks() throws Exception {
        ByteArrayOutputStream both = new ByteArrayOutputStream();
        both.writeBytes(MllpFrameCodec.encode(er7(SAMPLE_MSH, SAMPLE_PID)));
        both.writeBytes(MllpFrameCodec.encode(er7(SAMPLE_MSH.replace("CT-0001", "CT-0002"), SAMPLE_PID)));
        client.getOutputStream().write(both.toByteArray());
        client.getOutputStream().flush();

        String first = readAck();
        String second = readAck();

        assertEquals("CT-0001", msaField(first, 2));
        assertEquals("CT-0002", msaField(second, 2));
        assertEquals(2, server.receivedCount());
        assertEquals(2, server.ackedCount());
    }

    @Test
    @DisplayName("半包：分两次写完一帧，仍回一条回执")
    void test_partial_frame_written_in_two_chunks_still_gets_one_ack() throws Exception {
        byte[] framed = MllpFrameCodec.encode(er7(SAMPLE_MSH, SAMPLE_PID));
        int cut = framed.length / 3;
        OutputStream out = client.getOutputStream();
        out.write(framed, 0, cut);
        out.flush();
        Thread.sleep(50);
        out.write(framed, cut, framed.length - cut);
        out.flush();

        String ack = readAck();

        assertEquals("AA", msaField(ack, 1));
        assertEquals("CT-0001", msaField(ack, 2));
        assertEquals(1, server.receivedCount());
    }

    @Test
    @DisplayName("处理链抛异常不拖垮连接：坏帧只丢它自己，下一帧照常回执")
    void test_handler_failure_does_not_break_connection() throws Exception {
        server.close();
        MllpServer strict = new MllpServer("127.0.0.1", 0, payload -> {
            String er7 = new String(payload, StandardCharsets.UTF_8);
            if (!er7.contains("PID|")) {
                throw new IllegalStateException("缺 PID 段");
            }
            return new AckEchoHandler(LOCAL_AET, REMOTE_AET).handle(payload);
        });
        client.close();
        int port = strict.start();
        client = new Socket();
        client.connect(new InetSocketAddress("127.0.0.1", port), 5000);
        client.setSoTimeout(5000);
        server = strict;

        send(er7(SAMPLE_MSH));
        send(er7(SAMPLE_MSH.replace("CT-0001", "CT-0003"), SAMPLE_PID));

        // 第一帧：只有传输级 0x06，没有应用级 ACK；第二个字节仍是传输级 0x06，说明连接没断
        InputStream in = client.getInputStream();
        assertEquals(MllpFrameCodec.ACK, in.read());
        assertEquals(MllpFrameCodec.ACK, in.read(), "坏帧之后的传输级确认应继续到达");
        byte[] ack = MllpFrameCodec.readFrame(in);

        assertEquals("CT-0003", msaField(new String(ack, StandardCharsets.UTF_8), 2));
        assertEquals(2, server.receivedCount(), "两条报文都收到了");
        assertEquals(1, server.ackedCount(), "只有能处理的那条回了应用级回执");
    }
}