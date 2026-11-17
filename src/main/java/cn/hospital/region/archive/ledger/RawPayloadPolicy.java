package cn.hospital.region.archive.ledger;

import java.util.Objects;

/**
 * {@code message_ledger.raw_payload} 的容量策略 —— 台账能存多长的原始报文。
 *
 * <h2>为什么台账要有容量策略</h2>
 * <p>V1 schema 把 {@code raw_payload} 定成 {@code VARCHAR(1048576)}（1MB）。这个宽度不是随手写的：
 * 它是本产品对「一条健康档案变更报文」的容量承诺 —— 几百 KB 的完整档案（既往史、随访记录、
 * 慢病任务包）放得下，而对端把一个附件甚至整段转录文本塞进来时放不下。
 *
 * <p>真正的风险在于<b>超长报文撞上列宽时会怎样</b>：PostgreSQL 与 H2 都会直接抛
 * 「value too long for column」，事务回滚，调用方只看到一个 {@code SQLException}。
 * 那条报文于是既没入账、也没回执，对端第二天追问时只能整批重发 —— 一条坏报文把整批拖下水。
 * 所以截断必须发生在应用层、发生在入库之前，并且<b>留痕</b>：截掉了多少、要重投去哪里取，
 * 排障的人必须一眼能看出来这行是完整的。
 *
 * <h2>两道闸</h2>
 * <ol>
 *   <li><b>截断</b>（1MB ≤ 长度 ≤ 4MB）：保留头部 + 一行中文截断说明，入库成功并记 WARNING。
 *       报文头 MSH 在最前面，头部必然完整保留，因此幂等键、四态回执、T7 的段字段定位都不受影响；
 *       丢的只是尾部正文，重投时回源重取即可。</li>
 *   <li><b>拒收</b>（长度 &gt; 4MB）：截断到这个份上意味着报文本身不合规（多半是错发的文件流），
 *       存一行半截数据只会污染排障。抛 {@link PayloadTooLargeException}，让调用方回 AR。</li>
 * </ol>
 *
 * <h2>按字符而不是按字节</h2>
 * <p>{@code VARCHAR(N)} 在两库下都按<b>字符</b>计数，而本轮最重的一类报文是 GB18030 中文档案，
 * 一个汉字一个字符（{@code String.length()} 恰好也是 1）。按字节算会在中文报文上偏严一倍，
 * 明明只超了 300KB 的报文会被拒掉 —— 这种阈值与业务直觉不符的策略，运维查起来最费时间。
 *
 * <h2>截断点不许切开代理对</h2>
 * <p>{@code String.substring} 按 UTF-16 码元切，落在代理对中间会留下半个代理字符，
 * 从库里读出来就是一个无法编码的 {@code U+FFFD}，比截断本身更难查。本类在切之前把切点回退一格。
 */
public final class RawPayloadPolicy {

    /** 入库保留上限，与 V1 的 {@code VARCHAR(1048576)} 严格一致。 */
    public static final int MAX_STORED_CHARS = 1_048_576;

    /** 硬上限：超过这个长度视为报文本身不合规，拒收而不是截断。 */
    public static final int MAX_ACCEPTED_CHARS = 4_194_304;

    /** 截断说明的中文前缀；用 {@code \n} 而不是 HL7 段分隔符 {@code \r}，避免伪造出一个段。 */
    private static final String NOTE_PREFIX = "\n[台账截断] 原始报文共 ";

    /**
     * 说明写不下时的最小标记。
     *
     * <p>存在的原因不是「兜底美观」：说明本身带原始长度，正常只有几十字符，
     * 但 {@link #apply} 允许调用方把入库上限压到几十字符（测试即如此）。
     * 那时若不收缩说明，产出的字符串会比上限还长 —— 于是
     * 「入库长度 ≤ 上限」这条不变量在最需要它的小上限场景下反而破了，
     * 列宽保护也跟着失效。
     */
    private static final String SHORT_NOTE = "\n[台账截断]";

    private RawPayloadPolicy() {
    }

    /**
     * 判定一条原始报文能否入账，并给出实际入库的字符串。
     *
     * @param rawPayload 原始报文（{@link LedgerEntry} 已保证非 null，空报文用空串）
     * @return 处理结论；{@link Result#truncated()} 为 true 表示发生了截断
     * @throws PayloadTooLargeException 超过 {@link #MAX_ACCEPTED_CHARS}
     */
    public static Result apply(String rawPayload) {
        return apply(rawPayload, MAX_STORED_CHARS, MAX_ACCEPTED_CHARS);
    }

    /**
     * 带自定义上下限的判定入口 —— 单元测试用它把阈值压到几十字符，
     * 不必为了验证截断逻辑先造 1MB 字符串。
     *
     * @param storedCap 入库保留上限（字符数）
     * @param hardCap   拒收硬上限（字符数）
     */
    public static Result apply(String rawPayload, int storedCap, int hardCap) {
        Objects.requireNonNull(rawPayload, "rawPayload 不得为 null");
        if (storedCap <= 0 || hardCap < storedCap) {
            throw new IllegalArgumentException("阈值非法: storedCap=" + storedCap + " hardCap=" + hardCap);
        }
        int originalLength = rawPayload.length();
        if (originalLength <= storedCap) {
            return new Result(rawPayload, false, originalLength, originalLength);
        }
        if (originalLength > hardCap) {
            throw new PayloadTooLargeException(
                    "原始报文 " + originalLength + " 字符，超过硬上限 " + hardCap
                            + " 字符，拒绝入账（截断到这个份上只会污染排障，请对端回源重取）",
                    originalLength, hardCap);
        }

        String note = NOTE_PREFIX + originalLength + " 字符，超出入库上限 " + storedCap
                + " 字符，仅保留报文头与前段正文；尾部未入库，需要该段请回源重取。";
        if (note.length() >= storedCap) {
            note = SHORT_NOTE.substring(0, Math.min(SHORT_NOTE.length(), storedCap));
        }
        int keep = cutPoint(rawPayload, storedCap - note.length());
        return new Result(rawPayload.substring(0, keep) + note, true, originalLength, keep + note.length());
    }

    /**
     * 算出合法的截断点：不切开代理对，且不超预算。
     *
     * @param budget 留给正文的字符数（可能为负 → 返回 0）
     * @return 正文应保留的前缀长度
     */
    private static int cutPoint(String rawPayload, int budget) {
        if (budget <= 0) {
            return 0;
        }
        int cut = Math.min(budget, rawPayload.length());
        // 切点落在高代理项之后说明最后保留的那个字符是代理对的前半截，回退一格即可
        if (cut > 0 && cut < rawPayload.length() && Character.isHighSurrogate(rawPayload.charAt(cut - 1))) {
            cut--;
        }
        return cut;
    }

    /**
     * 一次容量判定的结论。
     *
     * @param storedPayload  实际入库的字符串（截断时含尾部中文说明）
     * @param truncated      是否发生了截断
     * @param originalLength 原始字符数
     * @param storedLength   实际入库字符数
     */
    public record Result(String storedPayload, boolean truncated, int originalLength, int storedLength) {

        public Result {
            Objects.requireNonNull(storedPayload, "storedPayload 不得为 null");
        }
    }
}