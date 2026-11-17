package cn.hospital.region.archive.model;

import java.util.List;

/**
 * 模型网关 —— <b>业务层唯一的模型出口</b>。
 *
 * <h2>边界：模型只做两件规则做不好的事</h2>
 * <p>拆帧、结构化、必填校验、字符集判定、幂等键、身份证一致/冲突、
 * {@code AA/AE/AR} 分类、ACK 组帧——这些全是<b>确定性</b>的，模型全挂时它们照跑不误。
 * 需要模型的只有两处，也正是确定性方案会系统性失手的两处：
 * <ol>
 *   <li>{@link #alignZFields}：Z 段自由文本 → 本地字段名。平台写的「既往病史摘要」
 *       与院内字段 {@code pastHistorySummary} 字面毫无交集，编辑距离<b>方向是反的</b>，
 *       越相似越不可能对上；</li>
 *   <li>{@link #identityCandidates}：简繁、同音错别字、姓名空格三种变体下的身份候选。</li>
 * </ol>
 *
 * <h2>为什么两个方法都返回结构化对象</h2>
 * <p>返回自由文本的话，「这条候选到底是 MRN-884512 还是 MRN-8845123」要靠正则去猜，
 * 而正则猜错时的表现是<b>写错一个人</b>——不是报错，是错得看起来很合理。
 * 所以两个方法都返回 record，业务层拿到的就是可以直接往确认台上摆的东西。
 *
 * <h2>失败语义</h2>
 * <p>失败一律抛 {@link ModelCallException}，<b>不返回半成品</b>。
 * 抛异常时不得推进任何业务状态：报文留在待确认，原文与幂等键保留。
 *
 * <p>实现类只有 {@link OpenAiCompatibleGateway}，且<b>不 import 任何厂商 SDK</b>
 * （由 {@code NoVendorSdkImportTest} 静态守住）：换厂商时只改配置与端点路径，
 * 业务层一行不动。
 */
public interface ModelGateway {

    /**
     * Z 段自由文本 → 本地字段的语义对齐。
     *
     * @param zSegments 报文里的自定义段（一般只取 ZX 系列），不可为 null
     * @return 对齐结果；「没有任何可对齐的东西」返回 {@link ZFieldMap#empty()}，不是异常
     * @throws ModelCallException 模型不可达、响应不可解析或结构校验失败
     */
    ZFieldMap alignZFields(List<ZSegment> zSegments);

    /**
     * 身份候选：在给定的院内候选切片里判断谁最可能是同一位居民。
     *
     * @param pid3         区域平台患者主键；<b>不会送给模型</b>（比对用不到，且它是跨院标识）
     * @param pid5         姓名（区域平台写法，可能简繁/同音/含空格）
     * @param pid7         出生日期
     * @param localIndex   院内候选切片（已由确定性基线收窄）
     * @return 候选列表，按置信度降序；切片为空或无匹配返回 {@link Candidates#empty()}
     * @throws ModelCallException 模型不可达、响应不可解析或结构校验失败
     */
    Candidates identityCandidates(String pid3, String pid5, String pid7, LocalPatientIndex localIndex);
}