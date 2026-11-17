package cn.hospital.region.archive.http;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 路由内部提前退出用的异常 —— 携带 {@link ApiErrorCode} 与可选补充明细。
 *
 * <p>为什么用异常而不是层层返回 {@code ApiResponse}：五个端点里，「缺字段」「令牌不对」
 * 「这行不在这批里」这三类判断散落在四五层深的地方（解析体 → 查批次 → 调确认门），
 * 用返回值就得让每层都记得「我拿到的是不是错误」，漏一层就把 500 当成功发出去。
 * 异常让「出错」成为不需要维护的默认路径：{@link Routes} 只在最外层接一次，
 * 中间任何一层发现不对就抛，响应体由 {@link ApiResponse#error} 统一渲染。
 *
 * <p><b>不带堆栈</b>（{@code super(message, null, false, false)}）：这四类都是
 * 调用方能自己改对的入参错误，不是本端缺陷，留堆栈只会让 500 的日志被噪声淹没。
 */
public final class ApiException extends RuntimeException {

    private final ApiErrorCode code;
    private final Map<String, Object> detail;

    /**
     * @param code    错误码；状态码由它决定
     * @param message 面向调用方的中文说明（不得含患者可识别明文）
     * @param detail  可选补充明细，会原样进响应体
     */
    public ApiException(ApiErrorCode code, String message, Map<String, Object> detail) {
        super(message == null || message.isBlank() ? Objects.requireNonNull(code).zhSummary() : message,
                null, false, false);
        this.code = Objects.requireNonNull(code, "错误码不得为 null");
        this.detail = detail == null || detail.isEmpty() ? null : new LinkedHashMap<>(detail);
    }

    public ApiException(ApiErrorCode code, String message) {
        this(code, message, null);
    }

    public ApiErrorCode code() {
        return code;
    }

    /** 补充明细；无则 {@code null}。 */
    public Map<String, Object> detail() {
        return detail;
    }

    /** 补一条明细后抛出（链式拼装用）。 */
    public ApiException with(String key, Object value) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (detail != null) {
            merged.putAll(detail);
        }
        merged.put(key, value);
        return new ApiException(code, getMessage(), merged);
    }

    // ---- 常用快捷抛出 ----

    /** 缺必填字段；{@code field} 会被点名写进 message 与 detail。 */
    public static ApiException missingField(String field, String why) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("field", field);
        return new ApiException(ApiErrorCode.MISSING_FIELD,
                "请求缺少必填字段 " + field + (why == null || why.isBlank() ? "" : "：" + why), detail);
    }

    /** 参数取值非法。 */
    public static ApiException invalidParameter(String name, String value, String why) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("parameter", name);
        detail.put("value", value);
        return new ApiException(ApiErrorCode.INVALID_PARAMETER,
                "参数 " + name + " 取值非法" + (why == null || why.isBlank() ? "" : "：" + why), detail);
    }
}