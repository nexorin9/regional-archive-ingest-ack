package cn.hospital.region.archive.ack;

import cn.hospital.region.archive.hl7.FieldLocator;
import cn.hospital.region.archive.hl7.Hl7Message;
import cn.hospital.region.archive.hl7.Hl7Parser;
import cn.hospital.region.archive.hl7.LocatableError;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MSA 三值 + ERR 段组装的应用级断言。
 *
 * <p>与传输级 0x06 断言（帧层契约测试）严格分开：这里断言的是「每条报文一个可以和台账逐条对账的
 * 业务结果」，不是「字节收到了」。
 */
class AckComposerTest {

    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-09-18T10:15:00Z"), ZoneOffset.UTC);

    private static AckComposer composer() {
        return new AckComposer("CHSCOMMUNITY", "REGIONAL_ARCHIVE", FIXED);
    }

    /** 常规入站档案变更包。 */
    private static final String INBOUND =
            "MSH|^~\\&|REGIONAL_ARCHIVE|FACILITY_A|CHSCOMMUNITY|FACILITY_B|20260918101500||ADT^A08|"
                    + "FAC-CTRL-0001|P|2.5.1|||AL|NE||UNICODE UTF-8\r"
                    + "PID|||RGN^PAT-998877^^^REGIONAL_ARCHIVE||张^伟||19850512|M\r";

    /** 缺 MSH-10（控制号）的报文：回执 MSA-2 只能为空，ERR 必须指出是 MSH 第 10 个字段。 */
    private static final String INBOUND_NO_CONTROL_ID =
            "MSH|^~\\&|REGIONAL_ARCHIVE|FACILITY_A|CHSCOMMUNITY|FACILITY_B|20260918101500||ADT^A08|"
                    + "|P|2.5.1|||AL|NE||UNICODE UTF-8\r"
                    + "PID|||RGN^PAT-998877^^^REGIONAL_ARCHIVE||张^伟||19850512|M\r";

    private static InboundHeader header(String er7) {
        return InboundHeader.parse(er7);
    }

    private static Hl7Message parse(String er7) {
        return Hl7Parser.parse(er7);
    }

    @Test
    @DisplayName("MSA-2 逐字复制入站 MSH-10：改一个字符对端就认不出这条回执")
    void test_ack_msa2_equals_inbound_msh10() {
        AckComposer composer = composer();
        for (String er7 : List.of(
                INBOUND,
                INBOUND.replace("FAC-CTRL-0001", "  CTRL-0007  "),
                INBOUND.replace("FAC-CTRL-0001", "CTRL000000000042"))) {
            Hl7Message inbound = parse(er7);
            String inboundControlId = inbound.field("MSH", 10);
            Hl7Message ack = parse(composer.composeAccepted(header(er7)));

            assertEquals(AckComposer.CODE_AA, ack.field("MSA", 1));
            // 逐字：不 trim、不去前导零、不补位
            assertEquals(inboundControlId, ack.field("MSA", 2),
                    "MSA-2 必须逐字等于入站 MSH-10（含空白与前导零）");
        }
    }

    @Test
    @DisplayName("MSA-1=AA 不产出 ERR 段")
    void test_aa_produces_no_err_segment() {
        String ack = composer().composeAccepted(header(INBOUND));
        Hl7Message msg = parse(ack);

        assertFalse(msg.has("ERR"), "成功回执不得带 ERR 段");
        assertEquals(2, msg.segments().size(), "AA 回执只应有 MSH + MSA 两段");
    }

    @Test
    @DisplayName("AA 回执带 ERR 是自相矛盾，直接拒")
    void test_aa_with_err_segment_is_rejected() {
        AckComposer composer = composer();
        ErrSegment err = ErrSegment.of(RejectionReason.MODEL_DEGRADED, null);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> composer.compose(header(INBOUND), AckComposer.CODE_AA, "不该出现", err));
        assertTrue(e.getMessage().contains("ERR"));
    }

    @Test
    @DisplayName("AE/AR 不带 ERR 一律拒拼：没有 ERR 的拒收对端既不知错在哪也不知该不该重投")
    void test_reject_without_err_segment_is_rejected() {
        AckComposer composer = composer();
        for (String code : List.of(AckComposer.CODE_AE, AckComposer.CODE_AR)) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> composer.compose(header(INBOUND), code, "出错原因", null));
            assertTrue(e.getMessage().contains("ERR"), "异常须说明缺 ERR");
            assertTrue(e.getMessage().contains("composeRejected"), "异常须指路到正确入口");
        }
    }

    @Test
    @DisplayName("八类拒收原因各自产出正确的 MSA-1（含 T27 新增的报文截断）")
    void test_each_rejection_reason_maps_to_expected_msa1() {
        Map<RejectionReason, String> expected = new LinkedHashMap<>();
        expected.put(RejectionReason.MISSING_REQUIRED_SEGMENT, AckComposer.CODE_AE);
        expected.put(RejectionReason.MISSING_REQUIRED_FIELD, AckComposer.CODE_AE);
        expected.put(RejectionReason.UNSUPPORTED_CHARSET, AckComposer.CODE_AR);
        expected.put(RejectionReason.IDENTITY_CONFLICT, AckComposer.CODE_AR);
        expected.put(RejectionReason.SEMANTIC_ALIGN_FAILED, AckComposer.CODE_AE);
        expected.put(RejectionReason.MODEL_DEGRADED, AckComposer.CODE_AE);
        expected.put(RejectionReason.VERSION_INCOMPATIBLE, AckComposer.CODE_AR);
        // T27 新增：帧中途断流。归 AE——半截传输几乎总是网络抖动，重投一次通常就完整了；
        // 归 AR 会让区域平台把这条扔进死信堆，本端这边一个字也没留。
        expected.put(RejectionReason.MESSAGE_TRUNCATED, AckComposer.CODE_AE);

        assertEquals(8, RejectionReason.values().length, "拒收原因应恰为八类");
        assertEquals(expected.size(), RejectionReason.values().length,
                "断言必须覆盖全部八类原因，不能只挑几条跑");

        AckComposer composer = composer();
        for (Map.Entry<RejectionReason, String> e : expected.entrySet()) {
            Hl7Message ack = parse(composer.composeRejected(header(INBOUND), e.getKey()));
            assertEquals(e.getValue(), ack.field("MSA", 1),
                    e.getKey() + " 应回 " + e.getValue());
            assertTrue(ack.has("ERR"), e.getKey() + " 必须带 ERR 段");
            assertEquals("FAC-CTRL-0001", ack.field("MSA", 2),
                    "MSA-2 仍逐字复制入站控制号");
        }
    }

    @Test
    @DisplayName("身份硬锚点冲突 → AR 且不可重投")
    void test_identity_conflict_maps_to_AR_redeliverable_false() {
        RejectionReason reason = RejectionReason.IDENTITY_CONFLICT;
        assertEquals(AckComposer.CODE_AR, reason.msa1());
        assertFalse(reason.redeliverable(), "身份证冲突是确定性事实，重投一万次还是同一条");
        assertTrue(reason.permanentReject());
        assertFalse(reason.suggestedCode().isBlank());
        assertFalse(reason.zhLabel().isBlank());

        // 走整条拼装路径：MSA-1 仍须是 AR，且带 ERR（无字段位置时 ERR 不给段字段位）
        Hl7Message ack = parse(composer().composeRejected(header(INBOUND), reason));
        assertEquals(AckComposer.CODE_AR, ack.field("MSA", 1));
        assertTrue(ack.has("ERR"));
        assertEquals("", ErrSegment.of(reason, null).errLocationReference(),
                "身份冲突错在两套主键之间，不产出段字段位置");
    }

    @Test
    @DisplayName("AE 可重投、AR 不重投，两类标记逐条相反")
    void test_ae_and_ar_redeliverable_flags_are_opposite() {
        boolean sawAe = false;
        boolean sawAr = false;
        for (RejectionReason r : RejectionReason.values()) {
            if (AckComposer.CODE_AE.equals(r.msa1())) {
                sawAe = true;
                assertTrue(r.redeliverable(), r.name() + " 是 AE，必须允许重投");
                assertFalse(r.permanentReject());
            } else if (AckComposer.CODE_AR.equals(r.msa1())) {
                sawAr = true;
                assertFalse(r.redeliverable(), r.name() + " 是 AR，必须排除出重投队列");
                assertTrue(r.permanentReject());
            } else {
                throw new AssertionError("拒收原因只允许 AE / AR，发现 " + r.msa1());
            }
        }
        assertTrue(sawAe && sawAr, "两类都必须有实例，否则不可重投这条规则没被验证");
    }

    @Test
    @DisplayName("ERR 段指向出错段与字段（接 FieldLocator 定位结果）")
    void test_err_segment_points_to_error_segment_and_field() {
        Hl7Message inbound = parse(INBOUND_NO_CONTROL_ID);
        LocatableError err = FieldLocator.firstBlocking(inbound).orElseThrow();

        Hl7Message ack = parse(composer().composeRejected(
                header(INBOUND_NO_CONTROL_ID), RejectionReason.MISSING_REQUIRED_FIELD, err));

        assertEquals(AckComposer.CODE_AE, ack.field("MSA", 1));
        assertTrue(ack.has("ERR"));

        // ERR-1：码^段名^字段序号 —— 区域平台据此定位到 MSH 第 10 个字段
        assertEquals(err.suggestedCode() + "^MSH^10", ack.field("ERR", 1));
        // ERR-3：HL7 错误码位，码与 ERR-1 一致
        assertEquals(err.suggestedCode(), ack.component("ERR", 3, 1));
        // ERR-4：唯一文案出口的中文说明 + HL7 码引用位。
        // 用 fields().get(3) 而非 field("ERR",4)：field() 按 HL7 语义只返回「第一个重复」，
        // 而原值片段里本就含 ~（来自 MSH 头 ^~\&），用它断言会被正常截断误判为丢字符。
        String description = ack.segment("ERR").fields().get(3);
        assertEquals(FieldLocator.describe(err), description.replace("（" + ErrSegment.HL7_CODE_REFERENCE_PREFIX
                + err.suggestedCode() + "）", ""));
        assertTrue(description.contains(ErrSegment.HL7_CODE_REFERENCE_PREFIX + err.suggestedCode()));
    }

    @Test
    @DisplayName("ERR-4 里的原值片段含字段分隔符，必须转义后拼装，否则回执被切成两段")
    void test_err_description_escapes_field_separator() {
        Hl7Message inbound = parse(INBOUND_NO_CONTROL_ID);
        LocatableError err = FieldLocator.firstBlocking(inbound).orElseThrow();
        // MSH 段片段取自段原文，必然含 '|'；若不转义，ERR-4 之后所有字段都会错位
        assertTrue(err.rawSnippet().contains("|"), "本用例的前提是片段确实含字段分隔符");

        String ackEr7 = composer().composeRejected(
                header(INBOUND_NO_CONTROL_ID), RejectionReason.MISSING_REQUIRED_FIELD, err);
        assertTrue(ackEr7.contains("\\F\\"), "ERR 文本中的字段分隔符应写成 \\F\\");

        Hl7Message ack = parse(ackEr7);
        // 解析（转义已还原）后 ERR-4 逐字等于原始说明，说明转义/还原成对、没有丢字符
        assertEquals(FieldLocator.describe(err) + "（" + ErrSegment.HL7_CODE_REFERENCE_PREFIX
                        + err.suggestedCode() + "）",
                ack.segment("ERR").fields().get(3));
        assertEquals(3, ack.segments().size(),
                "ERR 段不得把 ACK 拆出多余段：MSH/MSA/ERR 共 3 段");
    }

    @Test
    @DisplayName("回执沿用入站报文自述的分隔符，对端非默认分隔符时也能解析")
    void test_ack_reuses_inbound_delimiters() {
        // 字段分隔符 '!'，编码字符 "$#@%"（组件 $ / 重复 # / 转义 @ / 子组件 %）
        String er7 = "MSH!$#@%!REGIONAL_ARCHIVE!FACILITY_A!CHSCOMMUNITY!FACILITY_B!20260918101500!"
                + "!ADT^A08!FAC-CTRL-BANG-01!P!2.5.1!!!AL!NE!!UNICODE UTF-8\r"
                + "PID!!!RGN^PAT-1^^^REGIONAL_ARCHIVE!!张^伟!!19850512\r";

        Hl7Message ack = parse(composer().composeAccepted(header(er7)));
        assertEquals(AckComposer.CODE_AA, ack.field("MSA", 1));
        assertEquals("FAC-CTRL-BANG-01", ack.field("MSA", 2));
        assertTrue(ack.has("MSH"));
    }

    @Test
    @DisplayName("MSH-11 / MSH-12 / MSH-18 原样带回入站")
    void test_ack_msh11_msh12_msh18_copied_from_inbound() {
        Hl7Message inbound = parse(INBOUND);
        Hl7Message ack = parse(composer().composeAccepted(header(INBOUND)));

        assertEquals(inbound.field("MSH", 11), ack.field("MSH", 11));
        assertEquals(inbound.field("MSH", 12), ack.field("MSH", 12));
        assertEquals(inbound.field("MSH", 18), ack.field("MSH", 18));
        assertEquals("ACK", ack.component("MSH", 9, 1));
        assertNotNull(ack.field("MSH", 10));
        assertFalse(ack.field("MSH", 10).isBlank(), "回执自身控制号不得为空");
    }

    @Test
    @DisplayName("每条拒收回执的回执控制号自增，不与入站控制号重复")
    void test_ack_control_id_increments_per_message() {
        AckComposer composer = composer();
        String first = parse(composer.composeAccepted(header(INBOUND))).field("MSH", 10);
        String second = parse(composer.composeRejected(
                header(INBOUND), RejectionReason.MODEL_DEGRADED)).field("MSH", 10);

        assertFalse(first.equals(second), "回执控制号必须逐条自增");
        assertTrue(first.startsWith("ACK") && second.startsWith("ACK"));
        assertEquals(2, composer.issuedCount());
    }
}