package cn.hospital.region.archive.identity;

/**
 * 院内患者主索引写入端口 —— <b>业务层唯一的院内出站调用面</b>。
 *
 * <h2>为什么先定端口再定实现</h2>
 * <p>本产品的接入主路径要能回答一个能复核的问题：「同一条报文重发 100 次，HIS 里会不会出现
 * 100 个重复档案？」这个答案不能靠文档承诺，只能靠<b>把出站调用收敛到一个可计数的位置</b>，
 * 再用真实并发与真实重放把它压一遍。因此本轮（T16）先落这个端口与计数替身，
 * T31 由 {@code HisPatientIndexClient} 以<b>同一端口</b>接入真实 HTTP PUT。
 * 端口不变，T16 的断言在 T31 之后原样复跑，结论不得改变。
 *
 * <h2>三个参数就是正式对接规范的三个字段</h2>
 * <pre>
 *   patientRef   区域平台主键（报文 PID-3 第一组件）—— 两套主键必须分开，不与 localMrn 混装
 *   localMrn     院内主键（由身份候选 + 人工确认得出）
 *   sourceBatch  来源批次号（台账 batch_id），便于院内侧回查是哪一批推下来的
 * </pre>
 * 出站报文为 {@code PUT {HIS_MPI_ENDPOINT}/patient-index}，JSON 体三字段齐全，
 * 凭据 {@code HIS_MPI_TOKEN} 从环境变量读（见 T31）。
 *
 * <h2>调用纪律</h2>
 * <ul>
 *   <li>本接口<b>只能</b>由确认门（T30）在人工确认动作之后调用；接入主路径不得直接调用。
 *       「没有确认动作时调用次数恰好为 0」是本产品最硬的一条红线。</li>
 *   <li>失败一律抛 {@link PatientIndexWriteException}，不返回失败标记 —— 返回标记会被
 *       {@code if (!ok) return;} 之类的写法悄悄吞掉，而抛异常一定会打断调用链。</li>
 *   <li>本接口<b>不</b>做身份判定：传进来的三个值已经是确认门判定的结论。</li>
 * </ul>
 */
@FunctionalInterface
public interface PatientIndexWriter {

    /**
     * 写入（或幂等更新）一条患者主索引记录。
     *
     * @param patientRef  区域平台患者主键（PID-3 第一组件）；不得为空
     * @param localMrn    院内患者主键；不得为空
     * @param sourceBatch 来源批次号；不得为空，便于院内回查
     * @throws PatientIndexWriteException 写入失败。抛本异常时<b>不得</b>推进任何业务状态：
     *                                   台账留在待确认、报文原文与幂等键保留，等重试。
     */
    void put(String patientRef, String localMrn, String sourceBatch) throws PatientIndexWriteException;
}