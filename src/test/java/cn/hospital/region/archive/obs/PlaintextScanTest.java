package cn.hospital.region.archive.obs;

import cn.hospital.region.archive.cli.ObsCli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T40 用例 —— 明文扫描器与 {@code obs} 只读命令面。
 *
 * <p>本类的重点不是「能不能搜到明文」（那只要一个 {@code contains} 就行），
 * 而是<b>误报率</b>：一个天天误报的扫描器等于没有扫描器。
 * 因此每条形态规则都配了<b>反向用例</b>——在真实产物里最像明文、其实不是的那些串
 * （JUnit 临时目录名、epoch 毫秒、批次号、时间戳）必须<b>一条都不报</b>。
 */
class PlaintextScanTest {

    /**
     * 18 位证件号样本，<b>校验位自洽</b>（ISO 7064 MOD 11-2）。
     *
     * <p>刻意不用「看起来像」的那串：形态规则带校验位判定，
     * 校验位算不过的串<b>本来就不该命中</b>，拿它当正样本等于把用例写反。
     */
    private static final String REAL_ID_CARD = "310101198505121563";

    /** 与 {@link #REAL_ID_CARD} 仅末位不同、但校验位算不过的串：形态一样、不是真证件号。 */
    private static final String FAKE_ID_CARD = "310101198505121560";

    private static final String REAL_PHONE = "13800138000";

    // ================================================================ 形态规则

    @Test
    @DisplayName("命中：18 位身份证（校验位成立）与 11 位手机号逐条定位")
    void test_detects_real_shaped_identifiers() throws IOException {
        Path dir = tempDirWith("app.log",
                "2026-10-20 09:12:03 收到报文 证件=310101198505121563 电话=13800138000 已入账");
        PlaintextScanner.Report report = scan(dir);

        assertFalse(report.clean(), "应当命中：" + report.summary());
        assertEquals(1, report.countOf(PlaintextScanner.Kind.ID_CARD_18), report.summary());
        assertEquals(1, report.countOf(PlaintextScanner.Kind.PHONE_11), report.summary());
        assertEquals(1, report.hits().get(0).lineNo(), "应定位到第 1 行");
    }

    @Test
    @DisplayName("反向：校验位算不过的 18 位串不算证件号（否则随机数字串会全部误报）")
    void test_checksum_blocks_false_positives() {
        assertTrue(PlaintextScanner.isChecksumValidIdCard(REAL_ID_CARD));
        assertFalse(PlaintextScanner.isChecksumValidIdCard(FAKE_ID_CARD));
        // 末位为 X 的号码同样走同一套判定（X 是 11 个校验位取值之一）
        assertFalse(PlaintextScanner.isChecksumValidIdCard("310101198505121563X"));
        assertFalse(PlaintextScanner.isChecksumValidIdCard("31010119850512X563"));
        assertFalse(PlaintextScanner.isChecksumValidIdCard("13800138000"));
        assertFalse(PlaintextScanner.isChecksumValidIdCard("3101011985051215631"));
        assertFalse(PlaintextScanner.isChecksumValidIdCard(null));
    }

    @Test
    @DisplayName("反向：JUnit 临时目录名与 epoch 毫秒不得误报（本产品真实日志里最常见的假阳性）")
    void test_real_log_noise_does_not_trigger() throws IOException {
        // 这三行是从本项目 ../logs 的真实文件里摘出来的形态：JUnit 临时目录、
        // 重投退避时刻的 epoch 毫秒、批次时间戳。宽松的「搜 18 位数字」会把它们全报出来。
        Path dir = tempDirWith("surefire.log", String.join("\n",
                "/var/folders/vh3408gj01h0000gn/T/junit13822913855679018662/nobom.csv",
                "attempt=0 nextAtMs=1792544400000 msa=AA",
                "batch.elapsed_ms=2297 latency.p99_ms=32.930"));

        PlaintextScanner.Report report = scan(dir);

        assertTrue(report.clean(), "真实日志噪声不得误报：" + report.summary());
    }

    @Test
    @DisplayName("反向：紧贴更长数字串的片段不算命中（边界规则）")
    void test_bounded_matching_only() throws IOException {
        // 第一行的手机号与 18 位证件号形态都**内嵌在**一个 19 位长数字里（订单号），
        // 边界规则要求它们被排除；只有第三行独立成串的才该命中。
        Path dir = tempDirWith("x.log",
                "order=1913800138000000063\nbatchId=REG-20261020\nphoneNo=13800138000\n");
        PlaintextScanner.Report report = scan(dir);

        assertEquals(1, report.countOf(PlaintextScanner.Kind.PHONE_11),
                "只有第三行独立成串的该命中：" + report.summary());
        assertEquals(0, report.countOf(PlaintextScanner.Kind.ID_CARD_18), report.summary());
        assertEquals(0, report.countOf(PlaintextScanner.Kind.ID_CARD_15),
                "内嵌在 19 位长数字里的形态一律不算：" + report.summary());

        Path dir2 = tempDirWith("y.log", "phoneNo=13800138000\n");
        PlaintextScanner.Report report2 = scan(dir2);
        assertEquals(1, report2.countOf(PlaintextScanner.Kind.PHONE_11), report2.summary());
        assertEquals(0, report2.countOf(PlaintextScanner.Kind.ID_CARD_18), report2.summary());
    }

    @Test
    @DisplayName("命中：15 位旧版身份证形态")
    void test_detects_15_digit_legacy_id() throws IOException {
        Path dir = tempDirWith("old.log", "老档案证件 110105491231002 已归档");
        PlaintextScanner.Report report = scan(dir);
        assertTrue(report.countOf(PlaintextScanner.Kind.ID_CARD_15) >= 1, report.summary());
    }

    // ================================================================ 明文清单通道

    @Test
    @DisplayName("红线：两字中文姓名也必须能扫出来（清单通道不按长度过滤）")
    void test_known_plaintext_channel_catches_short_chinese_names() throws IOException {
        Path dir = tempDirWith("export.csv", "ledger_id,batch_id,patient_name\n1,REG-20261020,张伟\n");
        PlaintextScanner.Report report = new PlaintextScanner.ScanRequest()
                .addRoot(dir).withKnownPlaintext("张伟").scanOrThrow();

        assertFalse(report.clean(), "两字姓名必须命中：" + report.summary());
        assertEquals(1, report.countOf(PlaintextScanner.Kind.KNOWN_PLAINTEXT), report.summary());
    }

    @Test
    @DisplayName("反向：清单通道不能把空串与单空格当成明文（否则每个文件都命中）")
    void test_blank_entries_are_not_registered() throws IOException {
        Path dir = tempDirWith("x.log", "普通日志行\n");
        PlaintextScanner.Report report = new PlaintextScanner.ScanRequest()
                .addRoot(dir).withKnownPlaintext("").withKnownPlaintext("   ").scanOrThrow();
        assertTrue(report.clean(), report.summary());
        assertEquals(0, report.knownCount(), "空值不得登记进清单");
    }

    @Test
    @DisplayName("反向：重复登记的明文只算一处命中（否则一条报文刷十行淹没真位置）")
    void test_duplicate_known_plaintext_counted_once() throws IOException {
        Path dir = tempDirWith("x.log", "张伟 张伟 张伟\n");
        PlaintextScanner.Report report = new PlaintextScanner.ScanRequest()
                .addRoot(dir)
                .withKnownPlaintext(List.of("张伟", "张伟", "张伟"))
                .scanOrThrow();
        assertEquals(1, report.hits().size(), "同一行同一值只报一处：" + report.summary());
    }

    // ================================================================ 报告与脱敏

    @Test
    @DisplayName("红线：命中报告里不出现明文本身（否则报告本身成了患者明细副本）")
    void test_hit_report_never_contains_the_plaintext() throws IOException {
        Path dir = tempDirWith("x.log", "证件=310101198505121563 姓名=张伟\n");
        PlaintextScanner.Report report = new PlaintextScanner.ScanRequest()
                .addRoot(dir)
                .withKnownPlaintext(List.of("张伟", REAL_ID_CARD))
                .scanOrThrow();

        for (PlaintextScanner.Hit h : report.hits()) {
            assertFalse(h.excerpt().contains("张伟"), "片段泄露了姓名：" + h.excerpt());
            assertFalse(h.excerpt().contains(REAL_ID_CARD), "片段泄露了证件号：" + h.excerpt());
        }
        assertFalse(report.summary().contains("张伟"), report.summary());
    }

    @Test
    @DisplayName("红线：报证件号那一处时，片段不得顺带把同行手机号带出来（实测过的真实残留）")
    void test_excerpt_does_not_leak_a_sibling_identifier() throws IOException {
        // 这一行来自真实进程冒烟：最初版本报身份证时，
        // 前后 16 字窗口把手机号原文 13800138000 完整带了出来，报告本身成了患者明细副本。
        Path dir = tempDirWith("app.log",
                "2026-10-20 09:12:03 收到报文 证件=" + REAL_ID_CARD + " 电话=" + REAL_PHONE + " 已入账");
        PlaintextScanner.Report report = new PlaintextScanner.ScanRequest()
                .addRoot(dir).withKnownPlaintext(REAL_PHONE).scanOrThrow();

        assertFalse(report.clean(), report.summary());
        for (PlaintextScanner.Hit h : report.hits()) {
            assertFalse(h.excerpt().contains(REAL_PHONE), "片段泄露了同行手机号：" + h.excerpt());
            assertFalse(h.excerpt().contains(REAL_ID_CARD), "片段泄露了证件号：" + h.excerpt());
        }
    }

    @Test
    @DisplayName("红线：命中值内嵌在更大数字块里时，整块打码（半个身份证号仍然是泄露）")
    void test_excerpt_masks_the_whole_digit_run_around_an_embedded_hit() throws IOException {
        // 出生日期 19850512 内嵌在证件号里：报出生日期时不得把证件号的前 6 位与后 4 位留在片段里
        Path dir = tempDirWith("app.log", "证件=" + REAL_ID_CARD + " 已入账");
        PlaintextScanner.Report report = new PlaintextScanner.ScanRequest()
                .addRoot(dir).withKnownPlaintext("19850512").scanOrThrow();

        assertFalse(report.clean(), report.summary());
        PlaintextScanner.Hit hit = report.hits().stream()
                .filter(h -> h.kind() == PlaintextScanner.Kind.KNOWN_PLAINTEXT)
                .findFirst().orElseThrow();
        assertFalse(hit.excerpt().contains("310101"), "片段带出了证件号前段：" + hit.excerpt());
        assertFalse(hit.excerpt().contains("1563"), "片段带出了证件号后段：" + hit.excerpt());
    }

    @Test
    @DisplayName("报告：命中按 文件→行号→类别 排序，便于逐条销项")
    void test_hits_are_deterministically_ordered() throws IOException {
        Path dir = Files.createTempDirectory("obs-order");
        Files.writeString(dir.resolve("b.log"), REAL_ID_CARD + "\n");
        Files.writeString(dir.resolve("a.log"), REAL_ID_CARD + "\n13800138000\n");

        PlaintextScanner.Report report = new PlaintextScanner.ScanRequest()
                .addRoot(dir).scanOrThrow();
        List<String> keys = report.hits().stream()
                .map(h -> h.file() + ":" + h.lineNo()).collect(Collectors.toList());
        List<String> sorted = keys.stream().sorted().collect(Collectors.toList());
        assertEquals(sorted, keys, "命中必须有序：" + keys);
    }

    // ================================================================ 扫描范围与跳过

    @Test
    @DisplayName("扫描范围：只读 .log/.txt/.csv/.out/.err，跳过构建产物与源码目录")
    void test_only_artifact_extensions_are_scanned() throws IOException {
        Path dir = Files.createTempDirectory("obs-ext");
        Files.writeString(dir.resolve("hit.log"), REAL_ID_CARD + "\n");
        Files.writeString(dir.resolve("ignored.java"), REAL_ID_CARD + "\n");
        Files.writeString(dir.resolve("ignored.json"), REAL_ID_CARD + "\n");
        Path target = Files.createDirectories(dir.resolve("target"));
        Files.writeString(target.resolve("also-ignored.log"), REAL_ID_CARD + "\n");

        PlaintextScanner.Report report = new PlaintextScanner.ScanRequest()
                .addRoot(dir).scanOrThrow();

        assertEquals(1, report.filesScanned(), "只应读了根目录下那一个 .log");
        assertEquals(1, report.hits().size(), report.summary());
    }

    @Test
    @DisplayName("报告：路径不存在计入跳过而不是「干净」（否则 CI 里会变成永远为真的空断言）")
    void test_missing_root_is_reported_as_skipped_not_clean() throws IOException {
        PlaintextScanner.Report report = new PlaintextScanner.ScanRequest()
                .addRoot(Path.of("/definitely/not/here")).scanOrThrow();
        assertEquals(1, report.filesSkipped(), report.summary());
        assertEquals(0, report.filesScanned());
        assertTrue(report.summary().contains("跳过"), report.summary());
    }

    // ================================================================ 命令面

    @Test
    @DisplayName("obs scan-plaintext：干净返回 0，扫到明文返回 1 且不打印明文")
    void test_cli_exit_codes_separate_clean_from_dirty() throws Exception {
        Path clean = Files.createTempDirectory("obs-clean");
        Files.writeString(clean.resolve("a.log"), "schema 迁移完成: version=V5, segments=25\n");
        Files.writeString(clean.resolve("b.csv"), "ledger_id,state\n1,LANDED\n");

        CliResult ok = runCli("obs", "scan-plaintext", clean.toString());
        assertEquals(ObsCli.EXIT_OK, ok.exitCode(), ok.out());
        assertTrue(ok.out().contains("明文扫描"), ok.out());

        Path dirty = Files.createTempDirectory("obs-dirty");
        Files.writeString(dirty.resolve("a.log"), "证件=310101198505121563\n");
        CliResult bad = runCli("obs", "scan-plaintext", dirty.toString());
        assertEquals(ObsCli.EXIT_PLAINTEXT_FOUND, bad.exitCode(), bad.out());
        assertFalse(bad.out().contains("310101198505121563"),
                "命令输出不得打印明文：" + bad.out());
        assertTrue(bad.out().contains("a.log:1"), "应给位置：" + bad.out());
    }

    @Test
    @DisplayName("obs scan-plaintext：不给明文清单时明确提示姓名不在扫描范围内")
    void test_cli_warns_when_no_names_file_given() throws Exception {
        Path dir = Files.createTempDirectory("obs-nonames");
        Files.writeString(dir.resolve("a.log"), "普通日志\n");
        CliResult r = runCli("obs", "scan-plaintext", dir.toString());
        assertTrue(r.out().contains("未提供 --names-file"), r.out());
    }

    @Test
    @DisplayName("obs scan-plaintext：--names-file 逐行读入（# 开头视为注释）")
    void test_cli_reads_names_file() throws Exception {
        Path dir = Files.createTempDirectory("obs-names");
        Files.writeString(dir.resolve("a.log"), "已确认 患者 张伟\n");
        Path names = dir.resolve("names.txt");
        Files.writeString(names, "# 语料姓名\n张伟\n\n  \n李娜\n");

        CliResult r = runCli("obs", "scan-plaintext", dir.resolve("a.log").toString(),
                "--names-file", names.toString());
        assertEquals(ObsCli.EXIT_PLAINTEXT_FOUND, r.exitCode(), r.out());
        assertTrue(r.out().contains("明文清单 2 条"), r.out());
    }

    @Test
    @DisplayName("obs error-codes：导出目录 CSV，行数 = 条目数 + 表头")
    void test_cli_exports_error_code_catalog() throws Exception {
        Path out = Files.createTempDirectory("obs-codes").resolve("codes.csv");
        CliResult r = runCli("obs", "error-codes", "--out", out.toString());
        assertEquals(ObsCli.EXIT_OK, r.exitCode(), r.err());

        byte[] bytes = Files.readAllBytes(out);
        assertEquals((byte) 0xEF, bytes[0]);
        assertEquals((byte) 0xBB, bytes[1]);
        String csv = new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        assertEquals(ErrorCodeCatalog.entries().size() + 1, csv.strip().split("\n").length);
    }

    @Test
    @DisplayName("obs：无法识别的子命令返回 64（先报用法错，不报配置错）")
    void test_cli_unknown_subcommand_is_usage_error() {
        CliResult r = runCli("obs", "not-a-subcommand");
        assertEquals(ObsCli.EXIT_USAGE, r.exitCode());
        assertTrue(r.err().contains("无法识别的子命令"), r.err());
    }

    // ================================================================ 全量扫描（钦定用例）

    @Test
    @DisplayName("红线：全量日志与导出文件零明文（test_full_suite_logs_contain_zero_plaintext_identifiers）")
    void test_full_suite_logs_contain_zero_plaintext_identifiers() throws Exception {
        Path roundLogs = roundLogsDir();
        // 语料里的全部可识别明文：两侧姓名 + 证件号 + 平台侧主键
        List<String> corpusPlaintexts = corpusPlaintexts();
        assertFalse(corpusPlaintexts.isEmpty(), "语料明文清单不应为空");

        PlaintextScanner.Report report = new PlaintextScanner.ScanRequest()
                .addRoot(roundLogs)
                .withKnownPlaintext(corpusPlaintexts)
                .scanOrThrow();

        // 形态规则 + 清单通道，两条通道一起扫过本轮全部 80+ 个日志文件
        assertTrue(report.clean(),
                "全量日志出现明文 " + report.hits().size() + " 处：\n" + report.summary()
                        + "\n前 10 条：" + report.hits().stream().limit(10).map(Object::toString)
                        .collect(Collectors.joining("\n")));
        assertTrue(report.filesScanned() >= 50,
                "应扫到本轮全部构建日志，实际只扫了 " + report.filesScanned() + " 个：" + report.summary());
        assertEquals(0, report.filesSkipped(), "不应有文件被跳过：" + report.summary());
    }

    // ================================================================ 工具

    private static PlaintextScanner.Report scan(Path dir) throws IOException {
        return new PlaintextScanner.ScanRequest().addRoot(dir).scanOrThrow();
    }

    private static Path tempDirWith(String fileName, String content) throws IOException {
        Path dir = Files.createTempDirectory("obs-scan");
        Files.writeString(dir.resolve(fileName), content, StandardCharsets.UTF_8);
        return dir;
    }

    /** round 根目录下的 {@code logs/}（构建与冒烟日志都落在这里）。 */
    private static Path roundLogsDir() {
        Path roundRoot = Path.of("").toAbsolutePath().getParent();
        Path logs = roundRoot.resolve("logs");
        assertTrue(Files.isDirectory(logs), "找不到 round 日志目录：" + logs);
        return logs;
    }

    /** 语料里出现过的全部可识别明文（两侧姓名 + 证件号 + 平台主键）。 */
    private static List<String> corpusPlaintexts() throws IOException {
        Path index = Path.of("src", "test", "resources", "fixtures", "zh", "index.csv");
        assertTrue(Files.isRegularFile(index), "找不到语料索引：" + index.toAbsolutePath());
        List<String> out = new ArrayList<>();
        for (String line : Files.readAllLines(index, StandardCharsets.UTF_8)) {
            String[] cols = line.split(",");
            if (cols.length < 9 || cols[0].equals("file")) {
                continue;
            }
            out.add(cols[3]);   // remote_name（平台侧写法，可能含全角空格）
            out.add(cols[4]);   // local_name
            out.add(cols[6]);   // id_card
            out.add(cols[7]);   // pid3（区域平台主键）
        }
        return out.stream().filter(s -> s != null && !s.isBlank()).distinct()
                .collect(Collectors.toList());
    }

    private record CliResult(int exitCode, String out, String err) {
    }

    private static CliResult runCli(String... args) {
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        int code;
        try (PrintStream out = new PrintStream(outBuf, true, StandardCharsets.UTF_8);
             PrintStream err = new PrintStream(errBuf, true, StandardCharsets.UTF_8)) {
            code = ObsCli.run(args, out, err);
        }
        return new CliResult(code,
                outBuf.toString(StandardCharsets.UTF_8),
                errBuf.toString(StandardCharsets.UTF_8));
    }
}