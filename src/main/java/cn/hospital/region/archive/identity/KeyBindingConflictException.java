package cn.hospital.region.archive.identity;

/**
 * 双主键绑定被拒 —— 行不存在、当前状态不允许绑定，或已有值与新值不同。
 *
 * <p>本异常一律<b>不改任何数据</b>：拒收的原因是「静默覆盖比报错更坏」。
 * 典型场景是同一台账行先后被两条路径绑定：先由入账路径写了区域主键，
 * 随后确认路径带着同一个区域主键但<b>另一个</b>院内主键来了。
 * 若直接覆盖，院内主索引与台账就永久对不上，而这两处都不是发现得了的地方。
 * 正确的表现是当场抛错、让人去查是哪条路径写错了。
 */
public class KeyBindingConflictException extends Exception {

    private final long ledgerId;
    private final String reason;

    public KeyBindingConflictException(long ledgerId, String reason) {
        super("台账行 " + ledgerId + " 的双主键绑定被拒：" + reason);
        this.ledgerId = ledgerId;
        this.reason = reason;
    }

    public long ledgerId() {
        return ledgerId;
    }

    /** 拒绝原因（不含患者可识别信息）。 */
    public String reason() {
        return reason;
    }
}