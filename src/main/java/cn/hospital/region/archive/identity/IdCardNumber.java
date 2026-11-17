package cn.hospital.region.archive.identity;

import cn.hospital.region.archive.ledger.LedgerRedaction;
import cn.hospital.region.archive.model.IdentityText;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * 一个身份证号的<b>归一形态</b> —— 「能不能当硬锚点」与「归一成了什么」两件事的答案。
 *
 * <h2>为什么归一要单列一个类型</h2>
 * <p>同一个居民，平台侧与院内侧的证件号在真实报文里有这些写法差异，
 * 它们逐字都不相等，但指的是同一个人：
 * <ul>
 *   <li>前后带空格、字段里带不可见空白（{@code " 11010519491231002X "}）；</li>
 *   <li>校验位写成小写 {@code x}（{@code ...02x}）；</li>
 *   <li>15 位老号与 18 位新号并存（{@code 110105491231002} 与 {@code 11010519491231002X}
 *       —— 老号没有校验位，且年份只有两位，按 GB 11643 在第 7 位补 {@code 19} 即为同一人）。</li>
 * </ul>
 * 若不先归一再比，这三条都会判成「证件不一致」→ 拒收 → <b>把格式差异当成身份冲突</b>。
 * 而拒收是对端报文不再重投、院内也不建档的终局结论，误判的代价由居民承担。
 * 所以归一是硬校验的一部分，不是可选的清洗步骤。
 *
 * <h2>明文不进日志、不进台账</h2>
 * <p>本类<b>持有</b>归一后的明文（否则没法算校验位），但明文出口只有
 * <b>包级私有</b>的 {@link #canonical()}：台账与待确认清单只拿
 * {@link LedgerRedaction#digest8} 的 8 位摘要。{@link #toString()} 永远只打摘要与格式。
 *
 * <h2>比较用全量摘要，落库用 8 位摘要</h2>
 * <p>{@link #fullDigest()} 是 64 位 SHA-256，{@link #sameAs} 比它 ——
 * 8 位摘要只有 32 bit，拿它判「是不是同一个人」在长期运行下迟早撞上，
 * 而撞上的后果是<b>把两个不同的人判成同一人并合档</b>。8 位摘要只用于落库与人工肉眼对照。
 *
 * <h2>校验位不通过 = 不可用，而不是冲突</h2>
 * <p>18 位号码末位是 ISO 7064 MOD 11-2 校验位，由前 17 位算出。算不过有三种成因：
 * 平台录错、院内录错、或这个号本来就不是证件号。三者都不能推出「不是同一个人」，
 * 因此本类把它归为 {@link Format#UNUSABLE} —— 不作硬锚点、只出候选，
 * 依据写进 {@link #zhBasis()} 供人工看一眼。判成冲突则会拒收一个大概率只是录错的居民。
 */
public final class IdCardNumber {

    /** 归一形态。四种，不是两种：「格式不可用」与「没有」在下游处置上相同，依据不同，必须分开记。 */
    public enum Format {

        /** 平台或院内压根没给证件号。 */
        ABSENT("未提供"),

        /** 15 位老号已按 GB 11643 升位为 18 位，可用。 */
        UPGRADED_FROM_15("15 位升位为 18 位"),

        /** 本身就是合规的 18 位号，可用。 */
        USABLE_18("18 位"),

        /** 给了但不是合规证件号（长度不对 / 含非法字符 / 校验位不过），不可作锚点。 */
        UNUSABLE("格式不可用");

        private final String zhLabel;

        Format(String zhLabel) {
            this.zhLabel = zhLabel;
        }

        public String zhLabel() {
            return zhLabel;
        }
    }

    /** ISO 7064 MOD 11-2 权重：GB 11643-1999 附录 A 的 17 位加权因子。 */
    private static final int[] WEIGHTS = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};

    /** 校验位映射：余数 0→'1'、1→'0'、2→'X'、3→'9' … 10→'2'。 */
    private static final String CHECK_CHARS = "10X98765432";

    /** 15 位升位时补的世纪前缀：15 位号只覆盖 1900–1999 年出生的人（GB 11643-1999）。 */
    private static final String CENTURY_PREFIX = "19";

    private final Format format;
    private final String canonical;
    private final String fullDigest;
    private final String digest8;
    private final String reason;

    private IdCardNumber(Format format, String canonical, String reason) {
        this.format = format;
        this.canonical = canonical;
        this.reason = reason;
        this.fullDigest = canonical.isEmpty() ? "" : sha256Hex(canonical);
        this.digest8 = LedgerRedaction.digest8(canonical);
    }

    /** 「压根没有证件号」。两侧都缺时本产品走候选不合档，不作冲突。 */
    public static IdCardNumber absent() {
        return new IdCardNumber(Format.ABSENT, "", "未提供证件号");
    }

    /**
     * 解析一个原始证件号：去空白 → 大写 → 按 15/18 位规则判定 → 校验位。
     *
     * <p>入参为 {@code null}、空串、全空白一律得到 {@link Format#ABSENT}（不是 UNUSABLE）：
     * 「没填」与「填错了」在处置上同属「不作锚点」，但依据要能在确认台上分清。
     */
    public static IdCardNumber parse(String raw) {
        String cleaned = IdentityText.stripSpaces(raw).toUpperCase(Locale.ROOT);
        if (cleaned.isEmpty()) {
            return absent();
        }
        if (cleaned.length() == 15) {
            return parse15(cleaned);
        }
        if (cleaned.length() == 18) {
            return parse18(cleaned);
        }
        return new IdCardNumber(Format.UNUSABLE, "",
                "证件号长度 " + cleaned.length() + " 位（只接受 15 位或 18 位）");
    }

    /** 15 位：全数字且升位后自洽才算可用 —— 升位是确定性的，因此校验位必然通过。 */
    private static IdCardNumber parse15(String cleaned) {
        if (!allDigits(cleaned, 0, cleaned.length())) {
            return new IdCardNumber(Format.UNUSABLE, "", "15 位证件号含非数字字符");
        }
        // 6 位行政区划 + 2 位年份 + 6 位月日 + 3 位顺序码 = 15 位；
        // 在年份前补世纪位 19，得到 17 位，再按 GB 11643 算校验位。
        String body17 = cleaned.substring(0, 6) + CENTURY_PREFIX + cleaned.substring(6);
        String canonical = body17 + CHECK_CHARS.charAt(checkDigit(body17));
        return new IdCardNumber(Format.UPGRADED_FROM_15, canonical,
                "15 位证件号按 GB 11643 升位为 18 位（第 7 位补 " + CENTURY_PREFIX + "）");
    }

    /** 18 位：前 17 位必须全数字，末位为数字或 X，且校验位必须自洽。 */
    private static IdCardNumber parse18(String cleaned) {
        if (!allDigits(cleaned, 0, 17)) {
            return new IdCardNumber(Format.UNUSABLE, "", "18 位证件号前 17 位含非数字字符");
        }
        char last = cleaned.charAt(17);
        if (!Character.isDigit(last) && last != 'X') {
            return new IdCardNumber(Format.UNUSABLE, "", "18 位证件号校验位既非数字也非 X");
        }
        String expected = String.valueOf(CHECK_CHARS.charAt(checkDigit(cleaned.substring(0, 17))));
        if (!expected.equals(String.valueOf(last))) {
            // 依据里只说「算出来的校验位与原值不一致」，不打印原值与算出的值 ——
            // 这两样拼起来就是完整证件号，进了日志就是明文外泄。
            return new IdCardNumber(Format.UNUSABLE, "", "18 位证件号校验位自校验不通过");
        }
        return new IdCardNumber(Format.USABLE_18, cleaned, "18 位证件号校验位自校验通过");
    }

    /** 是否可作硬锚点：只有归一成功且校验位自洽的号码才为 true。 */
    public boolean usable() {
        return format == Format.USABLE_18 || format == Format.UPGRADED_FROM_15;
    }

    /** 归一形态。 */
    public Format format() {
        return format;
    }

    /**
     * 8 位摘要（SHA-256 前 8 位十六进制）—— 台账、待确认清单、日志里出现的形态。
     *
     * <p>空串表示没有可用号码。
     */
    public String digest8() {
        return digest8;
    }

    /**
     * 全量摘要（64 位十六进制）—— <b>只在进程内做同一性比较</b>，不落盘、不导出。
     *
     * <p>与 {@link #digest8()} 的分工：比较要绝对可靠（32 bit 会撞），
     * 而落库要人眼能对（64 位没法扫）。两者刻意分开。
     */
    public String fullDigest() {
        return fullDigest;
    }

    /**
     * 归一后的证件号明文 —— <b>包级私有</b>。
     *
     * <p>只有本包（硬校验与存储实现）能取到明文。跨包要明文就是缺陷：
     * 让它在本包外写不出编译错误，比靠 review 提醒「别打印明文」可靠。
     */
    String canonical() {
        return canonical;
    }

    /** 一行中文依据：格式、做了什么归一、能不能作锚点。<b>不含明文</b>。 */
    public String zhBasis() {
        return format.zhLabel() + "：" + reason;
    }

    /**
     * 是否同一个证件号。
     *
     * <p>任一侧不可用时返回 {@code false} —— 但<b>调用方不得据此判冲突</b>：
     * 「没法比」不是「不一样」。冲突判定在 {@link IdentityHardRules} 里先看
     * {@link #usable()} 再看本方法的值，顺序反了会把「平台没给证件」误判成冲突拒收。
     */
    public boolean sameAs(IdCardNumber other) {
        return other != null && usable() && other.usable() && fullDigest.equals(other.fullDigest);
    }

    @Override
    public String toString() {
        return "IdCardNumber(" + format.zhLabel()
                + ", 摘要=" + (digest8.isEmpty() ? "(无)" : digest8)
                + ", 可作锚点=" + usable() + ")";
    }

    // ------------------------------------------------------------------ 内部

    private static boolean allDigits(String s, int from, int to) {
        for (int i = from; i < to; i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }

    /** ISO 7064 MOD 11-2：加权求和后对 11 取余，余数映射到 {@link #CHECK_CHARS}。 */
    private static int checkDigit(String first17) {
        int sum = 0;
        for (int i = 0; i < first17.length(); i++) {
            sum += (first17.charAt(i) - '0') * WEIGHTS[i];
        }
        return sum % 11;
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] out = md.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(out.length * 2);
            for (byte b : out) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 强制实现；到这里说明运行环境异常。
            // 证件比对绝不能降级 —— 降级会让「判不出冲突」变成「都判一致」。
            throw new IllegalStateException("运行环境缺少 SHA-256，拒绝在身份硬校验里降级", e);
        }
    }
}