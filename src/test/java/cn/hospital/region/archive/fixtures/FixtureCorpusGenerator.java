package cn.hospital.region.archive.fixtures;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 测试数据层生成器 —— 内置确定性合成器。
 *
 * <p>上游来源与降级约定（见 scripts/fetch_fixtures.sh）：
 * <ul>
 *   <li>网络可达且给出 {@code SYNTHEA_JAR} 时，脚本改为真跑 Synthea 引擎出 HL7 v2；</li>
 *   <li>本类只在网络取数失败时兜底，产出 <b>Synthea HL7 v2 输出格式</b>的报文；
 *       数据本身是合成数据，不是真实患者，也不是 Synthea 引擎跑出来的产物；
 *       实际取数来源记录在 {@code fixtures/.source-synthea} 与 {@code fixtures/.source-hapi}。</li>
 * </ul>
 *
 * <p>同一种子（{@link #SEED}）连跑两次逐字节一致，供 T26「对照可复现」断言使用。
 * 所有标识符均为可识别的合成值（{@code TESTPAT-*} / 区划码 000000 / 000-0000-0000），
 * 不得与真实院内数据混淆。
 */
public final class FixtureCorpusGenerator {

    /** 固定随机种子：可复现性的前提。 */
    public static final long SEED = 20261020L;

    static final char SEGMENT_SEP = '\r';
    static final String ENCODING_CHARS = "^~\\&";
    static final Charset GB18030 = Charset.forName("GB18030");

    /** 区域平台侧的固定标识（与 AppConfig 的 ARCHIVE_LOCAL_AET 一致）。 */
    static final String LOCAL_AET = "COMMUNITY_HEALTH";
    static final String REMOTE_AET = "REGIONAL_ARCHIVE";

    private FixtureCorpusGenerator() {
    }

    // ------------------------------------------------------------------ 入口

    public static void main(String[] args) throws IOException {
        Path root = args.length > 0
                ? Paths.get(args[0])
                : Paths.get("src/test/resources/fixtures");
        Path synthea = root.resolve("synthea");
        Path hapi = root.resolve("hapi");
        Path zh = root.resolve("zh");
        for (Path dir : List.of(root, synthea, hapi, zh)) {
            Files.createDirectories(dir);
        }
        writeSynthea(synthea);
        writeHapiSamples(hapi);
        writeZhVariants(zh);
        System.out.printf("[generator] synthea=%d hapi=%d zh=%d%n",
                countHl7(synthea), countHl7(hapi), countHl7(zh));
    }

    private static long countHl7(Path dir) {
        try (var s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".hl7")).count();
        } catch (IOException e) {
            return 0;
        }
    }

    // --------------------------------------------------------- Synthea 语料

    /** 一位居民：四套写法（简/繁/同音错别字/带空格），共享同一个 PID-3 与出生日期。 */
    record VariantPerson(int idx, String simplified, String traditional, String homophone, String birthDate, char sex) {
        String remoteId() {
            return "TESTPAT-" + String.format("%04d", idx);
        }

        /** 合成身份证：区划码固定 000000（无真实区划），格式合法但不可能指向真人。 */
        String idCard() {
            return "000000" + birthDate + String.format("%03d", (idx * 37) % 1000) + checkChar(birthDate, idx);
        }

        String localMrn() {
            return "LOCMRN-" + String.format("%04d", idx);
        }
    }

    private static final VariantPerson[] PERSONS = {
            new VariantPerson(1, "张伟", "張偉", "章偉", "19850512", 'M'),
            new VariantPerson(2, "王芳", "王芳", "汪芳", "19790308", 'F'),
            new VariantPerson(3, "李娜", "李娜", "李纳", "19910723", 'F'),
            new VariantPerson(4, "刘洋", "劉洋", "留洋", "19821130", 'M'),
            new VariantPerson(5, "陈静", "陳靜", "沉静", "19660417", 'F'),
            new VariantPerson(6, "杨敏", "楊敏", "杨悯", "19880905", 'F'),
            new VariantPerson(7, "赵磊", "趙磊", "赵蕾", "19951211", 'M'),
            new VariantPerson(8, "黄强", "黃強", "黄蔷", "19740119", 'M'),
            new VariantPerson(9, "周婷", "周婷", "祝婷", "19900326", 'F'),
            new VariantPerson(10, "吴斌", "吳斌", "吴滨", "19860714", 'M'),
            new VariantPerson(11, "徐丽", "徐麗", "徐莉", "19981102", 'F'),
            new VariantPerson(12, "孙涛", "孫濤", "孙滔", "19710521", 'M'),
            new VariantPerson(13, "马超", "馬超", "马抄", "19890909", 'M'),
            new VariantPerson(14, "朱琳", "朱琳", "竹林", "19930407", 'F'),
            new VariantPerson(15, "胡军", "胡軍", "胡君", "19681230", 'M'),
            new VariantPerson(16, "郭静", "郭靜", "郭靖", "19820918", 'F'),
            new VariantPerson(17, "何欣", "何欣", "何馨", "19960224", 'F'),
            new VariantPerson(18, "高翔", "高翔", "高详", "19770616", 'M'),
            new VariantPerson(19, "林峰", "林峰", "临峰", "19831213", 'M'),
            new VariantPerson(20, "罗燕", "羅燕", "罗艳", "19910927", 'F'),
            new VariantPerson(21, "郑爽", "鄭爽", "郑双", "19980704", 'F'),
            new VariantPerson(22, "梁颖", "梁穎", "梁影", "19850401", 'F'),
            new VariantPerson(23, "谢涛", "謝濤", "谢滔", "19720908", 'M'),
            new VariantPerson(24, "宋洁", "宋潔", "宋杰", "19940122", 'F'),
    };

    /**
     * Z 段标签 → 本地字段名：真值（ground truth）。
     * 区域平台用的是中文业务标签，本地字段是英文驼峰，字面编辑距离方向是反的——
     * 这正是 T25 确定性基线判不中、T23 模型才补上的那部分。
     */
    static final String[][] Z_LABELS = {
            {"既往病史摘要", "pastHistorySummary", "高血压随访;2型糖尿病"},
            {"下次随访时间", "nextFollowupDate", "20270115"},
            {"责任医生姓名", "familyDoctorName", "李医生"},
            {"档案变更类型", "archiveChangeType", "随访记录更新"},
    };

    private static void writeSynthea(Path dir) throws IOException {
        Random rnd = new Random(SEED);
        String[] first = {"James", "Mary", "Robert", "Patricia", "John", "Jennifer", "Michael", "Linda",
                "David", "Elizabeth", "William", "Barbara", "Richard", "Susan", "Joseph", "Jessica",
                "Thomas", "Sarah", "Charles", "Karen", "Daniel", "Nancy", "Matthew", "Lisa"};
        String[] last = {"Smith", "Johnson", "Williams", "Brown", "Jones", "Garcia", "Miller", "Davis",
                "Rodriguez", "Martinez", "Hernandez", "Lopez", "Gonzalez", "Wilson", "Anderson",
                "Thomas", "Taylor", "Moore", "Jackson", "Martin"};
        String[] city = {"Anytown", "Springfield", "Riverton", "Fairview", "Georgetown"};
        String[] state = {"MA", "OH", "TX", "WA", "CA"};
        String[] types = {"ADT^A08", "ADT^A31", "ORU^R01"};

        for (int i = 1; i <= 60; i++) {
            String pid = "SYN-P" + String.format("%04d", i);
            String given = first[rnd.nextInt(first.length)];
            String family = last[rnd.nextInt(last.length)];
            String name = family + "^" + given + "^^^^^L";
            String birth = (1950 + rnd.nextInt(55)) + String.format("%02d", 1 + rnd.nextInt(12))
                    + String.format("%02d", 1 + rnd.nextInt(28));
            String sex = rnd.nextBoolean() ? "M" : "F";
            String addr = (100 + rnd.nextInt(900)) + " " + first[rnd.nextInt(first.length)] + " St^"
                    + "Apt " + (1 + rnd.nextInt(20)) + "^" + city[rnd.nextInt(city.length)] + "^"
                    + state[rnd.nextInt(state.length)] + "^" + String.format("%05d", 10000 + rnd.nextInt(89999));
            String phone = "000-" + String.format("%04d", rnd.nextInt(10000)) + "-" + String.format("%04d", rnd.nextInt(10000));
            String type = types[(i - 1) % types.length];

            StringBuilder msg = new StringBuilder();
            msg.append(msh("Synthea", "SYNTHETEA", LOCAL_AET, "COMMUNITY_CENTER",
                    timestamp(), type, pid, "2.3", "UNICODE UTF-8"));
            msg.append(pid(name, birth, sex.charAt(0), addr, phone, pid));
            if ("ORU^R01".equals(type)) {
                msg.append(obr(i)).append(obxBodyHeight(rnd, i)).append(obxWeight(rnd, i));
            }
            write(dir.resolve("synthea_patient_" + String.format("%04d", i) + ".hl7"),
                    msg.toString(), StandardCharsets.UTF_8);
        }
    }

    // ------------------------------------------------- HAPI / ACK 契约基线

    /**
     * ACK 契约基线：MSH-9 变体、MSH-12 版本变体、ERR 段、MSH-18 字符集声明、
     * 非默认分隔符、转义序列，以及两条故意缺陷报文（供 T7 字段级错误定位用）。
     */
    private static void writeHapiSamples(Path dir) throws IOException {
        String ts = timestamp();
        String pid = pid("Doe^Jane^^^^^L", "19750312", 'F', "123 Elm St^Apt 4^Anytown^MA^02115", "000-0000-0001", "SYN-H0001");

        write(dir.resolve("ack_aa_v23.hl7"), msh(LOCAL_AET, "COMMUNITY_CENTER", REMOTE_AET, "REGION_HIS", ts,
                "ACK^ACK^HL7_v2.3", "ACK000001", "2.3", "") + "MSA|AA|HL700001" + SEP, StandardCharsets.UTF_8);

        write(dir.resolve("ack_ae_err_v251.hl7"), msh(LOCAL_AET, "COMMUNITY_CENTER", REMOTE_AET, "REGION_HIS", ts,
                "ACK", "ACK000002", "2.5.1", "") + "MSA|AE|HL700002|||" + SEP
                + "ERR|||MSH^10^1^Missing required field^E|||控制号缺失||Patient data|", StandardCharsets.UTF_8);

        write(dir.resolve("ack_ar_v251.hl7"), msh(LOCAL_AET, "COMMUNITY_CENTER", REMOTE_AET, "REGION_HIS", ts,
                "ACK", "ACK000003", "2.5.1", "") + "MSA|AR|HL700003||身份硬锚点冲突，拒绝进候选|||" + SEP
                + "ERR|||PID^3^1^Value not found^E|||跨院标识与本地记录冲突||Patient data|", StandardCharsets.UTF_8);

        write(dir.resolve("adt_a01_v23.hl7"), msh(REMOTE_AET, "REGION_HIS", LOCAL_AET, "COMMUNITY_CENTER", ts,
                "ADT^A01", "HL700001", "2.3", "") + pid, StandardCharsets.UTF_8);

        write(dir.resolve("adt_a08_v251.hl7"), msh(REMOTE_AET, "REGION_HIS", LOCAL_AET, "COMMUNITY_CENTER", ts,
                "ADT^A08", "HL700008", "2.5.1", "") + pid, StandardCharsets.UTF_8);

        write(dir.resolve("adt_a31_v251.hl7"), msh(REMOTE_AET, "REGION_HIS", LOCAL_AET, "COMMUNITY_CENTER", ts,
                "ADT^A31", "HL700031", "2.5.1", "") + pid, StandardCharsets.UTF_8);

        write(dir.resolve("oru_r01_v23.hl7"), msh(REMOTE_AET, "REGION_HIS", LOCAL_AET, "COMMUNITY_CENTER", ts,
                "ORU^R01", "HL700100", "2.3", "")
                + pid + obr(1) + obxBodyHeight(new Random(SEED + 1), 1) + obxWeight(new Random(SEED + 2), 1), StandardCharsets.UTF_8);

        write(dir.resolve("oru_r01_v251.hl7"), msh(REMOTE_AET, "REGION_HIS", LOCAL_AET, "COMMUNITY_CENTER", ts,
                "ORU^R01", "HL700101", "2.5.1", "") + pid + obr(1), StandardCharsets.UTF_8);

        // MSH-18 字符集声明三种取值；GB18030 那份按 GB18030 真实编码落盘
        String zhMsg = msh(REMOTE_AET, "REGION_HIS", LOCAL_AET, "COMMUNITY_CENTER", ts,
                "ADT^A08", "HL700200", "2.5.1", "GB18030-2000")
                + pid("张^伟", "19850512", 'M', "示例路^1号^示例区", "000-0000-0002", "HL700200");
        write(dir.resolve("msh18_gb18030_declared.hl7"), zhMsg, GB18030);

        write(dir.resolve("msh18_unicode_utf8_declared.hl7"),
                msh(REMOTE_AET, "REGION_HIS", LOCAL_AET, "COMMUNITY_CENTER", ts,
                        "ADT^A08", "HL700201", "2.5.1", "UNICODE UTF-8")
                        + pid("张^伟", "19850512", 'M', "示例路^1号^示例区", "000-0000-0002", "HL700201"),
                StandardCharsets.UTF_8);

        write(dir.resolve("msh18_ascii_declared.hl7"),
                msh(REMOTE_AET, "REGION_HIS", LOCAL_AET, "COMMUNITY_CENTER", ts,
                        "ADT^A08", "HL700202", "2.5.1", "ASCII") + pid, StandardCharsets.UTF_8);

        // 国内平台偶见的非默认分隔符：字段 !、组件 $、重复 %、转义 *、子组件 &
        // 组件必须用本组自述的 $ 拼，不能沿用默认 ^ —— 否则样例自身就前后不一致，
        // 任何「非默认分隔符解析正确」的断言都会被这份坏样例架空。
        write(dir.resolve("delims_alternate_bang_dollar.hl7"),
                "MSH!$%*&!Synthea!SYNTHETEA!" + LOCAL_AET + "!COMMUNITY_CENTER!" + ts
                        + "!!ADT$A08!HL700300!P!2.5.1!!!AL!NE!CHN!UNICODE UTF-8"
                        + SEP + "PID!1!!HL700300$$$Synthea$MR!!Doe$Jane$$$$$L!!19750312!F",
                StandardCharsets.UTF_8);

        // 五类转义序列（供 T6 转义还原）
        write(dir.resolve("esc_sequences_v251.hl7"),
                msh(REMOTE_AET, "REGION_HIS", LOCAL_AET, "COMMUNITY_CENTER", ts,
                        "ADT^A08", "HL700400", "2.5.1", "")
                        + "PID|1||HL700400^^^Synthea^MR|A\\F\\B\\S\\C\\T\\D\\R\\E\\F\\^^^^^L||19750312|M",
                StandardCharsets.UTF_8);

        // 未成对转义：解析期记告警而不是抛错
        write(dir.resolve("unpaired_escape_v251.hl7"),
                msh(REMOTE_AET, "REGION_HIS", LOCAL_AET, "COMMUNITY_CENTER", ts,
                        "ADT^A08", "HL700401", "2.5.1", "")
                        + "PID|1||HL700401^^^Synthea^MR|A\\F\\B\\\\S\\C^^^^^L||19750312|M",
                StandardCharsets.UTF_8);

        // 故意缺陷：MSH-10 缺失（T7 定位 MSH-10）
        write(dir.resolve("defect_missing_msh10.hl7"),
                "MSH|^~\\&|" + REMOTE_AET + "|REGION_HIS|" + LOCAL_AET + "|COMMUNITY_CENTER|" + ts + "||ADT^A08||P|2.5.1"
                        + SEP + pid, StandardCharsets.UTF_8);

        // 故意缺陷：PID-3 缺失（T7 定位 PID-3）
        write(dir.resolve("defect_missing_pid3.hl7"),
                msh(REMOTE_AET, "REGION_HIS", LOCAL_AET, "COMMUNITY_CENTER", ts, "ADT^A08", "HL700500", "2.5.1", "")
                        + "PID|1||||Doe^Jane^^^^^L||19750312|F" + SEP, StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------- 中文变体语料

    private static void writeZhVariants(Path dir) throws IOException {
        List<String> index = new ArrayList<>();
        index.add("file,class,person_id,remote_name,local_name,birth_date,id_card,pid3,local_mrn,zx_label,local_field");

        for (VariantPerson p : PERSONS) {
            String base = String.format("%04d", p.idx());

            // 简体（与院内记录同名）
            addZh(dir, index, zhName(100, p.idx(), "simplified"), "simplified",
                    zhMsg(p, p.simplified()), p, p.simplified(), "简写");

            // 繁体（同一人的另一种写法）
            addZh(dir, index, zhName(100, p.idx(), "traditional"), "traditional",
                    zhMsg(p, p.traditional()), p, p.traditional(), "繁写");

            // 同音错别字（拼音相同、字不同：基线编辑距离吃不准，模型才补）
            addZh(dir, index, zhName(200, p.idx(), "homophone"), "homophone",
                    zhMsg(p, p.homophone()), p, p.homophone(), "同音错别字");

            // 姓名内含空格（半角 / 全角各若干）
            String spaced = p.idx() % 2 == 0 ? p.simplified().charAt(0) + "　" + p.simplified().substring(1)
                    : p.simplified().charAt(0) + " " + p.simplified().substring(1);
            addZh(dir, index, zhName(300, p.idx(), "space"), "space",
                    zhMsg(p, spaced), p, spaced, "姓名含空格");
        }

        // 编码族：同一条报文 UTF-8 与 GB18030 各一份，字节不同、解析结果必须逐字相同。
        // 两份的 MSH-18 声明各随自己的落盘编码：声明必须能被 CharsetResolver 解回同一批字节，
        // 否则主路径会按声明解出乱码且一声不响。
        for (int i = 1; i <= 20; i++) {
            VariantPerson p = PERSONS[i - 1];
            write(dir.resolve(String.format("zh_%04d_utf8.hl7", i)),
                    zhMsg(p, p.simplified(), "UNICODE UTF-8"), StandardCharsets.UTF_8);
            write(dir.resolve(String.format("zh_%04d_gb18030.hl7", i)),
                    zhMsg(p, p.simplified(), "GB18030-2000"), GB18030);
            index.add(csv(String.format("zh_%04d_utf8.hl7", i), "utf8", p, p.simplified(), "UTF-8 编码"));
            index.add(csv(String.format("zh_%04d_gb18030.hl7", i), "gb18030", p, p.simplified(),
                    "GB18030 编码，与 UTF-8 同内容"));
        }

        write(dir.resolve("index.csv"), String.join("\n", index) + "\n", StandardCharsets.UTF_8);
    }

    /** 变体文件命名：族号 + 居民序号，四位补零，便于按族批量取用。 */
    private static String zhName(int family, int idx, String clazz) {
        return String.format("zh_%04d_%s.hl7", family + idx, clazz);
    }

    private static void addZh(Path dir, List<String> index, String fileName, String clazz,
                       String message, VariantPerson p, String remoteName, String label) throws IOException {
        write(dir.resolve(fileName), message, StandardCharsets.UTF_8);
        index.add(csv(fileName, clazz, p, remoteName, label));
    }

    private static String csv(String file, String clazz, VariantPerson p, String remoteName, String label) {
        return String.join(",", file, clazz, String.valueOf(p.idx()), remoteName, p.simplified(),
                p.birthDate(), p.idCard(), p.remoteId(), p.localMrn(), Z_LABELS[0][0], Z_LABELS[0][1]);
    }

    /**
     * 中文姓名的组件切分：简体 / 繁体 / 同音错别字按 姓^名 拆成 PID-5.1 与 PID-5.2；
     * 「姓名含空格」这一类保持整串放在首组件——这正是真实脏数据的形态。
     */
    private static String familyGiven(String displayName) {
        return displayName.contains(" ") || displayName.contains("\u3000")
                ? displayName
                : displayName.charAt(0) + "^" + displayName.substring(1);
    }

    /** 一条中文变体报文：MSH + PID + 四条带中文标签的 Z 段。 */
    private static String zhMsg(VariantPerson p, String displayName) {
        return zhMsg(p, displayName, "UNICODE UTF-8");
    }

    /**
     * @param charset MSH-18 声明值。<b>必须与落盘字节实际用的编码一致</b>——
     *                 声明与字节对不上时，字符集解析会忠实地按声明去解，
     *                 产出的是<b>乱码且不报任何错</b>：解析通过、必填校验通过、
     *                 台账行数一条不少，唯一的表现是中文全是问号方块。
     *                 这比解析失败难查得多，所以生成器按落盘编码分别写声明。
     */
    private static String zhMsg(VariantPerson p, String displayName, String charset) {
        StringBuilder msg = new StringBuilder();
        msg.append(msh(REMOTE_AET, "REGION_HIS", LOCAL_AET, "COMMUNITY_CENTER", timestamp(),
                "ADT^A31", "ZHB" + String.format("%05d", p.idx()), "2.5.1", charset));
        msg.append(pid(familyGiven(displayName) + "^^^^^L", p.birthDate(), p.sex(),
                "示例路^" + p.idx() + "号^示例区^示例市", "000-0000-" + String.format("%04d", p.idx()),
                p.remoteId() + "^^^" + REMOTE_AET + "^PatientId~000000"
                        + p.birthDate() + String.format("%03d", (p.idx() * 37) % 1000) + checkChar(p.birthDate(), p.idx())
                        + "^^^" + REMOTE_AET + "^IdCard"));
        for (int i = 0; i < Z_LABELS.length; i++) {
            msg.append("ZX" + (i + 1) + "||" + Z_LABELS[i][0] + "|" + Z_LABELS[i][2]).append(SEGMENT_SEP);
        }
        return msg.toString();
    }

    // -------------------------------------------------------------- 段构造

    private static String msh(String sendApp, String sendFac, String recvApp, String recvFac, String ts,
                              String msgType, String controlId, String version, String charset) {
        // MSH-1 就是字段分隔符本身、不占数组位，故 f[2] 才是 MSH-3（发送应用）
        String[] f = new String[18];
        f[0] = "MSH";
        f[1] = ENCODING_CHARS;
        f[2] = sendApp;          // MSH-3
        f[3] = sendFac;          // MSH-4
        f[4] = recvApp;          // MSH-5
        f[5] = recvFac;          // MSH-6
        f[6] = ts;               // MSH-7
        f[8] = msgType;          // MSH-9
        f[9] = controlId;        // MSH-10
        f[10] = "P";             // MSH-11
        f[11] = version;         // MSH-12
        f[14] = "AL";            // MSH-15
        f[15] = "NE";            // MSH-16
        f[16] = "CHN";           // MSH-17
        f[17] = charset;         // MSH-18
        return join(f);
    }

    private static String pid(String name, String birthDate, char sex, String address, String phone, String id3) {
        String[] f = new String[30];
        f[0] = "PID";
        f[1] = "1";
        f[3] = id3;
        f[5] = name;
        f[7] = birthDate;
        f[8] = String.valueOf(sex);
        f[11] = address;
        f[13] = phone;
        f[15] = "zho";
        f[26] = "CHN";
        return join(f);
    }

    private static String obr(int i) {
        String[] f = new String[5];
        f[0] = "OBR";
        f[1] = "1";
        f[2] = "OBR" + String.format("%05d", i);
        f[3] = "8302-2^Body Height^LN";
        f[4] = "N";
        return join(f);
    }

    private static String obxBodyHeight(Random rnd, int i) {
        return obx(1, "8302-2^Body Height^LN", "cm", (150 + rnd.nextInt(40)) + ".0", String.format("%05d", i));
    }

    private static String obxWeight(Random rnd, int i) {
        return obx(2, "29463-7^Body Weight^LN", "kg", (45 + rnd.nextInt(45)) + ".0", String.format("%05d", i));
    }

    private static String obx(int setId, String observation, String unit, String value, String subId) {
        String[] f = new String[6];
        f[0] = "OBX";
        f[1] = String.valueOf(setId);
        f[2] = "NM";
        f[3] = observation;
        f[4] = value;
        f[5] = unit;
        return join(f);
    }

    private static String join(String[] fields) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) {
                sb.append('|');
            }
            sb.append(fields[i] == null ? "" : fields[i]);
        }
        return sb.append(SEGMENT_SEP).toString();
    }

    private static String timestamp() {
        return "20261020081500+0800";
    }

    /** 身份证校验位：让合成值格式合法，便于 T29 归一化与冲突判定按真实规则跑。 */
    private static char checkChar(String birthDate, int idx) {
        int[] weights = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
        String body = "000000" + birthDate + String.format("%03d", (idx * 37) % 1000);
        int sum = 0;
        for (int i = 0; i < 17; i++) {
            sum += (body.charAt(i) - '0') * weights[i];
        }
        return "10X98765432".charAt(sum % 11);
    }

    private static void write(Path path, String content, Charset charset) throws IOException {
        Files.write(path, content.getBytes(charset));
    }

    private static final String SEP = String.valueOf(SEGMENT_SEP);
}