package cn.hospital.region.archive.ack;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 应用级回执账页 —— 一条报文<b>在本端被判成了什么</b>、以及它当前停在哪个应用级状态。
 *
 * <p><b>为什么要有这一页，而不能只看传输级 {@code 0x06}</b>：传输级确认只说明「字节收全了」，
 * 与这条报文后来被怎么处理毫无关系。把 {@code 0x06} 读成「这条已落地」是本产品最容易犯、
 * 也最贵的错——对账时数字会对不上，而现场还查不出是哪一步错的。这一页就是那个分界：
 *
 * <pre>
 *   传输级：0x06 / 0x15（{@link cn.hospital.region.archive.mllp.MllpFrameCodec}）
 *            → 只回答「字节收到了吗」
 *   应用级：MSA-1 AA/AE/AR + 本页的状态 → 才回答「这条我收下了吗 / 为什么没收下」
 * </pre>
 *
 * <p><b>AA 不等于已落地</b>：{@code MSA-1=AA} 的语义是「这条我收下了，接下来走台账与确认门」，
 * 对应状态是 {@link AppState#RECEIVED}；真正落地要等人点确认（确认门落地后写入患者主索引），
 * 那是 {@link AppState#LANDED}。本产品把这两个状态分开记，就是为了让「收了」和「落地了」
 * 在任何一处报表里都不会被合并成一个数字。
 *
 * <p><b>幂等键</b>：{@code MSH-9 + MSH-10 + MSH-11}，与接入规范一致。用单元分隔符
 * {@code U+001F} 拼接——任何 HL7 字段值里都不会出现这个字符，拼接结果不会因为分隔符撞进
 * 字段值而产生两义。留空字段（MSH-10 缺失时）照常参与拼接：控制号缺失的报文同样要占一个键，
 * 否则同一批里的多条无控制号报文会在账页里互相覆盖。
 *
 * <p><b>本类是内存账页</b>，对应 T11 的数据库台账（{@code message_ledger}）。落库前的口径先在这里
 * 定死：状态命名、AA→已接收的映射、幂等键拼法都与后续台账一致，替换存储层时不需要重写调用点。
 */
public final class MessageReceiptBook {

    /** 幂等键分隔符：U+001F 单元分隔符，HL7 字段值里不会出现。 */
    private static final char KEY_SEPARATOR = '\u001F';

    /**
     * 应用级状态 —— 与接入侧四态同名同义，落库台账沿用同一套命名。
     *
     * <p>状态由<b>回执判定</b>推进，不由传输确认推进；{@link AppState#LANDED} 只能由人工确认动作产生。
     */
    public enum AppState {
        /** 已接收：MSA-1=AA，收下了，等待台账与确认门继续处理。 */
        RECEIVED,
        /** 待确认：MSA-1=AE，报文本身还有救（对端生成抖动 / 本端服务抖动），等人或重投处理。 */
        PENDING_CONFIRM,
        /** 已落地：已写院内患者主索引。<b>本产品只有人工确认一条路径能进这个状态。</b> */
        LANDED,
        /** 拒收：MSA-1=AR，确定性事实（字符集不支持 / 身份锚点冲突 / 版本不兼容），重投无意义。 */
        REJECTED;

        /** 终态：不会自己再变，等待人工动作（落地或撤回）。 */
        public boolean terminal() {
            return this == LANDED || this == REJECTED;
        }
    }

    /**
     * 一条报文的应用级回执记录。
     *
     * @param idempotencyKey 幂等键 MSH-9＋MSH-10＋MSH-11
     * @param messageType    MSH-9
     * @param controlId      MSH-10（可能为空：缺控制号的报文同样要占一个键）
     * @param processingId   MSH-11
     * @param ackCode        本端回出的 MSA-1：AA / AE / AR
     * @param state          应用级状态（由 {@link #stateFor(String)} 从 ackCode 映射）
     * @param redeliverable  是否允许进入重投集合（AE=true、AR=false、AA=false）
     * @param suggestedCode  定位到的本地错误码；受理报文为空串
     */
    public record Receipt(String idempotencyKey, String messageType, String controlId,
                          String processingId, String ackCode, AppState state,
                          boolean redeliverable, String suggestedCode) {

        public Receipt {
            if (idempotencyKey == null || idempotencyKey.isEmpty()) {
                throw new IllegalArgumentException("幂等键不得为空");
            }
            ackCode = ackCode == null ? "" : ackCode;
            state = state == null ? AppState.REJECTED : state;
            messageType = messageType == null ? "" : messageType;
            controlId = controlId == null ? "" : controlId;
            processingId = processingId == null ? "" : processingId;
            suggestedCode = suggestedCode == null ? "" : suggestedCode;
        }

        /** 一行摘要，进日志与运行摘要；不含患者可识别信息（只有控制号与类型）。 */
        public String toLogLine() {
            return "controlId=" + (controlId.isEmpty() ? "(空)" : controlId)
                    + " ack=" + ackCode
                    + " state=" + state
                    + (suggestedCode.isEmpty() ? "" : " code=" + suggestedCode)
                    + (redeliverable ? " redeliverable" : "");
        }
    }

    private final Map<String, Receipt> receipts = new ConcurrentHashMap<>();

    /**
     * 拼幂等键：MSH-9＋MSH-10＋MSH-11。
     *
     * <p>任一段为空照常参与（缺控制号的报文也要有键），空值以空串入键，不做「缺了就补默认值」
     * 的处理——补默认值会让两条不同的无控制号报文算出同一个键。
     */
    public static String idempotencyKey(String messageType, String controlId, String processingId) {
        return nullToEmpty(messageType) + KEY_SEPARATOR
                + nullToEmpty(controlId) + KEY_SEPARATOR
                + nullToEmpty(processingId);
    }

    /**
     * MSA-1 → 应用级状态。
     *
     * <p>{@code AA} 映射到 {@link AppState#RECEIVED} 而<b>不是</b> {@link AppState#LANDED}：
     * 「回执发了」与「数据写进院内系统了」是两件事，中间还隔着一个人工确认动作。
     */
    public static AppState stateFor(String ackCode) {
        if (AckComposer.CODE_AA.equals(ackCode)) {
            return AppState.RECEIVED;
        }
        if (AckComposer.CODE_AE.equals(ackCode)) {
            return AppState.PENDING_CONFIRM;
        }
        // AR 以及任何未预期的码一律按拒收记账：宁可记严，也不能让来路不明的码落进「已收下」
        if (ackCode == null || ackCode.isBlank()) {
            throw new IllegalArgumentException("回执代码不得为空：没有回执代码就没有应用级判定");
        }
        return AppState.REJECTED;
    }

    /**
     * 记一条回执判定。同一幂等键重复记账<b>覆盖</b>旧值并返回最新一条。
     *
     * <p>内存阶段允许覆盖；接入落库台账（T11）后由唯一约束保证同键只有一行，
     * 重复到达由幂等守卫返回「已处理」而不是覆盖业务状态。
     *
     * @param suggestedCode 定位到的本地错误码；受理报文传空串
     * @param redeliverable 是否允许进入重投集合
     */
    public Receipt record(String messageType, String controlId, String processingId,
                          String ackCode, String suggestedCode, boolean redeliverable) {
        String key = idempotencyKey(messageType, controlId, processingId);
        Receipt receipt = new Receipt(key, messageType, controlId, processingId, ackCode,
                stateFor(ackCode), redeliverable, suggestedCode);
        receipts.put(key, receipt);
        return receipt;
    }

    /** 按幂等键取记录；未记账返回 {@code null}。 */
    public Receipt find(String idempotencyKey) {
        return idempotencyKey == null ? null : receipts.get(idempotencyKey);
    }

    /** 按幂等键取记录；未记账返回空。 */
    public Optional<Receipt> lookup(String idempotencyKey) {
        return Optional.ofNullable(find(idempotencyKey));
    }

    /** 按 MSH-9/10/11 取应用级状态；未记账返回空。 */
    public Optional<AppState> stateOf(String messageType, String controlId, String processingId) {
        return lookup(idempotencyKey(messageType, controlId, processingId)).map(Receipt::state);
    }

    /** 已记账条数。 */
    public int size() {
        return receipts.size();
    }

    /** 处于某状态的条数。 */
    public long countByState(AppState state) {
        return receipts.values().stream().filter(r -> r.state() == state).count();
    }

    /**
     * 已落地条数。
     *
     * <p>确认门接入前恒为 0：没有任何路径能不经人工确认就把报文写进院内患者主索引。
     */
    public long landedCount() {
        return countByState(AppState.LANDED);
    }

    /** 全部记录（按幂等键排序，便于逐条比对与导出）。 */
    public List<Receipt> all() {
        return receipts.values().stream()
                .sorted(Comparator.comparing(Receipt::idempotencyKey))
                .toList();
    }

    /** 全部幂等键（排序后）。 */
    public List<String> keys() {
        return all().stream().map(Receipt::idempotencyKey).toList();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}