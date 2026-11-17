package cn.hospital.region.archive.hl7;

import java.util.Objects;

/**
 * MSH 自述的 5 个分隔符。
 *
 * <p>对应源产品 {@code HL7Parser.parse(BufferedReader)} 中
 * {@code isHeaderSegment} 命中后执行的 {@code setDelimiters(line.substring(3, 8))}
 * （github_ref/dcm4che/dcm4che-hl7/src/main/java/org/dcm4che3/hl7/HL7Parser.java:103, 158-163）。
 *
 * <p>5 个字符按 HL7 v2 规范固定为：
 * <pre>
 *   MSH-1           MSH-2（编码字符，4 位）
 *   [3] 字段        [4] 组件分隔符  [5] 重复分隔符  [6] 转义符  [7] 子组件分隔符
 *   ^\~&            默认值                ^         ~         \         &
 * </pre>
 *
 * <p>本类不做转义还原（T6 由 Hl7Parser 在解析期处理）。
 */
public final class Delimiters {

    public static final Delimiters DEFAULT = fromChars('|', '^', '~', '\\', '&');

    private final char fieldSeparator;
    private final char componentSeparator;
    private final char repetitionSeparator;
    private final char escapeCharacter;
    private final char subcomponentSeparator;

    private Delimiters(char field, char component, char repetition, char escape, char subcomponent) {
        this.fieldSeparator = field;
        this.componentSeparator = component;
        this.repetitionSeparator = repetition;
        this.escapeCharacter = escape;
        this.subcomponentSeparator = subcomponent;
    }

    public static Delimiters fromChars(char field, char component, char repetition, char escape, char subcomponent) {
        return new Delimiters(field, component, repetition, escape, subcomponent);
    }

    /**
     * 校验输入行是否为 MSH / BHS / FHS 段名（与源 {@code isHeaderSegment} 完全一致）。
     */
    public static boolean isHeaderSegment(String line) {
        return line.startsWith("MSH") || line.startsWith("BHS") || line.startsWith("FHS");
    }

    /**
     * 从 header 行的 substring(3, 8) 5 个字符读取分隔符。
     * 行为复刻源产品 {@code setDelimiters(line.substring(3, 8))} —— 长度不足时抛异常并把前 32 字节原值附在异常消息里。
     */
    public static Delimiters readFromHeader(String headerLine) {
        Objects.requireNonNull(headerLine, "headerLine");
        if (!isHeaderSegment(headerLine)) {
            throw new DelimiterNotDeclared(
                    "非 header 段，无法读取自述分隔符: " + headerLineSnippet(headerLine));
        }
        if (headerLine.length() < 8) {
            throw new DelimiterNotDeclared(
                    "header 段长度不足 8，无法读取 5 个分隔符: " + headerLineSnippet(headerLine));
        }
        char[] five = new char[5];
        headerLine.getChars(3, 8, five, 0);
        return new Delimiters(five[0], five[1], five[2], five[3], five[4]);
    }

    public char fieldSeparator() { return fieldSeparator; }
    public char componentSeparator() { return componentSeparator; }
    public char repetitionSeparator() { return repetitionSeparator; }
    public char escapeCharacter() { return escapeCharacter; }
    public char subcomponentSeparator() { return subcomponentSeparator; }

    /**
     * 把 5 个分隔符按下标顺序拼成 5 字符字符串，对应源 {@code Delimiter.values()}.
     */
    public String asString() {
        char[] s = new char[5];
        s[0] = fieldSeparator;
        s[1] = componentSeparator;
        s[2] = repetitionSeparator;
        s[3] = escapeCharacter;
        s[4] = subcomponentSeparator;
        return new String(s);
    }

    public boolean isAnyCharUnusedInField(char c) {
        return c == componentSeparator || c == repetitionSeparator || c == escapeCharacter || c == subcomponentSeparator;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Delimiters that)) return false;
        return fieldSeparator == that.fieldSeparator
                && componentSeparator == that.componentSeparator
                && repetitionSeparator == that.repetitionSeparator
                && escapeCharacter == that.escapeCharacter
                && subcomponentSeparator == that.subcomponentSeparator;
    }

    @Override
    public int hashCode() {
        return Objects.hash(fieldSeparator, componentSeparator, repetitionSeparator, escapeCharacter, subcomponentSeparator);
    }

    @Override
    public String toString() {
        return "Delimiters{field='" + fieldSeparator + "', comp='" + componentSeparator
                + "', rep='" + repetitionSeparator + "', esc='" + escapeCharacter
                + "', sub='" + subcomponentSeparator + "'}";
    }

    private static String headerLineSnippet(String headerLine) {
        String s = headerLine.length() > 32 ? headerLine.substring(0, 32) + "..." : headerLine;
        return "[len=" + headerLine.length() + "] " + s;
    }
}
