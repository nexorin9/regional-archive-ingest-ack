package cn.hospital.region.archive.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 一次身份候选调用的结果 —— 一个<b>有名字的</b>列表，而不是裸 {@code List}。
 *
 * <h2>为什么要包一层</h2>
 * <p>后续任务要给这个结果加东西：降级标记（T21）、被基线排除掉的条目（T27）、
 * 分桶统计需要的置信度分布（T28）。如果现在返回裸 {@code List}，
 * 那三处都得改方法签名，而方法签名是业务层唯一认的东西——改一次就要动一遍调用方。
 * 现在包一层，后面只加字段，签名不动。
 *
 * <p>排序在这里定死：<b>置信度降序，同分按院内主键升序</b>。
 * 同分也排，是为了让「同一条报文重投两次给出同样的候选顺序」成立——
 * 否则人工复核量会在两次运行之间漂移，而漂移本身就会被当成「系统不稳定」。
 */
public record Candidates(List<IdentityCandidate> candidates) {

    /** 置信度降序；同分按院内主键升序，保证结果可复现。 */
    public static final Comparator<IdentityCandidate> BY_CONFIDENCE_DESC =
            Comparator.comparingDouble(IdentityCandidate::confidence).reversed()
                    .thenComparing(IdentityCandidate::localMrn);

    public Candidates {
        Objects.requireNonNull(candidates, "候选集合不得为 null");
        List<IdentityCandidate> sorted = new ArrayList<>(candidates);
        sorted.sort(BY_CONFIDENCE_DESC);
        candidates = Collections.unmodifiableList(sorted);
    }

    public static Candidates empty() {
        return new Candidates(List.of());
    }

    public boolean isEmpty() {
        return candidates.isEmpty();
    }

    public int size() {
        return candidates.size();
    }

    /** 置信度最高的一条。 */
    public Optional<IdentityCandidate> best() {
        return candidates.isEmpty() ? Optional.empty() : Optional.of(candidates.get(0));
    }

    /** 前 {@code limit} 条（确认台一次只展示得下几条）。 */
    public Candidates top(int limit) {
        if (limit < 0) {
            throw new IllegalArgumentException("limit 不得为负数：" + limit);
        }
        if (limit >= candidates.size()) {
            return this;
        }
        return new Candidates(candidates.subList(0, limit));
    }

    @Override
    public String toString() {
        return "Candidates(" + candidates.size() + " 条)";
    }
}