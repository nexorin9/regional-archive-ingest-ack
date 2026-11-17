package cn.hospital.region.archive.model;

import cn.hospital.region.archive.ledger.JdbcLedgerStore;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 落库版模型调用审计 —— T20 的 {@link InMemoryModelCallAudit} 在接入常驻进程后由本类接手。
 *
 * <h2>为什么必须落库</h2>
 * <p>内存版审计随进程一起消失，于是「昨天模型挂了几个小时」这种问题<b>永远答不出来</b>：
 * 出事那天运维盯着的是实时日志，而日志早就被轮转掉了；等有人想起来要复盘，
 * 内存里已经空了。更要紧的是 {@code model audit --date 2026-10-19} 这个只读子命令
 * 是<b>另起一个进程</b>跑的，它没有任何办法看见上一个进程的内存。
 *
 * <h2>写失败不拖垮业务——这条策略在端口上就写死了</h2>
 * <p>{@link ModelCallAudit#record} 的契约写着「实现不应把异常抛回调用链」。
 * 本类据此把 {@link SQLException} 降级成一条 WARNING：调用照常成功、报文照常入账，
 * 只有审计那一行没写进去。<b>代价必须说清楚</b>：审计会缺行，缺的那几行事后查不出来。
 * 所以告警文案里带了操作名与耗时，运维能看出「哪段时间审计在漏」；
 * 真要盯住，把下面的 WARNING 接进日志采集的告警规则即可。
 *
 * <p>反过来，<b>读</b>侧的异常一律原样抛出——排障命令读到一半失败却给出一份完整报表，
 * 比直接报错糟糕得多。
 *
 * <h2>连接所有权</h2>
 * <p>每次读写各交一条连接、用完自关，与 {@link JdbcLedgerStore} 同约定。
 * 模型调用是<b>高频写</b>（一条报文 1 到 2 次），因此这里不缓存连接：
 * 审计写的延迟必须低于「在等模型推理」的那个数量级，长连接带来的那点开销不值得用一个连接池的复杂度换。
 */
public final class JdbcModelAuditStore
        implements ModelCallAudit, ConfidenceSampleSink, ModelAuditReadView {

    private static final Logger LOG = Logger.getLogger(JdbcModelAuditStore.class.getName());

    private static final String INSERT_AUDIT = """
            INSERT INTO model_call_audit
                (at_ms, model, operation, elapsed_ms, prompt_tokens, completion_tokens,
                 request_id, degraded, error_code, input_summary)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String INSERT_SAMPLE = """
            INSERT INTO confidence_sample (at_ms, series, subject_ref, confidence, degraded)
            VALUES (?, ?, ?, ?, ?)
            """;

    private static final String SELECT_AUDIT = """
            SELECT at_ms, model, operation, elapsed_ms, prompt_tokens, completion_tokens,
                   request_id, degraded, error_code, input_summary
            FROM model_call_audit
            WHERE at_ms >= ? AND at_ms < ?
            ORDER BY at_ms, id
            """;

    private static final String SELECT_SAMPLE = """
            SELECT at_ms, series, subject_ref, confidence, degraded
            FROM confidence_sample
            WHERE at_ms >= ? AND at_ms < ?
            ORDER BY at_ms, id
            """;

    private final JdbcLedgerStore.ConnectionProvider connections;

    public JdbcModelAuditStore(JdbcLedgerStore.ConnectionProvider connections) {
        this.connections = Objects.requireNonNull(connections, "连接提供者不得为 null");
    }

    // =====================================================================
    // 写：ModelCallAudit 端口
    // =====================================================================

    /**
     * 记一行调用审计。<b>本方法不抛异常</b>——见类注释「写失败不拖垮业务」。
     *
     * <p>时间取 {@link ModelCallRecord#at()}，不取「现在」：调用方在构造记录时
     * 已经把这次调用的完成时刻定死了，审计里再取一次系统时钟会让重放出来的数据对不上。
     */
    @Override
    public void record(ModelCallRecord record) {
        if (record == null) {
            return;
        }
        try (Connection conn = connections.open();
             PreparedStatement ps = conn.prepareStatement(INSERT_AUDIT)) {
            ps.setLong(1, record.at().toEpochMilli());
            ps.setString(2, record.model());
            ps.setString(3, record.operation());
            ps.setLong(4, record.elapsedMillis());
            ps.setInt(5, record.promptTokens());
            ps.setInt(6, record.completionTokens());
            ps.setString(7, record.requestId());
            ps.setBoolean(8, record.degraded());
            ps.setString(9, record.errorCode() == null ? null : record.errorCode().auditToken());
            ps.setString(10, clampSummary(record.inputSummary()));
            ps.executeUpdate();
        } catch (SQLException | RuntimeException e) {
            // 抓 RuntimeException 是有意为之：ConnectionProvider 完全可能因为驱动未加载、
            // 连接池耗尽这类原因抛一个非受检异常，而本方法的契约是「不把异常抛回调用链」。
            // 只抓 SQLException 会让一个池化配置错误变成「报文退回待确认」的业务事故。
            // 只打操作名、模型、耗时、是否降级与 SQLState —— 不打入参摘要（它已经是脱敏形态，
            // 但日志面再窄一点更省事），更不打异常消息里可能夹带的连接串口令。
            LOG.log(Level.WARNING,
                    "模型调用审计写入失败（该次调用未留痕，调用本身不受影响）: operation={0}, model={1},"
                            + " elapsedMs={2}, degraded={3}, 类型={4}, message={5}",
                    new Object[]{record.operation(), record.model(), record.elapsedMillis(),
                            record.degraded(), e.getClass().getSimpleName(), e.getMessage()});
        }
    }

    /**
     * 记一批置信度观察点（一条报文一次调用，最多几十条；不分条提交）。
     *
     * <p>与审计写同样<b>不抛异常</b>：观察点只服务于统计，丢一批观察点的后果是
     * 分桶总数与当天处理条数对不上，而这属于「明天看报表时发现」的问题，
     * 不该在报文处理的热路径上变成一次异常。
     *
     * <p>批内使用<b>单事务</b>：同一批观察点要么全在、要么全不在。
     * 半批会让「今天有 8 条待确认」变成一个看起来仍然合理、实际少算了几条的数字。
     */
    @Override
    public void record(List<ConfidenceSample> samples) {
        if (samples == null || samples.isEmpty()) {
            return;
        }
        try (Connection conn = connections.open()) {
            boolean previousAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(INSERT_SAMPLE)) {
                for (ConfidenceSample s : samples) {
                    ps.setLong(1, s.at().toEpochMilli());
                    ps.setString(2, s.series().dbValue());
                    ps.setString(3, s.subjectRef());
                    ps.setDouble(4, s.confidence());
                    ps.setBoolean(5, s.degraded());
                    ps.addBatch();
                }
                ps.executeBatch();
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException | RuntimeException e) {
            LOG.log(Level.WARNING,
                    "置信度观察点写入失败（分桶统计将少计这批条目，报文处理不受影响）: 条数={0},"
                            + " 类型={1}, message={2}",
                    new Object[]{samples.size(), e.getClass().getSimpleName(), e.getMessage()});
        }
    }

    // =====================================================================
    // 读：ModelAuditReadView 端口
    // =====================================================================

    @Override
    public List<ModelCallRecord> recordsBetween(Instant fromInclusive, Instant toExclusive)
            throws SQLException {
        requireRange(fromInclusive, toExclusive);
        List<ModelCallRecord> out = new ArrayList<>();
        try (Connection conn = connections.open();
             PreparedStatement ps = conn.prepareStatement(SELECT_AUDIT)) {
            ps.setLong(1, fromInclusive.toEpochMilli());
            ps.setLong(2, toExclusive.toEpochMilli());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(readAuditRow(rs));
                }
            }
        }
        return List.copyOf(out);
    }

    @Override
    public List<ConfidenceSample> samplesBetween(Instant fromInclusive, Instant toExclusive)
            throws SQLException {
        requireRange(fromInclusive, toExclusive);
        List<ConfidenceSample> out = new ArrayList<>();
        try (Connection conn = connections.open();
             PreparedStatement ps = conn.prepareStatement(SELECT_SAMPLE)) {
            ps.setLong(1, fromInclusive.toEpochMilli());
            ps.setLong(2, toExclusive.toEpochMilli());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new ConfidenceSample(
                            Instant.ofEpochMilli(rs.getLong("at_ms")),
                            ConfidenceSeries.fromDbValue(rs.getString("series")),
                            rs.getString("subject_ref"),
                            rs.getDouble("confidence"),
                            rs.getBoolean("degraded")));
                }
            }
        }
        return List.copyOf(out);
    }

    // =====================================================================
    // 内部
    // =====================================================================

    private static ModelCallRecord readAuditRow(ResultSet rs) throws SQLException {
        String errorToken = rs.getString("error_code");
        ModelErrorCode errorCode = null;
        if (errorToken != null && !errorToken.isBlank()) {
            errorCode = ModelErrorCode.fromAuditToken(errorToken);
        }
        return new ModelCallRecord(
                Instant.ofEpochMilli(rs.getLong("at_ms")),
                rs.getString("model"),
                rs.getString("operation"),
                rs.getLong("elapsed_ms"),
                rs.getInt("prompt_tokens"),
                rs.getInt("completion_tokens"),
                rs.getString("request_id"),
                rs.getBoolean("degraded"),
                rs.getString("input_summary"),
                errorCode);
    }

    /**
     * 摘要入库前的长度闸：{@code input_summary} 是 {@code VARCHAR(1024)}，
     * 超长会在<b>每次</b>写入时报「value too long」，而触发它的是某一台上游拼装出了异常长的形态行。
     * 与台账正文同一策略：截断 + 记一次告警，不让一条旁路数据把主链路带走。
     */
    private static String clampSummary(String summary) {
        if (summary == null) {
            return "";
        }
        if (summary.length() <= MAX_SUMMARY_CHARS) {
            return summary;
        }
        String clipped = summary.substring(0, MAX_SUMMARY_CHARS - NOTICE.length()) + NOTICE;
        LOG.log(Level.WARNING, "模型调用入参摘要超长，入库截断到 {0} 字符（原始 {1} 字符）",
                new Object[]{MAX_SUMMARY_CHARS, summary.length()});
        return clipped;
    }

    /** 入库上限，与 V2 脚本的 {@code VARCHAR(1024)} 严格对齐。 */
    static final int MAX_SUMMARY_CHARS = 1024;

    /** 截断留痕；与台账正文的标记同风格。 */
    static final String NOTICE = "[摘要截断]";

    private static void requireRange(Instant fromInclusive, Instant toExclusive) {
        Objects.requireNonNull(fromInclusive, "区间起点不得为 null");
        Objects.requireNonNull(toExclusive, "区间终点不得为 null");
        if (!fromInclusive.isBefore(toExclusive)) {
            throw new IllegalArgumentException("区间起点必须早于终点："
                    + fromInclusive + " >= " + toExclusive);
        }
    }

    /** 供测试断言「同一毫秒的多次调用按入库顺序返回」。 */
    static Timestamp toTimestamp(Instant instant) {
        return Timestamp.from(instant);
    }
}