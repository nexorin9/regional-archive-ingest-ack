# 命令与接口速查（完整版）

README 里是常用命令与端点摘要；这里是全部子命令、参数与退出码，供运维排障与脚本调用查阅。
契约以 `src/main/resources/http/openapi.yaml` 为准，`OpenApiConsistencyTest` 会校验路由与契约一致。

## 进程入口

```
java -jar regional-archive-ingest-ack.jar <子命令|自检开关>
```

| 退出码 | 含义 |
|--------|------|
| `0` | 正常 |
| `2` | 必需环境变量缺失或取值非法（不回落到本地替身） |
| `64` | 用法错误（未知子命令、参数缺失） |
| `1` | 只读排障命令命中明文（仅 `obs scan-plaintext`） |

## 自检开关（不读凭据、不连院内系统）

| 命令 | 作用 | 期望输出 |
|------|------|----------|
| `--check-config` | 校验环境变量并打印单行启动摘要 | 启动摘要含监听端口、AE、回执方言、schema 版本；令牌一律 `***` |
| `--mllp-smoke` | 本机回环口打一帧真实报文 | `received=1 acked=1` + 回执报文 |
| `--ack-contract-smoke [framed_ack\|bare_ack]` | 连发两帧（齐全 / 缺 MSH-10）核对传输级与逐条 MSA | 传输级字节形状 + 两条 MSA + `PASS` |
| `--capacity-benchmark` | 单批 500 条容量基准 | `P99 阈值 200ms → 通过（实测 …ms）` |
| `--api-smoke` | 回环 API 八步契约自检 | `API 自检 PASS` |
| `--recovery-scan` | 扫台账，把重投路径上未终态的条目按幂等键喂回重投队列 | 入队条数与扫描条数 |
| `--help` | 用法 | 退出码 0 |

## `ledger` 台账只读排障

| 命令 | 参数 | 说明 |
|------|------|------|
| `ledger batch-stats <batchId>` | — | 批次四态分布 + 拒收原因分布 |
| `ledger export <batchId>` | `--state STATE`、`--out PATH` | 清单 CSV（默认 `PENDING_CONFIRM`）；不给 `--out` 打到标准输出 |

`--state` 取值：`PENDING_CONFIRM`（待确认清单，默认）/ `REJECTED`（拒收清单）/ 其它四态（通用清单）。
导出件只出主键与 SHA-256 前 8 位片段，不含姓名、证件号、手机号。

## `model` 模型审计只读排障

| 命令 | 参数 | 说明 |
|------|------|------|
| `model audit --date YYYY-MM-DD` | `--tz ZONE`、`--out PATH`、`--no-bom` | 当日模型调用审计导出；不给日期取今天 |
| `model confidence-buckets --date YYYY-MM-DD` | `--tz ZONE` | 置信度分桶，用于估算当日人工复核工作量 |

## `obs` 可观测排障

| 命令 | 参数 | 说明 |
|------|------|------|
| `obs scan-plaintext PATH...` | `--names-file FILE` | 扫日志与导出件的明文命中；命中退出码 1，片段一律打码 |
| `obs error-codes` | `--out PATH` | 导出错误码总目录 CSV（44 条，跨 ack / http / identity / model / redelivery 五个来源包） |
| `obs batch-report <batchId>` | — | 批次处理量、四态分布、拒收率与耗时（走只读数据库配置） |

## 院内 HTTP API

| 方法与路径 | 用途 | 关键参数 |
|------------|------|----------|
| `GET /healthz` | 探针（不带令牌） | 返回服务名、apiVersion、localAet、schemaVersion |
| `POST /batches/{batchId}/confirm` | **唯一落地通道**：待确认 → 已落地 | 体：`ledgerId` / `patientRef` / `localMrn`（+ 可选 `candidateSource` / `note`）；操作人只从令牌取 |
| `POST /batches/{batchId}/revert` | 撤回已落地条目回待确认 | 体：`ledgerId` / `reason` |
| `GET /messages` | `?state=` `?batchId=` `?limit=` | 待办清单；确认前 `localMrn` 显式为空 |
| `GET /batches/{batchId}/stats` | 批次四态统计与拒收原因分布 | — |
| `GET /batches/{batchId}/reconcile` | 与对端回执做差集，出**只读**重投清单 | 该接口不入队，入队由重投调度按退避执行 |

鉴权：`Authorization: Bearer <ARCHIVE_API_TOKEN>` 或 `X-Archive-Api-Token` 头。状态码语义：
`400` 请求不合法 / `401` 令牌缺失或无效 / `404` 无此条或不属于本批次 / `409` 状态冲突（含重复确认、并发争用）/ `502`、`503` 对端不可达或 HIS 写失败（该条仍停在待确认可重试）。
## 发布前可复现验证：`scripts/verify.sh`

```bash
bash scripts/verify.sh                 # 默认跳过测试，只做构建 + 端到端验证
VERIFY_WITH_TESTS=1 bash scripts/verify.sh   # 连跑全部单元/契约测试
VERIFY_MESSAGES=8 bash scripts/verify.sh     # 改合法报文条数（默认 3，另加 1 条故意缺陷报文）
```

按「安装 → 配置 → 使用 → 验证」六步真跑，退出码 0 表示全过：

| 步骤 | 做什么 | 断言要点 |
|------|--------|----------|
| 0 环境前置 | 找一个**真正能运行**的 JDK（`/usr/bin/java` 常是坏 stub）、`mvn`、`curl` | JDK ≥ 17；缺任一项直接终止 |
| 1 构建 | `mvn -o -q package` | 退出码 0 且 `target/regional-archive-ingest-ack.jar` 存在 |
| 2 配置自检 | 加载 `.env.example` 跑 `--check-config`；再 `unset HIS_MPI_TOKEN` 复跑 | 前者退出码 0、摘要含 `mllp=<主机>:<端口>`、五项凭据一律 `***` 且无明文；后者退出码 **2** 且点名变量 |
| 3 起常驻进程 | `scripts/verify/VerifyLive.java serve` 装配真实部件 | 真实 `ServerSocket`（MLLP）+ 真实 `HttpApiServer` + 内嵌 H2 同一份迁移 SQL，打印 `READY mllp=… http=…` |
| 4 真实报文 | `VerifyLive.java send` 从真实 socket 打 N+1 条报文 | 每条报文一个应用级 MSA 回执；齐全回 `AA`，缺 `MSH-10` 回 `AE` 且 ERR-1 指向 `MSH^10` |
| 5 HTTP 端点 | `curl` 打 `/healthz`、`/messages`（401 与 200）、`/batches/{id}/confirm`（含重复确认 409）、`/batches/{id}/stats`、`/batches/{id}/revert`、`/batches/{id}/reconcile` | **回执条数 == 台账条数**；未确认时 `landed=0`；确认后 `landed=1`；重复确认 409；撤回后复原；对账对端不可达 503 且不出差集 |
| 6 隔离自证 | 回看运行日志 | 对账只指向本机关闭端口；确认动作只触发本地写端口；运行日志里没有凭据原文 |

它**不连任何院内系统、不需要任何真实凭据**：台账走内嵌 H2 的 PG 兼容模式，患者主索引端口用只记日志的本地实现（生产是真实 HTTP `PUT`），回执对账指向一个没人监听的端口因此必然走「对端不可达」降级——这正是要验的行为。所有绑定地址都是 `127.0.0.1`。

验证侧启动器 `scripts/verify/VerifyLive.java` 只用 JDK（`java scripts/verify/VerifyLive.java …` 单文件源码模式），不进 `target/classes`，不是产品的第二条启动路径。
