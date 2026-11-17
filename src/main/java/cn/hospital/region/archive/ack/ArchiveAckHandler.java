package cn.hospital.region.archive.ack;

import cn.hospital.region.archive.hl7.CharsetResolver;
import cn.hospital.region.archive.hl7.DelimiterNotDeclared;
import cn.hospital.region.archive.hl7.FieldLocator;
import cn.hospital.region.archive.hl7.FrameDecoder;
import cn.hospital.region.archive.hl7.Hl7Message;
import cn.hospital.region.archive.hl7.Hl7ParseException;
import cn.hospital.region.archive.hl7.LocatableError;
import cn.hospital.region.archive.hl7.UnsupportedCharset;
import cn.hospital.region.archive.mllp.MllpServer;
import cn.hospital.region.archive.model.DegradedResult;
import cn.hospital.region.archive.redelivery.RedeliveryIntentSet;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;

/**
 * 接入侧正式处理链 —— 一帧入站字节 → 结构化定位 → 一条可逐条对账的应用级回执。
 *
 * <p>{@link AckEchoHandler} 是主干最窄那条路径的回显器：收下、回 AA，不看报文里有什么。
 * 本类是接入侧真正对客的那条：每一条报文都必须落到一个<b>可解释</b>的判定上，
 * 且判定要能翻译成区域平台读得懂的 MSA-1 与 ERR 段——这正是源产品那句用户原话
 * 「对不上格式的那条自己掉出来让我看，而不是整包退回去」的落点。
 *
 * <pre>
 *   入站字节 → InboundHeader.parse（读 MSH 自述分隔符，回执要逐字复制的东西）
 *            → FrameDecoder.decode（按 MSH-18 解码 + 段/字段索引）
 *            → FieldLocator.firstBlocking（缺哪段哪个字段，段内第几段，原值片段）
 *            → AckComposer.composeAccepted / composeRejected
 *            → MessageReceiptBook 记账 + RedeliveryIntentSet 收集可重投条目
 *            → 按入站声明的字符集编码出站
 * </pre>
 *
 * <p><b>缺 MSH-10 也照样回一条 AE</b>：控制号缺失时 MSA-2 只能回空串，但 ERR 段仍然带
 * 「MSH-10 报文控制号缺失或为空」与段字段位置，区域平台能知道是哪一条生成侧出了问题。
 * 这比整帧不回应用级回执要好——不回回执的话，对端只能一直等，等到第二天追问，
 * 而本端连「错在哪」都没记下来。
 *
 * <p><b>出站字符集跟随入站声明</b>：区域平台声明 GB18030 就用 GB18030 回，让对端按它自己声明的
 * 口径解码。唯一例外是入站声明了本端解不出的字符集（{@code MARC-8} 之类）：此时回执
 * 把 MSH-18 改写为 {@code UNICODE UTF-8} 并按 UTF-8 出站——带着一个自己写不出来的字符集
 * 声明发回去，对端解出来只会是乱码，改成实情反而是对端能读懂的。
 */
public final class ArchiveAckHandler implements MllpServer.MessageHandler {

    /** 回执声明：本端写不出入站声明的字符集时，用这个值如实告知对端。 */
    public static final String REPLY_CHARSET_DECLARATION = "UNICODE UTF-8";

    private final AckComposer composer;
    private final MessageReceiptBook book;
    private final RedeliveryIntentSet intentSet;

    /** 只回执不记账：给只关心报文内容的调用点（如协议自检脚本）。 */
    public ArchiveAckHandler(String localAet, String remoteAet) {
        this(new AckComposer(localAet, remoteAet), null, null);
    }

    /** 记账但不收集重投意图。 */
    public ArchiveAckHandler(AckComposer composer, MessageReceiptBook book) {
        this(composer, book, null);
    }

    /** 完整装配：回执 + 应用级账页 + 重投意图集。 */
    public ArchiveAckHandler(AckComposer composer, MessageReceiptBook book, RedeliveryIntentSet intentSet) {
        if (composer == null) {
            throw new IllegalArgumentException("AckComposer 不得为 null");
        }
        this.composer = composer;
        this.book = book;
        this.intentSet = intentSet;
    }

    /**
     * 一帧入站报文的<b>接入侧判定结果</b> —— 「这条收到了吗、能不能读、缺哪段哪个字段、
     * 回执该按哪个字符集出站」。判定与回执组装在<b>同一个出口</b>，因此接入主路径
     * （{@link cn.hospital.region.archive.ledger.ArchiveIngestService}）与本类看到的
     * 永远是同一份结论，不会出现「台账说收下了、回执说 AE」这种两处各判一次的分裂。
     *
     * @param header        入站报文头（回执要逐字复制的字段都从这里取）
     * @param replyCharset  回执出站字符集（本端写不出入站声明时为 UTF-8）
     * @param reason        拒收原因；{@code null} 表示结构与必填字段全部通过
     * @param located       可定位错误（缺哪段哪个字段）；成因不在字段上时为 {@code null}
     * @param message       结构化报文；解析失败时为 {@code null}
     */
    public record Triage(InboundHeader header, Charset replyCharset, RejectionReason reason,
                         LocatableError located, Hl7Message message) {

        public boolean accepted() {
            return reason == null;
        }
    }

    /**
     * 判定一帧（已剥离 MLLP 帧的）入站报文，<b>不出站任何字节</b>。
     *
     * @return 判定结果；<b>连 MSH 都读不出时返回 {@code null}</b>
     *         （没有控制号就没有可指向的回执，MSA-2 无从复制，任何一条 ACK 对端都认不出来）
     */
    public Triage triage(byte[] payload) {
        InboundHeader header;
        try {
            // MSH 段本身是 ASCII 协议头，先按 ASCII 读出回执要逐字复制的那几个字段；
            // 中文姓名等业务字段由 FrameDecoder 按 MSH-18 解码，不在这里碰。
            header = InboundHeader.parse(new String(payload, StandardCharsets.US_ASCII));
        } catch (RuntimeException e) {
            System.err.println("[ack] 读不出 MSH 头（" + e.getMessage() + "），本条不出应用级回执");
            return null;
        }

        Charset replyCharset;
        try {
            replyCharset = CharsetResolver.resolve(header.characterSet(), null).charset;
        } catch (UnsupportedCharset e) {
            // 入站声明了本端写不出的字符集：回执如实改声明，按 UTF-8 出站（见类注释）
            replyCharset = StandardCharsets.UTF_8;
            header = withCharacterSet(header, REPLY_CHARSET_DECLARATION);
        }

        RejectionReason reason = null;
        LocatableError located = null;

        Hl7Message message = null;
        try {
            FrameDecoder.DecodedMessage decoded = FrameDecoder.decode(payload);
            message = decoded.message;
            // 有损解码只记告警、不改判定：报文结构仍可解析、回执仍能组装，整条必须继续往下走。
            // 这里把它写进 stderr 而不是只留在 DecodedMessage 里，是因为告警要跟着这条报文
            // 出现在排障输出中——「这句中文没解出来」和「这条报错了」是两种现场问题，
            // 只留在内存里的话现场看到的现象完全一样。
            if (decoded.lossy()) {
                // 两种情形要分开说：降级是「本端换了口径、文字取回来了」，
                // 替换字符是「这些字节真的丢了」。合成一句话会让现场分不清该找谁改。
                System.err.println("[ack] 解码不可全信：MSH-18 声明 " + decoded.charsetName
                        + "，实际按 " + decoded.appliedCharsetName + " 解码"
                        + (decoded.charsetDegraded ? "（声明与字节写法不一致，已降级）" : "")
                        + "，含 " + decoded.replacementCount + " 个替换字符；"
                        + "继续处理，不回退为拒收");
            }
        } catch (UnsupportedCharset e) {
            // 确定性事实：重投一万次还是这个字节 → AR，不进重投
            reason = RejectionReason.UNSUPPORTED_CHARSET;
        } catch (Hl7ParseException | DelimiterNotDeclared e) {
            // 报文结构整体读不下去（段分隔符缺失、段太短等），定位不到具体字段：
            // error 传 null，ERR 段只带原因码与中文说明，不编一个段字段位置出来。
            reason = RejectionReason.MISSING_REQUIRED_SEGMENT;
        }

        if (reason == null) {
            Optional<LocatableError> blocking = FieldLocator.firstBlocking(message);
            if (blocking.isPresent()) {
                located = blocking.get();
                reason = reasonFor(located.suggestedCode());
            }
        }
        return new Triage(header, replyCharset, reason, located, message);
    }

    /**
     * 按判定结果组装应用级回执并按出站字符集编码。
     *
     * <p>{@link #triage} 与本方法配套使用，保证「判定是什么」与「回执说什么」永远一致。
     */
    public byte[] ackBytes(Triage triage) {
        String er7 = triage.accepted()
                ? composer.composeAccepted(triage.header())
                : composer.composeRejected(triage.header(), triage.reason(), triage.located());
        return er7.getBytes(triage.replyCharset());
    }

    /**
     * 把判定结果记进内存账页与重投意图集。
     *
     * <p>记账<b>只有一个出口</b>：本类的 {@link #handle} 与接入主路径的
     * {@link cn.hospital.region.archive.ledger.ArchiveIngestService} 都调本方法，
     * 于是「回执判成什么」与「台账/重投集记成什么」永远是同一个结果，
     * 不会出现两个调用点各判一次、结论却不一致的情况。
     */
    public void record(Triage triage) {
        if (triage == null) {
            return;
        }
        InboundHeader header = triage.header();
        boolean redeliverable = !triage.accepted() && triage.reason().redeliverable();
        String ackCode = triage.accepted() ? AckComposer.CODE_AA : triage.reason().msa1();
        String key = MessageReceiptBook.idempotencyKey(
                header.messageType(), header.controlId(), header.processingId());
        if (book != null) {
            book.record(header.messageType(), header.controlId(), header.processingId(),
                    ackCode, triage.located() == null ? "" : triage.located().suggestedCode(),
                    redeliverable);
        }
        if (intentSet != null) {
            intentSet.offer(key, redeliverable);
        }
    }

    /**
     * 降级条目的回执：{@code MSA-1=AE + ERR}，成因固定为 {@link RejectionReason#MODEL_DEGRADED}。
     *
     * <p><b>为什么降级回 AE 而不是 AA</b>：AE 的语义是「这条我收到了，但本端这边出了点状况，
     * 重投有可能就成了」——正是模型挂掉这件事。区域平台收到 AE 会把它留在重投队列里，
     * 模型恢复后重投一次就能直接对齐。而 AA 会让对端认为这条已经处理完、停止重投，
     * 那条居民档案就停在本端台账里直到有人手动翻出来为止。
     *
     * <p>ERR 段带的是<b>固定口径</b>，不带模型端的原始报错：区域平台需要知道的是
     * 「这条可重投」，本端的五类错误分类只进本端台账与调用审计。
     */
    public byte[] ackDegraded(Triage triage, DegradedResult degraded) {
        Objects.requireNonNull(degraded, "降级结果不得为 null");
        if (triage == null) {
            return null;
        }
        return composer.composeRejected(triage.header(), RejectionReason.MODEL_DEGRADED)
                .getBytes(triage.replyCharset());
    }

    /**
     * 降级条目的记账：应用级账页记 AE + 可重投，重投意图集同样收下。
     *
     * <p>与 {@link #record} 并列而不是并进去：降级条目的 MSA-1 与错误码都由
     * {@link RejectionReason#MODEL_DEGRADED} 决定，而<b>不是</b>由某个字段缺失决定，
     * 塞进 {@code record} 的参数里会让「缺哪个字段」与「模型挂了」在同一条语句里分不清。
     */
    public void recordDegraded(Triage triage, DegradedResult degraded) {
        if (triage == null || degraded == null) {
            return;
        }
        InboundHeader header = triage.header();
        String key = MessageReceiptBook.idempotencyKey(
                header.messageType(), header.controlId(), header.processingId());
        if (book != null) {
            book.record(header.messageType(), header.controlId(), header.processingId(),
                    AckComposer.CODE_AE, RejectionReason.MODEL_DEGRADED.suggestedCode(), true);
        }
        if (intentSet != null) {
            // AE 一律可重投，与 AckComposer 的「拒收必带 ERR」约束同源：模型恢复后重投即成。
            intentSet.offer(key, true);
        }
    }

    /**
     * 处理一帧（已剥离 MLLP 帧）的入站报文。
     *
     * @return 应用级回执报文字节（ER7，按入站声明的字符集编码）；<b>连 MSH 都读不出时返回 {@code null}</b>
     */
    @Override
    public byte[] handle(byte[] payload) {
        Triage triage = triage(payload);
        if (triage == null) {
            return null;
        }
        record(triage);
        return ackBytes(triage);
    }

    /**
     * 定位码 → 拒收原因。
     *
     * <p>只有「整段缺失」单独成类；MSH-9 缺、MSH-12 缺、PID-3 缺这些都归「必需字段缺失」。
     * 归 AE 而不是 AR 的理由：这些都是<b>对端生成侧</b>的缺失或截断，重投一次很可能就带全了；
     * AR 的语义是「重投一万次还是这个字节」，只留给字符集不支持、身份锚点冲突、版本不兼容
     * 这三类确定性事实。把「缺 MSH-9」判成 AR，会让一次生成抖动永久丢掉一条居民档案。
     */
    static RejectionReason reasonFor(String suggestedCode) {
        if (FieldLocator.CODE_MISSING_SEGMENT.equals(suggestedCode)) {
            return RejectionReason.MISSING_REQUIRED_SEGMENT;
        }
        return RejectionReason.MISSING_REQUIRED_FIELD;
    }

    /** 复制一份只改了 MSH-18 的入站头（记录是 record，复制构造器即可）。 */
    private static InboundHeader withCharacterSet(InboundHeader in, String declaration) {
        return new InboundHeader(in.sendingApplication(), in.sendingFacility(), in.timestamp(),
                in.messageType(),
                in.controlId(), in.processingId(), in.version(), declaration,
                in.fieldSeparator(), in.encodingCharacters(), in.componentSeparator(),
                in.repetitionSeparator(), in.subcomponentSeparator(), in.escapeCharacter());
    }
}