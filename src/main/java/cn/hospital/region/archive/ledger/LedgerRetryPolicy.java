package cn.hospital.region.archive.ledger;

import java.sql.SQLException;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 并发迁移的<b>调用约定</b> —— 拿到 {@link ConcurrentLedgerUpdateException} 之后该做什么。
 *
 * <h2>约定本身只有两句，但必须写下来</h2>
 * <ol>
 *   <li><b>重读后重试</b>：冲突的根因是「我读的 version 过期了」，所以每次重试都必须
 *       <b>重新读</b> {@code state} 与 {@code version} 再发迁移。直接拿旧值重发是死循环 ——
 *       库里没变，重发还是同一个 {@code WHERE version = ?} 不命中。</li>
 *   <li><b>上限 3 次后转人工可见错误</b>：连着 3 次都是同一个信号时，结论已经不是抖动而是
 *       持续争用。此时抛 {@link LedgerConcurrencyExhaustedException}，
 *       该报文<b>保持原态</b>等人看，<b>不</b>自动推进、<b>不</b>静默回执。</li>
 * </ol>
 *
 * <h2>为什么不能自动多试几次</h2>
 * <p>本产品接入主路径每条报文自成一个事务，且同一条报文可能被多个线程同时推进
 * （接入线程与重投调度器、或两个对端连接同时重推同一条）。冲突通常在 1 次重读内就收敛；
 * 持续冲突意味着有别的问题（有人绕过状态机在改 state、或一条报文被两个流程同时确认），
 * 再重试只是把争用放大。<b>停下来让人看，比多撞几次库更安全。</b>
 *
 * <h2>非法迁移不重试</h2>
 * <p>{@link IllegalStateTransitionException} 是业务规则违反（这条边本来就不存在），
 * 重读多少次都一样，本类<b>原样上抛</b>不吞。把它混进「重读再试」是这类重试代码最常见的错误：
 * 它会把一条永远不可能成功的迁移重试到上限，然后报一个「并发冲突用尽」——
 * 排障的人会去查并发，而真正的病根是一次非法边。
 */
public final class LedgerRetryPolicy {

    private static final Logger LOG = Logger.getLogger(LedgerRetryPolicy.class.getName());

    /** 重试上限：3 次。规范里写死的默认值，测试与生产一致。 */
    public static final int DEFAULT_MAX_ATTEMPTS = 3;

    private final int maxAttempts;

    /** 默认上限 3 次。 */
    public LedgerRetryPolicy() {
        this(DEFAULT_MAX_ATTEMPTS);
    }

    public LedgerRetryPolicy(int maxAttempts) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("重试上限至少为 1（上限 0 等于不重试，那是另一个方法的事）: "
                    + maxAttempts);
        }
        this.maxAttempts = maxAttempts;
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    /**
     * 把一条报文推进到目标状态，全程遵守「重读后重试、上限 3 次」的约定。
     *
     * <p>每次尝试：重读该行的 {@code state} 与 {@code version} → 用带版本守卫的
     * {@link LedgerStore#transition} 提交 → 成功即返回。命中
     * {@link ConcurrentLedgerUpdateException} 或 {@link LedgerStateConflictException}
     * （两者都是「重读后会变」的性质）则重读再试；用尽上限抛
     * {@link LedgerConcurrencyExhaustedException}。
     *
     * <p>行不存在时直接抛 {@link LedgerConcurrencyExhaustedException}：台账行被删属于
     * 「重读再试」救不回来的情况，不能当成并发冲突空转满上限。
     *
     * @param targetState 目标状态；{@code to} 是否与当前态构成合法边由
     *                    {@link MessageStateMachine} 判定，非法边原样上抛
     * @param operator    操作人（不得为空串）；人工动作用 token subject，自动动作用 {@code system}
     * @param reason      原因短语（不得为空串）
     */
    public TransitionResult transitionTo(LedgerStore store, long ledgerId, MessageState targetState,
                                         String operator, String reason)
            throws SQLException, IllegalStateTransitionException, LedgerConcurrencyExhaustedException {

        Objects.requireNonNull(store, "台账存储不得为 null");
        Objects.requireNonNull(targetState, "目标状态不得为 null");

        long lastExpected = -1L;
        long lastActual = -1L;
        MessageState lastState = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            // ---- 每次重试都重读：冲突的根因就是「读到的值过期了」----
            LedgerRow row = store.findById(ledgerId).orElse(null);
            if (row == null) {
                throw new LedgerConcurrencyExhaustedException(ledgerId, attempt - 1,
                        lastExpected, lastActual, lastState);
            }
            lastExpected = row.version();
            lastActual = row.version();
            lastState = row.state();

            try {
                return store.transition(ledgerId, row.state(), targetState, row.version(),
                        operator, reason);
            } catch (ConcurrentLedgerUpdateException e) {
                lastExpected = e.expectedVersion();
                lastActual = e.actualVersion();
                lastState = e.actualState();
                LOG.log(Level.WARNING, "台账乐观锁冲突，重读后重试（第 {0}/{1} 次）: {2}",
                        new Object[]{attempt, maxAttempts, e.toLogLine()});
            } catch (LedgerStateConflictException e) {
                lastState = e.actualState();
                LOG.log(Level.WARNING, "台账来源态冲突，重读后重试（第 {0}/{1} 次）: ledgerId={2} "
                                + "声称 from={3} 实际 state={4}",
                        new Object[]{attempt, maxAttempts, e.ledgerId(),
                                e.claimedFrom() == null ? "<null>" : e.claimedFrom().dbValue(),
                                e.actualState() == null ? "<行已不存在>" : e.actualState().dbValue()});
            }
        }

        // 用尽上限：抛人工可见错误，让这条报文停在原态等人看，不静默放过
        throw new LedgerConcurrencyExhaustedException(ledgerId, maxAttempts,
                lastExpected, lastActual, lastState);
    }
}