package cn.hospital.region.archive.redelivery;

/**
 * 控制号（MSH-10）归一 —— 对账差集里「格式差异不得被当成缺失」的唯一口径。
 *
 * <h2>为什么需要它</h2>
 * <p>区域平台的回执列表是<b>对端自己存下来的那份报文头</b>：它经过对端网关、前置机、
 * 报表导出三轮手，多半没坏，但也<b>常有格式差异</b>：
 * <ul>
 *   <li>前后空白与换行 —— 报表导出按列截断时会把尾随空格带上；</li>
 *   <li>中间空白 —— 从网页表格复制进工单时，Excel 会把长串折行；</li>
 *   <li>前导零 —— 对端按数值排序列展示时 {@code CTRL-0001} 被存成 {@code 1}，
 *       再补零回来时补的位数与本端不同（{@code 0001} / {@code 01} / {@code 1}）。</li>
 * </ul>
 * 这些差异里<b>没有一条是真实的「对端没收到」</b>。若逐字比对，这三种都会把一条
 * <b>已经收到并回了 AA</b> 的报文判成缺失，于是信息员点「重投」，对端收到同一条报文的
 * 第二份 —— 恰好是本产品最该避免的事：<b>差集的假阳性会制造重复落地</b>。
 *
 * <h2>归一规则（三条，按顺序）</h2>
 * <ol>
 *   <li>去首尾空白；</li>
 *   <li>删掉内部所有空白字符（不限于空格，含 {@code \t \r \n} 与全角空格 U+3000）；</li>
 *   <li>对每一段<b>长度 ≥ 2 的连续数字</b> 去掉前导零，但<b>至少保留一位</b>
 *       （{@code 0001 → 1}、{@code 00 → 0}、{@code 0 → 0}）。</li>
 * </ol>
 *
 * <h2>为什么只处理长度 ≥ 2 的数字段</h2>
 * <p>单字符数字段去掉前导零后会变成空串，于是 {@code A-0-B} 与 {@code A--B} 会被判成同一个
 * 控制号 —— 这比误判缺失更糟。所以「长度 1 的数字段」一律原样保留，
 * {@code 0} 也留成 {@code 0}。
 *
 * <h2>归一<b>不</b>做的事：它不能把两条真报文合并</h2>
 * <p>HL7 规范要求 MSH-10 在<b>逐字</b>意义上唯一，所以 {@code MSG-01} 与 {@code MSG-1}
 * 理论上可能是两条不同报文。归一把它们并成同一个键 —— 这个歧义
 * <b>不在归一器里猜</b>：{@link AckReconciler} 检测到「同一个归一键对应多条本地条目」时，
 * 把它列进 {@code ambiguousControlIds} 并<b>把该键整体排除在缺失清单之外</b>
 * （宁可少重投一条让人看一眼，也不让差集凭一个归一规则去替平台决定两条报文是不是同一条）。
 *
 * <h2>不可变性</h2>
 * <p>本类是纯函数、无状态、线程安全；归一结果可缓存（{@code ControlIdNormalizer} 无内部状态，
 * 调用方若在热路径上反复归一，自行按批缓存）。
 */
public final class ControlIdNormalizer {

    private ControlIdNormalizer() {
    }

    /**
     * 归一一个控制号。
     *
     * @param raw 对端回执列表里或本地台账里的原始控制号；{@code null} 与空白返回空串
     * @return 归一后的比较键；<b>空串</b>表示输入本身没有可用内容（调用方须拒绝这样的控制号，
     *         拿空串去比对会让「对端漏报」和「本端没这条」两件事混成一件）
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder compact = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (!isWhitespace(c)) {
                compact.append(c);
            }
        }
        return stripLeadingZeros(compact.toString());
    }

    /** 归一后是否可用（非空）。 */
    public static boolean isUsable(String raw) {
        return !normalize(raw).isEmpty();
    }

    /** 中文说明：写进日志与差集报表，让排障的人知道这条为什么被判成缺失。 */
    public static String explain(String raw) {
        String normalized = normalize(raw);
        if (normalized.isEmpty()) {
            return "控制号为空";
        }
        if (normalized.equals(raw)) {
            return "控制号逐字一致（" + raw + "）";
        }
        return "控制号归一后一致（" + raw + " → " + normalized + "）";
    }

    private static boolean isWhitespace(char c) {
        // Character.isWhitespace 不认全角空格 U+3000 与不换行空格 U+00A0，
        // 而这两者恰恰是「从网页报表复制过来」的常见形态。
        return Character.isWhitespace(c) || c == ' ' || c == '　';
    }

    /**
     * 逐段去前导零：只动长度 ≥ 2 的数字段，且至少留一位。
     *
     * <p>逐字符扫一遍而不是用正则，是为了在遇到非数字字符时能原样复制，
     * 且长度有界、无回溯 —— 回执列表一批几百条时这段会被调很多次。
     */
    private static String stripLeadingZeros(String value) {
        int length = value.length();
        StringBuilder out = new StringBuilder(length);
        int i = 0;
        while (i < length) {
            if (!isDigit(value.charAt(i))) {
                out.append(value.charAt(i));
                i++;
                continue;
            }
            int runStart = i;
            while (i < length && isDigit(value.charAt(i))) {
                i++;
            }
            int runLength = i - runStart;
            if (runLength < 2) {
                out.append(value, runStart, i);
                continue;
            }
            int firstNonZero = runStart;
            while (firstNonZero < i - 1 && value.charAt(firstNonZero) == '0') {
                firstNonZero++;
            }
            out.append(value, firstNonZero, i);
        }
        return out.toString();
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }
}