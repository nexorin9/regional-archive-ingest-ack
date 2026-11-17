package cn.hospital.region.archive.model;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 送进模型的<b>单字段长度上限</b> —— 脏数据的第一道闸，位置在「本机读到」与「送给模型」之间。
 *
 * <h2>为什么要有这道闸</h2>
 * <p>真实院内报文不干净。一个病历摘要字段被上游系统灌进一整段文本、一个备注字段
 * 粘上了几万字的随访记录，都会让「一个字段」变成几十万字符。后果有两条，
 * <b>两条都不是「模型答得不好」，而是「这条根本不该发给模型」</b>：
 * <ul>
 *   <li><b>本机这一侧</b>：编辑距离是 O(n·m)。一个 5000 字的姓名去比 5000 字的院内姓名
 *       是一次 2500 万次比较，单条就能把工作线程占住几十毫秒——一条脏数据拖慢整批。</li>
 *   <li><b>模型这一侧</b>：超长请求要么被端点直接拒（413/400），要么把本批次的
 *       token 预算一次性吃光，剩下的正常报文跟着降级。<b>一条脏数据毁掉整批</b>，
 *       这是本产品最不能接受的失败形态。</li>
 * </ul>
 *
 * <h2>为什么是「截断 + 记告警」而不是「整条拒收」</h2>
 * <p>字段超长说明<b>这一个字段</b>有问题，不说明整条报文有问题。区域平台推来的档案包里，
 * 绝大多数内容仍然是好的。整条拒收（AE/AR）会让对端把这条留在重投队列里重投一万次，
 * 而重投一万次还是同样的超长字段——那是本产品最贵的失败。
 * 截断后继续比对：姓名/摘要被砍掉的是尾部，中间那部分照样能给出候选，
 * 而「这个字段超长、被截到 256 字符」这条告警会跟着这条报文走到待确认台面上，
 * 信息员看得见「这条有个字段不太对」，而不是看不见地少比了一截。
 *
 * <h2>按码点截，不按 char 截</h2>
 * <p>{@code substring} 砍在代理项中间会造出一个 U+FFFD —— 于是「本来干净的字段」
 * 因为截断本身变成了乱码字段，而 {@code U+FFFD} 在解码告警里又是另一个信号，
 * 两个问题叠在一起就分不清是报文脏还是自己砍出来的。本类一律按<b>码点</b>计数与切分，
 * 保证截断后的字符串仍然是合法文本。
 *
 * <h2>上限集中在这里，不散落到调用点</h2>
 * <p>本机侧（{@link InboundIdentity}）与模型侧（{@link OpenAiCompatibleGateway}）
 * 用的是<b>同一个常量、同一个方法</b>。两处各写一个 256 的话，改了一处就会出现
 * 「本机按 256 截了、模型侧按 512 送」，于是模型看到一段本机从没比较过的内容，
 * 给出一个谁也解释不了的高分依据。
 */
public final class ModelInputPolicy {

    private static final Logger LOG = Logger.getLogger(ModelInputPolicy.class.getName());

    /** 单字段送模型前的长度上限（码点）。 */
    public static final int MAX_FIELD_CHARS = 256;

    private ModelInputPolicy() {
    }

    /**
     * 截断结果。
     *
     * @param value         截断后的值（{@code null}/空白归一为 {@code ""}）
     * @param originalChars 截断前的码点数；{@code 0} 表示原值为空
     * @param truncated     是否真的发生过截断
     */
    public record Clamp(String value, int originalChars, boolean truncated) {

        public boolean wasEmpty() {
            return originalChars == 0;
        }
    }

    /**
     * 不记日志的纯截断（供调用方自己决定要不要报）。
     *
     * @param raw 原始值；{@code null} 视为空串
     */
    public static Clamp clamp(String raw) {
        String text = raw == null ? "" : raw;
        int codePoints = text.codePointCount(0, text.length());
        if (codePoints <= MAX_FIELD_CHARS) {
            return new Clamp(text, codePoints, false);
        }
        int end = text.offsetByCodePoints(0, MAX_FIELD_CHARS);
        return new Clamp(text.substring(0, end), codePoints, true);
    }

    /**
     * 送模型前的截断：截了就在日志里留一条告警。
     *
     * <p>告警只写<b>字段名与长度</b>，不写字段值——这里的字段值就是居民姓名与病历摘要，
     * 落进日志就等于把它抄到另一个不设访问控制的地方（T40 的明文扫描守的是同一条线）。
     *
     * @param raw        原始值
     * @param fieldLabel 字段名（PID-5 姓名 / ZX-3 字段值 …），仅进日志，不含取值
     */
    public static String clampForModel(String raw, String fieldLabel) {
        Clamp result = clamp(raw);
        if (result.truncated()) {
            LOG.log(Level.WARNING, "字段超长已截断（上限 {0} 码点）: 字段={1} 原长={2} 字符值不外发、不入日志",
                    new Object[]{MAX_FIELD_CHARS, fieldLabel, result.originalChars()});
        }
        return result.value();
    }
}