package cn.hospital.region.archive.model;

/**
 * 脏数据分流口径 —— <b>哪些报文不值得为它付一次模型调用</b>。
 *
 * <h2>本类只回答「能不能比」，不回答「是不是同一个人」</h2>
 * <p>答案永远是「不是同一个人」——身份合档只走确认门。本类给出的是另一样东西：
 * 一条报文的可比字段够不够，够就往下走，不够就在<b>不花钱、不可能崩</b>的位置停下来。
 *
 * <h2>四类脏数据的落点各不相同</h2>
 * <table border="1">
 *   <caption>四类脏数据与它们的落点</caption>
 *   <tr><th>脏数据</th><th>落点</th><th>为什么</th></tr>
 *   <tr><td>姓名与出生日期<b>都</b>为空</td>
 *       <td>{@link Verdict#NO_COMPARABLE_KEYS}：不产出候选、进待确认</td>
 *       <td>一个键都没有，比对这件事本身无从谈起。给它一个「最像的」等于凭空认亲。</td></tr>
 *   <tr><td>只有姓名或只有出生日期</td>
 *       <td>{@link Verdict#PARTIAL_KEYS}：照常比，note 里写明缺哪一项</td>
 *       <td>一个硬键仍然能排除掉绝大多数候选，比完全不比强。</td></tr>
 *   <tr><td>字段超长（&gt; 256 码点）</td>
 *       <td>{@link ModelInputPolicy} 截断，告警随报文走到确认台</td>
 *       <td>见该类注释：截断而不是拒收。</td></tr>
 *   <tr><td>解码出现替换字符（U+FFFD）</td>
 *       <td>照常比对，<b>标记为有损</b></td>
 *       <td>见 {@code FrameDecoder}：有损解码不抛异常，但必须让人知道这一条不可全信。</td></tr>
 * </table>
 *
 * <h2>为什么不把这几条写成 if 散在调用点</h2>
 * <p>因为「空姓名 + 空出生日期」这件事有两个调用点会各自碰到：身份候选比对（{@code match}）
 * 与后续的确认台展示。两处各写一遍 if 的话，其中一处将来被人改掉，
 * 表现是「待确认台上一条报文写着『未给出候选』，但没人说得清为什么」——
 * 而「为什么没有候选」正是信息员决定「新建档案」还是「找平台重推」的唯一依据。
 *
 * <p><b>不产出候选 ≠ 静默丢弃</b>：本类的判定一律落到
 * {@link IdentityMatchReport.Outcome#NO_CANDIDATE}，而该处置
 * {@link IdentityMatchReport#requiresHumanConfirmation()} 为 {@code true}，
 * 条目会进待确认台面等人处理。
 */
public final class DirtyDataPolicy {

    private DirtyDataPolicy() {
    }

    /** 一条入站身份的可比性判定。 */
    public enum Verdict {

        /** 姓名与出生日期都在，正常走模型候选比对。 */
        USABLE("可比对", false),

        /** 只有一个硬键：照常比，但依据里必须写明缺了哪一项。 */
        PARTIAL_KEYS("单键可比对", false),

        /** 一个硬键都没有：没有可比的东西，不产出候选也不调模型。 */
        NO_COMPARABLE_KEYS("无可比对字段", true);

        private final String zhLabel;
        private final boolean skipModel;

        Verdict(String zhLabel, boolean skipModel) {
            this.zhLabel = zhLabel;
            this.skipModel = skipModel;
        }

        public String zhLabel() {
            return zhLabel;
        }

        /** 是否应当<b>跳过模型调用</b>（其余情况照常调）。 */
        public boolean skipModel() {
            return skipModel;
        }
    }

    /**
     * 判定结果。
     *
     * @param verdict 比可比性判定
     * @param note    一句中文说明：缺了什么、为什么这么判（进待确认台面，信息员直接能读）
     * @param missing 本次缺失的字段名（PID-5 姓名 / PID-7 出生日期）；都齐全时为空
     */
    public record Assessment(Verdict verdict, String note, String missing) {

        /** 是否跳过模型调用。 */
        public boolean skipModel() {
            return verdict.skipModel();
        }
    }

    /**
     * 评估一条入站身份的可比性。
     *
     * @param inbound 报文里的来访居民（可为 {@code null}：连 PID 段都没解析出来时也该有答案）
     * @return 判定；<b>本方法不抛异常</b>，{@code null} 入参按「无可比对字段」处理
     */
    public static Assessment assess(InboundIdentity inbound) {
        if (inbound == null) {
            return new Assessment(Verdict.NO_COMPARABLE_KEYS,
                    "报文中没有可解析的身份段：连 PID-3/PID-5/PID-7 都没有读到，无任何可比对字段；"
                            + "不产出候选、不调模型，该条进待确认等人处理", "PID");
        }
        boolean hasName = inbound.hasName();
        boolean hasBirth = inbound.hasBirthDate();
        if (hasName && hasBirth) {
            return new Assessment(Verdict.USABLE,
                    "姓名与出生日期均在，可正常比对", "");
        }
        if (!hasName && !hasBirth) {
            return new Assessment(Verdict.NO_COMPARABLE_KEYS,
                    "报文未提供姓名（PID-5）与出生日期（PID-7）：没有任何可比对的字段，"
                            + "本端不产出候选、也不为这条报文调用语义对齐服务；"
                            + "该条进待确认，由信息员判断是新建档案还是找区域平台重推",
                    "PID-5 姓名, PID-7 出生日期");
        }
        String missing = hasName ? "PID-7 出生日期" : "PID-5 姓名";
        return new Assessment(Verdict.PARTIAL_KEYS,
                "报文缺少" + missing + "，只能按现有字段比对：依据里已标注该字段缺失，"
                        + "置信度不因此抬高，仍须人工确认",
                missing);
    }
}