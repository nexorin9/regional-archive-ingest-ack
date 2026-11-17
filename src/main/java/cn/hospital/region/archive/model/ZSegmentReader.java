package cn.hospital.region.archive.model;

import cn.hospital.region.archive.hl7.Hl7Message;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 从已结构化的报文里读出 Z 段（站点段）—— 模型语义对齐这一段的输入来源。
 *
 * <p>为什么放在 model 包而不是 hl7 包：Z 段在本产品里的<b>用途</b>只有一件，
 * 就是给字段语义对齐当输入。「哪些段算 Z 段」这个判据来自 HL7 约定，
 * 但「为什么要读它」来自模型环节，两头都占的类放在哪边都不干净，
 * 于是按<b>调用方</b>放，并在此处点明它读的是 hl7 包的结构化结果而不是原始字节。
 *
 * <p><b>只取 Zx 两字符段名</b>（{@code ZX1}、{@code ZBE}），不取 {@code ZZZ} 之外的任意以 Z 开头段。
 * 判据写成「名字长度 2 或 3 且首字母大写 Z」：院内系统自发定义的 {@code ZZZ}
 * 常装的是内部扩展字段，与平台字段对齐没关系，混进来只会把提示词喂脏。
 */
public final class ZSegmentReader {

    private ZSegmentReader() {
    }

    /**
     * 读出报文中全部 Z 段（保持报文内出现顺序）。
     *
     * <p>没有任何 Z 段时返回空列表——那是绝大多数常规报文的常态，不是错误。
     * 上游 {@link OpenAiCompatibleGateway#alignZFields} 对空列表直接短路，
     * <b>不会发出任何 HTTP 请求</b>，因此一条不带 Z 段的报文不会为模型付一次调用成本。
     *
     * @param message 结构化报文；{@code null} 视为无 Z 段
     */
    public static List<ZSegment> read(Hl7Message message) {
        if (message == null) {
            return List.of();
        }
        List<ZSegment> out = new ArrayList<>();
        for (Hl7Message.Segment segment : message.segments()) {
            if (!isZSegment(segment.name())) {
                continue;
            }
            // Segment 的字段下标从 1 起，与 ZSegment 的下标口径一致，逐个搬运不重编号。
            Map<Integer, String> fields = new LinkedHashMap<>();
            for (int idx = 1; idx <= segment.fieldCount(); idx++) {
                String value = segment.field(idx);
                if (value != null && !value.isEmpty()) {
                    fields.put(idx, value);
                }
            }
            out.add(new ZSegment(segment.name(), fields));
        }
        return List.copyOf(out);
    }

    /** 段名是否是「站点段」：首字母 Z，长度 2 或 3，余下全为大写字母或数字。 */
    public static boolean isZSegment(String segmentName) {
        Objects.requireNonNull(segmentName, "段名不得为 null");
        if (segmentName.length() < 2 || segmentName.length() > 3 || segmentName.charAt(0) != 'Z') {
            return false;
        }
        for (int i = 1; i < segmentName.length(); i++) {
            char c = segmentName.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
            if (!ok) {
                return false;
            }
        }
        return true;
    }
}