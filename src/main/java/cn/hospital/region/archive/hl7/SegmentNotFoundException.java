package cn.hospital.region.archive.hl7;

/**
 * 段名或段序号未命中时抛此异常（T7 FieldLocator 复用同一错误族）。
 */
public class SegmentNotFoundException extends RuntimeException {
    public SegmentNotFoundException(String message) {
        super(message);
    }
}
