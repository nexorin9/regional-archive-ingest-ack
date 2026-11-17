package cn.hospital.region.archive.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配置装配的验收：解析成功、缺项 fail fast、非法值拒绝、口令与令牌不外泄。
 *
 * <p>用真实映射走 {@code AppConfig.fromEnv(Map)} 这条唯一的解析路径——不另开测试专用捷径，
 * 保证测的就是进程启动时跑的那段代码。
 */
class AppConfigTest {

    private static Map<String, String> fullEnv() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("ARCHIVE_MLLP_HOST", "0.0.0.0");
        env.put("ARCHIVE_MLLP_PORT", "2575");
        env.put("ARCHIVE_REDELIVERY_HOST", "archive-platform.example.internal");
        env.put("ARCHIVE_REDELIVERY_PORT", "2575");
        env.put("ARCHIVE_LOCAL_AET", "CHSCOMMUNITY");
        env.put("ARCHIVE_REMOTE_AET", "REGIONAL_ARCHIVE");
        env.put("ARCHIVE_ACK_MODE", "framed_ack");
        env.put("ARCHIVE_DB_URL", "jdbc:postgresql://127.0.0.1:5432/regional_archive");
        env.put("ARCHIVE_DB_USERNAME", "archive_app");
        env.put("ARCHIVE_DB_PASSWORD", "db-pwd-SHOULD-NOT-LEAK");
        env.put("HIS_MPI_ENDPOINT", "http://his-mpi.example.internal:8080");
        env.put("HIS_MPI_TOKEN", "his-token-SHOULD-NOT-LEAK");
        env.put("ARCHIVE_ACK_API_BASE_URL", "https://archive-platform.example.internal/api/v1/");
        env.put("ARCHIVE_ACK_API_TOKEN", "ack-token-SHOULD-NOT-LEAK");
        env.put("ARCHIVE_API_TOKEN", "api-token-SHOULD-NOT-LEAK");
        env.put("MODEL_BASE_URL", "https://model-gateway.example.internal/v1");
        env.put("MODEL_API_KEY", "model-key-SHOULD-NOT-LEAK");
        env.put("MODEL_NAME", "qwen2.5-instruct");
        env.put("MODEL_TIMEOUT_SECONDS", "20");
        env.put("MODEL_MAX_RETRIES", "3");
        return env;
    }

    @Test
    @DisplayName("全量环境变量下解析成功，各字段落到对应值")
    void test_all_env_vars_parse_successfully() {
        AppConfig config = AppConfig.fromEnv(fullEnv());

        assertEquals("0.0.0.0", config.mllpHost());
        assertEquals(2575, config.mllpPort());
        assertEquals("CHSCOMMUNITY", config.localAet());
        assertEquals(1, config.allowedRemoteAets().size());
        assertTrue(config.allowedRemoteAets().contains("REGIONAL_ARCHIVE"));
        assertEquals(AppConfig.AckMode.FRAMED_ACK, config.ackMode());
        assertEquals("archive_app", config.dbUsername());
        // 尾斜杠被去掉：下游拼 "/patient-index" 不会变成 "//patient-index"
        assertEquals("https://archive-platform.example.internal/api/v1", config.ackApiBaseUrl());
        assertEquals("qwen2.5-instruct", config.modelName());
        assertEquals(20, config.modelTimeoutSeconds());
        assertEquals(3, config.modelMaxRetries());
    }

    @Test
    @DisplayName("缺 HIS_MPI_TOKEN 时抛 ConfigException 且消息含变量名")
    void test_missing_his_mpi_token_throws_with_variable_name() {
        Map<String, String> env = fullEnv();
        env.remove("HIS_MPI_TOKEN");

        ConfigException ex = assertThrows(ConfigException.class, () -> AppConfig.fromEnv(env));

        assertEquals("HIS_MPI_TOKEN", ex.variableName());
        assertTrue(ex.getMessage().contains("缺失环境变量 HIS_MPI_TOKEN"),
                "消息应说明缺哪个变量，实际：" + ex.getMessage());
    }

    @Test
    @DisplayName("空白值等同缺失，不当作已配置")
    void test_blank_value_counts_as_missing() {
        Map<String, String> env = fullEnv();
        env.put("HIS_MPI_TOKEN", "   ");

        ConfigException ex = assertThrows(ConfigException.class, () -> AppConfig.fromEnv(env));

        assertEquals("HIS_MPI_TOKEN", ex.variableName());
    }

    @Test
    @DisplayName("ARCHIVE_ACK_MODE 非法值被拒，且不接受猜测默认值")
    void test_invalid_ack_mode_rejected() {
        Map<String, String> env = fullEnv();
        env.put("ARCHIVE_ACK_MODE", "xml_ack");

        ConfigException ex = assertThrows(ConfigException.class, () -> AppConfig.fromEnv(env));

        assertEquals("ARCHIVE_ACK_MODE", ex.variableName());
        assertTrue(ex.getMessage().contains("ARCHIVE_ACK_MODE"));
    }

    @Test
    @DisplayName("ARCHIVE_ACK_MODE 未设置时按 fail fast 退出，不静默猜方言")
    void test_ack_mode_absent_is_rejected() {
        Map<String, String> env = fullEnv();
        env.remove("ARCHIVE_ACK_MODE");

        ConfigException ex = assertThrows(ConfigException.class, () -> AppConfig.fromEnv(env));

        assertEquals("ARCHIVE_ACK_MODE", ex.variableName());
    }

    @Test
    @DisplayName("bare_ack 方言可解析")
    void test_bare_ack_mode_parsed() {
        Map<String, String> env = fullEnv();
        env.put("ARCHIVE_ACK_MODE", "bare_ack");

        assertEquals(AppConfig.AckMode.BARE_ACK, AppConfig.fromEnv(env).ackMode());
        assertEquals("bare_ack", AppConfig.AckMode.BARE_ACK.wireName());
    }

    @Test
    @DisplayName("端口缺省取 HL7 标准端口 2575，越界与非数字被拒")
    void test_port_defaults_to_2575_and_rejects_illegal_values() {
        Map<String, String> env = fullEnv();
        env.remove("ARCHIVE_MLLP_PORT");
        assertEquals(AppConfig.DEFAULT_MLLP_PORT, AppConfig.fromEnv(env).mllpPort());

        env.put("ARCHIVE_MLLP_PORT", "70000");
        assertEquals("ARCHIVE_MLLP_PORT",
                assertThrows(ConfigException.class, () -> AppConfig.fromEnv(env)).variableName());

        env.put("ARCHIVE_MLLP_PORT", "not-a-number");
        assertEquals("ARCHIVE_MLLP_PORT",
                assertThrows(ConfigException.class, () -> AppConfig.fromEnv(env)).variableName());
    }

    @Test
    @DisplayName("端点必须是 http/https 且带主机名，数据库串必须 jdbc: 开头")
    void test_endpoint_and_db_url_validation() {
        Map<String, String> badScheme = fullEnv();
        badScheme.put("HIS_MPI_ENDPOINT", "ftp://his-mpi.example.internal");
        assertEquals("HIS_MPI_ENDPOINT",
                assertThrows(ConfigException.class, () -> AppConfig.fromEnv(badScheme)).variableName());

        Map<String, String> noHost = fullEnv();
        noHost.put("HIS_MPI_ENDPOINT", "his-mpi.example.internal:8080");
        assertEquals("HIS_MPI_ENDPOINT",
                assertThrows(ConfigException.class, () -> AppConfig.fromEnv(noHost)).variableName());

        Map<String, String> badJdbc = fullEnv();
        badJdbc.put("ARCHIVE_DB_URL", "postgresql://127.0.0.1:5432/regional_archive");
        assertEquals("ARCHIVE_DB_URL",
                assertThrows(ConfigException.class, () -> AppConfig.fromEnv(badJdbc)).variableName());
    }

    @Test
    @DisplayName("对端 AE 白名单准入：白名单内放行、名单外拒绝，大小写不敏感")
    void test_remote_aet_whitelist_admission() {
        Map<String, String> env = fullEnv();
        env.put("ARCHIVE_REMOTE_AET", "REGIONAL_ARCHIVE,ARCHIVE_BACKUP");
        AppConfig config = AppConfig.fromEnv(env);

        assertEquals(2, config.allowedRemoteAets().size());
        assertTrue(config.isRemoteAetAllowed("REGIONAL_ARCHIVE"));
        assertTrue(config.isRemoteAetAllowed("ARCHIVE_BACKUP"));
        // HL7 v2 规定 AE Title 比较不区分大小写
        assertTrue(config.isRemoteAetAllowed("  regional_archive  "));
        assertFalse(config.isRemoteAetAllowed("OTHER_PLATFORM"));
        assertFalse(config.isRemoteAetAllowed(null));
        assertTrue(config.rejectionReason("OTHER_PLATFORM").contains("不在白名单"));
    }

    @Test
    @DisplayName("AE Title 非法字符（HL7 分隔符、超长）被拒")
    void test_invalid_aet_title_rejected() {
        Map<String, String> delimiterInTitle = fullEnv();
        delimiterInTitle.put("ARCHIVE_LOCAL_AET", "CH|SCOMMUNITY");
        assertEquals("ARCHIVE_LOCAL_AET",
                assertThrows(ConfigException.class, () -> AppConfig.fromEnv(delimiterInTitle)).variableName());

        Map<String, String> tooLong = fullEnv();
        tooLong.put("ARCHIVE_REMOTE_AET", "A".repeat(17));
        assertEquals("ARCHIVE_REMOTE_AET",
                assertThrows(ConfigException.class, () -> AppConfig.fromEnv(tooLong)).variableName());
    }

    @Test
    @DisplayName("启动摘要与 toString 都不含口令与令牌明文")
    void test_summary_and_to_string_never_leak_secrets() {
        AppConfig config = AppConfig.fromEnv(fullEnv());
        String rendered = config.summaryLine() + "\n" + config;

        for (String secret : new String[]{
                "db-pwd-SHOULD-NOT-LEAK",
                "his-token-SHOULD-NOT-LEAK",
                "ack-token-SHOULD-NOT-LEAK",
                "api-token-SHOULD-NOT-LEAK",
                "model-key-SHOULD-NOT-LEAK"}) {
            assertFalse(rendered.contains(secret), "启动摘要泄露了凭据：" + secret);
        }
        // 该出现的运维信息必须在
        assertTrue(rendered.contains("2575"));
        assertTrue(rendered.contains("REGIONAL_ARCHIVE"));
        assertTrue(rendered.contains("framed_ack"));
        assertTrue(rendered.contains("his-mpi.example.internal:8080"));
        assertTrue(rendered.contains("secrets=[db:***"));
    }

    @Test
    @DisplayName("URL 里的 userinfo 口令被抹掉")
    void test_strip_user_info_removes_embedded_password() {
        assertEquals("jdbc:postgresql://***@127.0.0.1:5432/regional_archive",
                AppConfig.stripUserInfo("jdbc:postgresql://app:topsecret@127.0.0.1:5432/regional_archive"));
        assertEquals("jdbc:postgresql://127.0.0.1:5432/regional_archive",
                AppConfig.stripUserInfo("jdbc:postgresql://127.0.0.1:5432/regional_archive"));
    }
}