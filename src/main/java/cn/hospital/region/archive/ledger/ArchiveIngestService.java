package cn.hospital.region.archive.ledger;

import cn.hospital.region.archive.ack.ArchiveAckHandler;
import cn.hospital.region.archive.ack.AckComposer;
import cn.hospital.region.archive.ack.InboundHeader;
import cn.hospital.region.archive.hl7.CharsetResolver;
import cn.hospital.region.archive.hl7.Delimiters;
import cn.hospital.region.archive.hl7.Hl7Message;
import cn.hospital.region.archive.hl7.UnsupportedCharset;
import cn.hospital.region.archive.identity.PatientIndexWriteException;
import cn.hospital.region.archive.identity.PatientIndexWriter;
import cn.hospital.region.archive.mllp.MllpServer;
import cn.hospital.region.archive.model.ConfidenceSample;
import cn.hospital.region.archive.model.ConfidenceSampleSink;
import cn.hospital.region.archive.model.DegradedResult;
import cn.hospital.region.archive.model.ModelOutcome;
import cn.hospital.region.archive.model.ResilientModelGateway;
import cn.hospital.region.archive.model.ZFieldMap;
import cn.hospital.region.archive.model.ZSegmentReader;
import cn.hospital.region.archive.model.ZSegment;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 接入主路径 —— <b>一条入站报文从字节走到「已落地」的全部动作都在这里</b>。
 *
 * <pre>
 *   MLLP 帧字节
 *     → ArchiveAckHandler.triage        读 MSH 自述分隔符、按 MSH-18 解码、定位缺段缺字段
 *     → 预读台账命中？                  重复到达 → 不跑模型、不入账、不动状态（只回一条按当前态的回执）
 *     → ResilientModelGateway.alignZFields  模型环节（Z 段语义对齐），五类失败收敛到降级
 *     → IdempotencyGuard.tryAcquire    幂等键 (MSH-9, MSH-10, MSH-11) 占用
 *          ├─ Duplicate → 并发竞态兜底，同样不跑业务、不动状态
 *          └─ Acquired  → 结构通过：RECEIVED → PENDING_CONFIRM（等人确认）
 *                         结构通过但模型降级：RECEIVED → PENDING_CONFIRM + degraded=true，回 AE
 *                         结构不通过：RECEIVED → REJECTED（AE/AR，AR 不进重投）
 *     → ArchiveAckHandler.record/recordDegraded/ackBytes/ackDegraded
 *                                          回执与台账记的是同一个判定结果
 *
 *   人工确认后（ConfirmGate，T30 的入口在 land 之前）：
 *     → land(...)  乐观锁 CAS 认领 PENDING_CONFIRM → LANDED → PatientIndexWriter.put(...)
 * </pre>
 *
 * <h2>模型挂掉为什么不能拖垮主路径</h2>
 * <p>模型只做两件规则做不好的事，拆帧、结构化、必填校验、字符集、幂等键、ACK 组帧全是确定性的。
 * 因此模型环节<b>只影响这一条报文的处理方式，不影响它有没有处理</b>：
 * 降级时该条照样入账、照样回一条区域平台认得的 MSA 回执（AE + ERR，可重投），
 * 只是停在待确认等人点一下。<b>绝不出现「因为模型挂了就一条都不回执」</b>——
 * 那会让区域平台把整批都留在重投队列里，而本端连每条到底收没收到都没记下来。
 *
 * <h2>为什么「确认」与「落地写入」要分成两个方法</h2>
 * <p>本产品最硬的一条红线是<b>没有确认动作时 HIS 写入调用次数必须恰好为 0</b>。
 * 若把写入混进 {@link #ingest}，这条红线就没有可测的落点——「重放 100 次写了 100 次」
 * 与「重放 100 次写了 1 次」在代码上会长得一模一样。因此接收与落地分成两段：
 * <ul>
 *   <li>{@link #ingest} 只做接收与入账，<b>绝不</b>碰患者主索引；</li>
 *   <li>{@link #land} 是唯一的落地写入点，调用它就等于「有人确认了这条」，
 *       它自己也带一道「当前态必须是待确认」的闸（重复确认在写之前就被拒）。</li>
 * </ul>
 * T30 的 {@code ConfirmGate} 落在这条缝里：从 token 取操作人、校验决策，再调 {@link #land}。
 * 端口不变，T16 的「重放 100 次写 1 次」断言在 T30/T31 接入后原样成立。
 *
 * <h2>并发下为什么写入恰好一次</h2>
 * <p>{@link #land} 用<b>带版本守卫的乐观锁</b>把「待确认 → 已落地」当作一次<b>认领</b>：
 * UPDATE 带 {@code WHERE id = ? AND state = ? AND version = ?}，20 个线程同时点确认时
 * 只有一个能把行推走，其余 19 个拿到 {@link ConcurrentLedgerUpdateException} 或
 * {@link LedgerStateConflictException}，<b>在写之前</b>就被拒。因此不会出现
 * 「20 个线程都认为该写、写出去 20 次」。写失败则把行退回待确认（{@code LANDED → PENDING_CONFIRM}，
 * 状态机允许这条边），保留原文与幂等键等人重试——失败绝不静默改数据。
 */
public final class ArchiveIngestService implements MllpServer.MessageHandler {

    private static final Logger LOG = Logger.getLogger(ArchiveIngestService.class.getName());

    /** 自动迁移的操作人：人工动作才有具体的人（来自 token subject）。 */
    private static final String SYSTEM_OPERATOR = "system";

    /** 入账后推进到待确认的原因短语。 */
    public static final String REASON_AWAITING_CONFIRM = "received-awaiting-identity-confirm";

    /**
     * 拒收入账的原因前缀：具体码由 T7 的 FieldLocator 给出，这里只标明是哪一档。
     *
     * <p><b>公开</b>是为了让 T19 的只读导出能把它从审计行的 {@code reason} 里剥回原因码：
     * 统计与导出不需要重新解析报文原文，靠的就是「{@code rejected:} + 本地码」这一条约定。
     * 写侧仍只有本类一处产生该前缀（{@code insertIfAbsent} 之外没有任何地方写这条 reason）。
     */
    public static final String REASON_PREFIX_REJECTED = "rejected:";

    /** 落地认领成功后的原因短语（审计行 operator 带的是确认人）。 */
    public static final String REASON_CONFIRMED_LANDED = "confirmed-landed";

    /** 写失败退回待确认的原因短语。 */
    public static final String REASON_HIS_WRITE_FAILED = "his-write-failed";

    /**
     * 模型降级入账的原因前缀：具体错误码由 {@code DegradedResult.ledgerReason()} 补上，
     * 形如 {@code model-degraded:READ_TIMEOUT}。
     *
     * <p>与 {@link #REASON_PREFIX_REJECTED} 分开是因为二者的<b>终局不同</b>：
     * 拒收是终态（对端别再发），降级永远停在待确认（等人或等重投）。
     * 让 T19 的导出按前缀剥离时，两者不会混进同一张统计表。
     */
    public static final String REASON_PREFIX_DEGRADED = "model-degraded:";

    /**
     * 批次号口径 —— 「这条报文属于哪一批」，T19 的按批次统计与 T34 的对账差集都以它为准。
     *
     * <p>默认口径：<b>MSH-3 发送应用 + MSH-7 的日期部分</b>。理由是区域平台每天一个推送批次、
     * 批次内控制号各自唯一；用「发送方 + 日期」既能把不同区域平台的批次分开，
     * 又不会把同一天的两次推送错并成一批。MSH-7 读不到时退化为只按发送应用分批，
     * MSH-3 也为空时用固定串——<b>绝不返回空串</b>：没有批次归属的报文按批次对账时会被永远漏掉。
     */
    @FunctionalInterface
    public interface BatchIdResolver {
        String batchIdOf(InboundHeader header, Hl7Message message);

        /** 默认口径：MSH-3 发送应用 + {@code -} + MSH-7 前 8 位（yyyymmdd）。 */
        static BatchIdResolver sendingAppAndDay() {
            return (header, message) -> {
                String sender = header.sendingApplication().isBlank()
                        ? "UNKNOWN_SOURCE" : sanitize(header.sendingApplication());
                // 优先用解析后的 Hl7Message（结构完整）；结构未解出（MARC-8 等字符集不支持）时
                // 回退到 ASCII MSH 头里的 MSH-7（仍在 InboundHeader.timestamp()），避免结构拒收的
                // 报文被归到「按发送应用」的兜底批次，次日对账时说它找不到。
                String day = "";
                if (message != null) {
                    day = mshField(message, 7);
                }
                if (day.length() < 8 && header.timestamp() != null && header.timestamp().length() >= 8) {
                    day = header.timestamp();
                }
                if (day.length() >= 8) {
                    day = day.substring(0, 8);
                }
                return day.isBlank() ? sender : sender + "-" + sanitize(day);
            };
        }

        /** 去掉批次号里会污染文件名与 CSV 的字符，其余原样保留（批次号要能和平台对上）。 */
        private static String sanitize(String value) {
            return value.replaceAll("[\\s,;\"'\\\\/]+", "_");
        }

        private static String mshField(Hl7Message message, int idx) {
            try {
                return message.field("MSH", idx);
            } catch (RuntimeException e) {
                // MSH 段太短读不到该字段：批次号退化为「只按发送应用」，不猜
                return "";
            }
        }
    }

    /** 一条入站报文的处理结论。 */
    public enum IngestStatus {
        /** 首次收到，结构与必填字段全部通过，已入账并进待确认。 */
        ACCEPTED,
        /** 本端此前已收到过（对端重推 / 网络抖动重发 / 重启续跑），未重复入账、未重复跑业务。 */
        DUPLICATE,
        /** 首次收到但结构或必填字段不通过，已入账并推进到拒收。 */
        REJECTED,
        /** 连 MSH 都读不出：没有可归属的控制号，本条既不入账也不出应用级回执。 */
        UNREADABLE
    }

    /**
     * 入账结论。
     *
     * @param status   处理结论
     * @param ledgerId 台账行主键；{@link IngestStatus#UNREADABLE} 时为 {@code 0}
     * @param state    该行当前应用级状态；{@link IngestStatus#UNREADABLE} 时为 {@code null}
     * @param ackCode  本次回执的 MSA-1；{@link IngestStatus#UNREADABLE} 时为 {@code null}
     * @param ackBytes 应用级回执报文字节；{@link IngestStatus#UNREADABLE} 时为 {@code null}
     */
    public record IngestOutcome(IngestStatus status, long ledgerId, MessageState state,
                                String ackCode, byte[] ackBytes) {

        /** 是否是「本端第一次收到这条报文」。 */
        public boolean firstReceipt() {
            return status == IngestStatus.ACCEPTED || status == IngestStatus.REJECTED;
        }
    }

    /**
     * 落地结论。
     *
     * @param landed  <b>确认是否生效</b>（行已停在已落地）。注意它<b>不等于</b> {@link #wrotePatientIndex()}：
     *               撤回之后用<b>同一个</b>院内主键再确认时，确认生效但出站写被幂等抑制
     *               （HIS 里同一条报文本来就不该有两份一样的映射）。
     * @param state   该行处理完后的状态
     * @param wrotePatientIndex 本次是否真的向院内患者主索引发出了一次写入
     * @param note    人可读的一句话结论，进日志与 HTTP 响应（不含患者可识别信息）
     */
    public record LandOutcome(boolean landed, MessageState state,
                              boolean wrotePatientIndex, String note) {
    }

    private final ArchiveAckHandler ackHandler;
    private final IdempotencyGuard guard;
    private final LedgerStore store;
    private final PatientIndexWriter patientIndex;
    private final BatchIdResolver batchIds;

    /** 模型环节（可降级）；未装配时本端<b>不启用模型</b>，每条都按确定性基线走。 */
    private final ResilientModelGateway modelAssist;

    /**
     * 置信度观察点记录口（T28）。未装配时用丢弃口：主路径照常收报文，
     * 只是没有可供 {@code model confidence-buckets} 统计的观察点。
     *
     * <p>它是<b>旁路</b>而不是主路径的一环：记录失败只打告警，绝不改变本条的回执码与台账状态
     * （见 {@link ConfidenceSampleSink} 的契约）。理由与审计写同一条——
     * 「统计写不进去」是运维问题，把它变成「报文进不来」是业务事故。
     */
    private final ConfidenceSampleSink confidenceSamples;

    /** 用默认批次口径装配。 */
    public ArchiveIngestService(AckComposer composer, LedgerStore store,
                                IdempotencyGuard guard, PatientIndexWriter patientIndex) {
        this(composer, store, guard, patientIndex, BatchIdResolver.sendingAppAndDay());
    }

    public ArchiveIngestService(AckComposer composer, LedgerStore store, IdempotencyGuard guard,
                                PatientIndexWriter patientIndex, BatchIdResolver batchIds) {
        this(composer, store, guard, patientIndex, batchIds, null);
    }

    /** 完整装配：含模型环节。{@code modelAssist} 传 {@code null} 表示不启用模型。 */
    public ArchiveIngestService(AckComposer composer, LedgerStore store, IdempotencyGuard guard,
                                PatientIndexWriter patientIndex, BatchIdResolver batchIds,
                                ResilientModelGateway modelAssist) {
        this(composer, store, guard, patientIndex, batchIds, modelAssist, null);
    }

    /**
     * 完整装配：含模型环节与置信度观察点记录口（T28）。
     *
     * @param modelAssist        模型环节；{@code null} 表示不启用模型
     * @param confidenceSamples  观察点记录口；{@code null} 表示不启用统计
     */
    public ArchiveIngestService(AckComposer composer, LedgerStore store, IdempotencyGuard guard,
                                PatientIndexWriter patientIndex, BatchIdResolver batchIds,
                                ResilientModelGateway modelAssist, ConfidenceSampleSink confidenceSamples) {
        this.ackHandler = new ArchiveAckHandler(Objects.requireNonNull(composer, "composer 不得为 null"),
                null, null);
        this.store = Objects.requireNonNull(store, "台账存储不得为 null");
        this.guard = Objects.requireNonNull(guard, "幂等守卫不得为 null");
        this.patientIndex = Objects.requireNonNull(patientIndex, "患者主索引端口不得为 null");
        this.batchIds = Objects.requireNonNull(batchIds, "批次号口径不得为 null");
        this.modelAssist = modelAssist;
        this.confidenceSamples = confidenceSamples == null
                ? ConfidenceSampleSink.discarding() : confidenceSamples;
    }

    /** 是否启用了模型环节；没启用时每条报文都不会为模型付一次调用成本。 */
    public boolean modelEnabled() {
        return modelAssist != null;
    }

    /** 供接入主路径共用同一套判定与记账口径。 */
    public ArchiveAckHandler ackHandler() {
        return ackHandler;
    }

    // =====================================================================
    // 接收：入账 + 推进状态 + 回执（本方法绝不碰患者主索引）
    // =====================================================================

    /**
     * 处理一帧入站报文（已剥离 MLLP 帧）。
     *
     * <p><b>幂等是本方法的核心</b>：同一个幂等键无论到达多少次，台账只增一行、
     * 业务只跑一次、状态只推进一次。其余到达只回一条按<b>当前态</b>的回执，
     * 不产生第二条入账——重复到达在本产品里是常态（对端重推、网络抖动后重发、重启续跑）。
     */
    @Override
    public byte[] handle(byte[] payload) throws Exception {
        IngestOutcome outcome = ingest(payload);
        return outcome.ackBytes();
    }

    /**
     * 完整入账结论（供主路径、契约测试与只读排障使用）。
     *
     * @throws SQLException 台账不可用。<b>此异常上抛而不是被吞成 AE</b>：台账写不进去时
     *                      若照常回 AA，区域平台会认为这条已经收下并停止重投，
     *                      而本端其实什么都没记下——次日对账时数字对不上且现场无从查起。
     * @throws IllegalStateTransitionException 本方法只会推两条边
     *                      （{@code RECEIVED → PENDING_CONFIRM} 与 {@code RECEIVED → REJECTED}），
     *                      两者都在合法边表内，因此本异常实际只在状态机被改坏时出现 —— 照样上抛。
     * @throws LedgerStateConflictException 行刚由本线程插入（{@code state=RECEIVED, version=0}），
     *                      来源态对不上只可能是有人在这条到达被处理的同时改了同一行。
     *                      本产品<b>不</b>把它当成重复到达吞掉：那样等于把一次真实的状态分歧
     *                      记成「正常重复」，次日对账数字对不上时无从查起。照样上抛。
     */
    public IngestOutcome ingest(byte[] payload)
            throws SQLException, IllegalStateTransitionException, LedgerStateConflictException {
        Objects.requireNonNull(payload, "入站字节不得为 null");
        ArchiveAckHandler.Triage triage = ackHandler.triage(payload);
        if (triage == null) {
            return new IngestOutcome(IngestStatus.UNREADABLE, 0L, null, null, null);
        }

        InboundHeader header = triage.header();
        IdempotencyKey key = IdempotencyKey.of(
                header.messageType(), header.controlId(), header.processingId());
        String batchId = batchIds.batchIdOf(header, triage.message());

        // 第 0 步：预读去重。重复到达**不跑模型**——T16 的「重放 100 次」里 99 次走这一支，
        // 若先调模型再判重，那 99 次每次都要付一次模型调用（模型全挂时还各等三次退避），
        // 一条重复到达的报文会比首次到达的贵十几倍。
        Optional<LedgerRow> seen = store.findByIdempotencyKey(key);
        if (seen.isPresent()) {
            return duplicateOutcome(seen.get().id(), seen.get().state(), triage, key);
        }

        // 第 1 步：模型环节（唯一可降级的一段）。结构已不通过的报文不浪费这次调用：
        // 连 MSH-9/PID-3 都没有的报文，让模型去判断「哪个字段对应哪个本地字段」没有意义。
        // Z 段只读一次：模型环节与观察点记账用同一份产物，两边看到的是同一批字段。
        List<ZSegment> zSegments = zSegmentsOf(triage);
        ModelOutcome<ZFieldMap> outcome = runModel(zSegments);
        DegradedResult degraded = outcome.degradedResult().orElse(null);
        recordConfidenceSamples(triage, zSegments, outcome, degraded != null);

        LedgerEntry entry = degraded == null
                ? LedgerEntry.inbound(key, batchId, rawPayloadOf(payload, triage))
                : LedgerEntry.inboundDegraded(key, batchId, rawPayloadOf(payload, triage));

        AcquireResult acquired = guard.tryAcquire(entry);
        if (!acquired.shouldRunBusiness()) {
            // 预读与插入之间的竞态（同一毫秒内两条相同报文并发到达）：唯一约束兜底。
            // 这里仍回当前态的回执，而不是把这次到达说成「已收下」。
            return duplicateOutcome(acquired.ledgerId(), acquired.state(), triage, key);
        }

        // 第 2 步：首次到达 —— 本次到达有权决定这条的状态。
        //
        // 降级不是第三种终局：它与「结构通过」同属待确认，区别只在 degraded 标记与回执码。
        // 绝不因为模型挂了就把这条推进已落地（数据会被写进 HIS）或拒收（对端别再发了）。
        String reason = triage.accepted()
                ? (degraded == null ? REASON_AWAITING_CONFIRM : degraded.ledgerReason())
                : REASON_PREFIX_REJECTED + triage.reason().suggestedCode();
        MessageState to = triage.accepted() ? MessageState.PENDING_CONFIRM : MessageState.REJECTED;
        store.transition(acquired.ledgerId(), MessageState.RECEIVED, to, SYSTEM_OPERATOR, reason);

        String ackCode;
        byte[] ack;
        if (degraded != null && triage.accepted()) {
            ackHandler.recordDegraded(triage, degraded);
            ack = ackHandler.ackDegraded(triage, degraded);
            ackCode = AckComposer.CODE_AE;
        } else {
            ackHandler.record(triage);
            ack = ackHandler.ackBytes(triage);
            ackCode = triage.accepted() ? AckComposer.CODE_AA : triage.reason().msa1();
        }
        MessageState state = to;
        LOG.log(Level.INFO, "首次入账: ledgerId={0} {1} batch={2} state={3} ack={4} degraded={5}",
                new Object[]{acquired.ledgerId(), key, batchId, state.dbValue(), ackCode, degraded != null});
        return new IngestOutcome(
                triage.accepted() ? IngestStatus.ACCEPTED : IngestStatus.REJECTED,
                acquired.ledgerId(), state, ackCode, ack);
    }

    /**
     * 重复到达的结论：不入账、不跑业务、不动状态，但仍要回一条回执。
     *
     * <p><b>不回回执的话对端只能一直等到第二天追问</b>，而本端连「这条已经收过了」都没说出口。
     * 回的是<b>当前态</b>而不是固定 AA：一条曾经降级待确认的报文重投时，回 AA 会让对端
     * 以为它已经处理完，而它在台账里还等着人点确认。
     */
    private IngestOutcome duplicateOutcome(long ledgerId, MessageState currentState,
                                           ArchiveAckHandler.Triage triage, IdempotencyKey key) {
        ackHandler.record(triage);
        byte[] ack = ackHandler.ackBytes(triage);
        LOG.log(Level.INFO, "幂等命中，不重复入账: ledgerId={0} {1} state={2}",
                new Object[]{ledgerId, key, currentState.dbValue()});
        return new IngestOutcome(IngestStatus.DUPLICATE, ledgerId, currentState,
                AckComposer.CODE_AA, ack);
    }

    /**
     * 本条报文的 Z 段；未启用模型、结构不通过或没有 Z 段时返回空列表。
     *
     * <p>把「这次到底要不要问模型」这一个判断收在一处：模型环节与观察点记账
     * 都以它的结果为准，两边不会出现「问了模型却没记账」或「记了账却没问模型」。
     */
    private List<ZSegment> zSegmentsOf(ArchiveAckHandler.Triage triage) {
        if (modelAssist == null || !triage.accepted() || triage.message() == null) {
            return List.of();
        }
        return ZSegmentReader.read(triage.message());
    }

    /**
     * 记本条报文的置信度观察点（T28 的统计口径在主路径上的唯一供给点）。
     *
     * <p><b>只在模型确实被问过时才有观察点</b>：没启用模型、结构不通过、没有 Z 段
     * 这三种情况一律不记。理由是工作量口径——记一条「未给置信度」不会让任何报文
     * 多一件要人做的活，却会让「预计需人工复核 N 条」凭空多出 N 条。
     *
     * <p>这里<b>不记身份候选序列</b>：身份匹配发生在确认门（T29 装配
     * {@code IdentityHardRules} 与身份匹配器），那边自带 {@code IdentityMatchReport}，
     * 应当用 {@code ConfidenceSample.fromIdentity} 直接从报告取观察点，
     * 而不是从这里猜。
     *
     * <p>记录失败<b>吞掉</b>并打一条 WARNING：统计写不进去不该让报文退回待确认。
     */
    private void recordConfidenceSamples(ArchiveAckHandler.Triage triage,
                                         List<ZSegment> zSegments,
                                         ModelOutcome<ZFieldMap> outcome,
                                         boolean degraded) {
        if (zSegments.isEmpty()) {
            return;
        }
        try {
            confidenceSamples.record(ConfidenceSample.fromZFieldMap(
                    Instant.now(), triage.header().controlId(), outcome.value(), degraded));
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "置信度观察点记录失败（不影响本条报文处理）: controlId={0}, {1}",
                    new Object[]{triage.header().controlId(), e.toString()});
        }
    }

    /**
     * 跑模型环节；未启用模型或结构已不通过时<b>不发起任何调用</b>。
     *
     * <p>返回的永远是 {@link ModelOutcome}——本产品里「模型挂了」不是异常路径，
     * 是一条与「模型答了」并列的正常结果，由调用方在同一个出口处理。
     */
    private ModelOutcome<ZFieldMap> runModel(List<ZSegment> zSegments) {
        if (modelAssist == null || zSegments.isEmpty()) {
            return ModelOutcome.succeeded(ZFieldMap.empty());
        }
        // 没有 Z 段的报文，网关直接短路、不发 HTTP；
        // 于是「本端启用了模型」并不等于「每条报文都要调一次模型」。
        return modelAssist.alignZFields(zSegments);
    }

    // =====================================================================
    // 落地：唯一的患者主索引写入点（调用它 = 有人确认了这条）
    // =====================================================================

    /**
     * 确认落地：把一条待确认报文写进院内患者主索引。
     *
     * <p><b>恰好一次</b>由两步构成，顺序不能换：
     * <ol>
     *   <li>先用乐观锁把 {@code PENDING_CONFIRM → LANDED} 认领掉。20 个线程同时点确认时，
     *       只有认领成功的那个会走到第 2 步，其余在<b>写之前</b>就被拒；</li>
     *   <li>认领成功后才调 {@link PatientIndexWriter#put}。</li>
     * </ol>
     *
     * <p>写失败时把行退回 {@code PENDING_CONFIRM}（{@code LANDED → PENDING_CONFIRM} 是状态机允许的边），
     * 报文原文与幂等键原样保留，等人重试。<b>绝不</b>把写失败的条留在已落地：
     * 那会让次日对账时「回执说落地了、HIS 里查无此人」，而现场查不出是哪一步错的。
     *
     * @param messageType/controlId/processingId 幂等键三段
     * @param operator                            确认人（来自 token，不得为空）
     * @param patientRef                          区域平台主键（PID-3 第一组件）
     * @param localMrn                            院内主键（由身份候选 + 人工确认得出）
     * @throws IllegalArgumentException 操作人为空 / 两个主键为空 —— 属入参校验，不重试
     * @throws PatientIndexWriteException       对端拒绝或不可达；本行已退回待确认，可重放
     */
    public LandOutcome land(String messageType, String controlId, String processingId,
                            String operator, String patientRef, String localMrn)
            throws SQLException, PatientIndexWriteException {

        requireText(operator, "operator");
        requireText(patientRef, "patientRef");
        requireText(localMrn, "localMrn");

        IdempotencyKey key = IdempotencyKey.of(messageType, controlId, processingId);
        LedgerRow row = store.findByIdempotencyKey(key).orElseThrow(() ->
                new IllegalArgumentException("台账里没有这条报文，无法落地：" + key));

        if (row.state() != MessageState.PENDING_CONFIRM) {
            // 重复确认 / 拒收条目被确认：直接给出当前态，**不写**患者主索引。
            return new LandOutcome(false, row.state(), false,
                    "当前态为 " + row.state().dbValue() + "，不重复写入患者主索引");
        }

        // 这次确认要写的院内主键，与这一行上一次落地时写的**完全一样**吗？
        // （首次落地时 local_mrn 为空串，因此首条恒为 false，照常写一次。）
        boolean alreadyLandedWithSameMrn = !row.localMrn().isEmpty() && row.localMrn().equals(localMrn);

        // 第 1 步：认领。带版本守卫的 UPDATE，谁先到谁赢，其余在这里被拒。
        // 认领的同时把院内主键记进 local_mrn（与状态同一条 UPDATE）—— 撤回之后再确认时
        // 要靠这一列判断「这次是不是又要把同一个映射写进 HIS」。
        try {
            store.claimLanding(row.id(), row.version(), operator, REASON_CONFIRMED_LANDED, localMrn);
        } catch (ConcurrentLedgerUpdateException | LedgerStateConflictException e) {
            MessageState actual = e instanceof ConcurrentLedgerUpdateException cu
                    ? cu.actualState() : ((LedgerStateConflictException) e).actualState();
            return new LandOutcome(false, actual, false,
                    "并发争用：另一条确认已先落地或状态已变（当前态 " + dbValue(actual) + "），本次不写入");
        } catch (IllegalStateTransitionException e) {
            return new LandOutcome(false, row.state(), false, "非法迁移被状态机拒绝：" + e.getMessage());
        }

        // 第 2 0 步：同目标重确认 —— 撤回之后又挑了同一个身份映射再点确认。
        //
        // 这一步**不写** HIS，但认领照常做（状态回到已落地、审计 +1）：信息员点的是「确认」，
        // 让他点完还停在待确认、下次打开又看到一条待办，是比多写一次更难解释的结果。
        // 而多写一次恰恰是本产品要防的 —— 同一条报文、同一个院内主键，在 HIS 里只该有一条映射。
        //
        // 注意这与「换了个院内主键再确认」是两回事：那种是撤回真正要解决的场景
        // （上一次合错了人），必须真写一条新映射，否则 HIS 会永远留着那个错的。
        if (alreadyLandedWithSameMrn) {
            LOG.log(Level.INFO, "同目标重确认，不重复写患者主索引: ledgerId={0} batch={1} operator={2}",
                    new Object[]{row.id(), row.batchId(), operator});
            return new LandOutcome(true, MessageState.LANDED, false,
                    "确认已生效：这条此前已按同一院内主键写入 HIS，本次未重复写出站写");
        }

        // 第 2 步：认领成功，写患者主索引。写失败 → 退回待确认，保留原文与幂等键。
        try {
            patientIndex.put(patientRef, localMrn, row.batchId());
        } catch (PatientIndexWriteException e) {
            rollbackToPendingConfirm(row, operator);
            throw e;
        }
        LOG.log(Level.INFO, "确认落地并写入患者主索引: ledgerId={0} batch={1} operator={2}",
                new Object[]{row.id(), row.batchId(), operator});
        return new LandOutcome(true, MessageState.LANDED, true, "已写院内患者主索引并标记已落地");
    }

    /**
     * 写失败后的回退：{@code LANDED → PENDING_CONFIRM}。
     *
     * <p>失败不静默：这一条也进审计（操作人与原因都能查），并且行停在待确认等人重试。
     * 回退本身失败只记 ERROR —— 此时行停在已落地但没写成功，属于必须人工处置的状态，
     * 静默吞掉只会让人以为一切正常。
     */
    private void rollbackToPendingConfirm(LedgerRow claimed, String operator) {
        try {
            store.findById(claimed.id()).ifPresent(current -> {
                if (current.state() != MessageState.LANDED) {
                    return;
                }
                try {
                    // releaseLanding 而非普通 transition：写没成功，local_mrn 必须一并清掉，
                    // 否则下一次确认会被「同目标不重复写」的判断误伤，条目永远落不了地。
                    store.releaseLanding(current.id(), current.version(), operator,
                            REASON_HIS_WRITE_FAILED);
                } catch (SQLException | IllegalStateTransitionException
                         | LedgerStateConflictException | ConcurrentLedgerUpdateException e) {
                    LOG.log(Level.SEVERE, "写患者主索引失败后回退到待确认也失败了，该行停在已落地但未写入，"
                                    + "必须人工处置: ledgerId={0} 原因={1}",
                            new Object[]{current.id(), e.getMessage()});
                }
            });
        } catch (SQLException e) {
            LOG.log(Level.SEVERE, "写患者主索引失败后回读台账行失败: ledgerId={0} 原因={1}",
                    new Object[]{claimed.id(), e.getMessage()});
        }
    }

    // =====================================================================
    // 内部
    // =====================================================================

    /**
     * 入库的报文原文。
     *
     * <p>按入站声明的字符集解码，保留重投（T32 重发）与对账（T34）所需的原文；
     * 声明的字符集本端解不出时（那种报文已被判 AR）退回 ISO-8859-1 ——
     * 它按字节一对一映射，<b>不会像默认字符集那样把字节改写成别的字符</b>，
     * 存下来的仍是「对端发过来的那些字节」，排障时能拿它和抓包逐字比对。
     */
    private static String rawPayloadOf(byte[] payload, ArchiveAckHandler.Triage triage) {
        try {
            return new String(payload, CharsetResolver.resolve(triage.header().characterSet(), null).charset);
        } catch (UnsupportedCharset e) {
            return new String(payload, StandardCharsets.ISO_8859_1);
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不得为空：落地写入必须能追溯到「谁确认了哪条」");
        }
    }

    private static String dbValue(MessageState state) {
        return state == null ? "(行已不存在)" : state.dbValue();
    }
}