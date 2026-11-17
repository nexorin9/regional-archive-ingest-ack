package cn.hospital.region.archive.hl7;

/**
 * 子组件下标越界。
 */
public class SubcomponentIndexOutOfBounds extends RuntimeException {
    private final String segmentName;
    private final int fieldIndex;
    private final int componentIndex;
    private final int subcomponentIndex;
    private final int subcomponentCount;

    public SubcomponentIndexOutOfBounds(String segmentName, int fieldIndex, int componentIndex,
                                        int subcomponentIndex, int subcomponentCount) {
        super("段 " + segmentName + " 字段 " + fieldIndex + " 组件 " + componentIndex + " 的子组件下标越界: subIdx="
                + subcomponentIndex + "，共有 " + subcomponentCount + " 子组件");
        this.segmentName = segmentName;
        this.fieldIndex = fieldIndex;
        this.componentIndex = componentIndex;
        this.subcomponentIndex = subcomponentIndex;
        this.subcomponentCount = subcomponentCount;
    }

    public String segmentName() { return segmentName; }
    public int fieldIndex() { return fieldIndex; }
    public int componentIndex() { return componentIndex; }
    public int subcomponentIndex() { return subcomponentIndex; }
    public int subcomponentCount() { return subcomponentCount; }
}
