package cn.hospital.region.archive.hl7;

/**
 * 通用 HL7 解析期异常，附带原始行序号与前 32 字节片段供 FieldLocator 复用。
 */
public class Hl7ParseException extends RuntimeException {
    private final int segmentOrdinal;
    private final String rawSnippet;

    public Hl7ParseException(String message, int segmentOrdinal, String rawSnippet) {
        super(message);
        this.segmentOrdinal = segmentOrdinal;
        this.rawSnippet = rawSnippet;
    }

    public int segmentOrdinal() { return segmentOrdinal; }

    public String rawSnippet() { return rawSnippet; }
}
