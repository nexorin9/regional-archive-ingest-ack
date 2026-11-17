package cn.hospital.region.archive.model;

import cn.hospital.region.archive.ledger.LedgerRedaction;

import java.util.Map;
import java.util.Objects;

/**
 * 模型入参摘要 —— 调用审计里唯一允许落地的入参形态。
 *
 * <h2>只落「字段名 + 长度」，不落原文</h2>
 * <p>模型调用审计要能回答运维的三个问题：这条报文有没有走模型？走的哪个模型、多久、
 * 花了多少 token？入参摘要顺带回答第四个：<b>送出去的东西有多长</b>。
 * 但一旦把 Z 段自由文本或姓名写进审计行，审计表就变成了第二份患者明细表——
 * 它会比台账传播得更快、活得更久（导 CSV、贴工作群、随备份走），而它<b>本来只是给人看的</b>。
 *
 * <p>所以本类<b>构造完就把原文丢掉了</b>：内部只留两样东西——
 * {@code 字段名(len=N)} 的形态行，和一份 SHA-256 前 8 位摘要。
 * 摘要不可逆，但同一条报文重投多次时摘要不变，信息员据此能确认「这两次送给模型的是同一批东西」，
 * 这正是对账时唯一需要的性质；口径与台账导出共用 {@link LedgerRedaction#digest8}，
 * 免得同一份脱敏规则写两遍、日后各改各的。
 *
 * <p>注意 {@link #toString()} 返回的也是脱敏后的形态行：日志里不小心把整个对象打出去，
 * 落盘的仍然是 {@code ZX1.3(len=11)} 而不是姓名。
 */
public final class PromptSummary {

    /** 摘要拼接用的分隔符：U+001F，任何患者数据里都不可能出现，避免相邻字段粘连。 */
    private static final char SEP = '\u001F';

    private final String operation;
    private final String fieldLine;
    private final String digest;

    private PromptSummary(String operation, String fieldLine, String digest) {
        this.operation = operation;
        this.fieldLine = fieldLine;
        this.digest = digest;
    }

    /**
     * 用「字段名 → 原文」的映射生成摘要；原文只在本方法内被读取一次，之后不再持有。
     *
     * @param operation 操作名（{@code z_field_map} / {@code identity_candidates}）
     * @param values    字段名到原文的映射，按迭代顺序写进形态行（用保序 Map 以便两轮可复现）
     */
    public static PromptSummary of(String operation, Map<String, String> values) {
        Objects.requireNonNull(operation, "operation 不得为 null");
        Objects.requireNonNull(values, "values 不得为 null");
        StringBuilder line = new StringBuilder(operation);
        StringBuilder raw = new StringBuilder();
        for (Map.Entry<String, String> e : values.entrySet()) {
            String value = e.getValue();
            line.append(" | ").append(e.getKey()).append(value == null ? "(missing)" : "(len=" + value.length() + ")");
            raw.append(e.getKey()).append(SEP).append(value == null ? "" : value).append(SEP);
        }
        return new PromptSummary(operation, line.toString(), LedgerRedaction.digest8(raw.toString()));
    }

    /** 操作名。 */
    public String operation() {
        return operation;
    }

    /** 形态行，例如 {@code z_field_map | ZX1.2(len=6) | ZX1.3(len=11)}。 */
    public String fieldLine() {
        return fieldLine;
    }

    /** 入参 SHA-256 前 8 位（十六进制）；用于「同一批入参」的关联，不可用回查。 */
    public String digest() {
        return digest;
    }

    /** 写进审计行的一行：形态行 + 摘要。 */
    public String line() {
        return fieldLine + " digest=" + digest;
    }

    @Override
    public String toString() {
        return line();
    }
}