package cn.hospital.region.archive.ledger;

/**
 * 报文级应用级状态枚举 —— 一条入站报文在产品中的生命周期。
 *
 * <p><b>为什么拆主态与重投子态</b>：源产品（{@code github_ref/dcm4che/dcm4che-net-hl7/.../HL7ConnectionMonitor.java}）
 * 用一个 {@code onMessageProcessed(conn, s, msg, rsp, ex)} 双出口回调 —— 报文要么「处理完有响应」、
 * 要么「处理过程抛异常」，两条都汇到同一个出口。本产品把这条主路径改写为<b>报文级四态</b>，每条报文
 * 都必须落到一个明确的业务状态，<b>不允许静默丢弃</b>；再叠加重投子态处理次日重投。
 *
 * <p><b>四态语义</b>：
 * <ul>
 *   <li>{@link #RECEIVED} —— 已接收、已落库、已分帧、已结构化（拆分结束），尚未进入业务决策。
 *       这是入站台账行的默认状态。</li>
 *   <li>{@link #PENDING_CONFIRM} —— 待人工确认。模型给出身份候选、Z 段语义对齐未达成、或者
 *       业务规则要求人看一眼的，全部进此态，<b>绝不自动落地</b>。</li>
 *   <li>{@link #LANDED} —— 已落地。指经过确认门（{@code identity.ConfirmGate.confirm()}）
 *       后已写入院内 HIS 患者主索引，或在 RECEIVED 阶段就已无歧义直接落地。</li>
 *   <li>{@link #REJECTED} —— 拒收。本端判缺必填、字符集不支持、身份锚点冲突、版本不兼容，
 *       MSA-1=AR 且不进入重投集合。</li>
 * </ul>
 *
 * <p><b>重投子态</b>：当回执 MSA-1=AE（可重投）或对端次日追问未收回执时，报文进入以下子态
 * 等待调度重投：
 * <ul>
 *   <li>{@link #PENDING_RETRY} —— 已入重投队列、等待 {@code RedeliveryScheduler} 调度</li>
 *   <li>{@link #RETRYING} —— 已被调度出队、正在向对端重投</li>
 *   <li>{@link #RETRY_CONFIRMED} —— 重投成功、对端回 AA；下一步进 LANDED</li>
 *   <li>{@link #RETRY_PERMANENT_FAILED} —— 重投达到 {@code MAX_ATTEMPTS} 上限、停止自动重试，
 *       仅可由人工放行回到 {@link #PENDING_RETRY}</li>
 * </ul>
 *
 * <p><b>字符串映射</b>：{@link #dbValue()} 的返回值与 V1 schema 的 {@code message_ledger.state}
 * 列一一对应；调用方写入数据库前应走 {@link MessageStateMachine#assertTransition} 校验合法边。
 */
public enum MessageState {

    // ========== 主态：每条报文必有一个 ==========
    RECEIVED("RECEIVED"),
    PENDING_CONFIRM("PENDING_CONFIRM"),
    LANDED("LANDED"),
    REJECTED("REJECTED"),

    // ========== 重投子态（仅在重投路径上出现） ==========
    PENDING_RETRY("PENDING_RETRY"),
    RETRYING("RETRYING"),
    RETRY_CONFIRMED("RETRY_CONFIRMED"),
    RETRY_PERMANENT_FAILED("RETRY_PERMANENT_FAILED");

    /** 写入数据库 message_ledger.state 的字符串；与 V1 schema 的 VARCHAR(32) 对齐。 */
    private final String dbValue;

    MessageState(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    /**
     * 按数据库值反查枚举；非法值抛 {@link IllegalArgumentException}。
     * <p>这是 {@link ledger.JdbcLedgerStore#transition} 读取历史 state 时唯一允许的入口，
     * 防止脏字符串（错别字、旧版本残留）绕过状态机直接落库。
     */
    public static MessageState fromDbValue(String value) {
        if (value == null) {
            throw new IllegalArgumentException("state 不得为 null");
        }
        for (MessageState s : values()) {
            if (s.dbValue.equals(value)) {
                return s;
            }
        }
        throw new IllegalArgumentException("未知 state: " + value);
    }

    /**
     * 是否为「终态」—— 一旦进入，业务上不再允许自动迁移。
     *
     * <p>当前实现：只有 {@link #RETRY_CONFIRMED} 是重投成功后的瞬时状态，会立即推进到
     * {@link #LANDED}；{@link #REJECTED} 与 {@link #LANDED} 都不是终态（前者可被
     * T17 的 {@code RevertService} 撤回重审，后者也可被撤回），但<b>不再自动</b>迁移。
     */
    public boolean isTerminal() {
        return this == REJECTED || this == LANDED;
    }
}
