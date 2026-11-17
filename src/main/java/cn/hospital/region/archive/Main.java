package cn.hospital.region.archive;

import cn.hospital.region.archive.cli.LedgerCli;
import cn.hospital.region.archive.cli.ModelCli;
import cn.hospital.region.archive.cli.ObsCli;
import cn.hospital.region.archive.config.AppConfig;
import cn.hospital.region.archive.config.ConfigException;
import cn.hospital.region.archive.ledger.LedgerSchema;
import cn.hospital.region.archive.mllp.AckContractSmoke;
import cn.hospital.region.archive.mllp.AckDialect;
import cn.hospital.region.archive.mllp.CapacityBenchmark;
import cn.hospital.region.archive.mllp.MllpSmoke;

import java.io.PrintStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Arrays;

/**
 * 进程入口：装配配置 → 打印启动摘要 → 进入运行面。
 *
 * <p>本轮先把配置装配这一层立起来：任何必需环境变量缺失或取值不合法，进程在<b>启动期</b>
 * 以退出码 2 终止并打印出错的变量名，不会带着半套配置往下跑，也绝不回落到本地替身。
 * MLLP 监听口与 HTTP API 的装配在后续任务接入。
 *
 * <p>退出码：{@code 0} 正常；{@code 2} 配置缺失/非法；{@code 64} 用法错误。
 */
public final class Main {

    /** 配置缺失或非法。运维脚本按这个码判断「不是端口被占用，是环境没配全」。 */
    public static final int EXIT_CONFIG_ERROR = 2;

    /** 用法错误（sysexits.h 的 EX_USAGE）。 */
    public static final int EXIT_USAGE = 64;

    private static final String USAGE = String.join(System.lineSeparator(),
            "区域健康档案接入与回执系统",
            "",
            "用法：",
            "  java -jar regional-archive-ingest-ack.jar --check-config   校验环境变量并打印启动摘要",
            "  java -jar regional-archive-ingest-ack.jar --mllp-smoke     起监听口打一帧真实报文，验证收帧与回执链路",
            "  java -jar regional-archive-ingest-ack.jar --ack-contract-smoke [framed_ack|bare_ack]",
            "                                                              连发两帧，核对传输级确认字节与逐条 MSA 回执",
            "  java -jar regional-archive-ingest-ack.jar --capacity-benchmark",
            "                                                              单批 500 条容量基准：P50/P95/P99、队列深度、连接占用",
            "  java -jar regional-archive-ingest-ack.jar --recovery-scan  把台账里重投路径未终态条目按幂等键喂给重投队列",
            "  java -jar regional-archive-ingest-ack.jar --api-smoke         起回环 API 并逐端点自检：令牌校验、409 语义、四态统计、对账差集",
            cn.hospital.region.archive.cli.LedgerCli.usageLine(),
            cn.hospital.region.archive.cli.ModelCli.usageLine(),
            cn.hospital.region.archive.cli.ObsCli.usageLine(),
            "  java -jar regional-archive-ingest-ack.jar --help            显示本帮助",
            "",
            "全部凭据只从环境变量读取，样例见 .env.example。",
            "缺少任一必需项时进程以退出码 " + EXIT_CONFIG_ERROR + " 终止，不回落到本地替身。");

    private Main() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.err));
    }

    /**
     * 可测的入口：把退出码作为返回值，副作用只有往给定流打印。
     *
     * @param args 命令行参数
     * @param err  诊断输出流
     * @return 进程退出码
     */
    static int run(String[] args, PrintStream err) {
        String[] normalized = args == null ? new String[0] : args;
        if (normalized.length == 1 && ("--help".equals(normalized[0]) || "-h".equals(normalized[0]))) {
            System.out.println(USAGE);
            return 0;
        }
        if (normalized.length == 1 && "--mllp-smoke".equals(normalized[0])) {
            // 自检路径：不需要凭据，不连任何院内系统，只在本机回环口上打一帧
            return MllpSmoke.run(System.out);
        }
        if (normalized.length >= 1 && normalized.length <= 2 && "--ack-contract-smoke".equals(normalized[0])) {
            // 回执契约自检：连发两帧（一条齐全、一条缺 MSH-10），核对传输级确认字节与逐条 MSA 回执。
            // 方言缺省按 HL7 规范形式（framed_ack）；也可显式传 bare_ack 核对裸字节形态。
            AckDialect dialect = AckDialect.FRAMED_ACK;
            if (normalized.length == 2) {
                try {
                    dialect = AckDialect.fromWireName(normalized[1]);
                } catch (IllegalArgumentException e) {
                    err.println(e.getMessage());
                    err.println(USAGE);
                    return EXIT_USAGE;
                }
            }
            return AckContractSmoke.run(dialect, System.out);
        }
        if (normalized.length == 1 && "--capacity-benchmark".equals(normalized[0])) {
            // 容量基准：合成语料 + 本机回环口 + 内存台账，不连任何院内系统、不读任何凭据。
            // 跑的是接收与入账的真实主路径，产出的分位数与连接占用就是 README 容量小节的数字来源。
            return CapacityBenchmark.runCli(System.out);
        }
        if (normalized.length == 1 && "--api-smoke".equals(normalized[0])) {
            // 院内 API 自检：真实回环端口 + 真实 HTTP/1.1 + 内嵌台账。
            // 与 --mllp-smoke 同一性质——不读凭据、不连院内系统，因此不需要环境变量。
            return cn.hospital.region.archive.http.ApiSmoke.run(System.out);
        }
        if (normalized.length == 1 && "--recovery-scan".equals(normalized[0])) {
            // T35 重启续跑：扫一遍台账，把「重投路径上未终态」的条目按幂等键喂给重投队列。
            // 这是产品「进程崩了也能从台账恢复」的可核对入口；CI 与运维手动都能跑。
            return cn.hospital.region.archive.cli.RecoveryScanCli.run(System.out, err);
        }
        if (normalized.length >= 1 && "ledger".equals(normalized[0])) {
            // 只读排障通道：批次四态统计与清单导出。刻意只有两个子命令，不铺台账 CRUD。
            return LedgerCli.run(normalized, System.out, err);
        }
        if (normalized.length >= 1 && "model".equals(normalized[0])) {
            // 只读排障通道：模型调用审计导出与置信度分桶（估算今日复核工作量）。
            return ModelCli.run(normalized, System.out, err);
        }
        if (normalized.length >= 1 && "obs".equals(normalized[0])) {
            // 只读排障通道：明文扫描、错误码目录、批次可观测报告。
            // scan-plaintext 不读任何凭据（扫的是落盘文件），batch-report 与其余两个一样走 ReadOnlyDbConfig。
            return ObsCli.run(normalized, System.out, err);
        }
        if (normalized.length != 1 || !"--check-config".equals(normalized[0])) {
            err.println("参数无法识别：" + Arrays.toString(normalized));
            err.println(USAGE);
            return EXIT_USAGE;
        }

        AppConfig config;
        try {
            config = AppConfig.fromEnv();
        } catch (ConfigException e) {
            err.println("启动失败：" + e.getMessage());
            err.println("请按 .env.example 补齐环境变量后重启；本进程不会回落到本地替身。");
            return EXIT_CONFIG_ERROR;
        }

        System.out.println(config.summaryLine());
        // 启动自检：给方言建议，但绝不改配置。
        // 理由见 AckDialect 的类注释——猜错方言的表现是对端读包器卡死等帧尾、本地却一切正常，
        // 这种静默失败只能由人改 ARCHIVE_ACK_MODE 后重启来消除，程序不替人做这个决定。
        System.out.println(AckDialect.suggest(null, null).toLogLine());
        return 0;
    }
}