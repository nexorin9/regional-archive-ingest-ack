package cn.hospital.region.archive.hl7;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 把入站字节流先按 MSH-18 声明的字符集解码为字符串，再交给 {@link Hl7Parser} 解析。
 *
 * <p>输入字节流应为已由 {@code MllpFrameCodec} 去除 MLLP 帧的 HL7 报文（CR 分段）。
 * 为提高鲁棒性，本类仍会剥除头部的 {@code 0x0B}（SOM）与尾部的 {@code 0x1C 0x0D}（EOM）。
 *
 * <p>主路径：找首个 {@code \r} → 切出 MSH 行 → 用 ASCII 读出 MSH-18 →
 * {@link CharsetResolver#resolve(String, List)} → 用决议字符集解码整段 →
 * {@link Hl7Parser#parse(String, List)}（含 FSRET 转义还原）。
 *
 * <p>关键不变量：GB18030 与 UTF-8 编码的同一中文报文，解析出的同名字段值逐字相同。
 *
 * <p>源产品对应能力来自 {@code github_ref/dcm4che/dcm4che-hl7/.../HL7Charset.toCharsetName(String)}
 * （MSH-18 → Java Charset 映射）与
 * {@code github_ref/dcm4che/dcm4che-hl7/.../HL7Parser.java:103}（MSH 自述分隔符读取）。
 */
public final class FrameDecoder {

    /** 解析结果：报文 + 告警列表 + 字符集决议。 */
    public static final class DecodedMessage {
        public final Hl7Message message;
        public final List<String> warnings;
        public final String charsetName;
        public final CharsetResolver.Status charsetStatus;

        /** 解码后出现的替换字符（U+FFFD）个数；有损解码时大于 0。 */
        public final int replacementCount;

        /** 本端<b>实际</b>用来解码的字符集名；与 {@link #charsetName} 不一致即发生了降级。 */
        public final String appliedCharsetName;

        /** 是否因为「声明与字节实际写法不一致」而把解码字符集换了。 */
        public final boolean charsetDegraded;

        public DecodedMessage(Hl7Message message, List<String> warnings,
                              String charsetName, CharsetResolver.Status charsetStatus) {
            this(message, warnings, charsetName, charsetStatus, 0, null, false);
        }

        public DecodedMessage(Hl7Message message, List<String> warnings,
                              String charsetName, CharsetResolver.Status charsetStatus,
                              int replacementCount, Charset appliedCharset,
                              boolean charsetDegraded) {
            this.message = Objects.requireNonNull(message, "message");
            this.warnings = warnings == null ? List.of() : List.copyOf(warnings);
            this.charsetName = charsetName == null ? "" : charsetName;
            this.charsetStatus = charsetStatus == null ? CharsetResolver.Status.DEFAULTED : charsetStatus;
            this.replacementCount = Math.max(0, replacementCount);
            this.appliedCharsetName = appliedCharset == null ? this.charsetName : appliedCharset.name();
            this.charsetDegraded = charsetDegraded;
        }

        /**
         * 本次解码是否<b>不可全信</b>：出现了替换字符，或声明的字符集与字节写法对不上。
         *
         * <p>「不可全信」不等于「失败」。两种情形下报文结构仍然可解析、回执仍然能组装，
         * 整条必须继续往下走并进待确认；但其中那句中文可能已经不是对方本来的意思，
         * 信息员必须看到这一条标记。抛异常会把一条能进待确认的报文变成被拒收的报文，
         * 后果比少认一个人大得多。
         */
        public boolean lossy() {
            return replacementCount > 0 || charsetDegraded;
        }
    }

    private static final byte MLLP_SOM = (byte) 0x0B;
    private static final byte MLLP_EOM_FIRST = (byte) 0x1C;
    private static final byte MLLP_EOM_LAST = (byte) 0x0D;
    private static final byte CR = (byte) '\r';
    private static final byte LF = (byte) '\n';

    private FrameDecoder() {
    }

    /**
     * 入站字节 → 解析后的 Hl7Message（含字符集决议与告警）。
     *
     * @throws Hl7ParseException        报文结构非法（缺 MSH、缺段分隔符、空流）
     * @throws UnsupportedCharset      MSH-18 不在已知映射中
     */
    public static DecodedMessage decode(byte[] raw) {
        Objects.requireNonNull(raw, "raw");
        byte[] trimmed = stripMllpFraming(raw);
        if (trimmed.length == 0) {
            throw new Hl7ParseException("入站字节流为空", -1, "");
        }

        // 找首个段分隔符：\r 优先，\n 兜底
        int firstCR = indexOfByte(trimmed, CR);
        if (firstCR < 0) {
            firstCR = indexOfByte(trimmed, LF);
        }
        if (firstCR < 0) {
            throw new Hl7ParseException("入站报文未找到任何段分隔符 (\\r 或 \\n)",
                    -1, snippetBytes(trimmed, 0, 32));
        }

        // MSH 行用 ASCII 解（MSH 段结构与 MSH-18 值都是 ASCII 字符）
        byte[] mshBytes = Arrays.copyOf(trimmed, firstCR);
        String mshLine = new String(mshBytes, StandardCharsets.US_ASCII);

        if (!mshLine.startsWith("MSH")) {
            throw new Hl7ParseException("首段不是 MSH: " + snippet(mshLine, 32),
                    0, snippet(mshLine, 32));
        }
        if (mshLine.length() < 8) {
            throw new Hl7ParseException("MSH 段长度不足 8: " + snippet(mshLine, 32),
                    0, snippet(mshLine, 32));
        }

        String msh18 = extractMsh18(mshLine);
        List<String> warnings = new ArrayList<>();
        CharsetResolver.Decision decision = CharsetResolver.resolve(msh18, warnings);

        // —— 字符集降级：声明的编码与字节实际写法对不上时，改按实际写法解码 ——
        // 详见 charsetMismatchTo 的说明。降级只改「本端拿哪个字形去理解字节」，
        // 回执出站字符集仍按入站声明（ArchiveAckHandler 那一侧没有跟着变）。
        Charset mismatch = charsetMismatchTo(trimmed, decision);
        Charset applied = mismatch != null ? mismatch : decision.charset;
        boolean charsetDegraded = mismatch != null;
        if (charsetDegraded) {
            warnings.add("hl7/charset: MSH-18 声明 " + decision.declaredValue
                    + "，但这些字节同时是合法 UTF-8（含非 ASCII），判定为「声明与实际写法不一致」；"
                    + "本端已降级为按 UTF-8 解码以取回正确文本，出站回执仍按 " + decision.declaredValue
                    + " 声明，请区域平台修正 MSH-18");
        }

        // 用决议字符集解码整段报文
        String decoded = new String(trimmed, applied);
        // 有损解码检测：某些字节在声明的字符集下根本解不出来，解出来是一串 U+FFFD 而不抛异常。
        // 这里只记告警、不中断——报文结构仍可解析，回执仍能组装，整条必须继续往下走。
        int replacements = countReplacements(decoded);
        if (replacements > 0) {
            warnings.add("hl7/charset: 按 " + decision.declaredValue + " 解码出现 " + replacements
                    + " 个替换字符（U+FFFD），该报文存在无法解码的字节；本次按有损结果继续处理，"
                    + "不抛异常、不整条拒收");
        }
        // Hl7Parser 内部继续做 FSRET 转义还原（告警并入同一列表）
        Hl7Message msg = Hl7Parser.parse(decoded, warnings);

        return new DecodedMessage(msg, warnings, decision.declaredValue, decision.status,
                replacements, applied, charsetDegraded);
    }

    /**
     * 「声明 GB18030、字节其实是 UTF-8」这一类错配的判定。
     *
     * <h2>为什么不能用「有没有替换字符」来判</h2>
     * <p>GB18030 覆盖全部字节空间：拿 UTF-8 字节去按 GB18030 解，<b>不会</b>冒出 U+FFFD，
     * 而是解成一串看着像中文的乱码（俗称 mojibake）。也就是说，
     * 「数替换字符」这条最直观的办法在这一类上<b>完全不报警</b>——
     * 而这一类恰恰是国内区域平台最常见的脏报文（上游系统换了编码却没改 MSH-18）。
     * 结果是姓名解成乱码、比对必然判不中，而全链路没有任何一处说过「这条不可信」。
     *
     * <h2>为什么这条判定够稳</h2>
     * <p>判据是<b>双向的</b>：字节必须同时（a）含非 ASCII、且（b）能<b>严格</b>按 UTF-8 解出来。
     * 真正按 GB18030 写的中文正文极少同时满足（b）——UTF-8 的字节结构很严，
     * GB18030 的双字节区撞上合法 UTF-8 序列是巧合。纯 ASCII 报文两种编码结果相同，
     * 因此（a）把它排除掉，不去打扰那些根本没声明中文的报文。
     *
     * <h2>判错了会怎样</h2>
     * <p>最坏情况是「一段真正的 GB18030 文本碰巧也是合法 UTF-8」→ 本端按 UTF-8 解出乱码。
     * 但那时 {@link #countReplacements} 与本条告警会一起响，信息员在待确认台面上看得见，
     * 而<b>看不见</b>的失败（本产品真正要消灭的那一种）不会出现。
     *
     * @return 判定为错配时返回 {@code StandardCharsets.UTF_8}；否则返回 {@code null}
     */
    private static Charset charsetMismatchTo(byte[] trimmed, CharsetResolver.Decision decision) {
        if (!isGbFamily(decision.charset)) {
            return null;
        }
        if (isPureAscii(trimmed)) {
            return null;
        }
        return decodesStrictly(trimmed, StandardCharsets.UTF_8) ? StandardCharsets.UTF_8 : null;
    }

    private static boolean isGbFamily(Charset cs) {
        String name = cs.name().toUpperCase(java.util.Locale.ROOT);
        return name.startsWith("GB18030") || name.startsWith("GBK") || name.startsWith("GB2312");
    }

    private static boolean isPureAscii(byte[] raw) {
        for (byte b : raw) {
            if ((b & 0x80) != 0) {
                return false;
            }
        }
        return true;
    }

    /** 严格解码：解不出就返回 {@code false}（而不是悄悄替换成 U+FFFD 后当成成功）。 */
    private static boolean decodesStrictly(byte[] raw, Charset cs) {
        try {
            cs.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(raw));
            return true;
        } catch (java.nio.charset.CharacterCodingException e) {
            return false;
        }
    }

    /**
     * 数替换字符（U+FFFD）的个数。
     *
     * <p>用「代理项对」的判定而不是 {@code indexOf}：一个合法的 U+FFFD 在
     * 报文里极罕见，但真出现时它与「解不出来的字节」在字符层面<b>不可区分</b>。
     * 本产品选前者——宁可多报一条「有损」告警（多一条人看得到的提示），
     * 也不漏报（漏报的表现是一条姓名乱码的报文被合进了某个人的档案）。
     */
    private static int countReplacements(String decoded) {
        int count = 0;
        for (int i = 0; i < decoded.length(); i++) {
            // U+FFFD 是 BMP 字符，不会参与代理项对，因此按单个 char 逐个计数即可
            if (decoded.charAt(i) == '�') {
                count++;
            }
        }
        return count;
    }

    /** 剥除 MLLP 帧字节（SOM / EOM），不剥除段分隔 CR/LF。 */
    private static byte[] stripMllpFraming(byte[] raw) {
        int start = 0;
        int end = raw.length;
        while (start < end && raw[start] == MLLP_SOM) start++;
        if (end - start >= 2
                && raw[end - 2] == MLLP_EOM_FIRST
                && raw[end - 1] == MLLP_EOM_LAST) {
            end -= 2;
        }
        if (start >= end) return new byte[0];
        return Arrays.copyOfRange(raw, start, end);
    }

    private static int indexOfByte(byte[] arr, byte target) {
        for (int i = 0; i < arr.length; i++) {
            if (arr[i] == target) return i;
        }
        return -1;
    }

    /**
     * 从 MSH 行中提取 MSH-18 字段值。
     * MSH 段的字段下标约定：parts[0]="MSH"，parts[1]=编码字符（MSH-2），
     * parts[2]=MSH-3，…，parts[17]=MSH-18。
     */
    private static String extractMsh18(String mshLine) {
        char fieldSep = mshLine.charAt(3);
        String[] parts = mshLine.split(Pattern.quote(String.valueOf(fieldSep)), -1);
        if (parts.length > 17) {
            return parts[17];
        }
        return "";
    }

    private static String snippet(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    private static String snippetBytes(byte[] b, int off, int len) {
        if (b == null || b.length == 0) return "";
        int n = Math.min(len, b.length - off);
        if (n <= 0) return "";
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append((char) (b[off + i] & 0xFF));
        }
        return snippet(sb.toString(), 32);
    }
}
