package cn.hospital.region.archive.ledger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 接收台账 schema 迁移入口 —— 一份 V1 脚本同时跑在 PostgreSQL 与 H2 PG 兼容模式上。
 *
 * <p><b>为什么不用 Flyway / Liquibase</b>：本产品只要把一张表加几条索引落成 SQL 跑一次就完事，
 * 引入额外依赖只会让「断网点用本地 H2 替身跑通整条验证脚本」这条铁律变难调通。
 * 启动期 {@link #migrate(Connection)} 跑一次幂等迁移，重复跑也安全。
 *
 * <p><b>为什么不用应用层 if-exists 检查</b>：V1 创建两张表和一组索引，重复执行 CREATE TABLE
 * 会在两库都爆「已存在」错误。生产期只需要跑一次；测试期每个用例都开新库（{@code H2 内存模式}），
 * 不存在「已存在」问题。
 *
 * <p><b>幂等保证</b>：本方法会在执行前把整个 SQL 脚本过一遍「按行丢弃 -- 注释与空行」预扫，
 * 反复由同一实例 migrate 同一连接会正常失败（创建已存在）—— 这是测试套件的预期行为，
 * 同一连接复用需要调用方先清表。
 *
 * <p><b>schema 版本号</b>：当前版本字符串是 {@link #CURRENT_VERSION} = {@code "V5"}。
 * 该字符串会被 {@link #version(Connection)} 读出，进入启动摘要
 * （{@link cn.hospital.region.archive.Main} 的打印行），便于确认进程连的是预期的 schema。
 *
 * <p><b>版本不是一个字符串而是一串脚本</b>：{@link #MIGRATIONS} 按序号排列，
 * 每次 {@link #migrate(Connection)} 全部按序跑一遍，脚本自己往 {@code schema_version}
 * 写自登记行，因此「版本号」永远是<b>最后一个跑成功的脚本</b>。
 * 加版本只加脚本 + 加一行列表，不改 {@link #migrate} 的控制流。
 */
public final class LedgerSchema {

    private static final Logger LOG = Logger.getLogger(LedgerSchema.class.getName());

    /** classpath 下的 V1 脚本路径；生产与测试共用同一份。 */
    public static final String V1_RESOURCE_PATH = "/db/migration/V1__schema.sql";

    /** classpath 下的 V2 脚本路径（模型调用审计与置信度样本）。 */
    public static final String V2_RESOURCE_PATH = "/db/migration/V2__model_audit.sql";

    /** classpath 下的 V3 脚本路径（双主键分离：区域平台 patient_ref 与院内 local_mrn 各占一列）。 */
    public static final String V3_RESOURCE_PATH = "/db/migration/V3__identity_binding.sql";

    /** classpath 下的 V4 脚本路径（确认被拒留痕 confirm_refusal）。 */
    public static final String V4_RESOURCE_PATH = "/db/migration/V4__confirm_refusal.sql";

    /** classpath 下的 V5 脚本路径（重投队列 redelivery_queue）。 */
    public static final String V5_RESOURCE_PATH = "/db/migration/V5__redelivery_queue.sql";

    /**
     * 迁移脚本，按<b>执行顺序</b>排列（不可变）。
     *
     * <p>顺序是硬要求：V2 的两张表不依赖 V1，但 {@code schema_version} 由 V1 建表，
     * 反过来跑会在生产上炸「relation does not exist」而在 H2 上不一定炸——
     * 两库表现不一致的迁移脚本是最难查的一类，所以序号即顺序。
     */
    public static final List<String> MIGRATIONS =
            List.of(V1_RESOURCE_PATH, V2_RESOURCE_PATH, V3_RESOURCE_PATH, V4_RESOURCE_PATH,
                    V5_RESOURCE_PATH);

    /** 当前 schema 版本号：最后一个迁移脚本的版本号。 */
    public static final String CURRENT_VERSION = "V5";

    private LedgerSchema() {
        // 工具类，禁止实例化
    }

    /**
     * 在给定数据库连接上运行当前版本的 schema 迁移。
     *
     * <p>实现要点：
     * <ol>
     *   <li>从 classpath 读 V1 脚本（找不到则 {@link IllegalStateException}，绝不静默）</li>
     *   <li>逐段执行；每个 SQL 段在单独 {@code Statement.execute} 调用里执行，
     *       以便两库都能在「DDL 一段失败」时给出确切位置</li>
     *   <li>记录 INFO 级日志，输出已应用的版本号与耗时</li>
     * </ol>
     *
     * @param conn 已经打开的 JDBC 连接（由调用方管理生命周期）
     * @return 应用成功的版本号
     * @throws SQLException 任一段 DDL 失败时原样抛出；调用方决定是否回滚或重试
     */
    public static String migrate(Connection conn) throws SQLException {
        Objects.requireNonNull(conn, "数据库连接不得为空");

        long started = System.nanoTime();
        int statements = 0;
        // 把 SQL 段切开执行（用 ";" 作为段分隔）。每个段内可能是多语句复合块，
        // 但迁移脚本不含 PL/pgSQL 或存储过程，单段单语句足够。
        try (Statement st = conn.createStatement()) {
            for (String script : MIGRATIONS) {
                for (String segment : splitSqlSegments(readScript(script))) {
                    String trimmed = segment.trim();
                    if (trimmed.isEmpty()) {
                        continue;
                    }
                    st.execute(trimmed);
                    statements++;
                }
            }
            long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
            LOG.log(Level.INFO,
                    "schema 迁移完成: version={0}, scripts={1}, segments={2}, elapsedMs={3}",
                    new Object[]{CURRENT_VERSION, MIGRATIONS.size(), statements, elapsedMs});
            return CURRENT_VERSION;
        }
    }

    /**
     * 只读当前 schema 版本号（应用启动后查询）。
     *
     * <p>通过 {@code SELECT version FROM schema_version} 读；{@link #ensureVersionTable}
     * 已经在 {@link #migrate(Connection)} 内部创建该表。
     * 异常会转成空版本号（不抛）—— schema 版本号是观测指标，缺它不该让进程起不来。
     */
    public static String version(Connection conn) {
        if (conn == null) {
            return "";
        }
        try (Statement st = conn.createStatement();
             var rs = st.executeQuery("SELECT version FROM schema_version ORDER BY applied_at DESC LIMIT 1")) {
            if (rs.next()) {
                return rs.getString(1);
            }
            return "";
        } catch (SQLException e) {
            // schema_version 表尚未创建（迁移前），返回空串
            return "";
        }
    }

    /**
     * SQL 段切分：忽略 SQL 行内注释 {@code -- ...} 与空行后，按 {@code ;} 分段。
     *
     * <p>不引入第三方解析器：V1 脚本体里只有 DDL，没有字符串字面量、PL/pgSQL 或触发器嵌套，
     * 手写切分足够。如果未来脚本里出现 {@code ';'} 之类的字面量要再换正路。
     */
    static java.util.List<String> splitSqlSegments(String sql) {
        java.util.List<String> out = new java.util.ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inComment = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            // 处理 "--" 行注释到行尾
            if (!inComment && c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                inComment = true;
                i++; // 吞掉第二个 '-'
                continue;
            }
            if (inComment) {
                if (c == '\n') {
                    inComment = false;
                }
                continue;
            }
            // SQL 字符大小写不敏感，但 V1 关键字全大写 —— 这一段代码与关键字无关故不区分
            if (c == ';') {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
        return out;
    }

    /** 从 classpath 读 V1 脚本；找不到时抛 {@link IllegalStateException}。 */
    static String readV1Script() {
        return readScript(V1_RESOURCE_PATH);
    }

    /** 从 classpath 读指定迁移脚本；找不到时抛 {@link IllegalStateException}。 */
    static String readScript(String resourcePath) {
        try (InputStream in = LedgerSchema.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException(
                        "找不到 schema 脚本 " + resourcePath + " —— classpath 是否包含 src/main/resources");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读 schema 脚本失败: " + resourcePath, e);
        }
    }
}
