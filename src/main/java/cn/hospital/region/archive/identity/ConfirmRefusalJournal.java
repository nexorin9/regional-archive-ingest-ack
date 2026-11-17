package cn.hospital.region.archive.identity;

import cn.hospital.region.archive.ledger.JdbcLedgerStore;
import cn.hospital.region.archive.ledger.MessageState;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * 确认被拒的留痕口 —— 「不得静默成功」的落点。
 *
 * <h2>为什么不写进 {@code message_audit}</h2>
 * <p>{@code message_audit} 记的是<b>状态迁移</b>，而
 * {@link cn.hospital.region.archive.ledger.LedgerAuditChain} 的第 5 条不变量锁死了
 * 「审计行数 == 台账 version」。确认被拒时<b>一个字节的状态都没变</b>，
 * version 自然不会动；硬写一行进去，审计链当场断链，
 * 排障的人看到的会是一句「审计链坏了」—— 把一次正常的业务拒绝
 * 报成数据损坏，比不记还糟。所以另立 {@code confirm_refusal}（V4）只增不改。
 *
 * <h2>拒绝也是业务事实</h2>
 * <p>「上午那条为什么一直没落地」在现场有两种答案：
 * 没人点（{@code message_audit} 里查不到任何行）与点了没生效（本表有一行）。
 * 这两者的处置完全不同：前者是待办积压，后者多半是<b>界面把别人已落地的条目
 * 又摆到了确认台上</b>——那是个要改的 bug。本表让后者查得到。
 *
 * <h2>纪律</h2>
 * <ul>
 *   <li>{@code detail} 会被人直接 SELECT 出来看，<b>不得</b>写入姓名 / 证件号 / 手机号明文。</li>
 *   <li>留痕失败<b>不得</b>反过来把业务拒绝变成成功或抛给用户：拒绝的结论本身已经成立，
 *       留痕只是补证据。本实现把 {@link SQLException} 吞成一条 WARNING，
 *       让调用链继续按「拒绝」往下走 —— 否则一次磁盘抖动会让已经生效的拒绝在界面上变成报错。</li>
 * </ul>
 */
public interface ConfirmRefusalJournal {

    /**
     * 行不存在时 {@code actual_state} 列的哨兵值。
     *
     * <p>点了个不在台账里的条目时<b>没有</b>「实际状态」可言；
     * 拿 {@code RECEIVED} 之类的态顶上去，会让运维按错误的状态去排查一条根本不存在的行。
     * 列本身是 NOT NULL，所以落库时写这个哨兵，查询时一眼能认出「这不是状态，是缺行」。
     */
    String NO_ROW_STATE = "(no-row)";

    /** 一次拒绝的留痕内容。 */
    record Refusal(long ledgerId, MessageState actualState, String operator,
                   ConfirmGate.Refusal reason, String detail) {

        public Refusal {
            Objects.requireNonNull(reason, "拒绝原因码不得为 null");
            if (operator == null || operator.isBlank()) {
                throw new IllegalArgumentException("operator 不得为空白：查不到人的拒绝留痕等于没留");
            }
            detail = detail == null ? "" : detail;
            if (detail.length() > 512) {
                detail = detail.substring(0, 512);
            }
        }

        /** 落库用的状态字符串：行不存在时用 {@link #NO_ROW_STATE}。 */
        public String actualStateDbValue() {
            return actualState == null ? NO_ROW_STATE : actualState.dbValue();
        }
    }

    /** 记一条拒绝。留痕失败只告警、不抛出（见类注释「纪律」第 2 条）。 */
    void record(Refusal refusal);

    /** 生产实现：一条 INSERT INTO confirm_refusal，全参数绑定，自己开事务自己提交。 */
    static ConfirmRefusalJournal jdbc(Clock clock, JdbcLedgerStore.ConnectionProvider connections) {
        Objects.requireNonNull(clock, "clock 不得为 null");
        Objects.requireNonNull(connections, "连接提供者不得为 null");
        return refusal -> {
            Instant at = clock.instant();
            try (Connection conn = connections.open();
                 PreparedStatement ps = conn.prepareStatement(
                         "INSERT INTO confirm_refusal (ledger_id, actual_state, operator, reason, detail, at) "
                                 + "VALUES (?, ?, ?, ?, ?, ?)")) {
                ps.setLong(1, refusal.ledgerId());
                ps.setString(2, refusal.actualStateDbValue());
                ps.setString(3, refusal.operator());
                ps.setString(4, refusal.reason().name());
                ps.setString(5, refusal.detail());
                ps.setTimestamp(6, Timestamp.from(at));
                ps.executeUpdate();
            } catch (SQLException e) {
                // 拒绝的结论已经成立，留痕只是补证据：这里绝不让它把拒绝变成报错。
                java.util.logging.Logger
                        .getLogger(ConfirmRefusalJournal.class.getName())
                        .warning("确认被拒留痕写入失败（拒绝结论仍成立）: ledgerId="
                                + refusal.ledgerId() + " reason=" + refusal.reason()
                                + " 原因=" + e.getMessage());
            }
        };
    }

    /** 丢弃口：未装配留痕时用。拒绝照常发生，只是不留证据。 */
    static ConfirmRefusalJournal discarding() {
        return refusal -> { };
    }
}