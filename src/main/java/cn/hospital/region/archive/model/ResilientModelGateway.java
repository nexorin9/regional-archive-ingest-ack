package cn.hospital.region.archive.model;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 可降级的模型出口 —— <b>全系统唯一一条把「模型失败」变成「业务结论」的地方</b>。
 *
 * <p>它包住一个只会抛异常的严格网关（{@link ModelGateway}），把
 * {@link ModelCallException} 收敛成 {@link ModelOutcome}：要么值，要么 {@link DegradedResult}。
 * 业务层只依赖本类，因而<b>不可能</b>出现「某个调用点忘了 catch、把异常吞了当成功用」这种情况。
 *
 * <h2>本类的三条不变量</h2>
 * <ol>
 *   <li><b>不吞异常类型信息</b>：五类错误码原样带进 {@link DegradedResult}，
 *       排障时能直接区分「密钥失效」与「网络不通」；</li>
 *   <li><b>只重试有意义的</b>：判定完全交给 {@link ModelRetryPolicy}，本类不自己判断；</li>
 *   <li><b>不推进任何业务状态</b>：本类只返回对象，一个状态都不碰。
 *       推进由 {@code ArchiveIngestService} 完成，且只允许推进到<b>待确认</b>。</li>
 * </ol>
 *
 * <h2>被中断时不重试</h2>
 * <p>退避要真睡。工作线程被中断（服务停机、上层取消）时 {@code sleep} 立刻抛
 * {@link InterruptedException}——此时再重试只会让停机卡住。本类<b>当场收敛成降级</b>并
 * 把中断标志还回去，让上层看到「这条没拿到模型结论」而不是「这次调用被安静地取消了」。
 *
 * <p>不选「静默丢弃这条报文」：那会让台账上少一行，而次日对账时数字对不上、现场查不出原因。
 */
public final class ResilientModelGateway {

    private static final Logger LOG = Logger.getLogger(ResilientModelGateway.class.getName());

    private final ModelGateway delegate;
    private final ModelRetryPolicy policy;

    public ResilientModelGateway(ModelGateway delegate, ModelGatewayConfig config) {
        this(delegate, new ModelRetryPolicy(config.maxRetries()));
    }

    public ResilientModelGateway(ModelGateway delegate, ModelRetryPolicy policy) {
        this.delegate = Objects.requireNonNull(delegate, "被包的模型网关不得为 null");
        this.policy = Objects.requireNonNull(policy, "重试策略不得为 null");
    }

    /** 被包的严格网关（T20 的契约测试仍直接用它验协议形状）。 */
    public ModelGateway delegate() {
        return delegate;
    }

    public ModelRetryPolicy policy() {
        return policy;
    }

    /** Z 段语义对齐（可降级）。 */
    public ModelOutcome<ZFieldMap> alignZFields(List<ZSegment> zSegments) {
        return call(OpenAiCompatibleGateway.OP_Z_FIELD_MAP,
                () -> delegate.alignZFields(zSegments));
    }

    /** 身份候选（可降级）。 */
    public ModelOutcome<Candidates> identityCandidates(String pid3, String pid5, String pid7,
                                                        LocalPatientIndex localIndex) {
        return call(OpenAiCompatibleGateway.OP_IDENTITY_CANDIDATES,
                () -> delegate.identityCandidates(pid3, pid5, pid7, localIndex));
    }

    /**
     * 调用一次模型，按策略决定重试，最终收敛为 {@link ModelOutcome}。
     *
     * <p>这是<b>全系统唯一</b>捕获 {@link ModelCallException} 的地方。
     * 若日后另有一处直接用严格网关，异常会照原样上抛到主路径，那条路径不会降级、只会中断——
     * 这是刻意的：新增调用点时应当先想清楚要不要走本类。
     *
     * <p>返回的 {@code Succeeded} 里拿到的就是模型原样给出的结构化对象，
     * <b>没有做任何兜底填充</b>：模型给不出候选就是给不出，
     * 编一个「默认第一候选」出来等于凭空造一个身份。
     */
    public <T> ModelOutcome<T> call(String operation, Supplier<T> call) {
        Objects.requireNonNull(operation, "操作名不得为 null");
        Objects.requireNonNull(call, "调用不得为 null");

        int attempts = 0;
        while (true) {
            attempts++;
            try {
                return ModelOutcome.succeeded(call.get());
            } catch (ModelCallException e) {
                ModelErrorCode code = e.errorCode();
                if (!policy.shouldRetry(code, attempts)) {
                    return degrade(operation, code, attempts, e.getMessage());
                }
                try {
                    policy.awaitBackoff(attempts);
                } catch (InterruptedException interrupted) {
                    // 停机中的工作线程：不等了，也不再试一次 —— 但这条仍要有业务结论。
                    Thread.currentThread().interrupt();
                    return degrade(operation, code, attempts,
                            "重试等待被中断：" + e.getMessage());
                }
                int failedAt = attempts;
                LOG.log(Level.FINE, () -> "模型调用失败，按策略重试: op=" + operation
                        + " code=" + code.auditToken() + " 第 " + failedAt + " 次失败");
            }
        }
    }

    /** 打一条 WARNING 并返回降级结果——降级是正常处置，噪音要够低但不能没有。 */
    private static <T> ModelOutcome<T> degrade(String operation, ModelErrorCode code,
                                               int attempts, String detail) {
        DegradedResult result = DegradedResult.of(code, operation, attempts, detail);
        LOG.log(Level.WARNING, result.reason());
        return ModelOutcome.degraded(result);
    }
}