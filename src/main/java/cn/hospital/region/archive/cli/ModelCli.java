package cn.hospital.region.archive.cli;

import cn.hospital.region.archive.config.ConfigException;
import cn.hospital.region.archive.config.ReadOnlyDbConfig;
import cn.hospital.region.archive.model.ConfidenceBucketReport;
import cn.hospital.region.archive.model.ConfidenceSample;
import cn.hospital.region.archive.model.JdbcModelAuditStore;
import cn.hospital.region.archive.model.ModelAuditExporter;
import cn.hospital.region.archive.model.ModelCallRecord;

import java.io.PrintStream;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 只读运维子命令 {@code model} —— <b>调用审计导出</b>与<b>置信度分桶统计</b>。
 *
 * <h2>这两个数字回答什么问题</h2>
 * <p>{@code audit} 回答「模型今天调了没有、出错没有、慢了没有」，交的是一份能存进档的 CSV；
 * {@code confidence-buckets} 回答「今天大概要点多少下确认」，交的是两个可核对的指标
 * （低置信占比、降级率）。前者是合规材料，后者是工作量预估——都不是主路径，
 * 所以放在只读通道里，与 {@link LedgerCli} 同一条脉络。
 *
 * <h2>为什么必须有 {@code --tz}</h2>
 * <p>审计表存的是 epoch 毫秒（绝对时刻），「某一天」这一刀必须在应用层切，
 * 而切法依赖时区：服务器 TZ 被运维改过一次、跨夏令时的那一天、两地部署，
 * 都会让「昨天」指向不同的区间。因此默认取系统时区，但允许显式指定，
 * 并把用了哪个时区<b>打印在报表抬头</b>——两个人对着同一个日期各查一次得到不同数字时，
 * 第一件要核对的就是这一行，而不是先怀疑数据错了。
 *
 * <h2>凭据与退出码</h2>
 * <p>只认 {@link ReadOnlyDbConfig} 的三项数据库环境变量，缺任一以退出码 2 终止，
 * 绝不回落到本地替身（同 {@link LedgerCli}）。{@code 0} 正常；{@code 2} 配置缺失；
 * {@code 64} 用法错误；{@code 3} 查询或写文件失败。
 */
public final class ModelCli {

    public static final int EXIT_CONFIG_ERROR = 2;
    public static final int EXIT_USAGE = 64;
    public static final int EXIT_QUERY_ERROR = 3;

    /** 默认日期：今天。{@code --date} 不给时用它，免得「忘了写日期」变成一个用错日期的报表。 */
    private static final LocalDate DEFAULT_DATE = LocalDate.now();

    private static final String USAGE = String.join(System.lineSeparator(),
            "model —— 模型调用审计与置信度分桶只读排障通道",
            "",
            "用法：",
            "  model audit --date YYYY-MM-DD [--tz ZONE] [--out PATH] [--no-bom]",
            "                                                导出该日的模型调用审计 CSV",
            "  model confidence-buckets --date YYYY-MM-DD [--tz ZONE]",
            "                                                打印该日的置信度分桶与两个指标",
            "",
            "选项：",
            "  --date DATE    自然日 YYYY-MM-DD；不给则取今天",
            "  --tz ZONE      时区 ID（如 Asia/Shanghai）；不给则取系统默认时区",
            "  --out PATH     写入指定路径；不给则打到标准输出",
            "  --no-bom       写文件时不前置 UTF-8 BOM（默认前置，防止 Excel 打开中文乱码）",
            "",
            "凭据只从环境变量读取：" + ReadOnlyDbConfig.ENV_URL + " / " + ReadOnlyDbConfig.ENV_USERNAME
                    + " / " + ReadOnlyDbConfig.ENV_PASSWORD,
            "导出内容已脱敏：入参摘要过出口闸（非 ASCII 与连续 11 位以上数字一律替换），"
                    + "因此数据区不含中文，也不含身份证号与手机号。");

    private ModelCli() {
    }

    /**
     * 可测入口：退出码作为返回值，副作用只有往给定流打印与可能写一个文件。
     *
     * @param args {@code ["model", "audit", "--date", "2026-10-20"]} 形式（已含子命令名）
     */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        return run(args, out, err, ReadOnlyDbConfig::fromEnv);
    }

    /**
     * 可测入口（注入数据库配置来源）。
     *
     * <p>把配置读法做成参数而不是在方法体里写死，理由与 {@link LedgerCli} 同一类：
     * 子命令的<b>行为</b>（区间切分、报表行序、脱敏闸、退出码）与配置来源无关，
     * 不该让它们绑在一张只能从进程环境读的配置上——否则这组行为就没有办法在不启动
     * 第二个进程的前提下被测到。
     *
     * @param configSource 配置读法；返回 {@code null} 视为「配置缺失」并按退出码 2 终止
     */
    public static int run(String[] args, PrintStream out, PrintStream err,
                          java.util.function.Supplier<ReadOnlyDbConfig> configSource) {
        Objects.requireNonNull(args, "args 不得为 null");
        if (args.length >= 2 && ("--help".equals(args[1]) || "-h".equals(args[1]))) {
            out.println(USAGE);
            return 0;
        }
        if (args.length < 2) {
            err.println("参数不完整。");
            err.println(USAGE);
            return EXIT_USAGE;
        }
        // 子命令白名单先验，**排在读配置之前**：与 LedgerCli 同一理由——
        // 不认识一个子命令时先报「环境变量没配」，会把打错的拼写伪装成配置问题。
        String sub = args[1];
        if (!"audit".equals(sub) && !"confidence-buckets".equals(sub)) {
            err.println("未知子命令：" + sub + "。");
            err.println(USAGE);
            return EXIT_USAGE;
        }

        LocalDate date = DEFAULT_DATE;
        ZoneId zone = ZoneId.systemDefault();
        Path target = null;
        boolean bom = true;
        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--date" -> {
                    if (i + 1 >= args.length) {
                        return usageError(err, "--date 缺少取值");
                    }
                    try {
                        date = LocalDate.parse(args[++i]);
                    } catch (DateTimeParseException e) {
                        err.println("--date 取值不合法：" + e.getMessage() + "；应为 YYYY-MM-DD");
                        return EXIT_USAGE;
                    }
                }
                case "--tz" -> {
                    if (i + 1 >= args.length) {
                        return usageError(err, "--tz 缺少取值");
                    }
                    try {
                        zone = ZoneId.of(args[++i]);
                    } catch (DateTimeException e) {
                        err.println("--tz 取值不合法：" + e.getMessage() + "；应为时区 ID，如 Asia/Shanghai");
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

        ReadOnlyDbConfig config;
        try {
            config = Objects.requireNonNull(configSource.get(), "配置读法返回 null");
        } catch (ConfigException | NullPointerException e) {
            err.println("启动失败：" + e.getMessage());
            err.println("只读排障通道不回落到本地替身；请按 .env.example 补齐数据库环境变量后重试。");
            return EXIT_CONFIG_ERROR;
        }
        out.println(config.summaryLine());

        try {
            JdbcModelAuditStore store = new JdbcModelAuditStore(() -> DriverManager.getConnection(
                    config.jdbcUrl(), config.username(), config.password()));
            return "audit".equals(sub)
                    ? audit(store, date, zone, target, bom, out, err)
                    : buckets(store, date, zone, out);
        } catch (SQLException e) {
            err.println("模型审计只读查询失败：" + e.getMessage());
            err.println("若报的是连接错误，请核对 " + ReadOnlyDbConfig.ENV_URL
                    + " 指向的库是否可达；若库未迁移到 V2，先跑一次启动迁移。");
            return EXIT_QUERY_ERROR;
        }
    }

    private static int audit(JdbcModelAuditStore store, LocalDate date, ZoneId zone,
                             Path target, boolean bom, PrintStream out, PrintStream err) {
        ModelAuditExporter exporter = new ModelAuditExporter(store);
        try {
            if (target == null) {
                out.print(exporter.exportCsv(date, zone));
                out.flush();
                return 0;
            }
            ModelAuditExporter.ExportResult result = exporter.writeTo(date, zone, target, bom);
            out.println("已导出 " + date + "（时区 " + zone + "）的模型调用审计到 " + result.path()
                    + "：" + result.dataRows() + " 行");
            out.println("表头：" + result.headerLine());
            return 0;
        } catch (java.io.IOException e) {
            err.println("写导出文件失败：" + e.getMessage());
            return EXIT_QUERY_ERROR;
        } catch (SQLException e) {
            err.println("查询调用审计失败：" + e.getMessage());
            return EXIT_QUERY_ERROR;
        }
    }

    private static int buckets(JdbcModelAuditStore store, LocalDate date, ZoneId zone,
                               PrintStream out) throws SQLException {
        Instant from = ModelAuditExporter.startOf(date, zone);
        Instant to = ModelAuditExporter.startOf(date.plusDays(1), zone);
        List<ConfidenceSample> samples = new ArrayList<>(store.samplesBetween(from, to));
        List<ModelCallRecord> records = store.recordsBetween(from, to);
        ConfidenceBucketReport report = ConfidenceBucketReport.of(date, zone, samples, records);
        for (String line : report.displayLines()) {
            out.println(line);
        }
        return 0;
    }

    private static int usageError(PrintStream err, String detail) {
        err.println("用法有误（" + detail + "）。");
        err.println(USAGE);
        return EXIT_USAGE;
    }

    /** 帮助文本，供 {@link cn.hospital.region.archive.Main} 的 {@code --help} 内嵌一行指向。 */
    public static String usageLine() {
        return "  model audit --date D | model confidence-buckets --date D"
                + "        模型审计导出与置信度分桶（估算今日复核工作量）";
    }
}