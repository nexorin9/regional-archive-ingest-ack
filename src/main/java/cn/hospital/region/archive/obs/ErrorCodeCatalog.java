package cn.hospital.region.archive.obs;

import cn.hospital.region.archive.ack.ErrorCodeEntry;
import cn.hospital.region.archive.ack.Hl7ErrorCodes;
import cn.hospital.region.archive.ack.RejectionReason;
import cn.hospital.region.archive.http.ApiErrorCode;
import cn.hospital.region.archive.identity.HisWriteErrorCode;
import cn.hospital.region.archive.model.ModelErrorCode;
import cn.hospital.region.archive.redelivery.ReconcileErrorCode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;

/**
 * 错误码总目录 —— 把<b>分散在五个包里的失败分类合成一张可查表</b>。
 *
 * <h2>为什么需要这一张表</h2>
 * <p>本产品的失败分类是按「处置方式」分出来的，各自落在离现场最近的那个包：
 * HL7 拒收码在 {@code ack}、模型五类在 {@code model}、HIS 写回在 {@code identity}、
 * 对账出站在 {@code redelivery}、本院 API 在 {@code http}。
 * 这个分法对写代码的人是对的（改一处只动一个包），对<b>运维</b>却是灾难：
 * 值班的人拿到一个现象，要先知道它属于哪一家，才能找到那张表。
 * 本类把这五家合成一张，每条带三样东西：
 *
 * <ul>
 *   <li><b>码</b>：机器可读，形如 {@code hl7.101} / {@code model.READ_TIMEOUT} / {@code his.SERVER_ERROR}；</li>
 *   <li><b>处置口径</b>：<b>重投是否可能恢复</b>（{@link #retryable()}）——
 *       这是运维唯一真正要判断的那件事，比「这句话什么意思」有用得多；</li>
 *   <li><b>来源包</b>：{@link #source()}，定位到具体哪个文件去改。</li>
 * </ul>
 *
 * <h2>为什么<b>不</b>把 HL7 码与本端码合成一个字符串</h2>
 * <p>两者回答的是不同的问题，且发送对象不同：HL7 标准码（表 0357）发给<b>区域平台</b>，
 * 由对端解析并路由到它自己的整改流程；本端码（{@code model.READ_TIMEOUT} 等）
 * 只在<b>院内</b>的台账、审计与只读报表里出现。合成一个码会逼着对端去解析它不认识的前缀，
 * 而把两者混在一张表里却不加区分，则会让本端码看起来像是「也可以发给平台」——
 * 那正是 T9 明确拒绝过的事（语义对齐失败不硬套 206）。
 * 所以本目录是<b>一张查询表</b>，不是一个发报文的码空间：
 * 出站报文该发什么，仍由 {@link Hl7ErrorCodes} 与 {@link AckComposer} 决定。
 *
 * <h2>目录是只读的，且在类加载期做不变量校验</h2>
 * <p>五条不变量（见静态块）：码全局唯一；{@link ModelErrorCode} 与 {@link HisWriteErrorCode}
 * 的每一项都登记在册；{@link ApiErrorCode} 的每一项都有条目；
 * {@link RejectionReason} 的每一项都能查到 HL7 条目；{@code retryable} 与源枚举的自述一致。
 * 最后一条尤其重要：两张表一旦对不上，运维照着本目录判断「重投能恢复」，
 * 而调度器照着源枚举决定退避档位，两边给出相反的答案。
 */
public final class ErrorCodeCatalog {

    /** 错误来源包名（{@link #source()} 的取值）。 */
    public enum Source {
        /** HL7 v2 报文体：拒收原因与标准码（表 0357）。 */
        ACK("ack"),
        /** 语义对齐模型调用。 */
        MODEL("model"),
        /** 院内患者主索引写回。 */
        HIS("identity"),
        /** 区域平台回执对账。 */
        REDELIVERY("redelivery"),
        /** 本院 HTTP API。 */
        HTTP("http");

        private final String packageName;

        Source(String packageName) {
            this.packageName = packageName;
        }

        /** 对应的包名，便于直接定位到要改的目录。 */
        public String packageName() {
            return packageName;
        }
    }

    /**
     * 一条目录条目。
     *
     * @param code       机器可读码（本目录内唯一）
     * @param source     失败分类的来源包
     * @param zhLabel    一行中文说明
     * @param remedy     处置建议：现场下一步做什么
     * @param retryable  原样重投是否有希望恢复
     * @param nativeCode 来源枚举里的原始码值（如 {@code 101} / {@code READ_TIMEOUT}），便于回查
     */
    public record Entry(String code, Source source, String zhLabel, String remedy,
                        boolean retryable, String nativeCode) {

        public Entry {
            if (code == null || code.isBlank()) {
                throw new IllegalArgumentException("目录码不得为空");
            }
            if (source == null) {
                throw new IllegalArgumentException("来源包不得为 null");
            }
            if (zhLabel == null || zhLabel.isBlank()) {
                throw new IllegalArgumentException("中文说明不得为空：" + code);
            }
            if (nativeCode == null || nativeCode.isBlank()) {
                throw new IllegalArgumentException("原始码值不得为空：" + code);
            }
        }

        /** {@code 域.码} 形态，供日志与报表对齐用。 */
        public String qualified() {
            return source.packageName() + "." + code;
        }
    }

    private static final Map<String, Entry> BY_QUALIFIED;
    private static final Map<String, Entry> BY_NATIVE;

    static {
        Map<String, Entry> byQualified = new LinkedHashMap<>();
        Map<String, Entry> byNative = new LinkedHashMap<>();

        // ---- ack：HL7 拒收原因（每条都查得到标准码，标准码一并带出） ----
        for (RejectionReason r : RejectionReason.values()) {
            ErrorCodeEntry hl7 = Hl7ErrorCodes.forRejection(r);
            // 可重投与否取拒收原因自身的标记（AE=true / AR=false），而不是标准码：
            // 「这张表按什么重投」与「这张表按 HL7 属于哪一类」是两个问题。
            put(byQualified, byNative, new Entry(
                    r.suggestedCode(), Source.ACK, r.zhLabel(),
                    r.redeliverable() ? "对端重投即可；本端已把原文与幂等键留在台账，可原样重放"
                            : "对端重投也补不对，需要人工与平台侧核对字段口径",
                    r.redeliverable(), r.name() + "/" + hl7.code()));
        }

        // ---- model：五类错误（T21 的重试口径就是本表的 retryable） ----
        for (ModelErrorCode c : ModelErrorCode.values()) {
            put(byQualified, byNative, new Entry(
                    c.auditToken(), Source.MODEL, c.zhLabel(), c.remedy(),
                    c.retryable(), c.name()));
        }

        // ---- identity：HIS 写回七类 ----
        for (HisWriteErrorCode c : HisWriteErrorCode.values()) {
            put(byQualified, byNative, new Entry(
                    c.name().toLowerCase(Locale.ROOT), Source.HIS, c.zhLabel(),
                    c.jittery() ? "抖动类：原样重投即可恢复，本条仍停在待确认可重放"
                            : "非抖动类：重投多少次都一样，先查 HIS 令牌、路径与主索引冲突",
                    c.jittery(), c.name()));
        }

        // ---- redelivery：对账出站十一类 ----
        for (ReconcileErrorCode c : ReconcileErrorCode.values()) {
            put(byQualified, byNative, new Entry(
                    c.name().toLowerCase(Locale.ROOT), Source.REDELIVERY, c.zhLabel(), c.disposition(), c.jittery(), c.name()));
        }

        // ---- http：本院 API 十四类 ----
        for (ApiErrorCode c : ApiErrorCode.values()) {
            put(byQualified, byNative, new Entry(
                    c.name().toLowerCase(Locale.ROOT), Source.HTTP,
                    c.zhSummary(),
                    "院内系统按 HTTP " + c.httpStatus() + " 分支处理；本端状态不受该失败影响，"
                            + "报文原文与幂等键仍在台账",
                    // 只有「对端不可达」这一族值得原样重发；400/401/404/409 都是改请求或改凭据，重发无意义
                    c == ApiErrorCode.REMOTE_UNAVAILABLE || c == ApiErrorCode.HIS_WRITE_FAILED,
                    c.name()));
        }

        assertInvariants(byQualified, byNative);

        BY_QUALIFIED = Collections.unmodifiableMap(byQualified);
        BY_NATIVE = Collections.unmodifiableMap(byNative);
    }

    private ErrorCodeCatalog() {
    }

    private static void put(Map<String, Entry> byQualified, Map<String, Entry> byNative, Entry e) {
        Entry clash = byQualified.put(e.qualified(), e);
        if (clash != null) {
            throw new ExceptionInInitializerError("错误码目录出现重复码：" + e.qualified());
        }
        byNative.put(e.source().packageName() + "/" + e.nativeCode(), e);
    }

    /** 五条不变量：见类注释。任一不成立即拒绝启动——目录错了比没有目录更危险。 */
    private static void assertInvariants(Map<String, Entry> byQualified, Map<String, Entry> byNative) {
        // 1) 覆盖完整性：模型五类与 HIS 七类必须逐项登记，否则重投调度与本目录会对不上
        for (ModelErrorCode c : ModelErrorCode.values()) {
            require(byQualified, Source.MODEL, c.auditToken());
        }
        for (HisWriteErrorCode c : HisWriteErrorCode.values()) {
            require(byQualified, Source.HIS, c.name().toLowerCase(Locale.ROOT));
        }
        // 2) 本院 API 每一项都要有条目：调用点只挑 ApiErrorCode，漏登记就没有人发现
        for (ApiErrorCode c : ApiErrorCode.values()) {
            require(byQualified, Source.HTTP, c.name().toLowerCase(Locale.ROOT));
        }
        // 3) 拒收原因每一项都能查到 HL7 标准码（T9 的纪律在本目录里再确认一次）
        for (RejectionReason r : RejectionReason.values()) {
            ErrorCodeEntry entry = Hl7ErrorCodes.forRejection(r);
            if (entry == null || entry.code() == null || entry.code().isBlank()) {
                throw new ExceptionInInitializerError("拒收原因没有对应的 HL7 条目：" + r.name());
            }
        }
        // 4) retryable 必须与源枚举自述一致
        for (Map.Entry<String, Entry> e : byQualified.entrySet()) {
            Entry entry = e.getValue();
            boolean declaredBySourceEnum;
            if (entry.source() == Source.MODEL) {
                declaredBySourceEnum = ModelErrorCode.valueOf(entry.nativeCode()).retryable();
            } else if (entry.source() == Source.HIS) {
                declaredBySourceEnum = HisWriteErrorCode.valueOf(entry.nativeCode()).jittery();
            } else if (entry.source() == Source.REDELIVERY) {
                declaredBySourceEnum = ReconcileErrorCode.valueOf(entry.nativeCode()).jittery();
            } else {
                continue; // ack 用拒收原因自己的标记，上面已构造；http 的口径在本类内定义
            }
            if (declaredBySourceEnum != entry.retryable()) {
                throw new ExceptionInInitializerError(
                        "错误码 " + entry.qualified() + " 的 retryable 与源枚举自述不一致");
            }
        }
        // 5) 反查索引必须能覆盖全部条目
        if (byNative.size() != byQualified.size()) {
            throw new ExceptionInInitializerError("反查索引有条目丢失：" + byNative.size()
                    + " != " + byQualified.size());
        }
    }

    private static void require(Map<String, Entry> byQualified, Source source, String code) {
        if (!byQualified.containsKey(source.packageName() + "." + code)) {
            throw new ExceptionInInitializerError("错误码目录漏登记："
                    + source.packageName() + "." + code);
        }
    }

    /** 全量目录（只读，插入序即录入序）。 */
    public static List<Entry> entries() {
        return List.copyOf(BY_QUALIFIED.values());
    }

    /** 按 {@code 域.码} 取条目，查不到返回 {@code null}（不抛异常：查询口不该让调用方崩）。 */
    public static Entry byCode(String qualifiedCode) {
        return qualifiedCode == null ? null : BY_QUALIFIED.get(qualifiedCode);
    }

    /**
     * 按「来源包 + 原始码值」反查，如 {@code reconcile/CLIENT_ERROR}。
     *
     * <p>审计行与台账里存的是<b>原始码值</b>（它们本来就在那些包里产生），
     * 报表要按本目录的处置口径渲染，就得从原始码反查回来。
     */
    public static Entry byNative(String sourcePackage, String nativeCode) {
        if (sourcePackage == null || nativeCode == null) {
            return null;
        }
        return BY_NATIVE.get(sourcePackage + "/" + nativeCode);
    }

    /** 某一来源包的全部条目。 */
    public static List<Entry> entriesOf(Source source) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : BY_QUALIFIED.values()) {
            if (e.source() == source) {
                out.add(e);
            }
        }
        return List.copyOf(out);
    }

    /** 某一来源包里「重投可能恢复」的条目数 —— 一句话看清这批失败里有多少是抖动。 */
    public static long retryableCountOf(Source source) {
        return entriesOf(source).stream().filter(Entry::retryable).count();
    }

    /** 只读导出的 CSV 形态：表头 + 逐行。用 UTF-8 BOM 前置由调用方决定。 */
    public static String toCsv() {
        StringBuilder sb = new StringBuilder();
        sb.append("code,source,retryable,native_code,zh_label,remedy\n");
        for (Entry e : entries()) {
            sb.append(e.qualified()).append(',')
                    .append(e.source().packageName()).append(',')
                    .append(e.retryable() ? "true" : "false").append(',')
                    .append(csvEscape(e.nativeCode())).append(',')
                    .append(csvEscape(e.zhLabel())).append(',')
                    .append(csvEscape(e.remedy())).append('\n');
        }
        return sb.toString();
    }

    private static String csvEscape(String raw) {
        if (raw == null) {
            return "";
        }
        if (raw.indexOf(',') < 0 && raw.indexOf('"') < 0 && raw.indexOf('\n') < 0) {
            return raw;
        }
        return '"' + raw.replace("\"", "\"\"") + '"';
    }
}