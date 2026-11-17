package cn.hospital.region.archive.hl7;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 段/字段级错误定位 —— **全产品唯一的定位文案与错误码出口**。
 *
 * <p>源产品侧的原话是「对不上格式的那条自己掉出来让我看，而不是整包退回去」；源实现
 * {@code HL7Parser} 只能给出「第几行 + 整行原文」（{@code HL7Exception} 的两个字段），
 * 对不上区域平台要解析的 ERR 段语义。本类把这段主路径改写为：
 *
 * <pre>
 *   入站报文 → Hl7Message（段/字段索引）
 *            → FieldLocator.locate(msg)          按必需字段清单逐项比对
 *            → List&lt;LocatableError&gt;          段名 + 字段序号 + 段内序号 + 原值片段 + 建议码
 *            → firstBlocking(msg)                主路径只取第一条阻断错，交给 T8 组 ERR 段
 * </pre>
 *
 * <p><b>设计纪律</b>：
 * <ol>
 *   <li><b>清单外置</b>：必需字段集中在本类 {@link #REQUIRED_FIELDS}，新增字段只改这一处，
 *       各调用点不得自行判断「这个字段要不要」。</li>
 *   <li><b>单一文案出口</b>：{@link #describe(LocatableError)} 是唯一拼装位置的方法；
 *       API 响应、ERR-4 中文说明、日志、台账导出都调它，杜绝同一错误在不同端点报出不同位置。</li>
 *   <li><b>不静默丢字段</b>：定位失败一律产出 {@link LocatableError}，不返回 null、不抛裸异常。</li>
 *   <li><b>明文不进片段</b>：只有 MSH/BHS/FHS 允许片段携带原文（协议头，本就不含患者数据）；
 *       其余所有段的片段一律脱敏成「段名|（已脱敏：n 字段）」，需要核对时用 {@code valueDigest}
 *       （SHA-256 前 8 位）比对，不落姓名/身份证/出生日期/电话/地址明文。</li>
 * </ol>
 */
public final class FieldLocator {

    // ------------------------------------------------------------ 错误码目录

    /** 整段缺失（MSH / PID 等必需段没到）。 */
    public static final String CODE_MISSING_SEGMENT = "missing-required-segment";
    /** 缺 MSH-9：没有消息类型，无法判定走哪条处理路径，也无法回一个语义正确的 ACK。 */
    public static final String CODE_UNSUPPORTED_EVENT_TYPE = "unsupported-event-type";
    /** 缺 MSH-10：没有控制号，回执 MSA-2 无从指向，且幂等键退化为不可判定。 */
    public static final String CODE_CONTROL_ID_MISSING = "control-id-missing";
    /** 缺 MSH-11：处理 ID 缺失，ADT 消息的先后次序无法判定。 */
    public static final String CODE_PROCESSING_ID_MISSING = "processing-id-missing";
    /** 缺 MSH-12：版本号缺失，无法与对端协商字段定义。 */
    public static final String CODE_VERSION_MISSING = "version-missing";
    /** 缺 MSH-18：字符集未声明，按 T6 的既定默认路径走 UTF-8 并留告警，**不阻断**。 */
    public static final String CODE_CHARSET_NOT_DECLARED = "charset-not-declared";
    /** 缺 PID-3：跨院患者标识为空，无法与院内患者主索引做任何绑定。 */
    public static final String CODE_PATIENT_ID_MISSING = "patient-id-missing";
    /** 缺 PID-5：姓名为空，信息员无法人工判断该不该点确认。 */
    public static final String CODE_PATIENT_NAME_MISSING = "patient-name-missing";
    /** 缺 PID-7：出生日期为空，身份候选只剩姓名一项，达不到确认门槛。 */
    public static final String CODE_BIRTH_DATE_MISSING = "birth-date-missing";

    // ------------------------------------------------------------ 必需字段清单

    /**
     * 一条必需字段的规范。
     *
     * @param segmentName 段名
     * @param fieldIndex  字段序号（1-based，与 HL7 规范一致）
     * @param label       中文名，用于 {@link #describe} 的文案
     * @param code        缺失时建议的错误码
     * @param severity    阻断 / 告警
     * @param identifying 该字段是否属于患者可识别信息（决定错误片段是否脱敏）
     */
    public record RequiredField(String segmentName, int fieldIndex, String label, String code,
                                LocatableError.Severity severity, boolean identifying) {
    }

    /**
     * 必需字段清单 —— 本产品接入侧的最小契约。
     *
     * <p>MSH-18 是唯一的告警级项：国内区域平台有相当一部分实现根本不写字符集，
     * 按 T6 的既定语义默认 UTF-8 并留告警；若把它列为阻断，会把大批合法报文整批打回，
     * 反而回到源产品「整包退回去」的老毛病。
     */
    private static final List<RequiredField> REQUIRED_FIELDS = List.of(
            new RequiredField("MSH", 9, "消息类型", CODE_UNSUPPORTED_EVENT_TYPE,
                    LocatableError.Severity.BLOCKING, false),
            new RequiredField("MSH", 10, "报文控制号", CODE_CONTROL_ID_MISSING,
                    LocatableError.Severity.BLOCKING, false),
            new RequiredField("MSH", 11, "处理标识", CODE_PROCESSING_ID_MISSING,
                    LocatableError.Severity.BLOCKING, false),
            new RequiredField("MSH", 12, "版本号", CODE_VERSION_MISSING,
                    LocatableError.Severity.BLOCKING, false),
            new RequiredField("MSH", 18, "字符集", CODE_CHARSET_NOT_DECLARED,
                    LocatableError.Severity.ADVISORY, false),
            new RequiredField("PID", 3, "跨院患者标识", CODE_PATIENT_ID_MISSING,
                    LocatableError.Severity.BLOCKING, true),
            new RequiredField("PID", 5, "患者姓名", CODE_PATIENT_NAME_MISSING,
                    LocatableError.Severity.BLOCKING, true),
            new RequiredField("PID", 7, "出生日期", CODE_BIRTH_DATE_MISSING,
                    LocatableError.Severity.BLOCKING, true)
    );

    /** 必需段：缺整段与缺字段走同一出口，只是 fieldIndex=0。 */
    private static final List<String> REQUIRED_SEGMENTS = List.of("MSH", "PID");

    /**
     * ACK 回执免检的段：回执按 HL7 v2 规范只承载 MSH/MSA/ERR，本就不带 PID。
     * 若对 ACK 也要求 PID-3/5/7，本系统会把**自己发出去的每一条回执**判成缺必填字段，
     * 等于自己拒收自己。
     */
    private static final Set<String> ACK_EXEMPT_SEGMENTS = Set.of("PID");

    /**
     * 允许错误片段携带原文的段 —— 仅协议头。
     * MSH/BHS/FHS 承载的是发送应用、控制号、版本、字符集等协议字段，本就不含患者数据；
     * 其余段（PID/PV1/ERR 之外的 OBX、ZX* 等）一律按脱敏规则生成片段。
     */
    private static final Set<String> SNIPPET_PLAINTEXT_ALLOWED = Set.of("MSH", "BHS", "FHS");

    private FieldLocator() {
    }

    // ------------------------------------------------------------ 公开入口

    /** 必需字段清单（不可变）。新增必填字段只改本类常量表。 */
    public static List<RequiredField> requiredFields() {
        return REQUIRED_FIELDS;
    }

    /**
     * 全量定位：按「必需段 → 清单顺序」逐项比对，返回**全部**可定位错误（含告警级），顺序确定。
     *
     * <p>主路径只需要第一条阻断错时用 {@link #firstBlocking(Hl7Message)}；
     * 台账导出与运维排障需要全部条目时用本方法。
     *
     * <p>清单按消息类型裁剪：ACK 回执免检 PID 侧字段（回执规范上就没有患者段）。
     */
    public static List<LocatableError> locate(Hl7Message msg) {
        Objects.requireNonNull(msg, "msg");
        List<LocatableError> out = new ArrayList<>();
        boolean ack = isAck(msg);

        for (String segName : REQUIRED_SEGMENTS) {
            if (ack && ACK_EXEMPT_SEGMENTS.contains(segName)) {
                continue;
            }
            Hl7Message.Segment segment;
            if (msg.has(segName)) {
                segment = msg.segment(segName);
            } else {
                out.add(LocatableError.missingSegment(segName, CODE_MISSING_SEGMENT,
                        LocatableError.Severity.BLOCKING));
                continue;
            }
            for (RequiredField req : REQUIRED_FIELDS) {
                if (!req.segmentName().equals(segName)) {
                    continue;
                }
                Optional<LocatableError> err = check(segment, req);
                err.ifPresent(out::add);
            }
        }
        return List.copyOf(out);
    }

    /**
     * 是否 ACK 回执（按 MSH-9 首个组件判定，取不到 MSH-9 时按「非 ACK」处理，
     * 让 MSH-9 自身的缺失照常被报出来）。
     */
    public static boolean isAck(Hl7Message msg) {
        if (msg == null || !msg.has("MSH")) {
            return false;
        }
        try {
            String first = msg.component("MSH", 9, 1);
            return "ACK".equalsIgnoreCase(first);
        } catch (RuntimeException e) {
            // MSH-9 越界/为空交给 check() 正常报出，这里只负责判定是否 ACK
            return false;
        }
    }

    /**
     * 主路径入口：取第一条**阻断级**错误。
     *
     * <p>有阻断错即按 AE/AR 处理；只剩告警级（如 MSH-18 未声明）则返回空，
     * 调用方继续按 T6 的既定默认路径处理，不因此打断整批。
     */
    public static Optional<LocatableError> firstBlocking(Hl7Message msg) {
        for (LocatableError e : locate(msg)) {
            if (e.blocking()) {
                return Optional.of(e);
            }
        }
        return Optional.empty();
    }

    /**
     * 唯一文案出口 —— 供 T8 的 ERR-4 中文说明、HTTP 响应体、日志与台账导出共用。
     *
     * <p>禁止各调用点自行拼「段名 + 字段 + 段内序号 + 原值片段」：一旦有第二个拼装点，
     * 同一错误就会在 MLLP 回执与 HTTP 响应里报出两个位置，信息员按哪个查都查不到。
     */
    public static String describe(LocatableError err) {
        Objects.requireNonNull(err, "err");
        String label = REQUIRED_FIELDS.stream()
                .filter(f -> f.segmentName().equals(err.segmentName()) && f.fieldIndex() == err.fieldIndex())
                .map(RequiredField::label)
                .findFirst()
                .orElse(err.fieldIndex() == 0 ? "必需段" : "字段");
        return err.describe(label);
    }

    // ------------------------------------------------------------ 内部

    private static Optional<LocatableError> check(Hl7Message.Segment segment, RequiredField req) {
        String raw = rawValue(segment, req.fieldIndex());
        if (raw != null && !raw.isBlank()) {
            return Optional.empty();
        }
        // 字段在段内不存在（段太短）与字段为空，都按「缺失」报——对区域平台是同一件事：
        // 这个字段没给出。区别只体现在片段里（片段会带段原文供核对）。
        String snippet = snippetFor(segment);
        return Optional.of(LocatableError.of(
                req.segmentName(), req.fieldIndex(), segment.ordinal(), snippet,
                req.code(), req.severity(), digestOf(raw)));
    }

    /**
     * 取字段原始值；字段下标越界（段太短）返回 {@code null}，与「值为空」区分但对上层同一处理。
     */
    private static String rawValue(Hl7Message.Segment segment, int idx) {
        try {
            return segment.field(idx);
        } catch (FieldIndexOutOfBounds e) {
            return null;
        }
    }

    /**
     * 生成原值片段 —— **脱敏策略的唯一实现**。
     *
     * <ul>
     *   <li>协议头段（MSH/BHS/FHS）：取段原文截断到 32 字符，供信息员直接看是哪一行对不上。</li>
     *   <li>其余段：只落「段名|（已脱敏：n 字段）」，绝不携带任何字段值。</li>
     * </ul>
     * 无论哪条路径，长度都 ≤ {@link LocatableError#MAX_SNIPPET}（构造器再兜一次）。
     */
    static String snippetFor(Hl7Message.Segment segment) {
        if (SNIPPET_PLAINTEXT_ALLOWED.contains(segment.name())) {
            String raw = segment.rawLine();
            if (raw == null) {
                return "";
            }
            return raw.length() > LocatableError.MAX_SNIPPET
                    ? raw.substring(0, LocatableError.MAX_SNIPPET)
                    : raw;
        }
        return segment.name() + "|（已脱敏：" + segment.fieldCount() + " 字段）";
    }

    /** 原值的 SHA-256 前 8 位；字段为空（本次缺字段场景）时返回空串，不伪造摘要。 */
    static String digestOf(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(8);
            for (int i = 0; i < 4; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 必须提供 SHA-256", e);
        }
    }
}