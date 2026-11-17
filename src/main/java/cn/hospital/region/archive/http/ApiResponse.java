package cn.hospital.region.archive.http;

import cn.hospital.region.archive.model.Json;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 一次响应 —— 状态码与<b>已渲染好的 JSON 文本</b>。
 *
 * <h2>为什么在路由里就渲染完，而不是交给传输层去序列化</h2>
 * <p>本项目的响应体必须满足两条：<b>字段名固定</b>（T39 的 OpenAPI 对齐靠这个），
 * 以及<b>失败时绝不带内部细节</b>。把渲染提前到路由里，渲染就是唯一出口 ——
 * 任何端点想「顺手把 exception 带出去」都得先绕过 {@link #ok} / {@link #error} 手工拼串，
 * 而那两处会把 {@code detail} 白名单化。
 *
 * @param status HTTP 状态码
 * @param json   响应体（合法 JSON 文本；{@code null} 表示无体，如 204）
 */
public record ApiResponse(int status, String json) {

    public ApiResponse {
        if (status < 100 || status > 599) {
            throw new IllegalArgumentException("HTTP 状态码必须在 100..599，实际=" + status);
        }
        if (json != null && !json.isBlank()) {
            // 立刻验一次：渲染错了要在路由里炸，而不是等院内系统收到一坨非 JSON 再来问。
            Json.parse(json);
        }
    }

    /** 成功（200）+ 对象体。 */
    public static ApiResponse ok(Map<String, Object> body) {
        return new ApiResponse(200, Json.write(body));
    }

    /**
     * 成功（201/200）+ 对象体，用于「确认生效」这类<b>确实产生了一条新事实</b>的动作。
     *
     * <p>与 {@link #ok} 分开是刻意的：HTTP 语义里 201 会带 {@code Location}，
     * 而确认动作不产生新地址，因此对外仍走 200；本方法的存在只为让调用点的意图显式。
     */
    public static ApiResponse confirmed(Map<String, Object> body) {
        return new ApiResponse(200, Json.write(body));
    }

    /** 无体响应。 */
    public static ApiResponse empty(int status) {
        return new ApiResponse(status, null);
    }

    /**
     * 失败响应：{@code errorCode} + {@code message} + {@code path} 三个字段恒在。
     *
     * @param code    错误码；状态码由它决定，调用点不自己写数字
     * @param detail  可选补充（如 {@code ledgerId} 与当时状态）；<b>原样进响应体</b>，
     *                所以只允许放不含患者可识别明文的值
     */
    public static ApiResponse error(ApiErrorCode code, String message, String path,
                                    Map<String, Object> detail) {
        Objects.requireNonNull(code, "错误码不得为 null");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("errorCode", code.name());
        body.put("message", (message == null || message.isBlank()) ? code.zhSummary() : message);
        body.put("status", code.httpStatus());
        if (path != null) {
            body.put("path", path);
        }
        if (detail != null && !detail.isEmpty()) {
            body.put("detail", detail);
        }
        return new ApiResponse(code.httpStatus(), Json.write(body));
    }

    /** 失败响应（无补充信息）。 */
    public static ApiResponse error(ApiErrorCode code, String message, String path) {
        return error(code, message, path, null);
    }

    /** 已渲染的 JSON 文本；无体时返回空串（便于测试里 {@code contains} 断言不炸）。 */
    @Override
    public String toString() {
        return json == null ? "" : json;
    }
}