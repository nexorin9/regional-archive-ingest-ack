package cn.hospital.region.archive.obs;

import cn.hospital.region.archive.ack.RejectionReason;
import cn.hospital.region.archive.http.ApiErrorCode;
import cn.hospital.region.archive.identity.HisWriteErrorCode;
import cn.hospital.region.archive.model.ModelErrorCode;
import cn.hospital.region.archive.redelivery.ReconcileErrorCode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T40 用例 —— 脱敏日志出口、错误码总目录、批次可观测报告。
 *
 * <p>三组红线各有各的破法，所以用例也分三组：
 * <ol>
 *   <li><b>患者明文进不了日志</b>：走 {@link RedactedLog} 的类型化字段，
 *       并验证「即使有人把原文硬塞进去，渲染期也会拦下并拒绝落盘」；</li>
 *   <li><b>错误码目录与各源枚举不脱节</b>：五家枚举逐项都能查到，
 *       且 {@code retryable} 与源枚举自述一致（不一致即启动失败）；</li>
 *   <li><b>批次报告数字自洽</b>：四态之和 = 总数、缺数据时给出「未采集」而不是崩。</li>
 * </ol>
 */
class RedactedLogTest {

    // ================================================================ 脱敏日志

    @Test
    @DisplayName("红线：姓名/证件/手机号只出摘要，主键只出首尾片段")
    void test_patient_fields_render_as_digest_or_fragment() {
        RedactedLog.Handle log = RedactedLog.forClass(RedactedLogTest.class);
        String line = log
                .with(RedactedLog.name("张伟"))
                .with(RedactedLog.idCard("310101198505121567"))
                .with(RedactedLog.phone("13800138000"))
                .with(RedactedLog.key("PAT-20261020-0001"))
                .render("ack.sent");

        assertFalse(line.contains("张伟"), "姓名明文进了日志行：" + line);
        assertFalse(line.contains("310101198505121567"), "证件号明文进了日志行：" + line);
        assertFalse(line.contains("13800138000"), "手机号明文进了日志行：" + line);
        assertTrue(line.contains(RedactedLog.LABEL_NAME + "="), "缺姓名字段标签：" + line);
        assertTrue(line.contains(RedactedLog.LABEL_ID_CARD + "="), "缺证件字段标签：" + line);
        assertTrue(line.contains(RedactedLog.LABEL_PHONE + "="), "缺手机字段标签：" + line);
        assertTrue(line.contains("PAT***0001"), "主键应出首尾片段：" + line);
        assertTrue(line.startsWith("event=ack.sent"), "首段应是事件名：" + line);
    }

    @Test
    @DisplayName("红线：同一姓名两次变形摘要相同（对账靠这个确认是同一个人）")
    void test_same_name_yields_same_digest() {
        assertEquals(RedactedLog.name("张伟").rendered(), RedactedLog.name("张伟").rendered());
        assertFalse(RedactedLog.name("张伟").rendered().equals(RedactedLog.name("张娜").rendered()),
                "不同姓名必须给出不同摘要");
        assertEquals(8, RedactedLog.name("张伟").rendered().length(),
                "摘要应为 8 位十六进制");
    }

    @Test
    @DisplayName("红线：渲染期回搜拦住被硬塞回去的明文，且该条事件不落日志")
    void test_render_refuses_a_line_smuggling_plaintext() {
        LogCapture capture = new LogCapture();
        RedactedLog.Handle log = RedactedLog.forClass(RedactedLogTest.class).withCapture(capture);

        // 这一行模拟真实的现场事故：临时把一个字段名换成了字段值，
        // 而同一个事件里仍然挂着那个已经变形过的姓名字段 —— 回搜因此能抓到它。
        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> log
                .with(RedactedLog.text("note", "患者 张伟 已确认"))
                .with(RedactedLog.name("张伟"))
                .info("confirm.done"));

        assertTrue(thrown.getMessage().contains("明文"), "异常应说明是明文问题：" + thrown.getMessage());
        assertFalse(thrown.getMessage().contains("张伟"), "异常消息本身不得复述明文：" + thrown.getMessage());
        assertEquals(0, capture.records().size(), "被拒的事件不得落进日志");
    }

    @Test
    @DisplayName("红线：额外声明的明文同样被回搜（跨字段形态）")
    void test_extra_watched_plaintext_is_also_blocked() {
        RedactedLog.Handle log = RedactedLog.forClass(RedactedLogTest.class);
        assertThrows(IllegalStateException.class, () -> log
                .alsoWatching("310101198505121567")
                .with(RedactedLog.text("batch", "批次 REG-20261020 含 310101198505121567"))
                .info("batch.done"));
    }

    @Test
    @DisplayName("正常路径：状态名与错误码照常落日志（脱敏不得把日志变成筛子）")
    void test_operational_fields_stay_readable() {
        String line = RedactedLog.forClass(RedactedLogTest.class)
                .with(RedactedLog.text("from", "RECEIVED"))
                .with(RedactedLog.text("to", "PENDING_CONFIRM"))
                .with(RedactedLog.text("reason", "identity-candidates-ready"))
                .with(RedactedLog.text("elapsed_ms", 12L))
                .render("state.transition");

        assertTrue(line.contains("from=RECEIVED"), line);
        assertTrue(line.contains("to=PENDING_CONFIRM"), line);
        assertTrue(line.contains("reason=identity-candidates-ready"), line);
        assertTrue(line.contains("elapsed_ms=12"), line);
    }

    @Test
    @DisplayName("正常路径：空值给空渲染而不是摘要（空串摘要会看起来像「有一百个同名的人」）")
    void test_blank_patient_field_renders_empty() {
        assertEquals("", RedactedLog.name(null).rendered());
        assertEquals("", RedactedLog.name("").rendered());
        assertEquals("", RedactedLog.key("   ").rendered());
        assertEquals("", RedactedLog.idCard(null).rendered());
    }

    @Test
    @DisplayName("红线：拒绝落盘的事件不影响后续事件（闸是逐行的，不是把日志器毒死）")
    void test_rejected_line_does_not_poison_the_logger() {
        LogCapture capture = new LogCapture();
        RedactedLog.Handle log = RedactedLog.forClass(RedactedLogTest.class).withCapture(capture);
        assertThrows(IllegalStateException.class, () -> log
                .with(RedactedLog.text("note", "张伟"))
                .with(RedactedLog.name("张伟"))
                .info("bad"));
        // 句柄在自检抛错时同样已清空：这条被拒的明文不得继续挂在句柄上拖累后续事件
        log.with(RedactedLog.name("张伟")).info("good");

        assertEquals(1, capture.records().size(), "只有后一条该落盘");
        assertTrue(capture.records().get(0).getMessage().startsWith("event=good"),
                capture.records().get(0).getMessage());
        assertFalse(capture.records().get(0).getMessage().contains("张伟"));
    }

    @Test
    @DisplayName("正常路径：WARNING 与 SEVERE 与 INFO 走同一条渲染路径")
    void test_all_levels_share_the_same_guard() {
        LogCapture capture = new LogCapture();
        RedactedLog.Handle log = RedactedLog.forClass(RedactedLogTest.class).withCapture(capture);
        log.info("a");
        log.warning("b");
        log.severe("c");
        assertEquals(3, capture.records().size());

        LogCapture capture2 = new LogCapture();
        RedactedLog.Handle bad = RedactedLog.forClass(RedactedLogTest.class).withCapture(capture2);
        assertThrows(IllegalStateException.class, () -> bad
                .with(RedactedLog.text("x", "张伟"))
                .with(RedactedLog.name("张伟"))
                .log(Level.SEVERE, "d"));
        assertEquals(0, capture2.records().size(), "SEVERE 同样不得绕过自检");
    }

    @Test
    @DisplayName("正常路径：renderPairs 与 toPairs 供 CLI 复用，键按插入序输出")
    void test_pair_rendering_helpers() {
        String rendered = RedactedLog.renderPairs(Map.of("batch", "REG-20261020"));
        assertEquals("batch=REG-20261020", rendered);

        Map<String, String> pairs = RedactedLog.toPairs(RedactedLog.name("张伟"), RedactedLog.key("PAT-1"));
        assertEquals(RedactedLog.LABEL_NAME, pairs.keySet().iterator().next());
        assertFalse(pairs.toString().contains("张伟"));
    }

    // ================================================================ 错误码目录

    @Test
    @DisplayName("红线：五个来源包的每一项都在总目录里登记（漏登记即启动失败）")
    void test_every_source_enum_value_is_registered() {
        for (ModelErrorCode c : ModelErrorCode.values()) {
            ErrorCodeCatalog.Entry e = ErrorCodeCatalog.byCode("model." + c.auditToken());
            assertNotNull(e, "模型错误码未登记：" + c);
            assertEquals(c.retryable(), e.retryable(),
                    "重试口径与源枚举不一致：" + c.auditToken());
        }
        for (HisWriteErrorCode c : HisWriteErrorCode.values()) {
            ErrorCodeCatalog.Entry e = ErrorCodeCatalog.byCode(
                    "identity." + c.name().toLowerCase(java.util.Locale.ROOT));
            assertNotNull(e, "HIS 写回码未登记：" + c);
            assertEquals(c.jittery(), e.retryable(), "抖动口径与源枚举不一致：" + c);
        }
        for (ReconcileErrorCode c : ReconcileErrorCode.values()) {
            ErrorCodeCatalog.Entry e = ErrorCodeCatalog.byCode(
                    "redelivery." + c.name().toLowerCase(java.util.Locale.ROOT));
            assertNotNull(e, "对账错误码未登记：" + c);
        }
        for (ApiErrorCode c : ApiErrorCode.values()) {
            ErrorCodeCatalog.Entry e = ErrorCodeCatalog.byCode(
                    "http." + c.name().toLowerCase(java.util.Locale.ROOT));
            assertNotNull(e, "API 错误码未登记：" + c);
            assertTrue(e.zhLabel().contains(" ") || e.zhLabel().length() > 3,
                    "API 条目应带中文说明：" + c);
        }
        for (RejectionReason r : RejectionReason.values()) {
            ErrorCodeCatalog.Entry e = ErrorCodeCatalog.byCode("ack." + r.suggestedCode());
            assertNotNull(e, "拒收原因未登记：" + r);
            assertEquals(r.redeliverable(), e.retryable(), "可重投口径与拒收原因不一致：" + r);
            assertTrue(e.nativeCode().contains("/"),
                    "拒收条目的原始码应带 HL7 标准码（形如 原因名/标准码）：" + e.nativeCode());
        }
    }

    @Test
    @DisplayName("红线：全目录码值唯一且每条都能从源枚举反查回来")
    void test_catalog_codes_are_unique_and_reversible() {
        List<ErrorCodeCatalog.Entry> all = ErrorCodeCatalog.entries();
        assertEquals(all.size(), ErrorCodeCatalog.entries().stream()
                .map(ErrorCodeCatalog.Entry::qualified).distinct().count(),
                "目录码必须全局唯一");

        // 五条不变量之一：反查索引不得丢条目
        for (ErrorCodeCatalog.Entry e : all) {
            ErrorCodeCatalog.Entry back = ErrorCodeCatalog.byNative(
                    e.source().packageName(), e.nativeCode());
            assertNotNull(back, "反查失败：" + e.qualified());
            assertEquals(e.qualified(), back.qualified());
        }
    }

    @Test
    @DisplayName("目录：按来源包切分与重投计数可直接用于值班交接")
    void test_entries_of_and_retryable_count() {
        assertEquals(RejectionReason.values().length,
                ErrorCodeCatalog.entriesOf(ErrorCodeCatalog.Source.ACK).size());
        assertEquals(ModelErrorCode.values().length,
                ErrorCodeCatalog.entriesOf(ErrorCodeCatalog.Source.MODEL).size());
        // 模型五类里只有连接超时与读超时两类值得重投（T21 的纪律）
        assertEquals(2, ErrorCodeCatalog.retryableCountOf(ErrorCodeCatalog.Source.MODEL));
        // API 侧只有「对端不可达」与「HIS 写失败」值得原样重发
        assertEquals(2, ErrorCodeCatalog.retryableCountOf(ErrorCodeCatalog.Source.HTTP));
    }

    @Test
    @DisplayName("目录：CSV 导出每行字段数一致（处置建议里有逗号时必须被引号包住）")
    void test_csv_export_is_well_formed() {
        String csv = ErrorCodeCatalog.toCsv();
        String[] lines = csv.strip().split("\n");
        assertEquals("code,source,retryable,native_code,zh_label,remedy", lines[0]);
        assertEquals(ErrorCodeCatalog.entries().size() + 1, lines.length,
                "行数 = 表头 + 条目数");
        for (int i = 1; i < lines.length; i++) {
            assertEquals(6, countCsvFields(lines[i]), "第 " + i + " 行字段数不对：" + lines[i]);
        }
    }

    @Test
    @DisplayName("目录：查不到的码返回 null 而不是抛异常（查询口不该让调用方崩）")
    void test_lookup_of_unknown_code_returns_null() {
        assertNull(ErrorCodeCatalog.byCode("model.NOT_A_CODE"));
        assertNull(ErrorCodeCatalog.byCode(null));
        assertNull(ErrorCodeCatalog.byNative("model", "NOT_A_CODE"));
        assertNull(ErrorCodeCatalog.byNative(null, null));
    }

    // ================================================================ 批次报告

    @Test
    @DisplayName("批次报告：8 条批次的派生数字自洽（已落地 5 / 待确认 2 / 拒收 1）")
    void test_batch_report_derived_numbers() {
        cn.hospital.region.archive.ledger.BatchStats stats = statsOf("REGIONAL_ARCHIVE-20261020", 8, 5, 2, 1);
        BatchObservabilityReport report = BatchObservabilityReport.of("REGIONAL_ARCHIVE-20261020", stats);

        assertEquals(8, report.processed());
        assertEquals(5, report.landed());
        assertEquals(2, report.pendingConfirm());
        assertEquals(1, report.rejected());
        assertEquals(0.125, report.rejectionRate(), 1e-9);
        assertEquals(0.25, report.pendingConfirmRate(), 1e-9);
        assertEquals(8, report.mainStateCounts().values().stream().mapToLong(Long::longValue).sum(),
                "四态之和必须等于总数");
    }

    @Test
    @DisplayName("批次报告：缺耗时与置信度数据时给「未采集」而不是崩或造数")
    void test_batch_report_handles_missing_optional_sources() {
        BatchObservabilityReport report = BatchObservabilityReport.of("B-1", statsOf("B-1", 4, 2, 1, 1));
        assertFalse(report.hasLatency());
        assertFalse(report.hasConfidence());
        assertNull(report.p99Millis());
        assertNull(report.p50Millis());
        assertNull(report.lowConfidenceRatio());

        String rendered = String.join("\n", report.displayLines());
        assertTrue(rendered.contains("未采集"), "应显式标注未采集：" + rendered);
        assertTrue(report.rawData().contains("latency.p99_ms=not_collected"), report.rawData());
        assertTrue(report.rawData().contains("model.degradation_ratio=not_collected"),
                report.rawData());
        assertTrue(report.rawData().contains("latency.p99_ms=not_collected"), report.rawData());
    }

    @Test
    @DisplayName("批次报告：空批次不产生 NaN（NaN 会一路传染到同比环比）")
    void test_empty_batch_yields_zero_not_nan() {
        BatchObservabilityReport report = BatchObservabilityReport.of("B-EMPTY", statsOf("B-EMPTY", 0, 0, 0, 0));
        assertEquals(0.0, report.rejectionRate(), 1e-9);
        assertEquals(0.0, report.pendingConfirmRate(), 1e-9);
        assertFalse(String.join("\n", report.displayLines()).contains("NaN"));
        assertFalse(report.rawData().contains("NaN"));
    }

    @Test
    @DisplayName("批次报告：人工负载估计按可配置基线换算，基线非正即拒")
    void test_manual_click_estimate_scales_with_baseline() {
        BatchObservabilityReport report = BatchObservabilityReport.of("B-2", statsOf("B-2", 10, 4, 6, 0));
        assertEquals(6.0 / BatchObservabilityReport.DEFAULT_MANUAL_PER_BATCH,
                report.manualClickEstimate(), 1e-9);
        assertEquals(6.0 / 20, report.manualClickEstimate(20), 1e-9);
        assertThrows(IllegalArgumentException.class, () -> report.manualClickEstimate(0));
    }

    @Test
    @DisplayName("批次报告：批次号与统计对象不一致时构造即拒（避免贴错批次的报表）")
    void test_batch_id_mismatch_is_refused() {
        assertThrows(IllegalArgumentException.class,
                () -> BatchObservabilityReport.of("B-OTHER", statsOf("B", 1, 1, 0, 0)));
    }

    @Test
    @DisplayName("批次报告：rawData 的键=值形态可被逐行解析（供两轮差分）")
    void test_raw_data_is_line_parsable() {
        String raw = BatchObservabilityReport.of("B-3", statsOf("B-3", 6, 3, 2, 1)).rawData();
        int lines = 0;
        for (String line : raw.strip().split("\n")) {
            assertTrue(line.contains("="), "每行都应是 键=值：" + line);
            lines++;
        }
        assertTrue(lines >= 10, "rawData 行数过少：" + lines);
        assertTrue(raw.contains("batch.processed=6"));
        assertTrue(raw.contains("state.LANDED=3"));
    }

    // ================================================================ 工具

    private static cn.hospital.region.archive.ledger.BatchStats statsOf(
            String batchId, long total, long landed, long pending, long rejected) {
        Map<cn.hospital.region.archive.ledger.MessageState, Long> counts =
                new java.util.EnumMap<>(cn.hospital.region.archive.ledger.MessageState.class);
        counts.put(cn.hospital.region.archive.ledger.MessageState.RECEIVED, 0L);
        counts.put(cn.hospital.region.archive.ledger.MessageState.PENDING_CONFIRM, pending);
        counts.put(cn.hospital.region.archive.ledger.MessageState.LANDED, landed);
        counts.put(cn.hospital.region.archive.ledger.MessageState.REJECTED, rejected);
        Map<cn.hospital.region.archive.ledger.MessageState, Long> retry =
                new java.util.EnumMap<>(cn.hospital.region.archive.ledger.MessageState.class);
        for (cn.hospital.region.archive.ledger.MessageState s : cn.hospital.region.archive.ledger.MessageState
                .values()) {
            retry.putIfAbsent(s, 0L);
        }
        return new cn.hospital.region.archive.ledger.BatchStats(
                batchId, total, counts, retry, Map.of("patient-id-missing", rejected), pending);
    }

    /** 按 CSV 规范数一行里的字段数（引号内的逗号不算分隔符）。 */
    private static int countCsvFields(String line) {
        int fields = 1;
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                fields++;
            }
        }
        return fields;
    }

    /** 收集 java.util.logging 落到本测试的记录（0 个 mock 库：走真实 Handler）。 */
    static final class LogCapture extends Handler {

        private final List<LogRecord> records = new ArrayList<>();

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }

        List<LogRecord> records() {
            return records;
        }
    }
}