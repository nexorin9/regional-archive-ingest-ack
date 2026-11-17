package cn.hospital.region.archive.cli;

import cn.hospital.region.archive.config.ConfigException;
import cn.hospital.region.archive.config.ReadOnlyDbConfig;
import cn.hospital.region.archive.ledger.BatchStats;
import cn.hospital.region.archive.ledger.JdbcLedgerStore;
import cn.hospital.region.archive.obs.BatchObservabilityReport;
import cn.hospital.region.archive.obs.ErrorCodeCatalog;
import cn.hospital.region.archive.obs.PlaintextScanner;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * 只读运维子命令 {@code obs} —— <b>明文扫描</b>、<b>错误码目录导出</b>与<b>批次可观测报告</b>。
 *
 * <h2>为什么 {@code scan-plaintext} 是一条产品命令而不只是一条测试断言</h2>
 * <p>测试断言只在 CI 上跑，而「这一批的日志能不能给第三方看」这个问题
 * 发生在<b>部署之后</b>：等保测评要材料、内网审计要证据、信息员要把导出清单发进科室群。
 * 那时 CI 早已跑完，现场手上只有一堆落盘文件。把扫描做成命令，
 * 值班的人当场就能得到一个可写进报告的结论（命中 0 处），
 * 而不是「我们开发时测过了」——后者在测评现场没有任何分量。
 *
 * <h2>退出码把「干净」与「没扫成」分开</h2>
 * <ul>
 *   <li>{@code 0} —— 扫描完成且零命中（干净）；</li>
 *   <li>{@code 1} —— 扫描完成但有命中（<b>不</b>打印明文，只打脱敏片段与位置）；</li>
 *   <li>{@code 2} —— 配置缺失（仅 {@code batch-report} 需要数据库凭据）；</li>
 *   <li>{@code 3} —— 读文件或查询失败；</li>
 *   <li>{@code 64} —— 用法错误。</li>
 * </ul>
 * <p>「路径不存在」计入跳过数并打印出来，<b>不算干净</b>——路径写错的扫描如果返回 0，
 * CI 里就会变成一条永远为真的空断言。
 *
 * <h2>明文清单从哪来</h2>
 * <p>{@code --names-file} 指向一份每行一个值的清单文件（测试语料的姓名表、
 * 或脱敏演练时真实用过的样例值）。不给则只扫形态规则（身份证校验位、手机号号段），
 * 此时输出会明确标注「未提供明文清单，姓名类不在本次扫描范围内」——
 * 报告里少了一条检查范围却看不出来，是这类工具最常见的误用方式。
 */
public final class ObsCli {

    public static final int EXIT_OK = 0;

    /** 扫出了明文。 */
    public static final int EXIT_PLAINTEXT_FOUND = 1;

    public static final int EXIT_CONFIG_ERROR = 2;

    public static final int EXIT_QUERY_ERROR = 3;

    public static final int EXIT_USAGE = 64;

    private static final String USAGE = String.join(System.lineSeparator(),
            "obs —— 可观测只读排障通道",
            "",
            "用法：",
            "  obs scan-plaintext PATH [PATH...] [--names-file FILE]",
            "                                            扫描日志与导出文件，输出明文命中数",
            "  obs error-codes [--out PATH]              导出错误码总目录 CSV",
            "  obs batch-report <batchId>                打印该批次的可观测报告（处理量/四态/拒收率/耗时）",
            "",
            "选项：",
            "  --names-file FILE  不该出现在产物里的明文清单；每行一个值，同行可用逗号分隔多个",
            "  --out PATH         写入指定路径；不给则打到标准输出",
            "",
            "扫描范围：.log/.txt/.csv/.out/.err 文件；跳过 target/ node_modules/ .git/ src/ docs/",
            "形态规则：18 位身份证（校验位成立）、15 位旧版身份证、11 位手机号（1[3-9] 号段）",
            "不扫中文：本产品日志与 CSV 里大量出现中文说明，逐字判中文会把报表打成筛子。",
            "",
            "batch-report 凭据只从环境变量读取：" + ReadOnlyDbConfig.ENV_URL + " / "
                    + ReadOnlyDbConfig.ENV_USERNAME + " / " + ReadOnlyDbConfig.ENV_PASSWORD);

    private ObsCli() {
    }

    /**
     * 可测入口：退出码作为返回值。
     *
     * @param args 命令行参数（首元素恒为 {@code "obs"}）
     */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        Objects.requireNonNull(args, "args 不得为 null");
        if (args.length >= 2 && ("--help".equals(args[1]) || "-h".equals(args[1]))) {
            out.println(USAGE);
            return EXIT_OK;
        }
        if (args.length < 2) {
            err.println("参数不完整。");
            err.println(USAGE);
            return EXIT_USAGE;
        }
        return switch (args[1]) {
            case "scan-plaintext" -> scanPlaintext(args, out, err);
            case "error-codes" -> errorCodes(args, out, err);
            case "batch-report" -> batchReport(args, out, err);
            default -> {
                err.println("无法识别的子命令：" + args[1]);
                err.println(USAGE);
                yield EXIT_USAGE;
            }
        };
    }

    // ------------------------------------------------------------ scan-plaintext

    private static int scanPlaintext(String[] args, PrintStream out, PrintStream err) {
        List<Path> roots = new ArrayList<>();
        Path namesFile = null;
        for (int i = 2; i < args.length; i++) {
            if ("--names-file".equals(args[i])) {
                if (i + 1 >= args.length) {
                    return usage(err, "--names-file 缺少路径");
                }
                namesFile = Path.of(args[++i]);
            } else if (args[i].startsWith("--")) {
                return usage(err, args[i]);
            } else {
                roots.add(Path.of(args[i]));
            }
        }
        if (roots.isEmpty()) {
            return usage(err, "至少要给一个待扫描的路径");
        }

        List<String> known = new ArrayList<>();
        if (namesFile != null) {
            if (!Files.isRegularFile(namesFile)) {
                err.println("明文清单文件不存在：" + namesFile);
                return EXIT_USAGE;
            }
            try {
                for (String line : Files.readAllLines(namesFile, StandardCharsets.UTF_8)) {
                    String trimmed = line.strip();
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                        continue;
                    }
                    // 一行可以给多个值，用逗号分隔。
                    // 这么做是因为「一份可用的姓名清单」最自然的来源就是语料索引 index.csv
                    // （每行一个患者、逗号分隔多列）。若把整行当成一个值，
                    // 「张伟,张伟,19850512」这种行会去搜一个从不出现的整串，
                    // 扫描却报告「已按 48 条清单扫描」——扫了个空，结论仍然是绿的。
                    for (String cell : trimmed.split(",")) {
                        String value = cell.strip();
                        if (!value.isEmpty() && !known.contains(value)) {
                            known.add(value);
                        }
                    }
                }
            } catch (IOException e) {
                err.println("读明文清单失败：" + e.getMessage());
                return EXIT_QUERY_ERROR;
            }
        }

        PlaintextScanner.ScanRequest request = new PlaintextScanner.ScanRequest()
                .withKnownPlaintext(known);
        roots.forEach(request::addRoot);

        PlaintextScanner.Report report;
        try {
            report = PlaintextScanner.scan(request);
        } catch (IOException e) {
            err.println("扫描失败：" + e.getMessage());
            err.println("扫不出来不等于干净：请核对路径与读权限后重试。");
            return EXIT_QUERY_ERROR;
        }

        out.println(report.summary());
        if (known.isEmpty()) {
            out.println("提示：未提供 --names-file，姓名一类无形态可循的信息不在本次扫描范围内。");
        } else {
            out.println("明文清单 " + report.knownCount() + " 条（清单通道不按长度过滤："
                    + "两字中文姓名也是有效明文）。");
        }
        if (!report.clean()) {
            out.println("命中明细（值本身已打码，只给位置）：");
            for (PlaintextScanner.Hit h : report.hits()) {
                out.println("  [" + h.kind().name() + "] " + h.file() + ":" + h.lineNo()
                        + "  " + h.excerpt());
            }
            err.println("发现明文，未打印其值；请按位置核对来源并修正脱敏路径。");
            return EXIT_PLAINTEXT_FOUND;
        }
        if (report.filesScanned() == 0) {
            err.println("一个文件都没扫到（路径可能写错或全是构建产物）；这不等于通过。");
            return EXIT_QUERY_ERROR;
        }
        return EXIT_OK;
    }

    // ------------------------------------------------------------ error-codes

    private static int errorCodes(String[] args, PrintStream out, PrintStream err) {
        Path target = null;
        for (int i = 2; i < args.length; i++) {
            if ("--out".equals(args[i])) {
                if (i + 1 >= args.length) {
                    return usage(err, "--out 缺少路径");
                }
                target = Path.of(args[++i]);
            } else {
                return usage(err, args[i]);
            }
        }
        String csv = ErrorCodeCatalog.toCsv();
        if (target == null) {
            out.print(csv);
            out.flush();
            return EXIT_OK;
        }
        try {
            // 与台账导出同一口径：前置 UTF-8 BOM，防止 Excel 打开中文乱码
            Files.write(target, concat(LedgerCliUtf8Bom.bom(), csv.getBytes(StandardCharsets.UTF_8)));
            out.println("已导出 " + target + "（" + ErrorCodeCatalog.entries().size() + " 条错误码）");
            return EXIT_OK;
        } catch (IOException e) {
            err.println("写错误码目录失败：" + e.getMessage());
            return EXIT_QUERY_ERROR;
        }
    }

    private static byte[] concat(byte[] head, byte[] tail) {
        byte[] out = new byte[head.length + tail.length];
        System.arraycopy(head, 0, out, 0, head.length);
        System.arraycopy(tail, 0, out, head.length, tail.length);
        return out;
    }

    /** BOM 字节。复用台账导出口径，单独定义以免本类依赖 ledger 包。 */
    private static final class LedgerCliUtf8Bom {

        static byte[] bom() {
            return new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        }
    }

    // ------------------------------------------------------------ batch-report

    private static int batchReport(String[] args, PrintStream out, PrintStream err) {
        if (args.length < 3) {
            return usage(err, "batch-report 需要批次号");
        }
        String batchId = args[2];
        if (batchId.isBlank()) {
            err.println("批次号不得为空串。");
            return EXIT_USAGE;
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
        try {
            JdbcLedgerStore store = new JdbcLedgerStore(
                    () -> DriverManager.getConnection(config.jdbcUrl(), config.username(),
                            config.password()));
            BatchStats stats = BatchStats.of(store, batchId);
            BatchObservabilityReport report = BatchObservabilityReport.of(batchId, stats);
            report.displayLines().forEach(out::println);
            out.println();
            out.print(report.rawData());
            out.flush();
            return EXIT_OK;
        } catch (SQLException e) {
            err.println("台账只读查询失败：" + e.getMessage());
            err.println("若是连接错误请核对 " + ReadOnlyDbConfig.ENV_URL + " 指向的库是否可达；"
                    + "若是空结果请核对批次号。");
            return EXIT_QUERY_ERROR;
        }
    }

    private static int usage(PrintStream err, String detail) {
        err.println("用法有误（" + detail + "）。");
        err.println(USAGE);
        return EXIT_USAGE;
    }

    /** 帮助文本，供 {@link cn.hospital.region.archive.Main} 的 {@code --help} 内嵌一行。 */
    public static String usageLine() {
        return "  obs scan-plaintext PATH... | obs error-codes | obs batch-report <batchId>"
                + "   可观测排障：明文扫描、错误码目录、批次报告";
    }

    /** 供测试构造参数数组，避免测试里重复写字符串。 */
    static String[] args(String... more) {
        return Arrays.copyOf(new String[]{"obs"}, more.length + 1);
    }
}