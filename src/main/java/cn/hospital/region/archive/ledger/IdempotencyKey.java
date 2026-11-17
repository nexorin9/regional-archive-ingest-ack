package cn.hospital.region.archive.ledger;

import java.util.Objects;

/**
 * 幂等键 —— 一条入站报文在本端的唯一身份：{@code MSH-9 + MSH-10 + MSH-11}。
 *
 * <p><b>为什么三段缺一不可</b>：控制号（MSH-10）由对端生成，单靠它无法区分「同一个控制号的
 * 不同消息类型」（ADT^A08 与 ORU^R01 都可能用 CTRL-0001），也无法区分对端重启后复用了控制号的
 * 那一批。三段合起来才是接入规范里那条唯一约束 {@code uk_ledger_idem_key} 的口径。
 *
 * <p><b>空值不补默认值</b>：任一段为 null 一律折成空串参与拼接，<b>不</b>补 {@code "UNKNOWN"} 之类的
 * 占位。补了默认值，两条「都缺控制号」的不同报文会算出同一个键而在台账里互相覆盖——丢报文比
 * 撞键更难查。缺控制号的报文同样要占一行（它也可能被对端追问）。
 *
 * <p><b>拼接分隔符用 U+001F</b>（单元分隔符）：HL7 字段值里不会出现这个字符，因此拼接结果
 * 不会因为分隔符撞进字段值而产生二义。与 {@code ack.MessageReceiptBook.idempotencyKey} 同口径，
 * 两处算出的键可以互相比对。
 *
 * <p><b>不含患者可识别信息</b>：三段都是报文头元数据，不含姓名 / 身份证 / 手机号，
 * 因此本对象可以原样进日志与审计（见 spec.md 的脱敏边界）。
 */
public record IdempotencyKey(String messageType, String controlId, String processingId) {

    /** 幂等键拼接分隔符：U+001F 单元分隔符，HL7 字段值里不会出现。 */
    private static final char KEY_SEPARATOR = '\u001F';

    public IdempotencyKey {
        messageType = nullToEmpty(messageType);
        controlId = nullToEmpty(controlId);
        processingId = nullToEmpty(processingId);
        // 三段全空的键是「报文头被整个剥掉」的情形；它仍然唯一地标识这条报文，
        // 但必须在日志里显眼，否则排障时会把它当成 bug。这里保留空串，不抛异常 ——
        // 缺 MSH 的报文也要能入账并回一条带 ERR 的 AR（那是 T7/T8 的职责）。
    }

    /** 便捷构造。 */
    public static IdempotencyKey of(String messageType, String controlId, String processingId) {
        return new IdempotencyKey(messageType, controlId, processingId);
    }

    /**
     * 数据库查询用键：与 {@code ack.MessageReceiptBook.idempotencyKey} 拼法一致，
     * 供两侧日志 / 导出对账时逐字比对。
     */
    public String storageKey() {
        return messageType + KEY_SEPARATOR + controlId + KEY_SEPARATOR + processingId;
    }

    /** 从 {@code storageKey()} 反解；分隔符个数不为 2 视为非法键。 */
    public static IdempotencyKey parse(String storageKey) {
        Objects.requireNonNull(storageKey, "幂等键不得为 null");
        String[] parts = storageKey.split(String.valueOf(KEY_SEPARATOR), -1);
        if (parts.length != 3) {
            throw new IllegalArgumentException("幂等键格式非法（应恰为 3 段）：" + describe(storageKey));
        }
        return new IdempotencyKey(parts[0], parts[1], parts[2]);
    }

    /**
     * 日志友好的短描述：控制号缺失时显式写「(空)」，
     * 否则一条缺控制号的报文在排障日志里会显示成一个裸的分隔符串，看起来像乱码。
     */
    @Override
    public String toString() {
        return "type=" + (messageType.isEmpty() ? "(空)" : messageType)
                + " ctrl=" + (controlId.isEmpty() ? "(空)" : controlId)
                + " proc=" + (processingId.isEmpty() ? "(空)" : processingId);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String describe(String s) {
        // 只回长度与分隔符个数，不回原文 —— 免得异常消息把整条报文头带进日志
        return "<长度 " + s.length() + " 的键>";
    }
}