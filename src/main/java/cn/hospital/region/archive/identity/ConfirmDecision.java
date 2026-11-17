package cn.hospital.region.archive.identity;

import java.util.Objects;

/**
 * 信息员在确认台上点下「确认」时带过来的那一份决定 —— <b>人给的结论，模型的输出不是</b>。
 *
 * <h2>为什么把它做成一个类型而不是三个 String</h2>
 * <p>{@code confirm(id, operator, patientRef, localMrn, reason)} 五个平铺参数里，
 * {@code patientRef} 与 {@code localMrn} 写反了编译器不管、运行时也静默 ——
 * 而写反的后果是把区域平台的主键当成院内主键写进 HIS，院内从此多出一位不存在的居民，
 * 且次日对账时两端各执一词。把两侧主键绑成 {@link IdentityKeyBinding} 后，
 * 「区域主键哪来的、院内主键哪来的」在类型上就分开了：
 * 一个是报文带来的（{@link IdentityKeyBinding#inboundOnly}），
 * 一个是人挑的（{@link IdentityKeyBinding#bound}），来源不同就不该由同一个构造器填。
 *
 * <h2>本类型里的三个字段各自回答一个现场问题</h2>
 * <ul>
 *   <li>{@code localMrn}：这条档案在本院是<b>哪一位</b>居民。必填，不得为空串 ——
 *       空串会让 {@code local_mrn} 写出一个「看似落地、实则没有号」的行。</li>
 *   <li>{@code candidateSource}：人是从哪儿挑出这个号的（模型候选第几、还是直接手工新建）。
 *       它进审计行：三个月后回看「这个映射当时是谁按什么依据认的」，靠的就是它。</li>
 *   <li>{@code note}：一句自由文本备注。进<b>拒绝留痕与审计行的 reason</b>，
 *       因此同样受「不得写姓名 / 证件号 / 手机号明文」约束（T40 会扫全量日志）。</li>
 * </ul>
 *
 * <h2>不变量</h2>
 * <ul>
 *   <li>{@code operator} 与 {@code localMrn} 均不得为空白：前者查不到人，后者写不出号。</li>
 *   <li>{@code note} 长度封顶（{@value #MAX_NOTE}）：审计行是<b>全量留痕</b>，
 *       排障时会被直接读出来，一句三百字的备注会把整行撑成一堵墙。</li>
 * </ul>
 */
public record ConfirmDecision(String operator,
                              IdentityKeyBinding keys,
                              String candidateSource,
                              String note) {

    /** 备注长度上限：审计行里这一列的可读上限。 */
    public static final int MAX_NOTE = 200;

    public ConfirmDecision {
        requireText(operator, "operator");
        Objects.requireNonNull(keys, "双主键绑定不得为 null");
        if (!keys.boundToHis()) {
            throw new IllegalArgumentException(
                    "确认必须带院内主键（localMrn 不得为空串）：空串会让台账留下一行"
                            + "「已落地却查无此人」，次日对账时数字对不上且现场无从查起");
        }
        candidateSource = candidateSource == null ? "" : candidateSource.trim();
        note = note == null ? "" : note.trim();
        if (note.length() > MAX_NOTE) {
            throw new IllegalArgumentException("备注超过 " + MAX_NOTE + " 字，实际 " + note.length()
                    + " 字：审计行是全量留痕，备注要能被排障的人一眼读完");
        }
        if (containsPatientPlaintextHint(keys.localMrn())) {
            // 不做内容识别（那是脱敏器的活，T40），只挡住最常见的一类低级错误：
            // 把姓名或证件号直接填进院内主键字段。院内主键是编号，不是名字。
            throw new IllegalArgumentException("院内主键看起来不像编号（长度 " + keys.localMrn().length()
                    + "）：主键列只放编号，姓名与证件号属于患者可识别信息，不得写入该列");
        }
    }

    /** 从区域内发报文、模型出了候选、信息员挑中第 {@code rank} 个。 */
    public static ConfirmDecision fromCandidate(String operator, String patientRef,
                                                String localMrn, int rank) {
        return new ConfirmDecision(operator, IdentityKeyBinding.bound(patientRef, localMrn),
                "model-candidate#" + rank, "");
    }

    /** 信息员手工新建映射（模型没出候选，或候选全被否了）。 */
    public static ConfirmDecision manual(String operator, String patientRef,
                                         String localMrn, String note) {
        return new ConfirmDecision(operator, IdentityKeyBinding.bound(patientRef, localMrn),
                "manual-entry", note);
    }

    /** 区域平台主键（报文带来的，本产品任何路径都不生成它）。 */
    public String patientRef() {
        return keys.patientRef();
    }

    /** 院内主键（人挑的，确认通过后才写进 HIS）。 */
    public String localMrn() {
        return keys.localMrn();
    }

    /** 审计行 reason 片段：{@code confirm:<来源>} —— 排障时按来源聚合确认动作。 */
    public String auditSource() {
        return "confirm:" + (candidateSource.isEmpty() ? "unspecified" : candidateSource);
    }

    private static boolean containsPatientPlaintextHint(String localMrn) {
        // 只挡最常见的一类手滑：把姓名直接填进「院内主键」输入框。
        // 判据是**含中日韩统一表意文字**——主键是编号，不会有汉字；
        // 证件号是纯数字，这里不拦（15/18 位数字串在别处已有校验位把关）。
        // 这不是完整脱敏，是第一道；完整脱敏在 T40 的 RedactedLog。
        return localMrn.codePoints().anyMatch(cp ->
                Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field
                    + " 不得为空白：没有操作人的确认在审计行里等于匿名操作，事后查不到是谁点的");
        }
    }
}