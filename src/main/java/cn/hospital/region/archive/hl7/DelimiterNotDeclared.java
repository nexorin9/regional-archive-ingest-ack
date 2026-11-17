package cn.hospital.region.archive.hl7;

/**
 * MSH 自述分隔符缺失或非 header 段时抛此异常。
 *
 * <p>异常消息必须保留输入行前 32 字节原值（不落患者明文，落 segment + rawSnippet），
 * 供 T7 的 FieldLocator 复用同一口径。
 */
public class DelimiterNotDeclared extends RuntimeException {
    public DelimiterNotDeclared(String message) {
        super(message);
    }
}
