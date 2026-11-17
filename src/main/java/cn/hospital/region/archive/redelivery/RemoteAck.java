package cn.hospital.region.archive.redelivery;

import java.time.Instant;
import java.util.Objects;

/**
 * 对端回执列表里的一条 —— 「区域平台说它收到了我们这条报文的回执」。
 *
 * <p>它是<b>对端视角的事实</b>，不是本端台账状态：同一控制号在本端可以是已落地 / 待确认 /
 * 拒收，差别只在于<b>这条回执有没有到达对端</b>。差集算法要的就是这两侧的事实之差。
 *
 * @param controlId   对端回执里的 MSH-10 原文（<b>不</b>归一，原文要留给排障）
 * @param messageType 对端回执自述的 MSH-9；缺省空串（对端只回控制号时）
 * @param msaCode     对端记下的 MSA-1（AA / AE / AR）；对端未回报时为空串
 * @param receivedAt  对端收到本端回执的时刻；未回报时为 {@link Instant#EPOCH}
 */
public record RemoteAck(String controlId, String messageType, String msaCode, Instant receivedAt) {

    public RemoteAck {
        controlId = controlId == null ? "" : controlId;
        messageType = messageType == null ? "" : messageType;
        msaCode = msaCode == null ? "" : msaCode.trim();
        receivedAt = receivedAt == null ? Instant.EPOCH : receivedAt;
    }

    /** 对账用比较键（归一后的控制号）。空串表示这条回执的控制号不可用。 */
    public String normalizedControlId() {
        return ControlIdNormalizer.normalize(controlId);
    }

    /** 这条回执能不能参与差集：控制号归一后必须非空。 */
    public boolean usable() {
        return !normalizedControlId().isEmpty();
    }

    /** 对端是否明确记为「应用受理」（AA）。空串（未回报 MSA）不当作 AA。 */
    public boolean accepted() {
        return "AA".equals(msaCode);
    }

    /** 人工可读的一行（不含任何患者可识别内容 —— 对端回执里只有控制号与状态码）。 */
    public String toLogLine() {
        return "controlId=" + controlId
                + " msa=" + (msaCode.isEmpty() ? "(对端未回报)" : msaCode)
                + " 归一键=" + normalizedControlId()
                + (receivedAt.equals(Instant.EPOCH) ? "" : " receivedAt=" + receivedAt);
    }

    /** 校验一条待构造的回执：控制号不可用时立即拒，不让空控制号进入差集。 */
    public static RemoteAck requireUsable(String controlId, String messageType, String msaCode,
                                          Instant receivedAt, String where) throws ReconcileException {
        RemoteAck ack = new RemoteAck(controlId, messageType, msaCode, receivedAt);
        if (!ack.usable()) {
            throw new ReconcileException(
                    ReconcileErrorCode.MALFORMED_RESPONSE,
                    where + "：对端回执缺控制号或控制号归一后为空，"
                            + "拿空控制号做差集会把「对端漏报」与「本端没这条」混成一件，拒绝参与对账",
                    null, null);
        }
        return ack;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RemoteAck o)) {
            return false;
        }
        // 比较键是归一后的控制号 + MSA 码：接收时刻差几秒不改变「对端是否认了这条」
        return normalizedControlId().equals(o.normalizedControlId())
                && msaCode.equals(o.msaCode);
    }

    @Override
    public int hashCode() {
        return Objects.hash(normalizedControlId(), msaCode);
    }
}