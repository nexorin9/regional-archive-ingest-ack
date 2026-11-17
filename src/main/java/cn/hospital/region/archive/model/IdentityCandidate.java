package cn.hospital.region.archive.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 一条身份候选 —— 「这条区域平台报文，可能说的是这位居民」。
 *
 * <h2>候选，不是结论</h2>
 * <p>本类型存在的全部意义是让信息员在确认台上少打几个字、少查一个系统。
 * 它<b>不能</b>让报文落地：{@code 待确认 → 已落地} 只能由确认门（T30）在人工确认动作之后推进，
 * 没有任何路径允许模型输出直接推进业务状态。
 *
 * <h2>置信度低也是有效输出</h2>
 * <p>模型给 0.2 分、理由写「姓名繁简不同、出生日期一致、地址不在候选里」，比给一个 0.95 分
 * 但理由空洞的结果有用得多：前者告诉人「这条大概没戏，可以跳过」，
 * 后者只会让人在错误的方向上点确认。所以 {@code confidence} 不做下限过滤，
 * 过滤交给 T23/T27 的阈值，而不是在这里悄悄丢候选。
 */
public record IdentityCandidate(String localMrn, double confidence, List<Evidence> evidence) {

    /** 一条依据：哪个字段支持（或反对）这条候选。 */
    public record Evidence(String field, String detail) {
        public Evidence {
            Objects.requireNonNull(field, "依据字段名不得为 null");
            field = field.trim();
            if (field.isEmpty()) {
                throw new IllegalArgumentException("依据字段名不得为空");
            }
            detail = detail == null ? "" : detail;
        }
    }

    public IdentityCandidate {
        Objects.requireNonNull(localMrn, "院内主键不得为 null");
        localMrn = localMrn.trim();
        if (localMrn.isEmpty()) {
            throw new IllegalArgumentException("院内主键不得为空");
        }
        if (Double.isNaN(confidence) || confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("置信度须在 0 到 1 之间，实际 " + confidence);
        }
        Objects.requireNonNull(evidence, "依据集合不得为 null");
        evidence = Collections.unmodifiableList(new ArrayList<>(evidence));
    }

    /** 单行摘要，进确认台列表与 CSV 导出。 */
    public String summaryLine() {
        return localMrn + " 置信度=" + confidence + " 依据=" + evidence.size() + " 条";
    }
}