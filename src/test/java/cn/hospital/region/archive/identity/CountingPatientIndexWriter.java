package cn.hospital.region.archive.identity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 计数替身 —— 实现与 T31 {@code HisPatientIndexClient} <b>同一个端口</b>，
 * 只把出站调用记下来，不发任何网络请求。
 *
 * <h2>为什么需要它</h2>
 * <p>「同一条报文重发 100 次，HIS 里会不会出现 100 个重复档案」这个问题，
 * 只有在<b>能数出调用次数</b>的前提下才答得上来。端口先于实现落地，正是为了让这个断言
 * 在 T31 把真实 HTTP 接进来之前就能写好；T31 之后把本类换成
 * {@code HisPatientIndexClient}，断言<b>原样不变</b>——变的只是「写出去真的到了 HIS」，
 * 不变的是「只写了一次」。
 *
 * <h2>替身不替代真实实现的验收</h2>
 * <p>本类只回答「写了几次、写了什么参数」，<b>不</b>回答「对端认不认这份 JSON、
 * 5xx 怎么分类、超时怎么退避」。那三件事由 T31 的契约测试（真实 HTTP 进程 + 真实 JSON）
 * 负责。本类不承担、也不假装承担正式对接层的验收。
 *
 * <p>线程安全：并发用例里多个线程会同时调用，计数与参数列表都按并发口径实现。
 */
public final class CountingPatientIndexWriter implements PatientIndexWriter {

    /** 一次 put 调用的参数留痕（供断言「参数与幂等键对得上」）。 */
    public record Invocation(String patientRef, String localMrn, String sourceBatch) {
    }

    private final AtomicInteger invocations = new AtomicInteger();
    private final List<Invocation> calls = Collections.synchronizedList(new ArrayList<>());
    private volatile String failNextWith;

    /** 下一次 put 抛「可重放」的写失败。传 {@code null} 关闭。 */
    public void failNextWith(String message) {
        this.failNextWith = message;
    }

    @Override
    public void put(String patientRef, String localMrn, String sourceBatch)
            throws PatientIndexWriteException {
        String failure = failNextWith;
        if (failure != null) {
            failNextWith = null;
            // 计数仍然 +1：这次调用真的发生过。断言「写失败后计数仍为 1、
            // 且台账退回待确认」时要用到这个口径。
            invocations.incrementAndGet();
            calls.add(new Invocation(patientRef, localMrn, sourceBatch));
            throw new PatientIndexWriteException(failure, true);
        }
        invocations.incrementAndGet();
        calls.add(new Invocation(patientRef, localMrn, sourceBatch));
    }

    /** 出站调用总次数 —— 「重放 100 次写了几次」就看这个数。 */
    public int invocationCount() {
        return invocations.get();
    }

    /** 逐次调用的参数留痕（按发生顺序）。 */
    public List<Invocation> invocations() {
        synchronized (calls) {
            return List.copyOf(calls);
        }
    }

    /** 是否发生任何出站调用（用于「未经确认零写入」这类红线断言）。 */
    public boolean neverCalled() {
        return invocations.get() == 0;
    }
}