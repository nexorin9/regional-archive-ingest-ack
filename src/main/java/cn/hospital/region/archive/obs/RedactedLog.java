package cn.hospital.region.archive.obs;

import cn.hospital.region.archive.ledger.LedgerRedaction;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 脱敏日志出口 —— <b>患者可识别明文进不来的一条路</b>。
 *
 * <h2>为什么不用「大家记得别打明文」当纪律</h2>
 * <p>本产品的日志要回答的问题是「这一条为什么没落地、卡在哪、谁点的确认」，
 * 而这些问题的答案全都紧挨着患者身份：控制号、主键、姓名、证件号。
 * 现场排障时最容易发生的一次改动是「把这个字段名临时换成字段值，看一眼到底对不对」——
 * 改完当场看对了，改完就忘了，<b>不留痕、不报错、测试照样绿</b>。
 * 因此本类不提供「拼一条日志字符串」这种用法：患者字段必须先经过
 * {@link RedactedValue} 变形，再作为<b>带类型</b>的值进 {@link Handle}。
 *
 * <h2>三道闸，形态各异</h2>
 * <ol>
 *   <li><b>构造期</b>：{@link #redacted(String, String)} 拿到的原文只存在于
 *       {@link RedactedValue#plaintext()} 这个包内访问器里，渲染出来的是摘要；</li>
 *   <li><b>渲染期</b>：{@link Handle} 把一行拼好之后，拿这一行里所有
 *       {@link RedactedValue} 记住的原文（加上调用方显式声明的额外明文）
 *       逐个回搜。<b>搜到就抛异常、不写日志</b> —— 宁可这一条事件少一条日志，
 *       也不能把姓名写进文件；</li>
 *   <li><b>事后扫描</b>：{@link PlaintextScanner} 对已落盘的日志与导出文件做形态扫描
 *       （身份证校验位、手机号边界、已知明文清单），作为独立于日志框架的一道反证。</li>
 * </ol>
 *
 * <h2>为什么要单独造一个而不是复用 {@link cn.hospital.region.archive.ledger.LedgerRedaction}</h2>
 * <p>{@code LedgerRedaction} 是<b>出口变形规则</b>（CSV 导出用），它不关心值是怎么进来的；
 * 而日志这一层真正的风险在<b>入口</b>：调用点手里那个字符串是不是患者信息。
 * 两层都做，规则（{@code digest8} / {@code keyFragment}）仍只有
 * {@link LedgerRedaction} 一处实现，不存在两套「大概差不多」的脱敏。
 *
 * <h2>不做什么</h2>
 * <p><b>不拦中文</b>。中文说明、错误码标签、台账状态名进日志是本产品的正常内容，
 * 一律替换成星号会让日志失去可读性。真正要挡的是「可识别信息」——
 * 姓名、证件号、手机号，以及主键的完整形态。
 */
public final class RedactedLog {

    /** 患者字段值的<b>标签前缀</b>：{@code name_digest8=ab12cd34}，看标签就知道这是摘要不是原文。 */
    public static final String LABEL_NAME = "name_digest8";

    public static final String LABEL_ID_CARD = "id_digest8";

    public static final String LABEL_PHONE = "phone_digest8";

    public static final String LABEL_KEY = "key_fragment";

    private RedactedLog() {
    }

    /**
     * 一个<b>已经变形过</b>的字段值。
     *
     * <p>它同时持有变形结果与原文：渲染只用前者，自检用后者。
     * 两者都不给公开的「原样取值」出口——要拿原文的调用点只能调 {@link #plaintext()}，
     * 而那个方法的用途在类注释里已经限定为「渲染期自检」。
     *
     * @param label     字段标签（如 {@link #LABEL_NAME}）
     * @param rendered  渲染值（摘要或主键片段）
     * @param plaintext 原文；仅供 {@link Handle} 回搜自检，{@code null}/空串表示无可检原文
     */
    public static final class RedactedValue {

        private final String label;
        private final String rendered;
        private final String plaintext;

        private RedactedValue(String label, String rendered, String plaintext) {
            this.label = Objects.requireNonNull(label, "字段标签不得为 null");
            this.rendered = Objects.requireNonNull(rendered, "渲染值不得为 null");
            this.plaintext = plaintext;
        }

        /** 字段标签，进日志的 {@code label=rendered} 形态。 */
        public String label() {
            return label;
        }

        /** 渲染值：摘要、主键片段或原样文本，<b>绝不是患者明文</b>（除非调用方用 {@link #text} 且传错了值）。 */
        public String rendered() {
            return rendered;
        }

        /**
         * 原文，<b>仅供渲染期自检</b>。
         *
         * <p>与 {@link #rendered()} 分成两个访问器而不是只留一个，是为了让「谁在读原文」
         * 在代码里看得见：读它的地方只有 {@link Handle} 的回搜，以及
         * {@link PlaintextScanTest} 一类的自检用例。
         */
        public String plaintext() {
            return plaintext;
        }

        @Override
        public String toString() {
            return label + "=" + rendered;
        }
    }

    /**
     * 患者字段：姓名 → SHA-256 前 8 位。
     *
     * <p>取摘要而非掩码（{@code 张*伟}）是有意的：掩码保留了姓与名的首尾字，
     * 而中文姓名里「张」这样的高频姓配合出生日期，在小范围里足以反推出具体是谁；
     * 摘要不可逆，对账时需要的性质只是「同一个人每次导出摘要相同」。
     */
    public static RedactedValue name(String rawName) {
        return patient(LABEL_NAME, rawName);
    }

    /** 患者字段：身份证号 → SHA-256 前 8 位。 */
    public static RedactedValue idCard(String rawIdCard) {
        return patient(LABEL_ID_CARD, rawIdCard);
    }

    /**
     * 患者字段：手机号 → SHA-256 前 8 位。
     *
     * <p>同样走摘要而不是「前 3 后 4」：本产品的手机号只出现在极少数报文里，
     * 而 {@code PlaintextScanner} 的手机号规则靠边界识别，
     * 一个 {@code 138****8000} 形态的串反而会被它的「已知明文」通道当成待查项。
     */
    public static RedactedValue phone(String rawPhone) {
        return patient(LABEL_PHONE, rawPhone);
    }

    /**
     * 患者字段：主键 → 首尾片段（{@code PAT***0001}）。
     *
     * <p>与前三者不同，主键<b>不</b>走摘要：它是业务标识不是身份标识，
     * 信息员要拿它去平台侧或 HIS 里定位那一条，抹成哈希就没法干活了。
     * 口径与台账导出完全一致（{@link LedgerRedaction#keyFragment}）。
     */
    public static RedactedValue key(String rawKey) {
        if (isBlank(rawKey)) {
            return new RedactedValue(LABEL_KEY, "", null);
        }
        return new RedactedValue(LABEL_KEY, LedgerRedaction.keyFragment(rawKey), rawKey);
    }

    private static RedactedValue patient(String label, String raw) {
        if (isBlank(raw)) {
            return new RedactedValue(label, "", null);
        }
        return new RedactedValue(label, LedgerRedaction.digest8(raw), raw);
    }

    /**
     * 非患者字段：原样进日志。
     *
     * <p>用于状态名、错误码、批次号、耗时这类运维可读性必需的内容。
     * <b>它不提供任何脱敏</b>——传进来的就是日志里出现的。误用（例如把姓名从这里塞进来）
     * 会被同一行里的 {@link RedactedValue} 回搜抓到，但前提是<b>同一行</b>里也有那个患者字段；
     * 这也是 {@link PlaintextScanner} 必须独立存在的原因。
     */
    public static RedactedValue text(String label, String value) {
        return new RedactedValue(label, value == null ? "" : value, null);
    }

    /** 便捷构造：非患者字段（标签与取值分开给，避免调用点写成拼接串）。 */
    public static RedactedValue text(String label, long value) {
        return new RedactedValue(label, String.valueOf(value), null);
    }

    /** 便捷构造：布尔字段。渲染为 {@code true}/{@code false}，与 {@code AuditTextGuard} 的 ASCII 口径一致。 */
    public static RedactedValue text(String label, boolean value) {
        return new RedactedValue(label, String.valueOf(value), null);
    }

    /** 取一个类绑定的日志句柄。 */
    public static Handle forClass(Class<?> type) {
        return new Handle(Logger.getLogger(type.getName()));
    }

    /** 取一个具名日志句柄（不用类的场景，如适配层）。 */
    public static Handle forName(String loggerName) {
        return new Handle(Logger.getLogger(Objects.requireNonNull(loggerName, "日志名不得为 null")));
    }

    /**
 * 一条事件的渲染句柄。
 *
 * <p><b>一个句柄只服务一条事件</b>：{@link #render} / {@link #log} 无论成功还是抛错，
 * 都会把已挂的字段清空。理由很实际——句柄若跨事件累积字段，那么
 * 「第一条事件因明文被拒」之后，这个句柄就永远带着那条明文，
 * <b>后面每一条事件都会被同一条陈旧明文拦下</b>，日志器就此哑掉。
 * 宁可要求调用点每次 {@code forClass(x).with(...).info(...)} 现取一个句柄。
 *
 * <p>{@link #info} / {@link #warning} / {@link #severe} 三个出口走同一条渲染路径，
 * 因此<b>不存在「某一级忘了自检」这种可能</b>。
 */
    public static final class Handle {

        private final Logger logger;
        private final List<RedactedValue> values = new ArrayList<>();
        private final List<String> extraSecrets = new ArrayList<>();

        private Handle(Logger logger) {
            this.logger = logger;
        }

        /** 挂一个字段值；返回本句柄以便链式书写。 */
        public Handle with(RedactedValue value) {
            values.add(Objects.requireNonNull(value, "字段值不得为 null"));
            return this;
        }

        /**
         * 挂一个<b>收集器</b>：本句柄此后落下的每一行都会同时复制一份给它。
         *
         * <p>存在的理由是「拒绝落盘」这条红线必须可验证：单看 {@link Logger} 无法区分
         * 「这一行写成功了」与「这一行被自检拦下了所以压根没写」，
         * 而这两者恰恰是本类最需要被断言的两种结果。
         * 生产代码不挂收集器；用例挂一个即可断言「被拒的行一条都没进去」。
         */
        public Handle withCapture(java.util.logging.Handler handler) {
            logger.addHandler(Objects.requireNonNull(handler, "收集器不得为 null"));
            return this;
        }

        /** 挂多个字段值。 */
        public Handle with(RedactedValue... vs) {
            for (RedactedValue v : vs) {
                with(v);
            }
            return this;
        }

        /** 挂一组非患者字段（{@code label → value}）。 */
        public Handle with(Map<String, String> fields) {
            fields.forEach((k, v) -> with(text(k, v)));
            return this;
        }

        /**
         * 声明<b>本行还会以别的形态出现</b>的明文，回搜时一并检查。
         *
         * <p>典型用途：调用点手上有一份原文要放进某个复合值里（目前产品内没有这种调用），
         * 或者要把原文喂给下游做别的事。声明之后它在本行的任何形态出现都会被拦下。
         */
        public Handle alsoWatching(String plaintext) {
            if (!isBlank(plaintext)) {
                extraSecrets.add(plaintext);
            }
            return this;
        }

        /**
         * 只渲染不落日志，供测试与 {@link PlaintextScanner} 的干跑校验使用。
         *
         * <p>渲染完即清空已挂字段（见本类注释的「一个句柄只服务一条事件」）。
         */
        public String render(String event) {
            StringBuilder sb = new StringBuilder();
            sb.append("event=").append(event);
            for (RedactedValue v : values) {
                sb.append(' ').append(v.label()).append('=').append(v.rendered());
            }
            String line = sb.toString();
            // 清空放在 finally：自检抛异常时同样要清，否则这条被拒的明文会一直挂在句柄上，
            // 把后面每一条事件都拦下来——一个拒绝机制不该把日志器变成哑巴。
            try {
                assertClean(line);
                return line;
            } finally {
                values.clear();
                extraSecrets.clear();
            }
        }

        /** INFO。 */
        public void info(String event) {
            log(Level.INFO, event);
        }

        /** WARNING。 */
        public void warning(String event) {
            log(Level.WARNING, event);
        }

        /** SEVERE。 */
        public void severe(String event) {
            log(Level.SEVERE, event);
        }

        /** 指定级别落一条（出口只有一个，见 {@link #log}）。 */
        public void log(Level level, String event) {
            logger.log(Objects.requireNonNull(level, "级别不得为 null"), render(event));
        }

        /**
         * 渲染期自检：这一行里出现了任何一段被记住的原文就<b>拒绝落日志</b>。
         *
         * @throws IllegalStateException 行内出现了明文
         */
        private void assertClean(String line) {
            List<String> secrets = new ArrayList<>(extraSecrets);
            for (RedactedValue v : values) {
                if (!isBlank(v.plaintext())) {
                    secrets.add(v.plaintext());
                }
            }
            for (String secret : secrets) {
                if (line.contains(secret)) {
                    throw new IllegalStateException(
                            "日志行里出现了未脱敏的明文（长度 " + secret.length()
                                    + "），本条事件已拒绝落盘；值本身不打印");
                }
            }
        }
    }

    /**
     * 把一组 {@code label → 值} 渲染成有序的 {@code k=v} 片段，供 CLI 与报告复用。
     *
     * <p>键按<b>插入序</b>输出而不是字典序：报表是要给人对着看的，
     * 「批次在前、状态在中、耗时在后」的顺序比字母序好读。
     */
    public static String renderPairs(Map<String, String> pairs) {
        Map<String, String> copy = new LinkedHashMap<>(pairs);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : copy.entrySet()) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    /** 把一组字段转成 {@code label → 值} 的普通 Map，供上面那个渲染方法使用。 */
    public static Map<String, String> toPairs(RedactedValue... vs) {
        Map<String, String> out = new LinkedHashMap<>();
        for (RedactedValue v : Arrays.asList(vs)) {
            out.put(v.label(), v.rendered());
        }
        return out;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}