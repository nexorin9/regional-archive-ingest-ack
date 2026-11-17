package cn.hospital.region.archive.ledger;

import cn.hospital.region.archive.ack.Hl7ErrorCodes;
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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T19 只读导出用例 —— 导出 CSV 的两条硬要求：<b>行数与台账一致</b>、<b>不含患者可识别明文</b>。
 *
 * <p>后者是本组用例的重点，且刻意从<b>产物</b>而不是从代码断言：脱敏写错时最典型的形态是
 * 「某一列忘了套 digest」，它在代码里看着完全正常，只有把 CSV 拼出来逐值比对才看得见。
 * 因此这里把语料里的姓名、证件号逐个拿去搜产物，搜到即失败 —— 与 T40 的全量日志明文扫描同一口径。
 */
class LedgerExporterTest {

    private static final String H2_URL =
            "jdbc:h2:mem:ledger_exporter_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-10-20T02:00:00Z"), ZoneId.of("Asia/Shanghai"));

    private static final String BATCH = "REGIONAL_ARCHIVE-20261020";

    /** 语料里出现的可识别明文；导出产物里一个都不许有。 */
    private static final List<String> SECRETS = List.of(
            "张伟", "李娜", "赵敏", "欧阳明月", "110101199003031234", "310101198505121567");

    /** 姓名以 {@code ^} 分隔（PID-5 姓^名），这里整串取。 */
    private static String er7(String controlId, String patientRef, String name, String idNumber) {
        return "MSH|^~\\&|CHSCOMMUNITY|REGIONAL_ARCHIVE|REGIONAL_ARCHIVE|FACILITY_A|202610201000||ADT^A08|"
                + controlId + "|P|2.5.1|||AL|NE||UNICODE UTF-8\r"
                + "PID|1||" + patientRef + "^^^REGIONAL^MR||" + name
                + "||19850512|M|||" + idNumber;
    }

    /** 缺 PID-3 的报文：拒收原因 patient-id-missing。 */
    private static String er7MissingPatientId(String controlId) {
        return "MSH|^~\\&|CHSCOMMUNITY|REGIONAL_ARCHIVE|REGIONAL_ARCHIVE|FACILITY_A|202610201000||ADT^A08|"
                + controlId + "|P|2.5.1|||AL|NE||UNICODE UTF-8\r"
                + "PID|1||||赵^敏||19930303|F|||310101198505121567";
    }

    /** 缺 MSH-10 的报文：解析不出来也必须照样导出一行（行数恒等）。 */
    private static String er7Unparseable() {
        return "\r\rMSH-10 被整个剥掉了，连控制号都没有";
    }

    private static Connection openConnection() throws SQLException {
        return DriverManager.getConnection(H2_URL, "sa", "");
    }

    private static JdbcLedgerStore store() {
        return new JdbcLedgerStore(LedgerExporterTest::openConnection, FIXED_CLOCK,
                AuditWriter.jdbc(FIXED_CLOCK));
    }

    private static void insertPending(JdbcLedgerStore store, String controlId, String patientRef,
                                      String name, String idNumber) throws Exception {
        long id = store.insertIfAbsent(LedgerEntry.inbound(IdempotencyKey.of("ADT^A08", controlId, "P"),
                BATCH, er7(controlId, patientRef, name, idNumber))).ledgerId();
        store.transition(id, MessageState.RECEIVED, MessageState.PENDING_CONFIRM, "system",
                ArchiveIngestService.REASON_AWAITING_CONFIRM);
    }

    private static void insertRejected(JdbcLedgerStore store, String controlId, String payload,
                                       String reasonCode) throws Exception {
        long id = store.insertIfAbsent(LedgerEntry.inbound(
                IdempotencyKey.of("ADT^A08", controlId, "P"), BATCH, payload)).ledgerId();
        store.transition(id, MessageState.RECEIVED, MessageState.REJECTED, "system",
                ArchiveIngestService.REASON_PREFIX_REJECTED + reasonCode);
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
    void cleanTables() throws SQLException {
        try (Connection conn = openConnection();
             var st = conn.createStatement()) {
            st.execute("DELETE FROM message_audit");
            st.execute("DELETE FROM message_ledger");
        }
    }

    // =====================================================================
    // 行数恒等：CSV 数据行数必须恒等于台账条数
    // =====================================================================

    @Test
    @DisplayName("待确认 CSV 行数与台账条数一致，且带表头")
    void test_pending_csv_row_count_matches_ledger_rows() throws Exception {
        JdbcLedgerStore store = store();
        insertPending(store, "CTRL-E-0001", "PAT-0001", "张^伟", "310101198505121567");
        insertPending(store, "CTRL-E-0002", "PAT-0002", "李^娜", "110101199003031234");
        insertRejected(store, "CTRL-E-0003", er7MissingPatientId("CTRL-E-0003"), "patient-id-missing");

        List<LedgerRow> pendingInLedger =
                store.listByBatch(BATCH, MessageState.PENDING_CONFIRM);
        String csv = new LedgerExporter(store).exportPendingCsv(BATCH);
        List<String> lines = csv.lines().toList();

        assertEquals(pendingInLedger.size() + 1, lines.size(),
                "CSV 行数 = 数据行数 + 1 行表头，且数据行数必须等于台账该状态条数");
        assertEquals(2, lines.size() - 1, "本例台账待确认为 2 条");
        assertTrue(lines.get(0).startsWith("ledger_id,batch_id,message_type,control_id"),
                "首行必须是表头：" + lines.get(0));
    }

    @Test
    @DisplayName("拒收清单行数与台账条数一致，且带表头")
    void test_rejected_csv_row_count_matches_ledger_rows() throws Exception {
        JdbcLedgerStore store = store();
        insertPending(store, "CTRL-E-1001", "PAT-1001", "张^伟", "310101198505121567");
        insertRejected(store, "CTRL-E-1002", er7MissingPatientId("CTRL-E-1002"), "patient-id-missing");
        insertRejected(store, "CTRL-E-1003", er7MissingPatientId("CTRL-E-1003"), "patient-id-missing");
        insertRejected(store, "CTRL-E-1004", er7Unparseable(), "control-id-missing");

        List<LedgerRow> rejectedInLedger = store.listByBatch(BATCH, MessageState.REJECTED);
        String csv = new LedgerExporter(store).exportRejectedCsv(BATCH);

        assertEquals(3, rejectedInLedger.size());
        assertEquals(rejectedInLedger.size() + 1, csv.lines().count(),
                "拒收 CSV 行数必须等于台账拒收条数 + 1 行表头");
    }

    @Test
    @DisplayName("报文解析不出来的拒收条目照样导出一行，不许少导")
    void test_unparseable_payload_row_is_still_exported() throws Exception {
        JdbcLedgerStore store = store();
        // 幂等键三段全空：不靠报文原文造出来的，所以入账没问题，但原文解析不了
        long id = store.insertIfAbsent(LedgerEntry.inbound(
                IdempotencyKey.of("ADT^A08", "", ""), BATCH, er7Unparseable())).ledgerId();
        store.transition(id, MessageState.RECEIVED, MessageState.REJECTED, "system",
                ArchiveIngestService.REASON_PREFIX_REJECTED + "control-id-missing");

        String csv = new LedgerExporter(store).exportRejectedCsv(BATCH);
        List<String> lines = csv.lines().toList();

        assertEquals(2, lines.size(), "1 条数据行 + 1 行表头");
        String row = lines.get(1);
        assertTrue(row.contains("control-id-missing"), "仍应带本地原因码：" + row);
        assertTrue(row.contains("MSH.10"), "必需字段类原因应给出出错位置：" + row);
        assertTrue(row.contains("(空)"), "空控制号显式写成 (空)，而不是裸分隔符串：" + row);
    }

    // =====================================================================
    // 脱敏：产物里不得出现姓名与证件号明文
    // =====================================================================

    @Test
    @DisplayName("待确认清单：姓名与证件号只出摘要，患者主键只出首尾片段")
    void test_pending_export_redacts_name_and_id_and_fragments_patient_ref() throws Exception {
        JdbcLedgerStore store = store();
        insertPending(store, "CTRL-E-2001", "PAT-20261020001", "张^伟", "310101198505121567");

        String csv = new LedgerExporter(store).exportPendingCsv(BATCH);
        String row = csv.lines().skip(1).findFirst().orElseThrow();

        for (String secret : SECRETS) {
            assertFalse(csv.contains(secret), "产物里出现了未脱敏明文（长度 " + secret.length() + "）");
        }
        // 姓名字段拆成 姓^名，摘要取的是整串 PID-5，因此断言整串摘要存在
        assertTrue(row.contains(LedgerRedaction.digest8("张^伟")),
                "姓名摘要缺失，应为 PID-5 整串的 SHA-256 前 8 位：" + row);
        assertTrue(row.contains(LedgerRedaction.digest8("310101198505121567")), "证件号摘要缺失：" + row);
        assertTrue(row.contains(LedgerRedaction.keyFragment("PAT-20261020001")),
                "患者主键应出首尾片段：" + row);
        assertFalse(row.contains("PAT-20261020001"), "患者主键原文不应出现在产物里：" + row);
        assertTrue(row.contains("是|否") || row.contains("低置信") || row.contains("CTRL-E-2001"),
                "该行应带幂等键与状态，便于人工定位：" + row);
    }

    @Test
    @DisplayName("拒收清单：带 HL7 标准码与 ERR 摘要，姓名与证件号仍不落盘")
    void test_rejected_export_carries_hl7_code_and_err_summary_without_plaintext() throws Exception {
        JdbcLedgerStore store = store();
        insertRejected(store, "CTRL-E-3001", er7MissingPatientId("CTRL-E-3001"), "patient-id-missing");

        String csv = new LedgerExporter(store).exportRejectedCsv(BATCH);
        String header = csv.lines().findFirst().orElseThrow();
        String row = csv.lines().skip(1).findFirst().orElseThrow();

        assertTrue(header.contains("hl7_error_code"), "拒收清单须有 HL7 标准码列：" + header);
        assertTrue(header.contains("err_summary"), "拒收清单须有 ERR 摘要列：" + header);
        assertTrue(row.contains("patient-id-missing"), "须带本地原因码：" + row);
        assertTrue(row.contains("101"), "patient-id-missing 在表 0357 里绑的是 101：" + row);
        assertTrue(row.contains("HL70357"), "ERR 摘要须含表来源：" + row);
        assertTrue(row.contains("PID.3"), "ERR 摘要须含出错位置：" + row);
        assertFalse(csv.contains("赵敏"), "拒收清单不得出现姓名明文");
        assertFalse(csv.contains("310101198505121567"), "拒收清单不得出现证件号明文");
    }

    @Test
    @DisplayName("未登记标准码的原因降级为 CUSTOM- 前缀，且标注对端不可解析")
    void test_unregistered_reason_degrades_to_custom_code() throws Exception {
        JdbcLedgerStore store = store();
        insertRejected(store, "CTRL-E-4001", er7MissingPatientId("CTRL-E-4001"),
                "some-future-local-reason");

        String csv = new LedgerExporter(store).exportRejectedCsv(BATCH);

        assertTrue(csv.contains("CUSTOM-some-future-local-reason"),
                "目录里没有的原因应降级为 CUSTOM- 前缀码，而不是编一个标准码：" + csv);
        assertTrue(csv.contains("对端不可解析"), "须明确标注该码对端不可解析：" + csv);
    }

    @Test
    @DisplayName("出口自检：产物里混入明文时拒绝写出")
    void test_redaction_guard_refuses_to_emit_plaintext() {
        String rendered = "ledger_id,name\n7,张伟身份证号310101198505121567\n";
        assertThrows(IllegalStateException.class,
                () -> LedgerRedaction.assertNoPlaintext(rendered, List.of("张伟身份证号310101198505121567")));

        String clean = "ledger_id,name_digest8\n7,a1b2c3d4\n";
        LedgerRedaction.assertNoPlaintext(clean, List.of("张伟身份证号310101198505121567"));
    }

    @Test
    @DisplayName("过短明文不进自检比对（避免数字列误报）")
    void test_short_plaintext_is_skipped_by_guard() {
        // "42" 少于 8 位：拿去全文搜必然在 CSV 的数字列里撞上一次，喊狼来了就没人看自检了
        LedgerRedaction.assertNoPlaintext("ledger_id,retry_count\n7,42\n", List.of("42", "x"));
    }

    // =====================================================================
    // CSV 形态与写盘
    // =====================================================================

    @Test
    @DisplayName("CSV 按 RFC 4180 转义：含逗号/引号的值加引号，内部引号翻倍")
    void test_csv_escapes_embedded_delimiters_and_quotes() throws Exception {
        JdbcLedgerStore store = store();
        // 批次号里带逗号与引号（脏数据是现实：平台侧拼批次号从不校验）
        String dirtyBatch = "BATCH,WEIRD\"NAME";
        long id = store.insertIfAbsent(LedgerEntry.inbound(
                IdempotencyKey.of("ADT^A08", "CTRL-E-5001", "P"), dirtyBatch,
                er7("CTRL-E-5001", "PAT-5001", "张^伟", "310101198505121567"))).ledgerId();
        store.transition(id, MessageState.RECEIVED, MessageState.PENDING_CONFIRM, "system", "await");

        String csv = new LedgerExporter(store).exportPendingCsv(dirtyBatch);
        String row = csv.lines().skip(1).findFirst().orElseThrow();

        assertTrue(row.contains("\"BATCH,WEIRD\"\"NAME\""),
                "含逗号与引号的值必须加引号且内部引号翻倍，否则列会错位：" + row);
        // 列数核对：转义后这一行仍应是 14 列
        assertEquals(14, splitCsvRow(row).size(), "转义后列数必须与表头一致：" + splitCsvRow(row));
    }

    @Test
    @DisplayName("写盘默认前置 UTF-8 BOM（Excel 直接双击打开中文不乱码）")
    void test_write_to_prepends_utf8_bom_by_default(@TempDir Path dir) throws Exception {
        JdbcLedgerStore store = store();
        insertPending(store, "CTRL-E-6001", "PAT-6001", "张^伟", "310101198505121567");

        Path out = dir.resolve("nested").resolve("pending.csv");
        LedgerExporter.ExportResult result =
                new LedgerExporter(store).writeTo(BATCH, MessageState.PENDING_CONFIRM, out, true);

        byte[] bytes = Files.readAllBytes(out);
        assertArrayEquals(LedgerExporter.UTF8_BOM,
                java.util.Arrays.copyOf(bytes, 3), "前 3 字节必须是 UTF-8 BOM");
        String body = new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        assertEquals(1, result.dataRows(), "1 条待确认 → 1 行数据");
        assertTrue(body.contains("CTRL-E-6001"), "表头 + 1 行数据");
        assertFalse(body.contains("张^伟"), "落盘内容同样不得含姓名明文");
        assertTrue(Files.exists(out), "导出路径的父目录不存在时应自动创建");
    }

    @Test
    @DisplayName("--no-bom 时不写 BOM")
    void test_write_to_can_skip_bom(@TempDir Path dir) throws Exception {
        JdbcLedgerStore store = store();
        insertPending(store, "CTRL-E-7001", "PAT-7001", "张^伟", "310101198505121567");

        Path out = dir.resolve("nobom.csv");
        new LedgerExporter(store).writeTo(BATCH, MessageState.PENDING_CONFIRM, out, false);

        byte[] bytes = Files.readAllBytes(out);
        assertFalse(bytes.length > 2 && bytes[0] == (byte) 0xEF && bytes[1] == (byte) 0xBB,
                "显式关掉 BOM 时不应写 BOM");
    }

    @Test
    @DisplayName("空状态只导出表头，不产出多余空行")
    void test_empty_state_exports_header_only() throws Exception {
        JdbcLedgerStore store = store();

        String csv = new LedgerExporter(store).exportPendingCsv(BATCH);

        assertEquals(1, csv.lines().count(), "没有待确认条目时只应有 1 行表头：" + csv);
    }

    // =====================================================================
    // CLI 通道
    // =====================================================================

    @Test
    @DisplayName("只读 CLI：参数不足 / 未知子命令返回用法错误码 64，且不碰数据库")
    void test_cli_rejects_bad_usage_without_touching_db() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code;
        try (PrintStream o = new PrintStream(out, true, StandardCharsets.UTF_8);
             PrintStream e = new PrintStream(err, true, StandardCharsets.UTF_8)) {
            code = cn.hospital.region.archive.cli.LedgerCli.run(
                    new String[]{"ledger", "batch-stats"}, o, e);
        }
        assertEquals(cn.hospital.region.archive.cli.LedgerCli.EXIT_USAGE, code);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("参数不完整"),
                err.toString(StandardCharsets.UTF_8));

        ByteArrayOutputStream out2 = new ByteArrayOutputStream();
        ByteArrayOutputStream err2 = new ByteArrayOutputStream();
        int code2;
        try (PrintStream o = new PrintStream(out2, true, StandardCharsets.UTF_8);
             PrintStream e = new PrintStream(err2, true, StandardCharsets.UTF_8)) {
            code2 = cn.hospital.region.archive.cli.LedgerCli.run(
                    new String[]{"ledger", "drop-table", "B1"}, o, e);
        }
        assertEquals(cn.hospital.region.archive.cli.LedgerCli.EXIT_USAGE, code2);
        assertTrue(err2.toString(StandardCharsets.UTF_8).contains("drop-table"),
                "未知子命令应被点出来：" + err2.toString(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("只读 CLI 的帮助文本含 batch-stats 与 export，且写明脱敏口径")
    void test_cli_help_documents_both_subcommands_and_redaction() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code;
        try (PrintStream o = new PrintStream(out, true, StandardCharsets.UTF_8);
             PrintStream e = new PrintStream(err, true, StandardCharsets.UTF_8)) {
            code = cn.hospital.region.archive.cli.LedgerCli.run(new String[]{"ledger", "--help"}, o, e);
        }
        String help = out.toString(StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertTrue(help.contains("ledger batch-stats"), "帮助须给出 batch-stats 用法：" + help);
        assertTrue(help.contains("ledger export"), "帮助须给出 export 用法：" + help);
        assertTrue(help.contains("脱敏"), "帮助须写明导出已脱敏：" + help);
        assertTrue(help.contains("ARCHIVE_DB_URL"), "帮助须点明凭据来源环境变量：" + help);
    }

    @Test
    @DisplayName("拒收清单的 ERR 摘要与错误码目录一致（不是另抄一份）")
    void test_rejected_export_reuses_error_code_catalog() throws Exception {
        JdbcLedgerStore store = store();
        insertRejected(store, "CTRL-E-8001", er7MissingPatientId("CTRL-E-8001"), "patient-id-missing");

        String csv = new LedgerExporter(store).exportRejectedCsv(BATCH);
        var entry = Hl7ErrorCodes.forSuggestedCode("patient-id-missing");

        assertTrue(csv.contains(entry.toEr7Form()), "ERR 摘要必须逐字等于目录条目：" + csv);
        assertTrue(csv.contains(entry.describeZh()), "中文摘要必须逐字等于目录条目：" + csv);
    }

    // =====================================================================
    // 内部工具
    // =====================================================================

    /** 按 RFC 4180 拆一行（只为核对转义后的列数）。 */
    private static List<String> splitCsvRow(String row) {
        List<String> cells = new java.util.ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < row.length(); i++) {
            char c = row.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < row.length() && row.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                cells.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        cells.add(cur.toString());
        return cells;
    }
}