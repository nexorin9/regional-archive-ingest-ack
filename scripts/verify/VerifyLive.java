/*
 * 发布前可复现验证的<b>验证侧启动器</b>（不属于产品代码，不进 target/classes）。
 *
 * <p>为什么要它：产品的运行面是「无界面 HTTP API + 常驻 MLLP 监听口」，
 * 但发布态不提供本地 serve 开关（避免真实接口需要显式开关才启用）。
 * 要在<b>不连任何院内系统、不需要真实凭据</b>的前提下把整条主路径真跑一遍，
 * 就需要一个把真实部件装配起来并常驻的进程 —— 这正是本文件的 serve 模式。
 *
 * <p>装配的全是产品自身的真实部件，没有替身：
 * <ul>
 *   <li>{@code ArchiveIngestService}：入站主路径（拆帧 → 结构化 → 定位 → 回执 → 台账），
 *       它本身就是 {@code MllpServer.MessageHandler}，直接挂到真实 {@code ServerSocket} 上；</li>
 *   <li>{@code JdbcLedgerStore} + 真实 V1–V5 迁移 SQL（内嵌 H2 的 PG 兼容模式，与生产同一份 DDL）；</li>
 *   <li>{@code HttpApiServer}：真实 {@code com.sun.net.httpserver} 监听口，由 verify.sh 用 curl 打；</li>
 *   <li>{@code ConfirmGate} / {@code RevertService}：确认门与撤回，共用同一条状态机与审计链。</li>
 * </ul>
 *
 * <p>唯一<b>不发真实外呼</b>的两处，都不碰院内系统，也不碰凭据：
 * 患者主索引端口用一个只记日志的本地实现（生产实现是真实 HTTP PUT），
 * 回执对账客户端指向一个<b>故意关闭</b>的端口（生产指向区域平台），因此
 * {@code /batches/{id}/reconcile} 会按契约返回「对端不可达」，这正是要验的降级行为。
 *
 * <p>用法（JDK 11+ 单文件源码模式，只用 JDK，不引入任何构建依赖）：
 * <pre>
 *   java -cp "target/regional-archive-ingest-ack.jar:target/lib/*" scripts/verify/VerifyLive.java \
 *        serve &lt;mllpPort&gt; &lt;httpPort&gt; &lt;apiToken&gt; &lt;batchId&gt;
 *   java -cp "target/regional-archive-ingest-ack.jar:target/lib/*" scripts/verify/VerifyLive.java \
 *        send  &lt;host&gt; &lt;port&gt; &lt;validCount&gt;
 * </pre>
 * serve 就绪后向标准输出打一行 {@code READY mllp=<port> http=<port> batch=<batchId>} 并常驻；
 * send 从真实 socket 打 N 条形状完整的档案变更报文 + 1 条故意缺 MSH-10 的报文，
 * 逐条读回 MSA 应用级回执，打印 {@code SENT=… ACKS=…} 摘要供 shell 断言。
 */

import cn.hospital.region.archive.ack.AckComposer;
import cn.hospital.region.archive.http.ApiTokenGuard;
import cn.hospital.region.archive.http.HttpApiServer;
import cn.hospital.region.archive.http.Routes;
import cn.hospital.region.archive.identity.ConfirmGate;
import cn.hospital.region.archive.identity.JdbcPatientKeyStore;
import cn.hospital.region.archive.identity.PatientIndexWriter;
import cn.hospital.region.archive.ledger.ArchiveIngestService;
import cn.hospital.region.archive.ledger.IdempotencyGuard;
import cn.hospital.region.archive.ledger.JdbcLedgerStore;
import cn.hospital.region.archive.ledger.LedgerSchema;
import cn.hospital.region.archive.ledger.RevertService;
import cn.hospital.region.archive.mllp.MllpFrameCodec;
import cn.hospital.region.archive.mllp.MllpServer;
import cn.hospital.region.archive.redelivery.BatchReconcilerClient;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class VerifyLive {

    private static final String LOCAL_AET = "CHSCOMMUNITY";
    private static final String REMOTE_AET = "REGIONAL_ARCHIVE";
    private static final String BATCH_ID_DEFAULT = "ARCHIVE-20261020-VERIFY";

    /** 内嵌台账：与全部台账测试、生产同一份迁移脚本（V1–V5），只是驱动换成 H2 的 PG 兼容模式。 */
    private static final String H2_URL =
            "jdbc:h2:mem:verify_live;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

    private VerifyLive() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("用法：serve <mllpPort> <httpPort> <apiToken> <batchId> | send <host> <port> <validCount>");
            System.exit(64);
        }
        switch (args[0]) {
            case "serve" -> System.exit(serve(args));
            case "send" -> System.exit(send(args));
            default -> {
                System.err.println("未知模式：" + args[0]);
                System.exit(64);
            }
        }
    }

    // =====================================================================
    // serve：装配真实部件 → 起 MLLP 监听口与 HTTP API → 常驻
    // =====================================================================

    private static int serve(String[] args) throws Exception {
        int mllpPort = Integer.parseInt(args[1]);
        int httpPort = Integer.parseInt(args[2]);
        String apiToken = args[3];
        String batchId = args.length > 4 && !args[4].isBlank() ? args[4] : BATCH_ID_DEFAULT;

        Connection conn = openConnection();
        try {
            conn.setAutoCommit(false);
            LedgerSchema.migrate(conn);
            conn.commit();
        } finally {
            conn.close();
        }

        JdbcLedgerStore store = new JdbcLedgerStore(VerifyLive::openConnection);
        // 批次号固定：verify.sh 要用同一个 batchId curl 四态统计与对账，不能随日期漂。
        ArchiveIngestService ingest = new ArchiveIngestService(
                new AckComposer(LOCAL_AET, REMOTE_AET, Clock.systemUTC()),
                store,
                new IdempotencyGuard(store),
                new LoggingOnlyPatientIndexWriter(),
                (header, message) -> batchId);

        ConfirmGate gate = new ConfirmGate(store, ingest, new RevertService(store), null,
                new JdbcPatientKeyStore(VerifyLive::openConnection));

        Routes routes = new Routes(
                new ApiTokenGuard(apiToken, LOCAL_AET),
                gate, store, store,
                // 对端回执对账：指向一个没人监听的端口，所以 /reconcile 必然走「对端不可达」降级。
                new BatchReconcilerClient("http://127.0.0.1:9/api/v1", "verify-unreachable-peer",
                        Duration.ofSeconds(3)),
                key -> "",
                LOCAL_AET, Clock.systemUTC(), LedgerSchema.CURRENT_VERSION);

        MllpServer mllp = new MllpServer("127.0.0.1", mllpPort, ingest);
        int boundMllpPort = mllp.start();
        HttpApiServer http = HttpApiServer.start("127.0.0.1", httpPort, routes);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            mllp.close();
            http.close();
        }));
        System.out.println("READY mllp=" + boundMllpPort + " http=" + http.port()
                + " batch=" + batchId + " schema=" + LedgerSchema.CURRENT_VERSION);
        System.out.flush();

        // 常驻到被 SIGTERM 收掉（verify.sh 结束时 kill）。
        Thread.currentThread().join();
        return 0;
    }

    // =====================================================================
    // send：真实 socket 打 N+1 条报文，逐条读回 MSA 应用级回执
    // =====================================================================

    private static int send(String[] args) throws Exception {
        String host = args[1];
        int port = Integer.parseInt(args[2]);
        int validCount = Math.max(1, Integer.parseInt(args[3]));

        List<String> sent = new ArrayList<>();
        for (int i = 1; i <= validCount; i++) {
            sent.add(validMessage(i));
        }
        // 故意缺 MSH-10：这条必须被拒收（AE + ERR），并单独入账为拒收态。
        sent.add(missingControlIdMessage());

        List<String> acks = new ArrayList<>();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 5000);
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            for (String er7 : sent) {
                out.write(MllpFrameCodec.encode(er7.getBytes(StandardCharsets.UTF_8)));
                out.flush();
                int transport = in.read();
                if (transport != MllpFrameCodec.ACK) {
                    System.err.println("SEND FAIL：传输级确认字节不是 0x06，实际是 "
                            + (transport < 0 ? "连接被关闭" : "0x" + Integer.toHexString(transport)));
                    return 1;
                }
                acks.add(new String(MllpFrameCodec.readFrame(in), StandardCharsets.UTF_8));
            }
        }

        int aa = 0;
        int ae = 0;
        int ar = 0;
        int withErr = 0;
        for (String ack : acks) {
            String msa1 = firstComponent(ack, "MSA", 1);
            if ("AA".equals(msa1)) {
                aa++;
            } else if ("AE".equals(msa1)) {
                ae++;
            } else if ("AR".equals(msa1)) {
                ar++;
            }
            if (ack.contains("ERR|")) {
                withErr++;
            }
            for (String line : ack.replace("\r", "\n").split("\n")) {
                if (line.startsWith("MSA|") || line.startsWith("ERR|")) {
                    System.out.println("ACK " + line);
                }
            }
        }
        System.out.println("SENT=" + sent.size() + " ACKS=" + acks.size()
                + " MSA_AA=" + aa + " MSA_AE=" + ae + " MSA_AR=" + ar + " ERR_SEGMENTS=" + withErr);
        // 每条报文一个回执；合法的走 AA，故意缺控制号的那条走 AE 且必须带 ERR 段。
        boolean ok = acks.size() == sent.size() && aa == validCount && ae == 1 && ar == 0
                && withErr == 1;
        return ok ? 0 : 1;
    }

    /** 形状完整的入站档案变更报文（MSH + PID），控制号按序号生成便于逐条比对。 */
    private static String validMessage(int index) {
        return String.join("\r",
                "MSH|^~\\&|REGIONAL_ARCHIVE|FACILITY_A|ARCHIVE_IN|FACILITY_A|20261020090000||"
                        + "ADT^A01^ADT_A01|VERIFY-CTRL-000" + index + "|P|2.5.1|||AL|NE||UNICODE UTF-8",
                "PID|1||PAT-000" + index + "^^^FACILITY_A^MR||张^三||19780315|M",
                "");
    }

    /** 故意缺 MSH-10（报文控制号）的报文：按契约必须 AE + ERR 段指出段与字段。 */
    private static String missingControlIdMessage() {
        return String.join("\r",
                "MSH|^~\\&|REGIONAL_ARCHIVE|FACILITY_A|ARCHIVE_IN|FACILITY_A|20261020090000||"
                        + "ADT^A01^ADT_A01||P|2.5.1|||AL|NE||UNICODE UTF-8",
                "PID|1||PAT-9999^^^FACILITY_A^MR||李^四||19790315|F",
                "");
    }

    private static String firstComponent(String er7, String segment, int fieldIndex) {
        for (String line : er7.split("\r")) {
            if (line.startsWith(segment + "|")) {
                String[] fields = line.split("\\|", -1);
                if (fieldIndex < fields.length) {
                    return fields[fieldIndex].split("\\^")[0];
                }
            }
        }
        return "";
    }

    private static Connection openConnection() throws java.sql.SQLException {
        return DriverManager.getConnection(H2_URL, "sa", "");
    }

    /**
     * 患者主索引端口的本地实现：只记日志、不发真实 HTTP。
     *
     * <p>生产实现是 {@code PUT {HIS_MPI_ENDPOINT}/patient-index}（T31）。验证期不连院内系统，
     * 但仍走同一条确认门 → 写端口的调用顺序，所以「无确认零写入、有确认才写一次」照旧可验。
     */
    private static final class LoggingOnlyPatientIndexWriter implements PatientIndexWriter {
        @Override
        public void put(String patientRef, String localMrn, String sourceBatch) {
            Logger.getLogger("verify.patient-index").log(Level.INFO,
                    "验证模式：触发院内患者主索引写入 patientRef={0} localMrn={1} sourceBatch={2}"
                            + "（验证期不发真实 HTTP）",
                    new Object[]{patientRef, localMrn, sourceBatch});
        }
    }
}