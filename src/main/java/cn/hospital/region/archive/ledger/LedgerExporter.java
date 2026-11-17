package cn.hospital.region.archive.ledger;

import cn.hospital.region.archive.ack.ErrorCodeEntry;
import cn.hospital.region.archive.ack.Hl7ErrorCodes;
import cn.hospital.region.archive.hl7.FieldLocator;
import cn.hospital.region.archive.hl7.Hl7Message;
import cn.hospital.region.archive.hl7.Hl7Parser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 台账只读导出 —— 信息员下午核对批次时真正会拿走的那份 CSV。
 *
 * <h2>导出两种清单，各回答一个问句</h2>
 * <ul>
 *   <li><b>待确认清单</b>（{@link MessageState#PENDING_CONFIRM}）：「这一批还有几条要我点确认？」
 *       每行给到主键片段、姓名摘要、低置信标记与确认时要用的幂等键三段。</li>
 *   <li><b>拒收清单</b>（{@link MessageState#REJECTED}）：「平台问为什么少了几条，我拿什么回话？」
 *       每行给本地原因码 + 对应的 HL7 标准码 + ERR 摘要 + 出错位置。</li>
 * </ul>
 *
 * <h2>ERR 摘要为什么能从台账重建，而不必留原文片段</h2>
 * <p>拒收原因写在审计行的 {@code reason} 里（{@code rejected:} + 本地码）。本地码经
 * {@link Hl7ErrorCodes} 查表即得标准码、中文说明与表来源，经 {@link FieldLocator} 的必需字段
 * 清单即得出错位置 —— 三样合起来正好是一个可被区域平台解析的 ERR 段语义。
 * <b>刻意不</b>把 T7 那个「原值片段」搬进台账导出：那个片段取自 PID 段，
 * 放在库里就要跟着每次导出流转一次。「回执里已经发给平台了，台账里不必再留一份」是本产品
 * 对可识别信息最省的一条路。
 *
 * <h2>脱敏</h2>
 * <p>所有姓名与证件号一律经 {@link LedgerRedaction} 变形；写出<b>之前</b>调用
 * {@link LedgerRedaction#assertNoPlaintext} 自检一次，明文一旦出现在产物里就不落盘。
 */
public final class LedgerExporter {

    private static final Logger LOG = Logger.getLogger(LedgerExporter.class.getName());

    /** CSV 时间列的格式：全院口径统一 {@code YYYY-MM-DD HH:mm}，不要 ISO 的 T 与 Z。 */
    public static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    /** Excel 打开无 BOM 的 UTF-8 CSV 会把中文显示成乱码——本院统计岗的电脑默认就是这个配置。 */
    public static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private static final String PENDING_HEADER =
            "ledger_id,batch_id,message_type,control_id,processing_id,state,"
                    + "patient_ref_fragment,name_digest8,id_digest8,local_mrn,"
                    + "low_confidence,retry_count,created_at,updated_at";

    private static final String REJECTED_HEADER =
            "ledger_id,batch_id,message_type,control_id,processing_id,state,"
                    + "rejection_code,hl7_error_code,err_summary,zh_summary,err_location,"
                    + "degraded,created_at,updated_at";

    private final LedgerReadView view;

    public LedgerExporter(LedgerReadView view) {
        this.view = Objects.requireNonNull(view, "台账只读面不得为 null");
    }

    /**
     * 导出某一状态下的清单。
     *
     * @param state 导出目标状态；{@link MessageState#PENDING_CONFIRM} 出待确认清单、
     *              {@link MessageState#REJECTED} 出拒收清单，其余状态出通用清单
     * @return CSV 文本（不含 BOM；首行是表头）
     */
    public String exportCsv(String batchId, MessageState state) throws SQLException {
        Objects.requireNonNull(state, "导出状态不得为 null：导出的是一个状态集合，不是一张表的全部行");
        requireBatchId(batchId);

        List<LedgerReadView.ExportRow> rows = view.listForExport(batchId, state);
        List<String> plaintexts = new ArrayList<>();
        StringBuilder out = new StringBuilder();
        out.append(state == MessageState.REJECTED ? REJECTED_HEADER : PENDING_HEADER).append('\n');

        for (LedgerReadView.ExportRow row : rows) {
            Hl7Message msg = parseQuietly(row.rawPayload());
            String name = fieldOf(msg, "PID", 5);
            String idNumber = fieldOf(msg, "PID", 11);
            String patientRef = firstComponent(fieldOf(msg, "PID", 3));
            // 明文来源登记在这：出口自检拿它逐值比对 CSV。
            plaintexts.add(name);
            plaintexts.add(idNumber);

            if (state == MessageState.REJECTED) {
                ErrorCodeEntry entry = Hl7ErrorCodes.forSuggestedCode(row.rejectionCode());
                append(out,
                        String.valueOf(row.id()),
                        row.batchId(),
                        row.key().messageType(),
                        displayControlId(row.key().controlId()),
                        displayControlId(row.key().processingId()),
                        row.state().dbValue(),
                        row.rejectionCode(),
                        entry.code(),
                        entry.toEr7Form(),
                        entry.describeZh(),
                        errLocation(row.rejectionCode()),
                        row.degraded() ? "是" : "否",
                        TIMESTAMP.format(row.createdAt()),
                        TIMESTAMP.format(row.updatedAt()));
            } else {
                append(out,
                        String.valueOf(row.id()),
                        row.batchId(),
                        row.key().messageType(),
                        displayControlId(row.key().controlId()),
                        displayControlId(row.key().processingId()),
                        row.state().dbValue(),
                        LedgerRedaction.keyFragment(patientRef),
                        LedgerRedaction.digest8(name),
                        LedgerRedaction.digest8(idNumber),
                        row.localMrn(),
                        row.degraded() ? "是" : "否",
                        String.valueOf(row.retryCount()),
                        TIMESTAMP.format(row.createdAt()),
                        TIMESTAMP.format(row.updatedAt()));
            }
        }

        String csv = out.toString();
        LedgerRedaction.assertNoPlaintext(csv, plaintexts);
        return csv;
    }

    /** 待确认清单 CSV。 */
    public String exportPendingCsv(String batchId) throws SQLException {
        return exportCsv(batchId, MessageState.PENDING_CONFIRM);
    }

    /** 拒收清单 CSV。 */
    public String exportRejectedCsv(String batchId) throws SQLException {
        return exportCsv(batchId, MessageState.REJECTED);
    }

    /**
     * 写盘：{@code --out <路径>} 时走这里。
     *
     * @param bom 是否前置 UTF-8 BOM（默认给 true：直接双击打开的场合几乎都需要）
     * @return 写出结果（路径 + 数据行数 + 表头）
     */
    public ExportResult writeTo(String batchId, MessageState state, Path out, boolean bom)
            throws SQLException, IOException {
        Objects.requireNonNull(out, "导出路径不得为 null");
        String csv = exportCsv(batchId, state);
        byte[] body = csv.getBytes(StandardCharsets.UTF_8);
        if (bom) {
            byte[] withBom = new byte[UTF8_BOM.length + body.length];
            System.arraycopy(UTF8_BOM, 0, withBom, 0, UTF8_BOM.length);
            System.arraycopy(body, 0, withBom, UTF8_BOM.length, body.length);
            body = withBom;
        }
        Path parent = out.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(out, body);
        int dataRows = csv.isEmpty() ? 0 : csv.split("\n", -1).length - 2;
        LOG.log(Level.INFO, "台账只读导出完成: batch={0} state={1} 行数={2} 路径={3}",
                new Object[]{batchId, state.dbValue(), dataRows, out});
        return new ExportResult(out, Math.max(dataRows, 0), firstLine(csv));
    }

    /** 导出结果。 */
    public record ExportResult(Path path, int dataRows, String headerLine) {
    }

    // =====================================================================
    // 内部
    // =====================================================================

    /**
     * 解析报文原文用于取姓名 / 证件号 / 患者标识。
     *
     * <p><b>解析失败不抛</b>：拒收条目里相当一部分就是「报文本身解析不了」的那种
     * （缺 MSH-10、字符集不支持、段结构错）。导出这条路的职责是把「卡在哪」递出去，
     * 不是因为解析不出来就少导一行——<b>导出行数必须恒等于台账条数</b>，
     * 否则信息员拿到一份比台账少的 CSV，会以为剩下的已经处理过了。
     */
    private static Hl7Message parseQuietly(String rawPayload) {
        if (rawPayload == null || rawPayload.isBlank()) {
            return null;
        }
        try {
            return Hl7Parser.parse(rawPayload);
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "导出时报文原文解析不了（该行仍会导出，患者标识列留空）: 原因={0}",
                    e.getMessage());
            return null;
        }
    }

    /** 取字段；段不存在或字段越界时返回空串（走 {@code has}/{@code field} 的明确异常语义）。 */
    private static String fieldOf(Hl7Message msg, String segment, int index) {
        if (msg == null || !msg.has(segment)) {
            return "";
        }
        try {
            return msg.field(segment, index);
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** PID-3 是重复分隔的标识列表，第一组件才是区域平台主键。 */
    private static String firstComponent(String value) {
        if (value.isEmpty()) {
            return "";
        }
        int caret = value.indexOf('^');
        return caret < 0 ? value : value.substring(0, caret);
    }

    /**
     * 本地原因码 → 出错位置（{@code 段名.字段序号}），非必需字段类原因返回空串。
     *
     * <p>走 {@link FieldLocator#requiredFields()} 而不是另建一张表：那份清单是 T7 唯一的
     * 「码 ↔ 段.字段」映射，另建一张就多了一个可能与它分叉的地方。
     */
    private static String errLocation(String rejectionCode) {
        if (rejectionCode == null || rejectionCode.isBlank()) {
            return "";
        }
        for (FieldLocator.RequiredField f : FieldLocator.requiredFields()) {
            if (f.code().equals(rejectionCode)) {
                return f.segmentName() + "." + f.fieldIndex();
            }
        }
        return "";
    }

    /** 空控制号显式写成 {@code (空)}：裸分隔符串在 CSV 里是最难认的一种排障形态。 */
    private static String displayControlId(String value) {
        return value == null || value.isEmpty() ? "(空)" : value;
    }

    private static String firstLine(String csv) {
        int nl = csv.indexOf('\n');
        return nl < 0 ? csv : csv.substring(0, nl);
    }

    /** 按 RFC 4180 追加一行：含逗号 / 引号 / 换行的值加引号，内部引号翻倍。 */
    private static void append(StringBuilder out, String... cells) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(csvCell(cells[i]));
        }
        out.append('\n');
    }

    private static String csvCell(String value) {
        String v = value == null ? "" : value;
        boolean needsQuote = v.indexOf(',') >= 0 || v.indexOf('"') >= 0
                || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0;
        if (!needsQuote) {
            return v;
        }
        return '"' + v.replace("\"", "\"\"") + '"';
    }

    private static void requireBatchId(String batchId) {
        Objects.requireNonNull(batchId, "batchId 不得为 null");
        if (batchId.isBlank()) {
            throw new IllegalArgumentException("batchId 不得为空串：批次号是统计与导出的唯一入口");
        }
    }

    /** 供 {@code cli.LedgerCli} 复用：把 Instant 按统一格式渲染。 */
    public static String formatTimestamp(Instant instant) {
        return instant == null ? "" : TIMESTAMP.format(instant);
    }

    /** 供 CLI 打印统计分布时复用；空 Optional 表示该状态没有条目。 */
    public static Optional<String> describeRejection(String rejectionCode) {
        return rejectionCode == null || rejectionCode.isBlank()
                ? Optional.empty()
                : Optional.of(Hl7ErrorCodes.forSuggestedCode(rejectionCode).describeZh());
    }
}