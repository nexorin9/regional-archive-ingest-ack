package cn.hospital.region.archive.mllp;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 容量基准语料 —— 一批 500 条「合成健康档案变更报文」，覆盖三种真实输入形态。
 *
 * <h2>为什么必须混这三种，而不是拿 500 条一模一样的报文凑数</h2>
 * <p>全是干净报文的 P99 是<b>没有意义</b>的：它量的是「顺利路径有多快」，而真正决定接入口
 * 扛不扛得住的是「不顺的时候有多慢」。三种形态各挑一批：
 * <ul>
 *   <li><b>UTF-8 正常报文</b>：顺利路径基准；</li>
 *   <li><b>GB18030 报文</b>：国内区域平台下发的中文档案大量声明 GB18030-2000，
 *       解码路径与 UTF-8 不同，必须一起进批次；</li>
 *   <li><b>重复控制号</b>：对端重推、网络抖动后重发 —— 走幂等命中分支，
 *       比首次入账少一半数据库往返，把它算进 P99 才等于覆盖真实负载；</li>
 *   <li><b>缺字段报文</b>：缺 PID-3 与缺 MSH-10 两类，回 AE 并入账为拒收。
 *       它们走的是「先定位后拒收」的另一条分支，比顺利路径重。</li>
 * </ul>
 *
 * <h2>合成数据的自我约束</h2>
 * <p>本类生成的一切都是<b>合成的</b>：姓名写成 {@code 居民^A0001} 这种一眼可辨的占位形式，
 * 不使用任何看起来像真实居民标识的姓名、身份证号或住院号。容量基准要的是<b>字节形状</b>对，
 * 不是数据像真的。
 *
 * <h2>批次构成（被验收用例逐项断言，不靠注释自证）</h2>
 * <pre>
 *   UTF-8 正常       330 条  → 台账新增一行，进待确认
 *   GB18030 正常     120 条  → 台账新增一行，进待确认
 *   重复控制号        30 条  → 幂等命中，不新增行
 *   缺 PID-3         12 条  → 台账新增一行，进拒收
 *   缺 MSH-10         8 条  → 台账新增一行，进拒收
 *   ------------------------------------
 *   合计            500 帧  → 台账 470 行（= 500 − 30 重复）
 * </pre>
 *
 * <p>缺 MSH-10 的报文控制号为空串，若不靠 MSH-11 区分就会全部撞上同一个幂等键、
 * 变成「一条拒收 + 七条重复」——那样测出来的批次构成就不是上面这张表了。
 * 所以这 8 条的 MSH-11 各自不同。
 */
public final class CapacityCorpus {

    /** 一帧语料：已加 MLLP 帧的字节 + 定位信息 + 形态。 */
    public record Frame(byte[] framed, String label, Kind kind, Charset charset) {
    }

    /** 语料的四种形态。 */
    public enum Kind {
        /** UTF-8 编码、字段齐全的健康档案变更报文。 */
        UTF8_VALID,
        /** GB18030 编码、字段齐全（MSH-18 自报 {@code GB18030-2000}）。 */
        GB18030_VALID,
        /** 控制号与前面某条重复 —— 模拟对端重推 / 网络抖动重发。 */
        DUPLICATE,
        /** 缺 PID-3（患者标识）—— 定位码 {@code patient-id-missing}，AE 可重投。 */
        MISSING_PID3,
        /** 缺 MSH-10（报文控制号）—— 定位码 {@code control-id-missing}，AE 可重投。 */
        MISSING_MSH10
    }

    /** 默认批次帧数；spec 的容量验收口径。 */
    public static final int DEFAULT_BATCH_SIZE = 500;

    /** GB18030 在 JDK 里的字符集名（报文里自报的是 {@code GB18030-2000}）。 */
    public static final Charset GB18030 = Charset.forName("GB18030");

    /** 报文自报的 GB18030 字符集名。 */
    public static final String MSH18_GB18030 = "GB18030-2000";

    /** 报文自报的 UTF-8 字符集名。 */
    public static final String MSH18_UTF8 = "UNICODE UTF-8";

    private static final String SEND_APP = "ARCHIVE_IN";
    private static final String SEND_FACILITY = "FACILITY_A";
    private static final String RECEIVE_APP = "REGIONAL_ARCHIVE";
    private static final String RECEIVE_FACILITY = "FACILITY_A";

    private CapacityCorpus() {
        // 工具类，禁止实例化
    }

    /**
     * 生成一批语料。
     *
     * @param batchSize 帧数；必须 ≥ 20（构成比例按 500 条设计，低于 20 条时各形态会取不到样本）
     */
    public static List<Frame> generate(int batchSize) {
        if (batchSize < 20) {
            throw new IllegalArgumentException("批次至少 20 帧，收到 " + batchSize);
        }
        int dupCount = Math.max(1, batchSize * 30 / 500);
        int missingPid3 = Math.max(1, batchSize * 12 / 500);
        int missingMsh10 = Math.max(1, batchSize * 8 / 500);
        int gbCount = Math.max(1, batchSize * 120 / 500);
        int utf8Count = batchSize - dupCount - missingPid3 - missingMsh10 - gbCount;
        if (utf8Count < 1) {
            throw new IllegalArgumentException("批次 " + batchSize + " 条装不下这五种形态");
        }

        List<Frame> frames = new ArrayList<>(batchSize);
        List<String> validControlIds = new ArrayList<>(utf8Count + gbCount);

        int seq = 0;
        for (int i = 0; i < utf8Count; i++) {
            seq++;
            String controlId = "CAP-" + String.format("%05d", seq);
            validControlIds.add(controlId);
            frames.add(frame(er7(seq, controlId, "P", MSH18_UTF8, true),
                    controlId, Kind.UTF8_VALID, StandardCharsets.UTF_8));
        }
        for (int i = 0; i < gbCount; i++) {
            seq++;
            String controlId = "CAP-" + String.format("%05d", seq);
            validControlIds.add(controlId);
            frames.add(frame(er7(seq, controlId, "P", MSH18_GB18030, true),
                    controlId, Kind.GB18030_VALID, GB18030));
        }
        // 重复控制号：全部指向前面真实出现过的那些，逐条错开取，避免「同一条重复 N 次」
        // 把幂等分支测成一条消息的重放。
        for (int i = 0; i < dupCount; i++) {
            String controlId = validControlIds.get((i * 7 + 3) % validControlIds.size());
            int dupSeq = utf8Count + gbCount + i + 1;
            frames.add(frame(er7(dupSeq, controlId, "P", MSH18_UTF8, true),
                    controlId, Kind.DUPLICATE, StandardCharsets.UTF_8));
        }
        for (int i = 0; i < missingPid3; i++) {
            seq++;
            String controlId = "CAP-" + String.format("%05d", seq);
            frames.add(frame(er7(seq, controlId, "P", MSH18_UTF8, false),
                    controlId, Kind.MISSING_PID3, StandardCharsets.UTF_8));
        }
        for (int i = 0; i < missingMsh10; i++) {
            seq++;
            // 控制号留空；靠 MSH-11 区分，否则这 8 条会全撞同一个幂等键。
            String processingId = String.format("PMF-%03d", i + 1);
            frames.add(frame(er7(seq, "", processingId, MSH18_UTF8, true),
                    "controlId=(空)/" + processingId, Kind.MISSING_MSH10, StandardCharsets.UTF_8));
        }
        return Collections.unmodifiableList(frames);
    }

    /** 一条报文；{@code withPid3=false} 时把 PID-3 整个留空（不是留一个空首组件）。 */
    private static String er7(int seq, String controlId, String processingId,
                              String charsetDeclaration, boolean withPid3) {
        String msh = "MSH|^~\\&|" + SEND_APP + "|" + SEND_FACILITY + "|"
                + RECEIVE_APP + "|" + RECEIVE_FACILITY + "|"
                + String.format("20261020%06d", 900_000 + seq % 86_400)
                + "||ADT^A01^ADT_A01|" + controlId + "|" + processingId
                // MSH-13/14/15 是三个空位，MSH-16=AL、MSH-17=NE、MSH-18=字符集。
                // 少写一个空位字符集就落到 MSH-17 上：报文照样能解析、必填校验照样通过，
                // 只是所有 GB18030 帧都被当成「未声明字符集」按 UTF-8 解，中文全成乱码——
                // 而台账行数与状态分布一个都不少，容量数字照样好看。
                + "|2.5.1||||AL|NE|" + charsetDeclaration;
        // PID-3 必须整个为空串才算「缺患者标识」。写成 "|1||^PAT-xxx^F^MR" 时首组件为空、
        // 字段本身非空，FieldLocator 的非空判定会放它过去 —— 那样这 12 条就白造了。
        String pid3 = withPid3 ? patientRef(seq) + "^" + patientRef(seq) + "^" + SEND_FACILITY + "^MR" : "";
        String pid = "PID|1||" + pid3
                + "||居民^" + letterOf(seq) + String.format("%04d", seq)
                + "||19" + (60 + seq % 40) + String.format("%02d", 1 + seq % 12)
                + String.format("%02d", 1 + seq % 28) + "|" + (seq % 2 == 0 ? "F" : "M");
        return msh + "\r" + pid;
    }

    private static String patientRef(int seq) {
        return "PAT-CAP-" + String.format("%05d", seq);
    }

    private static String letterOf(int seq) {
        return String.valueOf((char) ('A' + seq % 26));
    }

    /** 加 MLLP 帧：{@code 0x0B} + ER7 + {@code 0x1C 0x0D}。 */
    private static Frame frame(String er7, String label, Kind kind, Charset charset) {
        byte[] payload = er7.getBytes(charset);
        byte[] framed = MllpFrameCodec.encode(payload);
        return new Frame(framed, label, kind, charset);
    }

    /** 按形态计数，供验收用例断言批次构成而不靠注释自证。 */
    public static int countOf(List<Frame> frames, Kind kind) {
        int n = 0;
        for (Frame f : frames) {
            if (f.kind() == kind) {
                n++;
            }
        }
        return n;
    }
}