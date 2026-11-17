package cn.hospital.region.archive.mllp;

import cn.hospital.region.archive.ack.AckComposer;
import cn.hospital.region.archive.ack.ArchiveAckHandler;
import cn.hospital.region.archive.ack.MessageReceiptBook;
import cn.hospital.region.archive.hl7.Delimiters;
import cn.hospital.region.archive.hl7.Hl7Message;
import cn.hospital.region.archive.hl7.Hl7Parser;
import cn.hospital.region.archive.redelivery.RedeliveryIntentSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回执方言契约测试 —— 断言落在对端真正收到的<b>帧字节</b>上，<b>不使用任何 mock 库</b>。
 *
 * <p>两条方言的差别只有一处：传输级确认字节的形状（{@code 0B 06 1C 0D} 对裸 {@code 06}）。
 * 应用级 ACK 报文在两种方言下都是加 MLLP 帧发出的，内容逐字节相同——把两件事分开断言，
 * 是因为本产品最容易犯的错就是把传输级 0x06 当成业务结果。
 */
class AckDialectContractTest {

    private static final String LOCAL_AET = "CHSCOMMUNITY";
    private static final String REMOTE_AET = "REGIONAL_ARCHIVE";

    /** 字段齐全的健康档案变更报文。 */
    private static final String VALID = String.join("\r",
            "MSH|^~\\&|REGIONAL_ARCHIVE|FACILITY_A|ARCHIVE_IN|FACILITY_A|20261020090000"
                    + "||ADT^A01^ADT_A01|CTRL-20261020-0001|P|2.5.1|||AL|NE||UNICODE UTF-8",
            "PID|1||PAT-0001^^^FACILITY_A^MR||张^三||19780315|M",
            "");

    /** 缺 MSH-10：AE + ERR 指向 MSH 第 10 个字段（控制号）。 */
    private static final String NO_CONTROL_ID = String.join("\r",
            "MSH|^~\\&|REGIONAL_ARCHIVE|FACILITY_A|ARCHIVE_IN|FACILITY_A|20261020090000"
                    + "||ADT^A01^ADT_A01||P|2.5.1|||AL|NE||UNICODE UTF-8",
            "PID|1||PAT-0002^^^FACILITY_A^MR||李^四||19820106|F",
            "");

    /** MSH-18 声明本端不认识的字符集：AR，重投没有意义。 */
    private static final String UNKNOWN_CHARSET = String.join("\r",
            "MSH|^~\\&|REGIONAL_ARCHIVE|FACILITY_A|ARCHIVE_IN|FACILITY_A|20261020090000"
                    + "||ADT^A01^ADT_A01|CTRL-20261020-0003|P|2.5.1|||AL|NE||MARC-8",
            "PID|1||PAT-0003^^^FACILITY_A^MR||王^五||19900101|M",
            "");

    private static Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-10-20T09:00:00Z"), ZoneOffset.UTC);
    }

    /** 一组对接好的端口与账页：真实 ServerSocket + 真实处理链。 */
    private static final class Harness implements AutoCloseable {
        final MessageReceiptBook book = new MessageReceiptBook();
        final RedeliveryIntentSet intents = new RedeliveryIntentSet();
        final MllpServer server;
        final int port;

        Harness(AckDialect dialect) throws Exception {
            server = new MllpServer("127.0.0.1", 0,
                    new ArchiveAckHandler(
                            new AckComposer(LOCAL_AET, REMOTE_AET, fixedClock()), book, intents),
                    5000, dialect);
            port = server.start();
        }

        /** 连发若干帧，返回每条的应用级 ACK 文本；传输级确认字节逐条读掉并校验形状。 */
        List<String> sendAndReadAcks(AckDialect dialect, String... messages) throws Exception {
            ByteArrayOutputStream batch = new ByteArrayOutputStream();
            for (String message : messages) {
                batch.writeBytes(MllpFrameCodec.encode(message.getBytes(StandardCharsets.UTF_8)));
            }
            List<String> acks = new java.util.ArrayList<>();
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 5000);
                socket.setSoTimeout(5000);
                OutputStream out = socket.getOutputStream();
                out.write(batch.toByteArray());
                out.flush();

                InputStream in = socket.getInputStream();
                for (int i = 0; i < messages.length; i++) {
                    byte[] expected = dialect.transportAckBytes();
                    byte[] actual = in.readNBytes(expected.length);
                    assertArrayEquals(expected, actual,
                            "第 " + (i + 1) + " 条的传输级确认字节不符（方言 " + dialect.wireName() + "）");
                    byte[] payload = MllpFrameCodec.readFrame(in);
                    assertNotNull(payload, "第 " + (i + 1) + " 条没读到应用级 ACK 帧");
                    acks.add(new String(payload, StandardCharsets.UTF_8));
                }
            }
            return acks;
        }

        @Override
        public void close() {
            server.close();
        }
    }

    @Test
    @DisplayName("framed_ack：传输级确认是完整一帧 0B 06 1C 0D，随后才是加帧的应用级 ACK")
    void test_framed_ack_returns_0B_06_1C_0D() throws Exception {
        try (Harness h = new Harness(AckDialect.FRAMED_ACK)) {
            try (Socket socket = new Socket("127.0.0.1", h.port)) {
                socket.setSoTimeout(5000);
                socket.getOutputStream().write(MllpFrameCodec.encode(VALID.getBytes(StandardCharsets.UTF_8)));
                socket.getOutputStream().flush();

                InputStream in = socket.getInputStream();
                byte[] transport = in.readNBytes(4);
                assertArrayEquals(new byte[]{0x0B, 0x06, 0x1C, 0x0D}, transport,
                        "framed_ack 的传输级确认必须是完整一帧，不能是裸 0x06");
                assertEquals(4, transport.length);

                byte[] ack = MllpFrameCodec.readFrame(in);
                assertNotNull(ack, "传输级确认之后必须跟一条应用级 ACK");
                assertTrue(ack.length > 0 && new String(ack, StandardCharsets.UTF_8).startsWith("MSH|"),
                        "应用级 ACK 也是加帧发出的，帧内首段必须是 MSH");
                String er7 = new String(ack, StandardCharsets.UTF_8);
                assertEquals("AA", msaField(er7, 1), "字段齐全的报文应回 AA：" + printable(er7));
                assertEquals("CTRL-20261020-0001", msaField(er7, 2), "MSA-2 必须逐字复制入站 MSH-10");
            }
        }
    }

    @Test
    @DisplayName("bare_ack：只发一个 0x06，紧跟着就是应用级 ACK 的帧头，不多写两个帧字节")
    void test_bare_ack_returns_bare_0x06() throws Exception {
        try (Harness h = new Harness(AckDialect.BARE_ACK)) {
            try (Socket socket = new Socket("127.0.0.1", h.port)) {
                socket.setSoTimeout(5000);
                socket.getOutputStream().write(MllpFrameCodec.encode(VALID.getBytes(StandardCharsets.UTF_8)));
                socket.getOutputStream().flush();

                InputStream in = socket.getInputStream();
                int first = in.read();
                assertEquals(MllpFrameCodec.ACK, first,
                        "bare_ack 的传输级确认必须是裸 0x06，实际是 0x" + Integer.toHexString(first));
                // 关键：0x06 之后紧跟的就是应用级 ACK 的起始块（0x0B）。
                // 若监听口多写了 0x1C 0x0D，这里读到的是 0x1C，用例立刻失败。
                assertEquals(MllpFrameCodec.START_BLOCK, in.read(),
                        "裸字节之后必须紧接应用级 ACK 的帧头 0x0B");
                // 起始块已读掉，剩下半帧自己读到帧尾——不能再用 readFrame（它会去找下一个 0x0B）
                String er7 = new String(readRestOfFrame(in), StandardCharsets.UTF_8);
                assertEquals("AA", msaField(er7, 1), "字段齐全的报文应回 AA：" + printable(er7));
                assertEquals("CTRL-20261020-0001", msaField(er7, 2), "MSA-2 必须逐字复制入站 MSH-10");
            }
        }
    }

    @Test
    @DisplayName("同一份入站在两种方言下应用级 ACK 逐字节相同，只有传输级确认字节不同")
    void test_two_dialects_differ_only_in_transport_bytes() throws Exception {
        byte[] framed;
        byte[] bare;
        try (Harness h = new Harness(AckDialect.FRAMED_ACK)) {
            framed = h.sendAndReadAcks(AckDialect.FRAMED_ACK, VALID).get(0).getBytes(StandardCharsets.UTF_8);
        }
        try (Harness h = new Harness(AckDialect.BARE_ACK)) {
            bare = h.sendAndReadAcks(AckDialect.BARE_ACK, VALID).get(0).getBytes(StandardCharsets.UTF_8);
        }
        assertArrayEquals(framed, bare, "应用级 ACK 内容不得随方言改变");
        assertNotEquals(hex(AckDialect.FRAMED_ACK.transportAckBytes()),
                hex(AckDialect.BARE_ACK.transportAckBytes()), "两种方言的传输级字节必须不同");
    }

    @Test
    @DisplayName("拒收回执：回帧 MSA-1=AE，ERR-1 指向 MSH 第 10 字段，ERR-3 带目录标准码可被平台解析")
    void test_err_frame_reply_carries_ae_and_points_to_segment_field() throws Exception {
        try (Harness h = new Harness(AckDialect.FRAMED_ACK)) {
            String ack = h.sendAndReadAcks(AckDialect.FRAMED_ACK, NO_CONTROL_ID).get(0);

            assertEquals("AE", msaField(ack, 1), "缺 MSH-10 应回 AE：" + printable(ack));
            assertTrue(hasSegment(ack, "ERR"), "AE 必须带 ERR 段：" + printable(ack));

            // 用平台侧同一套解析口径重新解析这条回执：能被解析出来，才谈得上「可被区域平台解析」
            Hl7Message parsed = Hl7Parser.parse(ack, new java.util.ArrayList<>());
            Delimiters d = parsed.delimiters();

            String[] err1 = parsed.segment("ERR").field(1).split(java.util.regex.Pattern.quote(
                    String.valueOf(d.componentSeparator())), -1);
            assertEquals("control-id-missing", err1[0], "ERR-1 首组件是本地码");
            assertEquals("MSH", err1[1], "ERR-1 第二组件是段名");
            assertEquals("10", err1[2], "ERR-1 第三组件是字段序号，须指向 MSH-10");

            String[] err3 = parsed.segment("ERR").field(3).split(java.util.regex.Pattern.quote(
                    String.valueOf(d.componentSeparator())), -1);
            assertEquals("control-id-missing", err3[0]);
            assertEquals("101", err3[2], "ERR-3 第三组件必须是目录标准码 101（缺必填字段）");
            assertEquals("HL70357", err3[3], "ERR-3 第四组件必须带表来源，平台据此归类");

            assertEquals(1, h.book.countByState(MessageReceiptBook.AppState.PENDING_CONFIRM));
            assertEquals(1, h.intents.size(), "AE 进重投集合");
        }
    }

    @Test
    @DisplayName("分层断言：传输级 0x06 收到过的报文，应用级仍可能是待确认或拒收，且没有任何一条被当成已落地")
    void test_transport_ack_does_not_equal_application_ack() throws Exception {
        try (Harness h = new Harness(AckDialect.FRAMED_ACK)) {
            List<String> acks = h.sendAndReadAcks(AckDialect.FRAMED_ACK, VALID, NO_CONTROL_ID, UNKNOWN_CHARSET);

            // 传输级：三帧全部收到了字节确认
            assertEquals(3, h.server.receivedCount(), "三条报文都收到了传输级确认");

            // 应用级：一条受理、一条待确认、一条拒收——三者的传输级确认字节没有任何区别
            assertEquals("AA", msaField(acks.get(0), 1));
            assertEquals("AE", msaField(acks.get(1), 1));
            assertEquals("AR", msaField(acks.get(2), 1));

            assertEquals(1, h.book.countByState(MessageReceiptBook.AppState.RECEIVED));
            assertEquals(1, h.book.countByState(MessageReceiptBook.AppState.PENDING_CONFIRM));
            assertEquals(1, h.book.countByState(MessageReceiptBook.AppState.REJECTED));

            // 红线：收到传输级 0x06 不等于已落地。没有任何人工确认动作，已落地必须是 0。
            assertEquals(0, h.book.landedCount(),
                    "传输级 0x06 不得被当作「已落地」：落地只由人工确认产生");

            // 且传输级确认字节完全相同——所以从字节上看不出任何业务结论
            byte[] transport = AckDialect.FRAMED_ACK.transportAckBytes();
            assertArrayEquals(new byte[]{0x0B, 0x06, 0x1C, 0x0D}, transport,
                    "三条报文的传输级确认字节形状完全一致，业务结论只在应用级");
        }
    }

    @Test
    @DisplayName("AR 排除：AE 进重投集合，AR 与已受理报文都不进")
    void test_ar_message_not_entered_redelivery_set() throws Exception {
        try (Harness h = new Harness(AckDialect.FRAMED_ACK)) {
            h.sendAndReadAcks(AckDialect.FRAMED_ACK, VALID, NO_CONTROL_ID, UNKNOWN_CHARSET);

            String aeKey = MessageReceiptBook.idempotencyKey(
                    "ADT^A01^ADT_A01", "", "P");
            String arKey = MessageReceiptBook.idempotencyKey(
                    "ADT^A01^ADT_A01", "CTRL-20261020-0003", "P");
            String aaKey = MessageReceiptBook.idempotencyKey(
                    "ADT^A01^ADT_A01", "CTRL-20261020-0001", "P");

            assertTrue(h.intents.contains(aeKey), "AE（缺 MSH-10）应进入重投集合");
            assertFalse(h.intents.contains(arKey), "AR（字符集不支持）绝不进重投集合");
            assertFalse(h.intents.contains(aaKey), "已受理（AA）不进重投集合");
            assertEquals(1, h.intents.size(), "只有 AE 那一条可重投");
            assertEquals(2, h.intents.permanentRejectedCount(), "AR 与 AA 各记一次排除");
        }
    }

    @Test
    @DisplayName("冒烟：一条命令连发两帧，回执条数 2，MSA-1 分别为 AA 与 AE")
    void test_smoke_two_messages_reports_two_receipts() throws Exception {
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        java.io.PrintStream sink = new java.io.PrintStream(captured, true, StandardCharsets.UTF_8);
        int exit = AckContractSmoke.run(AckDialect.FRAMED_ACK, sink);
        String text = captured.toString(StandardCharsets.UTF_8);

        assertEquals(0, exit, "冒烟应以 PASS 退出，实际输出：\n" + text);
        assertTrue(text.contains("回执报文条数=2"), "冒烟应报告回执条数 2：\n" + text);
        assertTrue(text.contains("MSA-1=AA"), "第一条应回 AA：\n" + text);
        assertTrue(text.contains("MSA-1=AE"), "第二条应回 AE：\n" + text);
        assertTrue(text.contains("已落地=0"), "冒烟应显式打印已落地=0：\n" + text);
        assertTrue(text.contains("PASS"), text);
    }

    @Test
    @DisplayName("两种方言各跑一次冒烟，结论一致：业务判定不随方言改变")
    void test_smoke_passes_in_both_dialects() throws Exception {
        for (AckDialect dialect : AckDialect.values()) {
            java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
            java.io.PrintStream sink = new java.io.PrintStream(captured, true, StandardCharsets.UTF_8);
            int exit = AckContractSmoke.run(dialect, sink);
            String text = captured.toString(StandardCharsets.UTF_8);

            assertEquals(0, exit, dialect.wireName() + " 下冒烟应通过：\n" + text);
            assertTrue(text.contains(dialect.wireName()), "应打印当前方言：\n" + text);
            assertTrue(text.contains("回执报文条数=2"), text);
            assertTrue(text.contains("已落地=0"), text);
        }
    }

    // ------------------------------------------------------------ 断言辅助

    private static String msaField(String ackEr7, int n) {
        for (String line : ackEr7.split("\r")) {
            if (line.startsWith("MSA|")) {
                return line.split("\\|", -1)[n];
            }
        }
        throw new AssertionError("回执里没有 MSA 段：" + printable(ackEr7));
    }

    private static boolean hasSegment(String ackEr7, String name) {
        for (String line : ackEr7.split("\r")) {
            if (line.startsWith(name + "|")) {
                return true;
            }
        }
        return false;
    }

    private static String printable(String er7) {
        return er7.replace("\r", "\n    ");
    }

    /** 起始块已读掉之后，把帧尾 {@code 0x1C 0x0D} 之前的载荷读完。 */
    private static byte[] readRestOfFrame(InputStream in) throws Exception {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        int read;
        while ((read = in.read()) != -1) {
            if (read == MllpFrameCodec.END_BLOCK_1 && in.read() == MllpFrameCodec.END_BLOCK_2) {
                return sink.toByteArray();
            }
            sink.write(read);
        }
        throw new AssertionError("帧中途断开，没读到帧尾 0x1C 0x0D");
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