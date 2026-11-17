package cn.hospital.region.archive.ack;

import cn.hospital.region.archive.hl7.FieldLocator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HL7 错误码目录断言。
 *
 * <p>这一组的验收标准是<b>目录本身可信</b>：条目格式统一、码是数字、每类拒收原因都登记了码、
 * 没有对应标准语义的原因如实走自定义码而不是硬套一个标准码。
 */
class Hl7ErrorCodesTest {

    @Test
    @DisplayName("标准码目录条目均为 <码^说明^表来源> 三段式，且码为纯数字")
    void test_standard_entries_are_three_part_and_numeric() {
        List<ErrorCodeEntry> entries = Hl7ErrorCodes.standardEntries();
        assertFalse(entries.isEmpty(), "标准码目录不得为空");
        for (ErrorCodeEntry e : entries) {
            assertTrue(e.code().chars().allMatch(Character::isDigit),
                    "标准码必须是纯数字，实际：" + e.code());
            assertEquals(ErrorCodeEntry.TABLE_0357, e.tableSource(),
                    "标准码必须标注 HL7 表来源：" + e.code());
            assertEquals(ErrorCodeEntry.HL7_VERSION, e.hl7Version(),
                    "标准码必须标注 HL7 版本：" + e.code());
            assertFalse(e.text().isBlank(), "标准码必须有英文说明：" + e.code());
            assertFalse(e.zhLabel().isBlank(), "标准码必须有中文说明：" + e.code());

            String[] parts = e.toCwe().split("\\^", -1);
            assertEquals(3, parts.length, "三段式应为 码^说明^表来源：" + e.code());
            assertEquals(e.code(), parts[0]);
            assertEquals(e.text(), parts[1]);
            assertEquals(e.tableSource(), parts[2]);
        }
    }

    @Test
    @DisplayName("目录点名收录的码都在位：103 表值、201 事件码、200 语法、101/102 必填字段缺失类")
    void test_named_codes_are_present() {
        assertEquals("103", Hl7ErrorCodes.TABLE_VALUE_NOT_FOUND.code());
        assertEquals("201", Hl7ErrorCodes.UNSUPPORTED_EVENT_CODE.code());
        assertEquals("200", Hl7ErrorCodes.SYNTAX_ERROR.code());
        assertEquals("101", Hl7ErrorCodes.MISSING_OR_INVALID_ELEMENT.code());
        assertEquals("102", Hl7ErrorCodes.DATA_TYPE_ERROR.code());
        assertEquals("103", Hl7ErrorCodes.requireByCode("103").code());
        assertEquals("201", Hl7ErrorCodes.requireByCode("201").code());
    }

    @Test
    @DisplayName("七类拒收原因各自绑定一个目录条目，没有一条落到空")
    void test_every_rejection_reason_binds_a_catalog_entry() {
        for (RejectionReason reason : RejectionReason.values()) {
            ErrorCodeEntry entry = reason.standardCode();
            assertNotNull(entry, reason.name() + " 未绑定目录条目");
            assertAll(reason.name(),
                    () -> assertFalse(entry.code().isBlank(), "目录码不得为空串"),
                    () -> assertFalse(entry.tableSource().isBlank(), "表来源不得为空串"),
                    () -> assertEquals(entry, Hl7ErrorCodes.forRejection(reason),
                            "同一原因多次取目录必须得到同一条目（目录是只读的）"));
        }
    }

    @Test
    @DisplayName("确定性成因绑定标准码：字符集→207、版本→204、身份冲突→100、模型降级→206")
    void test_deterministic_causes_bind_standard_codes() {
        assertEquals("207", Hl7ErrorCodes.forRejection(RejectionReason.UNSUPPORTED_CHARSET).code());
        assertEquals("204", Hl7ErrorCodes.forRejection(RejectionReason.VERSION_INCOMPATIBLE).code());
        assertEquals("100", Hl7ErrorCodes.forRejection(RejectionReason.IDENTITY_CONFLICT).code());
        assertEquals("206", Hl7ErrorCodes.forRejection(RejectionReason.MODEL_DEGRADED).code());
        assertEquals("101", Hl7ErrorCodes.forRejection(RejectionReason.MISSING_REQUIRED_FIELD).code());
        assertEquals("202", Hl7ErrorCodes.forRejection(RejectionReason.MISSING_REQUIRED_SEGMENT).code());
    }

    @Test
    @DisplayName("无对应标准语义的成因走自定义码并带 CUSTOM- 前缀，不硬套标准码")
    void test_unmapped_cause_falls_back_to_custom_with_prefix() {
        ErrorCodeEntry entry = Hl7ErrorCodes.forRejection(RejectionReason.SEMANTIC_ALIGN_FAILED);
        assertTrue(entry.custom(), "语义对齐失败在 HL7 表 0357 里没有对应码，必须走自定义码");
        assertTrue(entry.code().startsWith(ErrorCodeEntry.CUSTOM_PREFIX),
                "自定义码必须带 CUSTOM- 前缀，实际：" + entry.code());
        assertEquals(ErrorCodeEntry.TABLE_LOCAL, entry.tableSource());
        assertFalse(entry.code().chars().allMatch(Character::isDigit),
                "自定义码不得冒充数字标准码：" + entry.code());
        // 不能悄悄变成 206：那会让对端以为这是一次偶发故障并安排重投，而真实情况是补不对字段
        assertFalse("206".equals(entry.code()), "语义对齐失败不得被硬套成 206");
    }

    @Test
    @DisplayName("未登记的本地码降级为自定义码而不是抛异常：回执照样发得出去，但一眼看出对端解析不了")
    void test_unregistered_local_code_degrades_to_custom() {
        ErrorCodeEntry entry = Hl7ErrorCodes.forSuggestedCode("some-brand-new-condition");
        assertTrue(entry.custom());
        assertEquals("CUSTOM-some-brand-new-condition", entry.code());
        assertEquals(ErrorCodeEntry.TABLE_LOCAL, entry.tableSource());
        assertTrue(entry.describeZh().contains("对端不可解析"),
                "自定义码的中文说明必须点明对端不可解析：" + entry.describeZh());
    }

    @Test
    @DisplayName("空值与 null 不炸，降级为 CUSTOM-unspecified")
    void test_null_and_blank_degrade_to_unspecified() {
        assertEquals("CUSTOM-unspecified", Hl7ErrorCodes.forSuggestedCode(null).code());
        assertEquals("CUSTOM-unspecified", Hl7ErrorCodes.forSuggestedCode("   ").code());
    }

    @Test
    @DisplayName("已经拿到目录码值（含 CUSTOM- 前缀）时直接取用，不重复造同码条目")
    void test_known_code_value_returns_same_entry() {
        assertEquals(Hl7ErrorCodes.SEMANTIC_ALIGN_FAILED,
                Hl7ErrorCodes.forSuggestedCode(Hl7ErrorCodes.SEMANTIC_ALIGN_FAILED.code()));
        assertEquals(Hl7ErrorCodes.byCode("207"), Hl7ErrorCodes.forSuggestedCode("207"));
        assertEquals(1, Hl7ErrorCodes.customEntries().size());
    }

    @Test
    @DisplayName("FieldLocator 的必需字段缺失码全部登记了标准码，两套词表不打架")
    void test_field_locator_vocabulary_is_fully_bound() {
        Map<String, ErrorCodeEntry> bindings = Hl7ErrorCodes.bindings();
        for (String code : List.of(
                FieldLocator.CODE_MISSING_SEGMENT,
                FieldLocator.CODE_UNSUPPORTED_EVENT_TYPE,
                FieldLocator.CODE_CONTROL_ID_MISSING,
                FieldLocator.CODE_PROCESSING_ID_MISSING,
                FieldLocator.CODE_VERSION_MISSING,
                FieldLocator.CODE_PATIENT_ID_MISSING,
                FieldLocator.CODE_PATIENT_NAME_MISSING,
                FieldLocator.CODE_BIRTH_DATE_MISSING,
                FieldLocator.CODE_CHARSET_NOT_DECLARED)) {
            ErrorCodeEntry entry = bindings.get(code);
            assertNotNull(entry, "FieldLocator 词表里的 " + code + " 未登记标准码");
            assertEquals(entry, Hl7ErrorCodes.forSuggestedCode(code),
                    "目录绑定表与查询口径必须一致：" + code);
        }
        assertEquals("201", bindings.get(FieldLocator.CODE_UNSUPPORTED_EVENT_TYPE).code());
        assertEquals("207", bindings.get(FieldLocator.CODE_CHARSET_NOT_DECLARED).code());
    }

    @Test
    @DisplayName("标准目录只读：没有重复码，取到的条目两次是同一个")
    void test_catalog_is_read_only_and_deduplicated() {
        List<ErrorCodeEntry> entries = Hl7ErrorCodes.standardEntries();
        assertEquals(entries.size(),
                entries.stream().map(ErrorCodeEntry::code).distinct().count(),
                "标准码目录不得有重复码");
        assertEquals(entries, Hl7ErrorCodes.standardEntries(), "目录两次取用必须一致");
        assertNull(Hl7ErrorCodes.byCode("999"), "目录里没有的码应返回 null 而不是抛异常");
    }

    @Test
    @DisplayName("ERR 段的 ERR-3 带上目录标准码与表来源，且第一组件仍是本端本地码")
    void test_err_segment_carries_local_code_and_catalog_code() {
        ErrSegment err = ErrSegment.of(RejectionReason.UNSUPPORTED_CHARSET, null);
        assertEquals(RejectionReason.UNSUPPORTED_CHARSET.suggestedCode(), err.errorCode(),
                "ERR-3 首组件仍是本端本地码");
        assertEquals("207", err.catalogCode(), "ERR-3 第三组件是目录标准码");
        assertEquals("HL70357", err.tableSource(), "ERR-3 第四组件是表来源");
        assertTrue(err.standardCodeResolvable());

        ErrSegment custom = ErrSegment.of(RejectionReason.SEMANTIC_ALIGN_FAILED, null);
        assertTrue(custom.catalogCode().startsWith(ErrorCodeEntry.CUSTOM_PREFIX),
                "自定义码必须带 CUSTOM- 前缀：" + custom.catalogCode());
        assertFalse(custom.standardCodeResolvable(), "自定义码必须报「对端不可解析」");
        assertEquals(ErrorCodeEntry.TABLE_LOCAL, custom.tableSource());
        assertEquals(custom.catalogCode(), custom.catalogEntry().code(),
                "自定义码条目能从码值反查回目录条目");
    }
}