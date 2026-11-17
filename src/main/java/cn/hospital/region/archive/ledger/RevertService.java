package cn.hospital.region.archive.ledger;

import java.sql.SQLException;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 撤回重审 —— 信息员发现某条合错了时的<b>唯一</b>入口。
 *
 * <h2>要解决的现场问题</h2>
 * <p>社区卫生服务中心的信息员点完「确认」之后，<b>还能不能改</b>？改错了只有三条路，
 * 前两条都是错的：
 * <ol>
 *   <li>直接连库 {@code UPDATE message_ledger SET state='PENDING_CONFIRM'} —— 状态回去了，
 *       但审计链断了一节（{@link LedgerAuditChain} 的第 5 条不变量当场报警），
 *       且次日对账时「回执说 AA、HIS 里查无此人」，现场无从查起；</li>
 *   <li>删掉台账行重新收一遍 —— 幂等键、批次归属、报文原文一起没了，
 *       这一批的「收到几条」就永远对不上。</li>
 * </ol>
 * <p>本类给的是第三条路：<b>撤回是一次正常的业务状态迁移</b>，走与确认完全相同的
 * {@link LedgerStore#transition} 通道、写同样的一条 {@code message_audit}、用同样的乐观锁守卫。
 * 没有任何后门 —— 类的字段里只有 {@link LedgerStore} 一个，想直接改库都无从下手。
 *
 * <h2>放行哪些来源态（{@link #REVERTABLE_FROM}）</h2>
 * <pre>
 *   LANDED          → PENDING_CONFIRM   撤回：合错了，重新挑身份候选再走一次确认门
 *   PENDING_CONFIRM → PENDING_CONFIRM   补充依据：状态不前进，只追加一条审计（唯一自环）
 * </pre>
 * 其余六态一律拒绝，理由逐条写在 {@link #explainNotRevertable(MessageState)} 里 ——
 * <b>尤其是 {@code REJECTED}</b>：虽然 {@code REJECTED → PENDING_CONFIRM} 在状态机里是合法边，
 * 但拒收已经以 MSA-1=AE/AR 回给区域平台了，事后自己翻案会让两端对同一条报文各执一词。
 * 那属于「向对端重投一条新报文」，是 T32 重投队列的活，不是撤回。
 *
 * <h2>不变量</h2>
 * <ul>
 *   <li><b>不清空报文原文与幂等键</b>：撤回只走 {@code transition}，
 *       而 {@code transition} 的 UPDATE 只碰 {@code state} / {@code version} / {@code updated_at}
 *       三列（见 {@link JdbcLedgerStore}）。原文与幂等键原样留在行里，
 *       所以撤回之后重投（T32）、对账（T34）、再确认都还能用同一条报文。</li>
 *   <li><b>撤回本身不写患者主索引</b>：本类<b>没有</b> {@link cn.hospital.region.archive.identity.PatientIndexWriter}
 *       字段，唯一能落地写入的仍然是 {@link ArchiveIngestService#land}。
 *       「撤回一条已落地的」不会去 HIS 侧删记录 —— 那需要 HIS 提供反向接口，
 *       本轮不在范围内；本轮的边界是<b>本端台账与回执状态</b>，撤回后下一条落地写入仍走确认门。</li>
 *   <li><b>乐观锁</b>：带 {@code version} 守卫的迁移。两个信息员同时点撤回时只有一个生效，
 *       另一个拿到 {@link ConcurrentLedgerUpdateException} —— 不会出现两次撤回叠成四条审计。</li>
 * </ul>
 *
 * <h2>操作人从哪来</h2>
 * <p>{@code operator} 一律来自请求 token 的 subject（T30 的 {@code ConfirmGate} / T38 的
 * token 校验），本类<b>不</b>接受匿名：空操作人的撤回在方法入口就被拒，
 * 否则审计行里会出现 {@code operator = ''} 的撤回记录，事后查不到是谁撤的。
 */
public final class RevertService {

    private static final Logger LOG = Logger.getLogger(RevertService.class.getName());

    /**
     * 允许撤回的来源态 —— 本类的业务闸，比状态机<b>窄</b>。
     *
     * <p>{@code REJECTED} 被刻意排除：见类注释。排除靠的是这个集合，
     * 「再加一个态」必须显式改这里并补测试，而不是靠状态机表里恰好有这条边。
     */
    public static final Set<MessageState> REVERTABLE_FROM = EnumSet.of(
            MessageState.LANDED,
            MessageState.PENDING_CONFIRM);

    /** 审计行 {@code reason} 的固定前缀 —— 排障时一眼能筛出全部撤回动作。 */
    public static final String REASON_PREFIX = "revert:";

    /** 撤回后唯一的目标态：交回确认门，重新走一遍身份候选与人工确认。 */
    public static final MessageState REVERT_TARGET = MessageState.PENDING_CONFIRM;

    /**
     * 一次撤回的结论。
     *
     * @param reverted       本次是否真的执行了撤回
     * @param from           撤回前的状态；被业务闸拒绝时即请求时的实际状态
     * @param state          处理完后的状态
     * @param auditReason    写进审计行的完整 reason（含 {@link #REASON_PREFIX}）
     * @param note           人可读的一句话结论，进日志与 HTTP 响应
     */
    public record RevertOutcome(boolean reverted, MessageState from, MessageState state,
                                String auditReason, String note) {
    }

    private final LedgerStore store;

    public RevertService(LedgerStore store) {
        this.store = Objects.requireNonNull(store, "台账存储不得为 null");
    }

    /**
     * 撤回一条报文，交回待确认重走。
     *
     * <p>顺序固定为 <b>参数校验 → 业务闸 → 版本守卫迁移</b>，三道都在写之前：
     * 任一道没过，台账与审计<b>一个字节都不变</b>（失败不静默改数据）。
     *
     * @param ledgerId 台账行主键
     * @param operator 操作人（来自 token，不得为空白）—— 没有它审计行就查不到人
     * @param reason   撤回理由（不得为空白），会被前缀化成 {@code revert:<理由>} 写进审计行；
     *                 <b>调用方不要往里塞患者可识别信息</b>：审计行是全量留痕，
     *                 排障时是要被人直接读的
     * @throws IllegalArgumentException      operator 或 reason 为空白 —— 入参校验问题，不重试
     * @throws RevertNotAllowedException     行不存在，或当前态不在可撤回集合内
     * @throws IllegalStateTransitionException 状态机表被改坏（正常路径不会发生，照样上抛）
     * @throws ConcurrentLedgerUpdateException  两次撤回撞在同一版本上（状态对得上、版本被别人推走了）
     * @throws LedgerStateConflictException   行已被别人推进到别的状态，调用方拿着的是过期来源态
     *
     * <p><b>后两个并发异常一律上抛，不得就地吞掉</b>（与 {@link LedgerRetryPolicy} 同一纪律）：
     * 两个信息员同时点撤回时只有一个人会生效，另一个人必须看到「这条刚被别人改过」，
     * 而不是拿到一个看起来像成功的结论。T38 的 HTTP 层把两者都映射成 <b>409</b>。
     * 需要「谁赢谁输都行」的批量场景，用 {@link LedgerRetryPolicy} 重读后重试，不要在这里 catch。
     */
    public RevertOutcome revert(long ledgerId, String operator, String reason)
            throws SQLException, RevertNotAllowedException, IllegalStateTransitionException,
            ConcurrentLedgerUpdateException, LedgerStateConflictException {

        requireText(operator, "operator");
        requireText(reason, "reason");

        // 业务闸：先看能不能撤，再看边合不合法。反过来的话，REJECTED 会被报成
        // 「非法状态迁移」，而那条边在状态机里其实是合法的，排障会被带偏。
        LedgerRow row = store.findById(ledgerId).orElseThrow(() ->
                new RevertNotAllowedException(ledgerId, null, "台账里没有这一行，可能已被清理"));
        if (!REVERTABLE_FROM.contains(row.state())) {
            throw new RevertNotAllowedException(ledgerId, row.state(), explainNotRevertable(row.state()));
        }

        String auditReason = REASON_PREFIX + reason.trim();

        // 版本守卫迁移：与确认门走同一个 store.transition，审计链、乐观锁、回滚语义完全一致。
        // from == to 的自环只在 PENDING_CONFIRM 上存在（补充依据），state 不变但
        // version 自增、审计行 +1，LedgerAuditChain 的五条不变量仍然成立。
        TransitionResult result = store.transition(row.id(), row.state(), REVERT_TARGET,
                row.version(), operator, auditReason);

        LOG.log(Level.INFO, "撤回重审: ledgerId={0} batch={1} {2} → {3} operator={4} reason={5}",
                new Object[]{row.id(), row.batchId(), row.state().dbValue(),
                        REVERT_TARGET.dbValue(), operator, auditReason});

        return new RevertOutcome(true, row.state(), result.to(), auditReason,
                row.state() == REVERT_TARGET
                        ? "已补充依据，仍在待确认（业务状态未前进）"
                        : "已撤回至待确认，可重新确认");
    }

    /**
     * 为什么这个来源态不能撤 —— 拒绝时给排障的人一句能直接照着办的话。
     *
     * <p>逐条写理由而不是统一一句「当前状态不允许撤回」：拒绝理由本身是最快的排障入口。
     */
    public static String explainNotRevertable(MessageState state) {
        if (state == null) {
            return "台账行不存在";
        }
        return switch (state) {
            case RECEIVED -> "刚入账、还没确认过，没有可撤回的动作；这条的走法是等它进待确认或拒收";
            case REJECTED -> "拒收结论已经以 MSA-1=AE/AR 回给区域平台了，事后翻案会让两端各执一词；"
                    + "要让对端重看这条，走重投队列发一条新报文，不是撤回";
            case PENDING_RETRY -> "这条在重投队列里等着发，等调度结果出来再决定；"
                    + "撤回与重投同时动同一条会让审计链断在中间";
            case RETRYING -> "正在重投途中，此刻改状态会让对端收到的报文与本地台账对不上";
            case RETRY_CONFIRMED -> "重投已被对端确认，下一步是推进到已落地；要改身份映射请先等落地再撤回";
            case RETRY_PERMANENT_FAILED -> "重投已达上限，只有人工放行才能回到重投队列；"
                    + "撤回不是放行，放行走 PENDING_RETRY";
            case LANDED, PENDING_CONFIRM -> "可撤回，不该走到这里";
        };
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    field + " 不得为空白：撤回必须能追溯到「谁、因为什么撤的」，否则审计行等于没写");
        }
    }
}
