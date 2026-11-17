package cn.hospital.region.archive.hl7;

/**
 * 组件下标越界。
 */
public class ComponentIndexOutOfBounds extends RuntimeException {
    private final String segmentName;
    private final int fieldIndex;
    private final int componentIndex;
    private final int componentCount;

    public ComponentIndexOutOfBounds(String segmentName, int fieldIndex, int componentIndex, int componentCount) {
        super("段 " + segmentName + " 字段 " + fieldIndex + " 的组件下标越界: compIdx=" + componentIndex
                + "，共有 " + componentCount + " 组件");
        this.segmentName = segmentName;
        this.fieldIndex = fieldIndex;
        this.componentIndex = componentIndex;
        this.componentCount = componentCount;
    }

    public String segmentName() { return segmentName; }
    public int fieldIndex() { return fieldIndex; }
    public int componentIndex() { return componentIndex; }
    public int componentCount() { return componentCount; }
}
