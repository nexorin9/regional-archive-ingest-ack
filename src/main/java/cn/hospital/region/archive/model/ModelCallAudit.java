package cn.hospital.region.archive.model;

/**
 * 模型调用审计出口 —— 一个<b>端口</b>，不是一个「打个日志」的静态方法。
 *
 * <p>做成接口有两个理由，都不是洁癖：
 * <ul>
 *   <li><b>审计失败不许拖垮业务</b>：审计落库失败时，正确的行为是「调用照常成功、
 *       审计写日志告警」，而不是把一条本来能确认的报文退回待确认。
 *       这条策略要有地方写，就需要一个可替换的出口；</li>
 *   <li><b>T28 要导出与分桶统计</b>，届时换成 JDBC 落库实现，业务层不用动。</li>
 * </ul>
 *
 * <p>本轮（T20）随附的实现是 {@link InMemoryModelCallAudit}。
 * <b>刻意不提供「什么都不做」的实现</b>：网关构造时强制要求非空审计，
 * 免得某天装配漏了，模型调了而审计没写，事后谁也查不出来。
 */
@FunctionalInterface
public interface ModelCallAudit {

    /**
     * 记一行调用审计。
     *
     * <p>实现<b>不应</b>把异常抛回调用链；真抛了，网关会照常把结果返回给业务层，
     * 并打一条 WARNING——「审计写不进去」是运维问题，不该变成「报文进不来」。
     */
    void record(ModelCallRecord record);
}