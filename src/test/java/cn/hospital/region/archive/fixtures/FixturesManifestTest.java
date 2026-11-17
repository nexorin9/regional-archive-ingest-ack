package cn.hospital.region.archive.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 测试数据层的验收：条数、编码、必需样例、GB18030/UTF-8 同内容、真值可用。
 *
 * <p>语料由 scripts/fetch_fixtures.sh 生成（网络可达时抓上游，不可达时用内置确定性合成器兜底）。
 */
class FixturesManifestTest {

    private static final Charset GB18030 = Charset.forName("GB18030");

    @Test
    @DisplayName("完整性校验整体通过：条数 / 编码 / 必需样例 / GB18030-UTF-8 同内容")
    void manifest_verification_passes() throws IOException {
        FixturesManifest.Verification v = FixturesManifest.verify();
        assertTrue(v.ok(), () -> "语料完整性问题：" + String.join("；", v.problems()));
        assertTrue(v.syntheaFiles() >= FixturesManifest.SYNTHEA_MIN,
                "synthea ER7 样本应 ≥50，实际 " + v.syntheaFiles());
        assertTrue(v.zhFiles() >= FixturesManifest.ZH_FILES_MIN,
                "中文变体文件应 ≥60，实际 " + v.zhFiles());
        assertTrue(v.hapiFiles() >= 10, "ACK 契约基线样例应 ≥10，实际 " + v.hapiFiles());
    }

    @Test
    @DisplayName("synthea 族：每条 ER7 都含 MSH 与 PID 段")
    void synthea_er7_messages_contain_msh_and_pid() throws IOException {
        List<Path> files = FixturesManifest.syntheaMessages();
        assertTrue(files.size() >= FixturesManifest.SYNTHEA_MIN);
        for (Path p : files) {
            String msg = Files.readString(p, StandardCharsets.UTF_8);
            assertTrue(msg.startsWith("MSH|"), p + " 缺 MSH");
            assertTrue(msg.contains("\rPID|"), p + " 缺 PID");
            assertTrue(msg.endsWith("\r"), p + " 未以段分隔符结尾");
        }
    }

    @Test
    @DisplayName("HAPI 族覆盖 MSH-9 变体 / MSH-12 版本变体 / ERR 段 / MSH-18 字符集声明")
    void hapi_samples_cover_ack_contract_baseline() throws IOException {
        Map<String, String> byName = new java.util.LinkedHashMap<>();
        for (Path p : FixturesManifest.hapiMessages()) {
            byName.put(p.getFileName().toString(), FixturesManifest.readBytesAs(p));
        }
        assertTrue(byName.containsKey("ack_ae_err_v251.hl7"), "缺 AE + ERR 样例");
        assertTrue(byName.get("ack_ae_err_v251.hl7").contains("\rERR|"), "AE 样例缺 ERR 段");
        assertTrue(byName.get("ack_aa_v23.hl7").contains("MSA|AA|"), "AA 样例缺 MSA-1=AA");
        assertTrue(byName.get("ack_ar_v251.hl7").contains("MSA|AR|"), "AR 样例缺 MSA-1=AR");

        boolean v23 = byName.values().stream().anyMatch(m -> m.contains("|2.3|"));
        boolean v251 = byName.values().stream().anyMatch(m -> m.contains("|2.5.1|"));
        assertTrue(v23 && v251, "MSH-12 版本变体应同时覆盖 2.3 与 2.5.1");

        for (String decl : List.of("GB18030-2000", "UNICODE UTF-8", "ASCII")) {
            assertTrue(byName.values().stream().anyMatch(m -> m.contains(decl)), "缺 MSH-18 声明：" + decl);
        }
        for (String type : List.of("ADT^A01", "ADT^A08", "ADT^A31", "ORU^R01")) {
            assertTrue(byName.values().stream().anyMatch(m -> m.contains("|" + type + "|")), "缺 MSH-9 变体：" + type);
        }
        // 故意缺陷样例：T7 的字段级错误定位要靠它们
        assertTrue(byName.containsKey("defect_missing_msh10.hl7"), "缺 MSH-10 缺陷样例");
        assertTrue(byName.containsKey("defect_missing_pid3.hl7"), "缺 PID-3 缺陷样例");
    }

    @Test
    @DisplayName("中文变体每一类 ≥20 条")
    void zh_variants_cover_every_class() throws IOException {
        Map<String, Integer> byClass = FixturesManifest.verify().zhByClass();
        for (String clazz : List.of("simplified", "traditional", "homophone", "space", "utf8", "gb18030")) {
            assertTrue(byClass.getOrDefault(clazz, 0) >= FixturesManifest.ZH_PER_CLASS_MIN,
                    clazz + " 类应 ≥20，实际 " + byClass.getOrDefault(clazz, 0));
        }
    }

    @Test
    @DisplayName("GB18030 样本真的是 GB18030 字节，且与 UTF-8 孪生文件解析出同一 PID-5")
    void gb18030_and_utf8_same_message_same_pid5_value() throws IOException {
        for (int i = 1; i <= 20; i++) {
            String name = String.format("%04d", i);
            Path utf8 = FixturesManifest.ZH_DIR.resolve("zh_" + name + "_utf8.hl7");
            Path gb = FixturesManifest.ZH_DIR.resolve("zh_" + name + "_gb18030.hl7");
            assertTrue(Files.isRegularFile(utf8) && Files.isRegularFile(gb), "缺少编码孪生样本 " + name);

            byte[] raw = Files.readAllBytes(gb);
            // 真按 GB18030 落盘：这些字节不应是合法 UTF-8（否则说明只是改了个后缀）
            assertThrows(java.nio.charset.CharacterCodingException.class,
                    () -> StandardCharsets.UTF_8.newDecoder()
                            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                            .decode(java.nio.ByteBuffer.wrap(raw)),
                    "GB18030 样本不应能被 UTF-8 干净解码：" + name);
            // 换成 GB18030 解码则干净通过
            String msgGb = new String(raw, GB18030);
            String msgUtf8 = Files.readString(utf8, StandardCharsets.UTF_8);

            assertEquals(FixturesManifest.field(msgUtf8, "PID", 5),
                    FixturesManifest.field(msgGb, "PID", 5),
                    "同一报文两种编码解析出的 PID-5 应逐字相同：" + name);
            assertEquals(FixturesManifest.field(msgUtf8, "PID", 7),
                    FixturesManifest.field(msgGb, "PID", 7),
                    "PID-7 应一致：" + name);
        }
    }

    @Test
    @DisplayName("中文变体都带 Z 段，且真值索引可读（供 T25/T26 对照）")
    void zh_variants_carry_z_segments_and_truth_index() throws IOException {
        for (Path p : FixturesManifest.zhVariantFiles()) {
            String msg = FixturesManifest.readBytesAs(p);
            assertTrue(msg.contains("\rZX"), p + " 缺 Z 段");
            assertTrue(msg.contains("既往病史摘要"), p + " 缺中文标签段");
        }
        List<FixturesManifest.PersonTruth> truth = FixturesManifest.zhTruth();
        assertEquals(24, truth.size(), "真值索引应为 24 位居民");
        FixturesManifest.PersonTruth first = truth.get(0);
        assertNotNull(first.localName());
        assertNotNull(first.idCard());
        assertTrue(first.idCard().length() == 18, "合成身份证应为 18 位格式");
        assertTrue(first.idCard().startsWith("000000"), "合成身份证区划码应为 000000（不指向真实区划）");

        List<String[]> labels = FixturesManifest.zLabelGroundTruth();
        assertEquals(4, labels.size(), "Z 段标签真值应有 4 条");
        for (String[] row : labels) {
            assertFalse(row[0].equals(row[1]), "中文标签与本地字段名应完全不同（这是模型存在的理由）");
        }
    }

    @Test
    @DisplayName("生成器可复现：同种子连跑两次逐字节一致")
    void generator_is_deterministic_across_two_runs() throws IOException {
        Path a = Files.createTempDirectory("fixtures-a");
        Path b = Files.createTempDirectory("fixtures-b");
        try {
            FixtureCorpusGenerator.main(new String[]{a.toString()});
            FixtureCorpusGenerator.main(new String[]{b.toString()});
            for (String rel : List.of("synthea/synthea_patient_0001.hl7",
                    "synthea/synthea_patient_0060.hl7",
                    "hapi/ack_ae_err_v251.hl7",
                    "hapi/msh18_gb18030_declared.hl7",
                    "zh/zh_0101_simplified.hl7",
                    "zh/zh_0203_homophone.hl7",
                    "zh/zh_0007_gb18030.hl7",
                    "zh/index.csv")) {
                assertEquals(FixturesManifest.sha256(Files.readAllBytes(a.resolve(rel))),
                        FixturesManifest.sha256(Files.readAllBytes(b.resolve(rel))),
                        "两次生成结果不一致：" + rel);
            }
        } finally {
            deleteRecursively(a);
            deleteRecursively(b);
        }
    }

    // ------------------------------------------------------------ 小工具

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 临时目录删不掉不影响断言
                }
            });
        }
    }
}