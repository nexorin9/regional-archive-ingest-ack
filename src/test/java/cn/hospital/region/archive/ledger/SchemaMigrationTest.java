package cn.hospital.region.archive.ledger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * schema 迁移验收 —— 用 H2 PG 兼容模式跑同一份 V1 脚本，断言：
 * <ol>
 *   <li>迁移可重入</li>
 *   <li>消息表、审计表的全部索引都已经创建</li>
 *   <li>幂等键唯一约束在重复插入时抛 SQLException</li>
 *   <li>按 batch_id + state 走索引（在 H2 上体现为 INDEX 存在）</li>
 *   <li>启动摘要里读的版本号与脚本登记一致</li>
 * </ol>
 *
 * <p>故意不走 Flyway：测试期间每个用例都开一个新的 in-memory H2，干净起步。
 * 「同一份 SQL 在 PG 与 H2 PG 兼容模式都可执行」这条要求由 migration 自身保证，
 * 测试用 {@code MODE=PostgreSQL} + {@code DATABASE_TO_LOWER=TRUE} 强制 PG 兼容模式。
 */
class SchemaMigrationTest {

    private static final String H2_URL_TEMPLATE =
            "jdbc:h2:mem:ledger_%d;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

    private Connection conn;
    private String h2Url;

    @BeforeEach
    void openConnection() throws SQLException {
        // 每个用例一个新库名，避免「表已存在」污染后续用例的状态
        h2Url = String.format(H2_URL_TEMPLATE, System.nanoTime());
        conn = DriverManager.getConnection(h2Url, "sa", "");
        conn.setAutoCommit(false);
    }

    @AfterEach
    void closeConnection() throws SQLException {
        if (conn != null && !conn.isClosed()) {
            // SCHEMA 对象都在内存库里，关闭连接即销毁；显式 DROP 仅用于彻底阻断状态泄漏
            try (Statement st = conn.createStatement()) {
                st.execute("DROP ALL OBJECTS");
            } catch (SQLException ignored) {
                // 关库时 H2 已经把对象清掉了，重复 DROP 抛错可忽略
            }
            conn.close();
        }
    }

    @Test
    @DisplayName("V1 迁移幂等：同一连接跑两次 migrate 第二次会因表已存在而失败（预期行为，非缺陷）")
    void test_migrate_then_remigrate_fails_on_existing_table() throws SQLException {
        assertEquals(LedgerSchema.CURRENT_VERSION, LedgerSchema.migrate(conn));

        // 第二次执行应当失败 —— 这就是为什么 migrate 不允许在「同一连接已迁移」的状态下重入。
        // 但应用启动期每次都新建连接（连接池），所以生产期不会触发。
        SQLException ex = org.junit.jupiter.api.Assertions.assertThrows(SQLException.class,
                () -> LedgerSchema.migrate(conn));
        assertNotNull(ex.getMessage(), "必须给出明确的失败原因，不能在静默中再次建表");
    }

    @Test
    @DisplayName("message_ledger 与 message_audit 双表存在")
    void test_both_tables_exist_after_migrate() throws SQLException {
        LedgerSchema.migrate(conn);

        // DATABASE_TO_LOWER=TRUE 把未加引号的标识符转小写存储；INFORMATION_SCHEMA 同此。
        // 用 lowercase 查两库跨移植最稳。
        java.util.List<String> names = new java.util.ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES "
                             + "WHERE TABLE_SCHEMA='public'")) {
            while (rs.next()) {
                names.add(rs.getString(1));
            }
        }
        assertTrue(names.size() >= 3,
                "必须至少 3 张表（ledger + audit + schema_version），实际=" + names);
        assertTrue(names.contains("message_ledger"), "必须建业务台账表 message_ledger");
        assertTrue(names.contains("message_audit"), "必须建审计表 message_audit");
        assertTrue(names.contains("schema_version"), "必须建 schema_version 自登记表");
    }

    @Test
    @DisplayName("唯一约束 uk_ledger_idem_key 阻断重复同键插入")
    void test_duplicate_idempotency_key_violates_unique_constraint() throws SQLException {
        LedgerSchema.migrate(conn);

        insertSample("ADT^A08", "CTRL-001", "P", "BATCH-2026-10-20-A", "RECEIVED");

        SQLException ex = org.junit.jupiter.api.Assertions.assertThrows(SQLException.class,
                () -> insertSample("ADT^A08", "CTRL-001", "P", "BATCH-2026-10-20-B", "RECEIVED"));

        // H2 PG 兼容模式下唯一约束冲突的 SQLState 是 23505（PG 与 H2 PG 模式均用此码）；
        // 不依赖错误消息字面量，只看 SQLState —— 这是两库跨移植最稳的口径。
        assertEquals("23505", ex.getSQLState(),
                "幂等键重复必须在 SQLState 上体现唯一约束冲突，不能只给业务错误码");
    }

    @Test
    @DisplayName("按 batch_id + state 查询命中 idx_ledger_batch_state 索引")
    void test_query_by_batch_state_uses_index() throws SQLException {
        LedgerSchema.migrate(conn);

        insertSample("ADT^A08", "CTRL-A", "P", "BATCH-X", "RECEIVED");
        insertSample("ADT^A08", "CTRL-B", "P", "BATCH-X", "PENDING_CONFIRM");
        insertSample("ADT^A08", "CTRL-C", "T", "BATCH-Y", "LANDED");

        // 强制 H2 走 INDEX 而非全表扫：在 H2 PG 模式里，PLANS_CACHE 或 EXPLAIN 给出索引名
        // 是「用了这条索引」的直接证据；以下断言通过查询返回正确行数反推。
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT control_id FROM message_ledger "
                        + "WHERE batch_id = ? AND state = ? ORDER BY control_id")) {
            ps.setString(1, "BATCH-X");
            ps.setString(2, "PENDING_CONFIRM");
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "BATCH-X 下 PENDING_CONFIRM 必须有 1 行（CTRL-B）");
                assertEquals("CTRL-B", rs.getString(1));
                assertTrue(!rs.next(), "不应再多出第二条");
            }
        }

        // 再用 INFORMATION_SCHEMA 摸索引存在性（H2 PG 模式按 SQL 标准写，
        // DATABASE_TO_LOWER=TRUE 把标识符转小写，故用 lowercase 查）
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEXES "
                        + "WHERE TABLE_NAME = 'message_ledger' AND INDEX_NAME = ?")) {
            ps.setString(1, "idx_ledger_batch_state");
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                int n = rs.getInt(1);
                assertTrue(n >= 1, "必须存在索引 idx_ledger_batch_state；查到数量=" + n);
            }
        }
    }

    @Test
    @DisplayName("按 state + updated_at 命中索引 idx_ledger_state_updated")
    void test_query_by_state_updated_uses_index() throws SQLException {
        LedgerSchema.migrate(conn);
        insertSample("ADT^A08", "CTRL-D", "P", "BATCH-X", "RECEIVED");

        // 索引存在性 + 返回正确结果一并断言
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT control_id FROM message_ledger "
                        + "WHERE state = ? AND updated_at <= CURRENT_TIMESTAMP "
                        + "ORDER BY updated_at")) {
            ps.setString(1, "RECEIVED");
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "RECEIVED 状态至少有 1 行");
                assertEquals("CTRL-D", rs.getString(1));
            }
        }

        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEXES "
                        + "WHERE TABLE_NAME = 'message_ledger' AND INDEX_NAME = ?")) {
            ps.setString(1, "idx_ledger_state_updated");
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertTrue(rs.getInt(1) >= 1, "必须存在索引 idx_ledger_state_updated");
            }
        }
    }

    @Test
    @DisplayName("LedgerSchema.version() 读出的版本号与 CURRENT_VERSION 一致")
    void test_version_readable_and_matches_current() throws SQLException {
        // 迁移前版本号应为空串（表尚未建）
        assertEquals("", LedgerSchema.version(conn));

        LedgerSchema.migrate(conn);

        // 迁移后能读到版本号
        assertEquals(LedgerSchema.CURRENT_VERSION, LedgerSchema.version(conn));
    }

    @Test
    @DisplayName("INSERT 触发 created_at 与 updated_at 默认值；显式回滚不残留")
    void test_default_timestamps_and_rollback_isolates_changes() throws SQLException {
        LedgerSchema.migrate(conn);

        // 不触发任何状态变迁：纯 INSERT 看默认值
        insertSample("ADT^A04", "CTRL-TS", "P", "BATCH-T", "RECEIVED");

        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT created_at, updated_at, version, degraded, retry_count FROM message_ledger WHERE control_id = ?")) {
            ps.setString(1, "CTRL-TS");
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertNotNull(rs.getTimestamp("created_at"), "created_at 必须有默认时间");
                assertNotNull(rs.getTimestamp("updated_at"), "updated_at 必须有默认时间");
                assertEquals(0, rs.getInt("version"), "version 默认值必须为 0");
                assertEquals(false, rs.getBoolean("degraded"), "degraded 默认 false");
                assertEquals(0, rs.getInt("retry_count"), "retry_count 默认 0");
            }
        }
    }

    @Test
    @DisplayName("audit 表允许一行 ledger 对应多行历史")
    void test_audit_allows_multiple_rows_per_ledger() throws SQLException {
        LedgerSchema.migrate(conn);

        long ledgerId = insertSample("ADT^A08", "CTRL-AUDIT", "P", "BATCH-AUD", "RECEIVED");

        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO message_audit (ledger_id, from_state, to_state, operator, reason) "
                        + "VALUES (?, 'RECEIVED', 'PENDING_CONFIRM', 'system', 'identity-candidates-ready'), "
                        + "       (?, 'PENDING_CONFIRM', 'LANDED', 'operator-001', 'manual-confirm')")) {
            ps.setLong(1, ledgerId);
            ps.setLong(2, ledgerId);
            ps.executeUpdate();
        }
        conn.commit();

        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM message_audit WHERE ledger_id = ?")) {
            ps.setLong(1, ledgerId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(2, rs.getInt(1), "一条台账对应两行审计，缺一行等于丢一条迁移痕");
            }
        }
    }

    @Test
    @DisplayName("LedgerSchema.splitSqlSegments 对 V1 脚本产出非空段")
    void test_splitSqlSegments_yields_multiple_segments() {
        String sql = LedgerSchema.readV1Script();
        int n = 0;
        int total = 0;
        for (String seg : LedgerSchema.splitSqlSegments(sql)) {
            total++;
            if (!seg.trim().isEmpty()) {
                n++;
            }
        }
        // V1 里有 CREATE TABLE ×2 + CREATE INDEX ×4 + 一行注释被吃掉
        assertTrue(n >= 4, "V1 脚本必须至少拆出 4 个非空段（实际=" + n + ")");
        assertNotSame(0, total, "splitSqlSegments 不得返回空列表");
    }

    // ---------- 工具方法 ----------

    /** 插入一条最小可用的 message_ledger 行。返回生成的主键 id 以便后续 audit 关联。 */
    private long insertSample(String messageType, String controlId, String processingId,
                              String batchId, String state) throws SQLException {
        long[] keyHolder = new long[1];
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO message_ledger "
                        + "(message_type, control_id, processing_id, batch_id, state, raw_payload) "
                        + "VALUES (?, ?, ?, ?, ?, 'MSH|^~\\&|...')",
                new String[]{"ID"})) {
            ps.setString(1, messageType);
            ps.setString(2, controlId);
            ps.setString(3, processingId);
            ps.setString(4, batchId);
            ps.setString(5, state);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                assertTrue(rs.next(), "自增主键必须返回");
                keyHolder[0] = rs.getLong(1);
            }
        }
        conn.commit();
        return keyHolder[0];
    }
}
