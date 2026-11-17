package cn.hospital.region.archive.ledger;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 报文级四态状态机 —— 定义每条报文合法的状态迁移边，并对非法边抛
 * {@link IllegalStateTransitionException}。
 *
 * <h2>设计依据</h2>
 * <p>参考实现 {@code github_ref/dcm4che/dcm4che-net-hl7/.../HL7ConnectionMonitor.java:56-57} 把
 * 「成功响应」与「失败异常」放在同一个回调里：
 * <pre>{@code
 * void onMessageProcessed(Connection conn, Socket s, UnparsedHL7Message msg,
 *                         UnparsedHL7Message rsp, HL7Exception ex);
 * }</pre>
 * 这种「连接级双出口」设计只关心「这一条 socket 上的最后一个报文处理完了」，不关心
 * 报文的业务生命周期。本产品把这条主路径<b>改写为报文级显式状态机</b>：每条报文都必须落到
 * {@link MessageState} 的一个终态，不允许静默丢弃；调用方发起任何状态变更前必须先调
 * {@link #assertTransition(MessageState, MessageState)}。
 *
 * <h2>合法边（{@link #LEGAL_TRANSITIONS}）</h2>
 * <pre>
 *   RECEIVED          → PENDING_CONFIRM | LANDED | REJECTED | PENDING_RETRY
 *   PENDING_CONFIRM   → LANDED          | PENDING_RETRY | PENDING_CONFIRM（自环：补充依据）
 *   LANDED            → PENDING_CONFIRM   （T17 RevertService 撤回重审）
 *   REJECTED          → PENDING_CONFIRM   （T17 RevertService 撤回重审）
 *   PENDING_RETRY     → RETRYING
 *   RETRYING          → RETRY_CONFIRMED | RETRY_PERMANENT_FAILED | PENDING_RETRY
 *   RETRY_CONFIRMED   → LANDED
 *   RETRY_PERMANENT_FAILED → PENDING_RETRY   （人工放行）
 * </pre>
 *
 * <h2>非法边（明确禁止，调用方应改为对应合法边）</h2>
 * <ul>
 *   <li>{@code LANDED → RECEIVED}：已落地不能再回到已接收</li>
 *   <li>{@code REJECTED → LANDED}：拒收后不能直接落地，必须先撤回重审</li>
 *   <li>{@code PENDING_CONFIRM → REJECTED}：确认门只产出 LANDED，拒收发生在 RECEIVED 阶段</li>
 *   <li>任何「跳级」的边（如 RECEIVED → LANDED 经过非法字符集检查时不走这条；非法边应改成
 *       先走 REJECTED）</li>
 * </ul>
 *
 * <h2>用法</h2>
 * <pre>{@code
 *   try {
 *       MessageStateMachine.assertTransition(currentState, nextState);
 *       // 通过后写入 message_ledger.state + 写一条 message_audit
 *   } catch (IllegalStateTransitionException e) {
 *       // 业务上违反状态机规则：记告警、不写库、不推进
 *   }
 * }</pre>
 */
public final class MessageStateMachine {

    /**
     * 显式合法边集合 —— 任何不在表里的 (from, to) 都视为非法。
     *
     * <p>设计原则：
     * <ol>
     *   <li>每条边都是<b>有理由</b>的；新增边必须显式加在此处并写注释</li>
     *   <li>边集在类加载期一次性构建，运行时只读，调用方无法反射修改</li>
     *   <li>集合大小本身就是不变量的一部分（{@link #size()} 暴露给测试断言）</li>
     * </ol>
     */
    public static final Map<MessageState, Set<MessageState>> LEGAL_TRANSITIONS;

    static {
        Map<MessageState, Set<MessageState>> m = new HashMap<>();

        // 入站后的四条分叉：模型已给候选且无歧义 → LANDED；模型不可达或低置信 → PENDING_CONFIRM；
        // 字符集 / 必填段 / 身份锚点冲突 → REJECTED；AE 回执或对端缺回执 → PENDING_RETRY
        m.put(MessageState.RECEIVED, EnumSet.of(
                MessageState.PENDING_CONFIRM,
                MessageState.LANDED,
                MessageState.REJECTED,
                MessageState.PENDING_RETRY));

        // 待确认只允许走 LANDED（人点确认）或 PENDING_RETRY（对端缺回执走重投）；
        // 不允许直接 REJECTED（拒收发生在入站阶段的硬校验，参见 ArchiveAckHandler.reasonFor 注释）
        //
        // PENDING_CONFIRM → PENDING_CONFIRM 是**唯一一条自环**，语义与上面两条都不同：
        // 它是 T17 RevertService 的「补充依据」—— 信息员在待确认里又补了一条依据
        // （打了回居民电话核过姓名、拿到街道的协助核对单），但**业务状态不前进**。
        // 之所以把它建成显式合法边而不是让 RevertService 直接写审计，是因为
        // LedgerAuditChain 的第 3 条不变量要求「每一行审计的 from → to 都在本表内」，
        // 走自环才能既留痕又让审计链自洽（state 不变、version 自增、审计行 +1，
        // 三者仍然对得上）。别的状态一律不给自环：自环会让「改了几次」这个计数失去意义。
        m.put(MessageState.PENDING_CONFIRM, EnumSet.of(
                MessageState.PENDING_CONFIRM,
                MessageState.LANDED,
                MessageState.PENDING_RETRY));

        // 撤回：信息员发现已落地的合错了，T17 RevertService 把它拉回 PENDING_CONFIRM 重走；
        // 不允许退回到 RECEIVED（语义上不会变回「刚收到未审查」）
        m.put(MessageState.LANDED, EnumSet.of(
                MessageState.PENDING_CONFIRM));

        // 拒收也可撤回重审（信息员发现判定错了，走 T17）；同 LANDED 不允许直跳 RECEIVED
        m.put(MessageState.REJECTED, EnumSet.of(
                MessageState.PENDING_CONFIRM));

        // 重投队列里等待调度
        m.put(MessageState.PENDING_RETRY, EnumSet.of(
                MessageState.RETRYING));

        // 调度器出队后执行：成功 → RETRY_CONFIRMED；单次失败但未超上限 → 回 PENDING_RETRY；
        // 达到 MAX_ATTEMPTS → RETRY_PERMANENT_FAILED
        m.put(MessageState.RETRYING, EnumSet.of(
                MessageState.RETRY_CONFIRMED,
                MessageState.RETRY_PERMANENT_FAILED,
                MessageState.PENDING_RETRY));

        // 重投成功；下一步推进到 LANDED
        m.put(MessageState.RETRY_CONFIRMED, EnumSet.of(
                MessageState.LANDED));

        // 永久失败：仅人工放行能回到 PENDING_RETRY；不允许直接 LANDED 或 RECEIVED
        m.put(MessageState.RETRY_PERMANENT_FAILED, EnumSet.of(
                MessageState.PENDING_RETRY));

        // 包装成不可变 Map，避免被业务层修改
        Map<MessageState, Set<MessageState>> immutable = new HashMap<>();
        for (Map.Entry<MessageState, Set<MessageState>> e : m.entrySet()) {
            immutable.put(e.getKey(), Collections.unmodifiableSet(e.getValue()));
        }
        LEGAL_TRANSITIONS = Collections.unmodifiableMap(immutable);
    }

    private MessageStateMachine() {
        // 工具类，禁止实例化
    }

    /**
     * 校验 from → to 是否为合法迁移。
     *
     * <p>不合法时抛 {@link IllegalStateTransitionException}，消息含被拒的边。
     * 这是 {@link ledger.JdbcLedgerStore#transition} 进入事务前唯一允许的入口；
     * 任何业务代码绕过该断言直接 UPDATE message_ledger.state 都会被 T13 的
     * {@code test_cannot_bypass_state_machine} 断言捕获。
     *
     * @throws IllegalStateTransitionException from 或 to 为 null，或该边不在合法边集内
     */
    public static void assertTransition(MessageState from, MessageState to)
            throws IllegalStateTransitionException {
        if (from == null) {
            throw new IllegalStateTransitionException(null, to, "from-state 不得为 null");
        }
        if (to == null) {
            throw new IllegalStateTransitionException(from, null, "to-state 不得为 null");
        }
        Set<MessageState> allowed = LEGAL_TRANSITIONS.get(from);
        if (allowed == null) {
            throw new IllegalStateTransitionException(from, to,
                    "from-state 未在状态机中登记（程序错误）");
        }
        if (!allowed.contains(to)) {
            throw new IllegalStateTransitionException(from, to);
        }
    }

    /** 合法边的总数；用于自检「不变量不被偷偷改动」。 */
    public static int size() {
        int n = 0;
        for (Set<MessageState> s : LEGAL_TRANSITIONS.values()) {
            n += s.size();
        }
        return n;
    }

    /**
     * 判断 from → to 是否合法（不抛异常的版本）。供 T17 的 {@code RevertService} 在写审计前
     * 做断言测试，并供 T40 的 {@code obs/RedactedLog} 把非法边当告警记录。
     */
    public static boolean isLegal(MessageState from, MessageState to) {
        if (from == null || to == null) {
            return false;
        }
        Set<MessageState> allowed = LEGAL_TRANSITIONS.get(from);
        return allowed != null && allowed.contains(to);
    }
}
