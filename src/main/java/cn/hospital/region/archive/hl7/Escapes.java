package cn.hospital.region.archive.hl7;

import java.util.List;
import java.util.Objects;

/**
 * HL7 v2 FSRET 五类转义序列还原。
 *
 * <p>五类转义序列与 MSH 自述分隔符的对应（HL7 v2 标准）：
 * <pre>
 *   \F\  → 字段分隔符    （field separator）
 *   \S\  → 组件分隔符    （component separator）
 *   \R\  → 重复分隔符    （repetition separator）
 *   \E\  → 转义字符本身  （escape character）
 *   \T\  → 子组件分隔符  （subcomponent separator）
 * </pre>
 *
 * <p>边界（按本产品语义，**不**抛错）：
 * <ul>
 *   <li>完整 {@code \X\} 但 X 不在 FSRET 内：按"非标准转义文本"原样保留 {@code \X\}；</li>
 *   <li>未成对（{@code \}、{@code \X}、{@code \<eof>} 等）：原样保留并写入解析告警；</li>
 *   <li>{@code \\}（两个转义符紧邻）：视为未成对起始，原样保留并记告警（避免吃掉 {@code \\}）；</li>
 * </ul>
 *
 * <p>源产品对应实现见
 * {@code github_ref/dcm4che/dcm4che-hl7/src/main/java/org/dcm4che3/hl7/HL7Parser.java:113-131}；
 * 该实现走 SAX 事件流按 token 处理，本类简化为按字符扫描 + StringBuilder 输出，
 * 适用于 {@link Hl7Message.Segment#fields()} 的字段值批量还原。
 */
public final class Escapes {

    /** 字段分隔符转义字符（F → d.fieldSeparator()）。 */
    public static final char F = 'F';
    /** 组件分隔符转义字符（S → d.componentSeparator()）。 */
    public static final char S = 'S';
    /** 重复分隔符转义字符（R → d.repetitionSeparator()）。 */
    public static final char R = 'R';
    /** 转义字符本身的转义字符（E → d.escapeCharacter()）。 */
    public static final char E = 'E';
    /** 子组件分隔符转义字符（T → d.subcomponentSeparator()）。 */
    public static final char T = 'T';

    private Escapes() {
    }

    /**
     * 在字段值范围内把转义序列还原为对应分隔符；未成对或非标准序列保留原文并写告警。
     *
     * @param raw      字段原始值（已按字符集解码为 Java 字符串）
     * @param d        MSH 自述的 5 个分隔符
     * @param warnings 解析告警容器（可为 null 表示不收集；未成对序列会写入一条汇总告警）
     * @return 还原后的字符串
     */
    public static String restore(String raw, Delimiters d, List<String> warnings) {
        if (raw == null || raw.isEmpty()) return raw;
        char esc = d.escapeCharacter();
        StringBuilder out = new StringBuilder(raw.length());
        int i = 0;
        int len = raw.length();
        int unpaired = 0;
        while (i < len) {
            char c = raw.charAt(i);
            if (c != esc) {
                out.append(c);
                i++;
                continue;
            }
            // c == esc：尝试匹配 \X\ 三字符模式
            // 字段末尾的 \ 或 \X（不足 3 字符）按未成对处理
            if (i + 2 >= len) {
                unpaired++;
                out.append(raw, i, len);
                i = len;
                break;
            }
            char x = raw.charAt(i + 1);
            char close = raw.charAt(i + 2);
            if (close != esc) {
                // \X 后面不是 \ —— 未成对，保留 \X 两个字符
                unpaired++;
                out.append(raw, i, i + 2);
                i += 2;
                continue;
            }
            // 完整 \X\：查 FSRET 映射
            char restored = mapEscape(x, d);
            if (restored == 0) {
                // X 不在 FSRET 内，原样保留 \X\ 三字符（按非标准转义文本处理）
                out.append(raw, i, i + 3);
            } else {
                out.append(restored);
            }
            i += 3;
        }
        if (unpaired > 0 && warnings != null) {
            warnings.add("hl7/escape: 发现 " + unpaired
                    + " 处未成对转义（\\X 无关闭符 或 \\<字段末尾>），已按原文保留");
        }
        return out.toString();
    }

    /** 便捷重载：不收集告警。 */
    public static String restore(String raw, Delimiters d) {
        return restore(raw, d, null);
    }

    private static char mapEscape(char x, Delimiters d) {
        switch (x) {
            case F: return d.fieldSeparator();
            case S: return d.componentSeparator();
            case R: return d.repetitionSeparator();
            case E: return d.escapeCharacter();
            case T: return d.subcomponentSeparator();
            default: return 0;
        }
    }

    /**
     * 正向转义：把裸分隔符写成 {@code \X\}，供本产品<b>拼装</b>报文时使用（还原的逆运算）。
     *
     * <p>出站文本（MSA-3 说明、ERR-4 中文说明）必须先过这一层。原因很具体：ERR-4 会带上
     * FieldLocator 定位出的原值片段，片段取自 MSH 段原文截断，<b>必然含字段分隔符</b>；
     * 不转义直接拼，回执报文会从 ERR-4 中间被切成两段，对端解析必然错位。
     *
     * <p>与 {@link #restore} 的边界：还原对未成对 / 非标准序列保留原文并告警（入站容错），
     * 转义不做任何猜测——只做五类 FSRET 的机械替换，其余字符原样输出。
     *
     * @param raw 待拼装的文本（可含任意中英文与分隔符）
     * @param d   目标报文自述的 5 个分隔符
     * @return 转义后的文本；{@code raw} 为 null 时返回空串
     */
    public static String escape(String raw, Delimiters d) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        Objects.requireNonNull(d, "delimiters");
        char esc = d.escapeCharacter();
        StringBuilder out = new StringBuilder(raw.length() + 8);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            char marker = escapeMarker(c, d);
            if (marker == 0) {
                out.append(c);
            } else {
                out.append(esc).append(marker).append(esc);
            }
        }
        return out.toString();
    }

    /** 该字符需要写成哪个 FSRET 标记；不属于五类时返回 0。 */
    private static char escapeMarker(char c, Delimiters d) {
        if (c == d.fieldSeparator()) {
            return F;
        }
        if (c == d.componentSeparator()) {
            return S;
        }
        if (c == d.repetitionSeparator()) {
            return R;
        }
        if (c == d.subcomponentSeparator()) {
            return T;
        }
        if (c == d.escapeCharacter()) {
            return E;
        }
        return 0;
    }
}
