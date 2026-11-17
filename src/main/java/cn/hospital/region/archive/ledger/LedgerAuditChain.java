package cn.hospital.region.archive.ledger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 审计链一致性校验 —— 把「台账当前状态」与「逐条状态迁移审计」对起来看。
 *
 * <h2>不变量</h2>
 * <p>一条报文在台账里的生命周期是一条链：<b>入账时是 {@link MessageState#RECEIVED}</b>，
 * 之后每一次状态变化都追加一行审计。任何时刻都必须满足：
 * <ol>
 *   <li><b>链尾一致</b>：最后一行审计的 {@code to_state} == 台账行当前的 {@code state}；</li>
 *   <li><b>逐条相连</b>：第 i+1 行审计的 {@code from_state} == 第 i 行审计的 {@code to_state}；</li>
 *   <li><b>边合法</b>：每一行审计的 {@code from → to} 都在 {@link MessageStateMachine}
 *       的显式合法边表里（重投子态的边也必须走这张表，不许借重投绕过主态约束）；</li>
 *   <li><b>时间不倒流</b>：审计的 {@code at} 沿链非递减（同毫秒并列合法，因为存储精度到毫秒）；</li>
 *   <li><b>版本相符</b>：审计行数 == {@code message_ledger.version}。
 *       入账时 version=0，{@code transition} 每次 +1，因此「改了几次」与「留了几行审计」必须相等。</li>
 * </ol>
 *
 * <p>第 5 条是本类最有价值的一条：它把「有人绕过 {@code transition} 直接 UPDATE state」
 * 这种既没改 version 也没留审计的写法<b>也</b>抓出来。1–4 条抓的是改了审计但状态没跟上的情形。
 *
 * <h2>为什么这必须是独立可复用的校验而不是写在测试里</h2>
 * <p>因为它要被运维通道复用：T19 的批次统计、T34 的对账差集、T40 的每日汇总，都要在
 * 给出数字之前先确认这条批次的审计链没被绕过 —— <b>带着一条断链报出来的四态分布是误导</b>。
 * 校验逻辑放在库里，任何调用方都能在出数前跑一次；放在测试里只有测试知道。
 *
 * @see LedgerAuditChainBrokenException 断链时抛出的异常（说明为什么不用数据库触发器）
 */
public final class LedgerAuditChain {

    /** 断链的类型。枚举而非布尔：不同断点要给出不同的排查方向。 */
    public enum Break {
        /** 链完整。 */
        NONE,
        /** 台账行不存在（引用了不存在的 ledgerId，或该行已被清理）。 */
        LEDGER_ROW_MISSING,
        /** 第 i+1 行审计的 from 与第 i 行的 to 对不上：审计链中间被删改过。 */
        AUDIT_CHAIN_NOT_CONTIGUOUS,
        /** 某行审计的 from → to 不在状态机合法边表里：有人手写了一条假审计。 */
        AUDIT_EDGE_ILLEGAL,
        /** 审计链末端 to_state 与台账实际 state 不一致：有人绕过状态机改了 state。 */
        LEDGER_STATE_DIVERGES,
        /** 审计的 at 沿链倒流：时间戳被回写或行被乱序插入。 */
        AUDIT_TIMESTAMP_NOT_MONOTONIC,
        /** 审计行数与 version 不相等：改了 state 但既没留审计也没走 version 自增。 */
        AUDIT_COUNT_MISMATCHES_VERSION
    }

    /**
     * 一次校验的结论。
     *
     * @param ledgerId    被校验的台账行主键
     * @param auditRows   该行的审计行数
     * @param chainTail   沿审计链推导出的末端状态（无审计行时为入账初态 RECEIVED）
     * @param ledgerState 台账行当前状态；{@code null} 表示行不存在
     * @param version     台账行当前版本号
     * @param breakKind   断点类型；{@link Break#NONE} 表示完整
     * @param detail      人类可读的中文说明，直接进异常消息与告警日志
     */
    public record Verdict(long ledgerId, int auditRows, MessageState chainTail,
                          MessageState ledgerState, long version,
                          Break breakKind, String detail) {

        public Verdict {
            ledgerState = ledgerState;      // null 是合法取值（行不存在）
            chainTail = chainTail;
            breakKind = breakKind == null ? Break.NONE : breakKind;
            detail = detail == null ? "" : detail;
        }

        /** 链是否完整。 */
        public boolean intact() {
            return breakKind == Break.NONE;
        }

        /** 断链时抛 {@link LedgerAuditChainBrokenException}；完整时返回自身。 */
        public Verdict throwIfBroken() throws LedgerAuditChainBrokenException {
            if (!intact()) {
                throw new LedgerAuditChainBrokenException(this);
            }
            return this;
        }
    }

    /** 一行审计的读出投影。 */
    private record AuditStep(long auditId, MessageState from, MessageState to,
                             String operator, String reason, Instant at) {
    }

    private LedgerAuditChain() {
        // 工具类，禁止实例化
    }

    /**
     * 校验一条台账行的审计链；链完整时返回 {@link Break#NONE} 的结论，不抛异常。
     *
     * <p><b>只读</b>：本方法不开事务、不写任何东西。它可以在排障时随手跑。
     *
     * @param conn     数据库连接（调用方持有；本方法不关闭）
     * @param ledgerId 台账行主键
     * @return 校验结论（{@link Verdict#intact()} 为 true 表示链完整）
     * @throws SQLException 只有「查不动」才抛（连接断了、表不存在）；<b>链断不抛</b>，
     *                       由 {@link Verdict#intact()} 表达；调用方要异常语义用 {@link #verify}
     */
    public static Verdict inspect(Connection conn, long ledgerId) throws SQLException {
        // ---- 1. 读台账行当前状态与版本 ----
        MessageState ledgerState = null;
        long version = -1L;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT state, version FROM message_ledger WHERE id = ?")) {
            ps.setLong(1, ledgerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    ledgerState = MessageState.fromDbValue(rs.getString("state"));
                    version = rs.getLong("version");
                }
            }
        }
        if (ledgerState == null) {
            return new Verdict(ledgerId, 0, null, null, -1, Break.LEDGER_ROW_MISSING,
                    "台账行 " + ledgerId + " 不存在，无法校验审计链");
        }

        // ---- 2. 读该行全部审计，按时间与主键升序 ----
        List<AuditStep> steps = readSteps(conn, ledgerId);

        // ---- 3. 沿链推进：链根是入账初态 RECEIVED ----
        MessageState cursor = MessageState.RECEIVED;
        Instant previousAt = null;
        for (int i = 0; i < steps.size(); i++) {
            AuditStep step = steps.get(i);

            if (step.from() != cursor) {
                return new Verdict(ledgerId, steps.size(), cursor, ledgerState, version,
                        Break.AUDIT_CHAIN_NOT_CONTIGUOUS,
                        "第 " + (i + 1) + " 行审计（auditId=" + step.auditId() + "）的 from="
                                + step.from().dbValue() + "，但上一行审计的 to=" + cursor.dbValue()
                                + "：审计链中间被删改或乱序");
            }

            if (!MessageStateMachine.isLegal(step.from(), step.to())) {
                return new Verdict(ledgerId, steps.size(), cursor, ledgerState, version,
                        Break.AUDIT_EDGE_ILLEGAL,
                        "第 " + (i + 1) + " 行审计（auditId=" + step.auditId() + "）的迁移边 "
                                + step.from().dbValue() + "→" + step.to().dbValue()
                                + " 不在状态机合法边表内：有人手写了假审计");
            }

            if (previousAt != null && step.at().isBefore(previousAt)) {
                return new Verdict(ledgerId, steps.size(), cursor, ledgerState, version,
                        Break.AUDIT_TIMESTAMP_NOT_MONOTONIC,
                        "第 " + (i + 1) + " 行审计（auditId=" + step.auditId() + "）的 at="
                                + step.at() + " 早于上一行的 " + previousAt + "：时间戳倒流");
            }
            previousAt = step.at();
            cursor = step.to();
        }

        // ---- 4. 链尾必须等于台账实际状态 ----
        if (cursor != ledgerState) {
            return new Verdict(ledgerId, steps.size(), cursor, ledgerState, version,
                    Break.LEDGER_STATE_DIVERGES,
                    "审计链末端是 " + cursor.dbValue() + "，台账实际 state 是 " + ledgerState.dbValue()
                            + "：存在绕过状态机直接 UPDATE state 的写入");
        }

        // ---- 5. 审计行数必须等于 version（入账 version=0，每次 transition +1）----
        if (steps.size() != version) {
            return new Verdict(ledgerId, steps.size(), cursor, ledgerState, version,
                    Break.AUDIT_COUNT_MISMATCHES_VERSION,
                    "审计行数 " + steps.size() + " 与 version " + version + " 不相等："
                            + "状态被改过但既没经 transition 留审计、也没走版本自增");
        }

        return new Verdict(ledgerId, steps.size(), cursor, ledgerState, version, Break.NONE,
                "审计链完整：" + steps.size() + " 次迁移，末端 " + cursor.dbValue());
    }

    /**
     * 校验失败即抛 {@link LedgerAuditChainBrokenException}；完整则原样返回结论。
     *
     * <p>调用方形态：
     * <pre>{@code
     * LedgerAuditChain.verify(conn, ledgerId);   // 断链 → 抛异常，调用方拒绝出数
     * }</pre>
     */
    public static Verdict verify(Connection conn, long ledgerId)
            throws SQLException, LedgerAuditChainBrokenException {
        return inspect(conn, ledgerId).throwIfBroken();
    }

    /** 读一条台账行的全部审计，按 (at, id) 升序 —— id 作为同毫秒时的次序决胜。 */
    private static List<AuditStep> readSteps(Connection conn, long ledgerId) throws SQLException {
        List<AuditStep> steps = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, from_state, to_state, operator, reason, at FROM message_audit "
                        + "WHERE ledger_id = ? ORDER BY at ASC, id ASC")) {
            ps.setLong(1, ledgerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Timestamp at = rs.getTimestamp("at");
                    steps.add(new AuditStep(
                            rs.getLong("id"),
                            MessageState.fromDbValue(rs.getString("from_state")),
                            MessageState.fromDbValue(rs.getString("to_state")),
                            rs.getString("operator"),
                            rs.getString("reason"),
                            at == null ? Instant.EPOCH : at.toInstant()));
                }
            }
        }
        return steps;
    }
}