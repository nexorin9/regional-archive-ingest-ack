package cn.hospital.region.archive.http;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OpenAPI 文档（{@code src/main/resources/http/openapi.yaml}）与
 * {@link Routes#declaredRoutes()} 的双向一致性。
 *
 * <h2>为什么必须有这个测试</h2>
 * <p>院内对接系统按这份 OpenAPI 写代码：路径拼错、响应字段少一个，
 * 调用方的 200 都成了我们的 404。文档与实现一旦失同步，
 * 故障要等到真上线才浮出来——本测试提前在 CI 上逼着双向相等。
 *
 * <p><b>不允许单向包含</b>：少了一个路径（文档里没但代码有）、
 * 多了一个路径（文档里有但代码没有）都立刻失败。
 *
 * <p><b>0 mock 库</b>：手写最小 YAML 解析器直接从 classpath 读文件；
 * 路由集合通过 {@link Routes#declaredRoutes()} 的静态调用获得（产品代码真源）。
 */
class OpenApiConsistencyTest {

    private static OpenApiSpec spec;

    @BeforeAll
    static void loadSpec() throws IOException {
        spec = OpenApiSpec.loadFromClasspath("http/openapi.yaml");
        assertNotNull(spec, "openapi.yaml 加载失败");
    }

    @Test
    @DisplayName("OpenAPI 元信息（openapi / title / version）三栏非空且写对")
    void openapi_metadata_is_well_known() {
        assertEquals("3.0.3", spec.openapiVersion(), "openapi 版本必须是 3.0.3");
        assertTrue(spec.title().contains("区域健康档案"), "title 应含业务名：" + spec.title());
        assertEquals("v1", spec.version(), "info.version 与 Routes.API_VERSION 同步");
    }

    @Test
    @DisplayName("文档里所有路径都对应到 Routes 注册的方法（实现集 ⊆ 文档集）")
    void routes_subset_of_doc() {
        Set<String> declared = flattenDeclared(Routes.declaredRoutes());
        Set<String> documented = flattenDocumented(spec);

        List<String> extra = new ArrayList<>();
        for (String d : declared) {
            if (!documented.contains(d)) {
                extra.add(d);
            }
        }
        assertTrue(extra.isEmpty(),
                "下列端点 Routes 注册了但 openapi.yaml 没声明（先把它们写进文档）：\n"
                        + String.join("\n", extra));
    }

    @Test
    @DisplayName("Routes 注册的所有方法都在文档里声明（文档集 ⊆ 实现集）")
    void doc_subset_of_routes() {
        Set<String> declared = flattenDeclared(Routes.declaredRoutes());
        Set<String> documented = flattenDocumented(spec);

        List<String> extra = new ArrayList<>();
        for (String d : documented) {
            if (!declared.contains(d)) {
                extra.add(d);
            }
        }
        assertTrue(extra.isEmpty(),
                "openapi.yaml 声明了下列端点但 Routes 没注册（要么加 Routes，要么从文档里删）：\n"
                        + String.join("\n", extra));
    }

    @Test
    @DisplayName("/healthz 与其他 5 个业务端点的差异：探针不要令牌，其余都要")
    void security_requirements_match_design() {
        // /healthz 是探针，明确关闭鉴权。
        assertTrue(!spec.requiresAuth("/healthz", "GET"),
                "/healthz 标了 security: []，解析后不得要令牌");

        // 五个业务端点必须走 Bearer。
        String[][] business = {
                {"POST", "/batches/{batchId}/confirm"},
                {"POST", "/batches/{batchId}/revert"},
                {"GET", "/messages"},
                {"GET", "/batches/{batchId}/stats"},
                {"GET", "/batches/{batchId}/reconcile"}
        };
        for (String[] pair : business) {
            String m = pair[0];
            String p = pair[1];
            assertTrue(spec.requiresAuth(p, m),
                    "业务端点 " + m + " " + p + " 必须在文档里要令牌");
        }
    }

    @Test
    @DisplayName("/batches/{batchId}/confirm 必须声明 200/400/401/404/409/503 共 6 档响应")
    void confirm_endpoint_response_codes() {
        String[] codes = spec.responseCodesUnder("/batches/{batchId}/confirm", "POST");
        Set<String> declared = new TreeSet<>();
        Collections.addAll(declared, codes);
        Set<String> expected = new TreeSet<>();
        expected.add("200");
        expected.add("400");
        expected.add("401");
        expected.add("404");
        expected.add("409");
        expected.add("503");
        assertEquals(expected, declared,
                "POST /batches/{batchId}/confirm 的状态码集合与实现中 ApiErrorCode 派生的码必须一致");
    }

    @Test
    @DisplayName("文档覆盖了 6 条路由（共 6 个 路径×方法 组合）")
    void six_routes_total() {
        assertEquals(6, flattenDeclared(Routes.declaredRoutes()).size(),
                "Routes 总数与文档必须一致");
        assertEquals(6, flattenDocumented(spec).size(),
                "openapi.yaml 共应声明 6 条路由（与 Routes.declaredRoutes 完全对齐）");
    }

    // ===========================================================================
    // 帮助函数
    // ===========================================================================

    private static Set<String> flattenDeclared(List<Routes.DeclaredRoute> routes) {
        Set<String> out = new TreeSet<>();
        for (Routes.DeclaredRoute r : routes) {
            out.add(r.method() + " " + r.pathTemplate());
        }
        return out;
    }

    private static Set<String> flattenDocumented(OpenApiSpec spec) {
        Set<String> out = new TreeSet<>();
        for (String p : spec.paths()) {
            for (String m : spec.methodsUnder(p)) {
                out.add(m + " " + p);
            }
        }
        return out;
    }
}
