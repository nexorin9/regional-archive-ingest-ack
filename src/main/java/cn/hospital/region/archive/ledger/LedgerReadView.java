package cn.hospital.region.archive.ledger;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 台账<b>只读面</b> —— 按批次统计与只读导出用的查询契约。
 *
 * <h2>为什么不把这几个方法加进 {@link LedgerStore}</h2>
 * <p>{@link LedgerStore} 是<b>写面</b>：它唯一的价值是「状态只能通过 {@code transition} 改」。
 * 往里加只读方法本身无害，真正的理由是两者返回的东西根本不是一回事：
 * <ul>
 *   <li>{@code LedgerStore.listByBatch} 返回 {@link LedgerRow}，<b>带 raw_payload</b> ——
 *       接入主路径要报文原文是为了重投与排障；</li>
 *   <li>导出要的是一个<b>不含报文原文</b>的投影。按批次列 500 行、每行 1MB 原文，
 *       再在内存里把其中几个字段脱敏掉 —— 这条路径上明文在内存里活的时间长得没有道理，
 *       而导出恰恰是最容易被人把文件直接发到工作群里的那一步。</li>
 * </ul>
 * <p>拆成两个接口还有一个副作用好处：读面<b>一个写方法都没有</b>，
 * 于是「导出过程中误改了台账」这件事在本项目里写不出来。
 *
 * <h2>本接口的实现</h2>
 * <p>{@link JdbcLedgerStore} 同时实现 {@code LedgerStore} 与本接口（同一份 SQL，不分叉）。
 * 测试里若要造替身，只需实现这四个只读方法，不会牵动 T15/T17 那两个写面替身。
 */
public interface LedgerReadView {

    /**
     * 按批次统计各状态条数（预填全部 {@link MessageState} 取值，无记录的置 0）。
     *
     * <p>「这一批没有拒收条目」也是一条需要显式出现在报表上的结论，因此预填而不是省掉。
     */
    Map<MessageState, Long> countByState(String batchId) throws SQLException;

    /**
     * 按批次数「待确认里模型降级」的条数。
     *
     * <p>这是本轮「低置信待确认」的<b>可核对口径</b>：模型不可达而走降级路径的条目
     * （{@code degraded = true}），它们没有被模型判过身份，质量上比模型正常给出的候选弱一档，
     * 人工复核时值得优先排在前面。模型置信度字段属 T25 之后的模型模块，届时改为
     * 「低于置信度阈值」口径——本接口的签名不必变。
     */
    long countDegradedPending(String batchId) throws SQLException;

    /**
     * 按批次给拒收原因分布：<b>本地原因码</b> → 条数。
     *
     * <p>码取自 {@code message_audit} 里 {@code to_state = 'REJECTED'} 那条迁移行的
     * {@code reason}，剥掉 {@link ArchiveIngestService#REASON_PREFIX_REJECTED} 前缀后的部分
     * （如 {@code control-id-missing}），即 T7 的建议码词表——人看得懂，且能直接经
     * {@link cn.hospital.region.archive.ack.Hl7ErrorCodes} 查到对应标准码。
     *
     * <p>按条数<b>降序</b>返回，条数相同时按原因码字典序，保证同一批次两次出报表的顺序一致。
     */
    Map<String, Long> rejectionReasonCounts(String batchId) throws SQLException;

    /**
     * 按状态（可选再按批次）分页列投影行，按 {@code id} 升序，取最新 {@code limit} 条。
     *
     * <p>与 {@link #listForExport} 的分工：那个方法<b>必须给批次号</b>，服务于「导出这一批的清单」；
     * 本方法服务于院内既有系统<b>拉一屏待办</b>——它要问的是「现在全院有几条停在待确认」，
     * 此时还没有批次概念（区域平台一天推一批，但待确认往往跨天跨批堆着）。
     *
     * <p>分页而不是全量：HTTP 侧一次拉几千行会把报文原文（每行可能到 1MB）整批搬进内存，
     * 而待办界面永远只渲染前几十条。上限由调用方给，本方法只做兜底截断。
     *
     * @param state         按哪个状态过滤；{@code null} 表示不限
     * @param batchIdOrNull 限定批次；{@code null} 或空白表示不限批次
     * @param limit         最多返回多少行；小于等于 0 视为 {@link #MAX_PAGE_SIZE}
     */
    List<ExportRow> listMessages(MessageState state, String batchIdOrNull, int limit) throws SQLException;

    /** {@link #listMessages} 的行数兜底上限：一屏待办永远用不到这么多，再多就是拉全表。 */
    int MAX_PAGE_SIZE = 500;

    /**
     * 按批次列出待导出的投影行；{@code state} 传 {@code null} 表示不限状态。按 {@code id} 升序。
     *
     * <p>投影<b>含</b> {@code rawPayload}：导出要把报文原文里的姓名与证件号脱敏成摘要，
     * 不读原文就无从脱敏。但它是<b>唯一</b>带原文的出口，且 {@link LedgerExporter} 是它唯一的调用方
     * （T40 的全量日志明文扫描盯的就是这两处）。
     */
    List<ExportRow> listForExport(String batchId, MessageState state) throws SQLException;

    /**
     * 导出投影行 —— 台账一行的只读切片，<b>不含</b> raw_payload 之外的患者可识别明文。
     *
     * @param rejectionCode 拒收原因本地码（剥掉 {@code rejected:} 前缀后）；非拒收行为空串
     * @param rawPayload    报文原文，<b>只允许</b>在脱敏出口被消费，不得原样出现在任何产物里
     */
    record ExportRow(long id,
                     IdempotencyKey key,
                     String batchId,
                     MessageState state,
                     String localMrn,
                     boolean degraded,
                     int retryCount,
                     long version,
                     Instant createdAt,
                     Instant updatedAt,
                     String rawPayload,
                     String rejectionCode) {

        public ExportRow {
            if (id <= 0) {
                throw new IllegalArgumentException("台账行主键必须为正数，实际=" + id);
            }
            key = java.util.Objects.requireNonNull(key, "幂等键不得为 null");
            batchId = java.util.Objects.requireNonNull(batchId, "batchId 不得为 null");
            state = java.util.Objects.requireNonNull(state, "state 不得为 null");
            localMrn = localMrn == null ? "" : localMrn;
            rawPayload = rawPayload == null ? "" : rawPayload;
            rejectionCode = rejectionCode == null ? "" : rejectionCode;
            createdAt = createdAt == null ? Instant.EPOCH : createdAt;
            updatedAt = updatedAt == null ? createdAt : updatedAt;
        }

        /** 是否是拒收条目（{@code rejectionCode} 非空即为此，不看 state 以免两端口径打架）。 */
        public boolean rejected() {
            return !rejectionCode.isEmpty();
        }
    }
}