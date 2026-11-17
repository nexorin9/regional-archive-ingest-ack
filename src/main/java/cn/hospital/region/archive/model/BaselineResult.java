package cn.hospital.region.archive.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 基线一次比对的<b>全部产物</b>：排序后的候选 + 与之一一对应的判定。
 *
 * <h2>为什么不是裸 {@code Candidates}</h2>
 * <p>候选列表是给<b>信息员</b>看的（确认台排序、逐条点确认），
 * 判定列表是给<b>对照实验</b>看的（T26 要按变体类别归因、要导出逐条差异样本）。
 * 两者排序完全一致，但用途不同、消费者不同，返回值里分开给，
 * 免得 T26 为了拿档位去反解 {@link IdentityCandidate} 的证据文本。
 *
 * <h2>一一对应是硬约束</h2>
 * <p>{@code decisions.get(i).localMrn()} 必须等于 {@code candidates().get(i).localMrn()}。
 * 构造器直接按这个前提校验：一旦有人只改了候选没改判定，这里就抛，
 * 而不是留到 T26 的差异报告里发现「第 37 条对不上」。
 */
public record BaselineResult(Candidates candidates, List<BaselineDecision> decisions) {

    public BaselineResult {
        Objects.requireNonNull(candidates, "候选集合不得为 null");
        Objects.requireNonNull(decisions, "判定集合不得为 null");
        if (decisions.size() != candidates.size()) {
            throw new IllegalArgumentException("判定与候选必须一一对应：候选 " + candidates.size()
                    + " 条，判定 " + decisions.size() + " 条");
        }
        for (int i = 0; i < decisions.size(); i++) {
            String fromDecision = decisions.get(i).localMrn();
            String fromCandidate = candidates.candidates().get(i).localMrn();
            if (!fromDecision.equals(fromCandidate)) {
                throw new IllegalArgumentException("第 " + i + " 条判定与候选不是同一条：判定=" + fromDecision
                        + "，候选=" + fromCandidate);
            }
        }
        decisions = Collections.unmodifiableList(new ArrayList<>(decisions));
    }

    /** 没有候选：候选切片为空，或全部记录都不满足任何一档。 */
    public static BaselineResult empty() {
        return new BaselineResult(Candidates.empty(), List.of());
    }

    public boolean isEmpty() {
        return candidates.isEmpty();
    }

    public int size() {
        return candidates.size();
    }

    /** 某条院内主键的判定。 */
    public Optional<BaselineDecision> decisionOf(String localMrn) {
        for (BaselineDecision d : decisions) {
            if (d.localMrn().equals(localMrn)) {
                return Optional.of(d);
            }
        }
        return Optional.empty();
    }

    /** 排序最靠前那条的档位；无候选时为空。 */
    public Optional<BaselineLevel> topLevel() {
        return candidates.best()
                .flatMap(best -> decisionOf(best.localMrn()))
                .map(BaselineDecision::level);
    }

    @Override
    public String toString() {
        return "BaselineResult(" + candidates.size() + " 条候选，顶档=" + topLevel().orElse(null) + ")";
    }
}
