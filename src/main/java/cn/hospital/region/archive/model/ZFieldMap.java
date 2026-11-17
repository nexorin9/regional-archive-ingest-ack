package cn.hospital.region.archive.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Z 段 → 本地字段的语义对齐结果。
 *
 * <p>键是 Z 段内定位串（{@code ZX1.3} 这类 {@link ZSegment#locate(int)} 形态），
 * 值是<b>一条</b>绑定：本地字段名 + 置信度 + 依据。
 *
 * <p>「依据」不是装饰。信息员在确认台上看到 {@code ZX1.3 → pastHistorySummary（置信度 0.62）}
 * 时，唯一能做的判断依据就是这句话：它说的是「平台写的是既往病史摘要，摘要里出现的是
 * 高血压随访与 2 型糖尿病，所以对应既往病史」。没有依据，人就只能二选一或者不敢选，
 * 而不敢选就等于这条报文永远停在待确认——比直接拒收还麻烦。
 *
 * <p>本类型<b>只表达「模型认为」</b>，不含任何业务状态：它不会让报文落地，
 * 落地只走确认门（T30）。
 */
public record ZFieldMap(Map<String, LocalFieldBinding> bindings) {

    /** 一条绑定。 */
    public record LocalFieldBinding(String localField, double confidence, String rationale) {
        public LocalFieldBinding {
            Objects.requireNonNull(localField, "本地字段名不得为 null");
            localField = localField.trim();
            if (localField.isEmpty()) {
                throw new IllegalArgumentException("本地字段名不得为空");
            }
            if (Double.isNaN(confidence) || confidence < 0 || confidence > 1) {
                throw new IllegalArgumentException("置信度须在 0 到 1 之间，实际 " + confidence);
            }
            rationale = rationale == null ? "" : rationale;
        }
    }

    public ZFieldMap {
        Objects.requireNonNull(bindings, "绑定集合不得为 null");
        bindings = Collections.unmodifiableMap(new LinkedHashMap<>(bindings));
    }

    /** 空结果：模型没能给出任何绑定（不是异常，是「没有可对齐的东西」）。 */
    public static ZFieldMap empty() {
        return new ZFieldMap(Map.of());
    }

    public boolean isEmpty() {
        return bindings.isEmpty();
    }

    public int size() {
        return bindings.size();
    }

    /** 取某个 Z 段字段的绑定；未对齐返回 {@link Optional#empty()}。 */
    public Optional<LocalFieldBinding> bindingOf(String zField) {
        return Optional.ofNullable(bindings.get(zField));
    }

    /** 已给出的本地字段名集合（用于发现「多个 Z 段抢同一个本地字段」这类冲突）。 */
    public Map<String, Integer> localFieldUseCount() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (LocalFieldBinding binding : bindings.values()) {
            counts.merge(binding.localField(), 1, Integer::sum);
        }
        return Collections.unmodifiableMap(counts);
    }
}