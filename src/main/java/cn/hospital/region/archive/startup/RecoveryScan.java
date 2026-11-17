package cn.hospital.region.archive.startup;

import cn.hospital.region.archive.ledger.LedgerEntry;
import cn.hospital.region.archive.ledger.LedgerRow;
import cn.hospital.region.archive.ledger.LedgerStore;
import cn.hospital.region.archive.ledger.MessageState;
import cn.hospital.region.archive.redelivery.RedeliveryQueue;

import java.sql.SQLException;
import java.time.Clock;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 进程启动时的恢复扫描 —— 把「上一轮没跑完的事」从台账里捞回来。
 *
 * <h2>为什么需要它</h2>
 * <p>源产品（参考实现 {@code github_ref/dcm4che/dcm4che-conf/dcm4che-conf-ldap/.../ReconnectDirContext.java}
 * 以及它面向 MLLP 侧的 {@code MLLPConnection.readACK()}）是「瞬时重试一次 / 进程内重连」的工具库，
 * <b>进程崩了就没了</b>，没有「恢复」的概念。
 *
 * <p>本产品把重投做成<b>落库事件</b>（T32 {@code redelivery_queue} 表），于是出现
 * 一种源产品没有的边界状态：进程在调度器把台账从 {@code RETRYING} 推进到 {@code RETRY_CONFIRMED} /
 * {@code PERMANENT_FAILED} 之前被运维重启或 OOM kill，那这条报文在台账侧是 {@code RETRYING}、
 * 在队列侧是 {@code CONSUMED}，下次启动后 <b>必须有机制把它们重新接上</b>。
 *
 * <h2>扫描范围</h2>
 * <p>本类不区分「真正该被恢复」与「正常还在路上的」；它只回答一件事：
 * 「台账里 <b>在重投路径上还未终态</b> 的行，对应的重投队列条目能不能出队」。
 *
 * <p><b>纳入扫描的状态</b>（{@link #RETRY_PATH_STATES}）：
 * <ul>
 *   <li>{@link MessageState#PENDING_RETRY} —— 已入重投队列、等待被调度</li>
 *   <li>{@link MessageState#RETRYING} —— 已被调度出队，但重投完成回调未跑完即进程挂</li>
 *   <li>{@link MessageState#RETRY_CONFIRMED} —— 重投成功回调未跑完，账还没进 LANDED</li>
 *   <li>{@link MessageState#RETRY_PERMANENT_FAILED} —— 永久失败回调未跑完，账还没进
 *       终态的虚拟表达</li>
 * </ul>
 *
 * <p><b>不在扫描范围</b>：
 * <ul>
 *   <li>{@link MessageState#RECEIVED} —— 刚收到还没进业务决策，调度器不该拿</li>
 *   <li>{@link MessageState#PENDING_CONFIRM} —— 人工确认门管的范畴，与重投调度两条轨道</li>
 *   <li>{@link MessageState#LANDED} / {@link MessageState#REJECTED} —— 终态，<b>绝不</b>再去碰</li>
 * </ul>
 *
 * <h2>幂等性</h2>
 * <p>同一进程被启停两次（正常重启、运维发版、节点漂移）应当对最终可见状态毫无影响。
 * 幂等由两层保证：
 * <ol>
 *   <li>T32 的 {@code uk_redelivery_idem_key} 唯一约束 —— 重复 {@code offer} 同 key 直接得到
 *       {@link RedeliveryQueue.OfferResult} 的 {@code DUPLICATED}，<b>不新增行</b>。</li>
 *   <li>本类的「不在扫描范围」列表 —— 即使第一次扫描成功后状态已推进，
 *       第二次扫描找不到这些行了，对最终状态毫无影响。</li>
 * </ol>
 *
 * <h2>为什么不读 redelivery_queue 去找 CONSUMED 的孤儿</h2>
 * <p>扫描台账里 {@code RETRYING} 的行就能定位到那一条对应的队列行（{@code listByLedger}）：
 * 这条信息已经够「重新调度」用了。试图从队列侧反向扫<br>
 * · {@code CONSUMED} 的行可能正当正在被另一个调度器线程持有<br>
 * · 跨进程更不能这么扫<br>
 * 因此本类一律走「台账 → 队列」的顺序。
 *
 * <h2>启动接入</h2>
 * <p>{@link cn.hospital.region.archive.Main#run(String[], java.io.PrintStream)}
 * 在装配 MLLP 监听口 / HTTP API 之前调一次 {@link #scanAndRecover(long)}，把扫描耗时记一行
 * INFO；超过 {@link #WARN_LARGE_THRESHOLD} 条时打 WARNING 提醒值班人员查延迟原因。
 */
public final class RecoveryScan {

    private static final Logger LOG = Logger.getLogger(RecoveryScan.class.getName());

    /**
     * 进入扫描范围的重投路径状态集合。
     *
     * <p>改名改枚举会改 SQL 拼出来的 IN 子句列表；{@link LedgerStore#findByStates(Set)}
     * 经 {@code null} / 空集检查后会逐元素校验，集合里出现非法态会立即抛 NPE。
     */
    public static final Set<MessageState> RETRY_PATH_STATES = Set.of(
            MessageState.PENDING_RETRY,
            MessageState.RETRYING,
            MessageState.RETRY_CONFIRMED,
            MessageState.RETRY_PERMANENT_FAILED);

    /** 扫描条目数达到该阈值时打 WARNING —— 给值班人员「恢复队列里有这么多挂着的」的可视信号。 */
    public static final int WARN_LARGE_THRESHOLD = 500;

    private final LedgerStore ledgerStore;
    private final RedeliveryQueue redeliveryQueue;
    private final Clock clock;

    public RecoveryScan(LedgerStore ledgerStore, RedeliveryQueue redeliveryQueue, Clock clock) {
        this.ledgerStore = Objects.requireNonNull(ledgerStore, "ledgerStore 不得为 null");
        this.redeliveryQueue = Objects.requireNonNull(redeliveryQueue, "重投队列不得为 null");
        this.clock = Objects.requireNonNull(clock, "时钟不得为 null");
    }

    public RecoveryScan(LedgerStore ledgerStore, RedeliveryQueue redeliveryQueue) {
        this(ledgerStore, redeliveryQueue, Clock.systemUTC());
    }

    /**
     * 一次扫描的产出 —— 报告里实际打印的就是这份 record。
     *
     * <p>三个计数之间必须满足：{@code offered + duplicated + skipped ==
     * scanned}。构造器当场校验这条不变量，把「统计数字之间对不上」挡在测试侧，
     * 不让报表层自己加。
     *
     * @param scanned      扫到的「重投路径上未终态」行数
     * @param offered      本次新加入重投队列的条数（首次启动、为 0 表示之前都已存在）
     * @param duplicated   重复 offer 命中的条数（首次重启、为 0 表示首次扫描的全是新条目）
     * @param skipped      本次没去碰的条数（终态无关行或队列允许外的状态 —— 当前实现恒为 0，
     *                     仅作「接口预留」与日后扩展）
     * @param elapsedMs    扫描耗时
     */
    public record RecoveryResult(int scanned, int offered, int duplicated, int skipped,
                                 long elapsedMs) {
        public RecoveryResult {
            if (scanned < 0 || offered < 0 || duplicated < 0 || skipped < 0 || elapsedMs < 0) {
                throw new IllegalArgumentException(
                        "RecoveryResult 计数字段不得为负: scanned=" + scanned + " offered=" + offered
                                + " duplicated=" + duplicated + " skipped=" + skipped
                                + " elapsedMs=" + elapsedMs);
            }
            if (offered + duplicated + skipped != scanned) {
                throw new IllegalStateException(
                        "RecoveryResult 计数对不上: offered+duplicated+skipped="
                                + (offered + duplicated + skipped) + " 应等于 scanned=" + scanned);
            }
        }

        /** INFO 级一行摘要，给启动日志 / 测试断言同口径用。 */
        public String summaryLine() {
            return "recovery-scan: scanned=" + scanned
                    + " offered=" + offered
                    + " duplicated=" + duplicated
                    + " skipped=" + skipped
                    + " elapsedMs=" + elapsedMs;
        }
    }

    /**
     * 跑一次扫描。
     *
     * <p>语义：
     * <ol>
     *   <li>读台账：{@code state IN RETRY_PATH_STATES}，按 id 升序</li>
     *   <li>对每行尝试 {@link RedeliveryQueue#offer}：
     *       <ul>
     *         <li>{@code state == RETRYING / RETRY_CONFIRMED / RETRY_PERMANENT_FAILED} —— 队列层
     *             与台账层已经对齐（队列侧是 CONSUMED / ACK_CONFIRMED / PERMANENT_FAILED），
     *             {@code offer} 必然被业务闸拒，转为 {@code skipped} 计数</li>
     *         <li>{@code state == PENDING_RETRY} —— 这是唯一应该入队的场景；
     *             命中已有 → {@code duplicated}，未命中 → {@code offered}</li>
     *       </ul></li>
     *   <li>记日志：扫描到的条数与耗时；超过 {@link #WARN_LARGE_THRESHOLD} 打 WARNING</li>
     * </ol>
     *
     * <p><b>不抛异常</b>：扫描异常一律走日志，调用方根据 {@link RecoveryResult} 自行决定是否报警。
     * 恢复正常比恢复齐全更重要 —— 扫到一半丢了也得让进程起得来。
     *
     * @param nowMs 扫描时刻（epoch 毫秒）；小于 0 时由本方法改走 {@link Clock#millis()}
     * @return 扫描结果
     */
    public RecoveryResult scanAndRecover(long nowMs) {
        long startedNs = System.nanoTime();
        long effectiveNow = nowMs < 0 ? clock.millis() : nowMs;

        List<LedgerRow> rows;
        try {
            rows = ledgerStore.findByStates(RETRY_PATH_STATES);
        } catch (SQLException e) {
            long elapsed = (System.nanoTime() - startedNs) / 1_000_000L;
            LOG.log(Level.SEVERE,
                    "recovery-scan 读台账失败，本次扫描作废: {0}", e.toString());
            return new RecoveryResult(0, 0, 0, 0, elapsed);
        }

        int offered = 0;
        int duplicated = 0;
        int skipped = 0;

        for (LedgerRow row : rows) {
            MessageState state = row.state();
            // 已经处于 RETRYING/RETRY_CONFIRMED/RETRY_PERMANENT_FAILED 的行说明队列侧已经对齐，
            // offer() 会按业务闸拒。把这些行计为 skipped 而非 offered，避免报表误报「这是新加入的」。
            if (state != MessageState.PENDING_RETRY) {
                skipped++;
                continue;
            }
            RedeliveryQueue.OfferResult result;
            try {
                result = redeliveryQueue.offer(row.key(), row.id(), row.batchId(),
                        "AE", state, RedeliveryQueue.EnqueueTrigger.RECOVERY_SCAN,
                        effectiveNow);
            } catch (SQLException e) {
                LOG.log(Level.WARNING,
                        "recovery-scan 单条 offer 失败，跳过本条后续处理: ledgerId={0} {1}",
                        new Object[]{row.id(), e.toString()});
                // 单条失败不能让整个扫描崩 —— 不计入 scanned，让总数自洽
                skipped++;
                continue;
            }

            if (result.isOffered()) {
                offered++;
            } else if (result.isDuplicated()) {
                duplicated++;
            } else {
                // offer 被拒：本类型 state 与 PENDING_RETRY + AE 的口径下不会发生，
                // 但接口返回的语义变了时不要静默吞掉
                skipped++;
            }
        }

        long elapsedMs = (System.nanoTime() - startedNs) / 1_000_000L;
        RecoveryResult result = new RecoveryResult(rows.size(), offered, duplicated, skipped,
                elapsedMs);
        if (result.scanned() >= WARN_LARGE_THRESHOLD) {
            LOG.log(Level.WARNING,
                    "{0} (恢复条目数超出阈值 {1}，请查延迟)",
                    new Object[]{result.summaryLine(), WARN_LARGE_THRESHOLD});
        } else {
            LOG.log(Level.INFO, "{0}", new Object[]{result.summaryLine()});
        }
        return result;
    }

    /**
     * 静态扫描入口 —— 给单测与 CLI 用。
     *
     * <p>{@link LedgerEntry} 占位以便调用方不用 import 一整组 ledger 类型 —— 测试场景只需
     * ledgerStore / redeliveryQueue / clock 三件套。
     *
     * @param nowMs {@code -1L} 代表用注入的时钟；其它值原样下传
     */
    public static RecoveryResult runOnce(LedgerStore ledgerStore, RedeliveryQueue redeliveryQueue,
                                         long nowMs, Clock clock) {
        return new RecoveryScan(ledgerStore, redeliveryQueue, clock).scanAndRecover(nowMs);
    }

    /** {@link #RETRY_PATH_STATES} 的可变快照 —— 给单测与日后扩展用。 */
    public static EnumSet<MessageState> retryPathStates() {
        EnumSet<MessageState> set = EnumSet.noneOf(MessageState.class);
        set.addAll(RETRY_PATH_STATES);
        return set;
    }
}
