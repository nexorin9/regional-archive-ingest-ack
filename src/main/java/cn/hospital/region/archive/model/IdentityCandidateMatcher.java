package cn.hospital.region.archive.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * 身份候选闸门 —— 模型在身份这件事上的<b>第二件真活</b>（第一件是 {@link ZFieldAligner}）。
 *
 * <h2>候选，不是结论</h2>
 * <p>本类只回答「这可能是谁、凭什么」。它<b>不写 HIS、不改台账、不推进任何状态</b>：
 * {@code 待确认 → 已落地} 只由确认门（{@code ConfirmGate}，T30）在人工确认动作之后推进。
 * 本类连 {@code PatientIndexWriter} 端口都不持有 —— 不是靠自觉，是结构上没有这个引用，
 * 由 {@code IdentityCandidateMatcherTest} 反射断言守着。
 *
 * <h2>三件事的顺序不能换</h2>
 * <ol>
 *   <li><b>先证件冲突扫描（确定性，零调用）</b>：报文证件摘要与某条院内记录不一致，
 *       而两者在跨院主键或「姓名 + 出生日期」上对得上 —— 这时整条不产出候选、按拒收处理。
 *       这条不能交给模型：证件一致与否是<b>硬校验</b>，把它交给一个概率性的东西，
 *       等于把「绝对不能错」降级成「多数时候不错」。冲突一旦成立就没必要再问模型，
 *       这一趟省下来的调用是真的省下来了。</li>
 *   <li><b>再证件一致出候选（确定性，零调用）</b>：摘要对上了就是一个人对一个人，
 *       置信度直接置高并标记 {@link IdentityAnchorLevel#IDCARD}。</li>
 *   <li><b>最后才问模型</b>：简繁、同音错别字、姓名空格三种变体规则判不了，
 *       交给模型在已收窄的切片里排序，出来再过一遍闸门（词表外依据丢弃、
 *       无依据候选丢弃、无锚点置信度封顶）。</li>
 * </ol>
 *
 * <h2>为什么降级在这里不抛、而 Z 段对齐那里抛</h2>
 * <p>本类持有的是 {@link ResilientModelGateway}（全系统唯一把模型失败收敛成业务结论的出口），
 * 模型不可达时收敛成 {@link IdentityMatchReport.Outcome#MODEL_DEGRADED} 并<b>继续给确定性结论</b>；
 * 而 {@link ZFieldAligner} 持有严格网关、原样上抛。区别不在风格，在<b>结论的性质</b>：
 * 证件一致与否是确定性且带安全性质的结论，模型挂了不能把它一起弄丢
 * —— 丢了它，这条本该拒收的报文会被当成「只是没结论」，从而走进待确认等人点确认。
 * 这是本项目里唯一一处「降级比抛异常更安全」的地方，因此写明理由而不是照抄上一层的做法。
 *
 * <h2>无锚点即封顶</h2>
 * <p>平台没给证件时，任何候选的置信度都被压到 {@link #UNANCHORED_CONFIDENCE_CAP}
 * —— 恰好低于 {@link ZFieldAligner#DEFAULT_CONFIDENCE_THRESHOLD}。这样「没有证件就不能自动合档」
 * 这条规则就不必散落在每个调用点里比对阈值，任何下游按同一个阈值判断都自动是对的。
 *
 * <h2>日志里不出现人</h2>
 * <p>本类会打 WARN（丢弃候选、发现冲突），日志只写<b>院内主键与字段名</b>，
 * 不写姓名、证件号、报文原文：待确认清单与台账导出都是同一套约束（T40 守着那条线）。
 */
public final class IdentityCandidateMatcher {

    private static final Logger LOG = Logger.getLogger(IdentityCandidateMatcher.class.getName());

    /** 证件摘要一致时的置信度：够得上「一个人对一个人」，但仍不到 1.0。 */
    public static final double ANCHORED_CONFIDENCE = 0.95d;

    /** 跨院主键与院内主键字面相同时的置信度。 */
    public static final double CROSS_REF_CONFIDENCE = 0.90d;

    /**
     * 无硬锚点（平台没给证件）时的置信度上限。
     *
     * <p>取合档阈值减 {@code 0.01}：比阈值低一丁点，就低到<b>任何</b>按
     * {@link ZFieldAligner#DEFAULT_CONFIDENCE_THRESHOLD} 判断的下游都自动判为「需人工确认」。
     * 写成 {@code 0.74} 的话，阈值一改这里就跟着失效，而失效的表现是有人被自动合了档。
     */
    public static final double UNANCHORED_CONFIDENCE_CAP =
            ZFieldAligner.DEFAULT_CONFIDENCE_THRESHOLD - 0.01d;

    static {
        if (UNANCHORED_CONFIDENCE_CAP >= ZFieldAligner.DEFAULT_CONFIDENCE_THRESHOLD) {
            throw new IllegalStateException("无锚点置信度上限必须低于合档阈值，否则缺证件的候选会被自动合档");
        }
    }

    private final ResilientModelGateway model;

    public IdentityCandidateMatcher(ResilientModelGateway model) {
        this.model = Objects.requireNonNull(model, "模型网关不得为 null");
    }

    /**
     * 比对一次身份，给出候选与依据。
     *
     * @param inbound    报文里的来访居民（PID-3 / PID-5 / PID-7 + 证件摘要）
     * @param localIndex 院内候选切片（已由确定性基线按字面条件收窄，不是全量索引）
     * @return 候选报告；<b>本方法不抛业务异常，也不返回 null</b>
     */
    public IdentityMatchReport match(InboundIdentity inbound, LocalPatientIndex localIndex) {
        Objects.requireNonNull(inbound, "来访身份不得为 null");
        Objects.requireNonNull(localIndex, "院内候选切片不得为 null");

        if (localIndex.isEmpty()) {
            return IdentityMatchReport.noCandidate(inbound.anchorHint(),
                    "院内候选切片为空：本地没有可比对的记录，通常意味着这位居民在本院首次建档");
        }

        // —— 第 0 遍：脏数据分流。姓名与出生日期都没有时，比对这件事本身无从谈起 ——
        // 这时候给一个「最像的」等于凭空认亲，而为它付一次模型调用是白花钱：
        // 模型能拿到的输入和这里一样，也是空的。判定在
        // DirtyDataPolicy 里，各调用点不各写一遍 if。
        DirtyDataPolicy.Assessment quality = DirtyDataPolicy.assess(inbound);
        if (quality.skipModel()) {
            LOG.warning("身份候选：报文字段不足以比对，不产出候选、不调用模型（缺 "
                    + quality.missing() + "；姓名、证件号与报文原文不入日志）");
            return IdentityMatchReport.noCandidate(inbound.anchorHint(), quality.note());
        }

        // —— 第一遍：证件冲突。确定性判定，且冲突成立就没必要问模型 ——
        List<IdentityMatchReport.IdCardConflict> conflicts = idCardConflicts(inbound, localIndex);
        if (!conflicts.isEmpty()) {
            List<String> mrns = new ArrayList<>(conflicts.size());
            for (IdentityMatchReport.IdCardConflict c : conflicts) {
                mrns.add(c.localMrn());
            }
            LOG.warning("身份候选：证件摘要与院内记录冲突，整条不产出候选，待人工线下核对；"
                    + "冲突记录数=" + conflicts.size() + "，院内主键=" + mrns
                    + "（姓名、证件号与报文原文不入日志）");
            return IdentityMatchReport.conflictRejected(inbound.anchorHint(), conflicts);
        }

        // —— 第二遍：证件一致。硬锚点，置信度置高，同样不需要模型 ——
        List<LocalPatientIndex.LocalPatient> anchored = anchoredByIdCard(inbound, localIndex);
        if (!anchored.isEmpty()) {
            List<IdentityCandidate> pinned = new ArrayList<>(anchored.size());
            for (LocalPatientIndex.LocalPatient p : anchored) {
                pinned.add(new IdentityCandidate(p.localMrn(), ANCHORED_CONFIDENCE,
                        deterministicEvidence(inbound, p)));
            }
            return IdentityMatchReport.candidatesReady(IdentityAnchorLevel.IDCARD,
                    new Candidates(pinned),
                    "证件摘要与院内记录一致，按硬锚点直接给出候选（未调用模型）");
        }

        IdentityAnchorLevel anchor = anchorLevelOf(inbound, localIndex);

        // —— 第三遍：规则解决不了的变体，交给模型在给定切片内排序 ——
        ModelOutcome<Candidates> outcome =
                model.identityCandidates(inbound.patientRef(), inbound.name(), inbound.birthDate(), localIndex);
        if (outcome.degraded()) {
            String code = outcome.degradedResult().orElseThrow().errorCode().auditToken();
            Candidates deterministic = new Candidates(deterministicCandidates(inbound, localIndex));
            LOG.warning("身份候选：模型不可达（" + code + "），改用确定性核对结果，"
                    + deterministic.size() + " 条候选需人工确认；拆帧、解析与回执不受影响");
            return withQualityNote(IdentityMatchReport.modelDegraded(anchor, deterministic,
                    "模型不可达（" + code + "），候选只保留本机可核对的确定性命中项；"
                            + "该条进待确认并标注降级原因"), quality);
        }

        Candidates gated = gate(outcome.value(), inbound, localIndex, anchor);
        if (gated.isEmpty()) {
            return withQualityNote(IdentityMatchReport.noCandidate(anchor,
                    "模型未给出可用的候选：候选为空，或全部候选因无依据/不在给定切片内被剔除"), quality);
        }
        return withQualityNote(IdentityMatchReport.candidatesReady(anchor, gated,
                "候选由模型在给定切片内排序，逐条带依据字段；须人工确认后才落地"), quality);
    }

    /**
     * 把「本报文缺了哪个字段」补进说明。
     *
     * <p>只在缺字段时补：字段齐全的报文不需要在每条候选后面都挂一句「字段都齐」。
     * 缺失说明必须跟着结论一起走到待确认台面——信息员看到的是「候选有 2 条，
     * 但这条报文没有出生日期」，而不是「候选有 2 条」外加一处没人会读的日志。
     */
    private static IdentityMatchReport withQualityNote(IdentityMatchReport report,
                                                       DirtyDataPolicy.Assessment quality) {
        if (quality.verdict() != DirtyDataPolicy.Verdict.PARTIAL_KEYS) {
            return report;
        }
        return new IdentityMatchReport(report.anchor(), report.outcome(), report.candidates(),
                report.conflicts(), report.note() + "；" + quality.note());
    }

    // ============================================================== 各段判定

    /** 证件冲突扫描：证件不一致 <b>且</b>别的硬字段对得上，才算冲突。 */
    private static List<IdentityMatchReport.IdCardConflict> idCardConflicts(InboundIdentity inbound,
                                                                          LocalPatientIndex localIndex) {
        List<IdentityMatchReport.IdCardConflict> out = new ArrayList<>();
        if (!inbound.hasIdCard()) {
            return out;
        }
        for (LocalPatientIndex.LocalPatient p : localIndex.patients()) {
            if (p.idCardDigest8().isEmpty() || p.idCardDigest8().equals(inbound.idCardDigest8())) {
                continue;
            }
            String matchedOn = strongKeyMatch(inbound, p);
            if (matchedOn != null) {
                out.add(new IdentityMatchReport.IdCardConflict(p.localMrn(), matchedOn));
            }
        }
        return out;
    }

    /**
     * 「本来该是同一个人」的确定性判据；不成立返回 {@code null}。
     *
     * <p>只认两种：跨院主键与院内主键字面相同；姓名与出生日期<b>同时</b>一致。
     * 单靠姓名一致不算 —— 全国叫「张伟」的能把一个县的人口数填满，
     * 拿姓名当冲突判据的结果就是误拒一批本来正确的档案。
     */
    private static String strongKeyMatch(InboundIdentity inbound, LocalPatientIndex.LocalPatient p) {
        if (!inbound.patientRef().isEmpty() && inbound.patientRef().equals(p.localMrn())) {
            return "跨院主键与院内主键字面相同";
        }
        if (IdentityText.sameName(inbound.name(), p.name())
                && IdentityText.sameBirthDate(inbound.birthDate(), p.birthDate())) {
            return "姓名与出生日期同时一致";
        }
        return null;
    }

    /** 证件摘要命中的院内记录（可能是多条：院内索引本身可能有重复档案）。 */
    private static List<LocalPatientIndex.LocalPatient> anchoredByIdCard(InboundIdentity inbound,
                                                                         LocalPatientIndex localIndex) {
        List<LocalPatientIndex.LocalPatient> out = new ArrayList<>();
        if (!inbound.hasIdCard()) {
            return out;
        }
        for (LocalPatientIndex.LocalPatient p : localIndex.patients()) {
            if (p.idCardDigest8().equals(inbound.idCardDigest8())) {
                out.add(p);
            }
        }
        return out;
    }

    /** 比对之后才知道的锚点等级：证件那条已经在前面处理过了。 */
    private static IdentityAnchorLevel anchorLevelOf(InboundIdentity inbound, LocalPatientIndex localIndex) {
        if (!inbound.patientRef().isEmpty() && localIndex.byMrn(inbound.patientRef()) != null) {
            return IdentityAnchorLevel.CROSS_REF;
        }
        return IdentityAnchorLevel.MISSING;
    }

    /** 模型结果过闸：词表外依据丢弃、无依据候选丢弃、无锚点封顶、补本机可核对的依据。 */
    private static Candidates gate(Candidates raw, InboundIdentity inbound,
                                   LocalPatientIndex localIndex, IdentityAnchorLevel anchor) {
        List<IdentityCandidate> kept = new ArrayList<>(raw.size());
        List<String> dropped = new ArrayList<>();
        for (IdentityCandidate c : raw.candidates()) {
            LocalPatientIndex.LocalPatient local = localIndex.byMrn(c.localMrn());
            if (local == null) {
                // 网关已经过滤过一次，这里是第二道：候选主键必须来自给定切片，
                // 凭空造出来的院内主键进了确认台就等于凭空多一个人。
                dropped.add(c.localMrn() + "(不在切片内)");
                continue;
            }
            List<IdentityCandidate.Evidence> evidence = keptEvidence(c, dropped, local.localMrn());
            evidence.addAll(deterministicEvidence(inbound, local));
            if (evidence.isEmpty()) {
                dropped.add(c.localMrn() + "(无任何可核对依据)");
                continue;
            }
            double confidence = c.confidence();
            if (anchor == IdentityAnchorLevel.CROSS_REF && local.localMrn().equals(inbound.patientRef())) {
                confidence = Math.max(confidence, CROSS_REF_CONFIDENCE);
            }
            if (anchor == IdentityAnchorLevel.MISSING) {
                if (confidence > UNANCHORED_CONFIDENCE_CAP) {
                    confidence = UNANCHORED_CONFIDENCE_CAP;
                    evidence.add(new IdentityCandidate.Evidence(IdentityEvidenceField.ID_CARD_DIGEST8.wire(),
                            "平台未提供证件摘要，无硬锚点：置信度已按无锚点上限收敛，本条只能人工确认"));
                }
            }
            kept.add(new IdentityCandidate(c.localMrn(), confidence, evidence));
        }
        if (!dropped.isEmpty()) {
            LOG.warning("身份候选：剔除不可用候选 " + dropped.size() + " 条，明细=" + dropped
                    + "（依据原文与姓名不入日志）");
        }
        return new Candidates(kept);
    }

    /** 只保留落在封闭词表内的模型依据。 */
    private static List<IdentityCandidate.Evidence> keptEvidence(IdentityCandidate c,
                                                                   List<String> dropped, String localMrn) {
        List<IdentityCandidate.Evidence> out = new ArrayList<>(c.evidence().size());
        for (IdentityCandidate.Evidence e : c.evidence()) {
            Optional<IdentityEvidenceField> field = IdentityEvidenceField.fromWire(e.field());
            if (field.isPresent()) {
                out.add(new IdentityCandidate.Evidence(field.get().wire(), e.detail()));
            } else {
                dropped.add(localMrn + "(依据字段不在词表内: " + e.field() + ")");
            }
        }
        return out;
    }

    /**
     * 本机就能核对出来的依据 —— 不问模型、也不依赖模型说什么。
     *
     * <p>模型可能给高分也可能给低分，但「出生日期一样不一样」是查一下就知道的事。
     * 把这类事实由本机补进依据里，信息员看到的就是一条<b>可复核</b>的判断依据，
     * 而不是一句需要他自己再查一遍的说法。
     */
    private static List<IdentityCandidate.Evidence> deterministicEvidence(InboundIdentity inbound,
                                                                         LocalPatientIndex.LocalPatient local) {
        List<IdentityCandidate.Evidence> out = new ArrayList<>(4);
        if (inbound.hasIdCard() && !local.idCardDigest8().isEmpty()) {
            if (local.idCardDigest8().equals(inbound.idCardDigest8())) {
                out.add(new IdentityCandidate.Evidence(IdentityEvidenceField.ID_CARD_DIGEST8.wire(),
                        "证件摘要与院内记录一致（硬锚点，不经模型）"));
            } else {
                out.add(new IdentityCandidate.Evidence(IdentityEvidenceField.ID_CARD_DIGEST8.wire(),
                        "院内记录的证件摘要与平台不一致，且其余字段不足以判定为同一人，不构成硬锚点"));
            }
        }
        if (!inbound.patientRef().isEmpty() && inbound.patientRef().equals(local.localMrn())) {
            out.add(new IdentityCandidate.Evidence(IdentityEvidenceField.PATIENT_REF.wire(),
                    "区域平台主键与院内主键字面相同"));
        }
        if (inbound.hasName() && !local.name().isEmpty()) {
            out.add(new IdentityCandidate.Evidence(IdentityEvidenceField.NAME.wire(),
                    IdentityText.nameVariantNote(inbound.name(), local.name()) + "（本机核对，不经模型）"));
        }
        if (inbound.hasBirthDate() && !local.birthDate().isEmpty()) {
            boolean same = IdentityText.sameBirthDate(inbound.birthDate(), local.birthDate());
            out.add(new IdentityCandidate.Evidence(IdentityEvidenceField.BIRTH_DATE.wire(),
                    (same ? "出生日期与院内记录一致" : "出生日期与院内记录不一致") + "（本机核对，不经模型）"));
        }
        return out;
    }

    /**
     * 模型不可达时仍可给的候选：只保留本机判得出「很可能同一人」的那些
     * （跨院主键字面相同，或姓名与出生日期同时一致）。
     *
     * <p>不按相似度铺一批候选出来充数：没有排序依据时给十个候选，
     * 信息员只能从头看到尾，等于把模型省下的时间又还回去。
     */
    private static List<IdentityCandidate> deterministicCandidates(InboundIdentity inbound,
                                                                  LocalPatientIndex localIndex) {
        List<IdentityCandidate> out = new ArrayList<>();
        for (LocalPatientIndex.LocalPatient p : localIndex.patients()) {
            boolean crossRef = !inbound.patientRef().isEmpty() && inbound.patientRef().equals(p.localMrn());
            boolean samePerson = IdentityText.sameName(inbound.name(), p.name())
                    && IdentityText.sameBirthDate(inbound.birthDate(), p.birthDate());
            if (!crossRef && !samePerson) {
                continue;
            }
            double confidence = crossRef ? CROSS_REF_CONFIDENCE : ANCHORED_CONFIDENCE;
            if (!inbound.hasIdCard()) {
                confidence = Math.min(confidence, UNANCHORED_CONFIDENCE_CAP);
            }
            out.add(new IdentityCandidate(p.localMrn(), confidence,
                    deterministicEvidence(inbound, p)));
        }
        return out;
    }
}