package cn.hospital.region.archive.ledger;

/**
 * 撤回请求被拒 —— 该台账行当前的状态<b>不在可撤回集合</b>里。
 *
 * <h2>与 {@link IllegalStateTransitionException} 的分工</h2>
 * <p>两者都是 checked 且都「拒绝了状态变化」，但回答的是<b>不同的问题</b>：
 * <ul>
 *   <li>{@link IllegalStateTransitionException}：这条 {@code from → to} 边不在状态机合法边表内
 *       —— 说的是<b>边</b>非法；</li>
 *   <li>本异常：这条边的去向是「待确认」（在状态机里对不少来源态都合法），但业务上
 *       <b>这个来源态不该走撤回</b> —— 说的是<b>业务闸</b>没过。</li>
 * </ul>
 * <p>例：{@code REJECTED → PENDING_CONFIRM} 在状态机里是合法边（信息员发现拒收判错了要重审），
 * 但 {@link RevertService} 明确<b>不放行</b>它 —— 拒收是回给区域平台的 AE/AR 结论，
 * 撤回一条已经回过 AR 的报文会让对端与本端对同一条报文的说法自相矛盾。这种「边合法但业务不许」
 * 的情形如果笼统抛 {@code IllegalStateTransitionException}，排障的人会去查状态机表，
 * 查半天发现边是合法的，浪费一整个下午。
 *
 * <h2>为什么是 checked</h2>
 * <p>与 {@link IllegalStateTransitionException} 同一理由：撤回是业务动作，不是程序 bug，
 * 调用点必须显式处理「这条不能撤」，不得静默跳过。
 */
public class RevertNotAllowedException extends Exception {

    private final long ledgerId;
    private final MessageState actualState;

    public RevertNotAllowedException(long ledgerId, MessageState actualState, String reason) {
        super("台账行 " + ledgerId + " 当前为 " + (actualState == null ? "<行不存在>" : actualState.dbValue())
                + "，不允许撤回 —— " + reason);
        this.ledgerId = ledgerId;
        this.actualState = actualState;
    }

    /** 被请求撤回的台账行主键。 */
    public long ledgerId() {
        return ledgerId;
    }

    /** 请求发生时的实际状态；{@code null} 表示台账行不存在。 */
    public MessageState actualState() {
        return actualState;
    }
}
