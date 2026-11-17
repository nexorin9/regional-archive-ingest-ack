package cn.hospital.region.archive.mllp;

import java.io.IOException;

/**
 * 对端回了<b>不该出现</b>的字节 —— 协议层不一致，<b>不是</b>连接断了。
 *
 * <h2>为什么要单独一类</h2>
 * <p>参考实现的 {@code ReconnectDirContext} 靠 {@code isLdap_connection_has_been_closed(e)}
 * 把「连接没了，该重建再试」与「这个操作本身就不该这么做，原样上抛」分开：
 * 只有前者才值得重连重建，后者重连一百次也是同一个结果。
 * <p>MLLP 侧最典型的不可重连错误就是本类：首字节既不是传输级 {@code 0x06}/{@code 0x15}，
 * 也不是帧起始 {@code 0x0B}。这说明双方根本没在说同一种协议（端口上跑的不是 MLLP、
 * 或者对端实现有偏差），重发同一份字节只会再收到同样的垃圾，还会把重试预算白烧光。
 */
public class MllpProtocolException extends IOException {

    public MllpProtocolException(String message) {
        super(message);
    }
}
