package cn.hospital.region.archive.mllp;

import cn.hospital.region.archive.ack.InboundHeader;

import java.util.Arrays;
import java.util.Objects;

/**
 * 回执方言开关：决定<b>传输级</b>确认字节写成哪种形状。
 *
 * <p>两种方言：
 * <ul>
 *   <li>{@link #FRAMED_ACK} —— {@code 0x0B 0x06 0x1C 0x0D}，HL7 规范形式，也是参考实现
 *       {@code MLLPConnection.writeACK()}（{@code :110-114}）在 {@code MLLPRelease.MLLP2} 下的行为：
 *       {@code mllpOut.write(ACK); mllpOut.finish();} —— 写的是<b>一个字节</b>，
 *       但 {@code MLLPOutputStream} 会在外层补上 SOM / EOM，于是对端看到的是一帧 {@code 0B 06 1C 0D}。</li>
 *   <li>{@link #BARE_ACK} —— 裸 {@code 0x06}，不加帧。国内相当一部分区域平台的 MLLP 实现
 *       读 ACK 时只取一个字节，多读两个帧字节会被当成下一条报文的前缀。</li>
 * </ul>
 *
 * <p><b>方言只影响这一层。</b>应用级 ACK 报文（{@code AckComposer} 产出的 MSH/MSA/ERR 文本）
 * 在两种方言下<b>逐字节相同</b>——方言决定的是包在报文外面的那层字节，
 * 不是报文内容。切换方言不会改变任何一条回执的业务含义，只是让对端的读包器认得这个包。
 *
 * <p><b>为什么不自动探测后自动切换</b>：方言写错的后果是对端读包器卡住等一个永远不会来的帧尾，
 * 表现是「连接挂死」而不是「报错」，排查成本远高于填错一个环境变量。所以本类只<b>给建议</b>，
 * 切换必须由人改 {@code ARCHIVE_ACK_MODE} 后重启，见 {@link #suggest}。
 */
public enum AckDialect {

    /** 带 MLLP 帧：{@code 0B 06 1C 0D}（HL7 规范形式，对应参考实现的 MLLP2 行为）。 */
    FRAMED_ACK("framed_ack"),

    /** 裸 ACK 字节：{@code 0x06}（部分国内区域平台实现如此）。 */
    BARE_ACK("bare_ack");

    private final String wireName;

    AckDialect(String wireName) {
        this.wireName = wireName;
    }

    /** 环境变量 {@code ARCHIVE_ACK_MODE} 里写的字面值。 */
    public String wireName() {
        return wireName;
    }

    /** 是否带 MLLP 帧。 */
    public boolean framed() {
        return this == FRAMED_ACK;
    }

    /**
     * 本方言下的传输级确认字节。
     *
     * @return 带帧为 4 字节 {@code 0B 06 1C 0D}；裸字节为 1 字节 {@code 06}
     */
    public byte[] transportAckBytes() {
        return framed()
                ? new byte[]{MllpFrameCodec.START_BLOCK, MllpFrameCodec.ACK,
                        MllpFrameCodec.END_BLOCK_1, MllpFrameCodec.END_BLOCK_2}
                : new byte[]{MllpFrameCodec.ACK};
    }

    /**
     * 应用级 ACK 报文的出站字节。
     *
     * <p>两种方言在这里<b>完全一致</b>：应用级报文永远是加 MLLP 帧发的（参考实现里
     * {@code writeMessage} 走 {@code MLLPOutputStream.writeMessage}，报文永远带帧），
     * 方言管的是<b>帧里的 ACK 字节</b>，不是这个报文本身。方法存在的意义是把这条不变量
     * 写进签名——将来谁想把报文也按方言裸发，会在这里撞到口径而不是悄悄改坏。
     */
    public byte[] frameApplicationAck(byte[] applicationAckPayload) {
        Objects.requireNonNull(applicationAckPayload, "applicationAckPayload");
        return MllpFrameCodec.encode(applicationAckPayload);
    }

    /** 按环境变量字面值解析；不接受猜测值，非法值直接抛。 */
    public static AckDialect fromWireName(String wireName) {
        if (wireName != null) {
            for (AckDialect d : values()) {
                if (d.wireName.equalsIgnoreCase(wireName.trim())) {
                    return d;
                }
            }
        }
        throw new IllegalArgumentException(
                "回执方言只接受 framed_ack 或 bare_ack，实际是：" + wireName);
    }

    // ------------------------------------------------------------ 启动自检：只建议，不改配置

    /**
     * 方言建议。
     *
     * @param suggested 建议采用的方言
     * @param basis     判断依据的一句话（人看得懂，出现在启动日志里）
     * @param confident 是否有实测字节支撑；{@code false} 表示这是按对端报文头猜的
     * @param applied   恒为 {@code false}——本产品不在运行期改配置
     */
    public record DialectAdvice(AckDialect suggested, String basis, boolean confident, boolean applied) {

        public DialectAdvice {
            Objects.requireNonNull(suggested, "suggested");
            basis = basis == null ? "" : basis;
        }

        /** 启动日志用的一行文案。 */
        public String toLogLine() {
            return "回执方言建议: " + suggested.wireName()
                    + "（依据: " + basis
                    + "，置信度: " + (confident ? "实测字节" : "报文头推断")
                    + "，是否已应用: " + (applied ? "是" : "否，仅提示") + "）";
        }
    }

    /**
     * 按对端表现给出方言建议 —— <b>只建议，不改配置</b>。
     *
     * <p>判断顺序：实测字节优先，探针没探到才退回按 MSH-11 / MSH-12 推断，
     * 且推断结果一律标 {@code confident=false}，因为「国内区域平台的实现习惯」不是可依据的事实。
     *
     * @param observedTransportAck 探针实际收到的字节；{@code null} 或空表示没探到
     * @param header               已读到的对端 MSH 头；{@code null} 表示连接还没建起来
     */
    public static DialectAdvice suggest(byte[] observedTransportAck, InboundHeader header) {
        AckDialect probed = probe(observedTransportAck);
        if (probed != null) {
            return new DialectAdvice(probed,
                    "对端实际回执字节为 " + describe(observedTransportAck), true, false);
        }
        if (header == null) {
            return new DialectAdvice(FRAMED_ACK,
                    "尚未与对端建立连接，按 HL7 规范的带帧形式给出默认建议", false, false);
        }
        // 以下几条都不是「判出对端用哪种方言」，而是「报文头能告诉操作员的哪些事」。
        // MSH-11 在 HL7 v2 里说的是对端要什么口径的回执（AL/NE/ER/SU），与字节形状没有因果关系；
        // 凭它编一条「P 就是裸字节」的规则只会误导人，所以这里只如实报出对端的回执诉求，
        // 方言一律回落到 HL7 规范默认形式并标 confident=false，等实测字节来定。
        String processingId = header.processingId();
        String version = header.versionOrDefault();
        if ("NE".equalsIgnoreCase(processingId)) {
            return new DialectAdvice(FRAMED_ACK,
                    "对端 MSH-11=NE（声明不要求逐条回执），方言配置对该对端当前不生效", false, false);
        }
        if ("AL".equalsIgnoreCase(processingId)) {
            return new DialectAdvice(FRAMED_ACK,
                    "对端 MSH-11=AL、MSH-12=" + version
                            + "（要求逐条回执），按 HL7 规范默认形式建议；务必按实测字节复核", false, false);
        }
        return new DialectAdvice(FRAMED_ACK,
                "对端 MSH-11=" + (processingId.isBlank() ? "(空)" : processingId)
                        + "、MSH-12=" + version
                        + " 未要求逐条回执，仅凭报文头判不出方言，按 HL7 规范默认形式建议；"
                        + "务必按实测字节复核", false, false);
    }

    /** 从实测字节判定方言；探针没给出明确形状时返回 {@code null}。 */
    public static AckDialect probe(byte[] observedTransportAck) {
        if (observedTransportAck == null || observedTransportAck.length == 0) {
            return null;
        }
        if (observedTransportAck.length == 1 && observedTransportAck[0] == MllpFrameCodec.ACK) {
            return BARE_ACK;
        }
        byte[] framed = FRAMED_ACK.transportAckBytes();
        if (Arrays.equals(observedTransportAck, framed)) {
            return FRAMED_ACK;
        }
        return null;
    }

    private static String describe(byte[] bytes) {
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