package cn.hospital.region.archive.ledger;

import java.util.Objects;

/**
 * {@link IdempotencyGuard#tryAcquire} 的结果 —— 「这条报文本端是不是第一次收到」。
 *
 * <h2>为什么不是布尔值</h2>
 * <p>接入主路径拿到结果后要做两件不同的事，两件事都要求知道<b>是哪一层拦下的</b>：
 * <ul>
 *   <li>{@link Acquired} → 继续跑业务（对齐本地字段、给身份候选），失败要能整条回执；</li>
 *   <li>{@link Duplicate} → <b>不</b>跑业务，直接按 {@link Duplicate#currentState()} 回执。
 *       这里回当前态而不是固定回 AA：同一条报文第二次到达时它可能已推进到待确认甚至已落地，
 *       对端重复收到两条互相矛盾的结果时，对账会永远平不了。</li>
 * </ul>
 * 所以这里返回的不是 {@code true/false}，而是一个带 <b>原行主键</b>与<b>当前态</b>的值对象。
 *
 * <h2>命中的是哪一层</h2>
 * <p>{@link DuplicateOrigin} 把「先查命中」（{@link DuplicateOrigin#PRE_READ}）与
 * 「唯一约束兜底」（{@link DuplicateOrigin#UNIQUE_CONSTRAINT}）分开记：
 * <ul>
 *   <li><b>先查命中</b>是常态（对端重推、网络抖动后重发、重启续跑），走一次 SELECT 就返回，
 *       不开事务、不撞约束、不回滚，代价最低。</li>
 *   <li><b>唯一约束兜底</b>是并发窗口：两个线程同时先查都查不到、然后同时插入，
 *       靠 {@code uk_ledger_idem_key} 让其中一个失败并被翻译成 Duplicate。
 *       这一层的命中数应当<b>接近 0</b>；它一旦常态上升，说明先查被绕过了或在长事务里失效，
 *       此时每条重复报文都要付一次事务回滚的代价 —— 这是可观测的运维信号。</li>
 * </ul>
 */
public sealed interface AcquireResult permits AcquireResult.Acquired, AcquireResult.Duplicate {

    /** 台账行主键（新插入的用新 id，重复的用原 id）。 */
    long ledgerId();

    /** 幂等键。 */
    IdempotencyKey key();

    /** 台账行当前状态。 */
    MessageState state();

    /** 本次是否需要继续跑业务处理（= 本端第一次收到这条报文）。 */
    boolean shouldRunBusiness();

    /** 本端第一次收到，已入账，可以继续跑业务。 */
    record Acquired(long ledgerId, IdempotencyKey key, MessageState initialState) implements AcquireResult {

        public Acquired {
            if (ledgerId <= 0) {
                throw new IllegalArgumentException("新入账的行必须持有正主键，实际=" + ledgerId);
            }
            Objects.requireNonNull(key, "key 不得为 null");
            Objects.requireNonNull(initialState, "initialState 不得为 null");
        }

        @Override
        public long ledgerId() {
            return ledgerId;
        }

        @Override
        public IdempotencyKey key() {
            return key;
        }

        @Override
        public MessageState state() {
            return initialState;
        }

        @Override
        public boolean shouldRunBusiness() {
            return true;
        }
    }

    /** 该幂等键此前已入账，本次<b>没有</b>新插入任何行。 */
    record Duplicate(long ledgerId, IdempotencyKey key, MessageState currentState,
                     DuplicateOrigin origin) implements AcquireResult {

        public Duplicate {
            if (ledgerId <= 0) {
                throw new IllegalArgumentException("已存在的行必须持有正主键，实际=" + ledgerId);
            }
            Objects.requireNonNull(key, "key 不得为 null");
            Objects.requireNonNull(currentState, "currentState 不得为 null");
            origin = Objects.requireNonNull(origin, "origin 不得为 null");
        }

        @Override
        public long ledgerId() {
            return ledgerId;
        }

        @Override
        public IdempotencyKey key() {
            return key;
        }

        @Override
        public MessageState state() {
            return currentState;
        }

        /** 重复到达<b>不是</b>错误 —— 主路径必须继续跑完并回执，只是不能再跑一次业务。 */
        @Override
        public boolean shouldRunBusiness() {
            return false;
        }
    }

    /** 命中了哪一层双保险。 */
    enum DuplicateOrigin {

        /** 先查命中：SELECT 到已有行，直接按当前态回执，不开事务。 */
        PRE_READ,

        /** 唯一约束兜底：先查没查到、插入时被 {@code uk_ledger_idem_key} 拦下。 */
        UNIQUE_CONSTRAINT
    }
}