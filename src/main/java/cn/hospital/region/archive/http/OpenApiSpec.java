package cn.hospital.region.archive.http;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * 把本项目的 OpenAPI 3 文档（{@code src/main/resources/http/openapi.yaml}）
 * 装成内存里的可查表 ——<b>手写最小解析器</b>，不引入 SnakeYAML / Jackson-YAML。
 *
 * <h2>为什么不引依赖</h2>
 * <p>本产品的依赖面是死线（jdk 17 自身 + postgres 驱动 + h2 + junit）。
 * 再加一个 yaml 库，要在院内前置机上离线构建时同时带着它的传递依赖 ——
 * 前置机常常没外网。OpenAPI 这份文档结构稳定、范围窄，
 * 自己写一个 「够用即可」 的解析器只有**百行**，代价远小于扩依赖。
 *
 * <h2>支持的语法（与 {@code openapi.yaml} 实际使用的子集对应）</h2>
 * <ul>
 *   <li>缩进 2 空格表示层级；</li>
 *   <li>键值行：{@code key: value}（value 可空 / 字符串 / 行内序列 {@code [a, b]} / 块串 {@code key: |}）；</li>
 *   <li>序列：{@code - item}（同缩进下若干项）；序列也可写作行内 {@code [a, b, c]}；</li>
 *   <li>键名/字符串可加引号（{@code "a"} 或 {@code 'a'}）；</li>
 *   <li>注释以 {@code #} 开头（必须在一行的开头或跟随空白）；块串中允许出现 {@code #}；</li>
 *   <li>行内键值序列（如 {@code security: [- Name: [] ]}）；</li>
 *   <li>块串 {@code key: |} —— 以下若干同缩进的非空行都属于内容，换行用 {@code \n} 串起来。</li>
 * </ul>
 * <p>不打算支持：{@code &} 锚点、{@code !!str} 标签、UTF-16/UTF-32 BOM —— 本文档没有这些。
 *
 * <h2>用法</h2>
 * <pre>{@code
 * OpenApiSpec spec = OpenApiSpec.loadFromClasspath("/http/openapi.yaml");
 * String[] paths = spec.paths();
 * for (String p : paths) System.out.println(p + " " + spec.methodsUnder(p));
 * }</pre>
 */
public final class OpenApiSpec {

    private final Node root;
    private final String source;

    private OpenApiSpec(Node root, String source) {
        this.root = root;
        this.source = source;
    }

    /**
     * 从 classpath 读 {@code openapi.yaml}。
     *
     * @throws IOException                资源不可读
     * @throws IllegalArgumentException   文档语法不能被解析
     */
    public static OpenApiSpec loadFromClasspath(String resourcePath) throws IOException {
        Objects.requireNonNull(resourcePath, "resourcePath");
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            cl = OpenApiSpec.class.getClassLoader();
        }
        try (InputStream in = cl.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IOException("classpath 上找不到资源：" + resourcePath);
            }
            byte[] bytes = in.readAllBytes();
            String text = new String(bytes, StandardCharsets.UTF_8);
            return loadFromString(text, resourcePath);
        }
    }

    /** 直接由字符串装载；测试用。 */
    public static OpenApiSpec loadFromString(String text, String source) {
        return new OpenApiSpec(Parser.parse(text, source), source);
    }

    /** 文首声明的 openapi 版本（如 {@code 3.0.3}）。 */
    public String openapiVersion() {
        return root.child("openapi").asScalar("");
    }

    /** 文首 info.title。 */
    public String title() {
        return root.child("info").child("title").asScalar("");
    }

    /** 文首 info.version。 */
    public String version() {
        return root.child("info").child("version").asScalar("");
    }

    /**
     * 所有声明过的路径（按字典序）。
     */
    public String[] paths() {
        Node pathsNode = root.child("paths");
        if (!pathsNode.isMap()) {
            return new String[0];
        }
        return new TreeMap<>(pathsNode.children).keySet().toArray(new String[0]);
    }

    /**
     * 一条路径上声明过的 HTTP 方法（按字典序）。
     * 仅返回大写标准名（{@code GET / POST / PUT / DELETE / PATCH / HEAD / OPTIONS}）。
     */
    public String[] methodsUnder(String path) {
        Node pathNode = root.child("paths").child(path);
        if (!pathNode.isMap()) {
            return new String[0];
        }
        List<String> known = new ArrayList<>();
        for (String m : new TreeMap<>(pathNode.children).keySet()) {
            String upper = m.toUpperCase();
            switch (upper) {
                case "GET": case "POST": case "PUT": case "DELETE":
                case "PATCH": case "HEAD": case "OPTIONS":
                    known.add(upper);
                    break;
                default: // parameters / summary / description 等非方法键，不算
                    break;
            }
        }
        return known.toArray(new String[0]);
    }

    /**
     * 路径上某方法的响应码列表（如 {@code ["200", "400", "401"]}）。
     */
    public String[] responseCodesUnder(String path, String method) {
        Node opNode = root.child("paths").child(path)
                .child(method.toLowerCase());
        Node responses = opNode.child("responses");
        if (!responses.isMap()) {
            return new String[0];
        }
        return new TreeMap<>(responses.children).keySet().toArray(new String[0]);
    }

    /**
     * 该路径上是否要求鉴权：默认要；显式 {@code security: []} 表示不需要。
     */
    public boolean requiresAuth(String path, String method) {
        Node opNode = root.child("paths").child(path)
                .child(method.toLowerCase());
        Node security = opNode.child("security");
        if (!security.isList()) {
            return true;
        }
        return !security.list.isEmpty();
    }

    /** 解析来源（classpath 路径或字符串标记），调试用。 */
    public String source() {
        return source;
    }

    @Override
    public String toString() {
        return "OpenApiSpec{openapi=" + openapiVersion() + ", version=" + version() + ", paths="
                + List.of(paths()) + "}";
    }

    /** 测试用：把路径+方法集合拍平成字符串集合。 */
    public static Set<String> flattenDeclarations(OpenApiSpec spec) {
        Set<String> out = new LinkedHashSet<>();
        for (String p : spec.paths()) {
            for (String m : spec.methodsUnder(p)) {
                out.add(m + " " + p);
            }
        }
        return Collections.unmodifiableSet(out);
    }

    // ===========================================================================
    // 最小语法树
    // ===========================================================================

    /**
     * 一段 YAML 文档的最小建模。Node 同时持有三种可能的内容：
     * 标量、映射（按插入顺序）、序列。任意时刻只有一种类型「活跃」。
     */
    static final class Node {
        private final String scalar;
        private final Map<String, Node> children;
        private final List<Node> list;
        private final Type type;

        enum Type { SCALAR, MAP, LIST, NULL }

        private Node(String scalar, Map<String, Node> children, List<Node> list, Type type) {
            this.scalar = scalar;
            this.children = children;
            this.list = list;
            this.type = type;
        }

        static Node ofScalar(String value) {
            return new Node(value, null, null, Type.SCALAR);
        }

        static Node ofMap(Map<String, Node> children) {
            return new Node(null, children, null, Type.MAP);
        }

        static Node ofList(List<Node> items) {
            return new Node(null, null, items, Type.LIST);
        }

        static Node ofNull() {
            return new Node(null, null, null, Type.NULL);
        }

        Node child(String key) {
            if (children == null) {
                return ofNull();
            }
            Node n = children.get(key);
            return n == null ? ofNull() : n;
        }

        String asScalar(String fallback) {
            return type == Type.SCALAR ? (scalar == null ? fallback : scalar) : fallback;
        }

        boolean isMap() {
            return type == Type.MAP && children != null;
        }

        boolean isList() {
            return type == Type.LIST && list != null;
        }

        /** 取 MAP 里最后插入的 value；用于「上一个空 MAP 升级成 LIST」。 */
        Node lastValue() {
            if (children == null || children.isEmpty()) return null;
            Node last = null;
            for (Node n : children.values()) last = n;
            return last;
        }

        /** 把 MAP 最后插入的那对 key→value 中的 value 换成给定的 node。 */
        void replaceLastValue(Node newValue) {
            if (children == null || children.isEmpty()) return;
            String lastKey = null;
            for (String k : children.keySet()) lastKey = k;
            if (lastKey != null) children.put(lastKey, newValue);
        }

        @Override
        public String toString() {
            switch (type) {
                case SCALAR: return "scalar(" + scalar + ")";
                case MAP:    return "map(" + (children == null ? 0 : children.size()) + ")";
                case LIST:   return "list(" + (list == null ? 0 : list.size()) + ")";
                default:     return "null";
            }
        }
    }

    // ===========================================================================
    // 解析器
    // ===========================================================================

    /**
     * 把文档切成「带缩进的行 → 嵌套结构」。
     * 仅支持 OpenAPI 这份子文档用到的语法（看类注释）。不试图覆盖 YAML 1.2 全部。
     */
    private static final class Parser {

        private final List<String> lines;
        private final String source;

        private Parser(List<String> lines, String source) {
            this.lines = lines;
            this.source = source;
        }

        static Node parse(String text, String source) {
            List<String> rawLines = splitLines(text);
            // 移除空行与全注释行；同时把块串标记化。
            List<Line> tokens = new ArrayList<>();
            BlockString block = null;
            int blockIndent = -1;
            int blockStart = 0;
            for (int idx = 0; idx < rawLines.size(); idx++) {
                String raw = rawLines.get(idx);
                if (block != null) {
                    int indent = leadingSpaces(raw);
                    String trimmed = raw.substring(indent);
                    if (indent < blockIndent || (indent == blockIndent && trimmed.isEmpty())) {
                        // 块结束
                        tokens.add(new Line(blockIndent, "<BLOCK>", false,
                                "BLOCK|" + block.text));
                        block = null;
                        if (indent < blockIndent && !trimmed.isEmpty()) {
                            tokens.add(toLine(indent, trimmed, idx));
                        }
                        continue;
                    }
                    if (trimmed.isEmpty()) {
                        block.text += "\n";
                    } else {
                        block.text = block.text.isEmpty() ? trimmed : block.text + "\n" + trimmed;
                    }
                    continue;
                }
                int indent = leadingSpaces(raw);
                String trimmed = raw.substring(indent);
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                Line line = toLine(indent, trimmed, idx);
                // 检测 `key: |` / `key: >` —— 这里只支持 `|`。
                if (line.scalarValue != null && (line.scalarValue.equals("|") || line.scalarValue.equals(">"))) {
                    block = new BlockString();
                    blockIndent = indent + 2; // 块串内容必须比 key 多缩至少 2 空格
                    blockStart = idx + 1;
                    // 块串 marker 自身不入栈，下一轮起进入块串模式
                    continue;
                }
                tokens.add(line);
            }
            if (block != null) {
                tokens.add(new Line(blockIndent, "<BLOCK>", false, "BLOCK|" + block.text));
            }
            return buildTree(tokens, source);
        }

        private static Line toLine(int indent, String trimmed, int idx) {
            // 列表项：以 "-" 起头
            if (trimmed.startsWith("- ")) {
                String rest = trimmed.substring(2).trim();
                // 如果列表项里有 "key: value"，那就是一个单键映射的列表项
                int colon = colonIndex(rest);
                if (colon >= 0) {
                    String key = unquote(rest.substring(0, colon).trim());
                    String value = stripQuotes(rest.substring(colon + 1).trim());
                    if (value.startsWith("[") && value.endsWith("]")) {
                        // 列表项的 value 是行内序列，例如 `- Name: [...]`。
                        return new Line(indent, key, true, value);
                    }
                    return new Line(indent, key, true, value);
                }
                return new Line(indent, "<item>", true, stripQuotes(rest));
            }
            if (trimmed.equals("-")) {
                return new Line(indent, "<item>", true, "");
            }
            // 映射行
            int colon = colonIndex(trimmed);
            if (colon < 0) {
                throw new IllegalArgumentException(
                        "第 " + (idx + 1) + " 行没有可解析的 key: " + trimmed);
            }
            String key = unquote(trimmed.substring(0, colon).trim());
            String value = restAfterColon(trimmed, colon + 1).trim();
            return new Line(indent, key, false, value);
        }

        /**
         * 吃完 ":" 后剩下的部分，可能是空（子键往下走）、值字符串、行内序列 [...]、块串 marker。
         */
        private static String restAfterColon(String line, int from) {
            if (from >= line.length()) return "";
            return line.substring(from);
        }

        private static List<String> splitLines(String text) {
            List<String> out = new ArrayList<>();
            int start = 0;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '\r') {
                    out.add(text.substring(start, i));
                    start = i + 1;
                    if (start < text.length() && text.charAt(start) == '\n') start++;
                } else if (c == '\n') {
                    out.add(text.substring(start, i));
                    start = i + 1;
                }
            }
            if (start < text.length()) {
                out.add(text.substring(start));
            }
            return out;
        }

        private static int leadingSpaces(String raw) {
            int n = 0;
            while (n < raw.length() && raw.charAt(n) == ' ') n++;
            return n;
        }

        private static int colonIndex(String trimmed) {
            boolean inSingle = false, inDouble = false;
            for (int i = 0; i < trimmed.length(); i++) {
                char c = trimmed.charAt(i);
                if (c == '\'' && !inDouble) inSingle = !inSingle;
                else if (c == '"' && !inSingle) inDouble = !inDouble;
                else if (c == ':' && !inSingle && !inDouble) return i;
            }
            return -1;
        }

        private static String unquote(String s) {
            if (s.length() >= 2) {
                if ((s.startsWith("\"") && s.endsWith("\""))
                        || (s.startsWith("'") && s.endsWith("'"))) {
                    return s.substring(1, s.length() - 1);
                }
            }
            return s;
        }

        private static String stripQuotes(String s) {
            return unquote(s);
        }

        /**
         * 把扁平 {@link Line} 列升成嵌套树。
         * 算法：按缩进堆栈分层。当前行的「缩进」必须 ≥ 栈顶元素的缩进 + 2。
         * - 行内值 / 空值：作为标量 / 子节点加到当前层；
         * - 序列项：聚合到当前键下，成为一个 LIST 节点。
         */
        private static Node buildTree(List<Line> lines, String source) {
            if (lines.isEmpty()) {
                return Node.ofMap(new LinkedHashMap<>());
            }
            // 栈 frame：保存「层级 → 当前累积节点」。
            Deque<Frame> stack = new ArrayDeque<>();
            // 虚拟根
            Frame rootFrame = new Frame(-1, Node.ofMap(new LinkedHashMap<>()));
            stack.push(rootFrame);

            for (Line line : lines) {
                // 1) 把缩进 ≤ 当前栈顶的行回退到正确的层。
                while (!stack.isEmpty() && stack.peek().indent >= line.indent) {
                    stack.pop();
                }
                if (stack.isEmpty()) {
                    throw new IllegalArgumentException(
                            "YAML 缩进错乱，源 " + source + "：缩进=" + line.indent);
                }
                Frame parent = stack.peek();
                Node parentNode = parent.node;

                if (line.isListItem) {
                    if (!parentNode.isList()) {
                        // 父若是 MAP：可能两种情形
                        //   a) 直接是空 MAP（如刚解析了 `key:`，还没子项）—— 自己升级为 LIST；
                        //   b) 里面最后一项是空 MAP —— 升级那一项为 LIST；
                        //   c) 里面最后一项已是 LIST —— 沿用。
                        if (parentNode.isMap()) {
                            if (parentNode.children.isEmpty()) {
                                // 整个父容器是空 MAP：自身替换为 LIST。
                                Node list = Node.ofList(new ArrayList<>());
                                // 把栈上对应帧的 node 替换掉
                                Frame parentFrame = stack.peek();
                                Frame replacement = new Frame(parentFrame.indent, list);
                                stack.pop();
                                stack.push(replacement);
                                parentNode = list;
                            } else {
                                Node tail = parentNode.lastValue();
                                if (tail == null) {
                                    throw new IllegalArgumentException(
                                            "YAML 序列项没有父列表，源 " + source
                                                    + "（父容器是 " + parentNode + "，缩进行=" + line.indent + "）");
                                }
                                if (tail.isMap() && tail.children.isEmpty()) {
                                    Node list = Node.ofList(new ArrayList<>());
                                    parentNode.replaceLastValue(list);
                                    parentNode = list;
                                } else if (tail.isList()) {
                                    parentNode = tail;
                                } else {
                                    throw new IllegalArgumentException(
                                            "YAML 序列项无可承接的父容器，源 " + source
                                                    + "（上一节点是 " + tail + "），缩进行=" + line.indent);
                                }
                            }
                        } else {
                            throw new IllegalArgumentException(
                                    "YAML 序列项没有父列表，源 " + source
                                            + "（父容器是 " + parentNode + "，缩进行=" + line.indent + "）");
                        }
                    }
                    Node newNode = materialize(line);
                    parentNode.list.add(newNode);
                    if (newNode.isMap()) {
                        stack.push(new Frame(line.indent, newNode));
                    }
                } else {
                    if (!parentNode.isMap()) {
                        throw new IllegalArgumentException(
                                "YAML 键值行的父节点不是 MAP，源 " + source);
                    }
                    Node newNode = materialize(line);
                    parentNode.children.put(line.key, newNode);
                    // 只有 MAP 才需要 push 让子行往里填；空值（key:）也不一定 LIST —— 见上。
                    if (newNode.isMap()) {
                        stack.push(new Frame(line.indent, newNode));
                    }
                }
            }

            return rootFrame.node;
        }

        /**
         * 把 {@code Line} 变成 Node。
         *  - 标量值（"foo"）：直接是标量；
         *  - 空值：空 MAP（让子行往里堆）；
         *  - 行内序列（"[a, b]"）：LIST；
         *  - 块串（"BLOCK|..."）：标量但展开过的。
         *
         * <p>列表项的特殊分支：当列表项是 {@code - key: value}（而非裸 {@code - item}），
         * 构建一个「单键 MAP」作为列表的元素，便于后续的 description / url 这类子键
         * 继续往里挂。
         */
        private static Node materialize(Line line) {
            String v = line.scalarValue;
            if (line.isListItem && !line.key.equals("<item>")) {
                // 单键映射的列表项：构建单键 MAP（即便 value 为空）。
                Map<String, Node> child = new LinkedHashMap<>();
                child.put(line.key, materializeScalar(v));
                return Node.ofMap(child);
            }
            return materializeScalar(v);
        }

        private static Node materializeScalar(String v) {
            if (v == null) {
                return Node.ofMap(new LinkedHashMap<>());
            }
            if (v.isEmpty()) {
                return Node.ofMap(new LinkedHashMap<>());
            }
            if (v.startsWith("[") && v.endsWith("]")) {
                return Node.ofList(parseInlineList(v));
            }
            if (v.startsWith("BLOCK|")) {
                return Node.ofScalar(v.substring("BLOCK|".length()));
            }
            return Node.ofScalar(v);
        }

        private static List<Node> parseInlineList(String text) {
            String inside = text.substring(1, text.length() - 1).trim();
            if (inside.isEmpty()) {
                return Collections.emptyList();
            }
            List<Node> items = new ArrayList<>();
            int depth = 0;
            int start = 0;
            for (int i = 0; i < inside.length(); i++) {
                char c = inside.charAt(i);
                if (c == '[' || c == '{') depth++;
                else if (c == ']' || c == '}') depth--;
                else if (c == ',' && depth == 0) {
                    items.add(parseInlineListItem(inside.substring(start, i).trim()));
                    start = i + 1;
                }
            }
            items.add(parseInlineListItem(inside.substring(start).trim()));
            return items;
        }

        private static Node parseInlineListItem(String raw) {
            // 形如 "Name: []" —— 解析成单键 MAP。
            int colon = colonIndex(raw);
            if (colon >= 0 && raw.length() > colon + 1) {
                String rest = raw.substring(colon + 1).trim();
                if (rest.startsWith("[") && rest.endsWith("]")) {
                    String key = unquote(raw.substring(0, colon).trim());
                    Map<String, Node> map = new LinkedHashMap<>();
                    map.put(key, Node.ofList(parseInlineList(rest)));
                    return Node.ofMap(map);
                }
            }
            return Node.ofScalar(unquote(raw));
        }

        private static final class BlockString {
            String text = "";
        }

        /** 解析后的扁平行：包含缩进、key、是否列表项、scalar 值或 { "" / "[...]" / "BLOCK|..." }。 */
        private static final class Line {
            final int indent;
            final String key;
            final boolean isListItem;
            final String scalarValue;

            Line(int indent, String key, boolean isListItem, String scalarValue) {
                this.indent = indent;
                this.key = key;
                this.isListItem = isListItem;
                this.scalarValue = scalarValue;
            }
        }

        /** 用 ArrayDeque 当栈，写个 push / pop / peek 套件。 */
        private static final class Frame {
            final int indent;
            final Node node;
            Frame(int indent, Node node) {
                this.indent = indent;
                this.node = node;
            }
        }
    }
}
