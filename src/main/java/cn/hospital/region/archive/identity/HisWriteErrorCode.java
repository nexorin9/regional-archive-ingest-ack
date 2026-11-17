package cn.hospital.region.archive.identity;

/**
 * 院内患者主索引写回失败的<b>分类码</b>。
 *
 * <h2>为什么按类分而不是按状态码分</h2>
 * <p>「HIS 写失败」这一个结论在排障时毫无用处：现场要回答的是<b>「现在该怎么办」</b>，
 * 而不同失败给出的处置完全不同：</p>
 * <ul>
 *   <li>{@link #CONNECT_TIMEOUT} / {@link #READ_TIMEOUT} / {@link #TRANSPORT_FAILURE}：
 *       院内 HIS 服务在抖或者网络中断，等一会儿自己会好 → 自动重投，不必叫醒人；</li>
 *   <li>{@link #SERVER_ERROR}（含 5xx 与 408/425/429）：HIS 自己在拒绝或限流 →
 *       同样值得重投，但退避要比网络抖动更久（这条退避档位由 T33 的调度器按本码决定）；</li>
 *   <li>{@link #CLIENT_ERROR}（其余 4xx）：<b>本端这条请求 HIS 收不了</b> ——
 *       401/403 是令牌过期或没授权，409/422 是 HIS 侧主索引冲突。
 *       原样重投一次还是会得到同样的答案，重投队列应该尽快放行给人；</li>
 *   <li>{@link #REDIRECT_UNRESOLVED}：HIS 把写接口重定向到了别处，
 *       多半是前置机上配的路径变了，重投无意义，先查配置。</li>
 * </ul>
 *
 * <p><b>本枚举只回答「怎么处置」，不回答「行停在哪」</b>：无论哪一类失败，
 * {@code ArchiveIngestService.land} 都把行退回待确认、原文与幂等键原样保留 ——
 * 那是 {@code PatientIndexWriteException#replayable} 与状态机管的事，不在本枚举的职责内。
 */
public enum HisWriteErrorCode {

    /** 连接阶段超时：HIS 主机不可达或端口不响应。 */
    CONNECT_TIMEOUT("连接院内患者主索引超时", true),

    /** 连上了但迟迟不返回响应。 */
    READ_TIMEOUT("读取院内患者主索引响应超时", true),

    /** 连接被拒 / 主机不可达 / DNS 失败 / TLS 握手失败 —— 在「能不能连上」这一层是同一件事。 */
    TRANSPORT_FAILURE("连接院内患者主索引失败", true),

    /** 对端 5xx，以及 408 请求超时 / 425 尚未就绪 / 429 限流。 */
    SERVER_ERROR("院内患者主索引返回服务端错误或限流", true),

    /** 对端其余 4xx：本端这条请求 HIS 收不了，重投会得到同样的结果。 */
    CLIENT_ERROR("院内患者主索引拒绝本端请求", false),

    /** 对端返回 3xx 且未能跟随到目标位置：多半是前置机路径配变了。 */
    REDIRECT_UNRESOLVED("院内患者主索引返回重定向且未能跟随", false),

    /** 写回过程中线程被中断（停机 / 取消）。 */
    INTERRUPTED("写院内患者主索引被中断", true);

    private final String zhLabel;

    /** 是否属于「原样重投就有希望恢复」的抖动类。 */
    private final boolean jittery;

    HisWriteErrorCode(String zhLabel, boolean jittery) {
        this.zhLabel = zhLabel;
        this.jittery = jittery;
    }

    /** 人可读的一句话，进日志与 HTTP 响应。 */
    public String zhLabel() {
        return zhLabel;
    }

    /**
     * 原样重投是否有希望恢复。
     *
     * <p>T32/T33 的调度器据此选退避档位：抖动类用短退避连打几次，
     * 非抖动类应当尽快转人工 —— 让一个注定被 400 拒掉的请求在重投队列里排三轮，
     * 只是把「HIS 路径配错了」这件事推迟三个人时后才有人看见。
     */
    public boolean jittery() {
        return jittery;
    }

    /**
     * 按 HTTP 状态码分类非 2xx 响应。
     *
     * <p>四段的<b>先后次序不能换</b>：408 / 425 / 429 是 4xx 里的「等一会儿再来」，
     * 必须先于 4xx 那一段判掉；而 3xx 又排在 4xx 之后 —— 写成 {@code if (status >= 300)}
     * 会把全部 4xx 一起吞进「重定向」分支，于是<b>令牌失效（401）、主键冲突（409）
     * 全被标成重定向</b>，重投调度会照着「先查配置」的建议去查一个根本没重定向的问题。
     */
    public static HisWriteErrorCode classify(int status) {
        if (status >= 500 || status == 408 || status == 425 || status == 429) {
            return SERVER_ERROR;
        }
        if (status >= 400) {
            return CLIENT_ERROR;
        }
        if (status >= 300) {
            return REDIRECT_UNRESOLVED;
        }
        // 1xx/2xx 走不到这里（调用点已判过 2xx）。兜底仍归客户端错误：
        // 一个说不清来源的状态码，宁可按「本端请求有问题」处置，也不要自动无限重投。
        return CLIENT_ERROR;
    }
}