package cn.hospital.region.archive.ledger;

/**
 * 非法的报文状态迁移 —— 调用方发起的 from → to 不在 {@link MessageStateMachine}
 * 显式列出的合法边里。
 *
 * <p>本异常的契约：
 * <ol>
 *   <li><b>消息必须含被拒的边</b>（"RECEIVED → LANDED"），便于审计与排障日志直接读</li>
 *   <li><b>不携带堆栈深处的源代码位置</b>（避免把上游调用栈写进台账）</li>
 *   <li><b>是一个 checked exception</b>：业务层必须在调用 {@code MessageStateMachine.assertTransition}
 *       时显式处理，或向上抛给调度器由其决定是否降级</li>
 * </ol>
 *
 * <p>为什么不继承 {@link RuntimeException}：本产品把「非法状态迁移」视为<b>业务规则违反</b>，
 * 不是程序 bug。运行时异常会让业务层不写 try/catch，从而在并发场景下静默走错分支。
 * 这里改成 checked，让所有调用点都看到这条边必须先经过断言的事实。
 */
public class IllegalStateTransitionException extends Exception {

    private final MessageState from;
    private final MessageState to;

    public IllegalStateTransitionException(MessageState from, MessageState to) {
        super("非法状态迁移：" + format(from, to)
                + "（合法边见 MessageStateMachine.LEGAL_TRANSITIONS）");
        this.from = from;
        this.to = to;
    }

    public IllegalStateTransitionException(MessageState from, MessageState to, String reason) {
        super("非法状态迁移：" + format(from, to) + " —— " + reason
                + "（合法边见 MessageStateMachine.LEGAL_TRANSITIONS）");
        this.from = from;
        this.to = to;
    }

    public MessageState from() {
        return from;
    }

    public MessageState to() {
        return to;
    }

    private static String format(MessageState from, MessageState to) {
        return from == null ? "<null>" : from.dbValue()
                + " → "
                + (to == null ? "<null>" : to.dbValue());
    }
}
