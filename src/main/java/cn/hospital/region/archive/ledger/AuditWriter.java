package cn.hospital.region.archive.ledger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * 审计行写入器 —— 被 {@link JdbcLedgerStore#transition} 在<b>同一个事务</b>里调用。
 *
 * <p>抽成接口的唯一理由是<b>可注入的失败</b>：T14 的边界用例「审计写失败时整体回滚」
 * 需要在审计这一步稳定地失败一次，而生产代码里不能留一个 {@code if (failNextWrite)} 开关。
 * 把这一步做成接口后，测试传入一个必抛 {@code SQLException} 的实现即可，
 * 生产代码里没有任何测试专用分支。
 *
 * <p>实现 {@link #write} 时<b>不得</b>自己 commit / rollback：事务边界由调用方持有。
 * 自己 commit 会让「ledger 更新 + 审计写入」不再原子，等于把本产品最核心的保证丢掉。
 */
@FunctionalInterface
public interface AuditWriter {

    /**
     * 在调用方给定的事务里写一行 {@code message_audit}。
     *
     * @param conn     调用方已开启事务的连接；本方法只允许用 PreparedStatement
     * @param ledgerId 关联的 {@code message_ledger.id}
     * @param from     迁移来源态
     * @param to       迁移目标态
     * @param operator 操作人；自动迁移传 {@code system}，人工动作传 token subject
     * @param reason   原因短语（如 {@code manual-confirm} / {@code revert}）
     * @throws SQLException 写失败时抛出，调用方据此回滚整个事务
     */
    void write(Connection conn, long ledgerId, MessageState from, MessageState to,
               String operator, String reason) throws SQLException;

    /**
     * 生产实现：一条 {@code INSERT INTO message_audit}，全参数绑定。
     *
     * <p>{@code at} 取应用层 {@link Clock}（而非数据库 {@code CURRENT_TIMESTAMP}）：
     * 事务里 ledger 与 audit 两行必须落在<b>同一时刻</b>，让数据库各算一次会差几毫秒，
     * 而 T19 导出「这一批多久从收到到落地」时这差值会直接变成负数。
     */
    static AuditWriter jdbc(Clock clock) {
        Objects.requireNonNull(clock, "clock 不得为 null");
        return (conn, ledgerId, from, to, operator, reason) -> {
            if (ledgerId <= 0) {
                throw new SQLException("ledgerId 非法，不写审计行: " + ledgerId);
            }
            if (from == null || to == null) {
                throw new SQLException("审计行必须同时有 from_state 与 to_state");
            }
            Instant at = clock.instant();
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO message_audit (ledger_id, from_state, to_state, operator, reason, at) "
                            + "VALUES (?, ?, ?, ?, ?, ?)")) {
                ps.setLong(1, ledgerId);
                ps.setString(2, from.dbValue());
                ps.setString(3, to.dbValue());
                ps.setString(4, operator);
                ps.setString(5, reason);
                ps.setTimestamp(6, Timestamp.from(at));
                ps.executeUpdate();
            }
        };
    }
}