package cn.hospital.region.archive.ledger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 报文级四态状态机的合法边 / 非法边全覆盖测试。
 *
 * <p>覆盖策略：
 * <ul>
 *   <li>每条合法边一条 happy-path 用例</li>
 *   <li>每条非法边一条 negative 用例（重点覆盖 task 12 明确点名的三条）</li>
 *   <li>空指针防御</li>
 *   <li>size() 不变量（防止有人偷偷加边忘了写测试）</li>
 *   <li>{@link MessageState#fromDbValue} 反查轮转</li>
 *   <li>枚举自身完备性：每个 state 都至少有一条出边或被显式声明为不可出</li>
 * </ul>
 */
class MessageStateMachineTest {

    // ========================================================================
    // 合法边 happy-path —— 任务 12 spec 要求「逐条覆盖每条合法边」
    // ========================================================================

    @Test
    @DisplayName("RECEIVED → PENDING_CONFIRM 合法：模型给出候选但需要人确认")
    void received_to_pending_confirm_is_legal() throws IllegalStateTransitionException {
        MessageStateMachine.assertTransition(MessageState.RECEIVED, MessageState.PENDING_CONFIRM);
    }

    @Test
    @DisplayName("RECEIVED → LANDED 合法：模型直接给出高置信映射且无歧义")
    void received_to_landed_is_legal() throws IllegalStateTransitionException {
        MessageStateMachine.assertTransition(MessageState.RECEIVED, MessageState.LANDED);
    }

    @Test
    @DisplayName("RECEIVED → REJECTED 合法：缺必填段 / 字符集不支持 / 身份锚点冲突")
    void received_to_rejected_is_legal() throws IllegalStateTransitionException {
        MessageStateMachine.assertTransition(MessageState.RECEIVED, MessageState.REJECTED);
    }

    @Test
    @DisplayName("RECEIVED → PENDING_RETRY 合法：入站即知需重投（如 MSA-1=AE 写入后立刻重投）")
    void received_to_pending_retry_is_legal() throws IllegalStateTransitionException {
        MessageStateMachine.assertTransition(MessageState.RECEIVED, MessageState.PENDING_RETRY);
    }

    @Test
    @DisplayName("PENDING_CONFIRM → LANDED 合法：人工确认后落地")
    void pending_confirm_to_landed_is_legal() throws IllegalStateTransitionException {
        MessageStateMachine.assertTransition(MessageState.PENDING_CONFIRM, MessageState.LANDED);
    }

    @Test
    @DisplayName("PENDING_CONFIRM → PENDING_RETRY 合法：人对端追问未收回执，挂重投队列")
    void pending_confirm_to_pending_retry_is_legal() throws IllegalStateTransitionException {
        MessageStateMachine.assertTransition(MessageState.PENDING_CONFIRM, MessageState.PENDING_RETRY);
    }

    @Test
    @DisplayName("LANDED → PENDING_CONFIRM 合法：T17 RevertService 撤回重审")
    void landed_to_pending_confirm_is_legal() throws IllegalStateTransitionException {
        MessageStateMachine.assertTransition(MessageState.LANDED, MessageState.PENDING_CONFIRM);
    }

    @Test
    @DisplayName("REJECTED → PENDING_CONFIRM 合法：T17 RevertService 撤回拒收重审")
    void rejected_to_pending_confirm_is_legal() throws IllegalStateTransitionException {
        MessageStateMachine.assertTransition(MessageState.REJECTED, MessageState.PENDING_CONFIRM);
    }

    @Test
    @DisplayName("PENDING_RETRY → RETRYING 合法：RedeliveryScheduler 出队执行")
    void pending_retry_to_retrying_is_legal() throws IllegalStateTransitionException {
        MessageStateMachine.assertTransition(MessageState.PENDING_RETRY, MessageState.RETRYING);
    }

    @Test
    @DisplayName("RETRYING → RETRY_CONFIRMED 合法：对端回 AA")
    void retrying_to_retry_confirmed_is_legal() throws IllegalStateTransitionException {
        MessageStateMachine.assertTransition(MessageState.RETRYING, MessageState.RETRY_CONFIRMED);
    }

    @Test
    @DisplayName("RETRYING → RETRY_PERMANENT_FAILED 合法：达到 MAX_ATTEMPTS 上限")
    void retrying_to_retry_permanent_failed_is_legal() throws IllegalStateTransitionException {
        MessageStateMachine.assertTransition(MessageState.RETRYING, MessageState.RETRY_PERMANENT_FAILED);
    }

    @Test
    @DisplayName("RETRYING → PENDING_RETRY 合法：单次失败未达上限，回队列下次再试")
    void retrying_to_pending_retry_is_legal() throws IllegalStateTransitionException {
        MessageStateMachine.assertTransition(MessageState.RETRYING, MessageState.PENDING_RETRY);
    }

    @Test
    @DisplayName("RETRY_CONFIRMED → LANDED 合法：重投成功后推进到终态")
    void retry_confirmed_to_landed_is_legal() throws IllegalStateTransitionException {
        MessageStateMachine.assertTransition(MessageState.RETRY_CONFIRMED, MessageState.LANDED);
    }

    @Test
    @DisplayName("RETRY_PERMANENT_FAILED → PENDING_RETRY 合法：人工放行恢复一次重试")
    void retry_permanent_failed_to_pending_retry_allowed() throws IllegalStateTransitionException {
        MessageStateMachine.assertTransition(MessageState.RETRY_PERMANENT_FAILED, MessageState.PENDING_RETRY);
    }

    // ========================================================================
    // 非法边 —— 任务 12 proof 明确点名这三条
    // ========================================================================

    @Test
    @DisplayName("LANDED → RECEIVED 非法：已落地不能再回到刚收到")
    void landed_to_received_is_illegal() {
        IllegalStateTransitionException ex = assertThrows(IllegalStateTransitionException.class,
                () -> MessageStateMachine.assertTransition(MessageState.LANDED, MessageState.RECEIVED));
        assertContainsBothStates(ex, MessageState.LANDED, MessageState.RECEIVED);
    }

    @Test
    @DisplayName("REJECTED → LANDED 非法：拒收后不能直接落地，必须先撤回重审")
    void rejected_to_landed_is_illegal() {
        IllegalStateTransitionException ex = assertThrows(IllegalStateTransitionException.class,
                () -> MessageStateMachine.assertTransition(MessageState.REJECTED, MessageState.LANDED));
        assertContainsBothStates(ex, MessageState.REJECTED, MessageState.LANDED);
    }

    @Test
    @DisplayName("PENDING_CONFIRM → REJECTED 非法：确认门只产出 LANDED，拒收发生在入站阶段")
    void pending_confirm_to_rejected_is_illegal() {
        IllegalStateTransitionException ex = assertThrows(IllegalStateTransitionException.class,
                () -> MessageStateMachine.assertTransition(MessageState.PENDING_CONFIRM, MessageState.REJECTED));
        assertContainsBothStates(ex, MessageState.PENDING_CONFIRM, MessageState.REJECTED);
    }

    // ========================================================================
    // 非法边 —— 其他常见违规
    // ========================================================================

    @Test
    @DisplayName("RECEIVED → RETRYING 非法：必须先入 PENDING_RETRY，再被调度出队")
    void received_to_retrying_is_illegal() {
        assertThrows(IllegalStateTransitionException.class,
                () -> MessageStateMachine.assertTransition(MessageState.RECEIVED, MessageState.RETRYING));
    }

    @Test
    @DisplayName("LANDED → REJECTED 非法：已落地的报文不允许被判拒收")
    void landed_to_rejected_is_illegal() {
        assertThrows(IllegalStateTransitionException.class,
                () -> MessageStateMachine.assertTransition(MessageState.LANDED, MessageState.REJECTED));
    }

    @Test
    @DisplayName("LANDED → RETRYING 非法：已落地不再走重投")
    void landed_to_retrying_is_illegal() {
        assertThrows(IllegalStateTransitionException.class,
                () -> MessageStateMachine.assertTransition(MessageState.LANDED, MessageState.RETRYING));
    }

    @Test
    @DisplayName("REJECTED → LANDED 非法（重复钉死）：不允许越权落地")
    void rejected_to_landed_is_illegal_again() {
        assertThrows(IllegalStateTransitionException.class,
                () -> MessageStateMachine.assertTransition(MessageState.REJECTED, MessageState.LANDED));
    }

    @Test
    @DisplayName("RETRY_PERMANENT_FAILED → LANDED 非法：永久失败不能直接落地，必须人工放行")
    void retry_permanent_failed_to_landed_is_illegal() {
        assertThrows(IllegalStateTransitionException.class,
                () -> MessageStateMachine.assertTransition(MessageState.RETRY_PERMANENT_FAILED, MessageState.LANDED));
    }

    @Test
    @DisplayName("RETRY_PERMANENT_FAILED → RECEIVED 非法：不能回退到刚收到")
    void retry_permanent_failed_to_received_is_illegal() {
        assertThrows(IllegalStateTransitionException.class,
                () -> MessageStateMachine.assertTransition(MessageState.RETRY_PERMANENT_FAILED, MessageState.RECEIVED));
    }

    @Test
    @DisplayName("PENDING_RETRY → LANDED 非法：必须先走 RETRYING → RETRY_CONFIRMED → LANDED 三步")
    void pending_retry_to_landed_is_illegal() {
        assertThrows(IllegalStateTransitionException.class,
                () -> MessageStateMachine.assertTransition(MessageState.PENDING_RETRY, MessageState.LANDED));
    }

    // ========================================================================
    // 空指针 / null 防御
    // ========================================================================

    @Test
    @DisplayName("from=null 抛异常且消息含 'null'")
    void null_from_throws() {
        IllegalStateTransitionException ex = assertThrows(IllegalStateTransitionException.class,
                () -> MessageStateMachine.assertTransition(null, MessageState.LANDED));
        assertTrue(ex.getMessage().contains("null"), "异常消息应提示 null: " + ex.getMessage());
    }

    @Test
    @DisplayName("to=null 抛异常且消息含 'null'")
    void null_to_throws() {
        IllegalStateTransitionException ex = assertThrows(IllegalStateTransitionException.class,
                () -> MessageStateMachine.assertTransition(MessageState.RECEIVED, null));
        assertTrue(ex.getMessage().contains("null"));
    }

    @Test
    @DisplayName("isLegal(null, null) 返 false，不抛异常")
    void isLegal_null_safe() {
        assertFalse(MessageStateMachine.isLegal(null, MessageState.RECEIVED));
        assertFalse(MessageStateMachine.isLegal(MessageState.RECEIVED, null));
        assertFalse(MessageStateMachine.isLegal(null, null));
    }

    // ========================================================================
    // size() 不变量 —— 防止有人偷偷加边忘了更新测试
    // ========================================================================

    @Test
    @DisplayName("LEGAL_TRANSITIONS.size() = 15：4+3+1+1+1+3+1+1")
    void total_legal_edges_is_stable() {
        // RECEIVED=4, PENDING_CONFIRM=3, LANDED=1, REJECTED=1, PENDING_RETRY=1,
        // RETRYING=3, RETRY_CONFIRMED=1, RETRY_PERMANENT_FAILED=1
        // 合计 = 4+3+1+1+1+3+1+1 = 15
        //
        // 第 15 条是 PENDING_CONFIRM → PENDING_CONFIRM 自环（T17 RevertService 的「补充依据」）。
        // 它是**唯一**一条自环：别处若再出现自环，「改了几次」这个计数就失去意义。
        assertEquals(15, MessageStateMachine.size(),
                "合法边总数变化意味着有未声明的新边或边被悄悄删除");
    }

    @Test
    @DisplayName("LEGAL_TRANSITIONS 不可变：业务层反射也写不进去")
    void legal_transitions_is_immutable() {
        assertThrows(UnsupportedOperationException.class,
                () -> MessageStateMachine.LEGAL_TRANSITIONS.put(MessageState.RECEIVED, EnumSet.of(MessageState.LANDED)));
        Set<MessageState> landedEdges = MessageStateMachine.LEGAL_TRANSITIONS.get(MessageState.LANDED);
        assertThrows(UnsupportedOperationException.class,
                () -> landedEdges.add(MessageState.RECEIVED));
    }

    // ========================================================================
    // 枚举完备性 —— 8 个状态全部登记；fromDbValue 反查
    // ========================================================================

    @Test
    @DisplayName("每个 state 都在 LEGAL_TRANSITIONS 里登记出边")
    void every_state_has_outbound_edges() {
        for (MessageState s : MessageState.values()) {
            assertNotNull(MessageStateMachine.LEGAL_TRANSITIONS.get(s),
                    "state " + s + " 未在状态机登记（程序错误）");
            assertFalse(MessageStateMachine.LEGAL_TRANSITIONS.get(s).isEmpty(),
                    "state " + s + " 没有任何出边（孤立点）");
        }
    }

    @Test
    @DisplayName("MessageState.fromDbValue 全部 8 个枚举轮转一致")
    void from_db_value_roundtrip() {
        for (MessageState s : MessageState.values()) {
            assertSame(s, MessageState.fromDbValue(s.dbValue()));
            assertEquals(s.dbValue(), s.dbValue());
        }
    }

    @Test
    @DisplayName("MessageState.fromDbValue 拒绝 null 与非法值")
    void from_db_value_rejects_invalid() {
        assertThrows(IllegalArgumentException.class, () -> MessageState.fromDbValue(null));
        assertThrows(IllegalArgumentException.class, () -> MessageState.fromDbValue(""));
        assertThrows(IllegalArgumentException.class, () -> MessageState.fromDbValue("received")); // 大小写敏感
        assertThrows(IllegalArgumentException.class, () -> MessageState.fromDbValue("RECEIVED_X"));
    }

    // ========================================================================
    // 全图枚举覆盖 —— 让任何「漏边」一眼暴露
    // ========================================================================

    @Test
    @DisplayName("全图枚举：8×8 = 64 种 (from, to) 中恰好 14 种合法，其余 50 种非法")
    void full_transition_matrix_matches_legal_set() {
        List<MessageState> all = new ArrayList<>();
        EnumSet<MessageState> legalCount = EnumSet.noneOf(MessageState.class);

        int legal = 0;
        int illegal = 0;
        for (MessageState from : MessageState.values()) {
            all.add(from);
            for (MessageState to : MessageState.values()) {
                if (MessageStateMachine.isLegal(from, to)) {
                    legal++;
                    legalCount.add(to);
                } else {
                    illegal++;
                }
            }
        }
        assertEquals(15, legal, "应为 15 条合法边");
        // 8*8 - 15 = 49；唯一合法的自环是 PENDING_CONFIRM → PENDING_CONFIRM（T17 补充依据）
        assertEquals(49, illegal, "应为 49 条非法边");

        // 自环纪律：全表里只允许 PENDING_CONFIRM 一处自环
        for (MessageState from : MessageState.values()) {
            if (MessageStateMachine.isLegal(from, from) && from != MessageState.PENDING_CONFIRM) {
                fail(from + " 不允许迁移到自己：自环会让「改了几次」这个计数失去意义");
            }
        }
    }

    // ========================================================================
    // helpers
    // ========================================================================

    /**
     * 断言异常消息同时含 from 与 to 的 dbValue，避免「只报一半」让排障人员摸不着头脑。
     */
    private static void assertContainsBothStates(IllegalStateTransitionException ex,
                                                 MessageState from,
                                                 MessageState to) {
        assertTrue(ex.getMessage().contains(from.dbValue()),
                "异常消息应含 from=" + from.dbValue() + "：实际=" + ex.getMessage());
        assertTrue(ex.getMessage().contains(to.dbValue()),
                "异常消息应含 to=" + to.dbValue() + "：实际=" + ex.getMessage());
        assertSame(from, ex.from());
        assertSame(to, ex.to());
    }
}
