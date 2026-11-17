package cn.hospital.region.archive;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 入口的验收：退出码与诊断文案。
 *
 * <p>fail fast 这条用<b>真实子进程</b>验——起一个 {@code java} 进程、清空继承的环境变量、
 * 只喂给它想要的那些，这样「缺 HIS_MPI_TOKEN 就以 2 退出」是进程级事实，不是同进程内的假象。
 */
class MainTest {

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
        env.put("ARCHIVE_DB_PASSWORD", "db-pwd");
        env.put("HIS_MPI_ENDPOINT", "http://his-mpi.example.internal:8080");
        env.put("HIS_MPI_TOKEN", "his-token-SHOULD-NOT-LEAK");
        env.put("ARCHIVE_ACK_API_BASE_URL", "https://archive-platform.example.internal/api/v1");
        env.put("ARCHIVE_ACK_API_TOKEN", "ack-token");
        env.put("ARCHIVE_API_TOKEN", "api-token");
        env.put("MODEL_BASE_URL", "https://model-gateway.example.internal/v1");
        env.put("MODEL_API_KEY", "model-key");
        env.put("MODEL_NAME", "qwen2.5-instruct");
        env.put("MODEL_TIMEOUT_SECONDS", "15");
        env.put("MODEL_MAX_RETRIES", "2");
        return env;
    }

    /** 起一个真实 java 子进程，只给它 env 里的变量，返回退出码与输出。 */
    private static Result runProcess(Map<String, String> env) throws Exception {
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder pb = new ProcessBuilder(javaBin, "-cp", System.getProperty("java.class.path"),
                Main.class.getName(), "--check-config");
        pb.environment().clear();
        pb.environment().putAll(env);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "子进程 60s 内未退出");
        return new Result(process.exitValue(), output);
    }

    private record Result(int exitCode, String output) {
    }

    @Test
    @DisplayName("缺 HIS_MPI_TOKEN：真实子进程退出码 2 且 stderr 点名变量")
    void test_missing_env_exits_with_code_2() throws Exception {
        Map<String, String> env = fullEnv();
        env.remove("HIS_MPI_TOKEN");

        Result result = runProcess(env);

        assertEquals(Main.EXIT_CONFIG_ERROR, result.exitCode());
        assertTrue(result.output().contains("缺失环境变量 HIS_MPI_TOKEN"),
                "诊断输出应点名缺失变量，实际：" + result.output());
        assertFalse(result.output().contains("启动摘要"), "配置没过 shouldn't 打启动摘要");
    }

    @Test
    @DisplayName("配置齐全：退出码 0，打印单行启动摘要且无令牌明文")
    void test_check_config_with_full_env_exits_zero() throws Exception {
        Result result = runProcess(fullEnv());

        assertEquals(0, result.exitCode());
        assertTrue(result.output().contains("启动摘要"));
        assertTrue(result.output().contains("mllp=0.0.0.0:2575"));
        assertTrue(result.output().contains("remoteAets=REGIONAL_ARCHIVE"));
        assertTrue(result.output().contains("ackMode=framed_ack"));
        assertTrue(result.output().contains("hisMpi=http://his-mpi.example.internal:8080"));
        assertFalse(result.output().contains("his-token-SHOULD-NOT-LEAK"), "启动摘要泄露了令牌");
    }

    @Test
    @DisplayName("无法识别的参数返回用法错误码 64")
    void test_unknown_argument_returns_usage_exit_code() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code;
        try (PrintStream ps = new PrintStream(err, true, StandardCharsets.UTF_8)) {
            code = Main.run(new String[]{"--serve"}, ps);
        }
        assertEquals(Main.EXIT_USAGE, code);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("参数无法识别"));
    }

    @Test
    @DisplayName("--help 返回 0")
    void test_help_returns_zero() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code;
        try (PrintStream ps = new PrintStream(err, true, StandardCharsets.UTF_8)) {
            code = Main.run(new String[]{"--help"}, ps);
        }
        assertEquals(0, code);
        assertEquals(0, err.size());
    }
}