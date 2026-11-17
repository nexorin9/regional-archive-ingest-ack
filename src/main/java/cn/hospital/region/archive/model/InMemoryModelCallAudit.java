package cn.hospital.region.archive.model;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 内存版调用审计 —— 本轮随产品提供的默认实现。
 *
 * <p>接入口是常驻进程，一天几百到几千次调用，量级不大；
 * 而「先记下来、再落库」的分层能换掉一个现实问题：模型调用返回得比落库快，
 * 审计写失败时业务不该跟着失败（T28 换成 JDBC 时这条策略就有着落了）。
 *
 * <p>{@link CopyOnWriteArrayList}：写入是低频的（一次调用一行），读是高频的
 * （每次要出统计口径就要看一遍）。锁开销在真实量级下可以忽略，而换成普通
 * {@code ArrayList} 会在「模型调用中把审计读一遍做打印」时踩到并发修改。
 */
public final class InMemoryModelCallAudit implements ModelCallAudit {

    private final List<ModelCallRecord> records = new CopyOnWriteArrayList<>();

    @Override
    public void record(ModelCallRecord record) {
        records.add(record);
    }

    /** 已记审计行（不可变快照）。 */
    public List<ModelCallRecord> records() {
        return Collections.unmodifiableList(List.copyOf(records));
    }

    public int count() {
        return records.size();
    }

    /** 最近一行；一条都没有时抛 {@link java.util.NoSuchElementException}。 */
    public ModelCallRecord latest() {
        if (records.isEmpty()) {
            throw new java.util.NoSuchElementException("尚无模型调用审计记录");
        }
        return records.get(records.size() - 1);
    }

    /** 某操作的调用行。 */
    public List<ModelCallRecord> of(String operation) {
        return records.stream().filter(r -> r.operation().equals(operation)).toList();
    }

    /** 清空（测试与热重启用；生产不调用）。 */
    public void clear() {
        records.clear();
    }
}