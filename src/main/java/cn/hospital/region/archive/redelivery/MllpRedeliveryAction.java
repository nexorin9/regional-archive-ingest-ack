package cn.hospital.region.archive.redelivery;

import cn.hospital.region.archive.hl7.CharsetResolver;
import cn.hospital.region.archive.hl7.Hl7Message;
import cn.hospital.region.archive.hl7.Hl7Parser;
import cn.hospital.region.archive.ledger.LedgerRow;
import cn.hospital.region.archive.ledger.LedgerStore;
import cn.hospital.region.archive.mllp.MllpClient;
import cn.hospital.region.archive.mllp.MllpReconnectExhaustedException;
import cn.hospital.region.archive.mllp.MllpReconnectPolicy;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 重投执行动作 —— 把台账里存着的<b>报文原文</b>按 MLLP 帧重新发给区域平台，
 * 并按对端回来的应用级回执（{@code MSA-1}）给出成功 / 可重试 / 永久失败三态结论。
 *
 * <h2>为什么「重投」是把报文原样发回给平台</h2>
 * <p>差集（T34）回答的是「对端次日追问：哪几条回执我没记下」。缺的是<b>回执</b>，
 * 而 HL7 v2 over MLLP 的回执只在当次连接里随帧回传，连接一关就没有第二次机会。
 * 区域平台的 MLLP 入口本身是双向的：把这条报文原样再推一次，平台读过后回一条
 * 应用级 {@code MSA-1=AA}，本端据此判定「这条对端确实收下了」，台账才推进到
 * {@link cn.hospital.region.archive.ledger.MessageState#RETRY_CONFIRMED}。
 * <p>本产品<b>不改写</b>报文内容：重投的就是当初收到的那些字节（按 MSH-18 声明的
 * 字符集重新编码），否则对端会当成一条新的档案记录，产生第二份居民档案。
 *
 * <h2>三种对端答复与三种结论</h2>
 * <table border="1">
 *   <caption>对端答复 → 重投结论</caption>
 *   <tr><th>对端答复</th><th>结论</th><th>理由</th></tr>
 *   <tr><td>{@code MSA-1=AA}</td><td>成功</td><td>平台已收下，闭环成立</td></tr>
 *   <tr><td>{@code MSA-1=AE}</td><td>可重试</td><td>应用级错误（多半是平台侧临时问题）</td></tr>
 *   <tr><td>{@code MSA-1=AR}</td><td>永久失败</td><td>确定性拒绝，重发同一份字节只会再被拒一次</td></tr>
 *   <tr><td>传输级 NAK / 超时 / 连不上</td><td>可重试</td><td>网络抖动，退避后重来</td></tr>
 *   <tr><td>回执解不出 MSA-1</td><td>永久失败</td><td>同一份字节重发只会得到同一份答复</td></tr>
 * </table>
 *
 * <h2>兼容两种对端回执方言</h2>
 * <p>对端可能先发传输级 {@code 0x06} 再发带帧的应用级回执（{@code 0B 06 1C 0D}），
 * 也可能直接发应用级回执不带传输级确认（本轮 T9 的 {@code bare_ack} 方言）。
 * 字节读取由 {@link MllpClient} 承担，两种方言都读：第一个字节是 {@code 0x06} 就再读一帧，
 * 是 {@code 0x0B} 就直接收帧。
 *
 * <h2>断线自愈与本类的责任边界</h2>
 * <p>连接中途被对端关掉时，{@link MllpClient} 会按参考实现的
 * 「分类 → 重建连接 → 重试同一操作」三段语义自动恢复，重试上限与退避由
 * {@link MllpReconnectPolicy} 从环境变量来。预算用尽时它抛
 * {@link MllpReconnectExhaustedException}，本类把它收敛成
 * <b>可重试失败</b>而不是「已投出」——报文原文与幂等键仍留在台账，队列条目退回
 * {@code 待重投}，退避后重投，进程重启时还有恢复扫描兜底。
 *
 * <h2>凭据与配置</h2>
 * <p>目标地址来自环境变量 {@code ARCHIVE_REDELIVERY_HOST} / {@code ARCHIVE_REDELIVERY_PORT}，
 * 重连预算来自 {@code ARCHIVE_MLLP_RECONNECT_*}（均由
 * {@link cn.hospital.region.archive.config.AppConfig} 校验后传入，缺任一必需项进程启动即非零退出）。
 * 本类<b>不读环境变量</b>，因此可在测试里指向回环地址上的真实 {@link java.net.ServerSocket}。
 */
public final class MllpRedeliveryAction implements RedeliveryAction {

    private static final Logger LOG = Logger.getLogger(MllpRedeliveryAction.class.getName());

    /** 建连超时（毫秒）。 */
    public static final int DEFAULT_CONNECT_TIMEOUT_MS = 5_000;

    /** 读回执超时（毫秒）；对端不回就按可重试失败处理。 */
    public static final int DEFAULT_READ_TIMEOUT_MS = 15_000;

    /** last_error 列宽（V5 schema 为 VARCHAR(256)），本类写出的说明一律截到该长度内。 */
    private static final int MAX_LAST_ERROR = 256;

    /** 台账里取报文原文的口子。 */
    @FunctionalInterface
    public interface PayloadSource {

        /** 按台账行主键取回原文；行不存在返回 {@link Optional#empty()}。 */
        Optional<String> rawPayloadOf(long ledgerId);

        /** 从台账存储取（生产装配用这个）。 */
        static PayloadSource fromLedger(LedgerStore store) {
            Objects.requireNonNull(store, "台账存储不得为 null");
            return ledgerId -> {
                try {
                    return store.findById(ledgerId).map(LedgerRow::rawPayload);
                } catch (SQLException e) {
                    throw new PayloadUnavailableException("台账读取失败: " + e.getMessage(), e);
                }
            };
        }
    }

    /** 台账读不出来时的运行时异常；被 {@link #perform} 收敛为可重试失败。 */
    public static final class PayloadUnavailableException extends RuntimeException {
        public PayloadUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final PayloadSource payloads;
    private final MllpClient client;
    private final int readTimeoutMs;

    public MllpRedeliveryAction(PayloadSource payloads, String remoteHost, int remotePort) {
        this(payloads, remoteHost, remotePort, DEFAULT_CONNECT_TIMEOUT_MS, DEFAULT_READ_TIMEOUT_MS);
    }

    public MllpRedeliveryAction(PayloadSource payloads, String remoteHost, int remotePort,
                                int connectTimeoutMs, int readTimeoutMs) {
        this(payloads, new MllpClient(remoteHost, remotePort,
                new MllpReconnectPolicy(MllpReconnectPolicy.DEFAULT_MAX_ATTEMPTS,
                        MllpReconnectPolicy.DEFAULT_BASE_BACKOFF_MS,
                        MllpReconnectPolicy.DEFAULT_MAX_BACKOFF_MS,
                        connectTimeoutMs, readTimeoutMs)));
    }

    /**
     * 直接给定一个已装配的 {@link MllpClient}（生产装配用这条：重连上限与退避来自环境变量）。
     *
     * <p>本类只负责「取原文 → 按 MSH-18 编回字节 → 交给客户端发 → 译回执」，
     * 连接怎么建、断了怎么重建，全部由 {@link MllpClient} 承担 —— 那样断线自愈只有一处实现。
     */
    public MllpRedeliveryAction(PayloadSource payloads, MllpClient client) {
        this.payloads = Objects.requireNonNull(payloads, "报文来源不得为 null");
        this.client = Objects.requireNonNull(client, "MLLP 客户端不得为 null");
        this.readTimeoutMs = client.policy().readTimeoutMs();
    }

    /** 本端准备把报文重投到哪里（进日志与 {@code --check-config} 摘要，不含凭据）。 */
    public String describe() {
        return client.endpoint();
    }

    /** 传输层客户端（运行指标与排障看重建次数用）。 */
    public MllpClient client() {
        return client;
    }

    @Override
    public RedeliveryResult perform(RedeliveryEntry entry) {
        String raw;
        try {
            Optional<String> found = payloads.rawPayloadOf(entry.ledgerId());
            if (found.isEmpty()) {
                return RedeliveryResult.permanentFailure("PAYLOAD_MISSING",
                        "台账里没有 ledgerId=" + entry.ledgerId() + " 这一行，无原文可重投");
            }
            raw = found.get();
            if (raw.isBlank()) {
                return RedeliveryResult.permanentFailure("PAYLOAD_MISSING",
                        "ledgerId=" + entry.ledgerId() + " 的原文为空，无从重投");
            }
        } catch (PayloadUnavailableException e) {
            return RedeliveryResult.retryableFailure("PAYLOAD_UNAVAILABLE", trim(e.getMessage()));
        }

        byte[] payload;
        try {
            payload = encode(raw);
        } catch (RuntimeException e) {
            // 原文结构不可解析（库里存的不是 ER7）：重发同一份内容只会同样失败
            return RedeliveryResult.permanentFailure("PAYLOAD_UNPARSABLE", trim(e.toString()));
        }

        byte[] response;
        try {
            // 传输全部交给 MllpClient：连接中途被对端关掉时它会重建并重发同一份字节
            response = client.exchange(payload);
        } catch (MllpReconnectExhaustedException e) {
            // 重试预算用尽。这<b>不是</b>「已经投出去了」——报文仍在台账里、条目仍在队列里，
            // 收敛成可重试失败让调度器退避后重投，进程重启时也由恢复扫描捞回来。
            return RedeliveryResult.retryableFailure("MLLP_RECONNECT_EXHAUSTED", trim(e.getMessage()));
        } catch (IOException e) {
            return RedeliveryResult.retryableFailure("MLLP_TRANSPORT",
                    "MLLP 传输失败: " + e.getClass().getSimpleName() + ": " + trim(e.getMessage()));
        }
        if (response == null) {
            // 只有传输级 NAK 会走到这里：那是明确答复，重发同一份字节多半再被拒一次，
            // 但仍属可重试（对端可能只是这一下忙不过来），交由退避队列决定。
            return RedeliveryResult.retryableFailure("MLLP_TRANSPORT_NAK",
                    "对端回传输级 NAK（0x15）：本次投递未被接受，退避后可重投");
        }
        return interpret(response, entry);
    }

    // =====================================================================
    // 回执解读（两种方言的字节读取已在 MllpClient 内完成）
    // =====================================================================

    /** 把对端回执译成本产品要的结论。 */
    private RedeliveryResult interpret(byte[] ackFrame, RedeliveryEntry entry) {
        String text;
        try {
            text = new String(ackFrame, charsetOfAck(ackFrame));
        } catch (RuntimeException e) {
            return RedeliveryResult.permanentFailure("REMOTE_ACK_UNPARSABLE",
                    "对端回执字符集解不出: " + trim(e.toString()));
        }
        String msa1;
        try {
            Hl7Message message = Hl7Parser.parse(text);
            msa1 = message.field("MSA", 1).trim();
        } catch (RuntimeException e) {
            return RedeliveryResult.permanentFailure("REMOTE_ACK_UNPARSABLE",
                    "对端回执里取不到 MSA-1: " + trim(e.toString()));
        }
        if (msa1.isEmpty()) {
            return RedeliveryResult.permanentFailure("REMOTE_ACK_MISSING_MSA",
                    "对端回执的 MSA-1 为空：无法判断平台是否收下");
        }
        LOG.log(Level.INFO, "重投收到对端回执: {0} msa={1}", new Object[]{entry.toLogLine(), msa1});
        return switch (msa1) {
            case "AA" -> RedeliveryResult.ok("对端回 AA，批次 " + entry.batchId());
            case "AE" -> RedeliveryResult.retryableFailure("REMOTE_AE",
                    "对端回 AE（应用级错误，可重投）");
            case "AR" -> RedeliveryResult.permanentFailure("REMOTE_AR",
                    "对端回 AR（确定性拒绝，不重投）");
            default -> RedeliveryResult.permanentFailure("REMOTE_ACK_UNKNOWN_MSA",
                    "对端回了 MSA-1=" + msa1 + "，不是 AA/AE/AR 三值之一");
        };
    }

    /**
     * 对端回执的字符集：优先按它自己 MSH-18 声明的解，解不出时退回 ISO-8859-1。
     *
     * <p>ISO-8859-1 按字节一对一映射，<b>不会把字节改写成别的字符</b>；回执里只有 ASCII
     * 的 MSA 码，落到哪个字符集都不影响判断，但它能保证「解不出来」这件事不会变成
     * 一串替换字符把人引到错误的结论上。
     */
    private static Charset charsetOfAck(byte[] ackFrame) {
        try {
            String decoded = new String(ackFrame, StandardCharsets.UTF_8);
            String declared = Hl7Parser.parse(decoded).field("MSH", 18);
            return CharsetResolver.resolve(declared).charset;
        } catch (RuntimeException e) {
            return StandardCharsets.ISO_8859_1;
        }
    }

    // =====================================================================
    // 原文 → 字节
    // =====================================================================

    /**
     * 按原文自述的 MSH-18 字符集把台账里的字符串重新编码成字节。
     *
     * <p>入库时是「按声明字符集解码成字符串」，重投时就必须「按同一字符集编码回字节」，
     * 否则 GB18030 下发���中文档案会在重投时变成乱码，而对端会把它当成另一条记录。
     */
    private static byte[] encode(String raw) {
        Charset charset;
        try {
            charset = CharsetResolver.resolve(Hl7Parser.parse(raw).field("MSH", 18)).charset;
        } catch (RuntimeException e) {
            // 取不到 MSH-18（原文连段结构都不对）：退回默认解码器，保持与入库同一条口径
            charset = CharsetResolver.resolve(null).charset;
        }
        return raw.getBytes(charset);
    }

    private static String trim(String value) {
        if (value == null) {
            return "";
        }
        String oneLine = value.replaceAll("\\s+", " ").trim();
        return oneLine.length() > MAX_LAST_ERROR ? oneLine.substring(0, MAX_LAST_ERROR) : oneLine;
    }
}