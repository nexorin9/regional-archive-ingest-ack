package cn.hospital.region.archive.model;

import java.time.Instant;
import java.util.Objects;

/**
 * 一次模型调用的审计行。
 *
 * <p>回答运维的四个问题：<b>谁（哪个模型）· 什么时候 · 多久 · 花了多少 token</b>，
 * 外加两条业务侧最关心的：这条调用的入参<b>摘要</b>是什么、它是否走了降级路径。
 *
 * <h2>刻意不落的字段</h2>
 * <ul>
 *   <li><b>入参原文</b>：只留 {@link PromptSummary} 的形态行（字段名 + 长度 + 摘要）。
 *       审计表不是第二份患者明细表；</li>
 *   <li><b>密钥</b>：{@code apiKey} 不进审计，配置摘要里也是 {@code ***}；</li>
 *   <li><b>响应原文</b>：模型的自由文本不进审计。只在结构校验失败时把「哪个字段不对」
 *       写进错误消息——那是有用的排障信息，而整段响应往往带着可识别内容。</li>
 * </ul>
 *
 * <p>{@code promptTokens} / {@code completionTokens}：响应未带 {@code usage} 段时记 0，
 * 本地模型端点不返回 token 用量是常态，编一个数出来会让「token 花费」这张报表好看得没有意义。
 *
 * <h2>失败调用也留一行</h2>
 * <p>降级的那几次尝试同样写审计行，且 {@code degraded=true}、{@code errorCode} 非空。
 * 「今天模型挂了没有、挂了多久、挂的哪一类」是运维<b>每天</b>要回答的问题，
 * 只有成功调用留痕的话，这个问题在挂掉的那些天里<b>一个字都答不出来</b>。
 * 一次重试序列会留下多行（每次尝试一行），T28 的统计按 {@code errorCode} 分桶即可。
 */
public record ModelCallRecord(
        Instant at,
        String model,
        String operation,
        long elapsedMillis,
        int promptTokens,
        int completionTokens,
        String requestId,
        boolean degraded,
        String inputSummary,
        ModelErrorCode errorCode) {

    /** 响应没给 {@code id} 时占位：{@code "(未返回)"}。 */
    public static final String NO_REQUEST_ID = "(未返回)";

    public ModelCallRecord {
        Objects.requireNonNull(at, "调用时间不得为 null");
        Objects.requireNonNull(model, "模型名不得为 null");
        Objects.requireNonNull(operation, "操作名不得为 null");
        if (elapsedMillis < 0) {
            throw new IllegalArgumentException("耗时不得为负数：" + elapsedMillis);
        }
        if (promptTokens < 0 || completionTokens < 0) {
            throw new IllegalArgumentException("token 用量不得为负数：" + promptTokens + "/" + completionTokens);
        }
        requestId = (requestId == null || requestId.isBlank()) ? NO_REQUEST_ID : requestId;
        inputSummary = inputSummary == null ? "" : inputSummary;
        // 不变量：降级行必须带错误码，否则「降级了但不知道为什么」在报表上就退化成一个布尔值。
        // 反过来不成立：成功行没有错误码是正常的。
        if (degraded && errorCode == null) {
            throw new IllegalArgumentException("降级调用必须带错误码（operation=" + operation + "）");
        }
    }

    /** 成功调用（无错误码）——保持八参构造可用，避免每个调用点都要多传一个 null。 */
    public ModelCallRecord(Instant at, String model, String operation, long elapsedMillis,
                           int promptTokens, int completionTokens, String requestId,
                           boolean degraded, String inputSummary) {
        this(at, model, operation, elapsedMillis, promptTokens, completionTokens, requestId,
                degraded, inputSummary, null);
    }

    public int totalTokens() {
        return promptTokens + completionTokens;
    }

    /** 单行审计输出；不含密钥、不含入参原文。 */
    public String summaryLine() {
        return String.join(" | ",
                "模型调用 " + operation,
                "model=" + model,
                "耗时=" + elapsedMillis + "ms",
                "token=" + promptTokens + "+" + completionTokens + "=" + totalTokens(),
                "requestId=" + requestId,
                "降级=" + (degraded ? "是" : "否"),
                (errorCode == null ? "" : "错误码=" + errorCode.auditToken() + " | "),
                "入参[" + inputSummary + "]");
    }

    @Override
    public String toString() {
        return summaryLine();
    }
}