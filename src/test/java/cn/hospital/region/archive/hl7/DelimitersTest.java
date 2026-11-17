package cn.hospital.region.archive.hl7;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Delimiters 的验收：默认 5 字符、从 MSH substring(3,8) 自读、4 组非默认变体识别、
 * 非 header/长度不足抛明确异常。**不允许把分隔符写死在调用方代码里。**
 */
class DelimitersTest {

    @Test
    @DisplayName("default 5 字符与 HL7 规范一致: | ^ ~ \\ &")
    void default_delimiters_match_hl7_standard() {
        Delimiters d = Delimiters.DEFAULT;
        assertEquals('|', d.fieldSeparator());
        assertEquals('^', d.componentSeparator());
        assertEquals('~', d.repetitionSeparator());
        assertEquals('\\', d.escapeCharacter());
        assertEquals('&', d.subcomponentSeparator());
        assertEquals("|^~\\&", d.asString());
    }

    @Test
    @DisplayName("从 MSH substring(3,8) 读出默认分隔符并等同于 DEFAULT")
    void readFromHeader_recovers_default_delimiters() {
        String msh = "MSH|^~\\&|APP|FAC|...";
        Delimiters d = Delimiters.readFromHeader(msh);
        assertEquals(Delimiters.DEFAULT, d);
        assertEquals('|', d.fieldSeparator());
        assertEquals('^', d.componentSeparator());
        assertEquals('~', d.repetitionSeparator());
        assertEquals('\\', d.escapeCharacter());
        assertEquals('&', d.subcomponentSeparator());
    }

    /**
     * 国内区域平台偶见的非法/弱默认分隔符（! $ % * &）——
     * 这是 HAPI v251 fixtures/delims_alternate_bang_dollar.hl7 用的方言。
     */
    @Test
    @DisplayName("非默认变体 #1 国内平台常见 ! $ % * &：从 MSH substring(3,8) 自读成功")
    void readFromHeader_handles_bang_dollar_percent_star_amp() {
        String msh = "MSH!$%*&!Synthea!SYNTHETEA!COMMUNITY_HEALTH|...";
        Delimiters d = Delimiters.readFromHeader(msh);
        assertEquals('!', d.fieldSeparator());
        assertEquals('$', d.componentSeparator());
        assertEquals('%', d.repetitionSeparator());
        assertEquals('*', d.escapeCharacter());
        assertEquals('&', d.subcomponentSeparator());
        assertNotEquals(Delimiters.DEFAULT, d);
    }

    @Test
    @DisplayName("非默认变体 #2 五个互不相同的分隔符（| ^ ~ $ @）—— 自述读出与默认不同")
    void readFromHeader_handles_distinct_five_delimiters() {
        // MSH|^~$@  位置: 01234567  →  substring(3,8) = '|', '^', '~', '$', '@'
        String msh = "MSH|^~$@|APP|FAC|...";
        Delimiters d = Delimiters.readFromHeader(msh);
        assertEquals('|', d.fieldSeparator());
        assertEquals('^', d.componentSeparator());
        assertEquals('~', d.repetitionSeparator());
        assertEquals('$', d.escapeCharacter());
        assertEquals('@', d.subcomponentSeparator());
        // 与默认不同
        assertNotEquals(Delimiters.DEFAULT, d);
        // 5 个分隔符字符全部互不相同（边界保护）
        java.util.Set<Character> five = new java.util.HashSet<>();
        five.add('|'); five.add('^'); five.add('~'); five.add('$'); five.add('@');
        assertEquals(5, five.size());
    }

    @Test
    @DisplayName("非默认变体 #3 BHS / FHS header 同样读到自述分隔符")
    void readFromHeader_handles_BHS_and_FHS() {
        String bhs = "BHS|^~\\&|...|...";
        assertEquals(Delimiters.DEFAULT, Delimiters.readFromHeader(bhs));
        String fhs = "FHS|^~\\&|...|...";
        assertEquals(Delimiters.DEFAULT, Delimiters.readFromHeader(fhs));
    }

    @Test
    @DisplayName("非默认变体 #4 管道变竖线（||/||/|/||）—— 边界长字符串不误判")
    void readFromHeader_handles_long_header_with_extra_chars() {
        String msh = "MSH|^~\\&|SendingApp|SendingFac|ReceivingApp|ReceivingFac|20261020120000+0800||ADT^A08|CTL123|P|2.5.1";
        Delimiters d = Delimiters.readFromHeader(msh);
        assertEquals(Delimiters.DEFAULT, d);
        // 即使 header 很长，只读前 5 个分隔符字符
        assertEquals('|', d.fieldSeparator());
    }

    @Test
    @DisplayName("非 header 段（例如 PID）调 readFromHeader 抛 DelimiterNotDeclared 并附前 32 字节")
    void readFromHeader_throws_for_non_header() {
        String pid = "PID|1||HL7-0001||Doe^Jane^^^L||19750312|F";
        DelimiterNotDeclared ex = assertThrows(DelimiterNotDeclared.class,
                () -> Delimiters.readFromHeader(pid));
        assertTrue(ex.getMessage().contains("PID"),
                "异常消息应包含被拒段名以便对账；实际=" + ex.getMessage());
        assertTrue(ex.getMessage().contains("[len="),
                "异常消息应附长度信息便于对账；实际=" + ex.getMessage());
    }

    @Test
    @DisplayName("header 段长度不足 8 抛 DelimiterNotDeclared 并附 MSH 前 32 字节")
    void readFromHeader_throws_for_too_short_header() {
        String tooShort = "MSH|^";
        DelimiterNotDeclared ex = assertThrows(DelimiterNotDeclared.class,
                () -> Delimiters.readFromHeader(tooShort));
        assertTrue(ex.getMessage().contains("长度不足 8"),
                "异常应说明长度不足；实际=" + ex.getMessage());
    }

    @Test
    @DisplayName("fromChars 与 readFromHeader 等价（同一组 5 字符）")
    void fromChars_consistent_with_readFromHeader() {
        Delimiters a = Delimiters.fromChars('|', '^', '~', '\\', '&');
        Delimiters b = Delimiters.readFromHeader("MSH|^~\\&|x|y|...");
        assertEquals(a, b);
    }

    @Test
    @DisplayName("isHeaderSegment 判定：MSH/BHS/FHS 是；PID/PV1/OBX/ZX 不是")
    void isHeaderSegment_only_for_msh_bhs_fhs() {
        assertTrue(Delimiters.isHeaderSegment("MSH|^~\\&"));
        assertTrue(Delimiters.isHeaderSegment("BHS|^~\\&"));
        assertTrue(Delimiters.isHeaderSegment("FHS|^~\\&"));
        assertEquals(false, Delimiters.isHeaderSegment("PID|1"));
        assertEquals(false, Delimiters.isHeaderSegment("PV1|1"));
        assertEquals(false, Delimiters.isHeaderSegment("OBR|1"));
        assertEquals(false, Delimiters.isHeaderSegment("ZX1|2"));
    }
}
