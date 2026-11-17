package cn.hospital.region.archive.ack;

import cn.hospital.region.archive.mllp.MllpServer;

import java.nio.charset.StandardCharsets;

/**
 * 主干最窄路径的处理链：拆出来的每一帧 → 读 MSH 头 → 回一条 {@code MSA-1=AA}。
 *
 * <p>刻意做到最窄——不落库、不调模型、不做身份判断。区域平台一推包，信息员当下最需要的是
 * 「这批东西我这边收不收得住」，先把「收 → 回执」这一段打通，后面的四态台账与确认门
 * 才有可挂载的位置。
 *
 * <p>传输层 {@code 0x06} 与应用级 MSA 是两件事：前者由 {@link MllpServer} 在处理<b>之前</b>
 * 发出、只说明「字节收到了」；后者是本处理链的产物，才是可以和台账逐条对账的业务结果。
 */
public final class AckEchoHandler implements MllpServer.MessageHandler {

    private final AckComposer composer;

    public AckEchoHandler(String localAet, String remoteAet) {
        this(new AckComposer(localAet, remoteAet));
    }

    public AckEchoHandler(AckComposer composer) {
        this.composer = composer;
    }

    @Override
    public byte[] handle(byte[] payload) {
        // 字符集按 MSH-18 解析由后续结构化任务接手；此处固定 UTF-8，与参考实现在未声明时的默认一致
        String er7 = new String(payload, StandardCharsets.UTF_8);
        InboundHeader header = InboundHeader.parse(er7);
        return composer.composeAccepted(header).getBytes(StandardCharsets.UTF_8);
    }
}