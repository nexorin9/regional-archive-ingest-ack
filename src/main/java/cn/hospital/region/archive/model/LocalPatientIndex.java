package cn.hospital.region.archive.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 院内患者索引的<b>候选切片</b> —— 送给模型做身份比对的本地名单。
 *
 * <h2>为什么是「切片」而不是整本索引</h2>
 * <p>院内索引有几万人，全量送模型既超预算又会外泄。确定性基线（T25）先按字面相等、
 * 出生日期一致等硬条件把名单缩到十几条，模型只在<b>这十几条里排序</b>——
 * 模型负责规则做不好的那一步（简繁、同音错别字、姓名空格），不负责「缩小范围」。
 * 这也让模型不可达时的降级代价可控：退回到基线名单，本地仍有候选可看。
 *
 * <h2>为什么身份证是摘要而不是明文</h2>
 * <p>身份证在这套流程里是<b>硬校验锚点</b>（一致则放行、冲突则拒收），
 * 而硬校验走的是确定性代码（T29），不需要模型参与判断。
 * 因此送给模型的每条候选只带 {@code idCardDigest8}（SHA-256 前 8 位，不可逆）：
 * 模型能看出「两条记录指向同一个证件」，却拿不到证件本身。
 * 需要看明文的那一步（确认台上给人看的对照）发生在院内系统里，不经过模型。
 */
public record LocalPatientIndex(List<LocalPatient> patients) {

    /** 一条本地候选。 */
    public record LocalPatient(String localMrn, String name, String birthDate, String idCardDigest8) {
        public LocalPatient {
            Objects.requireNonNull(localMrn, "院内主键不得为 null");
            localMrn = localMrn.trim();
            if (localMrn.isEmpty()) {
                throw new IllegalArgumentException("院内主键不得为空");
            }
            name = name == null ? "" : name;
            birthDate = birthDate == null ? "" : birthDate;
            idCardDigest8 = idCardDigest8 == null ? "" : idCardDigest8;
        }
    }

    public LocalPatientIndex {
        Objects.requireNonNull(patients, "候选集合不得为 null");
        patients = Collections.unmodifiableList(new ArrayList<>(patients));
    }

    public static LocalPatientIndex empty() {
        return new LocalPatientIndex(List.of());
    }

    public int size() {
        return patients.size();
    }

    public boolean isEmpty() {
        return patients.isEmpty();
    }

    /** 按院内主键取一条；取不到返回 {@code null}（调用点是「模型给的候选里有没有它」，不是错误）。 */
    public LocalPatient byMrn(String localMrn) {
        for (LocalPatient p : patients) {
            if (p.localMrn().equals(localMrn)) {
                return p;
            }
        }
        return null;
    }
}