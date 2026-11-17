package cn.hospital.region.archive.mllp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * MLLP 帧编解码：{@code <VT> 报文载荷 <FS><CR>}，即 {@code 0x0B ... 0x1C 0x0D}。
 *
 * <p>帧边界字节与时序取自参考实现的 {@code MLLPConnection}：{@code START_BLOCK}/{@code END_BLOCK}
 * 由 {@code MLLPOutputStream.writeMessage} 写出，{@link #ACK}/{@link #NAK} 两个传输级单字节
 * 由 {@code writeACK()} / {@code readACK()} 处理。本类只负责<b>字节级成帧</b>，
 * 不含任何报文语义（报文解析与回执组装在 {@code hl7} / {@code ack} 包）。
 *
 * <p>读侧处理真实 TCP 流的两种常态：
 * <ul>
 *   <li><b>半包</b>：一次 {@code read} 只拿到帧的前半截——{@link #readFrame(InputStream)}
 *       阻塞到帧尾才返回；{@link FrameBuffer} 则把它留在缓冲里等下一批字节；</li>
 *   <li><b>粘包</b>：一次 {@code read} 拿到多帧甚至「上一帧尾巴 + 下一帧头」——
 *       {@link FrameBuffer#feed(byte[])} 按序切出全部完整帧，SOM 之前的噪声字节丢弃。</li>
 * </ul>
 */
public final class MllpFrameCodec {

    /** 起始块，VT。 */
    public static final byte START_BLOCK = 0x0B;
    /** 帧尾第一字节，FS。 */
    public static final byte END_BLOCK_1 = 0x1C;
    /** 帧尾第二字节，CR。 */
    public static final byte END_BLOCK_2 = 0x0D;
    /** 传输级确认。 */
    public static final byte ACK = 0x06;
    /** 传输级否定确认。 */
    public static final byte NAK = 0x15;

    /** 单帧上限：区域平台一个批次常在数百条，单条健康档案报文不该超过这个量级。 */
    public static final int DEFAULT_MAX_FRAME_BYTES = 16 * 1024 * 1024;

    private MllpFrameCodec() {
    }

    /**
     * 给一段已成形的报文加 MLLP 帧。
     *
     * @param payload 报文载荷（ER7 文本的字节形式），不做任何字符集处理
     */
    public static byte[] encode(byte[] payload) {
        if (payload == null) {
            throw new IllegalArgumentException("payload 不能为 null");
        }
        byte[] out = new byte[payload.length + 3];
        out[0] = START_BLOCK;
        System.arraycopy(payload, 0, out, 1, payload.length);
        out[out.length - 2] = END_BLOCK_1;
        out[out.length - 1] = END_BLOCK_2;
        return out;
    }

    /**
     * 阻塞读一帧。
     *
     * @return 帧内报文载荷；<b>对端在帧边界上干净关闭时返回 {@code null}</b>
     * @throws MllpFrameException 帧中途断流，或帧长超上限
     */
    public static byte[] readFrame(InputStream in) throws IOException {
        return readFrame(in, DEFAULT_MAX_FRAME_BYTES);
    }

    /** 指定单帧上限的 {@link #readFrame(InputStream)}。 */
    public static byte[] readFrame(InputStream in, int maxFrameBytes) throws IOException {
        ByteArrayOutputStream payload = new ByteArrayOutputStream(4096);
        int read;
        // 第一步：跳过帧前的噪声字节，停在起始块上
        while ((read = in.read()) != -1) {
            if (read == START_BLOCK) {
                break;
            }
        }
        if (read == -1) {
            return null;
        }
        // 第二步：逐字节找帧尾 0x1C 0x0D
        while ((read = in.read()) != -1) {
            if (payload.size() > maxFrameBytes) {
                throw new MllpFrameException("单帧超过上限 " + maxFrameBytes + " 字节");
            }
            if (read != END_BLOCK_1) {
                payload.write(read);
                continue;
            }
            int next = in.read();
            if (next == END_BLOCK_2) {
                return payload.toByteArray();
            }
            // 0x1C 后面不是 0x0D：这不是帧尾，按数据处理，字节原样保留继续找
            payload.write(END_BLOCK_1);
            if (next == -1) {
                break;
            }
            payload.write(next);
        }
        throw new MllpFrameException("对端在帧中途关闭连接（已收 " + payload.size() + " 字节未遇帧尾 0x1C 0x0D）");
    }

    /** 帧层协议错误。传输层可恢复，业务层不能把半个报文当一条处理。 */
    public static final class MllpFrameException extends IOException {
        public MllpFrameException(String message) {
            super(message);
        }
    }

    /**
     * 增量拆帧器：喂多少字节都能切出「到目前为止的全部完整帧」。
     *
     * <p>用于非阻塞读取或已经把整段流读进内存的场景；阻塞式 {@link #readFrame(InputStream)}
     * 与它是同一套帧边界定义，不存在两套口径。
     */
    public static final class FrameBuffer {

        private final int maxFrameBytes;
        private byte[] buf = new byte[512];
        private int size;

        public FrameBuffer() {
            this(DEFAULT_MAX_FRAME_BYTES);
        }

        public FrameBuffer(int maxFrameBytes) {
            if (maxFrameBytes <= 0) {
                throw new IllegalArgumentException("maxFrameBytes 必须为正数");
            }
            this.maxFrameBytes = maxFrameBytes;
        }

        /**
         * 追加字节并切出本次新形成的完整帧。
         *
         * @return 顺序与字节流一致；未收全的半帧不返回，留到下次 {@code feed}
         * @throws MllpFrameException 半帧长度已超上限
         */
        public List<byte[]> feed(byte[] chunk) throws MllpFrameException {
            List<byte[]> frames = new ArrayList<>();
            if (chunk != null && chunk.length > 0) {
                append(chunk);
            }
            // cursor 始终是「已消费到哪」：噪声与已切出的帧最后一次性丢掉，半帧顶到缓冲区开头
            int cursor = 0;
            while (true) {
                int som = indexOf(START_BLOCK, cursor);
                if (som < 0) {
                    // 剩余全是噪声
                    cursor = size;
                    break;
                }
                int eom = findEom(som + 1);
                if (eom < 0) {
                    if (size - som > maxFrameBytes) {
                        throw new MllpFrameException("半帧长度 " + (size - som) + " 已超上限 " + maxFrameBytes
                                + " 字节，对端可能没发帧尾");
                    }
                    // 半帧：从起始块起保留
                    cursor = som;
                    break;
                }
                frames.add(Arrays.copyOfRange(buf, som + 1, eom));
                cursor = eom + 2;
            }
            discard(0, cursor);
            return frames;
        }

        /** 当前是否留有未收全的半帧。 */
        public boolean hasPartialFrame() {
            return size > 0;
        }

        /**
         * 当前留存的半帧字节副本（<b>含起始块</b>；无半帧时返回空数组）。
         *
         * <p>供「连接断开 / 读超时后按半条处理」这条路径取字节去组 AE + ERR：
         * 半条报文的 MSH 段通常已经完整到达，因此控制号读得出来，
         * 区域平台能把这条回执对回它正在发的那一条。
         */
        public byte[] partialPayload() {
            return size == 0 ? new byte[0] : Arrays.copyOf(buf, size);
        }

        /** 缓冲区内尚未成帧的字节数（半帧或纯噪声）。 */
        public int pendingBytes() {
            return size;
        }

        /** 丢弃全部缓冲，重置状态。 */
        public void reset() {
            size = 0;
        }

        private void append(byte[] chunk) {
            if (size + chunk.length > buf.length) {
                int capacity = Math.max(buf.length * 2, size + chunk.length);
                buf = Arrays.copyOf(buf, capacity);
            }
            System.arraycopy(chunk, 0, buf, size, chunk.length);
            size += chunk.length;
        }

        private void discard(int from, int to) {
            int len = to - from;
            if (len <= 0) {
                return;
            }
            System.arraycopy(buf, to, buf, from, size - to);
            size -= len;
        }

        private int indexOf(byte target, int from) {
            for (int i = Math.max(from, 0); i < size; i++) {
                if (buf[i] == target) {
                    return i;
                }
            }
            return -1;
        }

        private int findEom(int from) {
            // 末尾的孤立 0x1C 不算帧尾：0x0D 还没到，等下一批字节再判
            for (int i = Math.max(from, 0); i + 1 < size; i++) {
                if (buf[i] == END_BLOCK_1 && buf[i + 1] == END_BLOCK_2) {
                    return i;
                }
            }
            return -1;
        }
    }
}