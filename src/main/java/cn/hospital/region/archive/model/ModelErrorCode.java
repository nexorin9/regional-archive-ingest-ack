package cn.hospital.region.archive.model;

/**
 * 模型调用失败的五类错误码 —— <b>整个模型侧只有这五种失败</b>。
 *
 * <p>分类的唯一目的不是「说得更细」，而是回答一个运维问题：
 * <b>这一类失败重试有没有用？</b>把五类错误按这个问句二分，退避策略就只剩一条规则：
 *
 * <ul>
 *   <li>{@link #CONNECT_TIMEOUT} —— 连不上（连接超时 / 拒绝连接 / DNS 失败）：<b>可重试</b></li>
 *   <li>{@link #READ_TIMEOUT} —— 连上了但迟迟不给响应：<b>可重试</b></li>
 *   <li>{@link #NON_2XX} —— 对端明确报错（401 密钥失效 / 429 限流 / 5xx 故障）：<b>不重试</b></li>
 *   <li>{@link #UNPARSABLE} —— 响应不是合法 JSON：<b>不重试</b></li>
 *   <li>{@link #SCHEMA_INVALID} —— JSON 合法但不符合固定 Schema：<b>不重试</b></li>
 * </ul>
 *
 * <h2>为什么后三类一律不重试</h2>
 * <p>{@code 401} 重试一万次还是 {@code 401}（该换密钥，不是该重试）；
 * 响应不是合法 JSON 时重试拿到的多半还是同样一份坏内容；
 * 而结构不符虽然「看起来像是偶发」，但它说明的是<b>这个端点没有按我们随请求发过去的 Schema 解码</b>——
 * 换句话说问题在服务端配置上，不在这一次调用上。把重试预算花在确定性失败上，
 * 结果是每条报文都多等三倍超时才降级，监听口被拖慢，而问题一条也没被修好。
 *
 * <h2>为什么不留「其它」这一类</h2>
 * <p>留一个兜底类的问题在于：兜底的处置方式只能靠猜（本类取「不重试」），
 * 而猜出来的策略是<b>没人验证过</b>的。做成<b>封闭枚举</b>并让
 * {@link ModelCallException} 强制携带它，任何一处新增抛点都必须先回答「这是哪一类」——
 * 答不上来就编译不过。这是本产品对「五类错误」这条纪律能守住的原因。
 *
 * <p>每一类都带一句中文说明与一条处置建议，出错时直接进 MSA-3 / 审计行，
 * 信息员看到的是「语义对齐服务密钥失效，请检查 MODEL_API_KEY」而不是一串状态码。
 */
public enum ModelErrorCode {

    /** 连不上：连接超时、拒绝连接、主机不可达。成因在本端侧或网络侧，稍后重试有可能成。 */
    CONNECT_TIMEOUT(true,
            "连接语义对齐服务超时",
            "本端到语义对齐服务的网络不通或端点不可达；请核对 MODEL_BASE_URL 与院内网络策略"),

    /** 连上了但读不到响应：请求超时、服务端迟迟不返回。 */
    READ_TIMEOUT(true,
            "读取语义对齐响应超时",
            "服务已受理但迟迟未返回；可在 MODEL_TIMEOUT_SECONDS 与院内服务容量之间核对"),

    /** 对端明确报错：401 / 403 鉴权失败、429 限流、5xx 服务端故障。 */
    NON_2XX(false,
            "语义对齐服务返回错误状态",
            "按状态码处置：401/403 换密钥，429 降并发，5xx 联系模型服务维护方"),

    /** 响应体不是合法 JSON：被网关截断、返回了 HTML 错误页、或返回了纯文本。 */
    UNPARSABLE(false,
            "语义对齐响应不可解析",
            "响应不是合法 JSON；请核对 MODEL_BASE_URL 是否指向了 OpenAI-compatible 端点而非网页地址"),

    /** JSON 合法但不符合固定 Schema：字段缺失、类型不对、返回了多余结构。 */
    SCHEMA_INVALID(false,
            "语义对齐响应结构校验失败",
            "端点未按随请求下发的 JSON Schema 输出；请核对模型是否支持 response_format=json_schema");

    private final boolean retryable;
    private final String zhLabel;
    private final String remedy;

    ModelErrorCode(boolean retryable, String zhLabel, String remedy) {
        this.retryable = retryable;
        this.zhLabel = zhLabel;
        this.remedy = remedy;
    }

    /**
     * 重试有没有用。
     *
     * <p>由 {@code ModelRetryPolicy} 直接读取：{@code true} 才按 {@code MODEL_MAX_RETRIES} 退避重试，
     * {@code false} 第一次失败就降级。
     */
    public boolean retryable() {
        return retryable;
    }

    /** 一行中文说明，进 MSA-3 与台账审计行；不含患者可识别信息。 */
    public String zhLabel() {
        return zhLabel;
    }

    /** 处置建议：这一类失败现场该做什么。 */
    public String remedy() {
        return remedy;
    }

    /**
     * 台账审计行用的原因片段，例如 {@code READ_TIMEOUT}。
     *
     * <p>与 {@link #name()} 逐字相同，但单独给一个访问器，是为了让「审计行里用哪一段」
     * 这个格式决定只存在于本类，日后改枚举名时不必去翻审计行的解析代码。
     */
    public String auditToken() {
        return name();
    }

    /**
     * 按 {@link #auditToken()} 解析枚举值 —— 审计行从库里读回来时的反向入口。
     *
     * <p>未知值<b>抛异常而不返回 null</b>：库里出现一个本版本不认识的错误码，
     * 只可能意味着「写进去的是另一个版本的枚举值」。那是一次真实的数据错配，
     * 把它降级成 null 会让报表上那条调用的失败原因变成空白，而空白看起来像「没失败」。
     * 让 {@code model audit} 因此报错退出，运维至少知道版本对不上。
     *
     * @param token 库里的 {@code error_code} 取值（空值由调用方先行处理）
     * @throws IllegalArgumentException 取值不属于本枚举
     */
    public static ModelErrorCode fromAuditToken(String token) {
        for (ModelErrorCode c : values()) {
            if (c.auditToken().equals(token)) {
                return c;
            }
        }
        throw new IllegalArgumentException("未知的模型错误码：" + token
                + "；本版本只认 " + java.util.Arrays.toString(auditTokens()));
    }

    /** 全部审计取值，供错误提示与测试断言。 */
    public static String[] auditTokens() {
        ModelErrorCode[] all = values();
        String[] out = new String[all.length];
        for (int i = 0; i < all.length; i++) {
            out[i] = all[i].auditToken();
        }
        return out;
    }
}