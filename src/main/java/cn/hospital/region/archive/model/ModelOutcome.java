package cn.hospital.region.archive.model;

import java.util.Objects;
import java.util.Optional;

/**
 * 模型一侧的结果 —— <b>要么拿到值，要么拿到 {@link DegradedResult}，没有第三种</b>。
 *
 * <p>做成封闭的两支 sealed 接口（而不是「返回 null 或抛异常」）是为了让调用点
 * <b>在编译期就必须回答一个问题</b>：拿到结果之后降级了该怎么办。
 * 返回 {@code null} 的写法里，这个问题的答案会被 {@code if (x != null)} 之后的一长段代码冲掉；
 * 抛异常的写法里，它会被某个 {@code catch} 里的一行 {@code return;} 冲掉。
 *
 * <pre>
 *   ModelOutcome&lt;Candidates&gt; outcome = model.identityCandidates(...);
 *   if (outcome.degraded()) {
 *       // 唯一允许的分支：推进到待确认 + degraded=true，绝不推进到已落地或拒收
 *       ...
 *   } else {
 *       ...outcome.value()...
 *   }
 * </pre>
 *
 * @param <T> 成功时的取值类型
 */
public sealed interface ModelOutcome<T> {

    /** 本次结果是不是降级。降级时 {@link #value()} 不可用，必须走 {@link #degradedResult()}。 */
    boolean degraded();

    /**
     * 降级详情；未降级时返回空。
     *
     * <p>用 {@link Optional} 而不是抛异常：这是一个<b>必须被正常处理</b>的分支，
     * 抛异常会让「降级」在语义上仍然像一种事故，而它在本产品里是<b>正常处置</b>之一。
     */
    Optional<DegradedResult> degradedResult();

    /** 成功时的取值；降级时抛 {@link IllegalStateException}（不能把降级当空值用）。 */
    T value();

    /** 成功。 */
    record Succeeded<T>(T value) implements ModelOutcome<T> {

        @Override
        public boolean degraded() {
            return false;
        }

        @Override
        public Optional<DegradedResult> degradedResult() {
            return Optional.empty();
        }

        @Override
        public T value() {
            return value;
        }
    }

    /** 降级：模型不可用或响应不可信，本条按确定性基线继续，由人确认。 */
    record Degraded<T>(DegradedResult result) implements ModelOutcome<T> {

        public Degraded {
            Objects.requireNonNull(result, "降级结果不得为 null");
        }

        @Override
        public boolean degraded() {
            return true;
        }

        @Override
        public Optional<DegradedResult> degradedResult() {
            return Optional.of(result);
        }

        @Override
        public T value() {
            throw new IllegalStateException("降级结果没有取值：" + result);
        }
    }

    static <T> ModelOutcome<T> succeeded(T value) {
        return new Succeeded<>(value);
    }

    static <T> ModelOutcome<T> degraded(DegradedResult result) {
        return new Degraded<>(result);
    }

    /** 便捷构造：按错误码 + 操作名 + 尝试次数组装降级结果。 */
    static <T> ModelOutcome<T> degraded(ModelErrorCode code, String operation, int attempts, String detail) {
        return new Degraded<>(DegradedResult.of(code, operation, attempts, detail));
    }
}