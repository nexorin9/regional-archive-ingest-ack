package cn.hospital.region.archive.mllp;

import cn.hospital.region.archive.ack.AckComposer;
import cn.hospital.region.archive.ack.InboundHeader;
import cn.hospital.region.archive.ack.RejectionReason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回执方言断言 —— 两种方言共用同一份应用级产物，只差外层字节。
 *
 * <p>方言语义取自参考实现 {@code MLLPConnection} 的 {@code writeACK()}（{@code :110-114}）：
 * 它写的是一个字节，但 {@code MLLPOutputStream} 会补上帧边界，于是 MLLP2 对端看到的是
 * {@code 0B 06 1C 0D}；国内部分实现只读一个字节，故留 {@code bare_ack} 出口。
 */
class AckDialectTest {

    private static final String LOCAL_AET = "CHSCOMMUNITY";
    private static final String REMOTE_AET = "REGIONAL_ARCHIVE";

    private static final String INBOUND = String.join("\r",
            "MSH|^~\\&|REGIONAL_ARCHIVE|FACILITY_A|ARCHIVE_IN|FACILITY_A|20261020090000"
                    + "||ADT^A01^ADT_A01|CTRL-20261020-0001|P|2.5.1|||AL|NE||UNICODE UTF-8",
            "PID|1||PAT-0001^^^FACILITY_A^MR||张^三||19780315|M",
            "");

    /** 固定时钟 + 两条方言共用一个回执组装器：让「同一份产物」在字节层面可断言。 */
    private static AckComposer fixedClockComposer() {
        return new AckComposer(LOCAL_AET, REMOTE_AET,
                Clock.fixed(Instant.parse("2026-10-20T09:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    @DisplayName("同一 MSA 报文在两种方言下应用级内容完全相同，只有外层帧字节不同")
    void test_same_msa_same_content_across_two_dialects() {
        AckComposer composer = fixedClockComposer();
        InboundHeader header = InboundHeader.parse(INBOUND);

        String er7 = composer.composeAccepted(header);
        byte[] payload = er7.getBytes(StandardCharsets.UTF_8);

        byte[] viaFramed = AckDialect.FRAMED_ACK.frameApplicationAck(payload);
        byte[] viaBare = AckDialect.BARE_ACK.frameApplicationAck(payload);

        // 应用级内容：两种方言产物逐字节相同
        assertArrayEquals(payload, stripFrame(viaFramed), "应用级 ACK 内容不得随方言改变");
        assertArrayEquals(payload, stripFrame(viaBare), "应用级 ACK 内容不得随方言改变");

        // 外层帧：只有传输级确认字节的形状不同
        assertArrayEquals(new byte[]{0x0B, 0x06, 0x1C, 0x0D}, AckDialect.FRAMED_ACK.transportAckBytes());
        assertArrayEquals(new byte[]{0x06}, AckDialect.BARE_ACK.transportAckBytes());
        assertFalse(Arrays.equals(AckDialect.FRAMED_ACK.transportAckBytes(),
                AckDialect.BARE_ACK.transportAckBytes()), "两种方言的外层字节必须不同");

        // MSA 内容本身没有因方言被改动过
        assertTrue(er7.contains("MSA|" + AckComposer.CODE_AA + "|"), "回执仍应产出 MSA-1=AA 段：" + er7);
    }

    @Test
    @DisplayName("拒收回执同样两种方言同内容，ERR 段带目录标准码与表来源")
    void test_rejected_ack_is_dialect_invariant_and_carries_catalog_code() {
        AckComposer composer = fixedClockComposer();
        InboundHeader header = InboundHeader.parse(INBOUND);
        String er7 = composer.composeRejected(header, RejectionReason.UNSUPPORTED_CHARSET);

        byte[] framed = AckDialect.FRAMED_ACK.frameApplicationAck(er7.getBytes(StandardCharsets.UTF_8));
        byte[] bare = AckDialect.BARE_ACK.frameApplicationAck(er7.getBytes(StandardCharsets.UTF_8));
        assertArrayEquals(stripFrame(framed), stripFrame(bare), "拒收回执的应用级内容也必须逐字节相同");
        assertTrue(er7.contains("207"), "ERR-3 必须带目录标准码 207：" + er7.replace('\r', '\n'));
        assertTrue(er7.contains("HL70357"), "ERR-3 必须带表来源：" + er7.replace('\r', '\n'));
    }

    @Test
    @DisplayName("方言按 ARCHIVE_ACK_MODE 字面值解析，非法值直接拒，不猜默认值")
    void test_dialect_parsed_from_wire_name_and_illegal_value_rejected() {
        assertEquals(AckDialect.FRAMED_ACK, AckDialect.fromWireName("framed_ack"));
        assertEquals(AckDialect.BARE_ACK, AckDialect.fromWireName("bare_ack"));
        assertEquals(AckDialect.FRAMED_ACK, AckDialect.fromWireName("  FRAMED_ACK "));
        assertThrows(IllegalArgumentException.class, () -> AckDialect.fromWireName("xml_ack"));
        assertThrows(IllegalArgumentException.class, () -> AckDialect.fromWireName(null));
        assertEquals("framed_ack", AckDialect.FRAMED_ACK.wireName());
        assertTrue(AckDialect.FRAMED_ACK.framed());
        assertFalse(AckDialect.BARE_ACK.framed());
    }

    @Test
    @DisplayName("探针按实测字节判定方言：裸 0x06 → bare，0B 06 1C 0D → framed")
    void test_probe_reads_observed_bytes() {
        assertEquals(AckDialect.BARE_ACK, AckDialect.probe(new byte[]{0x06}));
        assertEquals(AckDialect.FRAMED_ACK, AckDialect.probe(new byte[]{0x0B, 0x06, 0x1C, 0x0D}));
        assertNull(AckDialect.probe(null));
        assertNull(AckDialect.probe(new byte[0]));
        assertNull(AckDialect.probe(new byte[]{0x15}), "探针读到 NAK 不得当成方言证据");
        assertNull(AckDialect.probe(new byte[]{0x0B, 0x06}), "半截字节不构成任何一种方言");
    }

    @Test
    @DisplayName("启动自检只建议不改配置：有实测字节时标 confident，无实测时一律标推断")
    void test_suggestion_never_changes_config() {
        AckDialect.DialectAdvice byProbe = AckDialect.suggest(new byte[]{0x06}, null);
        assertEquals(AckDialect.BARE_ACK, byProbe.suggested());
        assertTrue(byProbe.confident(), "有实测字节时建议必须标 confident");
        assertFalse(byProbe.applied(), "本产品不在运行期改配置");
        assertTrue(byProbe.toLogLine().contains("仅提示"));

        AckDialect.DialectAdvice byProbeFramed =
                AckDialect.suggest(new byte[]{0x0B, 0x06, 0x1C, 0x0D}, null);
        assertEquals(AckDialect.FRAMED_ACK, byProbeFramed.suggested());

        AckDialect.DialectAdvice noProbe = AckDialect.suggest(null, null);
        assertEquals(AckDialect.FRAMED_ACK, noProbe.suggested());
        assertFalse(noProbe.confident(), "没有实测字节就只能是推断");
        assertFalse(noProbe.applied());

        InboundHeader header = InboundHeader.parse(INBOUND);
        AckDialect.DialectAdvice byHeader = AckDialect.suggest(null, header);
        assertEquals(AckDialect.FRAMED_ACK, byHeader.suggested());
        assertFalse(byHeader.confident(), "按 MSH-11/MSH-12 推断出来的一律不算实测");
        assertTrue(byHeader.basis().contains("MSH-11"), "推断依据要写清看了哪两个字段：" + byHeader.basis());
        assertFalse(byHeader.applied());
    }

    @Test
    @DisplayName("真实 socket 端到端：同一份处理链产物在两种方言下都发得出去，只差传输级字节")
    void test_real_socket_two_dialects_same_application_ack() throws Exception {
        // 每次 exchange 用全新的组装器：同一个组装器的自增控制号会让第二条回执变成 ACK000002，
        // 那是产品正确行为（回执控制号进程内不重号），但会让「两种方言同内容」的对照失去意义。
        byte[] framedPayload = exchange(AckDialect.FRAMED_ACK);
        byte[] barePayload = exchange(AckDialect.BARE_ACK);

        assertArrayEquals(barePayload, framedPayload,
                "真实 socket 上两种方言回出的应用级 ACK 必须逐字节相同");
        String er7 = new String(framedPayload, StandardCharsets.UTF_8);
        assertTrue(er7.contains("CTRL-20261020-0001"), "MSA-2 应逐字复制入站控制号：" + er7);
    }

    /**
     * 真起一个监听口，真打一帧，读回应用级 ACK 载荷。
     *
     * <p>先按方言长度读传输级确认（带帧 4 字节 / 裸 1 字节），再读应用级 ACK 帧——
     * 读法与监听口写法定序一一对应，方言写错会直接在这里读崩。
     */
    private byte[] exchange(AckDialect dialect) throws Exception {
        MllpServer server = new MllpServer("127.0.0.1", 0,
                new cn.hospital.region.archive.ack.AckEchoHandler(fixedClockComposer()), 0, dialect);
        try {
            int port = server.start();
            assertEquals(dialect, server.ackDialect());
            try (Socket socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(5000);
                OutputStream out = socket.getOutputStream();
                out.write(MllpFrameCodec.encode(INBOUND.getBytes(StandardCharsets.UTF_8)));
                out.flush();

                InputStream in = socket.getInputStream();
                byte[] expectedTransport = dialect.transportAckBytes();
                byte[] actualTransport = in.readNBytes(expectedTransport.length);
                assertArrayEquals(expectedTransport, actualTransport,
                        dialect.wireName() + " 的传输级确认字节不符");
                return MllpFrameCodec.readFrame(in);
            }
        } finally {
            server.close();
        }
    }

    @Test
    @DisplayName("裸方言默认保持与主干监听口一致，避免方言开关成为静默行为变化")
    void test_default_dialect_stays_bare_for_backward_compatibility() throws Exception {
        MllpServer server = new MllpServer("127.0.0.1", 0,
                payload -> new byte[0], 2000);
        try {
            assertEquals(AckDialect.BARE_ACK, server.ackDialect(),
                    "不显式指定方言时沿用裸字节，与已跑通的主干监听口一致");
            int port = server.start();
            assertTrue(port > 0);
            assertNotNull(server.port());
        } finally {
            server.close();
        }
    }

    @Test
    @DisplayName("帧剥离辅助：带帧字节去掉首尾帧边界后就是载荷")
    void test_strip_frame_helper_matches_codec() throws IOException {
        byte[] payload = INBOUND.getBytes(StandardCharsets.UTF_8);
        byte[] framed = AckDialect.FRAMED_ACK.frameApplicationAck(payload);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        sink.write(framed, 1, framed.length - 3);
        assertArrayEquals(payload, sink.toByteArray());
        assertEquals(MllpFrameCodec.START_BLOCK, framed[0]);
        assertEquals(MllpFrameCodec.END_BLOCK_2, framed[framed.length - 1]);
    }

    private static byte[] stripFrame(byte[] framed) {
        return Arrays.copyOfRange(framed, 1, framed.length - 2);
    }
}