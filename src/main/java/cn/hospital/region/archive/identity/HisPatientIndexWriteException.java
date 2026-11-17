package cn.hospital.region.archive.identity;

/**
 * 院内患者主索引写回失败 —— 在 {@link PatientIndexWriteException} 上补<b>分类码与 HTTP 状态</b>。
 *
 * <p>父类只说「写失败、是不是可重放」，本子类补上「哪一类失败、对端回了什么状态码」。
 * 之所以做成子类而不是往父类上加字段：父类是 T16 立下的<b>端口契约</b>，
 * T16/T30 的断言与计数替身都不该因为 T31 接了真实 HTTP 就跟着变 ——
 * 端口不变，是这套契约能被复跑的前提。
 *
 * <p><b>继承即兼容</b>：调用方捕获 {@code PatientIndexWriteException} 的代码一字不用改，
 * {@code ArchiveIngestService.land} 的「写失败 → 退回待确认」那一步照常触发。
 *
 * <p><b>可重放一律为 true</b>：无论哪一类失败，本端的行都要退回待确认等人，
 * 报文原文与幂等键原样保留 —— 这条不变量不能因为「401 是配置问题」就打个折扣，
 * 否则现场会出现「第一次确认写失败 → 行停在已落地 → HIS 里查无此人」的静默数据不一致。
 * 真正决定「要不要自动重投」的是 {@link HisWriteErrorCode#jittery()} 与 T33 的退避上限，
 * 不是这个标志。
 */
public class HisPatientIndexWriteException extends PatientIndexWriteException {

    private final HisWriteErrorCode code;

    /** 对端 HTTP 状态码；连接阶段就失败（连不上、超时）时为 {@code null}。 */
    private final Integer httpStatus;

    public HisPatientIndexWriteException(String message, HisWriteErrorCode code,
                                         Integer httpStatus, Throwable cause) {
        super(message, true, cause);
        if (code == null) {
            throw new IllegalArgumentException("失败分类码不得为 null");
        }
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public HisWriteErrorCode code() {
        return code;
    }

    /** 对端状态码；未收到响应时返回 {@code null}（调用点据此区分「没连上」与「连上了被拒」）。 */
    public Integer httpStatus() {
        return httpStatus;
    }

    /** 原样重投是否有希望恢复（{@link HisWriteErrorCode#jittery()}）。 */
    public boolean jittery() {
        return code.jittery();
    }
}