package cn.hospital.region.archive.ack;

/**
 * 拒收原因 —— 决定 MSA-1 取 AA / AE / AR 中的哪一值，以及该条是否允许进入重投集合。
 *
 * <p><b>为什么是三值而不是两值</b>：区域平台收到 {@code AE}（应用错误）知道「这条我重投一次可能就成了」，
 * 收到 {@code AR}（应用拒绝）知道「这条重投一万次还是这个字节，别再发了」。本产品的七类原因据此二分：
 *
 * <ul>
 *   <li>{@link #MISSING_REQUIRED_SEGMENT} / {@link #MISSING_REQUIRED_FIELD} / {@link #SEMANTIC_ALIGN_FAILED}
 *       / {@link #MODEL_DEGRADED} → <b>AE（可重投）</b>：成因可能在传输截断、对端生成抖动或本端服务抖动，
 *       重投一次能让对端拿到完整报文、或让已恢复的模型再判一次。</li>
 *   <li>{@link #UNSUPPORTED_CHARSET} / {@link #IDENTITY_CONFLICT} / {@link #VERSION_INCOMPATIBLE}
 *       → <b>AR（不重投）</b>：成因是对端报文本身的确定性事实，重投只会把同样一份字节再打一遍，
 *       属于「把坏包无限打给对端」，T32 的重投队列据此把 AR 整类排除。</li>
 * </ul>
 *
 * <p><b>与传输层 0x06 / 0x15 的关系</b>：源实现 {@code MLLPConnection} 的 ACK/NAK 是<b>传输级</b>的
 * （{@code :55-56} 两个常量，{@code :110-114} 写裸 0x06，{@code :116-131} 读回时 NAK 抛异常），
 * 只说明「字节收到了 / 没收到」；本枚举是<b>应用级</b>的，是每条报文可以和本地台账逐条对账的结果。
 * 两者不得互相替代——收到传输级 ACK 的报文在应用级仍可能是待确认或拒收。
 *
 * <p>{@link #suggestedCode()} 是 kebab-case 本地码（与 {@code FieldLocator} 的错误码同一词表，
 * 便于直接拼进 ERR 说明）；{@link #standardCode()} 给它对应的 HL7 目录条目，
 * 让出站回执带的对端能直接解析的标准码与本端词表一一对应。
 */
public enum RejectionReason {

    /** 缺 MSH / PID 等必需段：报文多半在半路被截断，重投有可能拿到完整报文。 */
    MISSING_REQUIRED_SEGMENT(AckComposer.CODE_AE, true,
            "missing-required-segment", "缺少必需段"),

    /** 必需字段缺失或为空（如 MSH-10 控制号、PID-3 跨院标识）：对端生成侧抖动，重投可暴露。 */
    MISSING_REQUIRED_FIELD(AckComposer.CODE_AE, true,
            "missing-required-field", "必需字段缺失或为空"),

    /** MSH-18 声明了本端不认识的字符集：报文解不出字段，重投仍是同样字节，不重投。 */
    UNSUPPORTED_CHARSET(AckComposer.CODE_AR, false,
            "unsupported-charset", "声明的字符集不支持，无法解码"),

    /** 身份证硬锚点与院内患者主索引冲突：确定性事实，重投不会改变，不重投。 */
    IDENTITY_CONFLICT(AckComposer.CODE_AR, false,
            "identity-conflict", "身份硬锚点冲突，拒绝绑定"),

    /** Z 段语义对齐失败：等对端补字段或等人工确认后可再处理，AE 可重投。 */
    SEMANTIC_ALIGN_FAILED(AckComposer.CODE_AE, true,
            "semantic-align-failed", "Z 段语义对齐失败，未做硬合并"),

    /** 模型服务不可达（降级）：本端侧抖动，模型恢复后重投可能直接对齐，AE 可重投。 */
    MODEL_DEGRADED(AckComposer.CODE_AE, true,
            "model-degraded", "语义对齐服务不可用，本条降级为待确认"),

    /** 版本不兼容（如对端发 v2.7 而本端仅支持到 v2.5.1）：AR 不重投。 */
    VERSION_INCOMPATIBLE(AckComposer.CODE_AR, false,
            "version-incompatible", "报文版本不兼容"),

    /**
     * 报文在帧中途断流：收到了起始块却始终没等到帧尾 {@code 0x1C 0x0D}。
     *
     * <p>归 <b>AE 可重投</b>：这几乎总是网络侧的一次半截传输（对端写 socket 时被断、
     * 中间盒子超时、会话被回收），重投一次能拿到完整报文。归 AR 是错的：
     * 那会让区域平台把这条留在自己那边的死信堆里，而本端其实<b>什么都没存</b>——
     * 次日对账时「回执条数比平台发送条数少一条」，现场却查不出少的是哪条。
     */
    MESSAGE_TRUNCATED(AckComposer.CODE_AE, true,
            "message-truncated", "报文在帧中途断流，未收到帧尾 0x1C 0x0D");

    private final String msa1;
    private final boolean redeliverable;
    private final String suggestedCode;
    private final String zhLabel;

    RejectionReason(String msa1, boolean redeliverable, String suggestedCode, String zhLabel) {
        this.msa1 = msa1;
        this.redeliverable = redeliverable;
        this.suggestedCode = suggestedCode;
        this.zhLabel = zhLabel;
    }

    static {
        // 不变量：redeliverable 不是「顺手写一下」的注释，而是重投队列的分流依据，
        // 写反了会让 AR 类报文被反复打给区域平台。在这里一次性校验，枚举里写错立刻炸。
        for (RejectionReason r : values()) {
            boolean expected = AckComposer.CODE_AE.equals(r.msa1);
            if (expected != r.redeliverable) {
                throw new ExceptionInInitializerError("拒收原因 " + r.name()
                        + " 的 redeliverable 与 MSA-1=" + r.msa1 + " 不自洽");
            }
            // 不变量：每一类拒收原因都必须在 HL7 错误码目录里登记一条码。
            // 新增枚举项却忘了登记，ERR 段就会带出一个对端解析不了的裸字符串——
            // 那正是错误码目录要消灭的情况，所以在类加载时拦掉。
            if (Hl7ErrorCodes.forRejection(r) == null) {
                throw new ExceptionInInitializerError("拒收原因 " + r.name()
                        + " 的本地码 " + r.suggestedCode() + " 未在错误码目录登记");
            }
        }
    }

    /** MSA-1 应用级回执代码：{@code AE} 或 {@code AR}（成功路径不走本枚举）。 */
    public String msa1() {
        return msa1;
    }

    /**
     * 是否允许进入重投集合。
     *
     * <p>AE 为 {@code true}、AR 为 {@code false}，由枚举静态块强制校验；
     * 重投队列以本值为准入条件之一（另一条是台账状态为待确认）。
     */
    public boolean redeliverable() {
        return redeliverable;
    }

    /** kebab-case 建议错误码，本端判定口径；ERR-1 与 ERR-3 首组件带的是它。 */
    public String suggestedCode() {
        return suggestedCode;
    }

    /**
     * 本类对应的 HL7 错误码目录条目（标准码或自定义码）。
     *
     * <p>出站回执带它是为了让区域平台<b>直接解析</b>：只回一句中文说明，对端只能进人工信箱；
     * 带 {@code 207} 的回执能被对端归到「字符集不支持」并触发自己的接口整改流程。
     */
    public ErrorCodeEntry standardCode() {
        return Hl7ErrorCodes.forRejection(this);
    }

    /** 一行中文说明，进 MSA-3 与 ERR-4；不含患者可识别信息。 */
    public String zhLabel() {
        return zhLabel;
    }

    /** 是否为 AR（不可重投）——重投队列排除 AR 的判定入口。 */
    public boolean permanentReject() {
        return AckComposer.CODE_AR.equals(msa1);
    }
}