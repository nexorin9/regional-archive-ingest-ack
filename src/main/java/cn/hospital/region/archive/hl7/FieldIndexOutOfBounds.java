package cn.hospital.region.archive.hl7;

/**
 * 段存在但字段下标越界（idx ≤ 0 或 idx &gt; segment.fieldCount()）。
 * T7 FieldLocator 直接复用此异常类型作为 recommendedCode 候选。
 */
public class FieldIndexOutOfBounds extends RuntimeException {
    private final String segmentName;
    private final int index;
    private final int fieldCount;

    public FieldIndexOutOfBounds(String segmentName, int index, int fieldCount) {
        super("段 " + segmentName + " 字段下标越界: idx=" + index + "，段共 " + fieldCount + " 字段");
        this.segmentName = segmentName;
        this.index = index;
        this.fieldCount = fieldCount;
    }

    public String segmentName() { return segmentName; }
    public int index() { return index; }
    public int fieldCount() { return fieldCount; }
}
