package cn.hospital.region.archive.ledger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 台账的 JDBC 实现 —— {@code message_ledger} + {@code message_audit} 的唯一写入口。
 *
 * <h2>核心保证（本类存在的全部理由）</h2>
 * <ol>
 *   <li><b>幂等</b>：{@link #insertIfAbsent} 先查后写 + 数据库唯一约束 {@code uk_ledger_idem_key}
 *       双保险；唯一约束冲突在此<b>翻译成</b> {@link InsertOutcome.AlreadyProcessed} 返回给调用方，
 *       调用方永远不需要认识 SQLState。</li>
 *   <li><b>状态机是唯一写路径</b>：{@link #transition} 在开事务前先过
 *       {@link MessageStateMachine#assertTransition}，非法边一条数据都写不进去。</li>
 *   <li><b>状态与审计同事务</b>：{@code UPDATE message_ledger} 与 {@code INSERT message_audit}
 *       在同一个事务里，任一步失败整体回滚 —— 不会出现「状态变了但审计没留」或反之。</li>
 *   <li><b>来源态守卫</b>：UPDATE 带 {@code WHERE id = ? AND state = ?}，影响行数 0 即
 *       {@link LedgerStateConflictException}，防止拿着过期状态推进。</li>
 *   <li><b>容量闸</b>：{@link RawPayloadPolicy} 在入库<b>之前</b>截断超长报文并记 WARNING，
 *       超硬上限直接拒收 —— 不让「value too long for column」把整批报文拖下水。</li>
 * </ol>
 *
 * <h2>事务边界由本类持有，不由调用方持有</h2>
 * <p>{@link ConnectionProvider} 只负责「给一条连接」；{@code setAutoCommit(false)}、commit、
 * rollback、还原 autoCommit、关连接，全部在本类的方法内成对完成。这样调用方无法写出
 * 「自己 commit 了但审计还没写」这种半截状态。代价是每次调用一条连接 —— 接入主路径
 * 每条报文本来就自成一个事务，这个粒度是对的；连接池留给 T31 的 HTTP 客户端同批处理。
 *
 * <h2>SQL 全部参数绑定</h2>
 * <p>报文里的控制号、批次号、操作人都是外部来的字符串。V1 脚本里出现过
 * {@code 'MSH|^~\&|...'} 这类含字段分隔符的字面量，一旦有人改用字符串拼接就会被转义坑到；
 * 本类一律 PreparedStatement，不留拼接出口。
 *
 * @see LedgerAuditChain 「绕过状态机直接 UPDATE state」怎么被查出来
 * @see AuditWriter 审计写入为什么是可注入接口（为了让「审计写失败 → 整体回滚」可测）
 */
public final class JdbcLedgerStore implements LedgerStore, LedgerReadView {

    private static final Logger LOG = Logger.getLogger(JdbcLedgerStore.class.getName());

    /**
     * 唯一约束冲突的 SQLState —— PostgreSQL 用 {@code 23505}，H2 在 PG 兼容模式下同样返回
     * {@code 23505}。只认这个码，<b>不</b>去匹配异常消息的字面量（两库措辞不同，
     * 且不同驱动版本会变；按消息匹配是这类代码最常见的移植性 bug）。
     */
    private static final String SQLSTATE_UNIQUE_VIOLATION = "23505";

    /** 台账行的只读列；{@link #readRows} 与所有查询共用一份列序，改动时只改这一处。 */
    private static final String LEDGER_COLUMNS =
            "id, message_type, control_id, processing_id, batch_id, state, raw_payload, "
                    + "local_mrn, degraded, retry_count, version, created_at, updated_at";

    /** 提供数据库连接。测试传一个复用 H2 连接的 lambda，生产传开新连接的 lambda。 */
    @FunctionalInterface
    public interface ConnectionProvider {
        /** 交出一条可用连接；<b>连接所有权归调用方</b>（本类负责关）。 */
        Connection open() throws SQLException;
    }

    private final ConnectionProvider connections;
    private final Clock clock;
    private final AuditWriter auditWriter;

    /** 生产构造：系统时钟 + 真实审计写入器。 */
    public JdbcLedgerStore(ConnectionProvider connections) {
        this(connections, Clock.systemDefaultZone(), AuditWriter.jdbc(Clock.systemDefaultZone()));
    }

    /**
     * 全参构造。
     *
     * @param clock       时间源；测试传 {@code Clock.fixed(...)} 让 created_at / updated_at / at 可复现
     * @param auditWriter 审计写入器；测试传会抛异常的 lambda 来验证整体回滚
     */
    public JdbcLedgerStore(ConnectionProvider connections, Clock clock, AuditWriter auditWriter) {
        this.connections = Objects.requireNonNull(connections, "连接提供者不得为 null");
        this.clock = Objects.requireNonNull(clock, "clock 不得为 null");
        this.auditWriter = Objects.requireNonNull(auditWriter, "auditWriter 不得为 null");
    }

    // =====================================================================
    // insertIfAbsent
    // =====================================================================

    @Override
    public InsertOutcome insertIfAbsent(LedgerEntry entry) throws SQLException {
        Objects.requireNonNull(entry, "待入账报文不得为 null");
        IdempotencyKey key = entry.key();
        Instant now = clock.instant();

        // 容量闸在开事务之前：超硬上限的报文连「要不要开连接」都不该走到，
        // 更不该在事务里回滚一次再把异常抛出去。
        RawPayloadPolicy.Result payload = RawPayloadPolicy.apply(entry.rawPayload());

        Connection conn = connections.open();
        boolean previousAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            long id = doInsert(conn, entry, payload, now);
            conn.commit();
            LOG.log(Level.FINE, "台账入账: ledgerId={0} {1}", new Object[]{id, key});
            if (payload.truncated()) {
                // WARNING 而不是 FINE：截断意味着「台账里这行的尾部不是原文」，
                // 排障的人必须先看见它再去看数据。日志只落长度与主键，绝不落报文内容。
                // 长度传 String 而不是 int：java.util.logging 走 MessageFormat，
                // 传 int 会被按 locale 加千分位（1,048,576），机器 grep 不到、换个
                // locale 还变成 1.048.576。同一行里两种写法并存时，排障的人无从判断哪个能 grep。
                LOG.log(Level.WARNING,
                        "原始报文超出入库上限已截断: ledgerId={0} batchId={1} controlId={2} "
                                + "原始={3}字符 入库={4}字符，尾部未入库，需要该段请回源重取",
                        new Object[]{id, entry.batchId(), key.controlId(),
                                String.valueOf(payload.originalLength()),
                                String.valueOf(payload.storedLength())});
            }
            return new InsertOutcome.Inserted(id, key, MessageState.RECEIVED);
        } catch (SQLException e) {
            // 唯一约束冲突时 PostgreSQL 会把事务置为 aborted，必须先 rollback 才能再查
            safeRollback(conn);
            if (isUniqueViolation(e)) {
                InsertOutcome existing = lookupExisting(conn, key);
                // 重复到达是常态不是错误：INFO 级一行日志，不打 WARNING，避免刷屏
                LOG.log(Level.INFO, "幂等键已存在，按已处理返回: ledgerId={0} {1}",
                        new Object[]{existing.ledgerId(), key});
                return existing;
            }
            throw e;
        } catch (RuntimeException e) {
            safeRollback(conn);
            throw e;
        } finally {
            restoreAutoCommit(conn, previousAutoCommit);
            closeQuietly(conn);
        }
    }

    /** 真正的 INSERT；调用方已开启事务。唯一约束冲突时把 {@link SQLException} 往上抛。 */
    private long doInsert(Connection conn, LedgerEntry entry, RawPayloadPolicy.Result payload,
                          Instant now)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO message_ledger "
                        + "(message_type, control_id, processing_id, batch_id, state, raw_payload, "
                        + " local_mrn, degraded, retry_count, created_at, updated_at, version) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)",
                new String[]{"ID"})) {
            IdempotencyKey key = entry.key();
            ps.setString(1, key.messageType());
            ps.setString(2, key.controlId());
            ps.setString(3, key.processingId());
            ps.setString(4, entry.batchId());
            ps.setString(5, MessageState.RECEIVED.dbValue());
            ps.setString(6, payload.storedPayload());
            ps.setString(7, entry.localMrn());
            ps.setBoolean(8, entry.degraded());
            ps.setInt(9, entry.retryCount());
            ps.setTimestamp(10, Timestamp.from(now));
            ps.setTimestamp(11, Timestamp.from(now));
            ps.executeUpdate();

            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("INSERT 成功但未返回自增主键，无法定位台账行");
                }
                return keys.getLong(1);
            }
        }
    }

    /**
     * 唯一约束冲突后反查原行，返回 {@link InsertOutcome.AlreadyProcessed}。
     *
     * <p>查不到行是<b>数据不一致</b>（唯一约束说有行、SELECT 说没有），此时必须抛而不是
     * 返回一个假的「已处理」——返回假结果会让调用方跳过业务处理并回一条 AA，
     * 等于静默丢报文。
     */
    private InsertOutcome lookupExisting(Connection conn, IdempotencyKey key) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, state FROM message_ledger "
                        + "WHERE message_type = ? AND control_id = ? AND processing_id = ?")) {
            ps.setString(1, key.messageType());
            ps.setString(2, key.controlId());
            ps.setString(3, key.processingId());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("唯一约束 " + SQLSTATE_UNIQUE_VIOLATION
                            + " 已触发但按幂等键查不到原行 —— 台账数据不一致，拒绝返回假「已处理」: " + key);
                }
                return new InsertOutcome.AlreadyProcessed(rs.getLong("id"), key,
                        MessageState.fromDbValue(rs.getString("state")));
            }
        }
    }

    /**
     * 判断一个 {@link SQLException}（含其 cause 链）是否是唯一约束冲突。
     *
     * <p>先看 SQLState {@code 23505}；PG 与 H2 PG 模式一致。若驱动没填 SQLState，
     * 退一步看是不是 {@link SQLIntegrityConstraintViolationException} —— 有些驱动把唯一冲突
     * 也归到这一类，此时按「有唯一约束名才认」的宽松口径放过，避免把非空约束/外键冲突
     * 误判成幂等命中（那会把真正的数据缺陷当成「已处理」吞掉）。
     */
    private static boolean isUniqueViolation(SQLException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException se) {
                if (SQLSTATE_UNIQUE_VIOLATION.equals(se.getSQLState())) {
                    return true;
                }
                if (se instanceof SQLIntegrityConstraintViolationException
                        && containsUniqueConstraintHint(se.getMessage())) {
                    return true;
                }
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    /** 无 SQLState 时的兜底判据：消息里点名了唯一约束。 */
    private static boolean containsUniqueConstraintHint(String message) {
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("uk_ledger_idem_key")
                || lower.contains("unique constraint")
                || lower.contains("unique index");
    }

    // =====================================================================
    // findByIdempotencyKey
    // =====================================================================

    @Override
    public Optional<LedgerRow> findByIdempotencyKey(IdempotencyKey key) throws SQLException {
        Objects.requireNonNull(key, "幂等键不得为 null");
        Connection conn = connections.open();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT " + LEDGER_COLUMNS + " FROM message_ledger "
                        + "WHERE message_type = ? AND control_id = ? AND processing_id = ?")) {
            ps.setString(1, key.messageType());
            ps.setString(2, key.controlId());
            ps.setString(3, key.processingId());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(toRow(rs)) : Optional.empty();
            }
        } finally {
            closeQuietly(conn);
        }
    }

    // =====================================================================
    // transition —— 状态机 + 事务内状态/审计原子写入
    // =====================================================================

    @Override
    public TransitionResult transition(long id, MessageState from, MessageState to,
                                       String operator, String reason)
            throws SQLException, IllegalStateTransitionException, LedgerStateConflictException {
        try {
            return doTransition(id, from, to, null, operator, reason, null);
        } catch (ConcurrentLedgerUpdateException e) {
            // 不可能发生：expectedVersion 为 null 时 updateLedgerRow 的两处分诊都不会走并发分支。
            // 真走到这里说明守卫实现被改坏了；宁可在这里炸掉，也不要让调用方以为
            // 「不带版本守卫的那条路径也有乐观锁」——那是会掩盖真实并发窗口的假安全感。
            throw new IllegalStateException("无版本守卫的 transition 路径抛出了并发异常，版本守卫实现有误", e);
        }
    }

    @Override
    public TransitionResult transition(long id, MessageState from, MessageState to,
                                       long expectedVersion, String operator, String reason)
            throws SQLException, IllegalStateTransitionException, LedgerStateConflictException,
            ConcurrentLedgerUpdateException {
        return doTransition(id, from, to, Long.valueOf(expectedVersion), operator, reason, null);
    }

    @Override
    public TransitionResult claimLanding(long id, long expectedVersion, String operator,
                                         String reason, String localMrn)
            throws SQLException, IllegalStateTransitionException, LedgerStateConflictException,
            ConcurrentLedgerUpdateException {
        requireNonBlank(localMrn, "localMrn");
        return doTransition(id, MessageState.PENDING_CONFIRM, MessageState.LANDED,
                Long.valueOf(expectedVersion), operator, reason, localMrn);
    }

    @Override
    public TransitionResult releaseLanding(long id, long expectedVersion, String operator,
                                           String reason)
            throws SQLException, IllegalStateTransitionException, LedgerStateConflictException,
            ConcurrentLedgerUpdateException {
        // 空串 = 把 local_mrn 清回「这份映射还没写成功」；null 才是「本次不碰这一列」
        return doTransition(id, MessageState.LANDED, MessageState.PENDING_CONFIRM,
                Long.valueOf(expectedVersion), operator, reason, "");
    }

    /**
     * 三个写入口（{@code transition} 两个重载 + {@link #claimLanding} / {@link #releaseLanding}）
     * 的共同实现。
     *
     * @param expectedVersion {@code null} = 不带版本守卫（只靠 state 收敛）；
     *                          非空 = {@code WHERE ... AND version = ?} 的乐观锁 CAS
     * @param localMrn       {@code null} = 本次迁移不碰 {@code local_mrn} 列（普通状态迁移）；
     *                       非空 = 同一条 UPDATE 里顺带把院内主键记进该列（落地认领）
     */
    private TransitionResult doTransition(long id, MessageState from, MessageState to,
                                          Long expectedVersion, String operator, String reason,
                                          String localMrn)
            throws SQLException, IllegalStateTransitionException, LedgerStateConflictException,
            ConcurrentLedgerUpdateException {

        // ---- 第 1 步：状态机断言，在开事务之前 ----
        // 放在最前面的理由：非法边是业务规则违反，连连接都不该开，更不该写任何一行数据。
        MessageStateMachine.assertTransition(from, to);
        requireNonBlank(operator, "operator");
        requireNonBlank(reason, "reason");

        Instant now = clock.instant();

        Connection conn = connections.open();
        boolean previousAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            // ---- 第 2 步：更新台账行，带来源态守卫（+ 可选的 version 守卫）----
            long previousVersion =
                    updateLedgerRow(conn, id, from, to, expectedVersion, now, localMrn);

            // ---- 第 3 步：同事务写审计；失败则整体回滚 ----
            auditWriter.write(conn, id, from, to, operator, reason);

            // ---- 第 4 步：两边都成功才提交 ----
            conn.commit();

            TransitionResult result = new TransitionResult(id, from, to,
                    previousVersion, previousVersion + 1, operator, reason, now);
            LOG.log(Level.FINE, "状态迁移: {0}", result.toLogLine());
            return result;
        } catch (SQLException | RuntimeException e) {
            safeRollback(conn);
            throw e;
        } finally {
            restoreAutoCommit(conn, previousAutoCommit);
            closeQuietly(conn);
        }
    }

    /**
     * {@code UPDATE message_ledger ... WHERE id = ? AND state = ? [AND version = ?]}；
     * 返回迁移前的 version。
     *
     * <p><b>为什么要 version 这一层</b>：事务内的 {@code SELECT} 与随后的 {@code UPDATE} 之间
     * 存在窗口 —— 两个线程可能都读到 {@code state = RECEIVED, version = 0}，然后都提交
     * {@code WHERE state = 'RECEIVED'} 的 UPDATE。两次都命中，后写的覆盖先写的，
     * 而调用方以为「只有我改过这行」。加上 {@code AND version = ?} 之后这段窗口里第二次
     * UPDATE 的影响行数为 0，被数据库直接挡下；这正是 CAS，不依赖 JVM 内的锁
     * （本进程可能同时有接入线程、确认门请求与重投调度器三个写入来源）。
     *
     * <p><b>为什么先查 version 再查 state</b>：任何一次 {@code state} 变更都会让 {@code version}
     * 自增，所以「version 对得上」蕴含「state 也对得上」—— version 是<b>严格更强</b>的检查。
     * 反过来不成立：state 变了 version 一定变，而 version 变了 state 可能没变
     * （例如 {@code PENDING_CONFIRM → PENDING_CONFIRM} 这条合法自环边）。
     * 因此先看 version 有两个好处：
     * <ol>
     *   <li>「两个调用方读到同一份 version」这个最典型的并发局面报的是
     *       {@link ConcurrentLedgerUpdateException} 而不是被 state 冲突盖住 ——
     *       调用方据此知道该<b>重读 version</b>；</li>
     *   <li>state 不符但 version 相符，只可能意味着有人<b>绕过状态机裸改了 state</b>
     *       （迁移路径每次都自增 version），这时报 {@link LedgerStateConflictException}
     *       才是对的诊断 —— 它正是运维排障要找的那种数据不一致。</li>
     * </ol>
     */
    private long updateLedgerRow(Connection conn, long id, MessageState from, MessageState to,
                                 Long expectedVersion, Instant now, String localMrn)
            throws SQLException, LedgerStateConflictException, ConcurrentLedgerUpdateException {
        long previousVersion;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT state, version FROM message_ledger WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new LedgerStateConflictException(id, from, null, false);
                }
                MessageState actual = MessageState.fromDbValue(rs.getString("state"));
                previousVersion = rs.getLong("version");
                // 先 version 后 state，理由见本方法 javadoc
                if (expectedVersion != null && previousVersion != expectedVersion) {
                    // 乐观锁：调用方拿的是一份过期的 version，说明这行在它读之后被推进过
                    safeRollback(conn);
                    throw new ConcurrentLedgerUpdateException(id, expectedVersion,
                            previousVersion, actual);
                }
                if (actual != from) {
                    // 来源态不符时必须回滚，不能带着这条 UPDATE 继续走
                    safeRollback(conn);
                    throw new LedgerStateConflictException(id, from, actual, true);
                }
            }
        }

        // local_mrn 与状态在**同一条 UPDATE**里落：认领落地与记下院内主键必须原子。
        // 分成两条语句的话，第二条失败会留下一行「已落地但没记住主键」的记录，
        // 下次同目标重确认就判断不出该不该重写 —— 恰好是最需要判断的那种场景。
        StringBuilder sql = new StringBuilder(
                "UPDATE message_ledger SET state = ?, version = version + 1, updated_at = ?");
        if (localMrn != null) {
            sql.append(", local_mrn = ?");
        }
        sql.append(" WHERE id = ? AND state = ?");
        if (expectedVersion != null) {
            sql.append(" AND version = ?");
        }
        int updated;
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            ps.setString(idx++, to.dbValue());
            ps.setTimestamp(idx++, Timestamp.from(now));
            if (localMrn != null) {
                ps.setString(idx++, localMrn);
            }
            ps.setLong(idx++, id);
            ps.setString(idx++, from.dbValue());
            if (expectedVersion != null) {
                ps.setLong(idx, expectedVersion);
            }
            updated = ps.executeUpdate();
        }
        if (updated != 1) {
            // SELECT 与 UPDATE 之间被并发方改了（state 或 version 都可能）。分诊一次再抛，
            // 本次不覆盖对方的写入。分诊顺序与上面保持一致：先看 version。
            Long actualVersion = readVersionQuietly(conn, id);
            MessageState actual = readStateQuietly(conn, id);
            safeRollback(conn);
            if (expectedVersion != null && actualVersion != null
                    && actualVersion != expectedVersion) {
                throw new ConcurrentLedgerUpdateException(id, expectedVersion, actualVersion, actual);
            }
            throw new LedgerStateConflictException(id, from, actual, true);
        }
        return previousVersion;
    }

    // =====================================================================
    // listByBatch / countByState
    // =====================================================================

    @Override
    public List<LedgerRow> listByBatch(String batchId, MessageState state) throws SQLException {
        requireNonBlank(batchId, "batchId");
        // state 为 null 表示不限状态；两种分支都走 idx_ledger_batch_state
        String sql = "SELECT " + LEDGER_COLUMNS + " FROM message_ledger WHERE batch_id = ?"
                + (state == null ? "" : " AND state = ?")
                + " ORDER BY id ASC";
        Connection conn = connections.open();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, batchId);
            if (state != null) {
                ps.setString(2, state.dbValue());
            }
            try (ResultSet rs = ps.executeQuery()) {
                List<LedgerRow> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(toRow(rs));
                }
                return rows;
            }
        } finally {
            closeQuietly(conn);
        }
    }

    @Override
    public Map<MessageState, Long> countByState(String batchId) throws SQLException {
        requireNonBlank(batchId, "batchId");
        // 预填全部状态为 0：「这一批没有拒收条目」也是一条要显式出现在报表上的结论，
        // 不能因为 map 里缺这个 key 就从分布表上消失
        Map<MessageState, Long> counts = new EnumMap<>(MessageState.class);
        for (MessageState s : MessageState.values()) {
            counts.put(s, 0L);
        }
        Connection conn = connections.open();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT state, COUNT(*) FROM message_ledger WHERE batch_id = ? GROUP BY state")) {
            ps.setString(1, batchId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    counts.put(MessageState.fromDbValue(rs.getString(1)), rs.getLong(2));
                }
            }
            return counts;
        } finally {
            closeQuietly(conn);
        }
    }

    @Override
    public List<LedgerRow> findByStates(java.util.Set<MessageState> states) throws SQLException {
        if (states == null || states.isEmpty()) {
            return List.of();
        }
        // 不传 null 元素进 SQL；空集已在上方挡掉。
        for (MessageState s : states) {
            Objects.requireNonNull(s, "状态集合元素不得为 null");
        }

        // 动态拼 IN (?,?,...) 占位符；状态集合大小受 8 个枚举限死，
        // 不存在「外部控制的爆炸性 N」攻击面，拼接前只做长度上限卡死。
        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < states.size(); i++) {
            if (i > 0) {
                placeholders.append(", ");
            }
            placeholders.append("?");
        }
        String sql = "SELECT " + LEDGER_COLUMNS + " FROM message_ledger "
                + "WHERE state IN (" + placeholders + ") ORDER BY id ASC";
        Connection conn = connections.open();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int i = 1;
            for (MessageState s : states) {
                ps.setString(i++, s.dbValue());
            }
            try (ResultSet rs = ps.executeQuery()) {
                List<LedgerRow> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(toRow(rs));
                }
                return out;
            }
        } finally {
            closeQuietly(conn);
        }
    }

    // =====================================================================
    // LedgerReadView：批次统计与只读导出（T19）
    // =====================================================================
    //
    //  下面三个查询与上面两个共用同一份连接管理与同一套「SQL 全参数绑定」纪律，
    //  不另开类、不另开连接池：读面与写面分接口是为了「导出路径上没有写方法」，
    //  不是为了两套实现。

    /**
     * 待确认条目里模型降级的条数 —— T19 报表上的「低置信待确认」。
     *
     * <p>走 {@code idx_ledger_batch_state(batch_id, state)}：state 定死为 PENDING_CONFIRM，
     * degraded 作为回表过滤，不是 {@code WHERE batch_id=? AND degraded=true}（那会让
     * 四态全批量的降级条目混进「待确认」的口径里）。
     */
    @Override
    public long countDegradedPending(String batchId) throws SQLException {
        requireNonBlank(batchId, "batchId");
        Connection conn = connections.open();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM message_ledger WHERE batch_id = ? AND state = ? AND degraded = TRUE")) {
            ps.setString(1, batchId);
            ps.setString(2, MessageState.PENDING_CONFIRM.dbValue());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } finally {
            closeQuietly(conn);
        }
    }

    /**
     * 拒收原因分布：本地原因码 → 条数，按条数降序、同条数按码字典序。
     *
     * <p>取 {@code message_audit} 里 {@code to_state = 'REJECTED'} 的迁移行并剥掉
     * {@code rejected:} 前缀。一条报文只能从 {@code RECEIVED → REJECTED} 走到拒收，
     * 因此每行最多命中一条审计行，不存在「同一条被统计两次」的情形。
     */
    @Override
    public Map<String, Long> rejectionReasonCounts(String batchId) throws SQLException {
        requireNonBlank(batchId, "batchId");
        Map<String, Long> counts = new TreeMap<>();
        Connection conn = connections.open();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT a.reason FROM message_audit a "
                        + "JOIN message_ledger l ON l.id = a.ledger_id "
                        + "WHERE l.batch_id = ? AND a.to_state = ?")) {
            ps.setString(1, batchId);
            ps.setString(2, MessageState.REJECTED.dbValue());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    counts.merge(stripReasonPrefix(rs.getString(1)), 1L, Long::sum);
                }
            }
        } finally {
            closeQuietly(conn);
        }
        // 条数降序；同条数按码字典序 —— 同一批次两次出报表的顺序必须一致，否则 diff 没意义
        List<Map.Entry<String, Long>> ordered = new ArrayList<>(counts.entrySet());
        ordered.sort(Map.Entry.<String, Long>comparingByValue().reversed()
                .thenComparing(Map.Entry.comparingByKey()));
        Map<String, Long> sorted = new LinkedHashMap<>();
        ordered.forEach(e -> sorted.put(e.getKey(), e.getValue()));
        return sorted;
    }

    /**
     * 待办分页查询：按状态（可选再按批次）列行，<b>取最新若干条</b>。
     *
     * <p>与 {@link #listForExport} 同一套列与同一个标量子查询，两处 SQL 写法一致；
     * 差别只在 {@code ORDER BY id DESC LIMIT ?} —— 待办要的是「最新的那几条」，
     * 导出要的是「这一批的全部且升序」。<b>不共用一条 SQL</b>：把排序与分页做成参数会让
     * 导出那条最不能出错的路径多出两个可变量。
     */
    @Override
    public List<LedgerReadView.ExportRow> listMessages(MessageState state, String batchIdOrNull,
                                                       int limit) throws SQLException {
        int capped = limit <= 0 ? LedgerReadView.MAX_PAGE_SIZE : Math.min(limit, LedgerReadView.MAX_PAGE_SIZE);
        StringBuilder sql = new StringBuilder(EXPORT_SELECT_PREFIX);
        if (state != null || (batchIdOrNull != null && !batchIdOrNull.isBlank())) {
            sql.append(" WHERE ");
            if (state != null) {
                sql.append("l.state = ?");
            }
            if (state != null && batchIdOrNull != null && !batchIdOrNull.isBlank()) {
                sql.append(" AND ");
            }
            if (batchIdOrNull != null && !batchIdOrNull.isBlank()) {
                sql.append("l.batch_id = ?");
            }
        }
        sql.append(" ORDER BY l.id DESC LIMIT ?");
        Connection conn = connections.open();
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            if (state != null) {
                ps.setString(idx++, state.dbValue());
            }
            if (batchIdOrNull != null && !batchIdOrNull.isBlank()) {
                ps.setString(idx++, batchIdOrNull.trim());
            }
            ps.setInt(idx, capped);
            try (ResultSet rs = ps.executeQuery()) {
                List<LedgerReadView.ExportRow> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(readExportRow(rs));
                }
                return rows;
            }
        } finally {
            closeQuietly(conn);
        }
    }

    /** 导出投影查询：按批次（可选按状态）列行，并带上「这一行是被什么码拒收的」。
     *
     * <p>拒收码用<b>标量子查询</b>取而不是 JOIN：JOIN 会把非拒收行也复制一份
     * （LEFT JOIN 后 row 数 = 拒收审计行数），而导出最不能出的错就是行数对不上 ——
     * 信息员会拿 CSV 行数和台账条数对账。
     */
    @Override
    public List<LedgerReadView.ExportRow> listForExport(String batchId, MessageState state)
            throws SQLException {
        requireNonBlank(batchId, "batchId");
        String sql = EXPORT_SELECT_PREFIX + " WHERE l.batch_id = ?"
                + (state == null ? "" : " AND l.state = ?")
                + " ORDER BY l.id ASC";
        Connection conn = connections.open();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, batchId);
            if (state != null) {
                ps.setString(2, state.dbValue());
            }
            try (ResultSet rs = ps.executeQuery()) {
                List<LedgerReadView.ExportRow> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(readExportRow(rs));
                }
                return rows;
            }
        } finally {
            closeQuietly(conn);
        }
    }

    /** 导出/待办投影查询共用的 SELECT 前缀（含「这一行是被什么码拒收的」标量子查询）。 */
    private static final String EXPORT_SELECT_PREFIX = buildExportSelectPrefix();

    private static String buildExportSelectPrefix() {
        return "SELECT l.id, l.message_type, l.control_id, l.processing_id, l.batch_id, l.state, "
                + "l.local_mrn, l.degraded, l.retry_count, l.version, l.created_at, l.updated_at, "
                + "l.raw_payload, ("
                + "SELECT a.reason FROM message_audit a WHERE a.ledger_id = l.id "
                + "AND a.to_state = '" + MessageState.REJECTED.dbValue() + "' "
                + "ORDER BY a.at DESC, a.id DESC LIMIT 1) AS rejection_reason "
                + "FROM message_ledger l";
    }

    /** 投影结果集 → 投影行；两条查询共用，列序由 {@link #EXPORT_SELECT_PREFIX} 固定。 */
    private static LedgerReadView.ExportRow readExportRow(ResultSet rs) throws SQLException {
        Timestamp createdAt = rs.getTimestamp("created_at");
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        return new LedgerReadView.ExportRow(
                rs.getLong("id"),
                new IdempotencyKey(rs.getString("message_type"), rs.getString("control_id"),
                        rs.getString("processing_id")),
                rs.getString("batch_id"),
                MessageState.fromDbValue(rs.getString("state")),
                rs.getString("local_mrn"),
                rs.getBoolean("degraded"),
                rs.getInt("retry_count"),
                rs.getLong("version"),
                createdAt == null ? Instant.EPOCH : createdAt.toInstant(),
                updatedAt == null ? Instant.EPOCH : updatedAt.toInstant(),
                rs.getString("raw_payload"),
                stripReasonPrefix(rs.getString("rejection_reason")));
    }

    /** 剥掉 {@code rejected:} 前缀还原本地原因码；不匹配前缀时原样返回（不猜、不吞）。 */
    private static String stripReasonPrefix(String reason) {
        if (reason == null || reason.isBlank()) {
            return "";
        }
        String prefix = ArchiveIngestService.REASON_PREFIX_REJECTED;
        return reason.startsWith(prefix) ? reason.substring(prefix.length()) : reason;
    }

    // =====================================================================
    // 便捷入口（业务层与排障常用）
    // =====================================================================

    /** 按台账行主键读一行；不存在返回空。 */
    @Override
    public Optional<LedgerRow> findById(long id) throws SQLException {
        Connection conn = connections.open();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT " + LEDGER_COLUMNS + " FROM message_ledger WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(toRow(rs)) : Optional.empty();
            }
        } finally {
            closeQuietly(conn);
        }
    }

    /** 读一条台账行的当前状态；不存在返回 {@code null}。 */
    public Optional<MessageState> stateOf(long id) throws SQLException {
        Connection conn = connections.open();
        try {
            return Optional.ofNullable(readStateQuietly(conn, id));
        } finally {
            closeQuietly(conn);
        }
    }

    /**
     * 校验一行台账的审计链是否完整（{@link LedgerAuditChain} 的自开连接包装）。
     * 排障时先跑这个再出数：带一条断链报出来的四态分布是误导。
     */
    public LedgerAuditChain.Verdict inspectChain(long ledgerId) throws SQLException {
        Connection conn = connections.open();
        try {
            return LedgerAuditChain.inspect(conn, ledgerId);
        } finally {
            closeQuietly(conn);
        }
    }

    // =====================================================================
    // 内部工具
    // =====================================================================

    /** ResultSet → {@link LedgerRow}；列序与 {@link #LEDGER_COLUMNS} 一一对应。 */
    private static LedgerRow toRow(ResultSet rs) throws SQLException {
        Timestamp createdAt = rs.getTimestamp("created_at");
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        return new LedgerRow(
                rs.getLong("id"),
                new IdempotencyKey(rs.getString("message_type"), rs.getString("control_id"),
                        rs.getString("processing_id")),
                rs.getString("batch_id"),
                MessageState.fromDbValue(rs.getString("state")),
                rs.getString("raw_payload"),
                rs.getString("local_mrn"),
                rs.getBoolean("degraded"),
                rs.getInt("retry_count"),
                rs.getLong("version"),
                createdAt == null ? Instant.EPOCH : createdAt.toInstant(),
                updatedAt == null ? Instant.EPOCH : updatedAt.toInstant());
    }

    /** 读当前 state；行不存在或读取失败时返回 {@code null}（供冲突诊断用，不作为业务判定）。 */
    private static MessageState readStateQuietly(Connection conn, long id) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT state FROM message_ledger WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? MessageState.fromDbValue(rs.getString(1)) : null;
            }
        } catch (SQLException e) {
            LOG.log(Level.FINE, "回滚后读取 state 失败（仅影响诊断信息）", e);
            return null;
        }
    }

    /** 读当前 version；行不存在或读取失败时返回 {@code null}（同 {@link #readStateQuietly} 的定位）。 */
    private static Long readVersionQuietly(Connection conn, long id) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT version FROM message_ledger WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        } catch (SQLException e) {
            LOG.log(Level.FINE, "回滚后读取 version 失败（仅影响诊断信息）", e);
            return null;
        }
    }

    private static String requireNonBlank(String value, String field) {
        Objects.requireNonNull(value, field + " 不得为 null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " 不得为空串：每一次状态迁移都要能追到是谁做的、为什么");
        }
        return value;
    }

    private static void safeRollback(Connection conn) {
        try {
            if (!conn.getAutoCommit()) {
                conn.rollback();
            }
        } catch (SQLException e) {
            // 回滚失败说明连接已经不可用；继续抛原始异常更有诊断价值，这里只记一行
            LOG.log(Level.FINE, "回滚失败（连接可能已失效）", e);
        }
    }

    private static void restoreAutoCommit(Connection conn, boolean previous) {
        try {
            conn.setAutoCommit(previous);
        } catch (SQLException e) {
            LOG.log(Level.FINE, "还原 autoCommit 失败", e);
        }
    }

    private static void closeQuietly(Connection conn) {
        try {
            conn.close();
        } catch (SQLException e) {
            LOG.log(Level.FINE, "关闭连接失败", e);
        }
    }
}