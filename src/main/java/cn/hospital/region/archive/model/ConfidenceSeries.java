package cn.hospital.region.archive.model;

/**
 * 置信度观察点的两个<b>序列</b> —— 分桶统计必须分列，不能揉成一个「总体置信度」。
 *
 * <p>揉在一起会得出一个看起来很漂亮、实际上没人能用的数字：
 * 一条报文的 Z 段映射通常有 5 到 8 个观察点，身份候选只有 1 到 3 个，
 * 混算之后 Z 段条目多、身份条目少，权重被 Z 段悄悄拿走，
 * 而「这条到底是谁」恰恰才是决定它要不要人看的那个数。
 * 分列之后两个口径各自成立，也才能对着台账逐条核。
 */
public enum ConfidenceSeries {

    /** Z 段 → 本地字段的映射置信度：这条档案的每个自定义字段靠不靠得住。 */
    Z_ALIGNMENT("Z_SEGMENT", "Z 段映射置信度"),

    /** 身份候选置信度：这条档案说的可能是哪一位居民。 */
    IDENTITY_CANDIDATE("IDENTITY", "身份候选置信度");

    private final String dbValue;
    private final String zhLabel;

    ConfidenceSeries(String dbValue, String zhLabel) {
        this.dbValue = dbValue;
        this.zhLabel = zhLabel;
    }

    /** 数据库存储名。 */
    public String dbValue() {
        return dbValue;
    }

    /** 中文标签，报表表头用。 */
    public String zhLabel() {
        return zhLabel;
    }

    /**
     * 解析数据库取值。
     *
     * @throws IllegalArgumentException 未知取值——库里出现不认识的值说明迁移或写入端出了偏差，
     *                                  这种情况下报表宁可报错也不把它悄悄算进某个桶
     */
    public static ConfidenceSeries fromDbValue(String value) {
        for (ConfidenceSeries s : values()) {
            if (s.dbValue.equals(value) || s.name().equals(value)) {
                return s;
            }
        }
        throw new IllegalArgumentException("未知的置信度序列：" + value
                + "；可选值 " + java.util.Arrays.toString(dbValues()));
    }

    /** 全部数据库取值，供错误提示与测试断言。 */
    public static String[] dbValues() {
        ConfidenceSeries[] all = values();
        String[] out = new String[all.length];
        for (int i = 0; i < all.length; i++) {
            out[i] = all[i].dbValue;
        }
        return out;
    }
}