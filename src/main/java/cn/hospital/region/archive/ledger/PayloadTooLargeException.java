package cn.hospital.region.archive.ledger;

/**
 * 入站报文超过数据库硬上限、连截断兜底都不适用时抛出。
 *
 * <h2>为什么是 unchecked 而不是 {@link java.sql.SQLException}</h2>
 * <p>这不是「数据库连不上」「事务冲突」那类基础设施故障，而是<b>对端送来的报文本身不合规</b>
 * ——与 {@link LedgerEntry} 拒绝空 batchId 一样属于入参校验。把它包装成
 * {@code SQLException} 的坏处是：将来接入主路径里一句宽泛的 {@code catch (SQLException e)}
 * 就会把它当成数据库抖动默默重试，而报文大到入库不了这件事重试一万次也不会变小。
 * 保持 unchecked 并单列一个类型，就是逼调用方明确决定「这条怎么回执」。
 *
 * <p>调用方应当：记一条 WARNING 并回 {@code MSA-1 = AR} + ERR 段（容量类错误码，AR 不进重投集合），
 * 让对端知道这条根本没进来，而不是让连接上空转等超时。
 */
public class PayloadTooLargeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int originalLength;
    private final int hardCap;

    public PayloadTooLargeException(String message, int originalLength, int hardCap) {
        super(message);
        this.originalLength = originalLength;
        this.hardCap = hardCap;
    }

    /** 入站报文的实际字符数（未截断前的长度）。 */
    public int originalLength() {
        return originalLength;
    }

    /** 允许入账的硬上限字符数，即 {@link RawPayloadPolicy#MAX_ACCEPTED_CHARS}。 */
    public int hardCap() {
        return hardCap;
    }
}