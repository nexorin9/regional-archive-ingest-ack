package cn.hospital.region.archive.ledger;

/**
 * 审计链与台账状态对不上 —— 说明有状态变更<b>绕过了状态机</b>。
 *
 * <p>正常情况下这不可能发生：{@link JdbcLedgerStore#transition} 是唯一的写入口，
 * 每写一次 {@code message_ledger.state} 就同事务写一条 {@code message_audit}。所以这条异常
 * 一旦抛出，只有三种来源：
 * <ol>
 *   <li>有人拿着裸 JDBC 直接 {@code UPDATE message_ledger SET state=...}（运维脚本、手工排障、
 *       一次「就改一下」的操作）；</li>
 *   <li>有人手工往 {@code message_audit} 插了一条没走状态机的假审计；</li>
 *   <li>上一条 INSERT 台账行时状态就写错了（构造器已挡住，但历史数据仍可能有）。</li>
 * </ol>
 *
 * <p><b>为什么不建数据库触发器</b>：V1 schema 明确要求「同一份 SQL 不分叉」，而 PG 与 H2 的
 * 触发器语法与生命周期不同（PG 用 {@code CREATE OR REPLACE FUNCTION}，H2 要 {@code CREATE TRIGGER}），
 * 加触发器必然把 DDL 分成两份，等于推翻 T11 定下的移植性前提。改用「写路径强约束 + 读路径一致性断言」：
 * 写路径上 {@code transition} 用 {@code WHERE state = ?} 把来源态钉死、非法边在开事务前就被拒；
 * 读路径上由本异常把绕过行为暴露出来。宁可让绕过动作在排障时被立刻看见，也不要在库里埋一个
 * 两库行为不一致的暗门。
 */
public class LedgerAuditChainBrokenException extends Exception {

    private final LedgerAuditChain.Verdict verdict;

    public LedgerAuditChainBrokenException(LedgerAuditChain.Verdict verdict) {
        super(describe(verdict));
        this.verdict = verdict;
    }

    private static String describe(LedgerAuditChain.Verdict v) {
        if (v == null) {
            return "审计链校验失败（无诊断信息）";
        }
        return "审计链与台账状态不一致：ledgerId=" + v.ledgerId()
                + " 断点=" + v.breakKind()
                + " 台账实际 state=" + (v.ledgerState() == null ? "<行不存在>" : v.ledgerState().dbValue())
                + " 审计链末端=" + (v.chainTail() == null ? "<无>" : v.chainTail().dbValue())
                + " 审计行数=" + v.auditRows()
                + " —— 存在绕过状态机直接改 state 的写入；" + v.detail();
    }

    public LedgerAuditChain.Verdict verdict() {
        return verdict;
    }
}