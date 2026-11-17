package cn.hospital.region.archive.ack;

import cn.hospital.region.archive.hl7.FieldLocator;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HL7 v2 错误码目录 —— 本地建议码与 HL7 标准码（表 0357）的绑定表。
 *
 * <p>本类回答一个问题：<b>一条拒收回执里的中文说明，怎样才能让区域平台直接拿去归类？</b>
 * 做法是给本地原因码各绑一个标准码，出站时报文同时带两者：
 * <ul>
 *   <li>本地码（kebab-case，如 {@code patient-id-missing}）说明「本端判定是什么」，人看得懂；
 *       {@link cn.hospital.region.archive.hl7.FieldLocator} 与 {@link RejectionReason} 共用这一套词表。</li>
 *   <li>标准码（数字，如 {@code 101}）说明「按 HL7 属于哪一类」，平台能直接解析并路由到自己的整改流程。</li>
 * </ul>
 *
 * <p><b>没有对应标准码时不硬套。</b>「Z 段语义对齐失败」是院内业务结局，HL7 v2 表 0357 里
 * 根本没有这一类；把它塞进 {@code 206 处理过程中出错}，对端会以为是一次偶发故障并安排重投，
 * 而真实情况是这条报文对端永远补不对字段。这类码走 {@link #customCodes()}，
 * 码值带 {@code CUSTOM-} 前缀，并在出站报文里明确标注「对端不可按标准码解析」。
 *
 * <p><b>目录是只读的。</b>标准条目在静态块里一次性建好并做不变量校验（码为数字、表来源形如
 * {@code HL7nnnn}、版本非空），运行期不接受任何动态增补——错误码目录一旦可写，
 * 「同一条报文在回执里报出什么」就不可复现了，对账与审计都失去锚点。
 */
public final class Hl7ErrorCodes {

    // ------------------------------------------------------------ 标准码目录（HL7 v2 表 0357）

    /** 0：处理成功。ACK 路径用不到 MSA 之外的语义，留着便于对端按目录反查。 */
    public static final ErrorCodeEntry SUCCESS =
            ErrorCodeEntry.standard(0, "Success", "处理成功");

    /** 100：已接受但不处理——本端收下了报文，但按业务规则拒绝绑定（如身份硬锚点冲突）。 */
    public static final ErrorCodeEntry ACCEPTED_NOT_PROCESSED =
            ErrorCodeEntry.standard(100,
                    "The application accepted the message, but chose not to process it",
                    "已接受但不处理：报文收下了，按业务规则不做绑定");

    /** 101：缺必填字段或字段非法——本目录里最高频的一类（MSH-10、PID-3、PID-5、PID-7 都落这里）。 */
    public static final ErrorCodeEntry MISSING_OR_INVALID_ELEMENT =
            ErrorCodeEntry.standard(101,
                    "The application did not process the message because of a missing or invalid data element",
                    "缺必填字段或字段非法");

    /** 102：数据类型错误。 */
    public static final ErrorCodeEntry DATA_TYPE_ERROR =
            ErrorCodeEntry.standard(102,
                    "The application could not process the message because of a data type error",
                    "数据类型错误");

    /** 103：表中未找到该取值（如 MSH-9 事件码不在本端支持清单内）。 */
    public static final ErrorCodeEntry TABLE_VALUE_NOT_FOUND =
            ErrorCodeEntry.standard(103,
                    "The application could not process the message because of a table value not found",
                    "表中未找到该取值");

    /** 200：语法错误——报文根本不是合法 ER7。 */
    public static final ErrorCodeEntry SYNTAX_ERROR =
            ErrorCodeEntry.standard(200,
                    "The application could not process the message because of a syntax error",
                    "报文语法错误");

    /** 201：不支持的事件码。 */
    public static final ErrorCodeEntry UNSUPPORTED_EVENT_CODE =
            ErrorCodeEntry.standard(201,
                    "The application could not process the message because of an unsupported event code",
                    "不支持的事件码");

    /** 202：报文结构错误（如必需段 PID 整个没到）。 */
    public static final ErrorCodeEntry MESSAGE_STRUCTURE_ERROR =
            ErrorCodeEntry.standard(202,
                    "The application could not process the message because of a message structure error",
                    "报文结构错误（必需段缺失）");

    /** 204：版本不兼容（对端发 v2.7 而本端支持到 v2.5.1）。 */
    public static final ErrorCodeEntry VERSION_INCOMPATIBILITY =
            ErrorCodeEntry.standard(204,
                    "The application could not process the message because of a version incompatibility",
                    "版本不兼容");

    /** 206：处理过程中出错（语义对齐服务不可达等本端侧故障）。 */
    public static final ErrorCodeEntry PROCESSING_ERROR =
            ErrorCodeEntry.standard(206,
                    "The application could not process the message because of a processing error",
                    "处理过程中出错");

    /** 207：不支持的字符集（MSH-18 声明了本端解不出的字符集）。 */
    public static final ErrorCodeEntry UNSUPPORTED_CHARACTER_SET =
            ErrorCodeEntry.standard(207,
                    "The application could not process the message because of an unsupported character set",
                    "不支持的字符集");

    /** 标准码目录：码值 → 条目。条目顺序即目录录入顺序，便于只读导出时逐行比对。 */
    private static final Map<String, ErrorCodeEntry> STANDARD;

    static {
        Map<String, ErrorCodeEntry> m = new LinkedHashMap<>();
        for (ErrorCodeEntry e : List.of(
                SUCCESS,
                ACCEPTED_NOT_PROCESSED,
                MISSING_OR_INVALID_ELEMENT,
                DATA_TYPE_ERROR,
                TABLE_VALUE_NOT_FOUND,
                SYNTAX_ERROR,
                UNSUPPORTED_EVENT_CODE,
                MESSAGE_STRUCTURE_ERROR,
                VERSION_INCOMPATIBILITY,
                PROCESSING_ERROR,
                UNSUPPORTED_CHARACTER_SET)) {
            m.put(e.code(), e);
        }
        for (ErrorCodeEntry e : m.values()) {
            // 不变量：标准码必须是纯数字，且带 HL7 表来源。带 CUSTOM- 前缀的条目不放进标准目录。
            if (!e.code().chars().allMatch(Character::isDigit)) {
                throw new ExceptionInInitializerError("标准码 " + e.code() + " 不是纯数字");
            }
            if (!e.tableSource().matches("HL7\\d{4}")) {
                throw new ExceptionInInitializerError("标准码 " + e.code() + " 的表来源不合规：" + e.tableSource());
            }
            if (e.custom()) {
                throw new ExceptionInInitializerError("标准目录里混入自定义码：" + e.code());
            }
        }
        STANDARD = Collections.unmodifiableMap(m);
    }

    // ------------------------------------------------------------ 自定义码（无对应标准语义）

    /** Z 段语义对齐失败：HL7 表 0357 无对应类别，硬套 206 会让对端误以为偶发故障并安排重投。 */
    public static final ErrorCodeEntry SEMANTIC_ALIGN_FAILED =
            ErrorCodeEntry.custom("semantic-align-failed",
                    "Local semantic alignment of the Z segment to local fields failed",
                    "Z 段语义对齐失败，未做硬合并");

    private static final Map<String, ErrorCodeEntry> CUSTOM = Map.of(
            SEMANTIC_ALIGN_FAILED.code(), SEMANTIC_ALIGN_FAILED);

    // ------------------------------------------------------------ 本地建议码 → 标准码绑定

    /**
     * 本地建议码 → 目录条目。
     *
     * <p>键同时覆盖两套词表：{@link FieldLocator} 的必需字段缺失码（精确到 MSH-10 / PID-3 …）
     * 与 {@link RejectionReason} 的七类原因码（面向业务成因）。两者共用同一批绑定，
     * 出站时无论从哪条路径拿到一个本地码，都得到同一个标准码。
     */
    private static final Map<String, ErrorCodeEntry> BINDINGS;

    static {
        Map<String, ErrorCodeEntry> m = new LinkedHashMap<>();

        // FieldLocator 词表：缺什么字段 → 报哪个标准码
        m.put(FieldLocator.CODE_MISSING_SEGMENT, MESSAGE_STRUCTURE_ERROR);
        m.put(FieldLocator.CODE_UNSUPPORTED_EVENT_TYPE, UNSUPPORTED_EVENT_CODE);
        m.put(FieldLocator.CODE_CONTROL_ID_MISSING, MISSING_OR_INVALID_ELEMENT);
        m.put(FieldLocator.CODE_PROCESSING_ID_MISSING, MISSING_OR_INVALID_ELEMENT);
        m.put(FieldLocator.CODE_VERSION_MISSING, MISSING_OR_INVALID_ELEMENT);
        m.put(FieldLocator.CODE_PATIENT_ID_MISSING, MISSING_OR_INVALID_ELEMENT);
        m.put(FieldLocator.CODE_PATIENT_NAME_MISSING, MISSING_OR_INVALID_ELEMENT);
        m.put(FieldLocator.CODE_BIRTH_DATE_MISSING, MISSING_OR_INVALID_ELEMENT);
        // MSH-18 未声明是告警级、不阻断，但仍绑定 207：回执给出可解析的码，
        // 对端就能统计出「多少比例的报文没声明字符集」，这是推动对端改接口的抓手。
        m.put(FieldLocator.CODE_CHARSET_NOT_DECLARED, UNSUPPORTED_CHARACTER_SET);

        // RejectionReason 词表：业务成因 → 标准码
        m.put("missing-required-segment", MESSAGE_STRUCTURE_ERROR);
        m.put("missing-required-field", MISSING_OR_INVALID_ELEMENT);
        m.put("unsupported-charset", UNSUPPORTED_CHARACTER_SET);
        // 身份硬锚点冲突：报文本身合法、本端也收下了，只是按业务规则拒绝绑定 → 100（已接受但不处理）
        m.put("identity-conflict", ACCEPTED_NOT_PROCESSED);
        // 语义对齐失败：绑定到自定义码条目（这里写的是本地码，不是 CUSTOM- 前缀码值——
        // 目录里可查的是码值，出站报的是标准码，本地码才是各调用点手里那个键）
        m.put("semantic-align-failed", SEMANTIC_ALIGN_FAILED);
        // 模型服务不可达：本端侧故障，标准码 206 让对端知道「重投可能就好了」
        m.put("model-degraded", PROCESSING_ERROR);
        m.put("version-incompatible", VERSION_INCOMPATIBILITY);
        // 帧中途断流：报文结构不完整，按 202 归「报文结构错误」。AE 可重投，
        // 对端收到后重投一次通常就拿到完整报文（本端这里什么都没存，不重投就是永久丢一条）。
        m.put("message-truncated", MESSAGE_STRUCTURE_ERROR);

        BINDINGS = Collections.unmodifiableMap(m);
    }

    private Hl7ErrorCodes() {
    }

    /** 标准码目录全量（只读）。 */
    public static List<ErrorCodeEntry> standardEntries() {
        return List.copyOf(STANDARD.values());
    }

    /** 自定义码目录全量（只读）。 */
    public static List<ErrorCodeEntry> customEntries() {
        return List.copyOf(CUSTOM.values());
    }

    /** 按码值取目录条目；标准码与自定义码都可查，查不到返回 {@code null}（不抛异常）。 */
    public static ErrorCodeEntry byCode(String code) {
        if (code == null) {
            return null;
        }
        ErrorCodeEntry e = STANDARD.get(code);
        return e != null ? e : CUSTOM.get(code);
    }

    /** 按码值取目录条目，查不到直接抛——用于「目录不完整就是缺陷」这类强约束场景。 */
    public static ErrorCodeEntry requireByCode(String code) {
        ErrorCodeEntry e = byCode(code);
        if (e == null) {
            throw new IllegalArgumentException("错误码目录中没有该码：" + code);
        }
        return e;
    }

    /**
     * 按本地建议码取目录条目。
     *
     * <p><b>查不到时降级为自定义码</b>，而不是抛异常或默默丢字段：新增一条拒收原因却忘了在目录里登记，
     * 是很容易发生的维护失误；把它降级成 {@code CUSTOM-本地码}，回执照样发得出去，
     * 且一眼就能看出对端解析不了——比让一条报文因为「码没登记」发不出回执要好。
     */
    public static ErrorCodeEntry forSuggestedCode(String suggestedCode) {
        if (suggestedCode == null || suggestedCode.isBlank()) {
            return ErrorCodeEntry.custom("unspecified",
                    "No HL7 standard code defined for this local condition",
                    "本端原因未登记标准码，按自定义码上报");
        }
        ErrorCodeEntry bound = BINDINGS.get(suggestedCode);
        if (bound != null) {
            return bound;
        }
        // 调用点手上已经是目录里的码值（含 CUSTOM- 前缀）时直接取用，不重复造一条同码条目
        ErrorCodeEntry direct = byCode(suggestedCode);
        if (direct != null) {
            return direct;
        }
        return ErrorCodeEntry.custom(suggestedCode,
                "No HL7 standard code defined for this local condition",
                "本端原因未登记标准码，按自定义码上报");
    }

    /** 按拒收原因取目录条目。 */
    public static ErrorCodeEntry forRejection(RejectionReason reason) {
        if (reason == null) {
            throw new IllegalArgumentException("拒收原因不得为 null");
        }
        return forSuggestedCode(reason.suggestedCode());
    }

    /** 本地建议码 → 标准码的绑定表（只读），供只读导出与对照复核使用。 */
    public static Map<String, ErrorCodeEntry> bindings() {
        return BINDINGS;
    }
}