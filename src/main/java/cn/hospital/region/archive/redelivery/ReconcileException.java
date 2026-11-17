package cn.hospital.region.archive.redelivery;

/**
 * 对账失败 —— 取对端回执列表没跑成。
 *
 * <p>声明为 <b>受检异常</b>：调用方不能顺手 catch 掉继续跑。差集的唯一输入是「对端回执列表」，
 * 拿不到它就没有差集 —— 让它以 unchecked 的形式溜过去，等于给「静默清空重投清单」留了后门。
 *
 * <p>本异常<b>不携带响应体</b>：区域平台的错误响应里常带内部路径与机构信息，
 * 状态码与 {@link #code()} 已经够定位。
 */
public final class ReconcileException extends Exception {

    private static final long serialVersionUID = 1L;

    private final ReconcileErrorCode code;
    private final Integer httpStatus;

    public ReconcileException(ReconcileErrorCode code, String message,
                              Integer httpStatus, Throwable cause) {
        super(message, cause);
        if (code == null) {
            throw new IllegalArgumentException("ReconcileErrorCode 不得为 null");
        }
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public ReconcileErrorCode code() {
        return code;
    }

    /** HTTP 状态码；传输层失败为 {@code null}。 */
    public Integer httpStatus() {
        return httpStatus;
    }
}