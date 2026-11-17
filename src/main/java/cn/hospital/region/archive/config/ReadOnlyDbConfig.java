package cn.hospital.region.archive.config;

import java.util.Map;
import java.util.Objects;

/**
 * 只读排障通道的数据库连接配置 —— 只认三项环境变量。
 *
 * <h2>为什么不复用 {@link AppConfig}</h2>
 * <p>{@code AppConfig} 是<b>接入口常驻进程</b>的配置，它要求 HIS 端点、模型网关、
 * 对端 AET 等十几项全部配齐，缺一项就以退出码 2 终止——那是对的，服务带半套配置跑起来更糟。
 *
 * <p>但「导一份待确认清单」是运维排障动作：信息科值班的人半夜排查对账差异时，
 * 要求他为了看一眼 CSV 去申请模型网关的 API key 才能用这个工具，是把排障通道堵死。
 * 于是这里只读 {@code ARCHIVE_DB_URL / ARCHIVE_DB_USERNAME / ARCHIVE_DB_PASSWORD}，
 * <b>缺任一仍然非零退出，绝不回落到本地替身</b>——能连生产库的就连生产库，
 * 连不上就明确报错，不会拿一份 H2 空库出数让人以为「这一批确实没有待确认」。
 */
public record ReadOnlyDbConfig(String jdbcUrl, String username, String password) {

    public static final String ENV_URL = "ARCHIVE_DB_URL";
    public static final String ENV_USERNAME = "ARCHIVE_DB_USERNAME";
    public static final String ENV_PASSWORD = "ARCHIVE_DB_PASSWORD";

    public ReadOnlyDbConfig {
        Objects.requireNonNull(jdbcUrl, "jdbcUrl 不得为 null");
        Objects.requireNonNull(username, "username 不得为 null");
        Objects.requireNonNull(password, "password 不得为 null");
    }

    /** 从进程环境变量读；缺任一必需项抛 {@link ConfigException}（消息点名变量名）。 */
    public static ReadOnlyDbConfig fromEnv() {
        return from(System.getenv());
    }

    /** 可注入 env 的读法（测试用）。 */
    public static ReadOnlyDbConfig from(Map<String, String> env) {
        Objects.requireNonNull(env, "env 不得为 null");
        String url = require(env, ENV_URL);
        String user = require(env, ENV_USERNAME);
        String password = require(env, ENV_PASSWORD);
        if (!url.startsWith("jdbc:")) {
            throw ConfigException.invalid(ENV_URL, "不是合法的 JDBC URL（应以 jdbc: 开头）");
        }
        return new ReadOnlyDbConfig(url, user, password);
    }

    /** 供日志与诊断的一行摘要；<b>不含</b>口令明文，也不含 userinfo 段。 */
    public String summaryLine() {
        return "只读排障连接: url=" + stripUserInfo(jdbcUrl) + " user=" + username;
    }

    /** JDBC URL 里可能带着 {@code ?user=&password=}，摘要里必须去掉。 */
    private static String stripUserInfo(String url) {
        int schemeSep = url.indexOf("://");
        if (schemeSep < 0) {
            return url;
        }
        int hostStart = schemeSep + 3;
        int userInfoEnd = url.indexOf('@', hostStart);
        if (userInfoEnd < 0) {
            return url;
        }
        return url.substring(0, hostStart) + "***" + url.substring(userInfoEnd);
    }

    private static String require(Map<String, String> env, String name) {
        String v = env.get(name);
        if (v == null || v.isBlank()) {
            throw ConfigException.missing(name);
        }
        return v;
    }
}