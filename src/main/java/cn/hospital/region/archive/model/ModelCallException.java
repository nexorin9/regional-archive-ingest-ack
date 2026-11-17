package cn.hospital.region.archive.model;

import java.util.Objects;

/**
 * 模型调用失败信号。
 *
 * <p><b>抛异常而不是返回失败标记</b>：返回 {@code false}/{@code null} 的写法会被
 * {@code if (!ok) return;} 悄悄吞掉，而抛异常一定打断调用链。
 * 上层（{@link ResilientModelGateway} 的单一降级路径）接住它之后<b>不得推进任何业务状态</b>：
 * 台账留在待确认、报文原文与幂等键保留，等重试或人工。
 *
 * <h2>错误码是必填项，不是可选项</h2>
 * <p>五类错误（连接超时 / 读超时 / 非 2xx / 不可解析 / 结构校验失败）各自的处置不同：
 * 前两类值得按预算重试，后三类重试是浪费。因此本类的每个构造器都<b>强制携带</b>
 * 一个 {@link ModelErrorCode}——新增抛点时必须先回答「这是哪一类、这一类要不要重试」，
 * 答不上来就<b>编译不过</b>。
 *
 * <p>反过来看这为什么重要：一个没有错误码的模型异常传到降级路径，
 * 重试策略只能对它一律不重试（保守但慢）或一律重试（快但可能白等），
 * 两种都是「没验证过的猜测」。这里把它变成一道编译期必须回答的问题。
 */
public class ModelCallException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** HTTP 状态码；网络类失败（连不上、超时）没有状态码，此处为 {@code null}。 */
    private final Integer httpStatus;

    /** 五类错误之一；本类不允许为空。 */
    private final ModelErrorCode errorCode;

    public ModelCallException(ModelErrorCode errorCode, String message) {
        this(errorCode, message, null, null);
    }

    public ModelCallException(ModelErrorCode errorCode, String message, Throwable cause) {
        this(errorCode, message, null, cause);
    }

    public ModelCallException(ModelErrorCode errorCode, String message, Integer httpStatus, Throwable cause) {
        super(message, cause);
        this.errorCode = Objects.requireNonNull(errorCode, "错误码不得为 null：五类错误之外没有第六种失败");
        this.httpStatus = httpStatus;
    }

    /** 五类错误码之一；重试与降级处置全按它决定。 */
    public ModelErrorCode errorCode() {
        return errorCode;
    }

    /** HTTP 状态码；非 {@link ModelErrorCode#NON_2XX} 的失败没有状态码，返回空。 */
    public java.util.Optional<Integer> httpStatus() {
        return java.util.Optional.ofNullable(httpStatus);
    }
}