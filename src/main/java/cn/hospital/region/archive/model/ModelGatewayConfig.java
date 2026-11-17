package cn.hospital.region.archive.model;

import cn.hospital.region.archive.config.AppConfig;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;

/**
 * 模型调用配置面 —— 全部来自环境变量，缺项在启动期就 {@code exit(2)}，不兜底到任何默认对端。
 *
 * <p>本轮只选 <b>OpenAI-compatible 一种协议</b>：医院自建端点、本地模型与云服务
 * 共用同一组 {@code MODEL_*} 变量，换厂商时只改配置与 {@code base_url} 的路径形状，
 * 业务层代码一行不动（业务层只认 {@link ModelGateway}，不认厂商 SDK）。
 *
 * <p>{@code maxRetries} 在本轮（T20）只承载配置面：重试次数与退避落在 T21 的
 * 「五类错误 → 单一降级路径」里一起定，避免这里先写一套退避、T21 又写一套。
 * 值仍然在这里解析并校验，是为了让「配了一个非法的重试次数」在启动期就被发现，
 * 而不是等到某次超时才发现。
 */
public record ModelGatewayConfig(
        String baseUrl,
        String apiKey,
        String model,
        int timeoutSeconds,
        int maxRetries) {

    /** OpenAI-compatible 的补全端点路径。 */
    public static final String CHAT_COMPLETIONS_PATH = "/chat/completions";

    public ModelGatewayConfig {
        baseUrl = require(baseUrl, "MODEL_BASE_URL");
        apiKey = require(apiKey, "MODEL_API_KEY");
        model = require(model, "MODEL_NAME");
        if (timeoutSeconds < 1 || timeoutSeconds > 600) {
            throw new IllegalArgumentException("MODEL_TIMEOUT_SECONDS 须在 1 到 600 之间，实际 " + timeoutSeconds);
        }
        if (maxRetries < 0 || maxRetries > 10) {
            throw new IllegalArgumentException("MODEL_MAX_RETRIES 须在 0 到 10 之间，实际 " + maxRetries);
        }
        String lower = baseUrl.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            throw new IllegalArgumentException("MODEL_BASE_URL 必须是 http 或 https，实际 " + baseUrl);
        }
    }

    /** 从启动配置装配（与 {@link AppConfig} 同源，不另开一条读环境变量的路）。 */
    public static ModelGatewayConfig from(AppConfig app) {
        Objects.requireNonNull(app, "启动配置不得为 null");
        return new ModelGatewayConfig(app.modelBaseUrl(), app.modelApiKey(), app.modelName(),
                app.modelTimeoutSeconds(), app.modelMaxRetries());
    }

    /** 补全端点完整地址。 */
    public URI chatCompletionsUri() {
        String trimmed = baseUrl;
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return URI.create(trimmed + CHAT_COMPLETIONS_PATH);
    }

    /**
     * 单行配置摘要：模型名、主机、路径、超时与重试预算；<b>密钥不出现在这里</b>。
     *
     * <p>只保留主机名而不打整条 base_url：base_url 常带 {@code /v1} 之类的租户路径，
     * 少数云端厂商把它当成半凭据，整条打日志等于往外送半个密钥。
     */
    public String summaryLine() {
        URI uri = chatCompletionsUri();
        return "model=" + model
                + " host=" + uri.getHost()
                + " path=" + uri.getPath()
                + " timeout=" + timeoutSeconds + "s"
                + " retries=" + maxRetries
                + " apiKey=***";
    }

    private static String require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不得为空");
        }
        return value.trim();
    }
}