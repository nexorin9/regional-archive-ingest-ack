package cn.hospital.region.archive.fixtures;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 测试语料清单与完整性校验（数据层的单一事实来源）。
 *
 * <p>两个入口：
 * <ul>
 *   <li>{@code --print}：scripts/fetch_fixtures.sh 的硬门槛。打印报告、写 {@code MANIFEST.txt}、
 *       不通过则非零退出。</li>
 *   <li>JVM API：T7/T23/T25/T26/T28 直接读语料与真值，不必各写一套路径拼接。</li>
 * </ul>
 *
 * <p>校验口径固定为四类：条数、编码、必需样例、GB18030 与 UTF-8 同内容。
 */
public final class FixturesManifest {

    /** 语料根：surefire 以项目子目录为工作目录，脚本也在这里执行。 */
    public static final Path FIXTURES_ROOT = locateFixturesRoot();

    public static final Path SYNTHEA_DIR = FIXTURES_ROOT.resolve("synthea");
    public static final Path HAPI_DIR = FIXTURES_ROOT.resolve("hapi");
    public static final Path ZH_DIR = FIXTURES_ROOT.resolve("zh");
    public static final Path ZH_INDEX = ZH_DIR.resolve("index.csv");
    public static final Path MANIFEST_FILE = FIXTURES_ROOT.resolve("MANIFEST.txt");

    /** 中文变体每一类的下限：任一类不足即视为语料退化。 */
    public static final int ZH_PER_CLASS_MIN = 20;
    public static final int SYNTHEA_MIN = 50;
    public static final int ZH_FILES_MIN = 60;

    private static final Charset GB18030 = Charset.forName("GB18030");

    /** HAPI/ACK 契约基线必须覆盖的样例文件（缺一即判语料不合格）。 */
    private static final String[] REQUIRED_HAPI = {
            "ack_aa_v23.hl7",
            "ack_ae_err_v251.hl7",
            "ack_ar_v251.hl7",
            "adt_a01_v23.hl7",
            "adt_a08_v251.hl7",
            "adt_a31_v251.hl7",
            "oru_r01_v23.hl7",
            "oru_r01_v251.hl7",
            "msh18_gb18030_declared.hl7",
            "msh18_unicode_utf8_declared.hl7",
            "msh18_ascii_declared.hl7",
            "delims_alternate_bang_dollar.hl7",
            "esc_sequences_v251.hl7",
            "unpaired_escape_v251.hl7",
            "defect_missing_msh10.hl7",
            "defect_missing_pid3.hl7",
    };

    private FixturesManifest() {
    }

    // ---------------------------------------------------------------- 报告

    /** 完整性校验结果：计数 + 问题清单。 */
    public record Verification(int syntheaFiles, int hapiFiles, int zhFiles,
                              Map<String, Integer> zhByClass, List<String> problems) {

        public boolean ok() {
            return problems.isEmpty();
        }
    }

    // ------------------------------------------------------------ 公开访问

    public static List<Path> syntheaMessages() throws IOException {
        return hl7Files(SYNTHEA_DIR);
    }

    public static List<Path> hapiMessages() throws IOException {
        return hl7Files(HAPI_DIR);
    }

    public static List<Path> zhVariantFiles() throws IOException {
        return hl7Files(ZH_DIR);
    }

    /** 中文变体真值：给 T25 基线与 T26 对照提供「标准答案」。 */
    public record PersonTruth(String personId, String localName, String remoteName, String birthDate,
                              String idCard, String remoteId, String localMrn, String zLabel, String localField) {
    }

    /**
     * 读 {@code zh/index.csv}：一位居民一行（同一居民的四类变体文件在索引里合并为一条真值），
     * 后续 T25 基线与 T26 对照的输入与标准答案都从这里取。
     */
    public static List<PersonTruth> zhTruth() throws IOException {
        Map<String, PersonTruth> byPerson = new LinkedHashMap<>();
        for (String line : Files.readAllLines(ZH_INDEX, StandardCharsets.UTF_8)) {
            String[] c = line.split(",", -1);
            if (c.length < 11 || c[0].equals("file") || c[0].isBlank()) {
                continue;
            }
            byPerson.putIfAbsent(c[2], new PersonTruth(c[2], c[4], c[3], c[5], c[6], c[7], c[8], c[9], c[10]));
        }
        return new ArrayList<>(byPerson.values());
    }

    /** Z 段标签 → 本地字段名真值（{@code {中文标签, 本地字段, 样例值}}）。 */
    public static List<String[]> zLabelGroundTruth() {
        List<String[]> out = new ArrayList<>();
        for (String[] row : FixtureCorpusGenerator.Z_LABELS) {
            out.add(row.clone());
        }
        return out;
    }

    /** 文件名 → 变体分类（{@code index.csv} 首两列）。 */
    public static Map<String, String> zhIndexByClass() throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        for (String line : Files.readAllLines(ZH_INDEX, StandardCharsets.UTF_8)) {
            String[] c = line.split(",", -1);
            if (c.length >= 2 && !c[0].equals("file")) {
                out.put(c[0], c[1]);
            }
        }
        return out;
    }

    public static List<Path> zhFilesOfClass(String clazz) throws IOException {
        List<Path> out = new ArrayList<>();
        zhIndexByClass().forEach((file, c) -> {
            if (c.equals(clazz)) {
                out.add(ZH_DIR.resolve(file));
            }
        });
        return out;
    }

    // -------------------------------------------------------------- 校验

    public static Verification verify() throws IOException {
        List<String> problems = new ArrayList<>();
        int synthea = 0;
        int hapi = 0;
        int zh = 0;
        Map<String, Integer> byClass = new LinkedHashMap<>();

        if (!Files.isDirectory(FIXTURES_ROOT)) {
            problems.add("语料根不存在：" + FIXTURES_ROOT + "（先跑 bash scripts/fetch_fixtures.sh）");
            return new Verification(0, 0, 0, byClass, problems);
        }

        // ---- synthea 族：≥50 条，每条含 MSH 与 PID
        for (Path p : hl7Files(SYNTHEA_DIR)) {
            synthea++;
            String msg = readUtf8(p);
            if (!msg.startsWith("MSH|")) {
                problems.add("synthea 报文缺 MSH 段：" + p.getFileName());
            } else if (!msg.contains("\rPID|")) {
                problems.add("synthea 报文缺 PID 段：" + p.getFileName());
            }
        }
        if (synthea < SYNTHEA_MIN) {
            problems.add("synthea ER7 样本不足：" + synthea + " < " + SYNTHEA_MIN);
        }

        // ---- hapi 族：ACK 契约基线必须齐全，且含 ERR 段与 MSH-18 声明
        for (String name : REQUIRED_HAPI) {
            if (!Files.isRegularFile(HAPI_DIR.resolve(name))) {
                problems.add("HAPI 契约基线缺样例：" + name);
            }
        }
        for (Path p : hl7Files(HAPI_DIR)) {
            hapi++;
            if (!readAuto(p).startsWith("MSH")) {
                problems.add("HAPI 样例首行不是 MSH：" + p.getFileName());
            }
        }
        if (!anyContains(HAPI_DIR, "ERR|")) {
            problems.add("HAPI 样例集缺 ERR 段样例");
        }
        if (!anyContains(HAPI_DIR, "|2.3|")) {
            problems.add("HAPI 样例集缺 MSH-12=2.3 版本变体");
        }
        for (String type : new String[]{"ADT^A01", "ADT^A08", "ADT^A31", "ORU^R01"}) {
            if (!anyContains(HAPI_DIR, "|" + type + "|")) {
                problems.add("HAPI 样例集缺 MSH-9 变体：" + type);
            }
        }
        for (String charsetDecl : new String[]{"GB18030-2000", "UNICODE UTF-8", "ASCII"}) {
            if (!anyContains(HAPI_DIR, charsetDecl)) {
                problems.add("HAPI 样例集缺 MSH-18 字符集声明：" + charsetDecl);
            }
        }

        // ---- zh 族：条数、分类下限、编码真实性与「同内容」
        if (!Files.isRegularFile(ZH_INDEX)) {
            problems.add("中文变体缺 index.csv 真值索引");
        } else {
            for (String line : Files.readAllLines(ZH_INDEX, StandardCharsets.UTF_8)) {
                String[] c = line.split(",", -1);
                if (c.length >= 2 && !c[0].equals("file")) {
                    byClass.merge(c[1], 1, Integer::sum);
                }
            }
        }
        for (Path p : zhVariantFiles()) {
            zh++;
            String file = p.getFileName().toString();
            String content = readBytesAs(p);
            if (!content.startsWith("MSH|")) {
                problems.add("中文变体缺 MSH 段：" + file);
            }
            if (field(content, "PID", 5) == null) {
                problems.add("中文变体缺 PID-5 姓名：" + file);
            }
            if (!content.contains("\rZX")) {
                problems.add("中文变体缺 Z 段：" + file);
            }
            if (file.endsWith("_gb18030.hl7")) {
                byte[] raw = readBytes(p);
                if (decodesCleanly(raw, StandardCharsets.UTF_8)) {
                    problems.add("GB18030 样本实际是合法 UTF-8 字节（未真按 GB18030 落盘）：" + file);
                }
            }
            if (file.endsWith("_utf8.hl7") && !decodesCleanly(readBytes(p), StandardCharsets.UTF_8)) {
                problems.add("UTF-8 样本无法按 UTF-8 解码：" + file);
            }
        }
        if (zh < ZH_FILES_MIN) {
            problems.add("中文变体条数不足：" + zh + " < " + ZH_FILES_MIN);
        }
        for (String clazz : List.of("simplified", "traditional", "homophone", "space", "utf8", "gb18030")) {
            int n = byClass.getOrDefault(clazz, 0);
            if (n < ZH_PER_CLASS_MIN) {
                problems.add("中文变体分类不足：" + clazz + " " + n + " < " + ZH_PER_CLASS_MIN);
            }
        }
        for (int i = 1; i <= 20; i++) {
            Path u = ZH_DIR.resolve(String.format(Locale.ROOT, "zh_%04d_utf8.hl7", i));
            Path g = ZH_DIR.resolve(String.format(Locale.ROOT, "zh_%04d_gb18030.hl7", i));
            if (!Files.isRegularFile(u) || !Files.isRegularFile(g)) {
                problems.add("编码对照样本缺失：zh_" + String.format(Locale.ROOT, "%04d", i) + " 的 UTF-8/GB18030 孪生文件");
                continue;
            }
            String nameUtf8 = field(readUtf8(u), "PID", 5);
            String nameGb = field(readBytesAs(g), "PID", 5);
            if (nameUtf8 == null || !nameUtf8.equals(nameGb)) {
                problems.add("GB18030 与 UTF-8 同内容不一致：zh_" + String.format(Locale.ROOT, "%04d", i)
                        + " (" + nameUtf8 + " != " + nameGb + ")");
            }
        }

        return new Verification(synthea, hapi, zh, byClass, problems);
    }

    // -------------------------------------------------------------- main

    public static void main(String[] args) throws IOException {
        Verification v = verify();
        System.out.printf("[fixtures] 根目录 %s%n", FIXTURES_ROOT);
        System.out.printf("[fixtures] synthea=%d 条 / hapi=%d 条 / zh=%d 条%n", v.syntheaFiles(), v.hapiFiles(), v.zhFiles());
        v.zhByClass().forEach((k, n) -> System.out.printf("[fixtures]   zh 分类 %-12s %d 条%n", k, n));
        System.out.printf("[fixtures] 取数来源 synthea=%s%n", sourceOf(".source-synthea"));
        System.out.printf("[fixtures] 取数来源 hapi=%s%n", sourceOf(".source-hapi"));
        if (v.problems().isEmpty()) {
            System.out.println("[fixtures] 完整性校验通过（条数 / 编码 / 必需样例 / GB18030-UTF-8 同内容）");
            writeManifest();
            return;
        }
        v.problems().forEach(p -> System.out.println("[fixtures] 问题：" + p));
        System.exit(1);
    }

    private static void writeManifest() throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# 测试语料清单（由 FixturesManifest 自动生成，勿手工编辑）\n");
        sb.append("synthea=").append(sourceOf(".source-synthea")).append('\n');
        sb.append("hapi=").append(sourceOf(".source-hapi")).append('\n');
        sb.append("generator_seed=").append(FixtureCorpusGenerator.SEED).append('\n');
        for (Path dir : List.of(SYNTHEA_DIR, HAPI_DIR, ZH_DIR)) {
            for (Path p : hl7Files(dir)) {
                sb.append(sha256(readBytes(p))).append("  ").append(dir.getFileName())
                        .append('/').append(p.getFileName()).append('\n');
            }
        }
        Files.write(MANIFEST_FILE, sb.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("[fixtures] 清单已写出：" + MANIFEST_FILE);
    }

    // ------------------------------------------------------------ 工具方法

    /** 取 PID-5 之类的字段值：只用于校验语料，真正的解析器由 T5–T7 实现。 */
    static String field(String er7, String segment, int idx) {
        for (String seg : er7.split("\r")) {
            String[] parts = seg.split("\\|", -1);
            if (parts.length > 0 && parts[0].equals(segment) && parts.length > idx) {
                String v = parts[idx];
                return v.isEmpty() ? null : v;
            }
        }
        return null;
    }

    private static boolean anyContains(Path dir, String needle) throws IOException {
        for (Path p : hl7Files(dir)) {
            if (readAuto(p).contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String readUtf8(Path p) throws IOException {
        return new String(readBytes(p), StandardCharsets.UTF_8);
    }

    /** 按文件名判定编码：GB18030 样本按 GB18030 解，其余按 UTF-8。 */
    private static String readAuto(Path p) throws IOException {
        return readBytesAs(p);
    }

    static String readBytesAs(Path p) throws IOException {
        String name = p.getFileName().toString();
        return new String(readBytes(p), name.contains("gb18030") ? GB18030 : StandardCharsets.UTF_8);
    }

    private static byte[] readBytes(Path p) throws IOException {
        return Files.readAllBytes(p);
    }

    private static boolean decodesCleanly(byte[] raw, Charset charset) {
        CharsetDecoder dec = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            dec.decode(java.nio.ByteBuffer.wrap(raw));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    private static List<Path> hl7Files(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (var s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".hl7"))
                    .sorted()
                    .toList();
        }
    }

    private static String sourceOf(String name) {
        return Optional.ofNullable(FIXTURES_ROOT.resolve(name))
                .filter(Files::isRegularFile)
                .map(p -> {
                    try {
                        return Files.readString(p, StandardCharsets.UTF_8).trim();
                    } catch (IOException e) {
                        return "未知";
                    }
                })
                .orElse("未记录（未跑 fetch_fixtures.sh）");
    }

    static String sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest(data)) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Path locateFixturesRoot() {
        Path[] candidates = {
                Paths.get("src/test/resources/fixtures"),
                Paths.get("../regional-archive-ingest-ack/src/test/resources/fixtures"),
                Paths.get("regional-archive-ingest-ack/src/test/resources/fixtures"),
        };
        for (Path p : candidates) {
            if (Files.isDirectory(p)) {
                return p;
            }
        }
        return candidates[0];
    }
}