package cn.hospital.region.archive.http;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 一次入站 HTTP 请求的<b>与传输层无关</b>视图。
 *
 * <h2>为什么要做这一层，而不是让路由直接吃 {@code HttpExchange}</h2>
 * <p>{@code HttpExchange} 绑死在 {@code com.sun.net.httpserver} 上，三处后果：
 * <ul>
 *   <li><b>测试没法脱离 socket</b>。本项目的契约测试要打真实 HTTP（真实端口、真实报文），
 *       但路由逻辑本身（令牌比对、缺字段点名、409 语义）不该每次都占一个端口再等 IO；</li>
 *   <li><b>换传输层就得重写路由</b>。JDK 自带的 server 只是「够用」的选择，不是承诺；</li>
 *   <li><b>入参解析会散落在 handler 里</b>。query 编码、请求体大小上限、头名大小写——
 *       这些一旦每个端点各处理一次，五个端点就有五份不一致的实现。</li>
 * </ul>
 * 本 record 把它们收敛成一处：{@link #query(String)} 已解码、{@link #header(String)} 大小写无关、
 * {@link #body()} 是已按 UTF-8 解码并封顶的文本。
 *
 * @param method  HTTP 方法，大写（{@code GET} / {@code POST} …）
 * @param path    已去掉查询串的路径，<b>保持原样不 decode</b>（批次号里的 {@code %2F} 不能被提前吃掉）
 * @param query   查询参数；同名的多值以逗号连接
 * @param headers 头字段名（小写）到首值的映射
 * @param body    请求体文本；无体时为空串
 */
public record ApiRequest(String method,
                         String path,
                         Map<String, String> query,
                         Map<String, String> headers,
                         String body) {

    /** 请求体上限：确认请求只带四个短字段，1MB 以上的体一定是挂错了东西或有人在灌水。 */
    public static final int MAX_BODY_BYTES = 64 * 1024;

    public ApiRequest {
        method = Objects.requireNonNull(method, "method 不得为 null").toUpperCase(Locale.ROOT);
        path = Objects.requireNonNull(path, "path 不得为 null");
        query = Collections.unmodifiableMap(new LinkedHashMap<>(query));
        headers = Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        body = body == null ? "" : body;
        if (body.getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES) {
            throw new IllegalArgumentException("请求体超过 " + MAX_BODY_BYTES + " 字节上限："
                    + "确认请求只带 ledgerId 与主键两个字段，这么大的体不是本接口该收的东西");
        }
    }

    /** GET（或无体动词）快捷构造。 */
    public static ApiRequest get(String path, Map<String, String> query) {
        return new ApiRequest("GET", path, query, Map.of(), "");
    }

    /**
     * 首个查询参数值。
     *
     * @return 缺失或空白时为空
     */
    public String query(String name) {
        String v = query.get(name);
        return v == null ? "" : v.trim();
    }

    /**
     * 首个查询参数值（未 trim 的原值，用于「用户到底传了什么」的报错文案）。
     *
     * @return 缺失时为空
     */
    public String rawQuery(String name) {
        String v = query.get(name);
        return v == null ? "" : v;
    }

    /** 头字段（大小写无关）。 */
    public Optional<String> header(String name) {
        return Optional.ofNullable(headers.get(name.toLowerCase(Locale.ROOT)));
    }

    /** 是否是某个动词。 */
    public boolean isMethod(String candidate) {
        return method.equalsIgnoreCase(candidate);
    }
}