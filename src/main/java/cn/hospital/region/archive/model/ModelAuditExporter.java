package cn.hospital.region.archive.model;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

/**
 * 模型调用审计的<b>CSV 导出</b> —— 合规材料，也是模型质量的持续观测面。
 *
 * <h2>为什么要能导出</h2>
 * <p>值班的人每天要回答两类问题：「今天模型调了没有出错？」和「上周三它为什么慢了一倍？」
 * 前者看 {@code model_call_audit} 表，后者只能看导出件——表里的行没有时间序列的形状，
 * 而合规检查要的是一份能<b>存进档、能拿出去</b>的表格。
 * 本类只做导出，不做筛选与汇总：汇总在 {@link ConfidenceBucketReport}，
 * 两者的行数口径保持一致（同一区间、同一排序），因此两份产出可以互相核对。
 *
 * <h2>导出件里的每一列</h2>
 * <p>时间列写成<b>本地时间</b>并带时区偏移：{@code 2026-10-20 09:12:33+08:00}。
 * 只写 {@code 09:12:33} 的表在跨时区部署时会让人对着错位的时间排查，
 * 而这张表最常见的用途就是和平台侧的时间线对齐。
 *
 * <h2>脱敏</h2>
 * <p>逐格过 {@link AuditTextGuard}：入参摘要走严格档，{@code requestId} 走宽松档，
 * 其余列全部由本产品自己写入（枚举名、模型名、耗时、token），不可能带患者数据。
 * 写文件前再对整份文本跑一次 {@link AuditTextGuard#assertClean} 作为最后一道闸——
 * 它不依赖任何「已知明文清单」，因此新增列、改列顺序都不会绕过它。
 *
 * <p><b>数据区全 ASCII，表头才是中文</b>：「是否降级」这一列写 {@code Y}/{@code N} 而非「是/否」，
 * 就是为了让「数据区里一个非 ASCII 字符都没有」这条不变量真的成立。
 * 表头是本产品写死的常量，逐字可查；数据区则来自模型名、请求标识与入参摘要这些外部输入。
 * 两者分处这条分界线两侧，于是最后一道闸只对数据区生效，也仍然有效。
 */
public final class ModelAuditExporter {

    /** 时间列格式：本地时间 + 偏移量，同一毫秒的两条记录靠偏移后的纳秒部分也不会看混。 */
    public static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSxxx");

    /** Excel 打开无 BOM 的 UTF-8 CSV 会把中文显示成乱码；与台账导出同一取舍。 */
    public static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    /** 表头；{@link #csvHeader()} 的返回与之逐字一致，测试按它数行。表头是本导出里唯一允许出现中文的一行。 */
    public static final String HEADER = String.join(",",
            "本地时间", "操作", "模型", "耗时ms", "输入token", "输出token", "合计token",
            "requestId", "是否降级", "错误码", "入参摘要");

    /** 降级列的取值；ASCII，与中文表头配对使用。 */
    public static final String DEGRADED_YES = "Y";

    /** 降级列的取值；ASCII，与中文表头配对使用。 */
    public static final String DEGRADED_NO = "N";

    private final ModelAuditReadView view;

    public ModelAuditExporter(ModelAuditReadView view) {
        this.view = Objects.requireNonNull(view, "审计读口不得为 null");
    }

    /** 表头行（不含 BOM、不含换行符）。 */
    public static String csvHeader() {
        return HEADER;
    }

    /**
     * 导出某一天的调用审计（按传入时区切自然日）。
     *
     * @param date 自然日
     * @param zone 该日的时区；{@code Asia/Shanghai} 之类的具名时区
     * @return CSV 文本（不含 BOM；首行表头）。区间内没有调用时只返回表头
     * @throws SQLException 读库失败——原样抛出，不给出一份「看起来完整」的半截报表
     */
    public String exportCsv(LocalDate date, ZoneId zone) throws SQLException {
        Objects.requireNonNull(date, "日期不得为 null");
        Objects.requireNonNull(zone, "时区不得为 null");
        List<ModelCallRecord> rows = view.recordsBetween(startOf(date, zone), startOf(date.plusDays(1), zone));
        return render(rows, zone);
    }

    /**
     * 把审计行渲染成 CSV 文本。<b>不查库</b>，便于对照测试直接喂固定行。
     *
     * @param rows 已按 {@code (at, id)} 升序的行
     * @param zone 时间列的时区
     */
    public static String render(List<ModelCallRecord> rows, ZoneId zone) {
        Objects.requireNonNull(rows, "审计行不得为 null");
        Objects.requireNonNull(zone, "时区不得为 null");
        StringBuilder sb = new StringBuilder(HEADER).append('\n');
        for (ModelCallRecord r : rows) {
            sb.append(cell(TIMESTAMP.format(r.at().atZone(zone)))).append(',')
                    .append(cell(r.operation())).append(',')
                    .append(cell(AuditTextGuard.maskAsciiOnly(r.model()))).append(',')
                    .append(r.elapsedMillis()).append(',')
                    .append(r.promptTokens()).append(',')
                    .append(r.completionTokens()).append(',')
                    .append(r.totalTokens()).append(',')
                    .append(cell(AuditTextGuard.maskAsciiOnly(r.requestId()))).append(',')
                    .append(r.degraded() ? DEGRADED_YES : DEGRADED_NO).append(',')
                    .append(cell(r.errorCode() == null ? "" : r.errorCode().auditToken())).append(',')
                    .append(cell(AuditTextGuard.maskStrict(r.inputSummary())))
                    .append('\n');
        }
        String csv = sb.toString();
        // 最后一道闸：整份文本过一遍，不通过就不许写出去。
        // 只对数据区跑 —— 中文只允许出现在表头那一行，这正是「数据区里没有中文」
        // 这条不变量能成立的原因，也正是降级列写 Y/N 而不是「是/否」的原因。
        for (String line : csv.split("\n", -1)) {
            if (line.isEmpty() || line.startsWith("本地时间,")) {
                continue;
            }
            AuditTextGuard.assertClean(line);
        }
        return csv;
    }

    /**
     * 写到文件。
     *
     * @param date 自然日
     * @param zone 时区
     * @param out  目标路径；父目录不存在时创建
     * @param bom  是否前置 UTF-8 BOM（默认给 true：直接双击打开的场合几乎都需要）
     */
    public ExportResult writeTo(LocalDate date, ZoneId zone, Path out, boolean bom)
            throws SQLException, IOException {
        Objects.requireNonNull(out, "输出路径不得为 null");
        String csv = exportCsv(date, zone);
        if (out.getParent() != null) {
            Files.createDirectories(out.getParent());
        }
        byte[] body = csv.getBytes(StandardCharsets.UTF_8);
        if (bom) {
            byte[] withBom = new byte[UTF8_BOM.length + body.length];
            System.arraycopy(UTF8_BOM, 0, withBom, 0, UTF8_BOM.length);
            System.arraycopy(body, 0, withBom, UTF8_BOM.length, body.length);
            Files.write(out, withBom);
        } else {
            Files.write(out, body);
        }
        return new ExportResult(out, dataRowCount(csv), HEADER);
    }

    /** 某自然日的区间起点 Instant（左闭）。 */
    public static Instant startOf(LocalDate date, ZoneId zone) {
        Objects.requireNonNull(date, "日期不得为 null");
        Objects.requireNonNull(zone, "时区不得为 null");
        return date.atStartOfDay(zone).toInstant();
    }

    private static int dataRowCount(String csv) {
        int n = 0;
        for (String line : csv.split("\n")) {
            if (!line.isBlank() && !line.startsWith("本地时间,")) {
                n++;
            }
        }
        return n;
    }

    private static String cell(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        boolean needsQuote = value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
        if (!needsQuote) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    /**
     * 写文件的结果。
     *
     * @param path      写入路径
     * @param dataRows  数据行数（不含表头）
     * @param headerLine 表头行，供调用方打印时一眼核对列数
     */
    public record ExportResult(Path path, int dataRows, String headerLine) {
    }
}