package cn.hospital.region.archive.config;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 启动期配置。全部凭据与对端地址只从环境变量读，缺任一必需项即 {@link ConfigException}。
 *
 * <p>「哪些项可以给默认值」的判定标准是：<b>默认值会不会把流量悄悄指到别处</b>。
 * <ul>
 *   <li>{@code ARCHIVE_MLLP_PORT} 默认 2575 —— 2575 是 HL7 MLLP 的标准端口，是协议常量不是猜测，给默认值安全；</li>
 *   <li>{@code MODEL_TIMEOUT_SECONDS} / {@code MODEL_MAX_RETRIES} 默认 15s / 2 次 —— 只是超时与重试预算，不指向任何对端；</li>
 *   <li>{@code ARCHIVE_MLLP_RECONNECT_MAX_ATTEMPTS} / {@code _BASE_MS} / {@code _MAX_MS} 默认 3 次 / 200ms / 2s —— 同样只是重试预算；
 *       「重投时断线该多试几次」取决于院内是走专线还是公网，写死等于把一家医院的行为固化在别人的发布节奏里。</li>
 *   <li><b>{@code ARCHIVE_ACK_MODE} 不给默认值</b> —— 国内区域平台的回执方言不统一（带帧 {@code 0B 06 1C 0D} 与裸 {@code 0x06} 都存在），
 *       猜错方言的表现是「对端一条回执都收不到」且本地看着一切正常，正是本产品最不能容忍的静默失败，所以必须由院内显式指定。</li>
 * </ul>
 *
 * <p>对端准入判定沿用参考实现的连接级黑白名单语义（{@code onConnectionRejectedBlacklisted} /
 * {@code onConnectionRejected}），改写为「对端 AE Title 白名单」：连接建立后先看 MSH-3，
 * 不在白名单内的对端直接拒绝，不进入任何报文处理——见 {@link #isRemoteAetAllowed(String)}。
 */
public record AppConfig(
        String mllpHost,
        int mllpPort,
        String redeliveryHost,
        int redeliveryPort,
        String localAet,
        Set<String> allowedRemoteAets,
        AckMode ackMode,
        String dbUrl,
        String dbUsername,
        String dbPassword,
        String hisMpiEndpoint,
        String hisMpiToken,
        String ackApiBaseUrl,
        String ackApiToken,
        String apiToken,
        String modelBaseUrl,
        String modelApiKey,
        String modelName,
        int modelTimeoutSeconds,
        int modelMaxRetries,
        cn.hospital.region.archive.mllp.MllpReconnectPolicy mllpReconnectPolicy) {

    /** HL7 MLLP 标准端口。 */
    public static final int DEFAULT_MLLP_PORT = 2575;
    public static final int DEFAULT_MODEL_TIMEOUT_SECONDS = 15;
    public static final int DEFAULT_MODEL_MAX_RETRIES = 2;

    /** AE Title：1-16 位可打印 ASCII，且不含 HL7 的字段/组件/重复/子组件/转义分隔符。 */
    private static final Pattern AET_TITLE = Pattern.compile("[!-~]{1,16}");
    private static final String HL7_DELIMITERS = "^|&~\\!";

    /** 回执方言。 */
    public enum AckMode {
        /** 带 MLLP 帧：{@code 0B 06 1C 0D}（HL7 规范形式）。 */
        FRAMED_ACK("framed_ack"),
        /** 裸 ACK 字节：{@code 0x06}（部分区域平台实现如此）。 */
        BARE_ACK("bare_ack");

        private final String wireName;

        AckMode(String wireName) {
            this.wireName = wireName;
        }

        /** 环境变量里写的字面值。 */
        public String wireName() {
            return wireName;
        }
    }

    /** 从进程环境变量装配。 */
    public static AppConfig fromEnv() {
        return fromEnv(System.getenv());
    }

    /**
     * 从给定映射装配（测试与离线校验用同一套解析逻辑，不另开一条校验捷径）。
     *
     * @throws ConfigException 必需项缺失或取值不合法
     */
    public static AppConfig fromEnv(Map<String, String> env) {
        String mllpHost = requireValue(env, "ARCHIVE_MLLP_HOST");
        int mllpPort = optionalInt(env, "ARCHIVE_MLLP_PORT", DEFAULT_MLLP_PORT, 1, 65535);

        // 重投目标：把台账里存着的报文原文按 MLLP 帧原样发回区域平台，由平台回 MSA-1。
        // 与监听口分开配，因为两者通常不是同一台机器：院内前置机监听 0.0.0.0:2575，
        // 重投则要指向区域平台（或院内接口引擎）的 MLLP 入口。
        String redeliveryHost = requireValue(env, "ARCHIVE_REDELIVERY_HOST");
        int redeliveryPort = optionalInt(env, "ARCHIVE_REDELIVERY_PORT", DEFAULT_MLLP_PORT, 1, 65535);

        String localAet = requireAetTitle(env, "ARCHIVE_LOCAL_AET");
        Set<String> allowedRemoteAets = requireAetWhitelist(env, "ARCHIVE_REMOTE_AET");
        AckMode ackMode = requireAckMode(env);

        String dbUrl = requireJdbcUrl(env, "ARCHIVE_DB_URL");
        String dbUsername = requireValue(env, "ARCHIVE_DB_USERNAME");
        String dbPassword = requireValue(env, "ARCHIVE_DB_PASSWORD");

        String hisMpiEndpoint = requireHttpUrl(env, "HIS_MPI_ENDPOINT");
        String hisMpiToken = requireValue(env, "HIS_MPI_TOKEN");

        String ackApiBaseUrl = requireHttpUrl(env, "ARCHIVE_ACK_API_BASE_URL");
        String ackApiToken = requireValue(env, "ARCHIVE_ACK_API_TOKEN");
        String apiToken = requireValue(env, "ARCHIVE_API_TOKEN");

        String modelBaseUrl = requireHttpUrl(env, "MODEL_BASE_URL");
        String modelApiKey = requireValue(env, "MODEL_API_KEY");
        String modelName = requireValue(env, "MODEL_NAME");
        int modelTimeoutSeconds = optionalInt(env, "MODEL_TIMEOUT_SECONDS", DEFAULT_MODEL_TIMEOUT_SECONDS, 1, 600);
        int modelMaxRetries = optionalInt(env, "MODEL_MAX_RETRIES", DEFAULT_MODEL_MAX_RETRIES, 0, 10);

        // 出站重连预算：断了重建几次、每次退多久、建连与读回执各等多久。
        // 只兜「超时与重试预算」，不会把流量悄悄指到别的对端，故给默认值安全。
        cn.hospital.region.archive.mllp.MllpReconnectPolicy mllpReconnectPolicy =
                cn.hospital.region.archive.mllp.MllpReconnectPolicy.fromEnv(env);

        return new AppConfig(mllpHost, mllpPort, redeliveryHost, redeliveryPort,
                localAet, allowedRemoteAets, ackMode,
                dbUrl, dbUsername, dbPassword, hisMpiEndpoint, hisMpiToken,
                ackApiBaseUrl, ackApiToken, apiToken,
                modelBaseUrl, modelApiKey, modelName, modelTimeoutSeconds, modelMaxRetries,
                mllpReconnectPolicy);
    }

    /**
     * 出站 MLLP 重连策略（重试上限 + 退避 + 建连/读超时），来自 {@code ARCHIVE_MLLP_RECONNECT_*} 等环境变量。
     *
     * <p>与 {@link #ackDialect()} 同理：配置层给出值，传输层
     * {@link cn.hospital.region.archive.mllp.MllpClient} 消费，两处不各维护一份字面量。
     */
    public cn.hospital.region.archive.mllp.MllpReconnectPolicy mllpReconnectPolicy() {
        return mllpReconnectPolicy;
    }

    /**
     * 回执方言（{@code ARCHIVE_ACK_MODE} 在传输层的落地形态）。
     *
     * <p>配置层的 {@link AckMode} 与传输层的 {@link cn.hospital.region.archive.mllp.AckDialect}
     * 一一对应，这里给出换算，避免两处各维护一份字面量、某天改了一处忘了另一处。
     */
    public cn.hospital.region.archive.mllp.AckDialect ackDialect() {
        return cn.hospital.region.archive.mllp.AckDialect.fromWireName(ackMode.wireName());
    }

    /**
     * 对端 AE Title 是否在白名单内。
     *
     * <p>准入判定语义取自参考实现的 {@code onConnectionRejectedBlacklisted}：被判定为不可信的
     * 对端在进入消息处理之前就被拒掉，不产生任何业务状态。HL7 v2 规定 AE Title 比较<b>不区分大小写</b>，
     * 故入白名单时统一转大写存，比较时同样归一。
     */
    public boolean isRemoteAetAllowed(String remoteAet) {
        if (remoteAet == null || remoteAet.isBlank()) {
            return false;
        }
        return allowedRemoteAets.contains(remoteAet.trim().toUpperCase(Locale.ROOT));
    }

    /**
     * 对端 AE 不在白名单时的拒绝理由，供接入侧记审计。
     *
     * <p>AE Title 取自对端报文，是外部输入：先洗成可打印 ASCII 再截到 16 字符，
     * 免得对端塞进控制字符或超长串把日志行搞乱。
     */
    public String rejectionReason(String remoteAet) {
        return "对端 AE Title 不在白名单内："
                + sanitizeAetForLog(remoteAet)
                + "（白名单：" + String.join(",", allowedRemoteAets) + "）";
    }

    private static String sanitizeAetForLog(String value) {
        if (value == null) {
            return "(空)";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < value.length() && sb.length() < 16; i++) {
            char c = value.charAt(i);
            if (c >= '!' && c <= '~') {
                sb.append(c);
            }
        }
        return sb.isEmpty() ? "(空)" : sb.toString();
    }

    /**
     * 单行启动摘要。监听地址、对端 AET、回执方言、HIS 端点主机名、模型名与超时/重试预算；
     * <b>所有口令与令牌一律不出现在这里</b>。
     */
    public String summaryLine() {
        return String.join(" | ",
                "启动摘要: mllp=" + mllpHost + ":" + mllpPort,
                "redelivery=" + redeliveryHost + ":" + redeliveryPort,
                "localAet=" + localAet,
                "remoteAets=" + String.join(",", allowedRemoteAets),
                "ackMode=" + ackMode.wireName(),
                "db=" + dbDescription(),
                "hisMpi=" + hisMpiEndpoint,
                "ackApi=" + ackApiBaseUrl,
                "model=" + modelName + "(timeout=" + modelTimeoutSeconds + "s,retries=" + modelMaxRetries + ")",
                "mllpReconnect=maxAttempts=" + mllpReconnectPolicy.maxAttempts()
                        + ",base=" + mllpReconnectPolicy.baseBackoffMs() + "ms"
                        + ",cap=" + mllpReconnectPolicy.maxBackoffMs() + "ms",
                "secrets=[db:" + maskValue(dbPassword)
                        + ",hisMpi:" + maskValue(hisMpiToken)
                        + ",ackApi:" + maskValue(ackApiToken)
                        + ",api:" + maskValue(apiToken)
                        + ",model:" + maskValue(modelApiKey) + "]");
    }

    @Override
    public String toString() {
        // 记录打日志时可能整对象打印；这里覆盖掉自动生成的 toString，凭据永不出现在任何日志行。
        return summaryLine();
    }

    // ---------- 解析辅助 ----------

    private static String requireValue(Map<String, String> env, String name) {
        String raw = env.get(name);
        if (raw == null || raw.isBlank()) {
            throw ConfigException.missing(name);
        }
        return raw.trim();
    }

    private static int optionalInt(Map<String, String> env, String name, int defaultValue, int min, int max) {
        String raw = env.get(name);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        int value;
        try {
            value = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw ConfigException.invalid(name, "必须是整数");
        }
        if (value < min || value > max) {
            throw ConfigException.invalid(name, "取值须在 " + min + " 到 " + max + " 之间");
        }
        return value;
    }

    private static String requireAetTitle(Map<String, String> env, String name) {
        String value = requireValue(env, name);
        if (!isAetTitle(value)) {
            throw ConfigException.invalid(name, "AE Title 须为 1-16 位可打印 ASCII 且不含 HL7 分隔符");
        }
        return value.toUpperCase(Locale.ROOT);
    }

    /** 逗号分隔的 AE 白名单，例如 {@code REGIONAL_ARCHIVE,ARCHIVE_BACKUP}。 */
    private static Set<String> requireAetWhitelist(Map<String, String> env, String name) {
        String raw = requireValue(env, name);
        Set<String> whitelist = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String title = part.trim();
            if (title.isEmpty()) {
                throw ConfigException.invalid(name, "白名单存在空条目");
            }
            if (!isAetTitle(title)) {
                throw ConfigException.invalid(name, "白名单条目「" + title + "」不是合法 AE Title");
            }
            whitelist.add(title.toUpperCase(Locale.ROOT));
        }
        if (whitelist.isEmpty()) {
            throw ConfigException.missing(name);
        }
        return Collections.unmodifiableSet(whitelist);
    }

    private static boolean isAetTitle(String value) {
        if (!AET_TITLE.matcher(value).matches()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (HL7_DELIMITERS.indexOf(value.charAt(i)) >= 0) {
                return false;
            }
        }
        return true;
    }

    private static AckMode requireAckMode(Map<String, String> env) {
        String raw = requireValue(env, "ARCHIVE_ACK_MODE");
        for (AckMode mode : AckMode.values()) {
            if (mode.wireName.equalsIgnoreCase(raw)) {
                return mode;
            }
        }
        throw ConfigException.invalid("ARCHIVE_ACK_MODE", "只接受 framed_ack 或 bare_ack");
    }

    private static String requireHttpUrl(Map<String, String> env, String name) {
        String raw = requireValue(env, name);
        URI uri;
        try {
            uri = URI.create(raw);
        } catch (IllegalArgumentException e) {
            throw ConfigException.invalid(name, "不是合法 URL");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            throw ConfigException.invalid(name, "协议必须是 http 或 https");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw ConfigException.invalid(name, "缺少主机名");
        }
        // 去掉尾斜杠：下游拼 "/patient-index" 时不会变成 "//patient-index"
        String trimmed = raw.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static String requireJdbcUrl(Map<String, String> env, String name) {
        String raw = requireValue(env, name);
        if (!raw.regionMatches(true, 0, "jdbc:", 0, "jdbc:".length())) {
            throw ConfigException.invalid(name, "必须是 jdbc: 开头的连接串");
        }
        return raw;
    }

    /** 数据库描述：保留协议与库名，抹掉 userinfo 里可能夹带的口令。 */
    private String dbDescription() {
        return stripUserInfo(dbUrl);
    }

    /** 去掉 URL 中的 user:pass 部分，避免口令随日志外泄。 */
    static String stripUserInfo(String url) {
        int schemeEnd = url.indexOf("//");
        if (schemeEnd < 0) {
            return url;
        }
        int authorityStart = schemeEnd + 2;
        int authorityEnd = url.indexOf('/', authorityStart);
        String authority = authorityEnd < 0 ? url.substring(authorityStart) : url.substring(authorityStart, authorityEnd);
        int at = authority.lastIndexOf('@');
        if (at < 0) {
            return url;
        }
        // 最后一个 '@' 之前全是 userinfo（可能夹带口令），整段替换掉，别只抹口令留用户名
        String redactedAuthority = "***" + authority.substring(at);
        return authorityEnd < 0
                ? url.substring(0, authorityStart) + redactedAuthority
                : url.substring(0, authorityStart) + redactedAuthority + url.substring(authorityEnd);
    }

    /** 凭据在摘要里的替身：只说明「有没有配」，不说明配的是什么。 */
    static String maskValue(String secret) {
        if (secret == null || secret.isEmpty()) {
            return "(未设置)";
        }
        return "***";
    }
}