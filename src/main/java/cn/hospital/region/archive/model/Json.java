package cn.hospital.region.archive.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极小 JSON 编解码 —— 本轮<b>刻意不引第三方 JSON 库</b>。
 *
 * <h2>为什么不引</h2>
 * <p>整个产品的依赖面被压到 4 个（PostgreSQL 驱动 / H2 / JUnit），原因是院内前置机往往没有
 * 联网的制品库，多一个 jar 就多一处「装不上」。JSON 在本产品里只有两处用途：
 * 构造模型请求体、解析模型响应体，以及构造写回院内患者主索引的请求体（T31）。
 * 为此拖进一个 JSON 库不划算，而手写要提防的坑其实就三个，本类逐个堵死：
 * <ul>
 *   <li><b>中文</b>：非 ASCII 一律原样输出（JSON 允许，UTF-8 报文里是正确的），
 *       只有 U+0020 以下的控制字符与 {@code "}、{@code \} 必须转义 —— 漏掉前者会让
 *       报文里的换行把 JSON 结构劈成两半，漏掉后者会让一条 rationale 直接废掉整次调用；</li>
 *   <li><b>非有限数</b>：{@code NaN} / {@code Infinity} 不是合法 JSON 字面量，
 *       Java 却能把它们 {@code String.valueOf} 出来。这里直接拒绝，不给它们出门的机会
 *       （置信度算出来一个 NaN，等于让下游拿到一个看起来合法、实际无法比较的值）；</li>
 *   <li><b>类型错配</b>：模型少给一个字段、把数组写成对象，是最常见的失败形态。
 *       {@link #asObject} / {@link #asArray} / {@link #asString} / {@link #asNumber}
 *       一律带「在哪里出错」的位置串，调用点直接转成 {@link ModelCallException}，
 *       排障时能看出是第几个字段而不是只看到一句「解析失败」。</li>
 * </ul>
 *
 * <p>解析结果用 {@code Map<String,Object>} / {@code List<Object>} / {@code String} /
 * {@code Double} / {@code Boolean} / {@code null} 六种表示——够用，且不需要任何运行时依赖。
 */
public final class Json {

    private Json() {
    }

    /** JSON 结构不符（缺字段、类型错、非法字面量）。由调用点转成 {@link ModelCallException}。 */
    public static class JsonException extends RuntimeException {
        public JsonException(String message) {
            super(message);
        }
    }

    // ---------- 写 ----------

    /**
     * 序列化。支持 {@code Map}、{@code Collection}、{@code String}、{@code Number}、
     * {@code Boolean} 与 {@code null}；其余类型直接拒绝（静默 {@code toString} 出来的
     * 对象写进 JSON 会变成一段谁也读不懂的字符串）。
     */
    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value);
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            writeString(sb, s);
        } else if (value instanceof Boolean b) {
            sb.append(b ? "true" : "false");
        } else if (value instanceof Number n) {
            sb.append(numberText(n));
        } else if (value instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!(e.getKey() instanceof String key)) {
                    throw new JsonException("JSON 对象的键必须是字符串，实际是 "
                            + (e.getKey() == null ? "null" : e.getKey().getClass().getName()));
                }
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(sb, key);
                sb.append(':');
                writeValue(sb, e.getValue());
            }
            sb.append('}');
        } else if (value instanceof Collection<?> c) {
            sb.append('[');
            boolean first = true;
            for (Object item : c) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeValue(sb, item);
            }
            sb.append(']');
        } else {
            throw new JsonException("不支持写入 JSON 的类型：" + value.getClass().getName());
        }
    }

    private static String numberText(Number n) {
        if (n instanceof Double || n instanceof Float) {
            double d = n.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new JsonException("非有限数（" + d + "）不是合法 JSON 字面量，拒绝写出");
            }
        }
        return String.valueOf(n);
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        // 中文等非 ASCII 字符原样输出：JSON 允许，UTF-8 报文里是对的
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    // ---------- 读 ----------

    /**
     * 解析一段 JSON 文本。结尾不允许有多余内容——模型偶尔在 JSON 后面接一句解释，
     * 那属于结构校验失败，不该被悄悄截断后当成成功。
     */
    public static Object parse(String text) {
        if (text == null) {
            throw new JsonException("待解析的 JSON 文本为 null");
        }
        Parser parser = new Parser(text);
        Object value = parser.value();
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw new JsonException("JSON 结束后有多余内容，位置 " + parser.pos());
        }
        return value;
    }

    // ---------- 带位置串的取值 ----------

    public static Map<String, Object> asObject(Object value, String where) {
        if (value instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) map;
            return typed;
        }
        throw new JsonException(where + " 应为 JSON 对象，实际是 " + typeNameOf(value));
    }

    public static List<Object> asArray(Object value, String where) {
        if (value instanceof List<?> list) {
            @SuppressWarnings("unchecked")
            List<Object> typed = (List<Object>) list;
            return typed;
        }
        throw new JsonException(where + " 应为 JSON 数组，实际是 " + typeNameOf(value));
    }

    public static String asString(Object value, String where) {
        if (value instanceof String s) {
            return s;
        }
        throw new JsonException(where + " 应为字符串，实际是 " + typeNameOf(value));
    }

    public static String asStringOrDefault(Object value, String where, String fallback) {
        return value == null ? fallback : asString(value, where);
    }

    public static double asNumber(Object value, String where) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        throw new JsonException(where + " 应为数字，实际是 " + typeNameOf(value));
    }

    public static int asInt(Object value, String where) {
        double d = asNumber(value, where);
        return (int) Math.round(d);
    }

    public static int asIntOrDefault(Object value, String where, int fallback) {
        return value == null ? fallback : asInt(value, where);
    }

    private static String typeNameOf(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Map) {
            return "对象";
        }
        if (value instanceof List) {
            return "数组";
        }
        if (value instanceof Number) {
            return "数字";
        }
        if (value instanceof Boolean) {
            return "布尔";
        }
        return "字符串";
    }

    /** 构造一个保序 Map，省去到处 {@code new LinkedHashMap<>()} 的噪音。 */
    public static Map<String, Object> object() {
        return new LinkedHashMap<>();
    }

    /** 构造一个保序 List。 */
    public static List<Object> array() {
        return new ArrayList<>();
    }

    // ---------- 递归下降解析器 ----------

    private static final class Parser {

        private final String text;
        private int pos;

        Parser(String text) {
            this.text = text;
        }

        int pos() {
            return pos;
        }

        boolean atEnd() {
            return pos >= text.length();
        }

        void skipWhitespace() {
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        Object value() {
            skipWhitespace();
            if (atEnd()) {
                throw new JsonException("JSON 文本提前结束");
            }
            char c = text.charAt(pos);
            return switch (c) {
                case '{' -> objectValue();
                case '[' -> arrayValue();
                case '"' -> stringValue();
                case 't' -> {
                    expectLiteral("true");
                    yield Boolean.TRUE;
                }
                case 'f' -> {
                    expectLiteral("false");
                    yield Boolean.FALSE;
                }
                case 'n' -> {
                    expectLiteral("null");
                    yield null;
                }
                default -> numberValue();
            };
        }

        private void expectLiteral(String literal) {
            if (!text.startsWith(literal, pos)) {
                throw new JsonException("位置 " + pos + " 处期待字面量 " + literal);
            }
            pos += literal.length();
        }

        private Map<String, Object> objectValue() {
            Map<String, Object> map = new LinkedHashMap<>();
            pos++; // '{'
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = stringValue();
                skipWhitespace();
                if (peek() != ':') {
                    throw new JsonException("位置 " + pos + " 处期待 ':'");
                }
                pos++;
                map.put(key, value());
                skipWhitespace();
                char c = peek();
                if (c == ',') {
                    pos++;
                } else if (c == '}') {
                    pos++;
                    return map;
                } else {
                    throw new JsonException("位置 " + pos + " 处期待 ',' 或 '}'");
                }
            }
        }

        private List<Object> arrayValue() {
            List<Object> list = new ArrayList<>();
            pos++; // '['
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(value());
                skipWhitespace();
                char c = peek();
                if (c == ',') {
                    pos++;
                } else if (c == ']') {
                    pos++;
                    return list;
                } else {
                    throw new JsonException("位置 " + pos + " 处期待 ',' 或 ']'");
                }
            }
        }

        private String stringValue() {
            if (peek() != '"') {
                throw new JsonException("位置 " + pos + " 处期待字符串");
            }
            pos++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (atEnd()) {
                    throw new JsonException("字符串未闭合");
                }
                char c = text.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (atEnd()) {
                    throw new JsonException("转义符后文本结束");
                }
                char esc = text.charAt(pos++);
                switch (esc) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (pos + 4 > text.length()) {
                            throw new JsonException("\\u 转义不完整，位置 " + pos);
                        }
                        sb.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                    default -> throw new JsonException("不支持的转义序列 \\" + esc + "，位置 " + (pos - 1));
                }
            }
        }

        private Double numberValue() {
            int start = pos;
            if (peek() == '-' || peek() == '+') {
                pos++;
            }
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    pos++;
                } else {
                    break;
                }
            }
            if (start == pos) {
                throw new JsonException("位置 " + pos + " 处不是合法 JSON 值");
            }
            String literal = text.substring(start, pos);
            try {
                return Double.valueOf(literal);
            } catch (NumberFormatException e) {
                throw new JsonException("非法数字字面量：" + literal);
            }
        }

        private char peek() {
            if (atEnd()) {
                throw new JsonException("JSON 文本提前结束");
            }
            return text.charAt(pos);
        }
    }
}