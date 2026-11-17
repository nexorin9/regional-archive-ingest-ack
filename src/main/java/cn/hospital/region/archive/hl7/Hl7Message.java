package cn.hospital.region.archive.hl7;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 解析后 HL7 报文的索引模型。
 *
 * <p>源产品 {@code HL7Parser} 走的是 SAX 事件流（{@code startElement/endElement/characters}），
 * 本产品改写为**按段名 + 字段下标直接寻址**的查询结构，借鉴的是 HAPI Terser 的访问语义：
 * <ul>
 *   <li>字段下标 1-based，与 HL7 v2 规范一致（MSH-1、MSH-9、PID-3 等）。</li>
 *   <li>对 MSH/BHS/FHS 段，第 1 个"字段"存的就是该行使用的字段分隔符字符本身（与 HL7 规范 MSH-1=separator 一致）。</li>
 *   <li>字段内部不展开；想取重复用 {@link #repetitions(String, int)}，想取组件用 {@link #component(String, int, int)}。</li>
 * </ul>
 *
 * <p>本类不负责转义序列还原（T6 由 Hl7Parser 在解析期处理）。
 */
public final class Hl7Message {

    private final Delimiters delimiters;
    private final List<Segment> segmentsInOrder;
    private final Map<String, List<Segment>> byName;

    Hl7Message(Delimiters delimiters, List<Segment> segments) {
        this.delimiters = Objects.requireNonNull(delimiters, "delimiters");
        this.segmentsInOrder = List.copyOf(segments);
        Map<String, List<Segment>> by = new LinkedHashMap<>();
        for (Segment s : segmentsInOrder) {
            by.computeIfAbsent(s.name(), k -> new ArrayList<>()).add(s);
        }
        Map<String, List<Segment>> frozen = new LinkedHashMap<>();
        for (Map.Entry<String, List<Segment>> e : by.entrySet()) {
            frozen.put(e.getKey(), List.copyOf(e.getValue()));
        }
        this.byName = Collections.unmodifiableMap(frozen);
    }

    public Delimiters delimiters() { return delimiters; }

    public List<Segment> segments() { return segmentsInOrder; }

    public int segmentCount() { return segmentsInOrder.size(); }

    public Segment segmentAt(int ordinal) {
        if (ordinal < 0 || ordinal >= segmentsInOrder.size()) {
            throw new SegmentNotFoundException("按 ordinal=" + ordinal + " 越界（共有 " + segmentsInOrder.size() + " 段）");
        }
        return segmentsInOrder.get(ordinal);
    }

    /**
     * 段是否存在（对应 HAPI Terser {@code get(name) != null}）。
     *
     * <p>T7 的 {@link FieldLocator} 需要「先问有没有、再取值」的两步走，
     * 否则缺段只能靠捕 {@link SegmentNotFoundException} 表达，定位信息会散在异常里。
     */
    public boolean has(String name) {
        Objects.requireNonNull(name, "name");
        List<Segment> matches = byName.get(name);
        return matches != null && !matches.isEmpty();
    }

    public Segment segment(String name) {
        return segment(name, 1);
    }

    public Segment segment(String name, int occurrence) {
        Objects.requireNonNull(name, "name");
        if (occurrence < 1) {
            throw new SegmentNotFoundException("occurrence 必须 ≥ 1，传入 " + occurrence);
        }
        List<Segment> matches = byName.get(name);
        if (matches == null || matches.isEmpty()) {
            throw new SegmentNotFoundException("段名不存在: " + name);
        }
        if (occurrence > matches.size()) {
            throw new SegmentNotFoundException("段 " + name + " 第 " + occurrence
                    + " 次出现不存在（共有 " + matches.size() + " 次）");
        }
        return matches.get(occurrence - 1);
    }

    /**
     * 字段访问（1-based）。返回字段的**第一个重复**重组后的字符串；空字段返回 ""；
     * 越界抛 {@link FieldIndexOutOfBounds}。
     */
    public String field(String segName, int idx) {
        Segment s = segment(segName);
        return s.field(idx);
    }

    public List<String> repetitions(String segName, int idx) {
        Segment s = segment(segName);
        return s.repetitions(idx);
    }

    public String component(String segName, int idx, int compIdx) {
        Segment s = segment(segName);
        return s.component(idx, compIdx);
    }

    public String subcomponent(String segName, int idx, int compIdx, int subIdx) {
        Segment s = segment(segName);
        return s.subcomponent(idx, compIdx, subIdx);
    }

    /**
     * 一段报文内自下标约定的 1-based 字段序列。
     *
     * <p>对 MSH/BHS/FHS，{@code fields[0]}（即"第 1 个字段"）存的是该行使用的分隔符字符本身；
     * 其他段从第一个有意义的字段开始。
     */
    public static final class Segment {
        private final String name;
        private final int ordinal;
        private final String rawLine;
        private final List<String> fields;
        private final Delimiters delimiters;

        Segment(String name, int ordinal, String rawLine, List<String> fields, Delimiters delimiters) {
            this.name = Objects.requireNonNull(name, "name");
            this.ordinal = ordinal;
            this.rawLine = rawLine;
            this.fields = List.copyOf(Objects.requireNonNull(fields, "fields"));
            this.delimiters = Objects.requireNonNull(delimiters, "delimiters");
        }

        public String name() { return name; }

        public int ordinal() { return ordinal; }

        public String rawLine() { return rawLine; }

        public int fieldCount() { return fields.size(); }

        public List<String> fields() { return fields; }

        public String field(int idx) {
            return getFirstRepJoined(idx);
        }

        public List<String> repetitions(int idx) {
            if (idx < 1 || idx > fields.size()) {
                throw new FieldIndexOutOfBounds(name, idx, fields.size());
            }
            String raw = fields.get(idx - 1);
            if (raw.isEmpty()) return List.of("");
            return List.of(raw.split(Pattern.quote(Character.toString(delimiters.repetitionSeparator())), -1));
        }

        public String component(int idx, int compIdx) {
            List<String> reps = repetitions(idx);
            String firstRep = reps.get(0);
            if (firstRep.isEmpty()) {
                if (compIdx == 1) return "";
                throw new ComponentIndexOutOfBounds(name, idx, compIdx, 0);
            }
            String[] comps = firstRep.split(Pattern.quote(Character.toString(delimiters.componentSeparator())), -1);
            if (compIdx < 1 || compIdx > comps.length) {
                throw new ComponentIndexOutOfBounds(name, idx, compIdx, comps.length);
            }
            return comps[compIdx - 1];
        }

        public String subcomponent(int idx, int compIdx, int subIdx) {
            List<String> reps = repetitions(idx);
            String firstRep = reps.get(0);
            if (firstRep.isEmpty()) {
                if (compIdx == 1 && subIdx == 1) return "";
                throw new SubcomponentIndexOutOfBounds(name, idx, compIdx, subIdx, 0);
            }
            String[] comps = firstRep.split(Pattern.quote(Character.toString(delimiters.componentSeparator())), -1);
            if (compIdx < 1 || compIdx > comps.length) {
                throw new SubcomponentIndexOutOfBounds(name, idx, compIdx, subIdx, comps.length);
            }
            String comp = comps[compIdx - 1];
            if (comp.isEmpty()) {
                if (subIdx == 1) return "";
                throw new SubcomponentIndexOutOfBounds(name, idx, compIdx, subIdx, 0);
            }
            String[] subs = comp.split(Pattern.quote(Character.toString(delimiters.subcomponentSeparator())), -1);
            if (subIdx < 1 || subIdx > subs.length) {
                throw new SubcomponentIndexOutOfBounds(name, idx, compIdx, subIdx, subs.length);
            }
            return subs[subIdx - 1];
        }

        private String getFirstRepJoined(int idx) {
            if (idx < 1 || idx > fields.size()) {
                throw new FieldIndexOutOfBounds(name, idx, fields.size());
            }
            String raw = fields.get(idx - 1);
            if (raw == null || raw.isEmpty()) return "";
            // MSH 第 2 个字段是 4 字符编码字符串，其本身含 ~ 字符，
            // 不能与一般字段一样按"首个 ~ 视为重复边界"截断。
            // 与 Delimiters 四个编码字符拼出的 4 字符串逐字相等即认定为编码字段，原值返回。
            char[] enc = {delimiters.componentSeparator(), delimiters.repetitionSeparator(),
                    delimiters.escapeCharacter(), delimiters.subcomponentSeparator()};
            String encodingChars = new String(enc);
            if ("MSH".equals(name) && idx == 2 && encodingChars.equals(raw)) {
                return raw;
            }
            // 第一重复（首个 ~ 之前的部分），保留组件与子组件原始拼接
            int tilde = raw.indexOf(delimiters.repetitionSeparator());
            return tilde < 0 ? raw : raw.substring(0, tilde);
        }

        @Override
        public String toString() {
            return "Segment{" + name + " ord=" + ordinal + " fields=" + fields.size() + "}";
        }
    }
}
