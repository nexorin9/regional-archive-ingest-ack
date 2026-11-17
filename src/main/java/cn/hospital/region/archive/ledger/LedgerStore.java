package cn.hospital.region.archive.ledger;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 接收台账的存储契约 —— 业务层只认这五个方法，不认 JDBC。
 *
 * <h2>为什么要有这一层抽象</h2>
 * <p>产品里读台账的地方有五处（接入主路径、T19 批次统计、T32 重投队列、T34 对账差集、
 * T35 重启续跑），写台账的地方只有一处（接入主路径与确认门）。抽接口不是为了「将来换存储」，
 * 而是为了把下面这条纪律变成<b>编译期</b>可查的约束：
 *
 * <blockquote>
 * <b>状态只能通过 {@link #transition} 改</b>。接口里<b>没有</b> {@code updateState(...)}，
 * 于是「改 state 但不写审计」这件事在本项目里写不出来 —— 不是靠 code review 拦，是靠没有这个方法。
 * 真正拿到裸连接的人（运维排障脚本）绕过去也不是不行，那由 {@link LedgerAuditChain} 查出来。
 * </blockquote>
 *
 * <h2>实现</h2>
 * <p>当前唯一实现是 {@link JdbcLedgerStore}（PostgreSQL 生产 / H2 PG 兼容模式测试，同一份 SQL）。
 */
public interface LedgerStore {

    /**
     * 幂等插入一条报文。同一幂等键重复到达时<b>不</b>抛异常，而是返回
     * {@link InsertOutcome.AlreadyProcessed} 并带上原有那行的主键与<b>当前</b>状态。
     *
     * <p>这是本产品最常走的一条分支：对端重推、网络抖动后重发、T35 重启后补跑，
     * 都会命中「已存在」。调用方拿到 {@code AlreadyProcessed} 后应按
     * {@link InsertOutcome.AlreadyProcessed#currentState()} 回执，而不是再跑一遍业务。
     *
     * @param entry 待入账报文；其初始状态固定为 {@link MessageState#RECEIVED}
     */
    InsertOutcome insertIfAbsent(LedgerEntry entry) throws SQLException;

    /** 按幂等键查台账行；不存在返回 {@link Optional#empty()}（不是 {@code null}）。 */
    Optional<LedgerRow> findByIdempotencyKey(IdempotencyKey key) throws SQLException;

    /**
     * 按台账行主键读一行（含 {@code state} 与 {@code version}）；不存在返回 {@link Optional#empty()}。
     *
     * <p>这是「重读后重试」约定的读侧入口：{@link LedgerRetryPolicy} 每次重试都调它，
     * 拿最新的 {@code state} 与 {@code version} 去发带版本守卫的迁移。
     */
    Optional<LedgerRow> findById(long id) throws SQLException;

    /**
     * 迁移一条报文的状态 —— <b>本接口唯一的状态写入口</b>。
     *
     * <p>语义（在<b>单个事务</b>内完成，任一步失败整体回滚）：
     * <ol>
     *   <li>{@code assertTransition(from, to)}：非法边在开事务<b>之前</b>就被拒，绝不落库；</li>
     *   <li>{@code UPDATE message_ledger SET state=?, version=version+1, updated_at=? WHERE id=? AND state=?}：
     *       影响行数为 0 表示来源态与库里实际不符 → {@link LedgerStateConflictException}，
     *       该次迁移被存储层拒绝；</li>
     *   <li>{@code INSERT INTO message_audit}：审计失败 → 回滚上面那条 UPDATE；</li>
     *   <li>提交。</li>
     * </ol>
     *
     * <p><b>本重载不带版本守卫</b>，只靠 {@code state} 收敛并发。在「A 读 → B 读 → A 写 → B 写」
     * 这条窗口里两次读到的 {@code state} 相同且都还没写入，靠 {@code state} 守卫<b>拦不住</b>；
     * 同一条报文被两个线程同时推进（接入线程与重投调度器、或对端两个连接重推同一条）正是这条窗口。
     * 并发路径一律用下面带 {@code expectedVersion} 的重载。
     *
     * @param operator 操作人；自动迁移传 {@code system}，人工动作传 token subject（不得为空串）
     * @param reason   原因短语（不得为空串）
     */
    TransitionResult transition(long id, MessageState from, MessageState to,
                                String operator, String reason)
            throws SQLException, IllegalStateTransitionException, LedgerStateConflictException;

    /**
     * 带<b>乐观锁</b>的迁移 —— T15 的并发路径专用重载。
     *
     * <p>UPDATE 变为 {@code ... WHERE id = ? AND state = ? AND version = ?}：调用方提交它读到的
     * {@code version}，若这行在「读」与「写」之间被别人推进过，影响行数为 0，
     * 本次迁移被<b>存储层拒绝</b>，不覆盖对方的写入 —— 这条 CAS 由数据库保证，不依赖 JVM 内的锁。
     *
     * <p>影响行数为 0 时按实际读到的值分抛，两者职责不同、处置也不同：
     * <ul>
     *   <li>{@code state} 也变了 → {@link LedgerStateConflictException}（调用方拿着过期的来源态）；</li>
     *   <li>{@code state} 对得上但 {@code version} 变了 →
     *       {@link ConcurrentLedgerUpdateException}（真正的并发窗口，可重读后重试）。</li>
     * </ul>
     *
     * <p>调用方须重读 {@code state} 与 {@code version} 后重试；重试约定与上限（3 次后转人工可见错误）
     * 见 {@link LedgerRetryPolicy}，<b>不得静默吞掉</b>。
     *
     * @param expectedVersion 调用方最近一次读到的 version；必须与 {@code from} 同属一次读取
     */
    TransitionResult transition(long id, MessageState from, MessageState to,
                                long expectedVersion, String operator, String reason)
            throws SQLException, IllegalStateTransitionException, LedgerStateConflictException,
            ConcurrentLedgerUpdateException;

    /**
     * 落地认领：把 {@code PENDING_CONFIRM → LANDED} 的迁移与「记下院内主键」放进
     * <b>同一条 UPDATE</b>。
     *
     * <p>为什么不用普通的 {@link #transition} 再补一次写 {@code local_mrn}：
     * 两步之间失败会留下一行「已落地但没记住主键」的记录，而
     * {@code local_mrn} 正是判断「这次确认要不要重写 HIS」的唯一依据
     * （见 {@code ArchiveIngestService.land}）—— 恰好在最需要判断时判断不了。
     *
     * <p>其余语义（状态机断言、来源态守卫、乐观锁、同事务审计、失败整体回滚）
     * 与带版本守卫的 {@code transition} 完全一致，本方法同样只写这一个状态边。
     */
    TransitionResult claimLanding(long id, long expectedVersion, String operator,
                                   String reason, String localMrn)
            throws SQLException, IllegalStateTransitionException, LedgerStateConflictException,
            ConcurrentLedgerUpdateException;

    /**
     * 释放一次落地认领：{@code LANDED → PENDING_CONFIRM} 的同时<b>清空</b> {@code local_mrn}。
     *
     * <p>用于「认领成功了、但写 HIS 失败」之后的回退。这一步清空不是可选的：
     * {@code local_mrn} 是判断「下次确认要不要重写 HIS」的唯一依据
     * （见 {@code ArchiveIngestService.land}），一次<b>失败</b>的写入若把它留下，
     * 人的重试就会被幂等抑制掉 —— 报文永远落不了地，且症状是「日志说确认成功了、HIS 里查无此人」。
     *
     * <p>于是本项目维护一条明确不变量：
     * <b>{@code local_mrn} 非空 ⟺ 该行当前持有一份已成功写入 HIS 的映射。</b>
     * 撤回（{@code RevertService}）不清空它：撤回针对的是「待确认」这个待办，
     * 而 HIS 里那份映射确实还在；清空会让撤回后的同目标重确认多写一次。
     */
    TransitionResult releaseLanding(long id, long expectedVersion, String operator, String reason)
            throws SQLException, IllegalStateTransitionException, LedgerStateConflictException,
            ConcurrentLedgerUpdateException;

    /**
     * 按批次列出台账行；{@code state} 传 {@code null} 表示不限状态。
     * 按 {@code id} 升序返回（= 入账顺序），便于「下午按批次核对收到几条」时逐条比对。
     */
    List<LedgerRow> listByBatch(String batchId, MessageState state) throws SQLException;

    /**
     * 按批次统计各状态条数。
     *
     * <p>返回的 map <b>预填全部 {@link MessageState} 取值</b>（无记录的置 0），
     * 调用方直接打成一张完整分布表即可，不必自己补「没有拒收条目」这一行 ——
     * 「这一批没有拒收」是一个需要在报表上显式出现的结论，不该因为 map 里没这个 key 就消失。
     */
    Map<MessageState, Long> countByState(String batchId) throws SQLException;

    /**
     * 按状态集合列出台账行 —— 给 T35 重启续跑用。
     *
     * <p>指定一组 {@link MessageState}，按 {@code id} 升序返回该状态下<b>全部</b>台账行。
     * 当前唯一调用方是 {@code startup.RecoveryScan}：启动时筛选「需要被恢复队列」的行
     * （{@code PENDING_RETRY} 等非终态，且不在 {@code RECEIVED / PENDING_CONFIRM /
     * LANDED / REJECTED} 之列），给重投调度器续跑。
     *
     * <p>非终态与「未进入重投体系」的差由调用方决定：本接口只列出查询范围内的全部行，
     * 业务筛选交回调用方 —— 若日后区分「仅重投相关非终态」「全部非终态」，
     * 加新方法而不是把这一个变成万能桶（参数集合不限大小则实现就得分别考虑索引路径，
     * 与本接口「V1 的复合索引 {@code idx_ledger_state_updated} 已可覆盖」的初衷相悖）。
     *
     * @param states 状态集合；{@code null} 或空集合返回空列表而非抛异常
     */
    List<LedgerRow> findByStates(java.util.Set<MessageState> states) throws SQLException;
}