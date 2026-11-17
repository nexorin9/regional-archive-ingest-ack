package cn.hospital.region.archive.ack;

import cn.hospital.region.archive.hl7.CharsetResolver;
import cn.hospital.region.archive.hl7.UnsupportedCharset;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * 半帧回执组装 —— 收到起始块却始终没等到帧尾 {@code 0x1C 0x0D} 时，
 * 按 {@link RejectionReason#MESSAGE_TRUNCATED} 出一条 {@code MSA|AE} + {@code ERR}。
 *
 * <h2>为什么半帧也要出应用级回执</h2>
 * <p>半帧意味着本端已经收下了这条报文的<b>开头</b>。区域平台按控制号等回执等不到，
 * 只会做两件事：一直等到超时，或者在次日对账时发现少一条。两种做法下本端都吃亏——
 * 台账里连这一行都没有，现场既说不出少了哪条，也说不出本端收了多少。
 * 一条 {@code AE} 明确告诉对端「这条是半条、可重投」，本端则把断流写进日志，
 * 于是次日对账时两边说的是同一件事。
 *
 * <p>归 <b>AE 而不是 AR</b>：半截传输几乎总是网络侧的抖动（对端写 socket 被断、
 * 中间盒子超时、会话被回收），重投一次通常就完整了。归 AR 会让对端把这条扔进死信堆，
 * 那条居民档案就<b>永久</b>停在平台侧，本端这边一个字也没留。
 *
 * <h2>控制号从半帧里尽力读，读得出就必须填</h2>
 * <p>MSH 段总是在最前面，半帧里它多半是完整的，因此控制号通常读得出来。
 * 读得出来就逐字复制进 MSA-2——区域平台靠它把回执对回它正在发的那一条，
 * 填不出时 MSA-2 就是空串，回执进了对端台账也认不出是哪条，白发。
 *
 * <h2>读不出 MSH 时返回 {@code null}</h2>
 * <p>连 {@code MSH} 开头几个字符都没送到（对端刚建连就断）时，任何一条 ACK 都对不上号，
 * 与其发出一条对端认不出来的回执污染它的台账，不如不发。这与
 * {@link ArchiveAckHandler#triage} 对「连 MSH 都读不出」的既有口径一致。
 */
public final class PartialFrameAckComposer {

    private final AckComposer composer;

    public PartialFrameAckComposer(String localAet, String remoteAet) {
        this(new AckComposer(localAet, remoteAet));
    }

    public PartialFrameAckComposer(AckComposer composer) {
        if (composer == null) {
            throw new IllegalArgumentException("AckComposer 不得为 null");
        }
        this.composer = composer;
    }

    /**
     * 半帧字节 → 应用级 AE 回执（<b>未加 MLLP 帧</b>，加帧由 {@code MllpServer} 统一做）。
     *
     * @param partialPayload 缓冲区里残留的字节（<b>含起始块 {@code 0x0B}</b>）
     * @return 回执报文字节；<b>读不出 MSH 段时返回 {@code null}</b>
     */
    public byte[] compose(byte[] partialPayload) {
        if (partialPayload == null || partialPayload.length == 0) {
            return null;
        }
        String ascii = new String(stripSom(partialPayload), StandardCharsets.US_ASCII);
        InboundHeader header;
        try {
            header = InboundHeader.parse(ascii);
        } catch (RuntimeException e) {
            return null;
        }
        Charset charset = replyCharsetOf(header);
        return composer.composeRejected(header, RejectionReason.MESSAGE_TRUNCATED)
                .getBytes(charset);
    }

    /** 半帧里 MSH 自述的字符集；解不出或未声明时退回 UTF-8。 */
    private static Charset replyCharsetOf(InboundHeader header) {
        try {
            return CharsetResolver.resolve(header.characterSet(), null).charset;
        } catch (UnsupportedCharset e) {
            return StandardCharsets.UTF_8;
        }
    }

    private static byte[] stripSom(byte[] raw) {
        int start = 0;
        while (start < raw.length && raw[start] == 0x0B) {
            start++;
        }
        if (start >= raw.length) {
            return new byte[0];
        }
        byte[] out = new byte[raw.length - start];
        System.arraycopy(raw, start, out, 0, out.length);
        return out;
    }

    /** 便于装配层复用「半帧判定」这套口径：本类只回执，不做判定。 */
    public static String defaultCause() {
        return "连接结束或读超时，缓冲区残留未遇帧尾 0x1C 0x0D";
    }
}