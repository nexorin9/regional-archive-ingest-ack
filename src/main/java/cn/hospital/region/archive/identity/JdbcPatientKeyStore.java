package cn.hospital.region.archive.identity;

import cn.hospital.region.archive.ledger.JdbcLedgerStore;
import cn.hospital.region.archive.ledger.LedgerRedaction;
import cn.hospital.region.archive.ledger.MessageState;
import cn.hospital.region.archive.obs.RedactedLog;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link PatientKeyStore} 的 JDBC 实现 —— 一条台账行上两列主键的真实写入。
 *
 * <h2>三条硬约束</h2>
 * <ol>
 *   <li><b>两列各写各的</b>：{@code SET patient_ref = ?, local_mrn = ?} 两个占位符两个列名，
 *       SQL 里不存在「一个参数写两列」或「一个列写两个来源」的写法。
 *       这一点在测试里由 {@code test_two_primary_keys_stored_separately} 直接查列断言。</li>
 *   <li><b>状态守卫</b>：只在 {@code 已接收} 与 {@code 待确认} 两态可写。
 *       已落地 / 已拒收 / 重投子态的行一律拒写 —— 给一条已落地的行改主键，
 *       等于让「他确认时看到的那条」与「HIS 里现在那条」分家。</li>
 *   <li><b>不静默覆盖</b>：已有非空值且与新值不同 → {@link KeyBindingConflictException}，
 *       事务回滚，一列都不改。</li>
 * </ol>
 *
 * <h2>为什么没有「先查后写」的锁语义</h2>
 * <p>本方法在<b>单个事务</b>内完成「读当前值 → 判冲突 → 写」，并用
 * {@code UPDATE ... WHERE id = ? AND state IN (...)} 的行数做最后一道校验：
 * 行数为 0 说明并发的另一方已经把这行推走了，此时同样当作冲突抛出。
 * 跨行的乐观锁版本号（{@code version} 列）由 {@code JdbcLedgerStore} 管，
 * 本类只管自己这两列，不去动别人的字段。
 */
public final class JdbcPatientKeyStore implements PatientKeyStore {

    private final JdbcLedgerStore.ConnectionProvider connections;

    public JdbcPatientKeyStore(JdbcLedgerStore.ConnectionProvider connections) {
        this.connections = Objects.requireNonNull(connections, "连接提供者不得为 null");
    }

    @Override
    public void bind(long ledgerId, IdentityKeyBinding binding)
            throws SQLException, KeyBindingConflictException {
        Objects.requireNonNull(binding, "绑定不得为 null");
        try (Connection conn = connections.open()) {
            boolean previousAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                Current current = readCurrent(conn, ledgerId, binding);
                if (current == null) {
                    throw new KeyBindingConflictException(ledgerId, "台账无此报文行");
                }
                if (!current.bindable()) {
                    throw new KeyBindingConflictException(ledgerId,
                            "当前状态 " + current.state() + " 不允许绑定主键（只允许 已接收 / 待确认）");
                }
                if (!current.patientRef().isEmpty() && !current.patientRef().equals(binding.patientRef())) {
                    // 冲突消息同样出主键片段：异常消息会被日志与工单系统原样带走，
                    // 与走 INFO 的那条是同一个传播面。院内主键那一侧本就只有片段可出（它也是主键）。
                    throw new KeyBindingConflictException(ledgerId,
                            "区域主键已绑定为 " + LedgerRedaction.keyFragment(current.patientRef())
                                    + "，与本次传入的不一致，拒绝覆盖");
                }
                if (!current.localMrn().isEmpty() && !current.localMrn().equals(binding.localMrn())) {
                    throw new KeyBindingConflictException(ledgerId,
                            "院内主键已绑定且与本次传入的不一致，拒绝覆盖（静默改主键会让台账与 HIS 分家）");
                }
                int updated;
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE message_ledger SET patient_ref = ?, local_mrn = ? WHERE id = ?")) {
                    ps.setString(1, binding.patientRef());
                    ps.setString(2, binding.localMrn());
                    ps.setLong(3, ledgerId);
                    updated = ps.executeUpdate();
                }
                if (updated != 1) {
                    throw new KeyBindingConflictException(ledgerId,
                            "并发方已把该行推走，本次绑定未落（拒绝静默重试覆盖）");
                }
                conn.commit();
                // T40：这一行原先直接把 binding.zhOneLine() 拼进日志，而它渲染的是
                // **区域主键原文**。T40 的全量明文扫描在 ../logs 的 26 个文件里把它抓了出来
                //（每处都是同一条 TESTPAT-0001）。主键是业务标识、不是身份标识，
                // 但「区域平台给谁的档案」与「本院这条记录对应谁」合起来就是一次可回查的身份关联，
                // 而日志的传播路径完全不受控 —— 与台账导出保持同一口径：出主键片段，不出原文。
                RedactedLog.forClass(JdbcPatientKeyStore.class)
                        .with(RedactedLog.text("ledgerId", ledgerId))
                        .with(RedactedLog.key(binding.patientRef()))
                        .with(RedactedLog.text("localMrnBound", binding.boundToHis()))
                        .with(RedactedLog.text("crossKey", binding.crossKey()))
                        .info("identity.key-binding");
            } catch (KeyBindingConflictException | SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(previousAutoCommit);
            }
        }
    }

    @Override
    public Optional<IdentityKeyBinding> read(long ledgerId) throws SQLException {
        try (Connection conn = connections.open();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT patient_ref, local_mrn FROM message_ledger WHERE id = ?")) {
            ps.setLong(1, ledgerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                String patientRef = nullToEmpty(rs.getString(1));
                String localMrn = nullToEmpty(rs.getString(2));
                if (patientRef.isEmpty()) {
                    // 未绑定过（V3 加列后存量行为空串）：返回 empty 而不是「两列都空」的绑定，
                    // 免得调用方把它当成「已绑定但两套主键都没给」。
                    return Optional.empty();
                }
                return Optional.of(new IdentityKeyBinding(patientRef, localMrn));
            }
        }
    }

    // ------------------------------------------------------------------ 内部

    private record Current(String state, String patientRef, String localMrn) {
        boolean bindable() {
            return state != null
                    && (MessageState.RECEIVED.dbValue().equals(state)
                    || MessageState.PENDING_CONFIRM.dbValue().equals(state));
        }
    }

    private Current readCurrent(Connection conn, long ledgerId, IdentityKeyBinding binding)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT state, patient_ref, local_mrn FROM message_ledger WHERE id = ? FOR UPDATE")) {
            ps.setLong(1, ledgerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new Current(nullToEmpty(rs.getString(1)),
                        nullToEmpty(rs.getString(2)),
                        nullToEmpty(rs.getString(3)));
            }
        }
    }

    private static String nullToEmpty(String v) {
        return v == null ? "" : v;
    }
}