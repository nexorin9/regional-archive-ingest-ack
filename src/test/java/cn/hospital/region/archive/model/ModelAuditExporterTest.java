package cn.hospital.region.archive.model;

import cn.hospital.region.archive.ack.AckComposer;
import cn.hospital.region.archive.cli.ModelCli;
import cn.hospital.region.archive.config.ReadOnlyDbConfig;
import cn.hospital.region.archive.hl7.Hl7Message;
import cn.hospital.region.archive.hl7.Hl7Parser;
import cn.hospital.region.archive.identity.CountingPatientIndexWriter;
import cn.hospital.region.archive.identity.PatientIndexWriter;
import cn.hospital.region.archive.ledger.ArchiveIngestService;
import cn.hospital.region.archive.ledger.IdempotencyGuard;
import cn.hospital.region.archive.ledger.JdbcLedgerStore;
import cn.hospital.region.archive.ledger.LedgerSchema;
import cn.hospital.region.archive.ledger.LedgerStore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T28 只读导出与分桶用例 —— 两条硬要求：<b>分桶条数之和等于样本总数</b>、
 * <b>产物里没有患者可识别明文</b>。
 *
 * <h2>为什么「条数之和 = 总数」要单独立一条红线</h2>
 * <p>它是这份报表唯一的不变量。其余数字（低置信占比、降级率）都建立在它之上：
 * 一旦统计时把某些观察点跳过，报表仍然能打印、看起来仍然合理，
 * 只有拿它和当天实际处理条数一比才会发现少了——而那已经��第二天早上了。
 * 所以本用例刻意喂进<b>四个桶都有样本</b>的语料，逐桶断言数字后再断言其和等于样本数；
 * 空桶恒为 0 也单独验，因为「空桶显示 0」与「空桶没列出来」在报表上是两件完全不同的事。
 *
 * <h2>为什么脱敏断言要落在产物上</h2>
 * <p>和 T19 同一口径：脱敏写错时最典型的形态是「某一列忘了套闸」，它在代码里看着完全正常。
 * {@link AuditTextGuard} 挡的是<b>整类形态</b>（非 ASCII、连续 ≥11 位数字）而不是
 * 「已知明文清单」，因此本用例既能证明「姓名/证件号没漏出去」，
 * 也能证明「任何未来新增的中文列同样会被挡下」——后者靠的是把中文硬塞进摘要再试一次。
 *
 * <p>全走真实 H2（PG 兼容模式）+ 真实 JDBC + 真实 CSV 产物，0 个 mock 库。
 */
class ModelAuditExporterTest {

    private static final String H2_URL =
            "jdbc:h2:mem:model_audit_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    /** 报表的自然日：2026-10-20（东八区）。 */
    private static final LocalDate DAY = LocalDate.of(2026, 10, 20);

    /** 该日 00:00（东八区）对应的绝对时刻，语料全部落在这之后。 */
    private static final Instant DAY_START = Instant.parse("2026-10-19T16:00:00Z");

    /** 语料里出现的可识别明文；导出产物里一个都不许有。 */
    private static final List<String> SECRETS = List.of(
            "张伟", "李娜", "欧阳明月", "310101198505121567", "110101199003031234", "13901234567");

    @TempDir
    Path tempDir;

    private static Connection openConnection() throws SQLException {
        return DriverManager.getConnection(H2_URL, "sa", "");
    }

    private static JdbcModelAuditStore store() {
        return new JdbcModelAuditStore(ModelAuditExporterTest::openConnection);
    }

    @BeforeAll
    static void migrateOnce() throws SQLException {
        try (Connection conn = openConnection()) {
            conn.setAutoCommit(false);
            LedgerSchema.migrate(conn);
            conn.commit();
        }
    }

    @BeforeEach
    void clearTables() throws SQLException {
        try (Connection conn = openConnection(); var st = conn.createStatement()) {
            st.execute("DELETE FROM confidence_sample");
            st.execute("DELETE FROM model_call_audit");
        }
    }

    // =====================================================================
    // 语料构造
    // =====================================================================

    /** 成功调用的一行审计。 */
    private static ModelCallRecord ok(Instant at, String operation, long elapsed, int tokens) {
        return new ModelCallRecord(at, "stub-model-local", operation, elapsed,
                tokens, Math.max(0, tokens / 2), "chatcmpl-stub-" + operation, false,
                "z_field_map | ZX1.2(len=6) digest=ab12cd34");
    }

    /** 降级调用的一行审计（降级行必带错误码，否则记录本身构造不出来）。 */
    private static ModelCallRecord degraded(Instant at, String operation, ModelErrorCode code,
                                            String summary) {
        return new ModelCallRecord(at, "stub-model-local", operation, 1500L,
                0, 0, ModelCallRecord.NO_REQUEST_ID, true, summary, code);
    }

    private static ConfidenceSample sample(Instant at, ConfidenceSeries series, String subject,
                                          double confidence, boolean degraded) {
        return new ConfidenceSample(at, series, subject, confidence, degraded);
    }

    /**
     * 一天的语料：<b>四个桶都有样本</b>，两个序列都有，覆盖降级与未给分数。
     *
     * <p>身份候选 9 个观察点分布在 5 条报文上：CTRL-1 三个候选全高可信（不看）、
     * CTRL-2 一个高可信 + 一个中档（要看）、CTRL-3 降级且分数在 HIGH 档（仍要看，
     * 因为模型压根没看过那份结论）、CTRL-4 一条 UNKNOWN（要看）、CTRL-5 一条低分（要看）。
     */
    private static List<ConfidenceSample> daySamples() {
        List<ConfidenceSample> s = new ArrayList<>();
        s.add(sample(at(1), ConfidenceSeries.Z_ALIGNMENT, "CTRL-1", 0.82d, false));
        s.add(sample(at(2), ConfidenceSeries.Z_ALIGNMENT, "CTRL-1", 0.95d, false));
        s.add(sample(at(3), ConfidenceSeries.Z_ALIGNMENT, "CTRL-2", 0.62d, false));
        s.add(sample(at(4), ConfidenceSeries.Z_ALIGNMENT, "CTRL-2", -1d, false));
        s.add(sample(at(5), ConfidenceSeries.Z_ALIGNMENT, "CTRL-3", 0.78d, true));

        s.add(sample(at(6), ConfidenceSeries.IDENTITY_CANDIDATE, "CTRL-1", 0.95d, false));
        s.add(sample(at(7), ConfidenceSeries.IDENTITY_CANDIDATE, "CTRL-1", 0.91d, false));
        s.add(sample(at(8), ConfidenceSeries.IDENTITY_CANDIDATE, "CTRL-1", 0.90d, false));
        s.add(sample(at(9), ConfidenceSeries.IDENTITY_CANDIDATE, "CTRL-2", 0.96d, false));
        s.add(sample(at(10), ConfidenceSeries.IDENTITY_CANDIDATE, "CTRL-2", 0.80d, false));
        s.add(sample(at(11), ConfidenceSeries.IDENTITY_CANDIDATE, "CTRL-3", 0.90d, true));
        s.add(sample(at(12), ConfidenceSeries.IDENTITY_CANDIDATE, "CTRL-3", -1d, true));
        s.add(sample(at(13), ConfidenceSeries.IDENTITY_CANDIDATE, "CTRL-4", -1d, false));
        s.add(sample(at(14), ConfidenceSeries.IDENTITY_CANDIDATE, "CTRL-5", 0.55d, false));
        return List.copyOf(s);
    }

    /** 该日的调用审计：5 次成功 + 2 次降级（降级率 2/7）。 */
    private static List<ModelCallRecord> dayRecords() {
        List<ModelCallRecord> r = new ArrayList<>();
        r.add(ok(at(1), "z_field_map", 820L, 21));
        r.add(ok(at(6), "identity_candidates", 640L, 18));
        r.add(ok(at(9), "identity_candidates", 590L, 15));
        r.add(degraded(at(11), "identity_candidates", ModelErrorCode.READ_TIMEOUT,
                "identity | PID-5(len=6) digest=9f8e7d6c"));
        r.add(degraded(at(12), "z_field_map", ModelErrorCode.SCHEMA_INVALID,
                "z_field_map | ZX2.3(len=11) digest=0011aabb"));
        r.add(ok(at(13), "identity_candidates", 700L, 16));
        r.add(ok(at(14), "identity_candidates", 810L, 19));
        return List.copyOf(r);
    }

    private static Instant at(int secondOfDay) {
        return DAY_START.plusSeconds(secondOfDay);
    }

    // =====================================================================
    // 红线一：分桶条数之和 = 样本总数
    // =====================================================================

    @Test
    @DisplayName("红线：四个桶的条数之和等于观察点总数；空桶恒为 0（不是不列出来）")
    void test_bucket_sum_equals_total() {
        ConfidenceBucketReport report =
                ConfidenceBucketReport.of(DAY, SHANGHAI, daySamples(), dayRecords());

        for (ConfidenceSeries series : ConfidenceSeries.values()) {
            ConfidenceSeriesStats stats = series == ConfidenceSeries.Z_ALIGNMENT
                    ? report.zAlignment() : report.identityCandidate();
            int sum = 0;
            for (ConfidenceBucket b : ConfidenceBucket.values()) {
                int n = stats.count(b);
                assertTrue(n >= 0, series + " 的 " + b + " 桶不得为负");
                sum += n;
            }
            assertEquals(stats.total(), sum,
                    series + " 分桶条数之和必须等于观察点总数（构造器已断言，这里再从报表口径验一次）");
            assertEquals(ConfidenceBucket.values().length, stats.counts().size(),
                    "四个桶都必须有行；「今天高可信 0 条」与「今天没有高可信这一档」是两件事");
        }

        // 逐桶断言具体数字：把语料摊开数一遍，而不是只验一个加总等式
        ConfidenceSeriesStats z = report.zAlignment();
        assertEquals(5, z.total(), "Z 段观察点应为 5");
        assertEquals(1, z.count(ConfidenceBucket.HIGH), "0.95 一条（CTRL-1）");
        assertEquals(2, z.count(ConfidenceBucket.MEDIUM), "0.82 与 0.78 各一条（CTRL-1、CTRL-3 降级）");
        assertEquals(1, z.count(ConfidenceBucket.LOW), "0.62 一条（CTRL-2）");
        assertEquals(1, z.count(ConfidenceBucket.UNKNOWN), "-1 一条（CTRL-2 未给）");

        ConfidenceSeriesStats id = report.identityCandidate();
        assertEquals(9, id.total(), "身份候选观察点应为 9");
        assertEquals(5, id.count(ConfidenceBucket.HIGH), "0.95/0.91/0.90/0.96/0.90 五条");
        assertEquals(1, id.count(ConfidenceBucket.MEDIUM), "0.80 一条（CTRL-2）");
        assertEquals(1, id.count(ConfidenceBucket.LOW), "0.55 一条（CTRL-5）");
        assertEquals(2, id.count(ConfidenceBucket.UNKNOWN), "-1 两条（CTRL-3 降级、CTRL-4 没给结论）");

        // 「统计时跳过某些观察点」这条路径在构造器里就被挡住，不是等到报表上才发现
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new ConfidenceSeriesStats(ConfidenceSeries.Z_ALIGNMENT,
                        Map.of(ConfidenceBucket.HIGH, 1), 5));
        assertTrue(ex.getMessage().contains("必须等于样本总数"), ex.getMessage());
    }

    @Test
    @DisplayName("空桶显示 0 与 0.0%，而不是从报表上消失；空日的占比不出现 NaN")
    void test_empty_buckets_are_zero_not_missing() {
        ConfidenceBucketReport empty = ConfidenceBucketReport.of(DAY, SHANGHAI, List.of(), List.of());
        for (ConfidenceBucket b : ConfidenceBucket.values()) {
            assertEquals(0, empty.zAlignment().count(b), "空日的 Z 段 " + b + " 桶应为 0");
            assertEquals(0d, empty.zAlignment().ratio(b), "空日的占比应为 0 而不是 NaN");
        }
        assertEquals(0d, empty.lowConfidenceRatio());
        assertEquals(0d, empty.degradationRatio());
        assertEquals(0, empty.pendingConfirmEstimate().subjects());
        assertTrue(empty.displayLines().stream().anyMatch(l -> l.contains("提示：本日没有任何置信度观察点")),
                "空日必须给出提示，而不是一张空表");
    }

    @Test
    @DisplayName("跨序列的样本混进统计会被当场拒绝——两个口径不许互相顶替")
    void test_cross_series_sample_is_rejected() {
        List<ConfidenceSample> mixed = new ArrayList<>(daySamples());
        mixed.add(sample(at(15), ConfidenceSeries.IDENTITY_CANDIDATE, "CTRL-9", 0.9d, false));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ConfidenceSeriesStats.of(ConfidenceSeries.Z_ALIGNMENT, mixed));
        assertTrue(ex.getMessage().contains("观察点序列不符"), ex.getMessage());
    }

    // =====================================================================
    // 红线二：产物里没有患者可识别明文
    // =====================================================================

    @Test
    @DisplayName("红线：导出文件无患者可识别明文（拿语料里的姓名/证件/手机号逐个搜产物）")
    void test_export_has_no_plaintext_identifiers() throws Exception {
        JdbcModelAuditStore store = store();
        store.record(daySamples());
        for (ModelCallRecord r : dayRecords()) {
            store.record(r);
        }

        ModelAuditExporter exporter = new ModelAuditExporter(store);
        Path out = tempDir.resolve("model-audit-20261020.csv");
        ModelAuditExporter.ExportResult result = exporter.writeTo(DAY, SHANGHAI, out, true);

        assertEquals(dayRecords().size(), result.dataRows(), "导出行数必须等于当日调用次数");
        byte[] bytes = Files.readAllBytes(out);
        assertArrayEquals(ModelAuditExporter.UTF8_BOM, Arrays.copyOf(bytes, 3),
                "Excel 打开无 BOM 的 UTF-8 会把中文显示成乱码，默认必须带 BOM");
        String csv = Files.readString(out, StandardCharsets.UTF_8);

        for (String secret : SECRETS) {
            assertFalse(csv.contains(secret), "导出产物里出现了可识别明文：" + secret);
        }

        String[] lines = csv.split("\n");
        assertEquals(ModelAuditExporter.csvHeader(), stripBom(lines[0]), "首行必须是表头");
        // 表头之外确有内容，否则下面这圈断言会因为「文件是空的」而空过
        assertEquals(dayRecords().size(), countDataRows(csv));
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isEmpty()) {
                continue;
            }
            AuditTextGuard.assertClean(lines[i]);
            for (char c : lines[i].toCharArray()) {
                assertTrue(c < 0x80, "数据区第 " + (i + 1) + " 行含非 ASCII 字符：U+"
                        + String.format("%04X", (int) c));
            }
        }

        assertTrue(csv.contains("READ_TIMEOUT"), "错误码必须逐字出现，运维要靠它定位哪类失败");
        assertTrue(csv.contains("chatcmpl-stub-z_field_map"),
                "requestId 原样保留，便于与服务端日志对齐");
    }

    @Test
    @DisplayName("把中文姓名与身份证号硬塞进入参摘要，出口闸仍会挡下并拒绝写出")
    void test_guard_blocks_a_summary_that_smuggled_plaintext() throws Exception {
        // 这是本组用例真正想守住的那一种失败：有人为了排障把字段名换成字段值，
        // 摘要于是带上了患者明细。正常路径的结构化断言拦不住这一种，
        // 这里从产物侧证明它被挡住了。
        String smuggled = "z_field_map | PID-5=张伟 | IDCARD=310101198505121567 | TEL 13901234567";
        JdbcModelAuditStore store = store();
        store.record(new ModelCallRecord(at(1), "stub-model-local", "z_field_map", 700L,
                12, 6, "chatcmpl-stub-smuggle", false, smuggled));

        ModelAuditExporter exporter = new ModelAuditExporter(store);
        String csv = exporter.exportCsv(DAY, SHANGHAI);
        assertTrue(csv.contains("z_field_map | PID-5=** | IDCARD=*** | TEL ***"), csv);
        for (String secret : SECRETS) {
            assertFalse(csv.contains(secret), "掩码之后仍出现了可识别明文：" + secret);
        }
        // 最后一道闸守的是「掩码被绕过」的那一种情形：它认的是整类形态，不认具体某个人
        assertThrows(IllegalStateException.class,
                () -> AuditTextGuard.assertClean("PID-5=张伟; IDCARD=310101198505121567"));
        assertThrows(IllegalStateException.class, () -> AuditTextGuard.assertClean("ref=13901234567"));

        // 逐格看：严格档把中文与长数字串都替换掉，列还在、内容已经不可识别
        assertEquals("z_field_map | PID-5=** | IDCARD=*** | TEL ***",
                AuditTextGuard.maskStrict(smuggled),
                "「PID-5」里的 5 只有一位，不触发长数字串规则；姓名两个字各成一个 *");
        // 协议级标识走宽松档：中文仍挡、长数字串保留（服务端自增 ID 是常态）
        assertEquals("req-**-1234", AuditTextGuard.maskAsciiOnly("req-张伟-1234"), "两个汉字各一个 *");
        assertEquals("chatcmpl-13901234567", AuditTextGuard.maskAsciiOnly("chatcmpl-13901234567"));
        // 该留的数字不该被误伤：耗时与 token 都在 8 位以内
        assertEquals("elapsed=820 prompt=21 total=34",
                AuditTextGuard.maskStrict("elapsed=820 prompt=21 total=34"));
        // 端点没返回 id 时的占位是中文，严格档照样挡——否则每一行降级记录都会漏一个
        assertEquals("(***)", AuditTextGuard.maskAsciiOnly(ModelCallRecord.NO_REQUEST_ID),
                "「未返回」三个汉字各一个 * —— 不设闸的话每一行降级记录都会漏一次");
    }

    // =====================================================================
    // 导出：区间语义、排序、可复现
    // =====================================================================

    @Test
    @DisplayName("导出只取当日区间，左闭右开；换个时区「那一天」就是另一个区间")
    void test_export_window_is_half_open_and_zone_aware() throws Exception {
        JdbcModelAuditStore store = store();
        // 三条分别落在：当日 00:00 整点之前 1 毫秒、当日 00:00 整点、次日 00:00 整点
        store.record(ok(DAY_START.minusMillis(1), "z_field_map", 500L, 10));
        store.record(ok(DAY_START, "z_field_map", 510L, 11));
        store.record(ok(Instant.parse("2026-10-20T16:00:00Z"), "identity_candidates", 520L, 12));

        ModelAuditExporter exporter = new ModelAuditExporter(store);
        assertEquals(1, countDataRows(exporter.exportCsv(DAY, SHANGHAI)),
                "只该取到当日 00:00 整点那一条：左闭所以收，右开所以不收次日整点");

        // 同一份数据换个时区，「那一天」整个挪走：东八区取到第二条（z_field_map），
        // UTC 取到第三条（次日 00:00 整点）——这正是 --tz 必须出现在命令行上的理由
        String csvUtc = exporter.exportCsv(DAY, ZoneId.of("UTC"));
        assertEquals(1, countDataRows(csvUtc));
        assertTrue(csvUtc.contains("identity_candidates"), "UTC 日切取到的应是第三条：" + csvUtc);
        assertFalse(exporter.exportCsv(DAY, SHANGHAI).contains("identity_candidates"));
    }

    @Test
    @DisplayName("同一批数据连导两次逐字节相同（无采样、无随机）")
    void test_export_is_reproducible() throws Exception {
        JdbcModelAuditStore store = store();
        for (ModelCallRecord r : dayRecords()) {
            store.record(r);
        }
        ModelAuditExporter exporter = new ModelAuditExporter(store);
        assertEquals(exporter.exportCsv(DAY, SHANGHAI), exporter.exportCsv(DAY, SHANGHAI));
        assertEquals(ModelAuditExporter.render(dayRecords(), SHANGHAI),
                ModelAuditExporter.render(dayRecords(), SHANGHAI));
    }

    @Test
    @DisplayName("含逗号的入参摘要按 CSV 规则加引号，转义后逐字还原，行数不变")
    void test_cells_are_quoted_per_csv_rules() {
        String csv = ModelAuditExporter.render(List.of(
                new ModelCallRecord(at(1), "stub-model-local", "z_field_map", 800L, 20, 10,
                        "chatcmpl-x", false, "a,b \"q\" c")), SHANGHAI);
        String data = csv.split("\n")[1];
        assertTrue(data.endsWith("\"a,b \"\"q\"\" c\""), "含逗号与引号的单元格必须加引号并双写引号：" + data);
        assertEquals(1, countDataRows(csv), "加引号不得改变行数");
    }

    // =====================================================================
    // 两个指标
    // =====================================================================

    @Test
    @DisplayName("指标一低置信占比与指标二降级率各按各的分母算，不互相顶替")
    void test_two_metrics_use_their_own_denominators() {
        ConfidenceBucketReport report =
                ConfidenceBucketReport.of(DAY, SHANGHAI, daySamples(), dayRecords());

        // 指标一：身份序列非高可信 = (9 - 5) / 9
        assertEquals(4.0d / 9.0d, report.lowConfidenceRatio(), 1e-9);
        // 指标二：调用次数口径 = 2 / 7
        assertEquals(2.0d / 7.0d, report.degradationRatio(), 1e-9);
        assertEquals(2, report.audit().degradedCalls());
        assertEquals(7, report.audit().totalCalls());
        // 观察点口径的降级占比另给一个数：两个口径并列呈现，而不是二选一
        assertEquals(2.0d / 9.0d, report.degradedSampleRatio(), 1e-9);
        assertEquals(132, report.audit().totalTokens(), "prompt 89 + completion 43");
        assertEquals(937L, report.audit().avgElapsedMillis(), "(820+640+590+1500+1500+700+810)/7 取整");
        assertEquals(1500L, report.audit().maxElapsedMillis());
    }

    @Test
    @DisplayName("工作量按报文去重：4 个需复核观察点分布在 4 条报文上，报文数仍是 4")
    void test_workload_is_counted_per_message_not_per_sample() {
        ConfidenceBucketReport report =
                ConfidenceBucketReport.of(DAY, SHANGHAI, daySamples(), dayRecords());
        ConfidenceBucketReport.PendingConfirmEstimate est = report.pendingConfirmEstimate();
        assertEquals(5, est.subjects(), "身份序列共 5 条报文");
        assertEquals(4, est.needsReview(),
                "CTRL-1 全高可信不看；CTRL-2/3/4/5 都要看——CTRL-3 是因为降级，"
                        + "它的分数虽在 HIGH 档但没被模型看过");
        assertEquals(4.0d / 5.0d, est.ratio(), 1e-9);
        assertTrue(est.zhSummary().contains("预计需人工复核 4 条"), est.zhSummary());
    }

    @Test
    @DisplayName("取不到控制号的观察点不算进报文数，如实另报条数：工作量宁可少报不可虚高")
    void test_blank_subjects_are_reported_separately_not_guessed() {
        List<ConfidenceSample> s = List.of(
                sample(at(1), ConfidenceSeries.IDENTITY_CANDIDATE, "", 0.5d, false),
                sample(at(2), ConfidenceSeries.IDENTITY_CANDIDATE, null, 0.5d, false),
                sample(at(3), ConfidenceSeries.IDENTITY_CANDIDATE, "CTRL-7", 0.5d, false));
        ConfidenceBucketReport report = ConfidenceBucketReport.of(DAY, SHANGHAI, s, List.of());
        ConfidenceBucketReport.PendingConfirmEstimate est = report.pendingConfirmEstimate();

        assertEquals(1, est.subjects(),
                "空引用归不到具体报文，就不参与按报文去重——按「每条空引用各算一条报文」处理，"
                        + "会把「一条报文贡献 3 个候选」错算成 3 份工作量");
        assertEquals(1, est.needsReview());
        assertEquals(2, est.unattributedSamples(), "原始条数如实另给，不靠猜补回报文数");
        assertTrue(est.zhSummary().contains("2 个观察点取不到控制号"), est.zhSummary());
    }

    // =====================================================================
    // 观察点的产生口径
    // =====================================================================

    @Test
    @DisplayName("Z 段观察点：合上的按分数归档，一无所得的记一条未给（降级标记同时带上）")
    void test_z_field_map_samples_land_in_right_buckets() {
        Map<String, ZFieldMap.LocalFieldBinding> bindings = new LinkedHashMap<>();
        bindings.put("ZX1.3", new ZFieldMap.LocalFieldBinding("pastHistorySummary", 0.82d, "语义一致"));
        bindings.put("ZX2.3", new ZFieldMap.LocalFieldBinding("nextFollowUpDate", 0.61d, "字面近似"));
        ZFieldMap map = new ZFieldMap(bindings);

        List<ConfidenceSample> merged = ConfidenceSample.fromZFieldMap(at(1), "CTRL-1", map, false);
        assertEquals(2, merged.size());
        assertEquals(ConfidenceBucket.MEDIUM, merged.get(0).bucket());
        assertEquals(ConfidenceBucket.LOW, merged.get(1).bucket());

        List<ConfidenceSample> nothing =
                ConfidenceSample.fromZFieldMap(at(1), "CTRL-3", ZFieldMap.empty(), true);
        assertEquals(1, nothing.size(), "模型被问过却没答，仍是一条观察——否则分桶总数少一条");
        assertEquals(ConfidenceBucket.UNKNOWN, nothing.get(0).bucket());
        assertTrue(nothing.get(0).degraded());
        assertFalse(nothing.get(0).hasConfidence());
    }

    @Test
    @DisplayName("身份报告的四种结论产出不同条数：证件冲突整条拒收、零观察点；没结论补一条 UNKNOWN")
    void test_identity_report_outcomes_produce_distinct_sample_counts() {
        InboundIdentity identity = new InboundIdentity("PAT-0001", "张伟", "19850512", "aabbccdd");

        // 证件冲突：整条拒收，不占人工复核工作量
        IdentityMatchReport rejected = IdentityMatchReport.conflictRejected(
                IdentityAnchorLevel.IDCARD,
                List.of(new IdentityMatchReport.IdCardConflict("LOCMRN-0001", "证件摘要不一致")));
        assertEquals(0, ConfidenceSample.fromIdentity(at(1), "CTRL-X", rejected).size(),
                "拒收条目的工作量在拒收台账上，不该混进「预计待确认」");

        // 没给结论：补一条 UNKNOWN，让 UNKNOWN 桶有真实含义
        IdentityMatchReport none = IdentityMatchReport.noCandidate(IdentityAnchorLevel.MISSING, "无可比字段");
        List<ConfidenceSample> noCandidate = ConfidenceSample.fromIdentity(at(1), "CTRL-Y", none);
        assertEquals(1, noCandidate.size());
        assertEquals(ConfidenceBucket.UNKNOWN, noCandidate.get(0).bucket());

        // 降级但给出了确定性候选：候选各一条，外加一条「模型没给结论」
        IdentityMatchReport degradedReport = IdentityMatchReport.modelDegraded(
                IdentityAnchorLevel.CROSS_REF, Candidates.empty(), "模型不可达");
        List<ConfidenceSample> degradedSamples =
                ConfidenceSample.fromIdentity(at(2), "CTRL-Z", degradedReport);
        assertEquals(1, degradedSamples.size());
        assertTrue(degradedSamples.get(0).degraded());
        assertTrue(degradedReport.requiresHumanConfirmation());
        assertFalse(rejected.requiresHumanConfirmation());
        assertEquals("19850512", identity.birthDate());
    }

    @Test
    @DisplayName("NaN 不是「没给」：上游算错一处要在入库前就炸掉，不能混进报表")
    void test_nan_confidence_is_rejected_at_construction() {
        assertThrows(IllegalArgumentException.class,
                () -> sample(at(1), ConfidenceSeries.Z_ALIGNMENT, "CTRL-N", Double.NaN, false));
        assertThrows(IllegalArgumentException.class, () -> ConfidenceBucket.of(Double.NaN));
        assertEquals(ConfidenceBucket.UNKNOWN, ConfidenceBucket.of(-1d));
        assertEquals(ConfidenceBucket.LOW, ConfidenceBucket.of(0d));
        assertEquals(ConfidenceBucket.MEDIUM, ConfidenceBucket.of(0.75d));
        assertEquals(ConfidenceBucket.HIGH, ConfidenceBucket.of(0.90d));
        assertEquals("[0.75, 0.90)", ConfidenceBucket.MEDIUM.zhRange());
        assertEquals("未给分数", ConfidenceBucket.UNKNOWN.zhRange());
    }

    // =====================================================================
    // 落库
    // =====================================================================

    @Test
    @DisplayName("观察点与审计行落库后可原样读回（时刻、序列、分数、降级标记都逐字一致）")
    void test_samples_and_records_round_trip_through_jdbc() throws Exception {
        JdbcModelAuditStore store = store();
        store.record(daySamples());
        for (ModelCallRecord r : dayRecords()) {
            store.record(r);
        }

        List<ConfidenceSample> read = store.samplesBetween(DAY_START, at(15));
        assertEquals(daySamples(), read, "读回必须与写入逐字一致（含时刻与降级标记）");
        for (ConfidenceSample s : read) {
            assertTrue(s.at().isAfter(DAY_START), "读回来的时刻必须落在区间内");
        }

        List<ModelCallRecord> records = store.recordsBetween(DAY_START, at(15));
        assertEquals(dayRecords().size(), records.size());
        for (int i = 0; i < records.size(); i++) {
            assertEquals(dayRecords().get(i).errorCode(), records.get(i).errorCode());
            assertEquals(dayRecords().get(i).degraded(), records.get(i).degraded());
            assertEquals(dayRecords().get(i).elapsedMillis(), records.get(i).elapsedMillis());
            assertEquals(dayRecords().get(i).at(), records.get(i).at());
        }

        // 区间必须左闭右开，且起点不早于终点直接拒绝——把这两条做进 API 而不是靠调用方记得
        assertThrows(IllegalArgumentException.class, () -> store.samplesBetween(at(15), at(15)));
        assertThrows(IllegalArgumentException.class, () -> store.samplesBetween(at(15), at(1)));
    }

    @Test
    @DisplayName("审计写失败降级成 WARNING：调用本身照常成功，只有审计那一行没写进去")
    void test_audit_write_failure_does_not_throw() {
        JdbcModelAuditStore broken = new JdbcModelAuditStore(() -> {
            throw new IllegalStateException("库连不上");
        });
        // record 的契约是「实现不应把异常抛回调用链」：这里用一个连不上的提供者证明它守住了
        broken.record(ok(at(1), "z_field_map", 700L, 12));
        broken.record(daySamples());
    }

    @Test
    @DisplayName("入参摘要超长时截断到列宽并留痕，不让一条旁路数据把写入拖成异常")
    void test_oversized_summary_is_clamped() throws Exception {
        StringBuilder sb = new StringBuilder("z_field_map | ");
        for (int i = 0; i < 200; i++) {
            sb.append("ZX").append(i).append(".3(len=11) digest=ab12cd34 | ");
        }
        String clamped = clamp(sb.toString());
        assertTrue(clamped.length() <= 1024, "截断后必须放得进 VARCHAR(1024)，实际 " + clamped.length());
        assertTrue(clamped.endsWith("[摘要截断]"), "截断必须留痕，否则下游看不出这行被切过");

        String normal = "z_field_map | ZX1.2(len=6) digest=ab12cd34";
        assertEquals(normal, clamp(normal), "未超长的摘要逐字不动，不许被无差别地截");
    }

    /** 反射调包级 clampSummary —— 测试与生产走的是同一段代码。 */
    private static String clamp(String summary) throws Exception {
        var m = JdbcModelAuditStore.class.getDeclaredMethod("clampSummary", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, summary);
    }

    // =====================================================================
    // CLI
    // =====================================================================

    @Test
    @DisplayName("CLI：confidence-buckets 打出四个桶行与两个指标；audit 导出到文件")
    void test_cli_prints_report_and_exports() throws Exception {
        JdbcModelAuditStore store = store();
        store.record(daySamples());
        for (ModelCallRecord r : dayRecords()) {
            store.record(r);
        }

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(buf, true, StandardCharsets.UTF_8);
        int code = ModelCli.run(new String[]{
                        "model", "confidence-buckets", "--date", "2026-10-20", "--tz", "Asia/Shanghai"},
                out, out, dbConfig());
        assertEquals(0, code);
        String text = buf.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("模型置信度分桶统计 · 2026-10-20（时区 Asia/Shanghai）"), text);
        assertTrue(text.contains("高可信 [0.90, 1]"), "四个桶都要有行：" + text);
        assertTrue(text.contains("未给分数"), "未给桶同样要有行：" + text);
        assertTrue(text.contains("指标一 · 低置信占比"), text);
        assertTrue(text.contains("44.4%"), "低置信占比 = 4/9：" + text);
        assertTrue(text.contains("28.6%"), "降级率 = 2/7：" + text);
        assertTrue(text.contains("需复核 4 条 / 共 5 条"), text);
        assertTrue(text.contains("模型调用 7 次，其中降级 2 次"), text);

        ByteArrayOutputStream buf2 = new ByteArrayOutputStream();
        PrintStream out2 = new PrintStream(buf2, true, StandardCharsets.UTF_8);
        Path target = tempDir.resolve("cli-audit.csv");
        int code2 = ModelCli.run(new String[]{
                        "model", "audit", "--date", "2026-10-20", "--tz", "Asia/Shanghai",
                        "--out", target.toString()},
                out2, out2, dbConfig());
        assertEquals(0, code2);
        assertTrue(buf2.toString(StandardCharsets.UTF_8).contains("7 行"),
                buf2.toString(StandardCharsets.UTF_8));
        assertTrue(Files.readString(target, StandardCharsets.UTF_8).contains("READ_TIMEOUT"));
    }

    @Test
    @DisplayName("CLI 用法错误在读配置之前就被拒：不认识的子命令不会被伪装成配置问题")
    void test_cli_rejects_bad_subcommand_before_reading_config() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(err, true, StandardCharsets.UTF_8);
        assertEquals(ModelCli.EXIT_USAGE,
                ModelCli.run(new String[]{"model", "auditk", "--date", "2026-10-20"}, ps, ps));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("未知子命令"),
                err.toString(StandardCharsets.UTF_8));

        ByteArrayOutputStream err2 = new ByteArrayOutputStream();
        PrintStream ps2 = new PrintStream(err2, true, StandardCharsets.UTF_8);
        assertEquals(ModelCli.EXIT_USAGE,
                ModelCli.run(new String[]{"model", "audit", "--date", "20/10/2026"}, ps2, ps2));
        assertTrue(err2.toString(StandardCharsets.UTF_8).contains("--date 取值不合法"),
                err2.toString(StandardCharsets.UTF_8));

        ByteArrayOutputStream err3 = new ByteArrayOutputStream();
        PrintStream ps3 = new PrintStream(err3, true, StandardCharsets.UTF_8);
        assertEquals(ModelCli.EXIT_USAGE,
                ModelCli.run(new String[]{"model", "audit", "--tz", "Mars/Olympus"}, ps3, ps3));
        assertTrue(err3.toString(StandardCharsets.UTF_8).contains("--tz 取值不合法"),
                err3.toString(StandardCharsets.UTF_8));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream ps4 = new PrintStream(out, true, StandardCharsets.UTF_8);
        assertEquals(0, ModelCli.run(new String[]{"model", "--help"}, ps4, ps4));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("置信度分桶只读排障通道"));
    }

    @Test
    @DisplayName("CLI 缺数据库配置时退出码 2 并点名变量，不回落到本地替身")
    void test_cli_fails_fast_without_db_config() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(err, true, StandardCharsets.UTF_8);
        int code = ModelCli.run(new String[]{"model", "audit", "--date", "2026-10-20"}, ps, ps,
                () -> {
                    throw cn.hospital.region.archive.config.ConfigException.missing(
                            ReadOnlyDbConfig.ENV_PASSWORD);
                });
        assertEquals(ModelCli.EXIT_CONFIG_ERROR, code);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains(ReadOnlyDbConfig.ENV_PASSWORD),
                err.toString(StandardCharsets.UTF_8));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("不回落到本地替身"),
                err.toString(StandardCharsets.UTF_8));
    }

    private static java.util.function.Supplier<ReadOnlyDbConfig> dbConfig() {
        return () -> new ReadOnlyDbConfig(H2_URL, "sa", "");
    }

    // =====================================================================
    // 主路径：观察点真的会在报文处理时被记下来
    // =====================================================================

    @Test
    @DisplayName("接入主路径真的记账：有 Z 段的报文按映射条数记，没 Z 段与未启用模型时一条都不记")
    void test_ingest_path_records_samples_only_when_model_was_consulted() throws Exception {
        // 装配一个只回答固定 Z 段映射的网关替身：它是一个真实实现 ModelGateway 的对象，
        // 不是 mock 库拦截器，走的是与生产完全相同的 ResilientModelGateway 降级包装。
        Map<String, ZFieldMap.LocalFieldBinding> bindings = new LinkedHashMap<>();
        bindings.put("ZX1.3", new ZFieldMap.LocalFieldBinding("pastHistorySummary", 0.82d, "语义一致"));
        bindings.put("ZX2.3", new ZFieldMap.LocalFieldBinding("nextFollowUpDate", 0.77d, "语义一致"));
        ModelGateway stub = new ModelGateway() {
            @Override
            public ZFieldMap alignZFields(List<ZSegment> zSegments) {
                return new ZFieldMap(bindings);
            }

            @Override
            public Candidates identityCandidates(String pid3, String pid5, String pid7,
                                                 LocalPatientIndex localIndex) {
                return Candidates.empty();
            }
        };

        List<ConfidenceSample> captured = new ArrayList<>();
        ArchiveIngestService service = ingestService(stub, captured);
        CountingPatientIndexWriter writer = (CountingPatientIndexWriter) LAST_WRITER.get();

        ArchiveIngestService.IngestOutcome withZ = service.ingest(er7Bytes("CTRL-T28-Z1", true));
        assertEquals(ArchiveIngestService.IngestStatus.ACCEPTED, withZ.status());
        assertEquals(2, captured.size(), "两条映射 → 两个观察点");
        assertEquals(ConfidenceSeries.Z_ALIGNMENT, captured.get(0).series());
        assertEquals("CTRL-T28-Z1", captured.get(0).subjectRef(), "引用是 MSH-10 控制号");
        assertEquals(ConfidenceBucket.MEDIUM, captured.get(0).bucket());
        assertEquals(ConfidenceBucket.MEDIUM, captured.get(1).bucket());

        service.ingest(er7Bytes("CTRL-T28-Z2", false));
        assertEquals(2, captured.size(),
                "没有 Z 段的报文没有可对齐的东西，记一条「未给置信度」只会让工作量口径虚高");

        // 未启用模型时装配一条：一个观察点都不该凭空出现
        List<ConfidenceSample> none = new ArrayList<>();
        ingestService(null, none).ingest(er7Bytes("CTRL-T28-Z3", true));
        assertEquals(0, none.size(), "未装配模型时不得凭空产生观察点");

        assertTrue(writer.neverCalled(), "ingest 绝不碰患者主索引——观察点记账是旁路，不是落地通道");
    }

    /** H2 上起一个真装配的接入服务；顺便把计数写端口留给调用方断言。 */
    private static final ThreadLocal<PatientIndexWriter> LAST_WRITER = new ThreadLocal<>();

    private static ArchiveIngestService ingestService(ModelGateway gateway,
                                                      List<ConfidenceSample> captured) {
        LedgerStore ledgerStore = new JdbcLedgerStore(ModelAuditExporterTest::openConnection);
        CountingPatientIndexWriter writer = new CountingPatientIndexWriter();
        LAST_WRITER.set(writer);
        ResilientModelGateway model = gateway == null
                ? null
                : new ResilientModelGateway(gateway, new ModelRetryPolicy(0));
        return new ArchiveIngestService(
                new AckComposer("CHSCOMMUNITY", "REGIONAL_ARCHIVE"),
                ledgerStore,
                new IdempotencyGuard(ledgerStore),
                writer,
                ArchiveIngestService.BatchIdResolver.sendingAppAndDay(),
                model,
                captured::addAll);
    }

    /** 构造一条带 / 不带 Z 段的合法报文（MSH-18 落在正确位次）。 */
    private static byte[] er7Bytes(String controlId, boolean withZ) {
        String er7 = "MSH|^~\\&|CHSCOMMUNITY|REGIONAL_ARCHIVE|REGIONAL_ARCHIVE|FACILITY_A|202610201000"
                + "||ADT^A08|" + controlId + "|P|2.5.1|||AL|NE||UNICODE UTF-8\r"
                + "PID|1||PAT-T28-" + controlId + "^^^REGIONAL^MR||张^伟||19850512|M|||"
                + "310101198505121567\r"
                + (withZ ? "ZX1|1|2|既往高血压病史，近两年服药规律|\r" : "");
        return er7.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("语料报文能被真实解析（红线用例不得建在造不出来的输入上）")
    void test_fixture_messages_are_parsable() {
        Hl7Message msg = Hl7Parser.parse(
                new String(er7Bytes("CTRL-T28-Z1", true), StandardCharsets.UTF_8));
        assertEquals("ADT^A08", msg.field("MSH", 9));
        assertEquals("CTRL-T28-Z1", msg.field("MSH", 10));
        assertEquals("UNICODE UTF-8", msg.field("MSH", 18), "字符集必须落在 MSH-18 而不是 MSH-17");
        assertFalse(ZSegmentReader.read(msg).isEmpty());
        assertTrue(ZSegmentReader.read(
                Hl7Parser.parse(new String(er7Bytes("CTRL-T28-Z2", false), StandardCharsets.UTF_8)))
                .isEmpty(), "不带 Z 段的语料必须真的没有 Z 段");
    }

    /** 去掉可能存在的 UTF-8 BOM —— {@code Files.readString} 不会自动去掉它。 */
    private static String stripBom(String line) {
        return line.startsWith("\uFEFF") ? line.substring(1) : line;
    }

    private static int countDataRows(String csv) {
        int n = 0;
        for (String line : csv.split("\n")) {
            if (!line.isBlank() && !stripBom(line).startsWith("本地时间,")) {
                n++;
            }
        }
        return n;
    }
}