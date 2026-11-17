package cn.hospital.region.archive.redelivery;

/**
 * 单条重投的实际执行 —— 由产品接入主路径（{@code ArchiveIngestService}）装配，
 * 测试可注入桩。调度器只关心「拿到一条 {@link RedeliveryEntry}、执行、拿回结论」，
 * 不关心到底是 MLLP 还是 HTTP、是不是从台账回读报文原文、要不要签时间戳。
 *
 * <p>实现需是<b>无状态</b>或<b>内部同步</b>：调度器是单线程串行调用 {@code perform}，
 * 并发场景在调度器外部（{@link RedeliveryQueue#claimNextDue} 的原子出队）已保证。
 *
 * <p>允许抛运行时异常；调度器把异常归类为「不可重试失败」并把该条置为
 * {@link RedeliveryStatus#PERMANENT_FAILED}。
 */
@FunctionalInterface
public interface RedeliveryAction {

    /**
     * 执行一次重投并返回结论。
     *
     * @param entry 调度器刚原子出队的条目（含 ledgerId / batchId / idempotencyKey 等定位信息）
     * @return 三态之一的 {@link RedeliveryResult}
     * @throws Exception 网络/解析等底层异常；调度器会把任何异常归为不可重试失败并落
     *                   {@link RedeliveryStatus#PERMANENT_FAILED}
     */
    RedeliveryResult perform(RedeliveryEntry entry) throws Exception;
}
