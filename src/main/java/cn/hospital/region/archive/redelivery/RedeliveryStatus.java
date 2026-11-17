package cn.hospital.region.archive.redelivery;

/**
 * 重投队列条目状态 —— 调度器对一条报文的「这次重投尝试」而非「这条报文的状态」。
 *
 * <p><b>为什么不直接复用 {@code MessageState}</b>：
 * 台账状态机记的是「这条报文在产品中的业务阶段」（四态 + 重投四态），
 * 队列状态机记的是「调度器当前拿这条条目在做什么」（入队 / 持锁 / 已结算）。
 * 同样的 QUEUED 在台账是 {@code PENDING_RETRY}，队列里是「等调度」，两条责任混在一列上，
 * 迁移的时候会出现「到底是写哪个」的二义。故另立枚举，dbValue 与 V5 schema 的 VARCHAR(32) 一一对应。
 *
 * <p><b>状态迁移</b>（写库前必须在应用层校验合法边）：
 * <ul>
 *   <li>{@link #QUEUED} → {@link #CONSUMED}（调度器出队时；原子 UPDATE）</li>
 *   <li>{@link #CONSUMED} → {@link #ACK_CONFIRMED}（重投成功，对端回 AA）</li>
 *   <li>{@link #CONSUMED} → {@link #PERMANENT_FAILED}（达到 MAX_ATTEMPTS）</li>
 *   <li>{@link #PERMANENT_FAILED} → {@link #QUEUED}（人工放行）</li>
 * </ul>
 */
public enum RedeliveryStatus {

    /** 已入队、待调度器出队。{@code next_attempt_at_ms <= now()} 才被捞起。 */
    QUEUED("QUEUED"),

    /** 调度器原子 UPDATE 出来持有；正在向对端重投。 */
    CONSUMED("CONSUMED"),

    /** 重投成功、对端已回 AA。下一步由应用层推进台账到 {@code RETRY_CONFIRMED}。 */
    ACK_CONFIRMED("ACK_CONFIRMED"),

    /** 达到 {@code MAX_ATTEMPTS} 上限，停止自动重试。只可由人工放行回 {@link #QUEUED}。 */
    PERMANENT_FAILED("PERMANENT_FAILED");

    /** 写入 redelivery_queue.status 的字符串；与 V5 schema 对齐。 */
    private final String dbValue;

    RedeliveryStatus(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    /** 按数据库字符串反查枚举；非法值抛 {@link IllegalArgumentException}。 */
    public static RedeliveryStatus fromDbValue(String value) {
        if (value == null) {
            throw new IllegalArgumentException("status 不得为 null");
        }
        for (RedeliveryStatus s : values()) {
            if (s.dbValue.equals(value)) {
                return s;
            }
        }
        throw new IllegalArgumentException("未知 status: " + value);
    }

    /** 终态：不再被自动出队；人工放行接口可使其回到 {@link #QUEUED}。 */
    public boolean terminal() {
        return this == ACK_CONFIRMED || this == PERMANENT_FAILED;
    }
}
