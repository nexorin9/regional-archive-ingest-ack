package cn.hospital.region.archive.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 静态检查：业务层<b>不 import 任何厂商 SDK</b>，模型包只用 JDK 与本项目类型。
 *
 * <h2>为什么要写成测试而不是写进规范</h2>
 * <p>「业务层只依赖单一 {@code ModelGateway}」这句话写在文档里，三个月后就有人
 * 为了赶一个联调直接 {@code import com.openai.***}，文档没人看但测试天天跑。
 * 依赖形态是<b>编译不出来的约定</b>，那就让它变成一条每次构建都会失败的断言。
 *
 * <p>顺带查两件同样「看不出来」的事：
 * <ul>
 *   <li>模型包里出现<b>非 JDK、非本项目</b>的 import（今天没有，但明天可能有人顺手加了 gson）；</li>
 *   <li>模型包里出现<b>硬编码密钥字面量</b>（{@code sk-} 这类前缀）——
 *       凭据只准从环境变量来，这条同样只能靠机器查。</li>
 * </ul>
 */
@DisplayName("静态检查：业务层无厂商 SDK 依赖，模型包只用 JDK 与本项目类型")
class NoVendorSdkImportTest {

    /** 厂商 SDK 的包前缀。命中任意一条即失败。 */
    private static final List<String> VENDOR_SDK_PREFIXES = List.of(
            "com.openai.",
            "openai.",
            "com.theokanning.",
            "com.anthropic.",
            "anthropic.",
            "ai.anthropic.",
            "dev.langchain4j.",
            "langchain4j.",
            "ai.google.",
            "com.google.generativeai.",
            "com.mistralai.",
            "com.cohere.",
            "org.springframework.ai.",
            "cohere.");

    /** 允许出现在模型包里的 import 顶层包。 */
    private static final List<String> ALLOWED_IN_MODEL_PACKAGE = List.of(
            "java.", "javax.", "cn.hospital.region.archive.");

    private static final Path MAIN_SOURCES = Path.of("src", "main", "java");
    private static final Path MODEL_PACKAGE = MAIN_SOURCES.resolve(
            Path.of("cn", "hospital", "region", "archive", "model"));

    @Test
    @DisplayName("主源码里没有厂商 SDK 的 import")
    void test_no_vendor_sdk_imports_in_main_sources() {
        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles(MAIN_SOURCES)) {
            for (String imported : importsOf(file)) {
                for (String vendor : VENDOR_SDK_PREFIXES) {
                    if (imported.startsWith(vendor)) {
                        violations.add(MAIN_SOURCES.relativize(file) + " → " + imported);
                    }
                }
            }
        }
        if (!violations.isEmpty()) {
            fail("业务层出现厂商 SDK 依赖，换厂商就得改代码 —— 应只依赖 ModelGateway：" + violations);
        }
        assertFalse(javaFiles(MAIN_SOURCES).isEmpty(), "主源码目录不该为空，否则这条断言是空跑");
    }

    @Test
    @DisplayName("模型包只依赖 JDK 与本项目类型")
    void test_model_package_uses_only_jdk_and_project_types() {
        List<String> violations = new ArrayList<>();
        List<Path> files = javaFiles(MODEL_PACKAGE);
        assertFalse(files.isEmpty(), "模型包目录不存在或为空，源码根路径可能不对：" + MODEL_PACKAGE.toAbsolutePath());
        for (Path file : files) {
            for (String imported : importsOf(file)) {
                boolean allowed = ALLOWED_IN_MODEL_PACKAGE.stream().anyMatch(imported::startsWith);
                if (!allowed) {
                    violations.add(MODEL_PACKAGE.relativize(file) + " → " + imported);
                }
            }
        }
        if (!violations.isEmpty()) {
            fail("模型包引入了 JDK 与本项目之外的依赖（第三方 JSON 库等）—— 本项目依赖面刻意压到最小：" + violations);
        }
    }

    @Test
    @DisplayName("模型包没有硬编码密钥字面量")
    void test_no_hardcoded_api_key_literal_in_model_package() {
        for (Path file : javaFiles(MODEL_PACKAGE)) {
            String source = read(file);
            assertFalse(source.contains("sk-"),
                    file + " 出现疑似硬编码密钥字面量；凭据只能从环境变量 MODEL_API_KEY 读");
        }
    }

    // ---------- 辅助 ----------

    private static List<Path> javaFiles(Path root) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        } catch (IOException e) {
            throw new IllegalStateException("遍历源码目录失败：" + root, e);
        }
    }

    private static List<String> importsOf(Path file) {
        List<String> imports = new ArrayList<>();
        for (String line : read(file).split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("import ")) {
                imports.add(trimmed.substring("import ".length()).replace("static ", "").trim());
            }
        }
        return imports;
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取源码失败：" + file, e);
        }
    }

    /** 兜底：即使上面三条因目录异常全部空跑，也要让「一个文件都没扫到」这件事露出来。 */
    @Test
    @DisplayName("确实扫到了文件（防止断言空跑）")
    void test_source_root_is_walkable() {
        assertTrue(Files.isDirectory(MAIN_SOURCES),
                "源码根目录不存在：" + MAIN_SOURCES.toAbsolutePath());
        assertTrue(javaFiles(MAIN_SOURCES).size() >= 20,
                "扫到的源文件过少，路径多半不对：" + MAIN_SOURCES.toAbsolutePath());
    }
}