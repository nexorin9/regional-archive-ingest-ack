package cn.hospital.region.archive.config;

/**
 * 配置装配失败。
 *
 * <p>本产品对凭据与对端地址一律 fail fast：缺任一必需项、或取值不合法，进程在启动期
 * 非零退出（退出码 2），绝不静默切到本地替身。异常消息里<b>只带变量名与判定理由</b>，
 * 不回显任何环境变量的原值——原值可能是口令或令牌。
 */
public class ConfigException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String variableName;

    ConfigException(String variableName, String message) {
        super(message);
        this.variableName = variableName;
    }

    /** 必需项缺失或为空白。消息固定以「缺失环境变量 &lt;NAME&gt;」开头。 */
    public static ConfigException missing(String name) {
        return new ConfigException(name, "缺失环境变量 " + name);
    }

    /** 取值存在但不合法（协议不对、端口越界、枚举值不认识等）。 */
    public static ConfigException invalid(String name, String reason) {
        return new ConfigException(name, "环境变量 " + name + " 取值非法：" + reason);
    }

    /** 出问题的环境变量名，便于运维按名排查。 */
    public String variableName() {
        return variableName;
    }
}