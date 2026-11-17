package cn.hospital.region.archive.cli;

import cn.hospital.region.archive.Main;
import cn.hospital.region.archive.config.AppConfig;
import cn.hospital.region.archive.config.ConfigException;
import cn.hospital.region.archive.ledger.JdbcLedgerStore;
import cn.hospital.region.archive.ledger.LedgerSchema;
import cn.hospital.region.archive.redelivery.JdbcRedeliveryQueue;
import cn.hospital.region.archive.startup.RecoveryScan;

import java.io.PrintStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * {@code --recovery-scan} 的 CLI 入口 —— 启动期对台账做一次恢复扫描，把「重投路径上未终态」
 * 的条目按幂等键喂给重投队列。
 *
 * <p>独立入口的理由：源产品的 dcm4che 是连接层工具库，进程重启只意味着连接重建；
 * 本产品把重投做成落库事件，进程重启必须有显式扫描把状态接上 —— CLI 入口让 CI 与运维
 * 都能直接验证这件事发生且发生对了。
 *
 * <p>配置加载链路与主进程一致（{@link AppConfig#fromEnv()}）：凭据从环境变量读，
 * 缺项即非零退出，<b>不回落本地替身</b>。schema 迁移也走同一路径，确保生产数据库拿到
 * 的是当前 {@link LedgerSchema#CURRENT_VERSION} 的全部 DDL。
 */
public final class RecoveryScanCli {

    private RecoveryScanCli() {
    }

    /**
     * @param out 摘要输出
     * @param err 错误输出
     * @return 进程退出码（{@code 0} 成功 / {@code 2} 配置缺失 / {@code 1} 启动失败）
     */
    public static int run(PrintStream out, PrintStream err) {
        // 把 java.util.logging 同步到给定的输出，方便查看 INFO/WARNING
        Logger root = Logger.getLogger("");
        RecoveryLogHandler handler = new RecoveryLogHandler(out);
        root.addHandler(handler);
        try {
            return runInner(out);
        } catch (ConfigException e) {
            err.println("启动失败：" + e.getMessage());
            return Main.EXIT_CONFIG_ERROR;
        } catch (Exception e) {
            err.println("恢复扫描异常终止：" + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()));
            return 1;
        } finally {
            root.removeHandler(handler);
        }
    }

    private static int runInner(PrintStream out) throws Exception {
        AppConfig config = AppConfig.fromEnv();
        out.println("=== 启动恢复扫描 ===");
        out.println(config.summaryLine());

        String url = config.dbUrl();
        try (Connection conn = DriverManager.getConnection(url, config.dbUsername(),
                config.dbPassword())) {
            String version = LedgerSchema.migrate(conn);
            out.println("schema 迁移完成: version=" + version);

            JdbcLedgerStore ledgerStore = new JdbcLedgerStore(
                    () -> DriverManager.getConnection(url, config.dbUsername(), config.dbPassword()));
            JdbcRedeliveryQueue redeliveryQueue = new JdbcRedeliveryQueue(
                    () -> DriverManager.getConnection(url, config.dbUsername(), config.dbPassword()));

            RecoveryScan.RecoveryResult result = new RecoveryScan(ledgerStore, redeliveryQueue)
                    .scanAndRecover(-1L);
            out.println(result.summaryLine());
            out.println("=== 恢复扫描结束 ===");
            return 0;
        }
    }

    /** 简单的 stdout handler：把 {@code RecoveryScan} 内部的 INFO/WARNING 同步到调用方。 */
    private static final class RecoveryLogHandler extends Handler {
        private final PrintStream out;

        RecoveryLogHandler(PrintStream out) {
            this.out = out;
        }

        @Override
        public void publish(LogRecord record) {
            if (!isLoggable(record)) {
                return;
            }
            String prefix = switch (record.getLevel().getName()) {
                case "WARNING" -> "[WARN] ";
                case "SEVERE"  -> "[ERROR] ";
                default         -> "";
            };
            out.println(prefix + record.getMessage());
        }

        @Override
        public void flush() {
            out.flush();
        }

        @Override
        public void close() {
            // 无须实际关闭
        }
    }
}
