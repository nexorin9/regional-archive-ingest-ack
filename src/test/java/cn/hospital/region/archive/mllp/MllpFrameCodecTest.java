package cn.hospital.region.archive.mllp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 帧层验收：全部打真实字节，断言落在 {@code 0x0B} / {@code 0x1C 0x0D} 这两个常量上。
 *
 * <p>重点是半包与粘包——TCP 不承诺一次 {@code read} 恰好给一帧，真实区域平台推包时
 * 「上批尾巴 + 本批头」混在一次读里是常态。这里不用 mock 库，直接用手工构造的字节序列验证。
 */
class MllpFrameCodecTest {

    private static final String MSH = "MSH|^~\\&|REGIONAL_ARCHIVE|FACILITY_A|ARCHIVE_IN|FACILITY_A"
            + "|20261020090000||ADT^A01^ADT_A01|CT-0001|P|2.5.1";

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("encode 加的正是 0x0B 头与 0x1C 0x0D 尾")
    void test_encode_wraps_start_and_end_block() {
        byte[] framed = MllpFrameCodec.encode(bytes(MSH));

        assertEquals(MllpFrameCodec.START_BLOCK, framed[0], "首字节必须是起始块 0x0B");
        assertEquals(MllpFrameCodec.END_BLOCK_1, framed[framed.length - 2], "倒数第二字节必须是 0x1C");
        assertEquals(MllpFrameCodec.END_BLOCK_2, framed[framed.length - 1], "末字节必须是 0x0D");
        assertEquals(MSH, new String(framed, 1, framed.length - 3, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("readFrame 从整帧字节还原出原报文")
    void test_readFrame_returns_payload() throws IOException {
        byte[] framed = MllpFrameCodec.encode(bytes(MSH));

        byte[] payload = MllpFrameCodec.readFrame(new ByteArrayInputStream(framed));

        assertEquals(MSH, new String(payload, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("对端在帧边界干净关闭时返回 null，不当作半帧")
    void test_readFrame_returns_null_on_clean_eof() throws IOException {
        assertNull(MllpFrameCodec.readFrame(new ByteArrayInputStream(new byte[0])));
    }

    @Test
    @DisplayName("对端在帧中途断流抛 MllpFrameException：半个报文不能当一条处理")
    void test_readFrame_throws_on_truncated_frame() {
        byte[] framed = MllpFrameCodec.encode(bytes(MSH));
        byte[] truncated = new byte[framed.length - 5];
        System.arraycopy(framed, 0, truncated, 0, truncated.length);

        MllpFrameCodec.MllpFrameException ex = assertThrows(MllpFrameCodec.MllpFrameException.class,
                () -> MllpFrameCodec.readFrame(new ByteArrayInputStream(truncated)));
        assertTrue(ex.getMessage().contains("帧中途"), "异常应说明是帧中途断流，实际：" + ex.getMessage());
    }

    @Test
    @DisplayName("帧前的噪声字节被跳过，报文本身不受影响")
    void test_readFrame_skips_leading_noise() throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        raw.write(new byte[]{0x00, 0x13, 0x1A, 0x0D}); // 真实接入链路上常见的调参/心跳噪声
        raw.writeBytes(MllpFrameCodec.encode(bytes(MSH)));

        byte[] payload = MllpFrameCodec.readFrame(new ByteArrayInputStream(raw.toByteArray()));

        assertEquals(MSH, new String(payload, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("半包：一次喂前半截不产出帧，补齐后半截才成一帧")
    void test_frameBuffer_handles_partial_frame() throws IOException {
        byte[] framed = MllpFrameCodec.encode(bytes(MSH));
        MllpFrameCodec.FrameBuffer buffer = new MllpFrameCodec.FrameBuffer();
        int cut = framed.length / 2;

        List<byte[]> firstHalf = buffer.feed(java.util.Arrays.copyOfRange(framed, 0, cut));
        assertTrue(firstHalf.isEmpty(), "半包不该产出帧");
        assertTrue(buffer.hasPartialFrame(), "半包应留在缓冲里");

        List<byte[]> secondHalf = buffer.feed(java.util.Arrays.copyOfRange(framed, cut, framed.length));
        assertEquals(1, secondHalf.size());
        assertEquals(MSH, new String(secondHalf.get(0), StandardCharsets.UTF_8));
        assertFalse(buffer.hasPartialFrame(), "成帧后缓冲应为空");
    }

    @Test
    @DisplayName("帧尾 0x1C 与 0x0D 被拆到两次 feed 里，仍认得出帧尾")
    void test_frameBuffer_handles_split_end_block() throws IOException {
        byte[] framed = MllpFrameCodec.encode(bytes(MSH));
        MllpFrameCodec.FrameBuffer buffer = new MllpFrameCodec.FrameBuffer();

        assertTrue(buffer.feed(java.util.Arrays.copyOfRange(framed, 0, framed.length - 1)).isEmpty());
        List<byte[]> tail = buffer.feed(java.util.Arrays.copyOfRange(framed, framed.length - 1, framed.length));

        assertEquals(1, tail.size());
        assertEquals(MSH, new String(tail.get(0), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("粘包：一次喂「上帧尾巴 + 两帧 + 下帧头」切出两帧且顺序不乱")
    void test_frameBuffer_handles_coalesced_frames() throws IOException {
        byte[] framedOne = MllpFrameCodec.encode(bytes(MSH));
        byte[] framedTwo = MllpFrameCodec.encode(bytes(MSH.replace("CT-0001", "CT-0002")));
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        raw.write(new byte[]{MllpFrameCodec.END_BLOCK_1, MllpFrameCodec.END_BLOCK_2}); // 上批残留的孤立帧尾
        raw.writeBytes(framedOne);
        raw.writeBytes(framedTwo);
        raw.write(MllpFrameCodec.START_BLOCK); // 下批帧头只到了一半

        MllpFrameCodec.FrameBuffer buffer = new MllpFrameCodec.FrameBuffer();
        List<byte[]> frames = buffer.feed(raw.toByteArray());

        assertEquals(2, frames.size(), "两帧应一次切出");
        assertArrayEquals(bytes(MSH), frames.get(0));
        assertArrayEquals(bytes(MSH.replace("CT-0001", "CT-0002")), frames.get(1));
        assertTrue(buffer.hasPartialFrame(), "末尾半个帧头应留在缓冲里等下一批");
    }

    @Test
    @DisplayName("帧长超上限抛异常：不让一条坏流把内存吃光")
    void test_frameBuffer_rejects_oversized_frame() throws IOException {
        MllpFrameCodec.FrameBuffer buffer = new MllpFrameCodec.FrameBuffer(64);
        byte[] huge = new byte[256];
        huge[0] = MllpFrameCodec.START_BLOCK;
        for (int i = 1; i < huge.length; i++) {
            huge[i] = 'A';
        }

        assertThrows(MllpFrameCodec.MllpFrameException.class, () -> buffer.feed(huge));
    }
}