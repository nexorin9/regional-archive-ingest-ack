package cn.hospital.region.archive.model;

/**
 * 审计文本的<b>出口闸</b> —— 导出前把任何可能携带患者可识别内容的字符挡掉。
 *
 * <h2>为什么不直接相信上游已经脱敏</h2>
 * <p>{@link PromptSummary} 的形态行（{@code z_field_map | ZX1.2(len=6) digest=ab12cd34}）
 * 结构上确实不含原文，这是第一道防线。但防线只有一道是不够的：
 * 审计行的组装发生在<b>网关内部</b>，而网关的入参来源是真实院内报文；
 * 只要哪一天有人为了排障方便把一个字段名换成字段值（很常见的临时改动），
 * 库里立刻就有一份患者明细的副本，而这类改动<b>不留痕、不报错、测试照样绿</b>——
 * 现有用例断言的是「正常路径下摘要里没有明文」，而它拦不住这一种。
 *
 * <h2>本闸的两条规则，为什么这么切</h2>
 * <ul>
 *   <li><b>连续 ≥ {@value #MIN_DIGIT_RUN} 位的数字 → {@code ***}</b>。
 *       身份证 18 位、手机号 11 位、以及各种内部编号都落在这个形态里。
 *       阈值取 11 而不是 18：手机号同样是患者可识别信息，
 *       而 token 计数、耗时毫秒这些<b>该留</b>的数字都在 8 位以内，不会被误伤。</li>
 *   <li><b>可打印 ASCII 以外的字符 → {@code *}</b>。
 *       中文姓名、中文地址、全角标点、控制字符全部落在这里，
 *       于是「导出文件里没有中文」成为一条**可机器断言**的不变量：
 *       测试扫一遍就能证明没有姓名、没有地址、没有中文备注，
 *       而不需要维护一份「已知明文清单」去逐条比对。</li>
 * </ul>
 *
 * <p>代价也要说清楚：一旦有人把操作名写成中文，那一列在导出里会变成一串星号。
 * 这是<b>刻意选择</b>的失败方向——一份能看见的统计强过一份看不见的患者明细。
 * 操作名的取值都在本包内（{@code z_field_map} / {@code identity_candidates}），
 * 天然是 ASCII。
 */
public final class AuditTextGuard {

    /** 达到该位数的连续数字串一律视为可能可识别（身份证 18 位 / 手机号 11 位）。 */
    public static final int MIN_DIGIT_RUN = 11;

    /** 数字串的替换标记。 */
    public static final String DIGIT_MASK = "***";

    private AuditTextGuard() {
    }

    /**
     * 严格挡：长数字串与非 ASCII 都替换。用于<b>入参摘要</b>——唯一可能携带业务内容的列。
     *
     * @param raw 原文；{@code null} 返回空串
     */
    public static String maskStrict(String raw) {
        return apply(raw, true);
    }

    /**
     * 宽松挡：只把非 ASCII 字符替换成 {@code *}，ASCII 原样保留。
     *
     * <p>用于 {@code requestId} 这类<b>协议级标识</b>：正常取值是 {@code chatcmpl-xxx}，
     * 保留原样才能和服务端日志对上；但一个把患者姓名回显进 {@code id} 的端点不该因此漏出去，
     * 所以仍然过一道 ASCII 白名单。长数字串规则在这里不启用，
     * 因为服务端标识里出现长数字串是常态（如自增 ID），不是可识别信息。
     *
     * @param raw 原文；{@code null} 返回空串
     */
    public static String maskAsciiOnly(String raw) {
        return apply(raw, false);
    }

    private static String apply(String raw, boolean maskDigitRuns) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(raw.length());
        int i = 0;
        while (i < raw.length()) {
            char c = raw.charAt(i);
            if (maskDigitRuns && isDigit(c)) {
                int runEnd = i;
                while (runEnd < raw.length() && isDigit(raw.charAt(runEnd))) {
                    runEnd++;
                }
                if (runEnd - i >= MIN_DIGIT_RUN) {
                    out.append(DIGIT_MASK);
                } else {
                    out.append(raw, i, runEnd);
                }
                i = runEnd;
                continue;
            }
            out.append(isPrintableAscii(c) ? c : '*');
            i++;
        }
        return out.toString();
    }

    /**
     * 产物自检：整份导出文本里不得出现非可打印 ASCII，也不得残留长数字串。
     *
     * <p>与 {@link cn.hospital.region.archive.ledger.LedgerRedaction#assertNoPlaintext}
     * 的分工不同：那个要调用方提供「已知明文清单」，本方法<b>不需要</b>任何清单，
     * 因为它检查的是整类形态（中文 / 长数字串），覆盖面与清单无关。
     *
     * @param rendered 已渲染的产物（CSV 文本或文件名）
     * @throws IllegalStateException 产物里出现非 ASCII 或长数字串（此时文件不应写出）
     */
    public static void assertClean(String rendered) {
        if (rendered == null) {
            throw new IllegalStateException("待检产物不得为 null");
        }
        for (int i = 0; i < rendered.length(); i++) {
            char c = rendered.charAt(i);
            if (!isPrintableAscii(c)) {
                throw new IllegalStateException(
                        "审计导出产物里出现了非可打印 ASCII 的字符（U+"
                                + String.format("%04X", (int) c)
                                + "，位置 " + i + "）：可能是姓名、地址或中文备注，拒绝写出");
            }
        }
        int digits = 0;
        for (int i = 0; i < rendered.length(); i++) {
            char c = rendered.charAt(i);
            if (isDigit(c)) {
                digits++;
                if (digits >= MIN_DIGIT_RUN) {
                    throw new IllegalStateException(
                            "审计导出产物里出现了连续 " + digits + " 位数字（位置 " + i
                                    + "）：可能是身份证号或手机号，拒绝写出");
                }
            } else {
                digits = 0;
            }
        }
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    /** 可打印 ASCII：{@code 0x20} 到 {@code 0x7E}，含空格；制表与换行按非 ASCII 处理。 */
    private static boolean isPrintableAscii(char c) {
        return c >= 0x20 && c <= 0x7E;
    }
}