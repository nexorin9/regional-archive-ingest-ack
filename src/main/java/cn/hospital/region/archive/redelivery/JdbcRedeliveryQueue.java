package cn.hospital.region.archive.redelivery;

import cn.hospital.region.archive.ledger.IdempotencyKey;
import cn.hospital.region.archive.ledger.MessageState;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@link RedeliveryQueue} 的 JDBC 实现 —— {@code redelivery_queue} 表的唯一直写入口。
 *
 * <h2>核心保证</h2>
 * <ol>
 *   <li><b>准入由 offer 把关</b>：{@link #offer} 在开事务<b>之前</b>先过 {@link #shouldEnqueue}
 *       —— AR / AA / LANDED / RECEIVED 一律返回 {@link RedeliveryQueue.OfferResult#rejected()}，
 *       <b>绝不</b>触库；这与 T9 的 AE/AR 语义同口径。</li>
 *   <li><b>同 key 不重</b>：{@code uk_redelivery_idem_key} 唯一约束保证。
 *       并发窗口里两线程同时插同一条不会产两条 —— 约束冲突被翻译成
 *       {@link RedeliveryQueue.OfferResult#duplicated(RedeliveryEntry)}。</li>
 *   <li><b>出队原子</b>：{@link #claimNextDue} 用一条 {@code UPDATE ... LIMIT 1} 把
 *       {@code status='QUEUED' AND next_attempt_at_ms<=?} 的最早一条置为 CONSUMED。
 *       影响行数 0 即「没拿到」；1 即「拿到了」。<b>不靠 JVM 锁。</b></li>
 *   <li><b>结算与重排</b>：{@link #settle} 把 CONSUMED 推到终态（ACK_CONFIRMED / PERMANENT_FAILED），
 *       {@link #reschedule} 仅改 attempt 与 next_attempt_at_ms 给 T33 退避留口子。</li>
 * </ol>
 *
 * <h2>事务边界</h2>
 * <p>同 {@link cn.hospital.region.archive.ledger.JdbcLedgerStore}：本类持有事务，不让调用方
 * 拼半截——避免「出队了但重投前挂了、又自动重发」。一次 {@link #offer} / {@link #claimNextDue} /
 * {@link #settle} 自成事务。
 *
 * <h2>SQL 参数绑定</h2>
 * <p>一切外部输入（key / batchId / ackCode / reason）一律 PreparedStatement；不让把字段值拼成 SQL。
 */
public final class JdbcRedeliveryQueue implements RedeliveryQueue {

    private static final Logger LOG = Logger.getLogger(JdbcRedeliveryQueue.class.getName());

    /** PG / H2 PG 兼容模式下唯一约束冲突的 SQLState；同 V1 uk_ledger_idem_key 口径。 */
    private static final String SQLSTATE_UNIQUE_VIOLATION = "23505";

    /** {@code last_error} 列宽上限，与 V5 schema 的 {@code VARCHAR(512)} 严格对齐。 */
    private static final int MAX_LAST_ERROR = 512;

    /** 重投队列条目的只读列；{@link #readEntries} 与所有查询共用一份列序。 */
    private static final String QUEUE_COLUMNS =
            "id, ledger_id, idempotency_key, batch_id, status, attempt, next_attempt_at_ms, "
                    + "last_error, enqueue_reason, created_at, last_attempt_at, consumed_at";

    /** 提供数据库连接。测试复用 H2 连接 lambda，生产开新连接 lambda。 */
    @FunctionalInterface
    public interface ConnectionProvider {
        Connection open() throws SQLException;
    }

    private final ConnectionProvider connections;
    private final java.time.Clock clock;

    public JdbcRedeliveryQueue(ConnectionProvider connections, java.time.Clock clock) {
        this.connections = Objects.requireNonNull(connections, "数据库连接提供器不得为 null");
        this.clock = Objects.requireNonNull(clock, "时钟不得为 null");
    }

    public JdbcRedeliveryQueue(ConnectionProvider connections) {
        this(connections, java.time.Clock.systemUTC());
    }

    // =====================================================================
    // offer — 入队口
    // =====================================================================

    @Override
    public OfferResult offer(IdempotencyKey key, long ledgerId, String batchId,
                             String ackCode, MessageState currentState,
                             EnqueueTrigger trigger, long nextAttemptAtMs)
            throws SQLException {
        Objects.requireNonNull(key, "幂等键不得为 null");
        Objects.requireNonNull(batchId, "batchId 不得为 null");
        Objects.requireNonNull(ackCode, "ackCode 不得为 null");
        Objects.requireNonNull(currentState, "currentState 不得为 null");
        Objects.requireNonNull(trigger, "trigger 不得为 null");
        if (ledgerId <= 0) {
            throw new IllegalArgumentException("ledgerId 必须为正数，实际=" + ledgerId);
        }
        if (nextAttemptAtMs < 0) {
            throw new IllegalArgumentException("nextAttemptAtMs 不得为负: " + nextAttemptAtMs);
        }

        // ---- 第 1 道闸：准入 ----
        Rejection reject = shouldEnqueue(ackCode, currentState, trigger);
        if (reject != null) {
            return OfferResult.rejected(reject.reason);
        }

        String storageKey = key.storageKey();

        // ---- 第 2 道闸：同 key 已存在则返回 Duplicate ----
        try (Connection conn = connections.open()) {
            boolean previous = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                OfferResult duplicate = findExisting(conn, storageKey);
                if (duplicate != null) {
                    conn.commit();
                    return duplicate;
                }

                long id = insertNewEntry(conn, ledgerId, storageKey, batchId, trigger, nextAttemptAtMs);
                RedeliveryEntry row = readById(conn, id).orElseThrow(() ->
                        new SQLException("刚插入的队列条目立即查不到（id=" + id + "），拒绝继续："
                                + "不知道状态与字段值，调度器拿不到东西"));

                conn.commit();
                LOG.log(Level.FINE, "重投入队: ledgerId={0} status={1} trigger={2} attempt={3}",
                        new Object[]{ledgerId, row.status(), trigger.label(), row.attempt()});
                return OfferResult.offered(row);
            } catch (SQLIntegrityConstraintViolationException e) {
                conn.rollback();
                if (isUniqueViolation(e)) {
                    return OfferResult.rejected("uk_redelivery_idem_key: same idempotency_key already in queue");
                }
                throw e;
            } catch (SQLException e) {
                conn.rollback();
                // 兜底二次翻译：并发窗口里两人同时插入，约束冲突 SQLState 应该是 23505
                if (isUniqueViolation(e)) {
                    OfferResult duplicate = readAfterConstraint(conn, storageKey);
                    return duplicate != null
                            ? duplicate
                            : OfferResult.rejected("uk_redelivery_idem_key caught then missing row");
                }
                throw e;
            } finally {
                conn.setAutoCommit(previous);
            }
        }
    }

    /**
     * 准入判定 —— offer 入队口的唯一闸。返回值非 null 时 offer 立即返回 Rejected，<b>不触库</b>。
     *
     * <p><b>三不收</b>：
     * <ul>
     *   <li>AA：报文被本端受理但报「处理中」，再投反而把对端的「重复」返给我，混淆责任。
     *       AA 走 MessageReceiptBook.record → state=RECEIVED，<b>不入队</b>。
     *       <b>例外（T34 实测修正）</b>：次日差集触发（{@code NO_ACK_NEXT_DAY}）问的是
     *       「对端收到我们的回执没有」，AA 答的是「本端受理了没有」——两者无关，
     *       所以 {@code PENDING_CONFIRM + AA} 在对账通道上<b>必须</b>能入队，
     *       否则「平台次日追问」这条产品主路径一条也重投不了。</li>
     *   <li>AR：确定性事实，重复一万次还是同样的字节（字符集不支持 / 身份证冲突 / 版本不兼容）。
     *       再打一次只是浪费对端 CPU，毫秒计的节约也值得做。</li>
     *   <li>LANDED：已对账完结，重投会重新写 HIS，幂等键守不住的二次写入风险。</li>
     * </ul>
     */
    private static Rejection shouldEnqueue(String ackCode, MessageState state, EnqueueTrigger trigger) {
        if (MessageState.LANDED == state) {
            return new Rejection("state=LANDED: 已对账完结，不再重投");
        }
        if (MessageState.REJECTED == state) {
            // 与 AR 同语义：拒收原因已是本端判定的事实（字符集 / 身份证冲突等）。
            // RejectionService 撤回路径不经过 offer。
            return new Rejection("state=REJECTED: 拒收事实不允许重投");
        }
        if (MessageState.RETRY_PERMANENT_FAILED == state) {
            return new Rejection("state=RETRY_PERMANENT_FAILED: 永久失败仅可由人工放行回 PENDING_RETRY，offer() 不接受");
        }
        if (MessageState.RETRY_CONFIRMED == state) {
            return new Rejection("state=RETRY_CONFIRMED: 已确认待推进，不入队");
        }

        String code = ackCode.trim();
        if ("AR".equals(code)) {
            return new Rejection("ack=AR: 确定性事实，重投无意义（字符集 / 身份证冲突 / 版本不兼容）");
        }
        if ("AA".equals(code)) {
            // AA 答的是「本端应用层受理了这条报文」，它没有回答「对端收到我们的回执没有」
            // ——次日差集问的是后者。所以对账差异触发与两条恢复通道不受这条闸拦：
            // 一条还在 PENDING_CONFIRM 的报文，对端没记下它，就该让它进重投队列。
            // 只有「处理中重试」那条路（trigger=MSA_AE）才认 AA = 已受理、不必再投。
            if (MessageState.PENDING_CONFIRM == state && trigger != EnqueueTrigger.MSA_AE) {
                return null;
            }
            return new Rejection("ack=AA: 本端已受理且当前状态为 " + state.dbValue()
                    + "（非对账差异触发），不再入重投");
        }
        if ("AE".equals(code)) {
            // 命中：AE 可重投（不论当前态）
            return null;
        }

        // 未受控的 ackCode（null / 空 / 奇形）：与 AA / AR 都不一致时，仅当状态为 PENDING_CONFIRM 才入队
        // —— 这是差集触发（T34）的位置，次日对账发现状态还在 PENDING_CONFIRM 且 ack 还是未知值，
        // 才走 offer 入队（标记 NO_ACK_NEXT_DAY）。
        if (MessageState.PENDING_CONFIRM == state) {
            return null;
        }
        if (MessageState.PENDING_RETRY == state) {
            // PENDING_RETRY 入队口只为 recovery-scan / manual-release 留，触发 MSA_AE / NO_ACK_NEXT_DAY 一律拒。
            if (trigger == EnqueueTrigger.MANUAL_RELEASE || trigger == EnqueueTrigger.RECOVERY_SCAN) {
                return null;
            }
            return new Rejection("state=PENDING_RETRY: 仅人工放行/重启续跑可入，trigger=" + trigger.label());
        }
        return new Rejection("ackCode=" + code + " state=" + state + " 不在准入路径上");
    }

    private record Rejection(String reason) {
    }

    private OfferResult findExisting(Connection conn, String storageKey) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT " + QUEUE_COLUMNS + " FROM redelivery_queue WHERE idempotency_key = ?")) {
            ps.setString(1, storageKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return OfferResult.duplicated(readEntry(rs));
            }
        }
    }

    private OfferResult readAfterConstraint(Connection conn, String storageKey) throws SQLException {
        return findExisting(conn, storageKey);
    }

    private long insertNewEntry(Connection conn, long ledgerId, String storageKey, String batchId,
                                EnqueueTrigger trigger, long nextAttemptAtMs) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO redelivery_queue (ledger_id, idempotency_key, batch_id, status, "
                        + "attempt, next_attempt_at_ms, last_error, enqueue_reason) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                new String[]{"id"})) {
            ps.setLong(1, ledgerId);
            ps.setString(2, storageKey);
            ps.setString(3, batchId);
            ps.setString(4, RedeliveryStatus.QUEUED.dbValue());
            ps.setInt(5, 0);
            ps.setLong(6, nextAttemptAtMs);
            ps.setString(7, "");
            ps.setString(8, trigger.label());
            if (ps.executeUpdate() != 1) {
                throw new SQLException("INSERT redelivery_queue 影响行数不为 1");
            }
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("INSERT redelivery_queue 无返回主键");
                }
                return keys.getLong(1);
            }
        }
    }

    // =====================================================================
    // claimNextDue — 原子出队
    // =====================================================================

    @Override
    public Optional<RedeliveryEntry> claimNextDue(long nowMs) throws SQLException {
        if (nowMs < 0) {
            throw new IllegalArgumentException("nowMs 不得为负: " + nowMs);
        }
        try (Connection conn = connections.open()) {
            boolean previous = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                // 关键原子性：单条 UPDATE...LIMIT 1。
                // PG 严格支持 LIMIT；H2 PG 兼容模式同样支持。但 PG/H2 的「ORDER BY + LIMIT」
                // 在没有子查询包裹时行为略有差异；为保险起见改为先取 id 再 UPDATE。
                long claimedId;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT id FROM redelivery_queue "
                                + "WHERE status = 'QUEUED' AND next_attempt_at_ms <= ? "
                                + "ORDER BY next_attempt_at_ms ASC, id ASC "
                                + "LIMIT 1 FOR UPDATE")) {
                    ps.setLong(1, nowMs);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            conn.commit();
                            return Optional.empty();
                        }
                        claimedId = rs.getLong(1);
                    }
                }

                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE redelivery_queue SET status = ?, consumed_at = ?, "
                                + "last_attempt_at = ? WHERE id = ? AND status = ?")) {
                    ps.setString(1, RedeliveryStatus.CONSUMED.dbValue());
                    ps.setTimestamp(2, Timestamp.from(Instant.ofEpochMilli(nowMs)));
                    ps.setTimestamp(3, Timestamp.from(Instant.ofEpochMilli(nowMs)));
                    ps.setLong(4, claimedId);
                    ps.setString(5, RedeliveryStatus.QUEUED.dbValue());
                    int updated = ps.executeUpdate();
                    if (updated != 1) {
                        // 极少发生（与 SELECT FOR UPDATE 同事务被别人抢了），但仍要查清：
                        // SELECT FOR UPDATE 在 H2 PG 兼容模式下行为不完全等同于 PG，
                        // 因此这条 UPDATE 仍可能拿不到行（拿到 0 → 显式空）。
                        conn.rollback();
                        return Optional.empty();
                    }
                }

                Optional<RedeliveryEntry> entry = readById(conn, claimedId);
                if (entry.isEmpty()) {
                    conn.rollback();
                    throw new SQLException("刚 UPDATE 出去的队列条目立即查不到（id=" + claimedId
                            + "），拒绝继续：调度器拿不到东西");
                }
                conn.commit();
                return entry;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(previous);
            }
        }
    }

    // =====================================================================
    // settle & reschedule & releaseToQueued
    // =====================================================================

    @Override
    public void settle(long queueId, RedeliveryStatus next, int attempt, String lastError, long nowMs)
            throws SQLException {
        Objects.requireNonNull(next, "next 不得为 null");
        Objects.requireNonNull(lastError, "lastError 不得为 null");
        if (queueId <= 0) {
            throw new IllegalArgumentException("queueId 必须为正数，实际=" + queueId);
        }
        if (attempt < 0) {
            throw new IllegalArgumentException("attempt 不得为负: " + attempt);
        }
        if (next == RedeliveryStatus.QUEUED || next == RedeliveryStatus.CONSUMED) {
            // settle() 只允许推进到终态；回 QUEUED 走 releaseToQueued
            throw new IllegalArgumentException("settle() 不接受 " + next.dbValue()
                    + "，推进到 QUEUED 走 releaseToQueued()、推进到 CONSUMED 走 claimNextDue()");
        }
        try (Connection conn = connections.open()) {
            boolean previous = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE redelivery_queue SET status = ?, attempt = ?, last_error = ?, "
                                + "last_attempt_at = ? WHERE id = ? AND status = ?")) {
                    ps.setString(1, next.dbValue());
                    ps.setInt(2, attempt);
                    ps.setString(3, lastError);
                    ps.setTimestamp(4, Timestamp.from(Instant.ofEpochMilli(nowMs)));
                    ps.setLong(5, queueId);
                    ps.setString(6, RedeliveryStatus.CONSUMED.dbValue());
                    int updated = ps.executeUpdate();
                    if (updated != 1) {
                        conn.rollback();
                        throw new SQLException("settle: 红黑轮转未完成（queueId=" + queueId
                                + " status!=CONSUMED）—— 调度器拿走的条目状态被外部改了");
                    }
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(previous);
            }
        }
    }

    @Override
    public void reschedule(long queueId, int attempt, long nextAttemptAtMs) throws SQLException {
        reschedule(queueId, attempt, nextAttemptAtMs, null);
    }

    /**
     * {@code lastError} 传 {@code null} 表示<b>不覆盖</b> {@code last_error}（三参重载的旧语义）；
     * 传非 {@code null} 则与 {@code attempt} / {@code next_attempt_at_ms} 同一条 UPDATE 一起写入。
     */
    @Override
    public void reschedule(long queueId, int attempt, long nextAttemptAtMs, String lastError)
            throws SQLException {
        if (queueId <= 0) {
            throw new IllegalArgumentException("queueId 必须为正数，实际=" + queueId);
        }
        if (attempt < 0) {
            throw new IllegalArgumentException("attempt 不得为负: " + attempt);
        }
        if (nextAttemptAtMs < 0) {
            throw new IllegalArgumentException("nextAttemptAtMs 不得为负: " + nextAttemptAtMs);
        }
        if (lastError != null && lastError.length() > MAX_LAST_ERROR) {
            throw new IllegalArgumentException(
                    "lastError 不得超过 " + MAX_LAST_ERROR + " 字符（与 V5 schema 的 VARCHAR(512) 对齐）");
        }
        String sql = lastError == null
                ? "UPDATE redelivery_queue SET attempt = ?, next_attempt_at_ms = ?, "
                        + "last_attempt_at = ? WHERE id = ? AND status = ?"
                : "UPDATE redelivery_queue SET attempt = ?, next_attempt_at_ms = ?, "
                        + "last_attempt_at = ?, last_error = ? WHERE id = ? AND status = ?";
        try (Connection conn = connections.open()) {
            boolean previous = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    int idx = 1;
                    ps.setInt(idx++, attempt);
                    ps.setLong(idx++, nextAttemptAtMs);
                    ps.setTimestamp(idx++, Timestamp.from(Instant.now(clock)));
                    if (lastError != null) {
                        ps.setString(idx++, lastError);
                    }
                    ps.setLong(idx++, queueId);
                    ps.setString(idx, RedeliveryStatus.CONSUMED.dbValue());
                    int updated = ps.executeUpdate();
                    if (updated != 1) {
                        // 「重排」是 settle 的伴侣：CONSUMED → 下次可被出队仍需走 releaseToQueued。
                        // 这里故意拒绝 QUEUED 行重排——避免退避变得没数。
                        conn.rollback();
                        throw new SQLException("reschedule: 改到 status='CONSUMED' 的行失败（id="
                                + queueId + "）。CONSUMED 必须先 settle 或 release 回 QUEUED。");
                    }
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(previous);
            }
        }
    }

    @Override
    public void releaseToQueued(long queueId, long nextAttemptAtMs) throws SQLException {
        if (queueId <= 0) {
            throw new IllegalArgumentException("queueId 必须为正数，实际=" + queueId);
        }
        if (nextAttemptAtMs < 0) {
            throw new IllegalArgumentException("nextAttemptAtMs 不得为负: " + nextAttemptAtMs);
        }
        try (Connection conn = connections.open()) {
            boolean previous = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE redelivery_queue SET status = ?, consumed_at = ?, "
                                + "next_attempt_at_ms = ?, last_attempt_at = ? "
                                + "WHERE id = ? AND status IN ('CONSUMED', 'PERMANENT_FAILED')")) {
                    ps.setString(1, RedeliveryStatus.QUEUED.dbValue());
                    if (ps.getParameterMetaData().getParameterCount() < 3) {
                        throw new IllegalStateException("参数索引异常");
                    }
                    // consumed_at 在 QUEUED 状态下应为 NULL
                    ps.setNull(2, Types.TIMESTAMP);
                    ps.setLong(3, nextAttemptAtMs);
                    ps.setTimestamp(4, Timestamp.from(Instant.now(clock)));
                    ps.setLong(5, queueId);
                    int updated = ps.executeUpdate();
                    if (updated != 1) {
                        conn.rollback();
                        throw new SQLException("releaseToQueued: 行不在 CONSUMED/PERMANENT_FAILED 状态（id="
                                + queueId + "）—— QUEUED 已出队过、ACK_CONFIRMED 不允许再放回");
                    }
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(previous);
            }
        }
    }

    @Override
    public void manualReleaseToQueued(long queueId, long nextAttemptAtMs) throws SQLException {
        if (queueId <= 0) {
            throw new IllegalArgumentException("queueId 必须为正数，实际=" + queueId);
        }
        if (nextAttemptAtMs < 0) {
            throw new IllegalArgumentException("nextAttemptAtMs 不得为负: " + nextAttemptAtMs);
        }
        try (Connection conn = connections.open()) {
            boolean previous = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE redelivery_queue SET status = ?, attempt = 0, consumed_at = ?, "
                                + "next_attempt_at_ms = ?, last_attempt_at = ? "
                                + "WHERE id = ? AND status = 'PERMANENT_FAILED'")) {
                    ps.setString(1, RedeliveryStatus.QUEUED.dbValue());
                    ps.setNull(2, Types.TIMESTAMP);
                    ps.setLong(3, nextAttemptAtMs);
                    ps.setTimestamp(4, Timestamp.from(Instant.now(clock)));
                    ps.setLong(5, queueId);
                    int updated = ps.executeUpdate();
                    if (updated != 1) {
                        conn.rollback();
                        throw new SQLException("manualReleaseToQueued: 行不在 PERMANENT_FAILED 状态（id="
                                + queueId + "）—— 仅 PERMANENT_FAILED 可由人工放行回 QUEUED");
                    }
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(previous);
            }
        }
    }

    // =====================================================================
    // 读视图
    // =====================================================================

    @Override
    public List<RedeliveryEntry> listByLedger(long ledgerId) throws SQLException {
        if (ledgerId <= 0) {
            throw new IllegalArgumentException("ledgerId 必须为正数，实际=" + ledgerId);
        }
        try (Connection conn = connections.open();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT " + QUEUE_COLUMNS + " FROM redelivery_queue "
                             + "WHERE ledger_id = ? ORDER BY id ASC")) {
            ps.setLong(1, ledgerId);
            try (ResultSet rs = ps.executeQuery()) {
                List<RedeliveryEntry> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(readEntry(rs));
                }
                return List.copyOf(out);
            }
        }
    }

    @Override
    public long queuedCount() throws SQLException {
        try (Connection conn = connections.open();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM redelivery_queue WHERE status = 'QUEUED'")) {
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return 0;
                }
                return rs.getLong(1);
            }
        }
    }

    private Optional<RedeliveryEntry> readById(Connection conn, long id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT " + QUEUE_COLUMNS + " FROM redelivery_queue WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(readEntry(rs));
            }
        }
    }

    private static RedeliveryEntry readEntry(ResultSet rs) throws SQLException {
        Timestamp consumed = rs.getTimestamp("consumed_at");
        Instant consumedInstant = consumed == null ? null : consumed.toInstant();
        return new RedeliveryEntry(
                rs.getLong("id"),
                rs.getLong("ledger_id"),
                rs.getString("idempotency_key"),
                rs.getString("batch_id"),
                RedeliveryStatus.fromDbValue(rs.getString("status")),
                rs.getInt("attempt"),
                rs.getLong("next_attempt_at_ms"),
                rs.getString("last_error"),
                rs.getString("enqueue_reason"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("last_attempt_at").toInstant(),
                consumedInstant);
    }

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
