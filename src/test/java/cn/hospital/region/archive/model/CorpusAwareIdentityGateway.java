package cn.hospital.region.archive.model;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * T26 对照实验专用网关：根据一张「用例切片 → 期望答案」表给出正确答案。
 *
 * <p>这是协议替身（{@link OpenAiCompatibleStubServer}）的<b>内进程等价物</b>，
 * 不走 HTTP 也不用 mock 库 —— {@link ModelGateway#identityCandidates} 直接被截到本类，
 * 按给定的「真值表」给出正确答案与固定置信度。
 *
 * <p>为什么能这么干：T26 是<b>对照实验</b>而不是模型评测，
 * 真正要回答的是「当模型给出正确答案 + 高置信时，基线相比模型还差多少」。
 * 用真值表驱动，相当于把模型替换成「知识充分 + 不出错的代理」，
 * 排除模型不可达、模型返回值漂移这些与对照无关的变量。
 *
 * <p>选 0.85 这个置信度（高于 {@link IdentityCandidateMatcher#UNANCHORED_CONFIDENCE_CAP}
 * 但低于 0.95 的硬锚点）有两个用意：
 * <ol>
 *   <li>让所有「正确答案」都通过 T26 的「高置信正确」口径（{@link BaselineModelComparison#MODEL_HIGH_CONF_MIN}=0.80），
 *       这样同音叠加繁体的 2 条用例也能被识别为模型高命中；
 *       不至于被「模型说 0.6 自己掂量」的逻辑挡在 0.80 门外。</li>
 *   <li>让模型组答案仍走「无锚点封顶」的逻辑 —— 模型给出的 0.85 经过
 *       {@link IdentityCandidateMatcher#gate}，被压到
 *       {@code UNANCHORED_CONFIDENCE_CAP = ZFieldAligner.DEFAULT_CONFIDENCE_THRESHOLD - 0.01}，
 *       保证「需人工确认」的红线没被偷偷绕过。</li>
 * </ol>
 *
 * <p>本类只服务 T26 对照实验，<b>不</b>作为产品代码的一部分。
 * 它出现在测试源码而非主源码，是为了让产品代码的依赖图保持干净：
 * 对照实验引用真实产品类（{@link IdentityCandidateMatcher}），反过来不成立。
 */
final class CorpusAwareIdentityGateway implements ModelGateway {

    /** 选定的模型置信度 —— 见类注释。 */
    static final double ANSWER_CONFIDENCE = 0.85d;

    private final Map<InboundIdentity, String> expectedByInbound;
    private int alignCallCount;
    private int identityCallCount;

    /**
     * 构造时给定真值表 —— key 为 {@link InboundIdentity}，value 为正确答案的院内主键。
     * 这里传的是「同一份用例」的切片，与 {@link BaselineModelComparison#compare} 共用。
     */
    CorpusAwareIdentityGateway(List<VariantEvalCorpus.Case> cases) {
        this.expectedByInbound = new HashMap<>(cases.size());
        for (VariantEvalCorpus.Case c : cases) {
            expectedByInbound.put(c.inbound(), c.expectedLocalMrn());
        }
    }

    int alignCallCount() {
        return alignCallCount;
    }

    int identityCallCount() {
        return identityCallCount;
    }

    @Override
    public ZFieldMap alignZFields(List<ZSegment> zSegments) {
        Objects.requireNonNull(zSegments, "Z 段列表不得为 null");
        alignCallCount++;
        // 对照实验不评估 Z 段对齐；返回空映射即与「无待对齐字段」的现实一致。
        return ZFieldMap.empty();
    }

    @Override
    public Candidates identityCandidates(String pid3, String pid5, String pid7, LocalPatientIndex localIndex) {
        identityCallCount++;
        Objects.requireNonNull(pid5, "姓名不得为 null");
        Objects.requireNonNull(localIndex, "院内候选切片不得为 null");
        String expected = expectedByInbound.get(new InboundIdentity(pid3, pid5, pid7, ""));
        if (expected == null) {
            // 用例不在表里 —— 视为对照实验的对照组错配，给空候选让下游判为 noCandidate。
            return Candidates.empty();
        }
        LocalPatientIndex.LocalPatient hit = localIndex.byMrn(expected);
        if (hit == null) {
            // 正确答案不在给定的切片里 —— 与「正确答案不唯一」同义，给空候选。
            return Candidates.empty();
        }
        return new Candidates(List.of(new IdentityCandidate(
                hit.localMrn(),
                ANSWER_CONFIDENCE,
                List.of(
                        new IdentityCandidate.Evidence(IdentityEvidenceField.NAME.wire(),
                                "语义识别为同一姓名（模型组替身，按真值给出）"),
                        new IdentityCandidate.Evidence(IdentityEvidenceField.BIRTH_DATE.wire(),
                                "出生日期与院内记录一致（本机核对）")))));
    }
}
