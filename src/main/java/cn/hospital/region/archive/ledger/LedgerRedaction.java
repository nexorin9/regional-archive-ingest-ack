package cn.hospital.region.archive.ledger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Objects;

/**
 * 台账导出脱敏出口 —— 姓名、证件号、手机号一类可识别信息<b>唯一</b>的变形规则。
 *
 * <h2>为什么导出要脱敏，而台账本身不脱敏</h2>
 * <p>台账存报文原文是为了重投与排障——原文必须逐字可用，否则次日对账时
 * 「重投的那条和原来那条是不是同一条」就没法验了。但导出是另一回事：
 * CSV 会被贴进工作群、发进邮件、丢进共享盘，传播路径完全不受控。
 * 所以口径是<b>原文留在库里，出库的一律变形</b>。
 *
 * <h2>三种变形，各自解决什么问题</h2>
 * <ul>
 *   <li>{@link #digest8} —— 姓名的 SHA-256 前 8 位。<b>不可逆</b>，因此不可用于回查；
 *       但同一条报文重投多次导出时摘要不变，信息员能据此确认「这两行确实是同一个人、
 *       属于重复到达」而不必看见名字。这正是对账时唯一需要的性质。</li>
 *   <li>{@link #keyFragment} —— 主键片段：保留首 3 位与末 4 位，中间 {@code ***}。
 *       主键（PID-3 第一组件）本来就是业务标识不是身份标识，信息员要拿它去平台侧
 *       或 HIS 里定位那条记录，抹成哈希就没法干活了；保留首尾又足够让本人一眼认出、
 *       旁人无法枚举。</li>
 *   <li>{@link #assertNoPlaintext} —— 出口自检。脱敏出错时最典型的形态是
 *       「某一列忘了套 digest 就进了 CSV」，而这个形态在肉眼扫一遍时极难发现。
 *       于是把明文来源当作参数传进来，让机器在<b>写出之前</b>逐值比对一次。</li>
 * </ul>
 */
public final class LedgerRedaction {

    /** 摘要长度：8 位十六进制 = 32 bit。取 8 位是为了 CSV 里可读，再长就没法肉眼比对两行了。 */
    private static final int DIGEST_HEX_CHARS = 8;

    /** 主键片段保留的头部字符数。 */
    private static final int KEY_FRAGMENT_HEAD = 3;

    /** 主键片段保留的尾部字符数。 */
    private static final int KEY_FRAGMENT_TAIL = 4;

    /** 短于该长度的标识不给片段（头尾加起来会露全），直接只出摘要。 */
    private static final int KEY_FRAGMENT_MIN_LENGTH = KEY_FRAGMENT_HEAD + KEY_FRAGMENT_TAIL + 1;

    private LedgerRedaction() {
    }

    /**
     * 明文 → SHA-256 前 8 位十六进制；{@code null}/空白返回 {@code ""}。
     *
     * <p>空值不参与摘要：空串的摘要是一个固定值，一百条空名字都出同一个摘要，
     * 看起来像「这一批有一百个同名的人」，比留空更容易误导。
     */
    public static String digest8(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            return "";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] out = md.digest(plaintext.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(DIGEST_HEX_CHARS);
            for (int i = 0; i < out.length && hex.length() < DIGEST_HEX_CHARS; i++) {
                hex.append(String.format("%02x", out[i]));
            }
            return hex.substring(0, DIGEST_HEX_CHARS);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 强制实现的算法；走到这里说明运行环境异常，宁可炸也不静默出一个空摘要
            throw new IllegalStateException("运行环境缺少 SHA-256，拒绝在脱敏环节降级", e);
        }
    }

    /**
     * 主键片段：首 3 位 + {@code ***} + 末 4 位。
     *
     * <p>长度不足 {@value #KEY_FRAGMENT_MIN_LENGTH} 时只出 {@link #digest8}：
     * 一个 6 位的主键保首 3 保末 3 就等于全露了，那种情况下片段是负资产。
     */
    public static String keyFragment(String key) {
        if (key == null || key.isEmpty()) {
            return "";
        }
        if (key.length() < KEY_FRAGMENT_MIN_LENGTH) {
            return digest8(key);
        }
        return key.substring(0, KEY_FRAGMENT_HEAD) + "***"
                + key.substring(key.length() - KEY_FRAGMENT_TAIL);
    }

    /**
     * 出口自检：产物里<b>不得</b>出现任一明文。
     *
     * <p>比对前对明文做长度过滤：少于 {@value #DIGEST_HEX_CHARS} 位的串（如某个值恰好是
     * {@code "1"}）拿去全文搜，几乎必然在 CSV 的数字列里撞上一次，报出一个查不出所以然的假阳性。
     * 这种「宁可漏报也不误报」的取舍是有意的——自检拦不住的东西，人还能看出来；
     * 自检天天喊狼来了，人就不看了。
     *
     * @throws IllegalStateException 产物里出现了给定明文之一（此时 CSV 不应写出）
     */
    public static void assertNoPlaintext(String rendered, Collection<String> plaintexts) {
        Objects.requireNonNull(rendered, "产物不得为 null");
        Objects.requireNonNull(plaintexts, "明文清单不得为 null");
        for (String secret : plaintexts) {
            if (secret == null || secret.length() < DIGEST_HEX_CHARS) {
                continue;
            }
            if (rendered.contains(secret)) {
                throw new IllegalStateException(
                        "导出产物里出现了未脱敏的明文，拒绝写出（原值长度 " + secret.length()
                                + "，已定位，值本身不打印）");
            }
        }
    }
}