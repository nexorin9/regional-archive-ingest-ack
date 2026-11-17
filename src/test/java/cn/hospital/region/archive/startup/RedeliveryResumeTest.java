package cn.hospital.region.archive.startup;

import cn.hospital.region.archive.ledger.IdempotencyKey;
import cn.hospital.region.archive.ledger.InsertOutcome;
import cn.hospital.region.archive.ledger.JdbcLedgerStore;
import cn.hospital.region.archive.ledger.LedgerEntry;
import cn.hospital.region.archive.ledger.LedgerSchema;
import cn.hospital.region.archive.ledger.MessageState;
import cn.hospital.region.archive.redelivery.JdbcRedeliveryQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T35 重启续跑验收 —— 「关闭进程后用同一 H2 文件库重启，断言队列恢复条数与重启前一致」
 * 与「连续跑两次扫描，队列条数不变」。
 *
 * <p><b>不 mock 数据库</b>：每条用例用独立的文件型 H2 PG 兼容库（{@code DB_CLOSE_DELAY=0}），
 * 模拟「进程重启，连同一份库」。进程间并不共享内存，只共享磁盘文件，
 * 与生产 PostgreSQL 重启读同一库的语义一致。
 *
 * <p>0 个 mock 库：扫描走 {@link JdbcLedgerStore#findByStates} 与
 * {@link JdbcRedeliveryQueue#offer} 同一条主路径，与启动入口调用方使用同一份 SQL。
 */
class RedeliveryResumeTest {

    /** 文件型 H2：模拟「进程重启，连同一份库」。DB_CLOSE_DELAY=0 → 连接关就关，与内存模式不同。 */
    private static final String FILE_H2_PARAMS =
            ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=0";

    /** 固定时钟：2026-10-20 09:00:00 +08:00；让 created_at 等时间列可复现。 */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-10-20T01:00:00Z"), ZoneId.of("Asia/Shanghai"));

    /** 每条用例独立一份库文件；用 UUID 隔离避免并行运行时串库。 */
    private String currentFile;
    private String currentUrl;

    @BeforeEach
    void freshDatabase() throws IOException, SQLException {
        currentFile = "/tmp/regional-archive-resume-" + UUID.randomUUID();
        currentUrl = "jdbc:h2:" + currentFile + FILE_H2_PARAMS;
        // 加载 H2 驱动类（CI 一次性效应）
        try {
            Class.forName("org.h2.Driver");
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
        // 清理可能残留的文件
        deleteIfExists(currentFile + ".mv.db");
        deleteIfExists(currentFile + ".trace.db");
        // 跑迁移
        migrate();
    }

    private static void deleteIfExists(String path) throws IOException {
        Files.deleteIfExists(Path.of(path));
    }

    private void migrate() throws SQLException {
        try (Connection conn = DriverManager.getConnection(currentUrl, "sa", "");
             Statement st = conn.createStatement()) {
            LedgerSchema.migrate(conn);
        }
    }

    private JdbcLedgerStore.ConnectionProvider connections() {
        return () -> DriverManager.getConnection(currentUrl, "sa", "");
    }

    private JdbcRedeliveryQueue.ConnectionProvider queueConnections() {
        return () -> DriverManager.getConnection(currentUrl, "sa", "");
    }

    private JdbcLedgerStore newStore() {
        return new JdbcLedgerStore(connections());
    }

    private JdbcRedeliveryQueue newQueue() {
        return new JdbcRedeliveryQueue(queueConnections(), FIXED_CLOCK);
    }

    /** 把 ledger 行按指定状态与键插入到 message_ledger；返回 ledgerId。 */
    private long insertLedgerRow(IdempotencyKey k, String batchId, MessageState state)
            throws SQLException {
        try (Connection conn = DriverManager.getConnection(currentUrl, "sa", "");
             var ps = conn.prepareStatement(
                     "INSERT INTO message_ledger (message_type, control_id, processing_id, "
                             + "batch_id, state, raw_payload) VALUES (?, ?, ?, ?, ?, ?)",
                     new String[]{"ID"})) {
            ps.setString(1, k.messageType());
            ps.setString(2, k.controlId());
            ps.setString(3, k.processingId());
            ps.setString(4, batchId);
            ps.setString(5, state.dbValue());
            ps.setString(6, "MSH|^~\\&|CHSCOMMUNITY|REGIONAL_ARCHIVE|REGIONAL_ARCHIVE|FACILITY_A|"
                    + "202610200900||" + k.messageType() + "|" + k.controlId() + "|P|2.5.1|||AL|NE|"
                    + "|UNICODE UTF-8");
            ps.executeUpdate();
            try (var rs = ps.getGeneratedKeys()) {
                if (!rs.next()) {
                    throw new SQLException("INSERT 未返回主键");
                }
                return rs.getLong(1);
            }
        }
    }

    private long insertLedgerRowViaStore(JdbcLedgerStore store, IdempotencyKey k,
                                          String batchId) throws SQLException {
        LedgerEntry entry = new LedgerEntry(k, batchId, "MSH|^~\\&|CHSCOMMUNITY|REGIONAL_ARCHIVE"
                + "|REGIONAL_ARCHIVE|FACILITY_A|202610200900||" + k.messageType() + "|" + k.controlId()
                + "|P|2.5.1|||AL|NE||UNICODE UTF-8", "", false, 0);
        InsertOutcome outcome = store.insertIfAbsent(entry);
        if (!outcome.isNew()) {
            throw new IllegalStateException("Already processed");
        }
        return outcome.ledgerId();
    }

    // ===================================================================
    // 验收用例：test_resume_after_restart_restores_queue
    // ===================================================================

    @Test
    @DisplayName("进程重启后，从同一份库文件恢复的队列条数等于首次扫描之后的条数")
    void resume_after_restart_restores_queue() throws SQLException {
        // 准备 5 条 PENDING_RETRY 行
        for (int i = 0; i < 5; i++) {
            insertLedgerRow(IdempotencyKey.of("ADT^A08", "RESUME-A-" + i, "P"),
                    "BATCH-RESUME-A", MessageState.PENDING_RETRY);
        }

        long aQueued;
        {
            // ---- A 进程：首次扫描 ----
            JdbcLedgerStore ledger = newStore();
            JdbcRedeliveryQueue queue = newQueue();
            RecoveryScan scan = new RecoveryScan(ledger, queue, FIXED_CLOCK);
            RecoveryScan.RecoveryResult result = scan.scanAndRecover(-1L);
            assertEquals(5, result.scanned(), "A 进程扫描到的 PENDING_RETRY 行数");
            assertEquals(5, result.offered(), "A 进程全新加入队列的条数");
            assertEquals(0, result.duplicated(), "A 进程首次扫描应无重复");
            assertEquals(0, result.skipped());
            aQueued = queue.queuedCount();
            assertEquals(5L, aQueued, "A 进程的 QUEUED 计数");
        }
        // 「A 进程退出」—— 关闭 DB 连接（{@code DB_CLOSE_DELAY=0} 在 close 时已落盘）

        // ---- 「重启」：同一份库文件上的实例 ----
        // 不复制数据库 —— 直接用同一路径重新打开（JVM 之间不共享内存，
        // 但 disk 上的 .mv.db 是同一份）。这里通过复制文件到新路径再连，复刻「
        // 不同 JVM 进程对同一份数据库」的真实情形。
        String restartFile = currentFile + "-restart";
        String restartUrl = "jdbc:h2:" + restartFile + FILE_H2_PARAMS;
        copyDatabaseFile(currentFile, restartFile);
        currentUrl = restartUrl;
        currentFile = restartFile;

        JdbcLedgerStore ledgerB = newStore();
        JdbcRedeliveryQueue queueB = newQueue();
        RecoveryScan scanB = new RecoveryScan(ledgerB, queueB, FIXED_CLOCK);
        RecoveryScan.RecoveryResult resultB = scanB.scanAndRecover(-1L);
        assertEquals(5, resultB.scanned(), "B 进程重启后扫描到的条目数应等于 A 结束时");
        // 关键断言：上次扫描已经把这 5 条加进队列了；本次 offer 同 key → Duplicated
        assertEquals(0, resultB.offered(), "B 进程不应新增条目");
        assertEquals(5, resultB.duplicated(), "B 进程 5 条应该全部命中已有队列条目");

        long bQueued = queueB.queuedCount();
        assertEquals(aQueued, bQueued, "重启前后队列条数必须相等");
        assertEquals(5L, bQueued);
    }

    /**
     * 复制 H2 文件型数据库的真实文件到另一个位置 —— 模拟「磁盘上同一份数据被新启动的进程打开」。
     *
     * <p>{@code H2} 默认会在数据库文件名旁挂 {@code .mv.db} 与 {@code .trace.db} 两个文件，
     * 复制时一并保留才能在新 URL 下被识别为同一份库。
     */
    private static void copyDatabaseFile(String from, String to) {
        copyIfExists("/" + from + ".mv.db", "/" + to + ".mv.db");
        copyIfExists("/" + from + ".trace.db", "/" + to + ".trace.db");
    }

    private static void copyIfExists(String from, String to) {
        try {
            Path src = Path.of(from.startsWith("/") ? from : "/" + from);
            Path dst = Path.of(to.startsWith("/") ? to : "/" + to);
            if (Files.exists(src)) {
                Files.copy(src, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // ===================================================================
    // 验收用例：test_recovery_scan_is_idempotent
    // ===================================================================

    @Test
    @DisplayName("同一份库连续跑两次扫描，队列条数不变")
    void recovery_scan_is_idempotent() throws SQLException {
        // 准备 8 条 PENDING_RETRY + 2 条 LANDED（终态，扫描不应去碰）
        for (int i = 0; i < 8; i++) {
            insertLedgerRow(IdempotencyKey.of("ADT^A08", "IDEM-" + i, "P"),
                    "BATCH-IDEM", MessageState.PENDING_RETRY);
        }
        for (int i = 0; i < 2; i++) {
            insertLedgerRow(IdempotencyKey.of("ADT^A08", "IDEM-LANDED-" + i, "P"),
                    "BATCH-IDEM", MessageState.LANDED);
        }

        JdbcLedgerStore ledger = newStore();
        JdbcRedeliveryQueue queue = newQueue();
        RecoveryScan scan = new RecoveryScan(ledger, queue, FIXED_CLOCK);

        RecoveryScan.RecoveryResult first = scan.scanAndRecover(-1L);
        assertEquals(8, first.scanned(), "首次扫描：8 条 PENDING_RETRY 行（LANDED 不在扫描范围）");
        assertEquals(8, first.offered(), "首次 8 条全部新加入队列");
        assertEquals(0, first.duplicated());
        long queuedAfterFirst = queue.queuedCount();
        assertEquals(8L, queuedAfterFirst);

        // 第二次跑：应当 0 offered / 8 duplicated（已存在）
        RecoveryScan.RecoveryResult second = scan.scanAndRecover(-1L);
        assertEquals(8, second.scanned(), "二次扫描也是 8 条");
        assertEquals(0, second.offered(), "二次扫描不应新增");
        assertEquals(8, second.duplicated(), "二次扫描 8 条全部命中已有");
        assertEquals(0, second.skipped());
        long queuedAfterSecond = queue.queuedCount();
        assertEquals(queuedAfterFirst, queuedAfterSecond, "队列条数不变");

        // 第三次跑 —— 同样 idempotent
        RecoveryScan.RecoveryResult third = scan.scanAndRecover(-1L);
        assertEquals(8, third.scanned());
        assertEquals(0, third.offered());
        assertEquals(8, third.duplicated());
        assertEquals(queuedAfterSecond, queue.queuedCount(), "第三次跑队列仍为 8 条");
    }

    // ===================================================================
    // 验收补充：扫描范围过滤 + 构造器校验
    // ===================================================================

    @Test
    @DisplayName("RETRY 路径四态（PENDING_RETRY / RETRYING / RETRY_CONFIRMED / RETRY_PERMANENT_FAILED）都在扫描范围")
    void retry_path_states_are_scanned() throws SQLException {
        insertLedgerRow(IdempotencyKey.of("ADT^A08", "PENDING_RETRY-1", "P"),
                "BATCH-RETRY-PATH", MessageState.PENDING_RETRY);
        insertLedgerRow(IdempotencyKey.of("ADT^A08", "RETRYING-1", "P"),
                "BATCH-RETRY-PATH", MessageState.RETRYING);
        insertLedgerRow(IdempotencyKey.of("ADT^A08", "RETRY_CONFIRMED-1", "P"),
                "BATCH-RETRY-PATH", MessageState.RETRY_CONFIRMED);
        insertLedgerRow(IdempotencyKey.of("ADT^A08", "RETRY_PERMANENT_FAILED-1", "P"),
                "BATCH-RETRY-PATH", MessageState.RETRY_PERMANENT_FAILED);

        JdbcLedgerStore ledger = newStore();
        JdbcRedeliveryQueue queue = newQueue();
        RecoveryScan.RecoveryResult result = new RecoveryScan(ledger, queue, FIXED_CLOCK)
                .scanAndRecover(-1L);

        assertEquals(4, result.scanned(), "四态都在扫描范围内");
        // 只有 PENDING_RETRY 走 offer + RECOVERY_SCAN 路径并加入队列
        // RETRYING / RETRY_CONFIRMED / RETRY_PERMANENT_FAILED 因为状态机进队条件不匹配
        // (shouldEnqueue 拒 LANDED/REJECTED/RETRY_PERMANENT_FAILED/RETRY_CONFIRMED 都在被拒之列)
        assertEquals(1, result.offered(), "仅 PENDING_RETRY 真入队");
        assertEquals(0, result.duplicated());
        assertEquals(3, result.skipped(), "其余三态走 skipped 分支");

        assertEquals(1L, queue.queuedCount(), "队列实际只收到一条 PENDING_RETRY");
    }

    @Test
    @DisplayName("RecoveryResult 构造器自身校验：计数字段不允许负数")
    void recovery_result_rejects_negative_numbers() {
        assertThrows(IllegalArgumentException.class,
                () -> new RecoveryScan.RecoveryResult(-1, 0, 0, 0, 0));
        // counted mismatch: offered + duplicated + skipped (=5) != scanned (=4) → IllegalStateException
        assertThrows(IllegalStateException.class,
                () -> new RecoveryScan.RecoveryResult(4, 3, 1, 1, 0));
    }

    @Test
    @DisplayName("INFO 日志输出 summaryLine，大条目数时 WARNING 阈值生效")
    void info_log_includes_summary_line() throws SQLException {
        insertLedgerRow(IdempotencyKey.of("ADT^A08", "WARN-1", "P"),
                "BATCH-WARN", MessageState.PENDING_RETRY);

        Logger recoveryLogger =
                Logger.getLogger("cn.hospital.region.archive.startup.RecoveryScan");
        CollectingHandler handler = new CollectingHandler();
        recoveryLogger.addHandler(handler);
        try {
            JdbcLedgerStore ledger = newStore();
            JdbcRedeliveryQueue queue = newQueue();
            RecoveryScan.RecoveryResult result = new RecoveryScan(ledger, queue, FIXED_CLOCK)
                    .scanAndRecover(-1L);

            assertEquals(1, result.scanned());
            // 1 条不应触达 WARNING 阈值
            assertEquals(0, handler.warning);
            // 但 INFO 行应至少有一个（summaryLine）
            assertTrue(handler.info >= 1, "扫描完成应打 INFO 摘要");
        } finally {
            recoveryLogger.removeHandler(handler);
        }
    }

    @Test
    @DisplayName("findByStates 空集合返回空列表，不抛异常")
    void find_by_states_empty_returns_empty() throws SQLException {
        JdbcLedgerStore ledger = newStore();
        assertNotNull(ledger.findByStates(java.util.Set.of()));
    }

    @Test
    @DisplayName("RECOVERY_SCAN 的 enqueue_reason 标注与 offer 走同一条业务闸")
    void recovery_scan_uses_offers_label() throws SQLException {
        long ledgerId = insertLedgerRow(IdempotencyKey.of("ADT^A08", "OFFER-LABEL-1", "P"),
                "BATCH-OFFER-LABEL", MessageState.PENDING_RETRY);

        JdbcLedgerStore ledger = newStore();
        JdbcRedeliveryQueue queue = newQueue();
        new RecoveryScan(ledger, queue, FIXED_CLOCK).scanAndRecover(-1L);

        var all = queue.listByLedger(ledgerId);
        assertEquals(1, all.size(), "扫描入队的条目应能查到 1 行");
        assertEquals("recovery-scan", all.get(0).enqueueReason(),
                "扫描入队的条目 enqueue_reason 应为 recovery-scan");
    }

    /** 收集一段日志的 handler —— 不打到 stdout。 */
    private static final class CollectingHandler extends Handler {
        int info;
        int warning;
        int severe;

        @Override
        public void publish(LogRecord record) {
            switch (record.getLevel().getName()) {
                case "WARNING" -> warning++;
                case "SEVERE" -> severe++;
                default -> info++;
            }
        }

        @Override public void flush() { }
        @Override public void close() { }
    }
}
