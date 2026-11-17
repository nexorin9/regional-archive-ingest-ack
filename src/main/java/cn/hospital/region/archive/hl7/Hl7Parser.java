package cn.hospital.region.archive.hl7;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * HL7 ER7 字符串解析器，把入站字符串拆为按段名 + 字段下标直接寻址的索引模型。
 *
 * <p>主路径借鉴源产品 {@code HL7Parser.parse(BufferedReader)} 的：
 * <ul>
 *   <li>{@code isHeaderSegment} 判定（仅 MSH/BHS/FHS 视作 header）。</li>
 *   <li>header 段用 {@code setDelimiters(line.substring(3, 8))} 读 5 个分隔符。</li>
 *   <li>header 段从第 8 字符起 tokenize；非 header 段从全行 tokenize。</li>
 * </ul>
 *
 * <p>对源 SAX 事件流的改动：删除 {@code startElement/endElement/characters} 等所有 SAX 调用，
 * 改为构造 {@link Hl7Message} 索引模型（按段名 + 字段下标直接寻址的查询结构）。
 *
 * <p>T5 仅做分隔符与索引；T6 在本类的字段值切分后调用 {@link Escapes#restore(String, Delimiters, List)}
 * 把 FSRET 五类转义序列还原为对应分隔符；未成对序列保留原文并写告警，不抛错。
 * 字符集解码由 T6 在 {@link FrameDecoder#decode(byte[])} 入口先做，{@code parse(String)} 收到的就是 Java 字符串。
 */
public final class Hl7Parser {

    private static final char CR = '\r';

    private Hl7Parser() {
    }

    /**
     * 入口（不带告警收集）：解析一段 HL7 ER7 字符串。
     */
    public static Hl7Message parse(String er7) {
        return parse(er7, null);
    }

    /**
     * 入口（带告警收集）：解析一段 HL7 ER7 字符串，并对每个字段值做 FSRET 转义还原。
     *
     * <p>对端若送的是 {@code \r\n} 或 {@code \n} 行尾，调方需在调用本方法前自行归一为 {@code \r}（T3 MllpFrameCodec 已统一按 CR 切）。
     *
     * @param er7      已按字符集解码的 ER7 字符串（多段以 {@code \r} 分隔、以 {@code \r} 结尾）
     * @param warnings 解析告警容器（未成对转义序列会写入；可为 null 表示不收集）
     */
    public static Hl7Message parse(String er7, List<String> warnings) {
        if (er7 == null || er7.isEmpty()) {
            throw new Hl7ParseException("入站报文为空", -1, "");
        }
        // 归一所有 CR 形态：保留已有 \r；\r\n 与 \n 视为等同
        String normalized = er7.replace("\r\n", "\r").replace('\n', '\r');
        String[] lines = normalized.split(String.valueOf(CR), -1);
        List<Hl7Message.Segment> segments = new ArrayList<>(lines.length);

        Delimiters delimiters = null;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line == null || line.isEmpty()) continue;
            if (line.length() < 3) {
                throw new Hl7ParseException("段长度不足 3 字节: " + snippetOf(line), i, snippetOf(line));
            }
            // 段名长度：从行首到第一个字段分隔符为止。
            // HL7 v2 规定段标识符最长 3 字符，常见 PID/MSH/NTE 为 3，站点段 ZX 为 2；
            // 此前 substring(0,3) 会把 ZX|... 误读成段名 "ZX|"，导致 ZSegmentReader 漏判。
            // 找不到分隔符的整段（无论长度）按原先的「段长度不足 3 字节」口径拒收。
            char fsForSeg;
            int sepAt;
            if (i == 0) {
                // Header 行：MSH|^~\&|... 的第 4 个字符（索引 3）就是字段分隔符，
                // 兼容非默认分隔符（!$%*& 等），不能用默认的 '|' 去找位置。
                fsForSeg = line.charAt(3);
                sepAt = 3;
            } else {
                fsForSeg = delimiters != null ? delimiters.fieldSeparator() : Delimiters.DEFAULT.fieldSeparator();
                sepAt = line.indexOf(fsForSeg);
                if (sepAt < 1) {
                    throw new Hl7ParseException("段名格式非法（缺少字段分隔符）: " + snippetOf(line), i, snippetOf(line));
                }
            }
            String segName = line.substring(0, sepAt);
            if (segName.length() < 1 || segName.length() > 3) {
                throw new Hl7ParseException("段名长度非法: " + snippetOf(line), i, snippetOf(line));
            }
            boolean header = Delimiters.isHeaderSegment(segName);
            if (header) {
                if (line.length() < 8) {
                    throw new Hl7ParseException("header 段长度不足 8: " + snippetOf(line), i, snippetOf(line));
                }
                delimiters = Delimiters.readFromHeader(line);
                // MSH 段：fields[0] = separator 字符本身（HL7 规范 MSH-1=separator）
                // fields[1] = 编码字符（HL7 规范 MSH-2 = ^~\& 等；这里**不**还原转义——编码字符本身就是定界符定义）
                // fields[2..] 才是 MSH-3（sending application）起的实际数据，需 FSRET 还原
                List<String> fields = new ArrayList<>();
                fields.add(String.valueOf(delimiters.fieldSeparator()));        // MSH-1
                fields.add(line.substring(4, 8));                                // MSH-2 (5-8索引)
                // 跳过 MSH-1 + MSH-2 的 8 字节前缀；rest 以分隔符字符开头
                String rest = line.substring(8);
                String[] tks = rest.split(Pattern.quote(Character.toString(delimiters.fieldSeparator())), -1);
                // tks[0] 是空串（rest 以分隔符开头），丢弃；从 tks[1] 起是 MSH-3 / MSH-4 / ...
                for (int k = 1; k < tks.length; k++) {
                    fields.add(Escapes.restore(tks[k], delimiters, warnings));
                }
                segments.add(new Hl7Message.Segment(segName, i, line, fields, delimiters));
            } else {
                if (delimiters == null) {
                    throw new Hl7ParseException("非 header 段出现在 header 段之前: " + snippetOf(line), i, snippetOf(line));
                }
                String[] tks = line.split(Pattern.quote(Character.toString(delimiters.fieldSeparator())), -1);
                List<String> fields = new ArrayList<>(tks.length);
                for (int j = 0; j < tks.length; j++) {
                    // 非 header 段：tks[0] = segName；fields 从 tks[1] 起，全部 FSRET 还原
                    if (j == 0) continue;
                    fields.add(Escapes.restore(tks[j], delimiters, warnings));
                }
                segments.add(new Hl7Message.Segment(segName, i, line, fields, delimiters));
            }
        }

        if (segments.isEmpty()) {
            throw new Hl7ParseException("入站报文未解析出任何段", -1, "");
        }
        return new Hl7Message(delimiters, segments);
    }

    private static String snippetOf(String line) {
        if (line == null) return "";
        return line.length() > 32 ? line.substring(0, 32) + "..." : line;
    }
}
