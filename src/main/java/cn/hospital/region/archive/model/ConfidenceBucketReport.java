package cn.hospital.region.archive.model;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 一个自然日的置信度分桶报表 —— 本任务要交付的<b>那两个可核对指标</b>都在这里。
 *
 * <h2>为什么要有它</h2>
 * <p>信息员每天早上要决定「今天先看哪一批」。翻台账才能知道答案，
 * 而翻台账看到的是<b>已经发生</b>的事：等三条待确认摆在那儿了才知道今天有三条。
 * 本报表把同一批数据换成一个口径稳定、可提前对着昨天核对的数字，
 * 用途只有一个——<b>估算今日复核工作量</b>，以及在数字异常时早两天发现模型出了问题。
 *
 * <h2>指标一：低置信占比（= 预计进入待确认的比例）</h2>
 * <p>取<b>身份候选序列</b>的非高可信占比：身份结论不在 {@link ConfidenceBucket#HIGH}
 * 的观察点才需要人逐条看。Z 段序列的分桶<b>不</b>参与这个指标
 * —— 一个报文能贡献 5 到 8 个 Z 段观察点、1 到 3 个身份观察点，
 * 混算之后指标会被 Z 段条目数量支配，算出来的「工作量」与实际要点的条数对不上。
 *
 * <h2>指标二：降级率（= 模型不可达导致的比例）</h2>
 * <p>取<b>调用审计</b>的降级行占比，而不是观察点占比：降级率回答的是运维问题
 * 「模型今天挂没挂、挂了多久」，它的分母是<b>调用次数</b>而不是观察点。
 * 重试序列每次尝试各占一行，于是分母天然包含了重试——
 * 一个连试三次才成功的调用，在降级率里占 2/3，而不是 1/1。
 *
 * <h2>工作量按报文去重，不按观察点计</h2>
 * <p>{@link PendingConfirmEstimate} 按 {@code subjectRef} 去重后统计：
 * 一条报文贡献 8 个观察点也只算一条要看的活。这两个数字口径不同、各自成立，
 * 报表上分两行印出来，人能自己对上「8 个低置信观察点分布在 3 条报文上」。
 *
 * <p>因此本 record <b>保留原始样本</b>作为组件：两个分桶统计是从它聚合出来的，
 * 构造器会校验「统计总数 == 样本条数」，所以两份数字永远对得上，
 * 而不是靠调用方记得别算错。
 */
public record ConfidenceBucketReport(LocalDate date,
                                     ZoneId zone,
                                     ConfidenceSeriesStats zAlignment,
                                     ConfidenceSeriesStats identityCandidate,
                                     AuditTotals audit,
                                     Map<ConfidenceSeries, List<ConfidenceSample>> samples) {

    public ConfidenceBucketReport {
        Objects.requireNonNull(date, "日期不得为 null");
        Objects.requireNonNull(zone, "时区不得为 null");
        Objects.requireNonNull(zAlignment, "Z 段分桶统计不得为 null");
        Objects.requireNonNull(identityCandidate, "身份候选分桶统计不得为 null");
        Objects.requireNonNull(audit, "调用审计汇总不得为 null");
        Objects.requireNonNull(samples, "样本不得为 null");
        if (zAlignment.series() != ConfidenceSeries.Z_ALIGNMENT) {
            throw new IllegalArgumentException("Z 段统计的序列取值不符：" + zAlignment.series());
        }
        if (identityCandidate.series() != ConfidenceSeries.IDENTITY_CANDIDATE) {
            throw new IllegalArgumentException("身份统计的序列取值不符：" + identityCandidate.series());
        }
        Map<ConfidenceSeries, List<ConfidenceSample>> copy = new EnumMap<>(ConfidenceSeries.class);
        for (ConfidenceSeries s : ConfidenceSeries.values()) {
            List<ConfidenceSample> list = samples.getOrDefault(s, List.of());
            for (ConfidenceSample one : list) {
                if (one.series() != s) {
                    throw new IllegalArgumentException("样本序列与分组不符：组 " + s + " 里出现 " + one.series());
                }
            }
            copy.put(s, List.copyOf(list));
        }
        // 聚合与样本必须同源：这是「分桶条数之和 = 观察点总数」这条不变量在报表层的落点。
        if (zAlignment.total() != copy.get(ConfidenceSeries.Z_ALIGNMENT).size()) {
            throw new IllegalArgumentException("Z 段统计总数与样本条数不一致："
                    + zAlignment.total() + " != " + copy.get(ConfidenceSeries.Z_ALIGNMENT).size());
        }
        if (identityCandidate.total() != copy.get(ConfidenceSeries.IDENTITY_CANDIDATE).size()) {
            throw new IllegalArgumentException("身份统计总数与样本条数不一致："
                    + identityCandidate.total() + " != " + copy.get(ConfidenceSeries.IDENTITY_CANDIDATE).size());
        }
        samples = Map.copyOf(copy);
    }

    /**
     * 从一天的观察点与调用审计行建表。
     *
     * @param date    自然日
     * @param zone    时区（决定「这一天」的边界）
     * @param samples 全部观察点（两个序列混在一起，内部按序列分组）
     * @param records 全部调用审计行
     */
    public static ConfidenceBucketReport of(LocalDate date, ZoneId zone,
                                            List<ConfidenceSample> samples,
                                            List<ModelCallRecord> records) {
        Objects.requireNonNull(samples, "观察点不得为 null");
        Objects.requireNonNull(records, "调用审计行不得为 null");
        Map<ConfidenceSeries, List<ConfidenceSample>> grouped = new EnumMap<>(ConfidenceSeries.class);
        for (ConfidenceSeries s : ConfidenceSeries.values()) {
            grouped.put(s, new ArrayList<>());
        }
        for (ConfidenceSample s : samples) {
            grouped.get(s.series()).add(s);
        }
        return new ConfidenceBucketReport(date, zone,
                ConfidenceSeriesStats.of(ConfidenceSeries.Z_ALIGNMENT, grouped.get(ConfidenceSeries.Z_ALIGNMENT)),
                ConfidenceSeriesStats.of(ConfidenceSeries.IDENTITY_CANDIDATE,
                        grouped.get(ConfidenceSeries.IDENTITY_CANDIDATE)),
                AuditTotals.of(records),
                grouped);
    }

    /**
     * 指标一：低置信占比 —— 身份候选序列里非高可信的观察点占比。
     *
     * <p>与 {@link #pendingConfirmEstimate()} 的差别：一个是<b>条数口径</b>（观察点），
     * 一个是<b>工作量口径</b>（去重后的报文）。两者都必须给，因为它们回答的不是同一个问题：
     * 「模型今天有多准」看前者，「我今天要点多少下确认」看后者。
     */
    public double lowConfidenceRatio() {
        return identityCandidate.lowConfidenceRatio();
    }

    /** 指标二：降级率 —— 降级调用占全部调用的比例。 */
    public double degradationRatio() {
        return audit.degradationRatio();
    }

    /**
     * 指标一的报文口径版本：按 {@code subjectRef} 去重后，需要人工过目的报文数与占比。
     *
     * <p><b>判定规则</b>：一条报文需要过目，当且仅当它至少有一条身份观察点
     * 落在非高可信档，<b>或者</b>它的任何观察点带降级标记。
     * 降级单列一条规则，是因为模型不可达时留下的候选分数是确定性基线给的，
     * 数值上很可能仍在高可信档（例如跨院主键相同就给 0.90），
     * 但那份结论<b>没有被模型看过</b>，不能按自动落地处理。
     *
     * <p>引用为空的观察点<b>合并成一个不可归属的伪报文</b>，原始条数另给一个
     * {@link PendingConfirmEstimate#unattributedSamples()}。
     * 这里刻意<b>不</b>按「每条空引用各算一条报文」处理：那样会把「一条报文贡献了 3 个
     * 候选」错算成 3 份工作量，而这份报表的用途是排「今天要点多少下确认」，
     * 凭空多报三份比少报一份更糟——它会让值班的人不再相信这个数字。
     * 空引用意味着「取不到控制号、归不到具体报文」，如实说成归不回去，比猜一个数有用。
     */
    public PendingConfirmEstimate pendingConfirmEstimate() {
        Map<String, boolean[]> bySubject = new LinkedHashMap<>(); // [需要复核, 见过降级]
        int unattributed = 0;
        for (ConfidenceSample s : samplesOf(ConfidenceSeries.IDENTITY_CANDIDATE)) {
            if (s.subjectRef().isEmpty()) {
                // 归不回去的观察点不参与按报文去重，只计数
                unattributed++;
                continue;
            }
            boolean[] flags = bySubject.computeIfAbsent(s.subjectRef(), k -> new boolean[2]);
            if (s.bucket().requiresReview() || s.degraded()) {
                flags[0] = true;
            }
            if (s.degraded()) {
                flags[1] = true;
            }
        }
        int total = bySubject.size();
        int needReview = 0;
        for (boolean[] flags : bySubject.values()) {
            if (flags[0]) {
                needReview++;
            }
        }
        double ratio = total == 0 ? 0d : (double) needReview / total;
        return new PendingConfirmEstimate(total, needReview, ratio, unattributed);
    }

    /** 某序列的观察点（不可变快照）。 */
    public List<ConfidenceSample> samplesOf(ConfidenceSeries series) {
        Objects.requireNonNull(series, "序列不得为 null");
        return samples.getOrDefault(series, List.of());
    }

    /** 降级观察点占比（按观察点口径；与调用次数口径的 {@link #degradationRatio()} 并列给出）。 */
    public double degradedSampleRatio() {
        List<ConfidenceSample> identity = samplesOf(ConfidenceSeries.IDENTITY_CANDIDATE);
        if (identity.isEmpty()) {
            return 0d;
        }
        long degraded = identity.stream().filter(ConfidenceSample::degraded).count();
        return (double) degraded / identity.size();
    }

    /**
     * 终端打印用的一组行（{@code model confidence-buckets} 的输出）。
     *
     * <p>固定行序：标题 → 时区口径 → 两个序列的分桶表 → 两个指标 → 空数据提示。
     * 固定行序是为了让「昨天的报表和今天的报表」能直接 diff。
     */
    public List<String> displayLines() {
        List<String> lines = new ArrayList<>();
        lines.add("模型置信度分桶统计 · " + date + "（时区 " + zone + "）");
        lines.add("");
        lines.add(zAlignment.series().zhLabel() + "（观察点 " + zAlignment.total() + " 个）");
        lines.addAll(zAlignment.displayRows());
        lines.add("");
        lines.add(identityCandidate.series().zhLabel() + "（观察点 " + identityCandidate.total() + " 个）");
        lines.addAll(identityCandidate.displayRows());
        lines.add("");
        lines.add("指标一 · 低置信占比（身份候选序列非高可信）=" + pct(lowConfidenceRatio()));
        PendingConfirmEstimate est = pendingConfirmEstimate();
        lines.add("　　　　对应报文工作量：需复核 " + est.needsReview() + " 条 / 共 " + est.subjects() + " 条"
                + (est.unattributedSamples() == 0 ? ""
                : "（另有 " + est.unattributedSamples() + " 个观察点取不到控制号，未计入）"));
        lines.add("指标二 · 降级率（模型不可达导致的调用占比）" + pct(degradationRatio()));
        lines.add("　　　　模型调用 " + audit.totalCalls() + " 次，其中降级 " + audit.degradedCalls() + " 次；"
                + "token 合计 " + audit.totalTokens() + "，平均耗时 " + audit.avgElapsedMillis() + "ms，"
                + "最慢 " + audit.maxElapsedMillis() + "ms");
        if (zAlignment.total() == 0 && identityCandidate.total() == 0) {
            lines.add("");
            lines.add("提示：本日没有任何置信度观察点。若这与当日接收条数不符，先核对本端是否已接入模型模块，"
                    + "以及归档批次是否在模型处理之前就已全部落地。");
        }
        return lines;
    }

    private static String pct(double ratio) {
        return String.format(Locale.ROOT, "%.1f%%", ratio * 100);
    }

    /**
     * 按报文去重后的复核工作量。
     *
     * @param subjects            涉及报文条数（按 {@code subjectRef} 去重；空引用不计入）
     * @param needsReview         其中需要人工过目的条数
     * @param ratio               {@code needsReview / subjects}；{@code subjects == 0} 时为 {@code 0}
     * @param unattributedSamples 取不到控制号、归不到具体报文的观察点条数（不计入上两项）
     */
    public record PendingConfirmEstimate(int subjects, int needsReview, double ratio,
                                         int unattributedSamples) {

        public PendingConfirmEstimate {
            if (subjects < 0 || needsReview < 0 || unattributedSamples < 0) {
                throw new IllegalArgumentException("条数不得为负数：" + subjects + "/" + needsReview
                        + "/" + unattributedSamples);
            }
            if (needsReview > subjects) {
                throw new IllegalArgumentException("需复核条数不得超过涉及报文条数："
                        + needsReview + " > " + subjects);
            }
        }

        /** 三参构造（不可归属条数记 0），便于对照测试直接给数字。 */
        public PendingConfirmEstimate(int subjects, int needsReview, double ratio) {
            this(subjects, needsReview, ratio, 0);
        }

        /** 一行中文，供报表与日志；不可归属的条数不为 0 时额外说明一句。 */
        public String zhSummary() {
            String base = "预计需人工复核 " + needsReview + " 条 / 共 " + subjects + " 条报文（"
                    + String.format(Locale.ROOT, "%.1f%%", ratio * 100) + "）";
            return unattributedSamples == 0 ? base
                    : base + "；另有 " + unattributedSamples + " 个观察点取不到控制号，未计入报文数";
        }
    }

    /**
     * 调用审计汇总 —— 指标二与 token/耗时观察的来源。
     *
     * @param totalCalls       调用总次数（重试序列每次尝试各计一次）
     * @param degradedCalls    走了降级路径的次数
     * @param promptTokens     输入 token 合计（端点未返回 usage 的记 0）
     * @param completionTokens 输出 token 合计
     * @param avgElapsedMillis 平均单次耗时
     * @param maxElapsedMillis 最慢一次耗时
     */
    public record AuditTotals(int totalCalls,
                              int degradedCalls,
                              long promptTokens,
                              long completionTokens,
                              long avgElapsedMillis,
                              long maxElapsedMillis) {

        public AuditTotals {
            if (totalCalls < 0 || degradedCalls < 0) {
                throw new IllegalArgumentException("调用次数不得为负数：" + totalCalls + "/" + degradedCalls);
            }
            if (degradedCalls > totalCalls) {
                throw new IllegalArgumentException("降级次数不得超过总调用次数："
                        + degradedCalls + " > " + totalCalls);
            }
        }

        /** 从调用审计行汇总。 */
        public static AuditTotals of(List<ModelCallRecord> records) {
            Objects.requireNonNull(records, "调用审计行不得为 null");
            long prompt = 0;
            long completion = 0;
            long totalElapsed = 0;
            long maxElapsed = 0;
            int degraded = 0;
            for (ModelCallRecord r : records) {
                prompt += r.promptTokens();
                completion += r.completionTokens();
                totalElapsed += r.elapsedMillis();
                maxElapsed = Math.max(maxElapsed, r.elapsedMillis());
                if (r.degraded()) {
                    degraded++;
                }
            }
            return new AuditTotals(records.size(), degraded, prompt, completion,
                    records.isEmpty() ? 0 : totalElapsed / records.size(), maxElapsed);
        }

        /** 降级率；没有调用时为 {@code 0}。 */
        public double degradationRatio() {
            return totalCalls == 0 ? 0d : (double) degradedCalls / totalCalls;
        }

        /** token 合计。 */
        public long totalTokens() {
            return promptTokens + completionTokens;
        }
    }
}