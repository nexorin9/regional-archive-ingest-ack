package cn.hospital.region.archive.ledger;

import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 幂等键双保险 —— 「同一条报文无论到达多少次，业务处理只发生一次」的第一个落点。
 *
 * <h2>为什么源产品里没有这个东西</h2>
 * <p>源产品（{@code github_ref/dcm4che-hl7/.../MLLPConnection.java}）是「收进来就处理」的无状态
 * 工具库：{@code readACK()} 拿到 NAK 只是抛异常、上面重试一次，既没有幂等概念也没有台账，
 * 「这条报文我处理过没有」这个问题在它那儿<b>根本不会被提出</b>。而本产品要替区域平台
 * 逐条回可对账的回执，一旦同一条报文被重复处理，回执就会给出互相矛盾的结果，对账永远平不了。
 * 这就是本类存在的全部理由。
 *
 * <h2>两层保险各管一段窗口</h2>
 * <ol>
 *   <li><b>先查后写（快路径）</b>：SELECT {@code uk_ledger_idem_key} 三列。重复到达在本产品里是
 *       <b>常态</b>（对端重推、网络抖动后重发、重启续跑），绝大多数重复都该在这里结束 ——
 *       不开事务、不撞约束、不回滚，代价最低。</li>
 *   <li><b>唯一约束兜底（并发窗口）</b>：两个线程可能同时先查都查不到，然后同时插入。
 *       {@code message_ledger.uk_ledger_idem_key} 让其中一个失败。
 *       {@link JdbcLedgerStore#insertIfAbsent} 已把该 SQLState（23505）<b>翻译</b>成
 *       {@link InsertOutcome.AlreadyProcessed}；本类再兜一层，防止将来换一个
 *       {@link LedgerStore} 实现忘了翻译、让 23505 直接冒到主路径上打断整批处理。</li>
 * </ol>
 *
 * <h2>命中哪一层是可观测的</h2>
 * <p>两层的结果分别落在 {@link AcquireResult.DuplicateOrigin#PRE_READ} 与
 * {@link AcquireResult.DuplicateOrigin#UNIQUE_CONSTRAINT}。正常情况下后者应当<b>接近 0</b>；
 * 它一旦常态上升，说明先查被绕过了（有人直接调 store 没走 guard），或者先查被包在了一个
 * 长事务里导致快照读不到未提交行 —— 此时每条重复报文都要付一次事务回滚的代价。
 * 所以本类每次兜底命中都打 INFO 一行（见日志采样），让这个信号可被 grep。
 *
 * <h2>不做什么</h2>
 * <ul>
 *   <li>不吞异常：只有「重复到达」被翻译成 {@link AcquireResult.Duplicate}，
 *       其余 {@link SQLException} 一律上抛；</li>
 *   <li>不代替业务判断：拿到 {@link AcquireResult.Acquired} 之后要不要继续跑、跑哪些步骤，
 *       由主路径决定；</li>
 *   <li>不改状态：本类只负责「入账与否」，状态推进一律走
 *       {@link LedgerStore#transition}（T15 的乐观锁就在那条路径上）。</li>
 * </ul>
 *
 * @see LedgerRetryPolicy 并发迁移的调用约定：重读后重试，上限 3 次
 */
public final class IdempotencyGuard {

    private static final Logger LOG = Logger.getLogger(IdempotencyGuard.class.getName());

    /** 唯一约束冲突的 SQLState —— PG 与 H2 PG 兼容模式一致，见 {@link JdbcLedgerStore}。 */
    private static final String SQLSTATE_UNIQUE_VIOLATION = "23505";

    /**
     * 兜底层命中的日志采样：全部 INFO 会刷屏，但这个计数必须能查。
     * 每 50 条打一条汇总（含累计数），其余打 FINE，{@code LOG.setLevel(FINE)} 即可全量。
     */
    private static final int BACKSTOP_LOG_EVERY = 50;

    private final LedgerStore store;
    private int backstopHits = 0;

    public IdempotencyGuard(LedgerStore store) {
        this.store = Objects.requireNonNull(store, "台账存储不得为 null");
    }

    /**
     * 尝试占用这个幂等键 —— 本端第一次收到就入账并返回 {@link AcquireResult.Acquired}，
     * 已经收过就返回 {@link AcquireResult.Duplicate}（带原行主键与<b>当前</b>状态）。
     *
     * <p>幂等键取自入参 {@link LedgerEntry#key()}（即 MSH-9 + MSH-10 + MSH-11）；
     * 本类的单位是「一条待入账报文」，键只是它的身份，不单独作为入参 ——
     * 单独拿键来调是拿不到 {@code rawPayload} 的，而没有报文原文的入账等于没入账。
     *
     * <p><b>正常路径重复到达不会抛异常。</b>调用方按
     * {@link AcquireResult#shouldRunBusiness()} 分支：true 继续跑业务，false 直接按
     * {@link AcquireResult#state()} 回执即可。
     *
     * @throws SQLException 台账不可用（连接失败、SQL 本身出错）；重复到达<b>不</b>走这里
     */
    public AcquireResult tryAcquire(LedgerEntry entry) throws SQLException {
        Objects.requireNonNull(entry, "待入账报文不得为 null");
        IdempotencyKey key = entry.key();

        // ---- 第 1 层：先查（常态路径，开销最小）----
        Optional<LedgerRow> existing = store.findByIdempotencyKey(key);
        if (existing.isPresent()) {
            LedgerRow row = existing.get();
            LOG.log(Level.FINE, "幂等键已存在（先查命中）: ledgerId={0} {1} state={2}",
                    new Object[]{row.id(), key, row.state().dbValue()});
            return new AcquireResult.Duplicate(row.id(), key, row.state(),
                    AcquireResult.DuplicateOrigin.PRE_READ);
        }

        // ---- 第 2 层：插入，唯一约束兜底 ----
        try {
            InsertOutcome outcome = store.insertIfAbsent(entry);
            if (outcome instanceof InsertOutcome.AlreadyProcessed dup) {
                // 走到这里说明并发窗口里别人插成功了；store 已完成 23505 → AlreadyProcessed 的翻译。
                countBackstopHit();
                return new AcquireResult.Duplicate(dup.existingLedgerId(), dup.key(),
                        dup.currentState(), AcquireResult.DuplicateOrigin.UNIQUE_CONSTRAINT);
            }
            return new AcquireResult.Acquired(outcome.ledgerId(), key,
                    ((InsertOutcome.Inserted) outcome).initialState());
        } catch (SQLException e) {
            // store 若忘了翻译唯一约束，23505 就会冒到这里。重复到达不是错误，
            // 因此这一层兜底把它再翻译一次 —— 但只认 SQLState，不按消息字面量猜。
            if (isUniqueViolation(e)) {
                return backstopByLookup(key);
            }
            throw e;
        }
    }

    /** 兜底层：唯一约束确认有行、但上抛了 23505。重新查一次拿当前态返回 {@link Duplicate}。 */
    private AcquireResult backstopByLookup(IdempotencyKey key) throws SQLException {
        LedgerRow row = store.findByIdempotencyKey(key).orElseThrow(() -> new SQLException(
                "唯一约束 " + SQLSTATE_UNIQUE_VIOLATION + " 已触发但按幂等键查不到原行"
                        + " —— 台账数据不一致，拒绝返回假「已处理」（否则调用方会跳过业务处理，"
                        + "等于静默丢报文）: " + key));
        countBackstopHit();
        return new AcquireResult.Duplicate(row.id(), key, row.state(),
                AcquireResult.DuplicateOrigin.UNIQUE_CONSTRAINT);
    }

    /** 累计兜底命中数：正常应接近 0，上升即说明先查被绕过或失效。 */
    private void countBackstopHit() {
        int n = ++backstopHits;
        if (n % BACKSTOP_LOG_EVERY == 0) {
            LOG.log(Level.INFO, "唯一约束兜底累计命中 {0} 次 —— 正常应接近 0；上升说明先查被绕过"
                    + "（有人直接调 store 没走 guard）或先查被包在长事务里导致读不到未提交行", n);
        } else {
            LOG.log(Level.FINE, "唯一约束兜底命中（第 {0} 次）", n);
        }
    }

    /** 本进程内累计的兜底命中次数；供测试与运维排障读取。 */
    public int backstopHitCount() {
        return backstopHits;
    }

    /** 判断一个 {@link SQLException}（含 cause 链）是否是唯一约束冲突；只认 SQLState 23505。 */
    private static boolean isUniqueViolation(SQLException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException se
                    && SQLSTATE_UNIQUE_VIOLATION.equals(se.getSQLState())) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }
}