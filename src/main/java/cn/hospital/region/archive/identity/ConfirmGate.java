package cn.hospital.region.archive.identity;

import cn.hospital.region.archive.ledger.ArchiveIngestService;
import cn.hospital.region.archive.ledger.IdempotencyKey;
import cn.hospital.region.archive.ledger.LedgerRow;
import cn.hospital.region.archive.ledger.LedgerStore;
import cn.hospital.region.archive.ledger.MessageState;
import cn.hospital.region.archive.ledger.RevertService;

import java.sql.SQLException;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 确认门 —— <b>「待确认 → 已落地」在本产品里唯一的通道</b>。
 *
 * <h2>它守的那条红线</h2>
 * <p>模型给候选、硬校验给三态、重投调度给差集，这些都<b>不</b>能替人把一条档案写进 HIS。
 * 因此本产品的结构是：接入主路径收报文、把条目停在待确认，然后<b>什么都不做</b>；
 * 只有信息员在确认台上点下「确认」，{@link #confirm} 才会被调用，才会出现一次
 * {@link PatientIndexWriter#put}。「没有任何确认动作时，出站写调用次数恰好为 0」
 * 不是文档承诺，是 {@code ConfirmGateTest.test_no_confirm_means_zero_writes} 的断言。
 *
 * <h2>本类<b>没有</b> PatientIndexWriter 字段</h2>
 * <p>这是刻意的：确认通过之后要写院内主索引，但<b>本类自己不碰那个端口</b>，
 * 它把决策交给 {@link ArchiveIngestService#land}——那里才有乐观锁认领与失败回退。
 * 于是「确认门里偷偷发了个 HTTP」这件事在本项目里<b>写不出来</b>：
 * 没有字段就没有调用点，测试再用反射断言一次「本类无 {@code PatientIndexWriter} 字段」，
 * 这条职责分离就同时被编译期与运行期锁住了。
 *
 * <h2>四道闸，顺序固定，全在写之前</h2>
 * <pre>
 *   1. 操作人非空          —— 来自 T38 的 token 校验；空 = 匿名确认，审计行等于没写
 *   2. 行存在              —— 台账里没有这一行 = 点了个不存在的条目
 *   3. 当前态 = 待确认      —— 已落地重复确认 / 拒收条目被确认 / 重投中的条目，一律拒
 *   4. 迁移合法            —— 由 ArchiveIngestService.land 内的状态机断言把关
 * </pre>
 * 任一道没过：<b>台账、审计、出站写三样一个字节都不动</b>，
 * 只在 {@code confirm_refusal}（V4）里留一行「谁、几点、哪条、当时什么态、为什么被拒」。
 *
 * <h2>撤回不另开后门</h2>
 * <p>{@link #revert} 直接转调 T17 的 {@link RevertService}，本类不实现任何状态迁移：
 * 「确认」与「撤回」必须走同一个 {@code store.transition} 通道、写同一种审计行、
 * 受同一把乐观锁守卫 —— 只要有一处自己写 SQL，两条路径迟早会在审计链上分叉。
 */
public final class ConfirmGate {

    private static final Logger LOG = Logger.getLogger(ConfirmGate.class.getName());

    /**
     * 确认被拒的原因码。
     *
     * <p>逐条枚举而不是一个布尔：拒绝理由本身是最快的排障入口 ——
     * 「界面把已落地的条目又摆上确认台」与「两个信息员同时点同一条」，
     * 要找的人和要改的东西完全不一样。
     */
    public enum Refusal {

        /** 行不存在：点了个已经不在台账里的条目（清理过、或控制号传错）。 */
        LEDGER_ROW_MISSING("台账里没有这一条"),

        /** 已落地条目被重复确认：界面把收尾后的条目又摆到了确认台上。 */
        ALREADY_LANDED("这一条已经落地过了"),

        /** 拒收条目被确认：结论已以 AE/AR 回给区域平台，本端不能自己翻案。 */
        NOT_PENDING_CONFIRM("当前状态不允许确认");

        private final String zhLabel;

        Refusal(String zhLabel) {
            this.zhLabel = zhLabel;
        }

        public String zhLabel() {
            return zhLabel;
        }
    }

    private final LedgerStore store;
    private final ArchiveIngestService ingest;
    private final RevertService revertService;
    private final ConfirmRefusalJournal refusals;

    /** 双主键绑定口（可选）：装配时补齐「区域主键 + 院内主键」这一对绑定。 */
    private final PatientKeyStore keyStore;

    /** 完整装配（含双主键绑定与拒绝留痕）。 */
    public ConfirmGate(LedgerStore store, ArchiveIngestService ingest, RevertService revertService,
                       ConfirmRefusalJournal refusals, PatientKeyStore keyStore) {
        this.store = Objects.requireNonNull(store, "台账存储不得为 null");
        this.ingest = Objects.requireNonNull(ingest, "接入主路径不得为 null");
        this.revertService = Objects.requireNonNull(revertService, "撤回服务不得为 null");
        this.refusals = refusals == null ? ConfirmRefusalJournal.discarding() : refusals;
        this.keyStore = keyStore;
    }

    /** 常用装配：不做双主键补绑（接入侧已绑过 patient_ref 时够用）。 */
    public ConfirmGate(LedgerStore store, ArchiveIngestService ingest, RevertService revertService) {
        this(store, ingest, revertService, null, null);
    }

    /**
     * 确认一条待确认报文，把它写进院内患者主索引。
     *
     * <p>顺序：<b>四道闸 → 区域主键补绑 → 落地认领 + 出站写</b>。
     * 区域主键补绑放在落地之前，是为了「这条到底属于哪位居民」在确认那一刻就已落库；
     * 但院内主键<b>绝不</b>提前写 —— 它是「这次要不要真写 HIS」的判断依据（见
     * {@link ArchiveIngestService#land}），提前填会让首次确认被当成重确认而跳过出站写。
     *
     * @param ledgerId 台账行主键
     * @param decision 人的决定（院内主键 + 来源 + 备注），必填
     * @return 确认结论：生效或被拒，{@link ConfirmOutcome#confirmed()} 与
     *         {@link ConfirmOutcome#wrotePatientIndex()} 分开给
     * @throws IllegalArgumentException 决策为空或操作人为空 —— 入参校验，不重试
     * @throws PatientIndexWriteException 对端拒绝或不可达；本行已退回待确认，可重放
     * @throws KeyBindingConflictException 双主键已被绑成别的值（拒绝静默覆盖）
     */
    public ConfirmOutcome confirm(long ledgerId, ConfirmDecision decision)
            throws SQLException, PatientIndexWriteException, KeyBindingConflictException {

        Objects.requireNonNull(decision, "确认决策不得为 null");
        String operator = decision.operator();

        // 闸 1：操作人。放在最前面，因为后面每一条拒绝留痕都要靠它查人。
        if (ledgerId <= 0) {
            return refuse(ledgerId, null, operator, Refusal.LEDGER_ROW_MISSING,
                    "台账行主键必须为正数，实际 " + ledgerId);
        }

        // 闸 2：行存在。缺行时状态报 null 而不是拿某个态顶替 —— 见 ConfirmOutcome.state 的说明。
        LedgerRow row = store.findById(ledgerId).orElse(null);
        if (row == null) {
            return refuse(ledgerId, null, operator, Refusal.LEDGER_ROW_MISSING,
                    "台账里没有 id=" + ledgerId + " 这一行（可能已被清理，或控制号传错）");
        }

        // 闸 3：当前态。
        if (row.state() != MessageState.PENDING_CONFIRM) {
            Refusal why = row.state() == MessageState.LANDED
                    ? Refusal.ALREADY_LANDED
                    : Refusal.NOT_PENDING_CONFIRM;
            // detail 只带定位信息：**不带** zhLabel —— refuse() 会用 zhLabel 统一给 note 加前缀，
            // 这里再带一遍就成了「这一条已经落地过了：这一条已经落地过了：…」，而这句 note
            // 会原样出现在院内系统的界面上（HTTP 层直接拿它当 message）。
            return refuse(ledgerId, row.state(), operator, why,
                    "ledgerId=" + ledgerId + " state=" + row.state().dbValue()
                            + " ctrl=" + redactControlId(row) + " batch=" + row.batchId());
        }

        // 区域主键补绑：把入站报文带来的 patient_ref 落到位（V3 加列后存量行为空串）。
        //
        // **只绑区域主键，绝不在这里写 local_mrn** —— local_mrn 正是 land 判断
        // 「这次要不要真写 HIS」的唯一依据（见 ArchiveIngestService#land 的 alreadyLandedWithSameMrn）：
        // 提前把它填上，首次确认就会被当成「同目标重确认」而跳过那一次出站写，
        // 界面显示已落地、HIS 里却没有档案。对不上就抛，因为 patient_ref 只能来自 PID-3，
        // 人改不了也生成不出来，对不上说明拿错了条目。
        if (keyStore != null) {
            IdentityKeyBinding existing = keyStore.read(ledgerId).orElse(null);
            if (existing == null) {
                keyStore.bind(ledgerId, IdentityKeyBinding.inboundOnly(decision.patientRef()));
            } else if (!existing.patientRef().equals(decision.patientRef())) {
                throw new KeyBindingConflictException(ledgerId,
                        "区域主键已绑定为 " + existing.patientRef()
                                + "，与本次传入的不一致，拒绝覆盖");
            }
        }

        // 闸 4 + 出站写：交给接入主路径，状态机断言、乐观锁认领、写失败回退都在那里。
        IdempotencyKey key = row.key();
        ArchiveIngestService.LandOutcome landed = ingest.land(key.messageType(), key.controlId(),
                key.processingId(), operator, decision.patientRef(), decision.localMrn());

        if (!landed.landed()) {
            // 走到这里说明闸 3 之后状态被别人推走了（两个信息员同时点同一条）。
            // 结论仍然是拒绝：一个字节都不改，同时留痕，让排障的人看得到争用发生过。
            return refuse(ledgerId, landed.state(), operator, Refusal.NOT_PENDING_CONFIRM,
                    "并发争用：另一条确认已先落地或状态已变（当前态 "
                            + landed.state().dbValue() + "），本次未写入患者主索引");
        }

        LOG.log(Level.INFO, "确认门通过：ledgerId={0} batch={1} state={2} 写院内主索引={3} operator={4} 来源={5}",
                new Object[]{row.id(), row.batchId(), landed.state().dbValue(),
                        landed.wrotePatientIndex(), operator, decision.candidateSource()});

        return ConfirmOutcome.landed(landed.state(), landed.wrotePatientIndex(),
                decision.patientRef(), decision.localMrn(),
                landed.wrotePatientIndex()
                        ? "已写院内患者主索引并标记已落地"
                        : landed.note());
    }

    /**
     * 撤回一条已落地的报文，交回待确认重走。
     *
     * <p>本方法<b>不实现</b>任何状态迁移，只是转调 {@link RevertService}：
     * 「确认」与「撤回」共用同一个 {@code store.transition} 通道，
     * 只要有一处自己写 SQL，两条路径迟早在审计链上分叉。
     *
     * @throws cn.hospital.region.archive.ledger.RevertNotAllowedException 当前态不可撤回
     */
    public RevertService.RevertOutcome revert(long ledgerId, String operator, String reason)
            throws SQLException, cn.hospital.region.archive.ledger.RevertNotAllowedException,
            cn.hospital.region.archive.ledger.IllegalStateTransitionException,
            cn.hospital.region.archive.ledger.ConcurrentLedgerUpdateException,
            cn.hospital.region.archive.ledger.LedgerStateConflictException {
        return revertService.revert(ledgerId, operator, reason);
    }

    /** 装配的撤回服务（T38 的 HTTP 层也走它，两处必须是同一个实例）。 */
    public RevertService revertService() {
        return revertService;
    }

    // =====================================================================
    // 内部
    // =====================================================================

    /** 拒绝：留痕 + 返回结论。台账、审计、出站写三者一个字节都不动。 */
    private ConfirmOutcome refuse(long ledgerId, MessageState actualState, String operator,
                                  Refusal reason, String detail) {
        refusals.record(new ConfirmRefusalJournal.Refusal(ledgerId, actualState, operator, reason, detail));
        LOG.log(Level.WARNING, "确认被拒：ledgerId={0} state={1} reason={2} operator={3} —— {4}",
                new Object[]{ledgerId, actualState == null ? ConfirmRefusalJournal.NO_ROW_STATE
                                : actualState.dbValue(), reason.name(), operator, detail});
        return ConfirmOutcome.refused(actualState, reason, reason.zhLabel() + "：" + detail);
    }

    /** 控制号只进留痕不进日志全文时的脱敏出口（控制号非患者标识，但空值显式写「(空)」）。 */
    private static String redactControlId(LedgerRow row) {
        String ctrl = row.key().controlId();
        return ctrl == null || ctrl.isEmpty() ? "(空)" : ctrl;
    }
}