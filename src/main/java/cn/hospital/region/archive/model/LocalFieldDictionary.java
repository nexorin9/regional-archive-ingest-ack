package cn.hospital.region.archive.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 站内本地字段字典 —— <b>对齐环节的取值范围</b>。
 *
 * <p>它同时被两处使用，缺一不可：
 * <ul>
 *   <li>{@link OpenAiCompatibleGateway} 用它组装提示词里的 {@code local_field_dictionary}，
 *       让模型只能从这些名字里取值，编不出 {@code allergyHistory} 这种听着像、其实不存在的字段；</li>
 *   <li>{@link ZFieldAligner} 用它做<b>落地前的复读校验</b>：模型给回来的本地字段名
 *       必须真的在这本字典里，否则判 {@link AlignmentReason#UNKNOWN_LOCAL_FIELD}。
 *       这一步不是多余的——固定 JSON Schema 管得住字段形状，管不住取值，
 *       模型完全可以规规矩矩地返回 {@code "local_field":"unseenField"}。</li>
 * </ul>
 *
 * <p>抽成独立类型是为了让这两处<b>共用同一份</b>：各写一份的话，
 * 提示词里的字典和对齐后的校验口径迟早会漂移，而漂移的表现是
 * 「模型对齐得好好的，这条却被校验拒了」，排查起来两头都不认账。
 *
 * <p>字典<b>不是</b>院内字段体系的唯一真源（真实字典随院内系统版本走），
 * 它是本产品站点初始版本的那一份。替换它只改本类，业务层一行不动。
 *
 * <p><b>顺序即契约</b>：{@link LinkedHashMap} 保序，不用 {@code Map.copyOf}。
 * 提示词里字典的顺序一变，「同一段 Z 文本两次跑出同一个结论」这条可复现性就没了，
 * 而 T26 的对照实验要的正是这条。
 */
public final class LocalFieldDictionary {

    private static final Map<String, String> STANDARD = build();

    private LocalFieldDictionary() {
    }

    /** 站点初始字典（有序、不可变）。 */
    public static Map<String, String> standard() {
        return STANDARD;
    }

    /** 本地字段名是否在字典内。{@code null} 与空白一律判否。 */
    public static boolean contains(String localField) {
        return localField != null && !localField.trim().isEmpty() && STANDARD.containsKey(localField.trim());
    }

    /** 字典条目数；{@link ZFieldAligner} 用它判断「这份档案包本地装不装得下」。 */
    public static int size() {
        return STANDARD.size();
    }

    /** 取中文说明；不在字典内返回空串（调用方需先判 {@link #contains}）。 */
    public static String zhLabel(String localField) {
        if (localField == null) {
            return "";
        }
        String label = STANDARD.get(localField.trim());
        return label == null ? "" : label;
    }

    private static Map<String, String> build() {
        Map<String, String> dict = new LinkedHashMap<>();
        dict.put("pastHistorySummary", "既往病史摘要");
        dict.put("nextFollowUpDate", "下次随访时间");
        dict.put("attendingDoctorName", "责任医生姓名");
        dict.put("recordChangeType", "档案变更类型");
        dict.put("chronicDiseaseTag", "慢病标签");
        dict.put("medicationAdherence", "用药依从性");
        return Collections.unmodifiableMap(dict);
    }
}