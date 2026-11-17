package cn.hospital.region.archive.redelivery;

/**
 * 对账出站的失败分类 —— 「对账没跑成」和「对账跑了、结论是真的缺 2 条」必须能分开。
 *
 * <p>这两件事在现场长得很像：信息员看到「重投清单空着」时无法分辨是平台真没收到回执，
 * 还是我们根本没问成。后者若被当成前者，差集就是<b>静默清空</b> ——
 * 平台次日追问时一条都重投不了，而日志里什么异常都没有。
 * 所以取回执失败一律抛 {@link ReconcileException}，<b>绝不用空清单冒充「一条不缺」</b>。
 *
 * <p>{@link #jittery()} 决定处置口径：抖动类原样重投即可恢复；非抖动类是配置或对端事实问题，
 * 重投多少次都一样，必须人去查。
 */
public enum ReconcileErrorCode {

    /** HTTP 401 / 403：令牌不对或已过期。 */
    AUTH_REJECTED("对账令牌被拒", false),
    /** HTTP 404：对端不认识这个批次号（批次号写错，或平台侧还没建批）。 */
    BATCH_NOT_FOUND("对端没有这个批次", false),
    /** HTTP 429：对端限流。 */
    RATE_LIMITED("对端限流", true),
    /** HTTP 4xx（401/403/404/429 之外）：请求本身不对。 */
    CLIENT_ERROR("请求被对端拒绝", false),
    /** HTTP 5xx。 */
    SERVER_ERROR("对端服务异常", true),
    /** 非 2xx 且不属于以上任何一类（如 3xx 未被跟随）。 */
    UNEXPECTED_STATUS("对端返回了未预期的状态码", false),
    /** 连接超时。 */
    CONNECT_TIMEOUT("连不上对端", true),
    /** 读超时。 */
    READ_TIMEOUT("对端迟迟不回", true),
    /** 连接被拒 / DNS 失败 / TLS 握手失败等传输层失败。 */
    TRANSPORT_FAILURE("传输层失败", true),
    /** 等待期间线程被中断。 */
    INTERRUPTED("对账等待被中断", true),
    /** 响应体不符合正式字段规范（不是 JSON、缺 acks 数组、条目缺控制号等）。 */
    MALFORMED_RESPONSE("对端响应不符合回执列表字段规范", true);

    private final String zhLabel;
    private final boolean jittery;

    ReconcileErrorCode(String zhLabel, boolean jittery) {
        this.zhLabel = zhLabel;
        this.jittery = jittery;
    }

    /** 人话标签，进日志与异常消息。 */
    public String zhLabel() {
        return zhLabel;
    }

    /** 抖动类：原样重跑一次即可恢复；非抖动类：重投一万次还是同样结果，先查配置与对端事实。 */
    public boolean jittery() {
        return jittery;
    }

    /** 处置建议，进异常消息让人知道下一步做什么。 */
    public String disposition() {
        return jittery ? "原样重跑即可恢复" : "先查 ARCHIVE_ACK_API_BASE_URL / ARCHIVE_ACK_API_TOKEN 与批次号，再决定是否重跑";
    }

    /** 按 HTTP 状态码分类；2xx 返回 {@code null}（不是失败）。 */
    public static ReconcileErrorCode classify(int status) {
        if (status == 401 || status == 403) {
            return AUTH_REJECTED;
        }
        if (status == 404) {
            return BATCH_NOT_FOUND;
        }
        if (status == 429) {
            return RATE_LIMITED;
        }
        if (status / 100 == 4) {
            return CLIENT_ERROR;
        }
        if (status / 100 == 5) {
            return SERVER_ERROR;
        }
        return UNEXPECTED_STATUS;
    }
}