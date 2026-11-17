package cn.hospital.region.archive.hl7;

/**
 * MSH-18 字符集声明不在已知映射中（未声明、空值走默认 UTF-8 不抛此异常）。
 *
 * <p>异常消息固定含 MSH-18 原值，便于对账定位对端实现差异。
 */
public final class UnsupportedCharset extends RuntimeException {

    private final String declaredValue;

    public UnsupportedCharset(String message, String declaredValue) {
        super(message);
        this.declaredValue = declaredValue;
    }

    /** MSH-18 字段原值（含大小写与前后空格，**未归一**）。 */
    public String declaredValue() {
        return declaredValue;
    }
}
