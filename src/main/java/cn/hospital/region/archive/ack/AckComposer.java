package cn.hospital.region.archive.ack;

import cn.hospital.region.archive.hl7.Delimiters;
import cn.hospital.region.archive.hl7.Escapes;
import cn.hospital.region.archive.hl7.LocatableError;

import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 回执（ACK）报文组装 —— 应用级 MSA 三值 + ERR 段。
 *
 * <p>一条入站报文回一条应用级 ACK，核心是<b>逐字复制</b>：MSH-11、MSH-12、MSH-18 原样带回，
 * MSA-2 复制入站 MSH-10——区域平台正是靠 MSA-2 把回执和它发的那条报文对起来，
 * 改一个字符就等于回了一条它认不出来的回执。
 *
 * <p><b>MSA-1 三值的分工</b>（与传输级 0x06 / 0x15 是两件事，不可互相替代）：
 * <ul>
 *   <li>{@code AA} 应用接受：这条我收下了，后面会走台账与确认门；<b>不产出 ERR 段</b>。</li>
 *   <li>{@code AE} 应用错误：成因可能是一次抖动或对端报文不完整，<b>可重投</b>，必须带 ERR。</li>
 *   <li>{@code AR} 应用拒绝：确定性事实（字符集不支持 / 身份硬锚点冲突 / 版本不兼容），<b>不重投</b>，必须带 ERR。</li>
 * </ul>
 *
 * <p><b>源产品对应语义</b>：{@code MLLPConnection} 的 {@code ACK=0x06} / {@code NAK=0x15}（{@code :55-56}）
 * 是<b>传输级</b>双分支，{@code readACK()}（{@code :116-131}）收到 NAK 抛异常但上层仍持有报文原文——
 * 「失败了要拿得到原文去查」这一点被本类保留：ERR-4 里带定位与建议码，原始报文仍在本地台账。
 * 源实现只有 ACK/NAK 两值，本产品按 HL7 v2 的 MSA 语义细分出 AE/AR 不可重投的差别。
 *
 * <p><b>本类不判定四态</b>：已接收 / 待确认 / 已落地 / 拒收由调用方按台账状态决定，
 * 本类只负责把调用方给定的判定结果拼成对端认得的报文形状。
 */
public final class AckComposer {

    /** 应用级回执代码：应用接受。 */
    public static final String CODE_AA = "AA";
    /** 应用级回执代码：应用错误，可重投。 */
    public static final String CODE_AE = "AE";
    /** 应用级回执代码：应用拒绝，不可重投。 */
    public static final String CODE_AR = "AR";

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
            .withZone(ZoneOffset.UTC);

    private final String localAet;
    private final String remoteAet;
    private final Clock clock;
    private final AtomicLong ackSequence = new AtomicLong();

    public AckComposer(String localAet, String remoteAet) {
        this(localAet, remoteAet, Clock.systemUTC());
    }

    /**
     * 注入固定时钟，回执里的时间戳才可复现。
     *
     * <p>公开这个构造是为了让跨包的方言对照测试能断言「同一份产物逐字节相同」：
     * 用系统时钟时自增控制号与时间戳每条都不同，两种方言的产物没法比对。
     */
    public AckComposer(String localAet, String remoteAet, Clock clock) {
        this.localAet = localAet;
        this.remoteAet = remoteAet;
        this.clock = clock;
    }

    /**
     * 组装成功回执（{@code MSA-1=AA}，无 ERR 段）。
     *
     * @param in 入站报文头
     * @return ER7 文本，段分隔符 CR
     */
    public String composeAccepted(InboundHeader in) {
        return compose(in, CODE_AA, null, null);
    }

    /**
     * 组装拒收回执（{@code MSA-1} 由拒收原因决定，附 ERR 段）。
     *
     * @param in     入站报文头
     * @param reason 拒收原因（AE / AR 及可重投标记由它决定）
     * @param error  可定位错误；{@code null} 表示成因不在某个字段上（如身份冲突）
     * @return ER7 文本，段分隔符 CR
     */
    public String composeRejected(InboundHeader in, RejectionReason reason, LocatableError error) {
        Objects.requireNonNull(reason, "reason");
        return compose(in, reason.msa1(), msaTextFor(reason, error), ErrSegment.of(reason, error));
    }

    /** 无可定位字段时的拒收回执（如身份证硬锚点冲突：错在两套主键之间，不在某个字段上）。 */
    public String composeRejected(InboundHeader in, RejectionReason reason) {
        return composeRejected(in, reason, null);
    }

    /** MSA-3 文本：原因的一行中文说明 + 段字段位置（不含原值片段，片段在 ERR-4）。 */
    private static String msaTextFor(RejectionReason reason, LocatableError error) {
        if (error == null) {
            return reason.zhLabel();
        }
        return reason.zhLabel() + "：" + error.fieldRef();
    }

    /**
     * 组装回执。
     *
     * @param in        入站报文头
     * @param ackCode   MSA-1（AA / AE / AR）
     * @param errorText MSA-3 说明；成功时传 {@code null}
     * @param err       ERR 段；成功时传 {@code null}，拒收时必须非 {@code null}
     */
    public String compose(InboundHeader in, String ackCode, String errorText, ErrSegment err) {
        Objects.requireNonNull(in, "inbound header");
        Objects.requireNonNull(ackCode, "ack code");
        // 拒收必须带 ERR：没有 ERR 的 AE/AR，区域平台收到后既不知道错在哪条报文，也不知道该不该重投。
        if (!CODE_AA.equals(ackCode) && err == null) {
            throw new IllegalArgumentException(
                    "MSA-1=" + ackCode + " 是拒收回执，必须带 ERR 段；请改用 composeRejected(RejectionReason, …)");
        }
        // 成功回执带 ERR 是自相矛盾：对端按 MSA-1=AA 会认为这条已收下，ERR 只会污染对端台账。
        if (CODE_AA.equals(ackCode) && err != null) {
            throw new IllegalArgumentException("MSA-1=AA 是成功回执，不得携带 ERR 段");
        }

        String sep = String.valueOf(in.fieldSeparator());
        String comp = String.valueOf(in.componentSeparator());
        String ackControlId = nextAckControlId();
        Delimiters d = in.delimiters();

        // MSH-2 .. MSH-18，共 17 个字段；MSH-1 本身就是分隔符，不占字段位
        List<String> msh = new ArrayList<>(17);
        msh.add(in.encodingCharacters());
        msh.add(localAet);
        msh.add(remoteAet);
        // ACK 的 MSH-5/MSH-6 与入站的 MSH-3/MSH-4 对调
        msh.add(in.sendingApplication());
        msh.add(in.sendingFacility());
        msh.add(TS.format(clock.instant()));
        msh.add("");
        msh.add("ACK" + comp + "ACK" + comp + "HL7_v" + in.versionOrDefault());
        msh.add(ackControlId);
        msh.add(in.processingId());
        msh.add(in.version());
        msh.add("");
        msh.add("");
        msh.add("AL");
        msh.add("NE");
        msh.add("");
        msh.add(in.characterSet());

        // MSA-1 .. MSA-11
        List<String> msa = new ArrayList<>(11);
        msa.add(ackCode);
        // MSA-2 逐字复制入站 MSH-10：平台靠它把回执对回它发的那条，不做任何归一化
        msa.add(in.controlId());
        // MSA-3 是自由文本，必须转义：中文说明里可能带分隔符
        msa.add(Escapes.escape(errorText, d));
        msa.add("");
        msa.add("");
        msa.add("");
        msa.add("");
        msa.add("");
        // MSA-9 = ACK 的控制号（ACK_CONN_ACK），MSA-10 = 版本
        msa.add(ackControlId);
        msa.add(in.versionOrDefault());
        msa.add("");

        StringBuilder sb = new StringBuilder();
        sb.append("MSH").append(sep).append(String.join(sep, msh)).append('\r');
        sb.append("MSA").append(sep).append(String.join(sep, msa)).append('\r');
        if (err != null) {
            sb.append(err.toEr7(d)).append('\r');
        }
        return sb.toString();
    }

    /** 回执自身的控制号：进程内自增 + 时间戳，重启不重号，便于对端按控制号排查。 */
    private String nextAckControlId() {
        return String.format("ACK%06d%s", ackSequence.incrementAndGet(), TS.format(clock.instant()));
    }

    /** 已发出的回执条数，供运行摘要与冒烟输出用。 */
    public long issuedCount() {
        return ackSequence.get();
    }
}