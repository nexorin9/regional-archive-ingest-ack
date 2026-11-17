package cn.hospital.region.archive.identity;

import cn.hospital.region.archive.ack.Hl7ErrorCodes;
import cn.hospital.region.archive.ack.RejectionReason;
import cn.hospital.region.archive.ledger.IdempotencyKey;
import cn.hospital.region.archive.ledger.JdbcLedgerStore;
import cn.hospital.region.archive.ledger.LedgerEntry;
import cn.hospital.region.archive.ledger.LedgerSchema;
import cn.hospital.region.archive.ledger.MessageState;
import cn.hospital.region.archive.model.IdentityAnchorLevel;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T29 验收 —— 身份硬校验三态、两套主键分离存储、以及「冲突态一个字都不许落」。
 *
 * <p><b>不 mock 存储层</b>：H2 PG 兼容模式内存库 + 真实 JDBC + 真实事务，
 * 迁移跑的是与生产同一份 {@code V3__identity_binding.sql}。
 * 「两列分开写」这件事只有真查列才验得了：mock 掉 store 之后，
 * 一份把两个值写进同一列的实现也能让测试通过。
 *
 * <p><b>证件号取样说明</b>：{@code 11010519491231002X} 与其 15 位前身
 * {@code 110105491231002} 取自 GB 11643-1999 的公开示例，两者的校验位自校验都通过；
 * 其余冲突样例在第 17 位上做一处改动，改完必然校验不过，
 * 因此冲突用例走的是<b>两侧都可用但不同</b>这一支，而不是拿非法号充数。
 */
class IdentityHardRulesTest {

    private static final String H2_URL =
            "jdbc:h2:mem:identity_hard_rules_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

    /** GB 11643 公开示例号，18 位、校验位自洽。 */
    private static final String IDCARD_A = "11010519491231002X";
    /** 同一人的 15 位老号，按 GB 11643 升位后与 {@link #IDCARD_A} 相同。 */
    private static final String IDCARD_A15 = "110105491231002";
    /** 与 A 同为 18 位且校验位自洽，但确为另一个人的号（第 17 位由 0 改 3，校验位随之变 8）。 */
    private static final String IDCARD_B = "110105194912310038";

    private final IdentityHardRules rules = new IdentityHardRules();

    private static int seq = 0;

    // ================================================================ 三态

    @Test
    @DisplayName("一致：归一后相同 → anchor=IDCARD，允许合档，不拒收")
    void test_matching_idcard_yields_idcard_anchor() {
        IdentityHardDecision d = rules.evaluate(IDCARD_A, IDCARD_A);

        assertEquals(IdentityHardDecision.State.MATCH, d.state());
        assertEquals(IdentityAnchorLevel.IDCARD, d.anchor());
        assertTrue(d.mergeAllowed());
        assertTrue(d.allowsHisWrite());
        assertTrue(d.candidateEligible());
        assertFalse(d.rejected());
        assertTrue(d.redeliverable(), "非拒收态不拦重投（AE 类回执可重投；拒收由 AR 那一态拦）");
        assertNull(d.rejection(), "一致态不得携带拒收原因");
        assertNull(d.errorCodeOrNull(), "一致态不得携带错误码");
        assertEquals("identity-hard-match", d.ledgerReason());
    }

    @Test
    @DisplayName("冲突：拒收并回 AR，且未触发任何下游写入")
    void test_conflicting_idcard_rejects_with_AR() throws Exception {
        // 下游两侧都装计数器：院内主索引写入端口 + 台账双主键绑定端口。
        AtomicInteger hisWrites = new AtomicInteger();
        AtomicInteger ledgerWrites = new AtomicInteger();
        PatientIndexWriter countingWriter = (patientRef, localMrn, sourceBatch) ->
                hisWrites.incrementAndGet();

        IdentityHardDecision d = rules.evaluate(IDCARD_A, IDCARD_B);

        // —— 结论：拒收 ——
        assertEquals(IdentityHardDecision.State.CONFLICT, d.state());
        assertTrue(d.rejected());
        assertFalse(d.candidateEligible(), "冲突态不出候选：候选一旦摆到确认台上就等于请人点确认");
        assertFalse(d.mergeAllowed());
        assertFalse(d.allowsHisWrite());
        assertFalse(d.redeliverable(), "AR 不入重投队列");

        // —— 出站解析得了：拒收原因 + HL7 标准错误码 ——
        assertEquals(RejectionReason.IDENTITY_CONFLICT, d.rejection());
        assertNotNull(d.errorCodeOrNull());
        assertEquals(Hl7ErrorCodes.forRejection(RejectionReason.IDENTITY_CONFLICT).code(),
                d.errorCodeOrNull().code());
        assertEquals("100", d.errorCodeOrNull().code(), "身份冲突绑定 100（已接受但不处理）");
        assertFalse(d.errorCodeOrNull().custom(), "必须是对端可直接解析的标准码");
        assertEquals(RejectionReason.IDENTITY_CONFLICT.suggestedCode(), d.ledgerReason());

        // —— 主路径按结论分流：拒收即中止，两侧计数都必须仍是 0 ——
        dispatch(d, ledgerWrites, countingWriter, "TESTPAT-0001", "LOCMRN-0001", "BATCH-T29");
        assertEquals(0, hisWrites.get(), "冲突态不得触发院内患者主索引写入");
        assertEquals(0, ledgerWrites.get(), "冲突态不得绑定台账双主键");
    }

    @Test
    @DisplayName("缺失：只出候选不合档，回执走 AE/待确认而不是拒收")
    void test_missing_idcard_yields_candidates_only() throws Exception {
        AtomicInteger hisWrites = new AtomicInteger();
        AtomicInteger ledgerWrites = new AtomicInteger();
        PatientIndexWriter countingWriter = (patientRef, localMrn, sourceBatch) ->
                hisWrites.incrementAndGet();

        IdentityHardDecision d = rules.evaluate("", IDCARD_A);

        assertEquals(IdentityHardDecision.State.MISSING, d.state());
        assertEquals(IdentityAnchorLevel.MISSING, d.anchor());
        assertFalse(d.rejected(), "平台没给证件不是冲突：判成拒收会让对端停止重投、院内不建档");
        assertTrue(d.candidateEligible(), "缺失态仍然出候选 —— 人靠姓名与出生日期看一眼");
        assertFalse(d.mergeAllowed(), "无硬锚点一律不合档");
        assertNull(d.rejection());
        assertTrue(d.requiresHumanConfirmation());

        dispatch(d, ledgerWrites, countingWriter, "TESTPAT-0001", "LOCMRN-0001", "BATCH-T29");
        assertEquals(0, hisWrites.get(), "缺失态不得自动合档写入院内");
        assertEquals(1, ledgerWrites.get(), "缺失态允许落区域主键（记录来路），只是不给院内主键");
    }

    // ================================================================ 归一边界

    @Test
    @DisplayName("边界：大小写与前后空格归一后一致，判为一致")
    void test_case_and_spaces_normalize_to_same_idcard() {
        IdentityHardDecision lowerCase = rules.evaluate(IDCARD_A.toLowerCase(java.util.Locale.ROOT), IDCARD_A);
        IdentityHardDecision padded = rules.evaluate("  " + IDCARD_A + "\t", IDCARD_A);
        IdentityHardDecision fullWidthSpace = rules.evaluate("　" + IDCARD_A + "　", IDCARD_A);

        assertEquals(IdentityHardDecision.State.MATCH, lowerCase.state(),
                "小写 x 是书写差异，不是身份差异");
        assertEquals(IdentityHardDecision.State.MATCH, padded.state(),
                "前后空白（含制表）不改变证件号");
        assertEquals(IdentityHardDecision.State.MATCH, fullWidthSpace.state(),
                "全角空格同样按空白处理");
    }

    @Test
    @DisplayName("边界：15 位与 18 位按 GB 11643 升位后判为同一人，依据写明升位规则")
    void test_15_digit_upgrades_to_18_digit_same_person() {
        IdentityHardDecision d = rules.evaluate(IDCARD_A15, IDCARD_A);

        assertEquals(IdentityHardDecision.State.MATCH, d.state());
        assertTrue(d.basis().contains("升位"), "判定依据必须写明做了升位：" + d.basis());
        assertTrue(d.basis().contains("GB 11643"));

        IdCardNumber upgraded = IdCardNumber.parse(IDCARD_A15);
        assertEquals(IdCardNumber.Format.UPGRADED_FROM_15, upgraded.format());
        assertEquals(IdCardNumber.parse(IDCARD_A).fullDigest(), upgraded.fullDigest(),
                "升位后必须与 18 位号逐字相同");
    }

    @Test
    @DisplayName("边界：校验位不过 = 不可用（不作锚点），而不是冲突")
    void test_bad_check_digit_is_unusable_not_conflict() {
        // 把第 18 位改错：前 17 位不变，末位算不出来。
        String badCheck = IDCARD_A.substring(0, 17) + "1";
        IdentityHardDecision d = rules.evaluate(badCheck, IDCARD_A);

        assertEquals(IdentityHardDecision.State.MISSING, d.state(),
                "校验位不过推不出「不是同一个人」，判冲突会拒收一位大概率只是录错的居民");
        assertFalse(d.mergeAllowed());
        assertTrue(d.basis().contains("校验位"), d.basis());
        assertFalse(rules.evaluate(badCheck, IDCARD_A).rejected());
    }

    @Test
    @DisplayName("边界：长度非法与含非法字符同样不作锚点，依据可读")
    void test_malformed_lengths_are_unusable() {
        IdentityHardDecision tooShort = rules.evaluate("11010519491231", IDCARD_A);
        IdentityHardDecision oddLength = rules.evaluate("11010519491231002", IDCARD_A);
        IdentityHardDecision illegalChar = rules.evaluate("11010519491231A02X", IDCARD_A);
        IdentityHardDecision badCheckChar = rules.evaluate("11010519491231002Z", IDCARD_A);

        assertEquals(IdentityHardDecision.State.MISSING, tooShort.state());
        assertEquals(IdentityHardDecision.State.MISSING, oddLength.state());
        assertEquals(IdentityHardDecision.State.MISSING, illegalChar.state());
        assertEquals(IdentityHardDecision.State.MISSING, badCheckChar.state());
        assertTrue(tooShort.basis().contains("长度"), tooShort.basis());
        assertTrue(oddLength.basis().contains("长度"), oddLength.basis());
        assertTrue(illegalChar.basis().contains("非数字字符"), illegalChar.basis());
        assertTrue(badCheckChar.basis().contains("校验位"), badCheckChar.basis());
        assertFalse(illegalChar.rejected(), "格式不可用不等于冲突");
    }

    @Test
    @DisplayName("空缺一侧不算冲突：平台没给证件与院内记录无证件，都只出候选")
    void test_absent_on_either_side_is_missing_not_conflict() {
        assertEquals(IdentityHardDecision.State.MISSING, rules.evaluate(null, IDCARD_A).state());
        assertEquals(IdentityHardDecision.State.MISSING, rules.evaluate(IDCARD_A, null).state());
        assertEquals(IdentityHardDecision.State.MISSING, rules.evaluate(null, null).state());
        assertFalse(rules.evaluate(null, IDCARD_A).rejected());
        assertFalse(rules.evaluate(IDCARD_A, null).rejected());
    }

    // ================================================================ 切片与短路

    @Test
    @DisplayName("切片：任一条冲突即整条拒收，且冲突落在具体院内主键上")
    void test_conflict_short_circuits_and_names_the_local_record() {
        List<IdentityHardRules.LocalSubject> locals = List.of(
                new IdentityHardRules.LocalSubject("LOCMRN-0001", IDCARD_B),
                new IdentityHardRules.LocalSubject("LOCMRN-0002", IDCARD_A));

        List<IdentityHardRules.SubjectVerdict> verdicts = rules.evaluateAcross(IDCARD_A, locals);

        assertEquals(1, verdicts.size(), "冲突后短路：后面那条判了也只会白判");
        IdentityHardRules.SubjectVerdict v = verdicts.get(0);
        assertTrue(v.conflict());
        assertEquals("LOCMRN-0001", v.subject().localMrn(), "信息员要知道跟哪条院内记录冲突");
        assertFalse(v.decision().mergeAllowed());
    }

    @Test
    @DisplayName("切片：都不冲突时逐条给结论（一条硬锚点一致、一条院内没录证件）")
    void test_no_conflict_returns_every_subject() {
        // 第 2 条模拟院内老档案没录证件号 —— 这是基层的真实常态，不是边界造出来的形态。
        List<IdentityHardRules.LocalSubject> locals = List.of(
                new IdentityHardRules.LocalSubject("LOCMRN-0001", IDCARD_A),
                new IdentityHardRules.LocalSubject("LOCMRN-0002", ""));

        List<IdentityHardRules.SubjectVerdict> verdicts = rules.evaluateAcross(IDCARD_A15, locals);

        assertEquals(2, verdicts.size(), "不冲突就不短路：每条都得给结论，缺依据的也要说清缺什么");
        assertFalse(verdicts.get(0).conflict());
        assertTrue(verdicts.get(0).decision().mergeAllowed(), "15 位升位后与第 1 条一致");
        assertFalse(verdicts.get(1).conflict());
        assertEquals(IdentityHardDecision.State.MISSING, verdicts.get(1).decision().state());
        assertTrue(verdicts.get(1).decision().candidateEligible(),
                "院内没录证件不等于不是这个人：仍出候选，只是要人看一眼");
        assertFalse(verdicts.get(1).decision().mergeAllowed());
        assertTrue(verdicts.get(1).decision().basis().contains("院内记录无可用证件号"),
                verdicts.get(1).decision().basis());
    }

    // ================================================================ 双主键分离存储

    @Test
    @DisplayName("双主键：区域主键与院内主键分列写入，互不冒名")
    void test_two_primary_keys_stored_separately() throws Exception {
        long ledgerId = freshLedgerRow();
        JdbcPatientKeyStore store = new JdbcPatientKeyStore(IdentityHardRulesTest::openConnection);

        // 先只绑区域主键（报文刚入账的形态）
        store.bind(ledgerId, IdentityKeyBinding.inboundOnly("TESTPAT-0001"));
        assertEquals("TESTPAT-0001", readColumn(ledgerId, "patient_ref"));
        assertEquals("", readColumn(ledgerId, "local_mrn"), "入账时院内主键必须为空：还没人确认过");

        // 人工确认后补上院内主键
        store.bind(ledgerId, IdentityKeyBinding.bound("TESTPAT-0001", "LOCMRN-0001"));
        assertEquals("TESTPAT-0001", readColumn(ledgerId, "patient_ref"), "区域主键仍在自己那一列");
        assertEquals("LOCMRN-0001", readColumn(ledgerId, "local_mrn"), "院内主键写在自己那一列");
        assertNotEquals(readColumn(ledgerId, "patient_ref"), readColumn(ledgerId, "local_mrn"));

        IdentityKeyBinding read = store.read(ledgerId).orElseThrow();
        assertEquals("TESTPAT-0001", read.patientRef());
        assertEquals("LOCMRN-0001", read.localMrn());
        assertTrue(read.boundToHis());
        assertFalse(read.crossKey());
    }

    @Test
    @DisplayName("双主键：两侧字面相同也算两个来源各写一次，read 标 crossKey")
    void test_cross_key_binding_is_flagged() throws Exception {
        long ledgerId = freshLedgerRow();
        JdbcPatientKeyStore store = new JdbcPatientKeyStore(IdentityHardRulesTest::openConnection);
        store.bind(ledgerId, IdentityKeyBinding.bound("MRN-2026-0001", "MRN-2026-0001"));

        assertTrue(store.read(ledgerId).orElseThrow().crossKey(),
                "平台原样回带基层住院号的情形要能在确认台上被看出来");
        assertEquals("MRN-2026-0001", readColumn(ledgerId, "patient_ref"));
        assertEquals("MRN-2026-0001", readColumn(ledgerId, "local_mrn"));
    }

    @Test
    @DisplayName("双主键：未绑定的行 read 返回 empty，而不是两列皆空的绑定")
    void test_unbound_row_reads_as_empty() throws Exception {
        long ledgerId = freshLedgerRow();
        JdbcPatientKeyStore store = new JdbcPatientKeyStore(IdentityHardRulesTest::openConnection);
        assertTrue(store.read(ledgerId).isEmpty());
        assertTrue(store.read(ledgerId + 999_999).isEmpty(), "行不存在时同样是 empty，不抛异常");
    }

    @Test
    @DisplayName("双主键：拒绝静默覆盖已有值（区域主键或院内主键）")
    void test_binding_refuses_silent_overwrite() throws Exception {
        long ledgerId = freshLedgerRow();
        JdbcPatientKeyStore store = new JdbcPatientKeyStore(IdentityHardRulesTest::openConnection);
        store.bind(ledgerId, IdentityKeyBinding.bound("TESTPAT-0001", "LOCMRN-0001"));

        KeyBindingConflictException e1 = assertThrows(KeyBindingConflictException.class,
                () -> store.bind(ledgerId, IdentityKeyBinding.bound("TESTPAT-9999", "LOCMRN-0001")));
        assertTrue(e1.reason().contains("区域主键"), e1.reason());

        KeyBindingConflictException e2 = assertThrows(KeyBindingConflictException.class,
                () -> store.bind(ledgerId, IdentityKeyBinding.bound("TESTPAT-0001", "LOCMRN-9999")));
        assertTrue(e2.reason().contains("院内主键"), e2.reason());
        assertEquals(ledgerId, e2.ledgerId());

        // 拒绝之后一列都没被改
        assertEquals("TESTPAT-0001", readColumn(ledgerId, "patient_ref"));
        assertEquals("LOCMRN-0001", readColumn(ledgerId, "local_mrn"));
    }

    @Test
    @DisplayName("双主键：已落地 / 已拒收的行不允许再改主键")
    void test_binding_refused_on_terminal_states() throws Exception {
        JdbcLedgerStore store = new JdbcLedgerStore(IdentityHardRulesTest::openConnection);

        // 已拒收：已接收 → 拒收（拒收发生在入站阶段，合法边）
        long rejectedRow = freshLedgerRow();
        store.transition(rejectedRow, MessageState.RECEIVED, MessageState.REJECTED,
                "system", "identity-conflict");

        // 已落地：已接收 → 待确认 → 已落地（人工确认后的终局）
        long landedRow = freshLedgerRow();
        store.transition(landedRow, MessageState.RECEIVED, MessageState.PENDING_CONFIRM,
                "system", "received-awaiting-identity-confirm");
        store.transition(landedRow, MessageState.PENDING_CONFIRM, MessageState.LANDED,
                "operator-029", "confirmed-landed");

        JdbcPatientKeyStore keys = new JdbcPatientKeyStore(IdentityHardRulesTest::openConnection);
        for (Map.Entry<Long, MessageState> row : Map.of(
                rejectedRow, MessageState.REJECTED,
                landedRow, MessageState.LANDED).entrySet()) {
            KeyBindingConflictException e = assertThrows(KeyBindingConflictException.class,
                    () -> keys.bind(row.getKey(), IdentityKeyBinding.bound("TESTPAT-0001", "LOCMRN-0001")));
            assertTrue(e.reason().contains(row.getValue().dbValue()), e.reason());
            assertEquals("", readColumn(row.getKey(), "patient_ref"), "拒写之后一列都不该动");
            assertEquals("", readColumn(row.getKey(), "local_mrn"), "拒写之后一列都不该动");
        }
    }

    @Test
    @DisplayName("双主键：不存在的行给出明确拒绝，而不是静默成功")
    void test_binding_on_missing_row_is_rejected() {
        JdbcPatientKeyStore store = new JdbcPatientKeyStore(IdentityHardRulesTest::openConnection);
        KeyBindingConflictException e = assertThrows(KeyBindingConflictException.class,
                () -> store.bind(999_999_999L, IdentityKeyBinding.inboundOnly("TESTPAT-0001")));
        assertTrue(e.reason().contains("无此报文行"), e.reason());
    }

    @Test
    @DisplayName("双主键：patientRef 为空串在构造期就被拒")
    void test_binding_requires_non_blank_patient_ref() {
        assertThrows(IllegalArgumentException.class, () -> IdentityKeyBinding.inboundOnly(""));
        assertThrows(IllegalArgumentException.class, () -> IdentityKeyBinding.bound("   ", "LOCMRN-0001"));
        assertThrows(NullPointerException.class, () -> IdentityKeyBinding.bound(null, "LOCMRN-0001"));
    }

    // ================================================================ 结构不变量

    @Test
    @DisplayName("结构：硬规则类不持有任何模型网关（先硬后模不是注释）")
    void test_hard_rules_hold_no_model_gateway() {
        List<String> offenders = new ArrayList<>();
        for (Field f : IdentityHardRules.class.getDeclaredFields()) {
            String type = f.getType().getName();
            if (type.startsWith("cn.hospital.region.archive.model.")
                    && (type.contains("Gateway") || type.contains("Model"))) {
                offenders.add(f.getName() + ":" + type);
            }
        }
        assertTrue(offenders.isEmpty(),
                "硬校验不得持有模型网关（否则否决权会被交给概率性的东西）：" + offenders);
    }

    @Test
    @DisplayName("结构：结论自相矛盾时在构造期就炸")
    void test_decision_rejects_inconsistent_states() {
        // 冲突态却允许合档
        assertThrows(IllegalArgumentException.class,
                () -> new IdentityHardDecision(IdentityHardDecision.State.CONFLICT,
                        IdentityAnchorLevel.MISSING, RejectionReason.IDENTITY_CONFLICT,
                        Hl7ErrorCodes.forRejection(RejectionReason.IDENTITY_CONFLICT),
                        "人为造的自相矛盾结论", true, true));
        // 冲突态却挂了别的拒收原因
        assertThrows(IllegalArgumentException.class,
                () -> new IdentityHardDecision(IdentityHardDecision.State.CONFLICT,
                        IdentityAnchorLevel.MISSING, RejectionReason.VERSION_INCOMPATIBLE,
                        Hl7ErrorCodes.forRejection(RejectionReason.VERSION_INCOMPATIBLE),
                        "人为造的自相矛盾结论", false, false));
        // 一致态却不允许合档
        assertThrows(IllegalArgumentException.class,
                () -> new IdentityHardDecision(IdentityHardDecision.State.MATCH,
                        IdentityAnchorLevel.IDCARD, null, null, "人为造的自相矛盾结论", true, false));
        // 缺失态却标记了 IDCARD 锚点
        assertThrows(IllegalArgumentException.class,
                () -> new IdentityHardDecision(IdentityHardDecision.State.MISSING,
                        IdentityAnchorLevel.IDCARD, null, null, "人为造的自相矛盾结论", true, false));
    }

    @Test
    @DisplayName("安全：依据与日志口径里不出现证件明文")
    void test_basis_and_to_string_carry_no_plaintext() {
        IdentityHardDecision conflict = rules.evaluate(IDCARD_A, IDCARD_B);
        assertFalse(conflict.basis().contains(IDCARD_A), "依据里不得出现报文侧明文");
        assertFalse(conflict.basis().contains(IDCARD_B), "依据里不得出现院内侧明文");
        assertFalse(conflict.zhOneLine().contains(IDCARD_A));

        IdCardNumber n = IdCardNumber.parse(IDCARD_A);
        assertFalse(n.toString().contains(IDCARD_A), "toString 只出摘要与格式");
        assertTrue(n.toString().contains(n.digest8()));
        assertEquals(8, n.digest8().length());

        // 摘要长度：落库 8 位可比对，进程内比较用 64 位全量摘要（32 bit 会撞）
        assertEquals(64, n.fullDigest().length());
        assertTrue(n.fullDigest().startsWith(n.digest8()));
    }

    @Test
    @DisplayName("存储：V3 能把已在 V2 的库升上来，存量行得到空 patient_ref 且无需回填")
    void test_v3_upgrades_an_existing_v2_database() throws Exception {
        String url = "jdbc:h2:mem:identity_v3_upgrade_test;MODE=PostgreSQL;"
                + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        try (Connection conn = DriverManager.getConnection(url, "sa", "")) {
            // 先只跑 V1 + V2：这就是一台跑了两个版本的接入机上的库的样子
            runScriptFile(conn, "/db/migration/V1__schema.sql");
            runScriptFile(conn, "/db/migration/V2__model_audit.sql");
            assertFalse(hasColumn(conn, "patient_ref"), "V2 时代的表里没有双主键列");

            // 存量行：迁移前就已经在台账里（报文原文里带着 PID-3）
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO message_ledger (message_type, control_id, processing_id, batch_id, "
                            + "state, raw_payload) VALUES ('ADT^A31','CTRL-LEGACY-1','P','BATCH-LEGACY',"
                            + "'PENDING_CONFIRM','MSH|^~\\&|legacy')")) {
                ps.executeUpdate();
            }

            // 再跑 V3：只加列 + 加索引，不回填、不重写存量行
            runScriptFile(conn, "/db/migration/V3__identity_binding.sql");
            assertTrue(hasColumn(conn, "patient_ref"), "V3 之后两套主键各有各的列");

            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT patient_ref, local_mrn FROM message_ledger WHERE control_id = 'CTRL-LEGACY-1'");
                 ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("", rs.getString(1), "存量行的区域主键留空，回查仍走报文原文");
                assertEquals("", rs.getString(2), "存量行没有院内主键：没确认过的档案不该凭空有号");
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT version FROM schema_version ORDER BY applied_at DESC LIMIT 1");
                 ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("V3", rs.getString(1), "迁移脚本自登记版本");
            }
        }
    }

    // ================================================================ 夹具

    @BeforeAll
    static void migrateOnce() throws SQLException {
        try (Connection conn = openConnection()) {
            LedgerSchema.migrate(conn);
        }
    }

    /** 插入一条真实台账行（走生产用的 JdbcLedgerStore，不裸 SQL 插）。 */
    private static long freshLedgerRow() throws SQLException {
        int n = ++seq;
        JdbcLedgerStore store = new JdbcLedgerStore(IdentityHardRulesTest::openConnection);
        LedgerEntry entry = LedgerEntry.inbound(
                IdempotencyKey.of("ADT^A31", "CTRL-T29-" + n, "P"),
                "BATCH-T29-" + n,
                "MSH|^~\\&|CHSCOMMUNITY|REGIONAL_ARCHIVE|REGIONAL_ARCHIVE|FACILITY_A|20261020||ADT^A31|CTRL-T29-"
                        + n + "|P|2.5.1|||AL|NE||UNICODE UTF-8");
        assertNotNull(store.insertIfAbsent(entry));
        return store.findByIdempotencyKey(entry.key()).orElseThrow().id();
    }

    private static Connection openConnection() throws SQLException {
        return DriverManager.getConnection(H2_URL, "sa", "");
    }

    /** 直接查列：证明两列各自的值，而不是相信某个 store 返回的对象。 */
    private static String readColumn(long ledgerId, String column) throws SQLException {
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT " + column + " FROM message_ledger WHERE id = ?")) {
            ps.setLong(1, ledgerId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "台账行不存在: " + ledgerId);
                return nullToEmpty(rs.getString(1));
            }
        }
    }

    private static String nullToEmpty(String v) {
        return v == null ? "" : v;
    }

    /**
     * 按迁移脚本的切分口径（丢 {@code --} 行注释、按 {@code ;} 分段）跑一份 SQL。
     *
     * <p>与 {@code LedgerSchema.splitSqlSegments} 同一口径：只有 DDL、没有字符串字面量，
     * 手写切分足够；这里不直接调它是因为它是包级私有，而本用例刻意站在
     * {@code identity} 包外来验证「迁移脚本本身能不能把老库升上来」。
     */
    private static void runScriptFile(Connection conn, String resourcePath) throws Exception {
        String sql;
        try (var in = IdentityHardRulesTest.class.getResourceAsStream(resourcePath)) {
            assertNotNull(in, "找不到迁移脚本 " + resourcePath);
            sql = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        StringBuilder current = new StringBuilder();
        boolean inComment = false;
        try (var st = conn.createStatement()) {
            for (int i = 0; i < sql.length(); i++) {
                char c = sql.charAt(i);
                if (!inComment && c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                    inComment = true;
                    i++;
                    continue;
                }
                if (inComment) {
                    if (c == '\n') {
                        inComment = false;
                    }
                    continue;
                }
                if (c == ';') {
                    if (!current.toString().isBlank()) {
                        st.execute(current.toString());
                    }
                    current.setLength(0);
                } else {
                    current.append(c);
                }
            }
            if (!current.toString().isBlank()) {
                st.execute(current.toString());
            }
        }
    }

    /** 列是否存在：用一句只取列名的 SELECT 试，列不存在时两库都抛 SQLException。 */
    private static boolean hasColumn(Connection conn, String column) {
        try (var st = conn.createStatement();
             var rs = st.executeQuery("SELECT " + column + " FROM message_ledger WHERE 1 = 0")) {
            return rs != null;
        } catch (SQLException e) {
            return false;
        }
    }

    /**
     * 主路径的分流逻辑镜像 —— 与 {@code ArchiveIngestService.ingest/land} 的守卫同形：
     * 拒收即中止；无硬锚点只落区域主键（记录来路），不合档、不写院内。
     */
    private static void dispatch(IdentityHardDecision decision, AtomicInteger ledgerWrites,
                                 PatientIndexWriter writer, String patientRef, String localMrn,
                                 String batchId) throws Exception {
        if (decision.rejected()) {
            return;
        }
        ledgerWrites.incrementAndGet();
        if (decision.allowsHisWrite()) {
            writer.put(patientRef, localMrn, batchId);
        }
    }
}