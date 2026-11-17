package cn.hospital.region.archive.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 区域平台下发的自定义 Z 段（站点段）—— 字段语义对齐这件事的输入。
 *
 * <p>典型形态（来自区域健康档案平台的中文档案包）：
 * <pre>
 *   ZX1||既往病史摘要|高血压随访;2型糖尿病
 *   ZX2||下次随访时间|20270115
 * </pre>
 * 即 {@code ZX1-2} 是平台自己写的<b>字段名</b>、{@code ZX1-3} 是<b>字段值</b>。
 * 院内本地字段叫 {@code pastHistorySummary}，两边一个字都对不上，
 * 这正是编辑距离与字面相等规则全线失手、必须靠语义的那一段。
 *
 * <p>字段用 <b>1 起始</b>的整数下标存（与 HL7 字段序号一致），缺号即该字段为空——
 * 报文中「有 ZX1-2 但没有 ZX1-1」是完全正常的，不该在构造时就报错。
 */
public record ZSegment(String name, Map<Integer, String> fields) {

    public ZSegment {
        Objects.requireNonNull(name, "段名不得为 null");
        name = name.trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("段名不得为空");
        }
        Objects.requireNonNull(fields, "字段集合不得为 null");
        for (Integer index : fields.keySet()) {
            if (index == null || index < 1) {
                throw new IllegalArgumentException("HL7 字段序号从 1 起，非法下标：" + index);
            }
        }
        fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }

    /** 便捷构造：段名 + 「字段序号 → 文本」。 */
    public static ZSegment of(String name, Map<Integer, String> fields) {
        return new ZSegment(name, fields);
    }

    /** 取某个字段；未出现或为空返回 {@code ""}，与 HL7 空字段语义一致。 */
    public String field(int index) {
        String value = fields.get(index);
        return value == null ? "" : value;
    }

    /** 平台写的字段名（{@code ZX-2}）；为空表示这段没带字段名，对齐时按字段值兜底。 */
    public String label() {
        return field(2);
    }

    /** 字段值（{@code ZX-3}）。 */
    public String value() {
        return field(3);
    }

    /** 供审计与提示词使用的段内定位串，例如 {@code ZX1.3}。 */
    public String locate(int index) {
        return name + "." + index;
    }
}