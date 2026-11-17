package cn.hospital.region.archive.cli;

import cn.hospital.region.archive.config.ConfigException;
import cn.hospital.region.archive.config.ReadOnlyDbConfig;
import cn.hospital.region.archive.ledger.BatchStats;
import cn.hospital.region.archive.ledger.JdbcLedgerStore;
import cn.hospital.region.archive.ledger.LedgerExporter;
import cn.hospital.region.archive.ledger.MessageState;

import java.io.PrintStream;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 只读运维子命令 {@code ledger} —— <b>批次四态统计</b>与<b>清单导出</b>。
 *
 * <h2>定位：排障通道，不是主路径</h2>
 * <p>产品主路径（接帧 → 入账 → 回执 → 确认 → 落地）是常驻服务，界面在院内既有系统里。
 * 本类是给信息科值班人员留的<b>一条旁路</b>：出问题时他们需要一件能马上给出答案的工具，
 * 而不是「先把服务停掉、进数据库手写 SQL」。因此本命令面刻意只有两个子命令，
 * <b>不</b>铺一整套台账 CRUD —— 加了就会有人开始用它改数据，那条只读性质就没了。
 *
 * <h2>凭据</h2>
 * <p>只认 {@link ReadOnlyDbConfig} 的三项数据库环境变量，缺任一以退出码 2 终止，
 * 绝不回落到本地替身（一份 H2 空库出数，会让人以为「这一批确实没有待确认」）。
 *
 * <h2>退出码</h2>
 * <p>{@code 0} 正常；{@code 2} 配置缺失或非法；{@code 64} 用法错误；{@code 3} 台账查询失败。
 */
public final class LedgerCli {

    public static final int EXIT_CONFIG_ERROR = 2;
    public static final int EXIT_USAGE = 64;
    public static final int EXIT_QUERY_ERROR = 3;

    private static final String USAGE = String.join(System.lineSeparator(),
            "ledger —— 接收台账只读排障通道",
            "",
            "用法：",
            "  ledger batch-stats <batchId>                 打印该批次的四态分布、拒收原因与低置信待确认数",
            "  ledger export <batchId> [--state STATE]      导出该批次清单 CSV（默认 PENDING_CONFIRM）",
            "",
            "选项：",
            "  --state STATE   PENDING_CONFIRM（待确认清单，默认）| REJECTED（拒收清单）| 其它四态（通用清单）",
            "  --out PATH      写入指定路径；不给则打到标准输出",
            "  --no-bom        写文件时不前置 UTF-8 BOM（默认前置，防止 Excel 打开中文乱码）",
            "",
            "凭据只从环境变量读取：" + ReadOnlyDbConfig.ENV_URL + " / " + ReadOnlyDbConfig.ENV_USERNAME
                    + " / " + ReadOnlyDbConfig.ENV_PASSWORD,
            "导出内容已脱敏：姓名与证件号只出 SHA-256 前 8 位，患者主键只出首尾片段。");

    private LedgerCli() {
    }

    /**
     * 可测入口：退出码作为返回值，副作用只有往给定流打印与可能写一个文件。
     *
     * @param args {@code ["ledger", "batch-stats", "BATCH-X"]} 形式（已含子命令名）
     */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        Objects.requireNonNull(args, "args 不得为 null");
        // args[0] 恒为子命令名 "ledger"，因此 --help / -h 在 args[1]
        if (args.length >= 2 && ("--help".equals(args[1]) || "-h".equals(args[1]))) {
            out.println(USAGE);
            return 0;
        }
        if (args.length < 3) {
            err.println("参数不完整。");
            err.println(USAGE);
            return EXIT_USAGE;
        }
        String sub = args[1];
        String batchId = args[2];
        if (batchId.isBlank()) {
            err.println("批次号不得为空串：批次号是统计与导出的唯一入口。");
            return EXIT_USAGE;
        }
        // 子命令白名单先验，**排在读配置之前**：不认识一个子命令时先报「环境变量没配」，
        // 会把一个打错的子命令伪装成配置问题，值班的人会去翻 .env.example 而不是查命令拼写。
        if (!"batch-stats".equals(sub) && !"export".equals(sub)) {
            return usageError(err, sub);
        }

        ReadOnlyDbConfig config;
        try {
            config = ReadOnlyDbConfig.fromEnv();
        } catch (ConfigException e) {
            err.println("启动失败：" + e.getMessage());
            err.println("只读排障通道不回落到本地替身；请按 .env.example 补齐数据库环境变量后重试。");
            return EXIT_CONFIG_ERROR;
        }
        out.println(config.summaryLine());

        // 连接提供者**每次交一条新连接**：JdbcLedgerStore 的事务边界由它自己持有，
        // 每次查询开一条、用完自关。把同一条连接交给它跑多个查询，第二条就会拿到已关闭的连接 ——
        // 而 batch-stats 恰好要发四条查询（分布 / 拒收原因 / 低置信），只发一条的 export 不会暴露这个问题。
        try {
            JdbcLedgerStore store = new JdbcLedgerStore(() -> DriverManager.getConnection(
                    config.jdbcUrl(), config.username(), config.password()));
            return "batch-stats".equals(sub)
                    ? batchStats(store, batchId, out)
                    : export(store, batchId, args, out, err);
        } catch (SQLException e) {
            // 查询失败要说清是「查不到」还是「连不上」：前者多半是批次号打错，后者是库的问题
            err.println("台账只读查询失败：" + e.getMessage());
            err.println("若报的是连接错误，请核对 " + ReadOnlyDbConfig.ENV_URL
                    + " 指向的库是否可达；若是空结果，请核对批次号。");
            return EXIT_QUERY_ERROR;
        }
    }

    private static int batchStats(JdbcLedgerStore store, String batchId, PrintStream out)
            throws SQLException {
        BatchStats stats = BatchStats.of(store, batchId);
        for (String line : stats.displayLines()) {
            out.println(line);
        }
        if (stats.total() == 0) {
            out.println("提示：该批次在台账里没有任何条目。若批次号来自平台推送记录，"
                    + "先确认本端是否已接收过该批。");
        }
        return 0;
    }

    private static int export(JdbcLedgerStore store, String batchId, String[] args,
                              PrintStream out, PrintStream err) throws SQLException {
        MessageState state = MessageState.PENDING_CONFIRM;
        Path target = null;
        boolean bom = true;
        for (int i = 3; i < args.length; i++) {
            switch (args[i]) {
                case "--state" -> {
                    if (i + 1 >= args.length) {
                        return usageError(err, "--state 缺少取值");
                    }
                    try {
                        state = MessageState.fromDbValue(args[++i].toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException e) {
                        err.println("未知状态：" + args[i] + "；可选 " + List.of(
                                MessageState.RECEIVED.dbValue(),
                                MessageState.PENDING_CONFIRM.dbValue(),
                                MessageState.LANDED.dbValue(),
                                MessageState.REJECTED.dbValue()));
                        return EXIT_USAGE;
                    }
                }
                case "--out" -> {
                    if (i + 1 >= args.length) {
                        return usageError(err, "--out 缺少路径");
                    }
                    target = Path.of(args[++i]);
                }
                case "--no-bom" -> bom = false;
                default -> {
                    err.println("无法识别的选项：" + args[i]);
                    return usageError(err, args[i]);
                }
            }
        }

        LedgerExporter exporter = new LedgerExporter(store);
        try {
            if (target == null) {
                String csv = exporter.exportCsv(batchId, state);
                out.print(csv);
                out.flush();
                return 0;
            }
            LedgerExporter.ExportResult result = exporter.writeTo(batchId, state, target, bom);
            out.println("已导出 " + result.path() + "（" + result.headerLine() + "，"
                    + result.dataRows() + " 行）");
            return 0;
        } catch (java.io.IOException e) {
            err.println("写导出文件失败：" + e.getMessage());
            return EXIT_QUERY_ERROR;
        }
    }

    private static int usageError(PrintStream err, String detail) {
        err.println("用法有误（" + detail + "）。");
        err.println(USAGE);
        return EXIT_USAGE;
    }

    /** 帮助文本，供 {@link cn.hospital.region.archive.Main} 的 {@code --help} 内嵌一行指向。 */
    public static String usageLine() {
        return "  ledger batch-stats <batchId> | ledger export <batchId> [--state S] [--out P]"
                + "   台账只读排障：批次四态统计与清单导出（脱敏）";
    }
}