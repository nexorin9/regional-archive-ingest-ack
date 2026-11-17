package cn.hospital.region.archive.model;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

/**
 * 模型调用审计与置信度样本的<b>只读口</b> —— 与 {@link JdbcModelAuditStore}（读写一体）分离，
 * 理由和 {@link cn.hospital.region.archive.ledger.LedgerReadView} 一样：排障面
 * （{@code model audit} / {@code model confidence-buckets} 两个只读子命令）
 * 拿到的是一个<b>不可能改数据</b>的引用。给它 {@code Connection} 就等于
 * 给了一条能写库的旁路，而排障的人本来只需要看。
 *
 * <h2>区间语义：左闭右开</h2>
 * <p>{@code [fromInclusive, toExclusive)}。写库一侧按 epoch 毫秒存绝对时刻，
 * 「某一天」这一刀由调用方按时区切成两个 Instant 传进来——
 * 服务端不知道也不需要知道时区，跨机器部署时同一份数据仍然给出同一个区间。
 */
public interface ModelAuditReadView {

    /**
     * 取区间内的调用审计行，按 {@code (at, id)} 升序 —— 同一毫秒内的多次调用（重试序列）
     * 按入库顺序排，导出的 CSV 因此可复现。
     *
     * @param fromInclusive 区间起点（含）
     * @param toExclusive   区间终点（不含）
     */
    List<ModelCallRecord> recordsBetween(Instant fromInclusive, Instant toExclusive) throws SQLException;

    /**
     * 取区间内的置信度观察点，按 {@code (at, id)} 升序。
     *
     * @param fromInclusive 区间起点（含）
     * @param toExclusive   区间终点（不含）
     */
    List<ConfidenceSample> samplesBetween(Instant fromInclusive, Instant toExclusive) throws SQLException;
}