package cn.hospital.region.archive.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 姓名与出生日期的<b>确定性归一</b> —— 候选闸门与基线共用同一份实现。
 *
 * <h2>为什么归一写在这里，而不是各写一份</h2>
 * <p>「张伟 / 張偉 / 张 伟 / 张&#12288;伟」在本产品里要出现在三个地方：
 * 候选闸门给人看的依据说明、基线打分的字面比较、以及最终写进确认台的候选顺序。
 * 三处各写一份归一，早晚会有一份忘了去空格，于是出现
 * 「基线说不像、闸门说像」这种谁也说不清的对不上的局面。
 * 一份实现、三个调用点，差异才会只出现在<b>算法</b>上而不是归一上。
 *
 * <h2>简繁对照表为什么不追求完整</h2>
 * <p>这里的表只覆盖国内姓名用字里出现频率高的那几十对（姓与常见名字用字）。
 * 全量简繁表有上万字，塞进源码既撑大包体又拖慢启动，而漏掉的那些字
 * 正好落回模型擅长的那一片 —— 这也是本产品把「变体识别」交给模型的原因之一。
 * 表在这里的作用是<b>不花钱的那部分</b>：能用确定规则判定的，不该去调一次模型。
 *
 * <p>本类<b>不判定身份</b>，只做归一与差异说明；是否算同一个人由调用方决定。
 */
public final class IdentityText {

    /**
     * 高频姓名用字 繁体 → 简体。
     *
     * <p>只收「姓 + 常见名字用字」里几乎人人都写错（写繁）的那一批：
     * 覆盖国内绝大多数居民姓名，剩下的长尾交给模型。
     */
    private static final Map<Character, Character> TRADITIONAL_TO_SIMPLIFIED = buildFold();

    /** 姓名里要忽略的空白：半角空格、全角空格 U+3000、不换行空格、制表。 */
    private static boolean isSpace(char c) {
        return Character.isWhitespace(c) || c == '　' || c == ' ';
    }

    private IdentityText() {
    }

    /** 去掉所有空白（半角与全角都去），其余原样。 */
    public static String stripSpaces(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (!isSpace(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 繁体字逐字折成简体；表外的字原样返回。 */
    public static String foldTraditional(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            Character folded = TRADITIONAL_TO_SIMPLIFIED.get(c);
            sb.append(folded == null ? c : folded);
        }
        return sb.toString();
    }

    /**
     * 姓名归一：去首尾空白 → 去所有空白 → 繁折简 → 小写。
     *
     * <p>拉丁字母名按小写比对（{@code ZHANG} 与 {@code zhang} 是同一个人的两种写法）。
     */
    public static String normalizeName(String raw) {
        String s = foldTraditional(stripSpaces(raw == null ? "" : raw.trim())).trim();
        return s.toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * 出生日期归一：只留数字。
     *
     * <p>区域平台常把 {@code 19850512} 写成 {@code 19850512+0800} 或 {@code 1985-05-12}，
     * 直接比字符串会判成三个人。留数字后 {@code 198505120800} 与 {@code 19850512} 不等，
     * 所以额外去掉尾部 4 位时区：{@link #normalizeBirthDate} 只取前 8 位。
     */
    public static String normalizeBirthDate(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder digits = new StringBuilder(14);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c >= '0' && c <= '9') {
                digits.append(c);
            }
        }
        if (digits.length() > 8) {
            return digits.substring(0, 8);
        }
        return digits.toString();
    }

    /** 归一后是否同名（两段都非空才算）。 */
    public static boolean sameName(String a, String b) {
        String na = normalizeName(a);
        return !na.isEmpty() && na.equals(normalizeName(b));
    }

    /** 归一后是否同出生日期（两段都非空才算）。 */
    public static boolean sameBirthDate(String a, String b) {
        String na = normalizeBirthDate(a);
        return !na.isEmpty() && na.equals(normalizeBirthDate(b));
    }

    /**
     * 两段姓名的差异说明 —— 确认台上「为什么把这两条摆到一起」的那一行。
     *
     * <p><b>不复述姓名原文</b>：依据说明会进待确认清单与审计导出，而这两处的
     * 约定是不落姓名明文（只落哈希与主键片段）。两边姓名在确认台上本来就是并排显示的，
     * 依据里再抄一遍只会多一处明文落点。
     */
    public static String nameVariantNote(String inbound, String local) {
        String a = inbound == null ? "" : inbound.trim();
        String b = local == null ? "" : local.trim();
        if (a.isEmpty() || b.isEmpty()) {
            return "一方姓名为空，无法比对";
        }
        if (a.equals(b)) {
            return "姓名字面一致";
        }
        boolean spaceTouched = stripSpaces(a).length() != a.length() || stripSpaces(b).length() != b.length();
        String sa = stripSpaces(a);
        String sb = stripSpaces(b);
        if (sa.equals(sb)) {
            return "姓名仅空格差异";
        }
        if (foldTraditional(sa).equals(foldTraditional(sb))) {
            return spaceTouched ? "姓名为简繁异体且含空格差异" : "姓名为简繁异体";
        }
        return "姓名用字不同（可能是同音错别字）";
    }

    private static Map<Character, Character> buildFold() {
        // 每两个字符一组「繁简」，逐组写死而不是两条等长字符串按下标对齐：
        // 下标对齐一旦少打一个字，错的不是文案而是「两个人被判成同一人」的冲突误报。
        String pairs = String.join("",
                // 姓
                "張张", "陳陈", "劉刘", "楊杨", "黃黄", "趙赵", "吳吴", "馬马", "羅罗", "鄭郑",
                "謝谢", "韓韩", "馮冯", "蔣蒋", "賈贾", "葉叶", "賀贺", "盧卢", "鄒邹", "蘇苏",
                "鐘钟", "蘭兰", "閻阎", "雲云", "龍龙", "龔龚", "廣广", "賓宾", "萬万", "義义",
                "傳传", "譚谭", "顏颜", "閔闵", "蕭萧", "陸陆", "畢毕", "韋韦", "湯汤", "錢钱",
                "嚴严", "覃覃", "顧顾", "郝郝", "駱骆", "歐欧", "龐庞", "樊樊",
                // 常见名字用字
                "偉伟", "靜静", "麗丽", "強强", "曉晓", "傑杰", "艷艳", "豔艳", "潔洁", "鳳凤",
                "飛飞", "學学", "國国", "華华", "軍军", "輝辉", "紅红", "剛刚", "鋒锋", "達达",
                "遠远", "進进", "運运", "遲迟", "鐵铁", "銀银", "蓮莲", "蔭荫", "藝艺", "藥药",
                "藍蓝", "蟬蝉", "見见", "覺觉", "觀观", "規规", "視视", "親亲", "愛爱", "寧宁",
                "寶宝", "實实", "審审", "寫写", "對对", "將将", "專专", "業业", "產产", "開开",
                "關关", "門门", "問问", "聞闻", "語语", "說说", "讀读", "書书", "畫画", "醫医",
                "護护", "頭头", "題题", "願愿", "買买", "賣卖", "貿贸", "費费", "貴贵", "貸贷",
                "質质", "購购", "賽赛", "贏赢", "農农", "過过", "違违", "遷迁", "適适", "選选",
                "邏逻", "鄉乡", "鄰邻", "醜丑", "釋释", "針针", "錄录", "錯错", "鍵键", "鎖锁",
                "鏡镜", "鑑鉴", "閉闭", "閱阅", "陣阵", "隱隐", "雙双", "雜杂", "雞鸡", "離离",
                "難难", "靈灵", "韻韵", "響响", "頁页", "頂顶", "項项", "順顺", "須须", "預预",
                "領领", "頻频", "顆颗", "額额", "類类", "風风", "養养", "餘余", "館馆", "駕驾",
                "驗验", "體体", "髮发", "鬥斗", "魚鱼", "鳥鸟", "鴻鸿", "鵝鹅", "鹽盐", "麥麦",
                "點点", "黨党", "齊齐", "齒齿", "師师", "樣样", "園园", "圓圆", "圖图", "隊队");
        if (pairs.length() % 2 != 0) {
            throw new IllegalStateException("简繁对照表必须成对书写，实际长度 " + pairs.length());
        }
        Map<Character, Character> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length(); i += 2) {
            char f = pairs.charAt(i);
            char t = pairs.charAt(i + 1);
            if (f != t) {
                m.put(f, t);
            }
        }
        return m;
    }
}