package cn.hospital.region.archive.identity;

import java.sql.SQLException;
import java.util.Optional;

/**
 * 台账行上<b>双主键绑定</b>的读写端口。
 *
 * <p>与 {@link PatientIndexWriter} 分开是有意的：前者写的是<b>本产品自己的台账</b>
 * （区域主键 + 院内主键两个字段），后者写的是<b>院内 HIS</b>（一次出站 PUT）。
 * 两者都发生在「确认之后」，但一个失败不该被当成另一个失败：
 * 台账写成功而 HIS 写失败时，行仍停在待确认等人重试；反过来也一样。
 *
 * <h2>为什么是端口而不是直接写 JDBC</h2>
 * <p>因为本轮要守的不变量之一是<b>「冲突态下一个字都不许落」</b>。
 * 把这条不变量写成「谁在什么时候调了 {@link #bind}」的测试，比断言某个 UPDATE 语句更贴近现场：
 * 端口可数，实现可换（测试用 H2、生产用 PostgreSQL，端口不变）。
 */
public interface PatientKeyStore {

    /**
     * 绑定（或补绑）双主键。
     *
     * <p>实现必须保证：区域主键与院内主键写<b>各自那一列</b>，互不冒名；
     * 且<b>不静默覆盖</b>已有的不同值（宁可抛 {@link KeyBindingConflictException} 让人来看）。
     *
     * @param ledgerId 台账行 id（{@code message_ledger.id}）
     * @param binding  两套主键
     * @throws KeyBindingConflictException 行不存在、当前状态不允许绑定，或已有值与新值不同
     * @throws SQLException                 存储层故障
     */
    void bind(long ledgerId, IdentityKeyBinding binding) throws SQLException, KeyBindingConflictException;

    /**
     * 读回一条台账行上的双主键。
     *
     * @return 未绑定过（{@code patient_ref} 仍为空串）时为 {@link Optional#empty()}，
     *         而不是返回一个「两列都是空串」的绑定 —— 后者会被调用方误当成「已绑定但两套主键都没给」
     */
    Optional<IdentityKeyBinding> read(long ledgerId) throws SQLException;
}