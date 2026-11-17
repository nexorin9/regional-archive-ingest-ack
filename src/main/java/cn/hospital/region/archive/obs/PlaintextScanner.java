package cn.hospital.region.archive.obs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 明文扫描器 —— 「日志与导出文件里有没有患者可识别明文」的<b>可复核答案</b>。
 *
 * <h2>为什么扫描器必须存在，而不是只靠出口自检</h2>
 * <p>{@link RedactedLog} 的渲染期回搜与 {@link cn.hospital.region.archive.ledger.LedgerRedaction}
 * 的出口比对都只在<b>自己那条路径</b>上生效。产品里有 30 多处直接用
 * {@code java.util.logging} 打日志（源产品的连接层语义沿用下来的），
 * 任何一处改了句式、或者某个 {@code System.err.println} 混进来，
 * 上面的两道闸都看不见。<b>扫描是对这两道闸的独立反证</b>：它不关心代码怎么写的，
 * 只看落盘的文件里有没有。
 *
 * <h2>为什么必须做校验位与边界判定（本类最要紧的设计）</h2>
 * <p>最直觉的写法是「搜 18 位数字」或「搜 11 位数字」。在本项目的真实日志上实测：
 * 宽松的 {@code \d{17}[0-9X]} 在 {@code ../logs} 的 86 个文件里报出 <b>44 处命中</b>，
 * 逐条查下去<b>全部是假阳性</b>——来源是 JUnit 临时目录名
 * （{@code junit13822913855679018662/…}）与退避时刻的 epoch 毫秒
 * （{@code nextAtMs=1792544400000}）。一个天天误报的扫描器等于没有扫描器：
 * 值班的人学会无视它之后，真出现一条姓名明文也不会有人看第二眼。
 *
 * <p>因此本类用三条收紧规则把假阳性压到零：
 * <ol>
 *   <li><b>数字边界</b>：前后不能再挨着数字或字母（{@code (?<![0-9A-Za-z])…(?![0-9A-Za-z])}）。
 *       这一条直接干掉 JUnit 临时目录名与 epoch 毫秒——它们都是<b>更长数字串的子串</b>；</li>
 *   <li><b>身份证校验位</b>：18 位形态还必须过 ISO 7064 MOD 11-2 校验（{@link #isChecksumValidIdCard}）。
 *       随机 18 位数字串过不了校验，而真实身份证号一定过；</li>
 *   <li><b>显式明文清单</b>：姓名这类<b>没有形态可循</b>的信息，只能靠调用方给出清单
 *       （测试语料的姓名表、导出自检用的清单）。这是本类唯一的必填参数，
 *       理由写在 {@link #ScanRequest#withKnownPlaintext} 上。</li>
 * </ol>
 *
 * <h2>为什么不扫描中文</h2>
 * <p>本产品的日志与 CSV 里大量出现中文<b>说明文字</b>（错误码标签、状态名、处置建议）。
 * 一条「中文即命中」的规则会把整个报表打成筛子。因此本类只扫<b>可识别信息</b>：
 * 证件号（校验位）、手机号（边界 + 号段）、调用方给出的明文清单。
 * 中文脱敏由出口的两道闸负责（{@link RedactedLog} 的类型化字段、
 * {@link cn.hospital.region.archive.model.AuditTextGuard} 的非 ASCII 规则）。
 */
public final class PlaintextScanner {

    /** 单次扫描最多读多少字节进内存保护上限（默认 32MB）。 */
    public static final long MAX_FILE_BYTES = 32L * 1024 * 1024;

    /** 扫描的文件扩展名白名单：日志与导出产物。 */
    private static final List<String> SCANNED_SUFFIXES =
            List.of(".log", ".txt", ".csv", ".out", ".err");

    /** 跳过的目录名：构建产物与依赖缓存里没有本产品的日志，却有几 MB 的第三方文本。 */
    private static final List<String> SKIPPED_DIRS =
            List.of("target", "node_modules", ".git", "src", "docs");

    // ---- 形态规则 ------------------------------------------------------------

    /**
     * 18 位身份证：前后不挨数字或字母，且末位可为 {@code X}。
     *
     * <p>{@code (?<![0-9A-Za-z])} 这条否定回顾是关键：JUnit 临时目录
     * {@code junit13822913855679018662} 里那 18 位数字的左邻是字母 {@code t}，
     * epoch 毫秒里的 11 位串左邻也是数字，两类假阳性都被它排除。
     */
    private static final Pattern ID_CARD_18 =
            Pattern.compile("(?<![0-9A-Za-z])\\d{17}[0-9Xx](?![0-9A-Za-z])");

    /** 15 位旧版身份证：同样要求边界。 */
    private static final Pattern ID_CARD_15 =
            Pattern.compile("(?<![0-9A-Za-z])\\d{15}(?![0-9A-Za-z])");

    /**
     * 11 位手机号：中国大陆号段 {@code 1[3-9]} 开头，同样要求边界。
     *
     * <p>号段约束把「恰好 11 位数字」的一批误报排除掉：批次号、工单号、
     * 退避间隔这类运营数字常落在 11 位，但极少同时以 {@code 13}–{@code 19} 开头。
     */
    private static final Pattern PHONE_11 =
            Pattern.compile("(?<![0-9A-Za-z])1[3-9]\\d{9}(?![0-9A-Za-z])");

    // ISO 7064 MOD 11-2 加权因子（GB 11643-1999 附录 A 的 17 位加权因子）。
    private static final int[] ID_WEIGHTS = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};

    /** 校验位取值表：余数 → 字符。 */
    private static final String ID_CHECK_CHARS = "10X98765432";

    // =====================================================================
    // 扫描请求与结果
    // =====================================================================

    /**
     * 一次扫描的请求。
     *
     * <p><b>明文清单是必填的</b>（{@link #withKnownPlaintext}）。理由不是 API 洁癖：
     * 姓名没有形态可循，不给清单就等于只扫了证件号与手机号，
     * 而本产品最常漏的恰恰是姓名——T19/T28 各自做的出口自检都是拿语料姓名逐个搜，
     * 本类是把那件事变成一条命令。
     *
     * <h2>两条通道的最小长度<b>刻意不同</b></h2>
     * <p>{@link cn.hospital.region.archive.ledger.LedgerRedaction#assertNoPlaintext} 会跳过
     * 短于 8 位的值——那道过滤是为了挡「把 {@code "1"} 拿去全文搜必然在计数列撞上一次」这类假阳性。
     * 但<b>那份清单里的多数值恰恰是中文姓名（2–4 个字），会被规则整条跳过</b>，
     * 于是「拿姓名逐个搜产物」这条纪律在<b>运行时</b>其实一行都没真正搜过
     * （T19/T28 的<b>用例</b>是直接 {@code csv.contains(secret)}，所以测试仍然是绿的——
     * 用例守住的是产物，运行时那道闸守的是导出那一刻，两者不是同一处）。
     *
     * <p>本类的清单通道<b>不设这道过滤</b>：调用方已经明确声明「这个值就是患者明文」，
     * 那么产物里出现它就<b>是</b>命中，不存在「恰好撞上一次」的问题——
     * 一个两字中文姓名出现在日志里，本身就是要处理的事。
     * 长度过滤只属于<b>形态通道</b>（证件号 / 手机号），那里没有这份先验知识。
     */
    public static final class ScanRequest {

        /** 清单通道的最小长度：单个字的中文姓名也可能是真实姓名，不设两位下限。 */
        public static final int MIN_KNOWN_PLAINTEXT_CHARS = 1;

        private final List<Path> roots = new ArrayList<>();
        private final List<String> knownPlaintexts = new ArrayList<>();
        private boolean skipFilesAboveMaxBytes = true;

        /** 加一个待扫描的文件或目录。 */
        public ScanRequest addRoot(Path path) {
            roots.add(Objects.requireNonNull(path, "扫描根不得为 null"));
            return this;
        }

        /**
         * 登记一份<b>不该出现在产物里</b>的明文（姓名、证件号、手机号……）。
         *
         * <p>只丢掉空白值；其余全部保留。重复值只登记一次——同一份产物里同一个姓名
         * 出现在十行，报十处命中只会淹没真正的位置，且报告里看不出是哪一条规则命中的。
         */
        public ScanRequest withKnownPlaintext(Collection<String> plaintexts) {
            Objects.requireNonNull(plaintexts, "明文清单不得为 null");
            for (String s : plaintexts) {
                if (s != null && s.strip().length() >= MIN_KNOWN_PLAINTEXT_CHARS
                        && !knownPlaintexts.contains(s)) {
                    knownPlaintexts.add(s);
                }
            }
            return this;
        }

        /** 登记单个明文。 */
        public ScanRequest withKnownPlaintext(String plaintext) {
            return withKnownPlaintext(List.of(plaintext == null ? "" : plaintext));
        }

        /**
         * 超过 {@link #MAX_FILE_BYTES} 的文件：{@code true} 跳过并在结果里如实登记，
         * {@code false} 整份读入（可能吃内存）。<b>默认跳过</b>——
         * 一个不报告跳过的扫描器会给人「全扫过了」的错觉。
         */
        public ScanRequest skipFilesAboveMaxBytes(boolean skip) {
            this.skipFilesAboveMaxBytes = skip;
            return this;
        }

        List<Path> roots() {
            return List.copyOf(roots);
        }

        List<String> knownPlaintexts() {
            return List.copyOf(knownPlaintexts);
        }

        boolean skipOversize() {
            return skipFilesAboveMaxBytes;
        }

        /**
         * 直接跑一次扫描，把受检异常折成 unchecked 抛出。
         *
         * <p>给链式写法用（{@code new ScanRequest().addRoot(dir).withKnownPlaintext(...).scanOrThrow()}）。
         * 折成 unchecked <b>不改变语义</b>：读文件失败仍然会以异常形态冒出去，
         * 绝不会变成一份「干净」报告——那正是本类最不该出的那种结论。
         */
        public Report scanOrThrow() {
            try {
                return PlaintextScanner.scan(this);
            } catch (IOException e) {
                throw new IllegalStateException("扫描失败（扫不出来不等于干净）：" + e.getMessage(), e);
            }
        }
    }

    /**
     * 一处命中。
     *
     * @param kind     命中类别
     * @param file     命中所在文件（相对扫描根的路径）
     * @param lineNo   行号（1 起）
     * @param excerpt  该行的<b>脱敏片段</b>：命中值本身已被替换成 {@code ***}，
     *                 前后各留少量上下文用于人工定位
     */
    public record Hit(Kind kind, String file, int lineNo, String excerpt) {
    }

    /** 命中类别。 */
    public enum Kind {
        /** 18 位身份证形态且校验位成立。 */
        ID_CARD_18("18位身份证号"),
        /** 15 位旧版身份证形态。 */
        ID_CARD_15("15位旧版身份证号"),
        /** 11 位手机号形态。 */
        PHONE_11("11位手机号"),
        /** 调用方登记的明文之一（典型是姓名）。 */
        KNOWN_PLAINTEXT("已知明文");

        private final String zhLabel;

        Kind(String zhLabel) {
            this.zhLabel = zhLabel;
        }

        /** 人可读类别名，进 CLI 输出与失败报告。 */
        public String zhLabel() {
            return zhLabel;
        }
    }

    /**
     * 一次扫描的完整结论。
     *
     * @param filesScanned 实际读过内容的文件数
     * @param filesSkipped  因超长等原因跳过的文件数
     * @param bytesScanned 读入的总字节数
     * @param hits         全部命中
     * @param knownCount   本次登记的明文条数（少于 8 位的已被忽略，如实反映在这里）
     */
    public record Report(int filesScanned, int filesSkipped, long bytesScanned,
                         List<Hit> hits, int knownCount) {

        public Report {
            hits = List.copyOf(hits);
        }

        /** 无命中——这才是本产品期望的结论。 */
        public boolean clean() {
            return hits.isEmpty();
        }

        /** 按类别计数，进报表与 CLI 输出。 */
        public long countOf(Kind kind) {
            return hits.stream().filter(h -> h.kind() == kind).count();
        }

        /** 人可读的三行结论。 */
        public String summary() {
            if (clean()) {
                return "明文扫描：干净。已扫 " + filesScanned + " 个文件 / " + bytesScanned
                        + " 字节（跳过 " + filesSkipped + " 个），明文命中 0 处"
                        + (filesSkipped > 0 ? "；注意有 " + filesSkipped + " 个文件被跳过" : "");
            }
            StringBuilder sb = new StringBuilder();
            sb.append("明文扫描：发现 ").append(hits.size()).append(" 处明文（已扫 ")
                    .append(filesScanned).append(" 个文件 / ").append(bytesScanned)
                    .append(" 字节，跳过 ").append(filesSkipped).append(" 个）");
            for (Kind k : Kind.values()) {
                long n = countOf(k);
                if (n > 0) {
                    sb.append('\n').append("  ").append(k.zhLabel()).append("：").append(n).append(" 处");
                }
            }
            return sb.toString();
        }
    }

    private PlaintextScanner() {
    }

    /**
     * 执行一次扫描。
     *
     * @throws IOException 目录遍历或读文件失败；<b>不吞异常</b>——扫不出来不是「干净」
     */
    public static Report scan(ScanRequest request) throws IOException {
        Objects.requireNonNull(request, "扫描请求不得为 null");
        List<Hit> hits = new ArrayList<>();
        int scanned = 0;
        int skipped = 0;
        long bytes = 0L;

        for (Path root : request.roots()) {
            if (!Files.exists(root)) {
                // 不存在的根按「跳过」如实计数，而不是当成干净：CI 上路径写错时
                // 一次静默跳过会让整条断言变成永远为真的空断言。
                skipped++;
                continue;
            }
            if (Files.isRegularFile(root)) {
                ScanOutcome outcome = scanFile(root, root.getFileName().toString(), request, hits);
                scanned += outcome.scanned() ? 1 : 0;
                skipped += outcome.skipped() ? 1 : 0;
                bytes += outcome.bytes();
                continue;
            }
            for (Path file : walk(root)) {
                if (!isScannable(file)) {
                    continue;
                }
                ScanOutcome outcome = scanFile(file, relativize(root, file), request, hits);
                scanned += outcome.scanned() ? 1 : 0;
                skipped += outcome.skipped() ? 1 : 0;
                bytes += outcome.bytes();
            }
        }

        hits.sort(Comparator.comparing(Hit::file).thenComparingInt(Hit::lineNo)
                .thenComparing(h -> h.kind().name()));
        return new Report(scanned, skipped, bytes, hits, request.knownPlaintexts().size());
    }

    /** 一次文件的扫描结果三元组（读过 / 跳过 / 字节数）。 */
    private record ScanOutcome(boolean scanned, boolean skipped, long bytes) {

        static final ScanOutcome SKIPPED = new ScanOutcome(false, true, 0L);
    }

    private static ScanOutcome scanFile(Path file, String displayName, ScanRequest request,
                                         List<Hit> hits) throws IOException {
        // 清单按长度降序：长值先替换，短值（如两字姓名）后替换，
        // 否则先替换短值会在原串里造出新的、能与清单另一条对上的组合。
        List<String> knownLongestFirst = new ArrayList<>(request.knownPlaintexts());
        knownLongestFirst.sort(java.util.Comparator.comparingInt(String::length).reversed());
        long size = Files.size(file);
        if (size > MAX_FILE_BYTES && request.skipOversize()) {
            return ScanOutcome.SKIPPED;
        }
        // 二进制文件（编译产物里偶尔混进来的 .class / 图片）读成 UTF-8 会得到一片替换字符，
        // 逐行扫没有意义还拖慢；用扩展名白名单在前面已经挡掉大部分，这里再兜一层。
        String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);

        String[] lines = text.split("\r\n|\n|\r", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) {
                continue;
            }
            int lineNo = i + 1;
            Matcher m18 = ID_CARD_18.matcher(line);
            while (m18.find()) {
                if (isChecksumValidIdCard(m18.group())) {
                    hits.add(new Hit(Kind.ID_CARD_18, displayName, lineNo,
                            excerpt(line, m18.start(), m18.end(), request.knownPlaintexts())));
                }
            }
            Matcher m15 = ID_CARD_15.matcher(line);
            while (m15.find()) {
                hits.add(new Hit(Kind.ID_CARD_15, displayName, lineNo,
                        excerpt(line, m15.start(), m15.end(), request.knownPlaintexts())));
            }
            Matcher mp = PHONE_11.matcher(line);
            while (mp.find()) {
                hits.add(new Hit(Kind.PHONE_11, displayName, lineNo,
                        excerpt(line, mp.start(), mp.end(), request.knownPlaintexts())));
            }
            for (String secret : knownLongestFirst) {
                int at = line.indexOf(secret);
                if (at >= 0) {
                    // 命中值可能被更大的数字块包着（出生日期内嵌在证件号里是常见形态），
                    // 因此把打码范围<b>扩到所在数字块的两端</b>，
                    // 否则「报出一段出生日期」会顺带把整个证件号的前后段留在片段里。
                    int[] span = expandToDigitRun(line, at, at + secret.length());
                    hits.add(new Hit(Kind.KNOWN_PLAINTEXT, displayName, lineNo,
                            excerpt(line, span[0], span[1], request.knownPlaintexts())));
                }
            }
        }
        return new ScanOutcome(true, false, size);
    }

    /**
     * 命中的可读片段：命中值替换成 {@code ***}，前后各留 {@value #EXCERPT_CONTEXT} 字符。
     *
     * <p><b>片段里其余的可识别信息也一并屏蔽</b>，而且是<b>两类都屏蔽</b>：
     * <ul>
     *   <li>清单通道的明文：同一行写着「证件=310101… 姓名=张伟」时，
     *       身份证那一处的前后文正好把姓名带了出来；</li>
     *   <li><b>形态通道的命中</b>：实测里最典型的一例是
     *       「证件=310101198505121563 电话=13800138000」——报身份证那一处时，
     *       前后 16 字窗口把<b>手机号原文</b>完整带了出来，报告当场变成一份患者明细副本。</li>
     * </ul>
     * 所以片段拼好之后，先跑一遍形态规则、再跑一遍清单，两类都替换掉。
     * 顺序刻意是「形态在前」：形态命中已经带校验位判定，宁可多遮一点。
     */
    private static String excerpt(String line, int start, int end, List<String> known) {
        int from = Math.max(0, start - EXCERPT_CONTEXT);
        int to = Math.min(line.length(), end + EXCERPT_CONTEXT);
        String prefix = from > 0 ? "…" : "";
        String suffix = to < line.length() ? "…" : "";
        String out = prefix + line.substring(from, start) + MASK
                + line.substring(end, to) + suffix;
        out = maskShapes(out);
        for (String secret : known) {
            if (secret != null && !secret.isEmpty() && out.contains(secret)) {
                out = out.replace(secret, MASK);
            }
        }
        return out;
    }

    /**
     * 把片段里所有<b>长度 ≥ {@value #MIN_DIGIT_RUN_TO_MASK} 的连续数字块</b>整块替换成 {@code ***}。
     *
     * <p>为什么按「数字块」而不是按形态逐条替换：形态规则（18 位证件 / 11 位手机）要求
     * <b>完整的、两侧有边界的</b>串，而片段恰恰是<b>被窗口截断过</b>的——
     * 实测里出现过两处残留：
     * <ul>
     *   <li>报一个<b>内嵌在证件号里的出生日期</b>（清单通道命中）时，
     *       证件号的前 6 位与后 4 位原样留在片段里，等于把号报了一半出来；</li>
     *   <li>报<b>紧邻窗口右边界</b>的手机号时，只剩前 8 位在窗口内，
     *       「完整 11 位」这条规则因此不成立，一个字都没被替换。</li>
     * </ul>
     * 阈值取 7：短于 7 位的数字块（计数、耗时、批次序号、日）在报告里是定位信息，
     * 全遮会让报告失去用处；7 位以上则只有身份证、手机号、epoch 毫秒与订单号，
     * 这些正是不能出现在片段里的。
     */
    private static String maskShapes(String text) {
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') {
                out.append(c);
                i++;
                continue;
            }
            int end = i;
            while (end < text.length() && text.charAt(end) >= '0' && text.charAt(end) <= '9') {
                end++;
            }
            // 末尾一位若是 X/x（如末位为 X 的身份证），并进同一块
            int runEnd = end;
            if (runEnd < text.length() && (text.charAt(runEnd) == 'X' || text.charAt(runEnd) == 'x')) {
                runEnd++;
            }
            if (runEnd - i >= MIN_DIGIT_RUN_TO_MASK) {
                out.append(MASK);
            } else {
                out.append(text, i, runEnd);
            }
            i = runEnd;
        }
        return out.toString();
    }

    /** 片段里达到该长度的数字块整块打码。 */
    private static final int MIN_DIGIT_RUN_TO_MASK = 7;

    /**
 * 把命中范围扩到它所在的<b>连续数字块</b>两端。
 *
 * <p>命中值是数字、且左右紧邻数字时，扩到整块——否则报出一段内嵌的出生日期
 * 会把整个证件号的前 6 位与后 4 位留在片段里，那仍然是半个证件号。
 * 命中值两侧不是数字（典型是中文姓名）时原样返回，不扩。
 *
 * @return {@code {扩后的起点, 扩后的终点}}
 */
    private static int[] expandToDigitRun(String line, int start, int end) {
        boolean startIsDigit = start < line.length() && isDigit(line.charAt(start));
        boolean endIsDigit = end > 0 && end <= line.length() && isDigit(line.charAt(end - 1));
        if (!startIsDigit && !endIsDigit) {
            return new int[]{start, end};
        }
        int from = start;
        while (from > 0 && isDigit(line.charAt(from - 1))) {
            from--;
        }
        int to = end;
        while (to < line.length() && isDigit(line.charAt(to))) {
            to++;
        }
        return new int[]{from, to};
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

/** 片段里替换明文用的标记。 */
    private static final String MASK = "***";

    private static final int EXCERPT_CONTEXT = 16;

    /**
     * ISO 7064 MOD 11-2 校验位判定。
     *
     * <p>这是本类能把身份证假阳性压到零的关键：18 位随机数字串过不了这道校验，
     * 而任何真实存在的身份证号一定过（GB 11643-1999 附录 A）。
     *
     * <p><b>校验位本身九成以上是数字，只有约 1/11 是 {@code X}</b>。
     * 因此判定分两步：先确认末位是数字或 {@code X/x}，再与算出来的期望位比对。
     * 初版把末位直接要求成 {@code X}，结果是漏掉九成以上的真实号码——
     * 那比误报危险得多，因为<b>漏报是静默的</b>：扫出来「干净」，而明文就在文件里。
     * 末位小写 {@code x} 视为大写 {@code X}，这是唯一的宽容，且不引入假阳性。
     */
    public static boolean isChecksumValidIdCard(String raw) {
        if (raw == null || raw.length() != 18) {
            return false;
        }
        char last = Character.toUpperCase(raw.charAt(17));
        boolean lastIsDigit = last >= '0' && last <= '9';
        if (!lastIsDigit && last != 'X') {
            return false;
        }
        int sum = 0;
        for (int i = 0; i < 17; i++) {
            char c = raw.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
            sum += (c - '0') * ID_WEIGHTS[i];
        }
        return ID_CHECK_CHARS.charAt(sum % 11) == last;
    }

    /** 是否属于本类扫描的文件（按扩展名白名单）。 */
    public static boolean isScannable(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        for (String suffix : SCANNED_SUFFIXES) {
            if (name.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    /** 遍历目录下的候选文件，跳过构建产物与源码目录。 */
    private static List<Path> walk(Path root) throws IOException {
        List<Path> out = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String name = dir.getFileName() == null
                        ? "" : dir.getFileName().toString();
                if (!dir.equals(root) && SKIPPED_DIRS.contains(name)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile() && isScannable(file)) {
                    out.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        out.sort(Comparator.comparing(Path::toString));
        return out;
    }

    private static String relativize(Path root, Path file) {
        try {
            return root.relativize(file).toString();
        } catch (IllegalArgumentException e) {
            return file.toString();
        }
    }
}