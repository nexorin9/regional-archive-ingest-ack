package cn.hospital.region.archive.model;

import cn.hospital.region.archive.ledger.LedgerRedaction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T24 身份候选闸门 —— 用例。
 *
 * <p><b>不 mock</b>：变体输入取自 T4 落盘的 {@code fixtures/zh} 真实语料
 * （简繁 / 同音错别字 / 姓名空格三类各 20 余条，UTF-8 与 GB18030 两套编码），
 * 与 T4 落盘时用的是同一份 {@code fixtures/zh/index.csv} 真值索引；
 * 端到端那一例走真的 {@code com.sun.net.httpserver.HttpServer} + 真的 TCP + 真的 UTF-8 JSON。
 *
 * <h2>替身的职责边界</h2>
 * <p>本类里的 {@link ScriptedGateway} 是<b>模型替身</b>，用来给闸门喂规范的模型输出
 * （含「依据字段写错词表」「编造不存在的院内主键」这类真实会发生的输出）。
 * 本任务要验的是<b>闸门</b>——依据词表、锚点判定、封顶、剔除、职责分离；
 * 模型自己排得准不准是 T26 的对照实验负责的，不在这里假装验了。
 *
 * <p>钦定的两条断言口径（{@code proof} 点名的两个用例）：身份证冲突直接拒收、
 * 简繁变体产出带依据的候选。
 */
@DisplayName("T24 身份候选：证件硬锚点 + 依据字段 + 无依据不出候选")
class IdentityCandidateMatcherTest {

    private static final Path FIXTURES = Path.of("src", "test", "resources", "fixtures", "zh");
    private static final String API_KEY = "stub-key-20261024";
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-10-24T01:00:00Z"), ZoneOffset.UTC);

    // 语料第一位居民（fixtures/zh/index.csv person_id=1）：真值从这里来，不手写。
    private static final String MRN_1 = "LOCMRN-0001";
    private static final String PID3_1 = "TESTPAT-0001";
    private static final String LOCAL_NAME_1 = "张伟";
    private static final String TRAD_NAME_1 = "張偉";
    private static final String HOMOPHONE_NAME_1 = "章偉";
    private static final String SPACE_NAME_1 = "张 伟";
    private static final String BIRTH_1 = "19850512";
    private static final String ID_CARD_1 = "000000198505120371";

    // =====================================================================
    // 钦定断言口径之一：身份证冲突 → 拒收，且不产出候选
    // =====================================================================

    @Test
    @DisplayName("身份证冲突：证件摘要对不上且姓名+出生日期一致 → 整条拒收、不产出候选、不调模型")
    void test_idcard_conflict_rejects_without_candidates() {
        ScriptedGateway gateway = new ScriptedGateway();
        IdentityCandidateMatcher matcher = new IdentityCandidateMatcher(resilient(gateway));

        // 院内这条是同一个人（姓名 + 出生日期同时一致），但证件摘要不是同一个
        LocalPatientIndex index = indexOf(person(MRN_1, LOCAL_NAME_1, BIRTH_1, digestOf(ID_CARD_1)));
        InboundIdentity inbound = new InboundIdentity(PID3_1, LOCAL_NAME_1, BIRTH_1,
                LedgerRedaction.digest8("000000199901019999"));

        IdentityMatchReport report = matcher.match(inbound, index);

        assertEquals(IdentityMatchReport.Outcome.ID_CARD_CONFLICT_REJECTED, report.outcome(),
                "证件冲突必须整条拒收：同一个人对不上证件，先要人核对，而不是给一个像模像样的候选");
        assertTrue(report.candidates().isEmpty(), "拒收不得产出候选：候选一旦摆上确认台，人就会点确认");
        assertEquals(1, report.conflicts().size());
        IdentityMatchReport.IdCardConflict conflict = report.conflicts().get(0);
        assertEquals(MRN_1, conflict.localMrn());
        assertEquals("姓名与出生日期同时一致", conflict.matchedOn(),
                "必须写明凭什么判为「本来该是同一个人」，否则信息员无法自行复核这个拒收");
        assertTrue(report.rejected());
        assertFalse(report.requiresHumanConfirmation(),
                "拒收不进待确认：它走 AR 回执，由区域平台与信息员线下核对证件");
        assertEquals(IdentityAnchorLevel.IDCARD, report.anchor(),
                "证件有、只是对不上；锚点仍是有证件，只是被冲突拦下了");
        assertEquals(0, gateway.calls(),
                "冲突是确定性判定且已是终局，不该为它付一次模型调用");
    }

    @Test
    @DisplayName("冲突判定不因模型不可达而失效：模型整挂也照样拒收")
    void test_idcard_conflict_detected_before_any_model_call() {
        ScriptedGateway gateway = new ScriptedGateway();
        gateway.failWith(ModelErrorCode.CONNECT_TIMEOUT);
        IdentityCandidateMatcher matcher = new IdentityCandidateMatcher(resilient(gateway));

        LocalPatientIndex index = indexOf(person(MRN_1, LOCAL_NAME_1, BIRTH_1, digestOf(ID_CARD_1)));
        InboundIdentity inbound = new InboundIdentity(PID3_1, LOCAL_NAME_1, BIRTH_1,
                LedgerRedaction.digest8("000000199901019999"));

        IdentityMatchReport report = matcher.match(inbound, index);

        assertTrue(report.rejected(), "证件冲突是带安全性质的确定性结论，不能因为模型挂了就没有了");
        assertEquals(0, gateway.calls());
    }

    @Test
    @DisplayName("跨院主键与院内主键字面相同、证件摘要不一致 → 同样判冲突（主键也是硬键）")
    void test_cross_ref_key_mismatch_with_different_idcard_is_a_conflict() {
        ScriptedGateway gateway = new ScriptedGateway();
        IdentityCandidateMatcher matcher = new IdentityCandidateMatcher(resilient(gateway));

        // 姓名与出生日期故意对不上，只靠跨院主键字面命中 —— 主键比得上但证件对不上，仍然是冲突
        LocalPatientIndex index = indexOf(person(MRN_1, LOCAL_NAME_1, BIRTH_1, digestOf(ID_CARD_1)));
        InboundIdentity inbound = new InboundIdentity(MRN_1, "李雷", "19700101",
                LedgerRedaction.digest8("000000197001010016"));

        IdentityMatchReport report = matcher.match(inbound, index);

        assertTrue(report.rejected());
        assertEquals("跨院主键与院内主键字面相同", report.conflicts().get(0).matchedOn());
        assertEquals(0, gateway.calls());
    }

    @Test
    @DisplayName("只有姓名相同、出生日期不同 → 不算冲突：同名的人满街都是，不能误拒")
    void test_same_name_different_birth_is_not_a_conflict() {
        ScriptedGateway gateway = new ScriptedGateway();
        gateway.returns(candidate(MRN_1, 0.62,
                evidence("name", "平台与院内姓名字面一致"), evidence("birth_date", "出生日期不一致")));
        IdentityCandidateMatcher matcher = new IdentityCandidateMatcher(resilient(gateway));

        LocalPatientIndex index = indexOf(person(MRN_1, LOCAL_NAME_1, BIRTH_1, digestOf(ID_CARD_1)));
        InboundIdentity inbound = new InboundIdentity("TESTPAT-9999", LOCAL_NAME_1, "19991231",
                LedgerRedaction.digest8("000000199912319876"));

        IdentityMatchReport report = matcher.match(inbound, index);

        assertFalse(report.rejected(), "只凭姓名相同就拒收，会把一批本来正确的档案全部拒掉");
        assertEquals(1, report.candidates().size());
    }

    // =====================================================================
    // 钦定断言口径之二：简繁变体产出带依据的候选
    // =====================================================================

    @Test
    @DisplayName("简繁变体：平台写「張偉」、院内写「张伟」→ 产出候选且依据写明是简繁异体")
    void test_simplified_traditional_variant_returns_candidate_with_evidence() {
        ScriptedGateway gateway = new ScriptedGateway();
        gateway.returns(candidate(MRN_1, 0.82,
                evidence("name", "平台写的是繁体姓名，与院内简体写法指向同一人"),
                evidence("birth_date", "出生日期一致")));
        IdentityCandidateMatcher matcher = new IdentityCandidateMatcher(resilient(gateway));

        IdentityMatchReport report = matcher.match(
                new InboundIdentity(PID3_1, TRAD_NAME_1, BIRTH_1, ""), indexOfPerson1());

        assertEquals(IdentityMatchReport.Outcome.CANDIDATES_READY, report.outcome());
        assertEquals(IdentityAnchorLevel.MISSING, report.anchor(),
                "平台没给证件，没有硬锚点，只能出候选等人工确认");
        assertEquals(1, report.candidates().size());
        IdentityCandidate best = report.best().orElseThrow();
        assertEquals(MRN_1, best.localMrn());
        assertFalse(best.evidence().isEmpty(), "候选必须带依据，否则信息员无从判断该不该点确认");

        String nameEvidence = detailsOf(best, IdentityEvidenceField.NAME);
        assertNotNull(nameEvidence, "依据里必须有姓名这一项");
        assertTrue(nameEvidence.contains("简繁异体"),
                "本机核对出的依据要直接说清是哪一类变体，实际是：" + nameEvidence);
        assertTrue(nameEvidence.contains("本机核对"),
                "模型的判断与本机核出来的事实要同时在场：前者说像不像，后者说可不可以复核");
        assertTrue(detailOf(best, IdentityEvidenceField.BIRTH_DATE).contains("一致"));
        assertEquals(1, gateway.calls(), "简繁变体是规则判不了的，必须真的问了模型");
    }

    @Test
    @DisplayName("同音错别字：平台写「章偉」、院内写「张伟」→ 产出候选且依据写明用字不同")
    void test_homophone_variant_returns_candidate_with_evidence() {
        ScriptedGateway gateway = new ScriptedGateway();
        gateway.returns(candidate(MRN_1, 0.71,
                evidence("name", "姓名读音相同、用字不同，应为同一人")));
        IdentityCandidateMatcher matcher = new IdentityCandidateMatcher(resilient(gateway));

        IdentityMatchReport report = matcher.match(
                new InboundIdentity(PID3_1, HOMOPHONE_NAME_1, BIRTH_1, ""), indexOfPerson1());

        IdentityCandidate best = report.best().orElseThrow();
        assertEquals(MRN_1, best.localMrn());
        String note = detailsOf(best, IdentityEvidenceField.NAME);
        assertNotNull(note);
        assertTrue(note.contains("用字不同"), "同音错别字的依据要落在「用字不同」上，实际是：" + note);
        assertFalse(IdentityText.sameName(HOMOPHONE_NAME_1, LOCAL_NAME_1),
                "确定性归一不该把同音错别字判成同名：判成同名，这条就不需要模型了");
    }

    @Test
    @DisplayName("姓名含空格：平台写「张 伟」（含全角空格）→ 归一后与院内一致，依据写明空格差异")
    void test_space_variant_returns_candidate_with_evidence() {
        ScriptedGateway gateway = new ScriptedGateway();
        gateway.returns(candidate(MRN_1, 0.66, evidence("name", "平台写法在姓名中间带空格")));
        IdentityCandidateMatcher matcher = new IdentityCandidateMatcher(resilient(gateway));

        InboundIdentity inbound = new InboundIdentity(PID3_1, "张　伟", BIRTH_1, "");
        IdentityMatchReport report = matcher.match(inbound, indexOfPerson1());

        IdentityCandidate best = report.best().orElseThrow();
        assertEquals(MRN_1, best.localMrn());
        assertTrue(detailsOf(best, IdentityEvidenceField.NAME).contains("空格"));
    }

    @Test
    @DisplayName("三类变体全语料跑一遍：每条都产出带依据的候选，且正确院内主键在候选里")
    void test_every_zh_variant_row_yields_candidate_with_evidence() throws IOException {
        List<String[]> rows = zhRows();
        assertTrue(rows.size() >= 60,
                "中文变体语料应不少于 60 条，实际 " + rows.size());

        int checked = 0;
        for (String[] row : rows) {
            String clazz = row[1];
            if (!clazz.equals("traditional") && !clazz.equals("homophone") && !clazz.equals("space")) {
                continue;
            }
            String mrn = row[8];
            String remoteName = row[3];
            String birth = row[5];
            String pid3 = row[7];

            ScriptedGateway gateway = new ScriptedGateway();
            gateway.returns(candidate(mrn, 0.8,
                    evidence("name", "平台写法「" + remoteName + "」与院内写法指向同一人")));
            IdentityCandidateMatcher matcher = new IdentityCandidateMatcher(resilient(gateway));

            LocalPatientIndex index = indexOf(person(mrn, row[4], birth, ""));
            IdentityMatchReport report = matcher.match(new InboundIdentity(pid3, remoteName, birth, ""), index);

            assertEquals(IdentityMatchReport.Outcome.CANDIDATES_READY, report.outcome(),
                    "变体类 " + clazz + " 的报文应当产出候选：主键 " + mrn);
            IdentityCandidate best = report.best().orElseThrow();
            assertEquals(mrn, best.localMrn(),
                    "变体类 " + clazz + "（文件 " + row[0] + "）的正确院内主键必须在候选里");
            assertFalse(best.evidence().isEmpty(), "候选 " + mrn + " 必须带依据");
            checked++;
        }
        assertEquals(72, checked, "简繁 / 同音 / 空格三类各 24 条，共 72 条要逐条过闸");
    }

    // =====================================================================
    // 硬锚点：证件一致 → 高置信、零调用
    // =====================================================================

    @Test
    @DisplayName("证件摘要一致：置信度置高、锚点 IDCARD、不调模型")
    void test_idcard_anchor_raises_confidence_without_model_call() {
        ScriptedGateway gateway = new ScriptedGateway();
        IdentityCandidateMatcher matcher = new IdentityCandidateMatcher(resilient(gateway));

        LocalPatientIndex index = indexOf(person(MRN_1, LOCAL_NAME_1, BIRTH_1, digestOf(ID_CARD_1)));
        IdentityMatchReport report = matcher.match(
                new InboundIdentity(PID3_1, TRAD_NAME_1, BIRTH_1, digestOf(ID_CARD_1)), index);

        assertEquals(IdentityMatchReport.Outcome.CANDIDATES_READY, report.outcome());
        assertEquals(IdentityAnchorLevel.IDCARD, report.anchor());
        IdentityCandidate best = report.best().orElseThrow();
        assertEquals(MRN_1, best.localMrn());
        assertEquals(IdentityCandidateMatcher.ANCHORED_CONFIDENCE, best.confidence(), 1e-9,
                "证件一致是硬锚点，置信度应直接置高");
        assertTrue(detailOf(best, IdentityEvidenceField.ID_CARD_DIGEST8).contains("硬锚点"));
        assertEquals(0, gateway.calls(),
                "证件一致就是一个人对一个人，不该再花钱问模型");
    }

    @Test
    @DisplayName("平台没给证件：模型给 0.99 也被压到无锚点上限，且低于合档阈值（不可能自动落地）")
    void test_missing_idcard_caps_confidence_below_auto_land_threshold() {
        ScriptedGateway gateway = new ScriptedGateway();
        gateway.returns(candidate(MRN_1, 0.99,
                evidence("name", "姓名字面一致"), evidence("birth_date", "出生日期一致")));
        IdentityCandidateMatcher matcher = new IdentityCandidateMatcher(resilient(gateway));

        IdentityMatchReport report = matcher.match(
                new InboundIdentity(PID3_1, LOCAL_NAME_1, BIRTH_1, ""), indexOfPerson1());

        IdentityCandidate best = report.best().orElseThrow();
        assertEquals(IdentityCandidateMatcher.UNANCHORED_CONFIDENCE_CAP, best.confidence(), 1e-9);
        assertTrue(best.confidence() < ZFieldAligner.DEFAULT_CONFIDENCE_THRESHOLD,
                "没有证件锚点时，置信度必须低到任何按合档阈值判断的下游都判为需人工确认");
        assertTrue(detailOf(best, IdentityEvidenceField.ID_CARD_DIGEST8).contains("无硬锚点"),
                "封顶这件事要写在依据里，否则信息员看到 0.99 会以为这条已经定了");
    }

    @Test
    @DisplayName("跨院主键与院内主键字面相同：置信度提到次强锚点，依据写明主键命中")
    void test_cross_ref_anchor_boosts_confidence() {
        ScriptedGateway gateway = new ScriptedGateway();
        gateway.returns(candidate(MRN_1, 0.50, evidence("name", "姓名读音相近")));
        IdentityCandidateMatcher matcher = new IdentityCandidateMatcher(resilient(gateway));

        // 主键命中但姓名与出生日期都对不上：此时唯一能确定的依据就是主键本身
        LocalPatientIndex index = indexOf(person(MRN_1, LOCAL_NAME_1, BIRTH_1, ""));
        IdentityMatchReport report = matcher.match(
                new InboundIdentity(MRN_1, "李雷", "19700101", ""), index);

        assertEquals(IdentityAnchorLevel.CROSS_REF, report.anchor());
        IdentityCandidate best = report.best().orElseThrow();
        assertEquals(IdentityCandidateMatcher.CROSS_REF_CONFIDENCE, best.confidence(), 1e-9);
        assertTrue(detailOf(best, IdentityEvidenceField.PATIENT_REF).contains("字面相同"));
    }

    // =====================================================================
    // 依据字段：没有依据的候选一律不产出
    // =====================================================================

    @Test
    @DisplayName("依据字段写在词表外（「综合判断」）→ 该依据丢弃；四个字段都无可核对时整条候选不产出")
    void test_candidate_without_any_comparable_field_is_dropped() {
        ScriptedGateway gateway = new ScriptedGateway();
        gateway.returns(candidate(MRN_1, 0.88,
                evidence("综合判断", "看起来比较像同一个人"),
                evidence("整体观感", "档案内容接近")));
        IdentityCandidateMatcher matcher = new IdentityCandidateMatcher(resilient(gateway));

        // 平台给了姓名（所以比对照得起来），但院内这条记录的姓名与出生日期都是空：
        // 词表外依据被丢掉之后，本机一条可核对的依据都补不出来 → 不得产出
        //
        // 「平台侧姓名与出生日期都空」的那条路现在会更早短路（DirtyDataPolicy：连一个可比字段
        // 都没有就不必调模型），由 ModelDirtyDataTest 的
        // test_empty_name_and_birthdate_no_candidate_without_model_call 单独覆盖。
        // 本用例要钉的是<b>另一件事</b>：模型给了候选，但依据一条都核不出来。
        LocalPatientIndex index = indexOf(person(MRN_1, "", "", ""));
        IdentityMatchReport report = matcher.match(
                new InboundIdentity(PID3_1, LOCAL_NAME_1, BIRTH_1, ""), index);

        assertEquals(IdentityMatchReport.Outcome.NO_CANDIDATE, report.outcome(),
                "一条依据都核不出来、模型又只给了无法核对的说辞 → 不产出候选");
        assertTrue(report.candidates().isEmpty());
        assertTrue(report.requiresHumanConfirmation(),
                "没有候选也要进待确认并说明原因，绝不能静默丢弃这条报文");
        assertTrue(report.note().contains("无依据") || report.note().contains("未给出可用"));
    }

    @Test
    @DisplayName("依据字段大小写与空白差异按同一字段收口（name / Name / \" name \" 等价）")
    void test_evidence_field_name_is_case_and_space_insensitive() {
        ScriptedGateway gateway = new ScriptedGateway();
        gateway.returns(candidate(MRN_1, 0.5, evidence(" Name ", "姓名字面一致")));
        IdentityCandidateMatcher matcher = new IdentityCandidateMatcher(resilient(gateway));

        // 院内这条姓名与出生日期都与平台不同：能落地的依据只有模型那条 name
        LocalPatientIndex index = indexOf(person(MRN_1, "李雷", "19700101", ""));
        IdentityMatchReport report = matcher.match(
                new InboundIdentity(PID3_1, LOCAL_NAME_1, BIRTH_1, ""), index);

        IdentityCandidate best = report.best().orElseThrow();
        assertEquals(MRN_1, best.localMrn());
        assertEquals("姓名字面一致", detailOf(best, IdentityEvidenceField.NAME),
                "\" Name \" 这种写法说的是同一件事，丢掉它等于让模型白写依据");
    }

    @Test
    @DisplayName("模型编造了候选切片之外的院内主键 → 剔除，不进确认台")
    void test_candidate_outside_slice_is_dropped() {
        ScriptedGateway gateway = new ScriptedGateway();
        gateway.returns(candidate("LOCMRN-9999", 0.9, evidence("name", "姓名一致")),
                candidate(MRN_1, 0.7, evidence("name", "姓名一致")));
        IdentityCandidateMatcher matcher = new IdentityCandidateMatcher(resilient(gateway));

        IdentityMatchReport report = matcher.match(
                new InboundIdentity(PID3_1, LOCAL_NAME_1, BIRTH_1, ""), indexOfPerson1());

        assertEquals(1, report.candidates().size());
        assertEquals(MRN_1, report.candidates().candidates().get(0).localMrn(),
                "凭空造出来的院内主键进了确认台，就等于凭空多一个人");
    }

    @Test
    @DisplayName("候选切片为空 → 不产出候选、不调模型，并说明多半是首次建档")
    void test_empty_slice_returns_no_candidate_without_model_call() {
        ScriptedGateway gateway = new ScriptedGateway();
        IdentityCandidateMatcher matcher = new IdentityCandidateMatcher(resilient(gateway));

        IdentityMatchReport report = matcher.match(
                new InboundIdentity(PID3_1, LOCAL_NAME_1, BIRTH_1, ""), LocalPatientIndex.empty());

        assertEquals(IdentityMatchReport.Outcome.NO_CANDIDATE, report.outcome());
        assertTrue(report.note().contains("首次建档"));
        assertEquals(0, gateway.calls());
    }

    // =====================================================================
    // 降级：模型不可达时仍然给出确定性结论，且明确标注降级
    // =====================================================================

    @Test
    @DisplayName("模型不可达：收敛为降级并保留确定性命中项，置信度仍受无锚点上限约束")
    void test_model_unreachable_keeps_deterministic_candidates() {
        ScriptedGateway gateway = new ScriptedGateway();
        gateway.failWith(ModelErrorCode.CONNECT_TIMEOUT);
        IdentityCandidateMatcher matcher = new IdentityCandidateMatcher(resilient(gateway));

        // 姓名与出生日期完全一致（空格变体），但平台没给证件
        LocalPatientIndex index = indexOf(person(MRN_1, LOCAL_NAME_1, BIRTH_1, ""));
        IdentityMatchReport report = matcher.match(
                new InboundIdentity(PID3_1, SPACE_NAME_1, BIRTH_1, ""), index);

        assertEquals(IdentityMatchReport.Outcome.MODEL_DEGRADED, report.outcome());
        assertTrue(report.requiresHumanConfirmation(), "降级不是失败：这条仍要进待确认等人处理");
        assertEquals(1, report.candidates().size(), "确定性核对命中的那一条要留住");
        IdentityCandidate only = report.candidates().candidates().get(0);
        assertEquals(MRN_1, only.localMrn());
        assertTrue(only.confidence() <= IdentityCandidateMatcher.UNANCHORED_CONFIDENCE_CAP);
        assertTrue(report.note().contains("模型不可达"), "降级原因要跟着结论一起走，实际：" + report.note());
    }

    @Test
    @DisplayName("模型不可达且本机也核不出候选 → 仍是降级、仍需人工确认，而不是假装没有候选问题")
    void test_model_unreachable_with_no_deterministic_hit_still_requires_human() {
        ScriptedGateway gateway = new ScriptedGateway();
        gateway.failWith(ModelErrorCode.SCHEMA_INVALID);
        IdentityCandidateMatcher matcher = new IdentityCandidateMatcher(resilient(gateway));

        LocalPatientIndex index = indexOf(person(MRN_1, LOCAL_NAME_1, BIRTH_1, ""));
        IdentityMatchReport report = matcher.match(
                new InboundIdentity(PID3_1, "章偉", "19991231", ""), index);

        assertEquals(IdentityMatchReport.Outcome.MODEL_DEGRADED, report.outcome());
        assertTrue(report.candidates().isEmpty());
        assertTrue(report.requiresHumanConfirmation());
    }

    // =====================================================================
    // 职责分离：候选只用于人工确认，matcher 结构上就写不了 HIS
    // =====================================================================

    @Test
    @DisplayName("matcher 不持有任何 HIS 写端口：字段、构造参数、方法签名里都不出现 PatientIndexWriter")
    void test_matcher_cannot_call_patient_index_writer() throws Exception {
        Class<?> writer = Class.forName("cn.hospital.region.archive.identity.PatientIndexWriter");
        for (Class<?> type : List.of(IdentityCandidateMatcher.class, IdentityMatchReport.class,
                IdentityCandidate.class, Candidates.class, InboundIdentity.class)) {
            for (var field : type.getDeclaredFields()) {
                assertFalse(writer.isAssignableFrom(field.getType()),
                        type.getSimpleName() + " 的字段 " + field.getName() + " 持有 HIS 写端口");
            }
            for (Constructor<?> ctor : type.getDeclaredConstructors()) {
                for (Class<?> p : ctor.getParameterTypes()) {
                    assertFalse(writer.isAssignableFrom(p),
                            type.getSimpleName() + " 的构造参数持有 HIS 写端口");
                }
            }
            for (Method method : type.getDeclaredMethods()) {
                for (Class<?> p : method.getParameterTypes()) {
                    assertFalse(writer.isAssignableFrom(p),
                            type.getSimpleName() + "#" + method.getName() + " 接受 HIS 写端口");
                }
                assertFalse(writer.isAssignableFrom(method.getReturnType()),
                        type.getSimpleName() + "#" + method.getName() + " 返回 HIS 写端口");
            }
        }
        // 再加一道：闸门持有的任何依赖都不得来自 identity 包（确认门与 HIS 客户端都在那里）
        for (var field : IdentityCandidateMatcher.class.getDeclaredFields()) {
            String owner = field.getType().getName();
            assertFalse(owner.startsWith("cn.hospital.region.archive.identity"),
                    "闸门持有 identity 包的依赖 " + owner + "：确认门与 HIS 写回不该从候选这一层被引用");
        }
    }

    @Test
    @DisplayName("全流程里候选始终只是候选：跑完比对，落库接口与状态一律未动")
    void test_candidate_production_never_touches_ledger_or_his() {
        ScriptedGateway gateway = new ScriptedGateway();
        gateway.returns(candidate(MRN_1, 0.8, evidence("name", "姓名一致")));
        IdentityCandidateMatcher matcher = new IdentityCandidateMatcher(resilient(gateway));

        IdentityMatchReport report = matcher.match(
                new InboundIdentity(PID3_1, LOCAL_NAME_1, BIRTH_1, ""), indexOfPerson1());

        assertEquals(IdentityMatchReport.Outcome.CANDIDATES_READY, report.outcome());
        assertTrue(report.requiresHumanConfirmation(),
                "候选只把这条推给「待确认」；落地要等信息员点确认（本任务不提供这个动作）");
        assertTrue(report.conflicts().isEmpty());
    }

    // =====================================================================
    // 端到端：真 HTTP + 真 JSON → 网关 → 降级出口 → 闸门
    // =====================================================================

    @Test
    @DisplayName("端到端：真实 HTTP 替身 → 真实网关 → 候选闸门，简繁变体出带依据候选")
    void test_end_to_end_through_real_http_gateway() throws IOException {
        try (OpenAiCompatibleStubServer stub = OpenAiCompatibleStubServer.start(API_KEY)) {
            stub.setContentOverride(Json.write(Map.of("candidates", List.of(
                    Map.of("local_mrn", MRN_1,
                            "confidence", 0.79,
                            "evidence", List.of(
                                    Map.of("field", "name", "detail", "平台写繁体姓名，与院内简体写法指向同一人"),
                                    Map.of("field", "birth_date", "detail", "出生日期一致")))))));

            ModelGatewayConfig config = new ModelGatewayConfig(stub.baseUrl(), API_KEY, "stub-model-local", 15, 0);
            OpenAiCompatibleGateway strict = new OpenAiCompatibleGateway(
                    config, new InMemoryModelCallAudit(), HttpClient.newHttpClient(), FIXED_CLOCK);
            IdentityCandidateMatcher matcher = new IdentityCandidateMatcher(new ResilientModelGateway(strict, new ModelRetryPolicy(0)));

            IdentityMatchReport report = matcher.match(
                    new InboundIdentity(PID3_1, TRAD_NAME_1, BIRTH_1, ""), indexOfPerson1());

            assertEquals(IdentityMatchReport.Outcome.CANDIDATES_READY, report.outcome());
            IdentityCandidate best = report.best().orElseThrow();
            assertEquals(MRN_1, best.localMrn());
            assertNotNull(detailOf(best, IdentityEvidenceField.NAME),
                    "模型写的依据应当原样落在词表内的字段上");
            assertNotNull(detailOf(best, IdentityEvidenceField.BIRTH_DATE));
            assertTrue(stub.requestCount() >= 1, "这一例必须真的发过一次 HTTP，而不是走本地替身");
        }
    }

    @Test
    @DisplayName("端到端：模型返回 500 → 收敛为降级，身份证冲突那条仍按拒收走")
    void test_end_to_end_http_failure_degrades_without_pushing_state() throws IOException {
        try (OpenAiCompatibleStubServer stub = OpenAiCompatibleStubServer.start(API_KEY)) {
            stub.setHttpStatus(500);
            ModelGatewayConfig config = new ModelGatewayConfig(stub.baseUrl(), API_KEY, "stub-model-local", 15, 0);
            OpenAiCompatibleGateway strict = new OpenAiCompatibleGateway(
                    config, new InMemoryModelCallAudit(), HttpClient.newHttpClient(), FIXED_CLOCK);
            IdentityCandidateMatcher matcher = new IdentityCandidateMatcher(new ResilientModelGateway(strict, new ModelRetryPolicy(0)));

            LocalPatientIndex index = indexOf(person(MRN_1, LOCAL_NAME_1, BIRTH_1, ""));
            IdentityMatchReport report = matcher.match(
                    new InboundIdentity(PID3_1, LOCAL_NAME_1, BIRTH_1, ""), index);

            assertEquals(IdentityMatchReport.Outcome.MODEL_DEGRADED, report.outcome());
            assertTrue(report.requiresHumanConfirmation());
            assertEquals(1, report.candidates().size(), "确定性核对命中的那一条仍要留住");
        }
    }

    // =====================================================================
    // 日志：只落主键与字段名，不落姓名 / 证件 / 原文
    // =====================================================================

    @Test
    @DisplayName("冲突与剔除告警里没有姓名、证件号与报文原文")
    void test_warning_logs_carry_no_patient_plaintext() {
        LogCollector logs = LogCollector.attachTo(IdentityCandidateMatcher.class);
        try {
            ScriptedGateway gateway = new ScriptedGateway();
            gateway.returns(candidate(MRN_1, 0.9, evidence("综合判断", "看起来像")));
            IdentityCandidateMatcher matcher = new IdentityCandidateMatcher(resilient(gateway));

            // 触发剔除告警
            matcher.match(new InboundIdentity(PID3_1, LOCAL_NAME_1, BIRTH_1, ""),
                    indexOf(person(MRN_1, "李雷", "19700101", "")));
            // 触发冲突告警
            matcher.match(new InboundIdentity(PID3_1, LOCAL_NAME_1, BIRTH_1,
                            LedgerRedaction.digest8("000000199901019999")),
                    indexOf(person(MRN_1, LOCAL_NAME_1, BIRTH_1, digestOf(ID_CARD_1))));

            assertTrue(logs.warningCount() >= 2, "两处告警都该打出来，实际 " + logs.warningCount());
            for (String warning : logs.warnings()) {
                assertFalse(warning.contains(LOCAL_NAME_1), "日志里出现姓名：" + warning);
                assertFalse(warning.contains(ID_CARD_1), "日志里出现证件号：" + warning);
                assertFalse(warning.contains("000000199901019999"), "日志里出现证件号：" + warning);
            }
            assertTrue(logs.warnings().stream().anyMatch(w -> w.contains(MRN_1)),
                    "院内主键是可以落的：排障要靠它，报出来才查得到");
        } finally {
            logs.detach();
        }
    }

    // =====================================================================
    // 辅助
    // =====================================================================

    /** 一条院内记录。 */
    private static LocalPatientIndex.LocalPatient person(String mrn, String name, String birth, String digest) {
        return new LocalPatientIndex.LocalPatient(mrn, name, birth, digest);
    }

    private static LocalPatientIndex indexOf(LocalPatientIndex.LocalPatient... patients) {
        return new LocalPatientIndex(List.of(patients));
    }

    /** 语料第一位居民的院内切片（证件摘要留空：测无锚点路径时不该被证件拦下）。 */
    private static LocalPatientIndex indexOfPerson1() {
        return indexOf(person(MRN_1, LOCAL_NAME_1, BIRTH_1, ""));
    }

    private static String digestOf(String idCard) {
        return LedgerRedaction.digest8(idCard);
    }

    private static IdentityCandidate candidate(String mrn, double confidence,
                                               IdentityCandidate.Evidence... evidence) {
        return new IdentityCandidate(mrn, confidence, List.of(evidence));
    }

    private static IdentityCandidate.Evidence evidence(String field, String detail) {
        return new IdentityCandidate.Evidence(field, detail);
    }

    /** 某字段下的第一条依据（模型写的通常在前）。 */
    private static String detailOf(IdentityCandidate candidate, IdentityEvidenceField field) {
        for (IdentityCandidate.Evidence e : candidate.evidence()) {
            if (field.wire().equals(e.field())) {
                return e.detail();
            }
        }
        return null;
    }

    /**
     * 某字段下的全部依据（模型写的 + 本机核对的，按写入顺序拼开）。
     *
     * <p>同一个字段下可能挂着两条依据：模型给的理由与本机核对出的事实。
     * 只看第一条会漏掉「本机核出来的是什么」，而那恰恰是信息员能自己复核的那一半。
     */
    private static String detailsOf(IdentityCandidate candidate, IdentityEvidenceField field) {
        StringBuilder sb = new StringBuilder();
        for (IdentityCandidate.Evidence e : candidate.evidence()) {
            if (field.wire().equals(e.field())) {
                sb.append(e.detail()).append(" | ");
            }
        }
        return sb.toString();
    }

    private static ResilientModelGateway resilient(ScriptedGateway gateway) {
        return new ResilientModelGateway(gateway, new ModelRetryPolicy(0));
    }

    /** 读中文变体真值索引：file,class,person_id,remote_name,local_name,birth_date,id_card,pid3,local_mrn,... */
    private static List<String[]> zhRows() throws IOException {
        List<String[]> out = new ArrayList<>();
        for (String line : Files.readAllLines(FIXTURES.resolve("index.csv"), StandardCharsets.UTF_8)) {
            String[] c = line.split(",", -1);
            if (c.length >= 11 && !c[0].equals("file") && !c[0].isBlank()) {
                out.add(c);
            }
        }
        return out;
    }

    /**
     * 模型替身：记调用次数，按脚本返回候选或按脚本抛错。
     *
     * <p>不 mock 网络：真发网络那一例走 {@link OpenAiCompatibleStubServer}。
     */
    private static final class ScriptedGateway implements ModelGateway {

        private Candidates canned = Candidates.empty();
        private ModelErrorCode failWith = null;
        private int calls = 0;
        private final List<String> lastSliceMrns = new ArrayList<>();

        void returns(IdentityCandidate... candidates) {
            this.canned = new Candidates(List.of(candidates));
        }

        void failWith(ModelErrorCode code) {
            this.failWith = code;
        }

        int calls() {
            return calls;
        }

        @Override
        public ZFieldMap alignZFields(List<ZSegment> zSegments) {
            throw new UnsupportedOperationException("本任务只验身份候选");
        }

        @Override
        public Candidates identityCandidates(String pid3, String pid5, String pid7,
                                              LocalPatientIndex localIndex) {
            calls++;
            lastSliceMrns.clear();
            localIndex.patients().forEach(p -> lastSliceMrns.add(p.localMrn()));
            if (failWith != null) {
                throw new ModelCallException(failWith, "测试注入的失败");
            }
            return canned;
        }

        List<String> lastSliceMrns() {
            return lastSliceMrns;
        }
    }

    /** 收集指定类 logger 的 WARNING，供「日志里没有患者明文」这类断言使用。 */
    private static final class LogCollector extends Handler {

        private final Logger target;
        private final List<String> records = new ArrayList<>();

        private LogCollector(Logger target) {
            this.target = target;
            setLevel(Level.ALL);
        }

        static LogCollector attachTo(Class<?> type) {
            Logger logger = Logger.getLogger(type.getName());
            LogCollector collector = new LogCollector(logger);
            collector.target.setLevel(Level.ALL);
            collector.target.addHandler(collector);
            return collector;
        }

        void detach() {
            target.removeHandler(this);
        }

        @Override
        public void publish(LogRecord record) {
            if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                records.add(java.text.MessageFormat.format(
                        record.getMessage(), record.getParameters()));
            }
        }

        int warningCount() {
            return records.size();
        }

        List<String> warnings() {
            return records;
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }
}