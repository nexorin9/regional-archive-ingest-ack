package cn.hospital.region.archive.redelivery;

import java.util.Objects;

/**
 * 一次重投执行的结论 —— {@link RedeliveryAction#perform} 的返回类型。
 *
 * <p>与 {@link RedeliveryStatus} 不同：{@code RedeliveryStatus} 描述的是「队列条目本身的
 * 状态」，{@code RedeliveryResult} 描述的是「这次重投动作的结论」。前者落在 schema 的
 * {@code status} 列里供所有 SQL 查询；后者只在调度器这一层流转。
 *
 * <h2>三态</h2>
 * <ul>
 *   <li>{@link #ok} —— 对端已受理（AA）。下一步：调度器把条目
 *       {@code CONSUMED → ACK_CONFIRMED}；调用方的 {@code Hooks.onSuccess} 走
 *       {@code RETRYING → RETRY_CONFIRMED → LANDED}。</li>
 *   <li>{@link #retryableFailure} —— 上游请求级别失败（网络抖动、对端 5xx、超时等）。调度器
 *       先看 {@link RedeliveryScheduler} 的 {@code MAX_ATTEMPTS} 是否还有余地：有就走指数退避，
 *       没有就转 {@code PERMANENT_FAILED}。</li>
 *   <li>{@link #permanentFailure} —— 确定性失败（4xx 报文级别错误、本端判定字符集/版本/身份不兼容）。
 *       调度器跳过退避直接转 {@code PERMANENT_FAILED}。</li>
 * </ul>
 *
 * <h2>{@code errorCode} 与 {@code lastError} 的差别</h2>
 * <p>{@code errorCode} 是机器可分类的短码（如 {@code UPSTREAM_500}、{@code READ_TIMEOUT}）；
 * {@code lastError} 是人可读的描述。两者的字段约束不一样：
 * <ul>
 *   <li>{@code errorCode}：空串不允许；非空且不含空白；写入数据库前会原样落进
 *       {@code redelivery_queue.last_error}，固定 ASCII、与 csv/data 层同口径。</li>
 *   <li>{@code lastError}：可空（无内容描述时允许空串），最多 256 字符（与 V5 schema
 *       {@code last_error VARCHAR(256)} 严格对齐）。</li>
 * </ul>
 */
public record RedeliveryResult(boolean success, boolean retryable,
                               String errorCode, String lastError) {

    public RedeliveryResult {
        Objects.requireNonNull(errorCode, "errorCode 不得为 null；空串表示无错");
        if (!success && errorCode.isEmpty()) {
            throw new IllegalArgumentException("失败结果必须有 errorCode");
        }
        if (success && retryable) {
            throw new IllegalArgumentException("成功的重投不可标记为可重试");
        }
        Objects.requireNonNull(lastError, "lastError 不得为 null；无描述时传空串");
        if (lastError.length() > 256) {
            throw new IllegalArgumentException("lastError 不得超过 256 字符（与 V5 schema 对齐）");
        }
    }

    /** 重投成功 —— 对端回 AA。{@code errorCode} 恒为 {@code ""}。 */
    public static RedeliveryResult ok(String lastError) {
        return new RedeliveryResult(true, false, "", safe(lastError));
    }

    /** 重试可能成功的失败（如网络抖动、对端 5xx、超时）。 */
    public static RedeliveryResult retryableFailure(String errorCode, String lastError) {
        requireCode(errorCode);
        return new RedeliveryResult(false, true, errorCode, safe(lastError));
    }

    /** 不可重试的确定性失败（如对端 4xx 报文级错误）。 */
    public static RedeliveryResult permanentFailure(String errorCode, String lastError) {
        requireCode(errorCode);
        return new RedeliveryResult(false, false, errorCode, safe(lastError));
    }

    private static void requireCode(String code) {
        if (code == null || code.isEmpty()) {
            throw new IllegalArgumentException("失败结果必须有 errorCode");
        }
        for (int i = 0; i < code.length(); i++) {
            char ch = code.charAt(i);
            if (Character.isWhitespace(ch)) {
                throw new IllegalArgumentException("errorCode 不得含空白字符: \"" + code + "\"");
            }
        }
    }

    private static String safe(String s) {
        if (s == null) return "";
        if (s.length() > 256) {
            return s.substring(0, 256);
        }
        return s;
    }
}
