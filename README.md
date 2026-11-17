# 区域健康档案接入与回执系统

> 社区信息员把接入口挂到区域健康档案平台后，平台每天推下来的居民档案与慢病任务包逐条读懂、对上本地字段、给出可能同一位居民的身份候选；**每条报文回一个区域平台认得的 `AA`/`AE`/`AR` 回执**，配错的停在待确认等人点一下，确认后才写进院内患者档案；平台次日追问哪几条没收到时**只重投那几条**，不整包重发把数据写坏。

本系统常驻在院内前置机上：接一条、写一条回执、留一条台账，明天下午按批次核对，对不上的只补那几条。**无图形界面**——区域平台走报文接入，院内既有系统走 HTTP 接口调用确认与查询，命令行只作运维排障通道。

---

## 适用场景 / 目标岗位

| 岗位 | 什么时候用 | 得到什么 |
|------|------------|----------|
| 基层信息员 | 每天上午区域平台推居民档案 / 慢病任务包时 | 逐条四态结果（已接收 / 待确认 / 已落地 / 拒收）与同样条数的区域平台回执 |
| 基层信息员 | 下午按批次核对「今天收到几条」 | 按批次的四态分布、拒收原因清单、待确认清单（姓名与证件只出哈希片段） |
| 基层信息员 / 家庭医生 | 处理停在待确认的条目 | 可能同一位居民的身份候选 + 依据字段 + 置信度，**人点确认后才写进院内档案** |
| 区域平台对接人 | 次日追问哪几条没收到 | 按控制号圈出的可重投清单，只补那几条，不是整批重发 |
| 信息科运维 | 排查「这条为什么没落地」、等保前扫明文 | 该条原文与逐次状态迁移的审计行；一条命令给出「明文命中 0 处」的结论 |

---

## 能力要点

- **每条报文必有一个可对账的结局**——`AA` 收讫、`AE` 应用错误、`AR` 应用拒绝，每条都带区域平台能解析的标准错误码与出错段字段，不存在「收到但没人管」的报文。
- **分隔符与字符集按对方的说法解析**——字段、组件、重复分隔符与字符集一律从报文自己的 `MSH` 段读出，国内平台换分隔符或改用 `GB18030` 都不必先改代码就能收。
- **重复推一百次也只落地一次**——幂等键（消息类型 + 控制号 + 处理标识）加数据库唯一约束，同一条重发 100 次，写进院内档案的次数仍然是 1。
- **模型只给候选，不给结论**——`Z` 段到本地字段的语义对齐、简繁与同音错别字下的身份候选由模型给；没有任何一条路径允许模型输出直接推进业务状态，模型不可达时该条进待确认并标注降级原因。
- **接得上次没接完的活**——进程重启后从台账续跑；跨天追问时取对端回执与本地台账做差集，只重投差集里的那几条，`AR` 类永不自动重投。

---

## 快速开始

### 依赖

| 项 | 版本要求 | 说明 |
|----|---------|------|
| JDK / Maven | 17+ / 3.8+ | 构建用 `--release 17`；仅 4 个依赖（PostgreSQL 驱动 / H2 / JUnit 5），全程离线可构建 |
| 数据库 | PostgreSQL 12+ 或 H2 | 单机试点用 H2 的 PG 兼容模式；同一份迁移 SQL 不分叉 |

### 安装

```bash
git clone <本仓库> && cd regional-archive-ingest-ack && mvn -o -q package
ls -l target/regional-archive-ingest-ack.jar
```

### 配置

复制样例后按院内实际填写；**全部凭据只从环境变量读取**，缺任一必需项进程在启动期以退出码 2 终止，不会静默切到本地替身。

```bash
cp .env.example .env && set -a && . ./.env && set +a
```

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `ARCHIVE_MLLP_HOST` / `ARCHIVE_MLLP_PORT` | 无（必需） / `2575` | MLLP 监听地址与端口，院内前置机通常绑 `0.0.0.0:2575` |
| `ARCHIVE_LOCAL_AET` / `ARCHIVE_REMOTE_AET` | 无（必需） | 本院与对端 AE Title；对端不在白名单的连接被拒并记审计 |
| `ARCHIVE_ACK_MODE` | `framed_ack` | 回执方言：`framed_ack` 回 `0B 06 1C 0D`，`bare_ack` 回裸 `0x06` |
| `ARCHIVE_DB_URL` / `_USERNAME` / `_PASSWORD` | 无（必需） | 台账库连接 |
| `HIS_MPI_ENDPOINT` / `HIS_MPI_TOKEN` | 无（必需） | 院内患者主索引写入（确认后才 `PUT`） |
| `ARCHIVE_ACK_API_BASE_URL` / `ARCHIVE_ACK_API_TOKEN` | 无（必需） | 区域平台回执对账查询 |
| `ARCHIVE_API_TOKEN` | 无（必需） | 院内既有系统调用本系统接口的令牌 |
| `MODEL_BASE_URL` / `MODEL_API_KEY` / `MODEL_NAME` | 无（必需） | OpenAI-compatible 模型网关；模型只做字段对齐与身份候选 |
| `MODEL_TIMEOUT_SECONDS` / `MODEL_MAX_RETRIES` | `15` / `2` | 仅连接与读超时重试，其余错误直接降级 |

完整清单（含重投目标 `ARCHIVE_REDELIVERY_*`、断线重连预算 `ARCHIVE_MLLP_RECONNECT_*`）见 `.env.example`。

### 构建与验证

```bash
mvn -o -q dependency:resolve                # 离线解析依赖
mvn -o test                                 # 全量测试（649 条）
mvn -o test -Dtest=MllpServerContractTest   # 只跑真实帧往返契约（排查时用）
bash scripts/fetch_fixtures.sh              # 重建测试语料（可重跑，断网时用本地缓存）
bash scripts/verify.sh                      # 一条命令跑完 安装→配置→使用→验证（42 项断言，不连院内系统、不需要真实凭据）
```

### 主命令速查

```bash
java -jar target/regional-archive-ingest-ack.jar --check-config    # 校验配置并打印启动摘要
java -jar target/regional-archive-ingest-ack.jar --mllp-smoke      # 回环口打一帧：received=1 acked=1
java -jar target/regional-archive-ingest-ack.jar --ack-contract-smoke # 回执契约：传输级字节 + 逐条 MSA
java -jar target/regional-archive-ingest-ack.jar --api-smoke       # 院内接口八步自检
java -jar target/regional-archive-ingest-ack.jar ledger batch-stats <批次号>    # 批次四态统计
```

---

## 命令 / API / 配置说明

完整子命令、参数与退出码见 [`docs/cli.md`](docs/cli.md)；接口契约以 [`src/main/resources/http/openapi.yaml`](src/main/resources/http/openapi.yaml) 为准，有契约测试守着二者一致。

### 关键配置项

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `ARCHIVE_REDELIVERY_HOST` / `_PORT` | 无（必需） | 重投目标 MLLP 入口（通常与本机监听口不是同一台机器） |
| `ARCHIVE_MLLP_RECONNECT_MAX_ATTEMPTS` / `_BASE_MS` / `_MAX_MS` | `3` / `200` / `2000` | 重投途中对端断线的自愈预算 |
| `ARCHIVE_MLLP_CONNECT_TIMEOUT_MS` / `_READ_TIMEOUT_MS` | `5000` / `15000` | 建连与读对端回执的超时 |
| `ARCHIVE_ACK_MODE` | `framed_ack` | 猜错方言的表现是对端读包器卡死等帧尾而本地一切正常，因此由人配置；程序只给建议、不改配置 |

### CLI 子命令 / 接口端点

| 命令或路径 | 用途 | 关键参数 |
|------------|------|----------|
| `--check-config` | 校验环境变量并打印启动摘要（令牌一律 `***`） | 无 |
| `--mllp-smoke` / `--ack-contract-smoke` / `--api-smoke` | 三条零依赖自检路径：回环 socket 与真实帧字节 | `--ack-contract-smoke` 可带 `framed_ack` / `bare_ack` |
| `--capacity-benchmark` / `--recovery-scan` | 500 条容量基准；进程重启后从台账续跑 | 无 |
| `ledger batch-stats <batchId>` | 批次四态分布与拒收原因分布 | 无 |
| `ledger export <batchId>` | 清单 CSV（脱敏） | `--state PENDING_CONFIRM\|REJECTED`、`--out PATH` |
| `model audit --date D` / `model confidence-buckets --date D` | 模型调用审计导出；置信度分桶（估算今日复核工作量） | `--date`、`--tz`、`--out`、`--no-bom` |
| `obs scan-plaintext PATH...` | 扫日志与导出件的明文命中数（命中退出码 1） | `--names-file FILE`（同行可用逗号分隔多个） |
| `obs error-codes` / `obs batch-report <batchId>` | 导出错误码目录（44 条）；批次可观测报告 | `--out PATH` |
| `GET /healthz` | 探针（不带令牌），返回服务名、apiVersion、localAet、schemaVersion | 无 |
| `POST /batches/{batchId}/confirm` | **唯一落地通道**：待确认 → 已落地 | 体：`ledgerId` / `patientRef` / `localMrn`（+ 可选 `candidateSource` / `note`）；操作人只从令牌取 |
| `POST /batches/{batchId}/revert` | 撤回已落地条目回待确认重走 | 体：`ledgerId` / `reason` |
| `GET /messages` | 待办清单（确认前 `localMrn` 显式为空） | `?state=` `?batchId=` `?limit=` |
| `GET /batches/{batchId}/stats` / `/reconcile` | 批次四态统计与拒收原因分布；与对端回执做差集出**只读**重投清单（不入队） | 无 |

### 错误与降级

| 场景 | 表现 | 处理方式 |
|------|------|----------|
| 模型不可达 / 超时 / 返回不可解析 | 该条降级：进待确认并标 `degraded=true`，拆帧、硬校验与回执不受影响 | 人工确认仍可照常进行；`obs batch-report` 看得出降级条数 |
| 写院内患者主索引失败 | 状态留在**待确认**，报文原文与幂等键保留 | 可重试；重复确认同一院内主键被幂等抑制，不会写第二次 |
| 区域平台回执接口不可达 / 限流 | `502` / `503`，差集不产出 | 重投调度按退避重试；缺回执的条目仍可人工处理 |
| 对端断线（重投途中） | 记一笔失败 → 关旧连接 → 建新连接 → **重发同一份字节** | 受重连预算约束，超限转永久失败等人工放行 |
| 缺必需字段 / 字符集不支持 | `MSA-1=AE` 或 `AR` + `ERR` 段指向出错段字段 | `AE` 进重投集合、`AR` 不进；信息员看 `ERR-4` 中文说明 |
| 非白名单 AE 连接 | 连接被拒并记审计 | 核对 `ARCHIVE_REMOTE_AET` |
| 明文扫描扫到值 / 路径写错 | 退出码 `1`（逐条给 `文件:行号` 与**已打码**片段）/ 退出码 `3`（「一个文件都没扫到」） | 路径写错**不算干净**，避免变成永远为真的空断言 |

### 对接现有系统

| 对端 | 方向 | 协议 / 动作 | 字段规范 | 凭据来源 |
|------|------|-------------|----------|----------|
| 区域健康档案平台 | 收 + 回执 | HL7 v2.x over MLLP，双向 | 入站读 `MSH-9/10/11/12/18`、`PID-3/5/7`、`PV1` 与自定义 `Z` 段；出站回 `MSA-1=AA/AE/AR`，`AE`/`AR` 附 `ERR` 段（`ERR-1` 指向段与字段、`ERR-3` 带标准码与表来源） | `ARCHIVE_MLLP_HOST/PORT`、`ARCHIVE_LOCAL_AET`、`ARCHIVE_REMOTE_AET` |
| 院内 HIS 患者主索引 | **写** | `PUT {HIS_MPI_ENDPOINT}/patient-index`，`Authorization: Bearer` | 体含 `patientRef`（区域主键）/ `localMrn`（院内主键）/ `sourceBatch`，双主键分列不合并 | `HIS_MPI_ENDPOINT`、`HIS_MPI_TOKEN` |
| 区域平台回执对账 | **读** | `GET {ARCHIVE_ACK_API_BASE_URL}/batches/{batchId}/acks`，`Authorization: Bearer` | 响应须含 `acks` 数组，每条带控制号与 `MSA-1`；缺 `acks` 或条目缺控制号按响应不合规处理 | `ARCHIVE_ACK_API_BASE_URL`、`ARCHIVE_ACK_API_TOKEN` |
| 院内既有系统 | 对本院 | 上表 6 个端点 | `Authorization: Bearer` 或 `X-Archive-Api-Token`；操作人只从令牌取，请求体里的 `operator` 被忽略 | `ARCHIVE_API_TOKEN` |

未实连院内系统时的验证方式：三个零依赖自检开关在本机回环口上走真实 socket、真实帧字节与真实 HTTP；HIS 与对端的正式接口在测试里用同协议的本地替身跑契约——替身只用于验证，不是最终对接方式。

---

## 典型场景

### 场景 1：区域平台上午推包，信息员当天就知道哪几条没接住

监听口常开，区域平台把批次推过来；系统逐条拆帧、解析、对齐，每条回一个 `MSA` 回执并落一条台账；一次调用结束，四态条数与回执条数对得上。

```bash
java -jar target/regional-archive-ingest-ack.jar --ack-contract-smoke
# 回执报文条数=2；MSA-1=AA 与 MSA-1=AE（ERR 指向 MSH-10）
# 应用级账页：已接收=1 待确认=1 已落地=0（已落地为 0：还没人点确认）
```

### 场景 2：下午按批次核对，导出一份脱敏清单给科里留档

```bash
set -a && . ./.env && set +a
java -jar target/regional-archive-ingest-ack.jar ledger batch-stats ARCHIVE-20261020-A
java -jar target/regional-archive-ingest-ack.jar ledger export ARCHIVE-20261020-A --state PENDING_CONFIRM --out /tmp/pending.csv
# 清单行数与台账条数一致；姓名与证件只出哈希前 8 位
```

### 场景 3：处理停在待确认的条目，人点一下才落地

院内系统按 `GET /messages?state=PENDING_CONFIRM` 取待办、给信息员看身份候选与依据字段；信息员选中候选后回填 `patientRef` 与 `localMrn`，系统写院内患者主索引并把条目转为已落地——重复确认返回 409 而不是静默成功，操作人由令牌带出。

```bash
curl -sS -H "Authorization: Bearer $ARCHIVE_API_TOKEN" "http://127.0.0.1:8080/messages?state=PENDING_CONFIRM"
curl -sS -X POST -H "Authorization: Bearer $ARCHIVE_API_TOKEN" -H 'Content-Type: application/json' \
  -d '{"ledgerId":1,"patientRef":"PAT-0001","localMrn":"MRN-884512"}' \
  "http://127.0.0.1:8080/batches/ARCHIVE-20261020-A/confirm"
```

### 场景 4：次日平台追问哪几条没收到，只补那几条

取对端回执列表与本地台账按控制号归一后做差集，得到可重投清单、其余条目一个不碰，`AR` 类不进重投；重投收到 `AA` 后转「重投已确认」，超退避上限转永久失败等人工放行。

```bash
java -jar target/regional-archive-ingest-ack.jar --api-smoke       # 第 8 步：对账差集缺 1 条（ARCHIVE-0002）
java -jar target/regional-archive-ingest-ack.jar --recovery-scan  # 进程重启后从台账续跑
```

---

## 输出样例

### 逐条回执报文（`--mllp-smoke` 真实回显）

```text
MSH|^~\&|CHSCOMMUNITY|REGIONAL_ARCHIVE|REGIONAL_ARCHIVE|FACILITY_A|20261021032346||ACK^ACK^HL7_v2.5.1|ACK00000120261021032346|P|2.5.1|||AL|NE||UNICODE UTF-8
MSA|AA|SMOKE-CTRL-0001|||||||ACK00000120261021032346|2.5.1|
[smoke] received=1 acked=1
```

拒收时附 `ERR` 段指向出错段字段，`ERR-3` 带标准码与表来源，对端可直接解析：

```text
MSA|AE||必需字段缺失或为空：MSH-10
ERR|control-id-missing^MSH^10||control-id-missing^必需字段缺失或为空|MSH-10（报文控制号）缺失或为空…
```

### 批次四态统计（`GET /batches/{batchId}/stats` 真实响应）

```json
{"batchId":"ARCHIVE-20261020-A","total":3,"landed":0,"pendingConfirm":2,"received":0,"rejected":1,
 "counts":{"RECEIVED":0,"PENDING_CONFIRM":2,"LANDED":0,"REJECTED":1},"rejectionReasons":{"missing-required-field":1},
 "headline":"批次 ARCHIVE-20261020-A 共 3 条：已落地 0 / 待确认 2 / 拒收 1"}
```

### 待确认清单与对账差集（真实响应片段）

```json
{"state":"PENDING_CONFIRM","total":2,"items":[{"ledgerId":2,"controlId":"ARCHIVE-0002","state":"PENDING_CONFIRM","localMrn":""}]}
{"expectedCount":2,"remoteAckCount":1,"matchedCount":1,"missingCount":1,"missing":[{"controlId":"ARCHIVE-0002","reason":"对端回执列表里没有这一条"}]}
```

---

## 概念 / 实测对照

### 视觉目标

本产品**无图形界面**，交付物是报文与接口，不是网页。视觉目标落在**服务运行日志**与**curl 终端会话**上（详见 [`docs/concepts/visual-target.md`](docs/concepts/visual-target.md)）：起始态是一屏启动摘要（监听端口、双方 AE Title、回执方言、schema 版本，**不打印令牌明文**）；首个可用瞬间是一帧 `0B … 1C 0D` 进监听口、回一帧 `0B 06 1C 0D`、终端打出 `received=1 acked=1`；成形态是一次批次的运行摘要——四态条数与回执条数并列，平台追问后只打印差集那几条的重投结果。本环境不具备图像生成能力，`docs/concepts/` 只保留**结构化视觉目标卡**，不伪造概念图。

### 实测证据

`docs/screenshots/` 下为本轮真实跑出来的运行产物，全部来自可重放的 CLI 入口，不是概念图也不是设计稿。

| 文件 | 对应目标 | 真实产物说明 |
|------|----------|--------------|
| [`01-startup-summary.txt`](docs/screenshots/01-startup-summary.txt) | 起始态 | `--check-config` 打印的监听端口、AE、方言建议与 `secrets=[…***]`，令牌明文一律 `***` |
| [`02-mllp-smoke.txt`](docs/screenshots/02-mllp-smoke.txt) | 首个可用瞬间 | `--mllp-smoke` 真实 socket 跑一帧出 MSH/MSA 回执，控制号逐字回显，`received=1 acked=1` |
| [`03-ack-contract-framed.txt`](docs/screenshots/03-ack-contract-framed.txt) / [`03-ack-contract-bare.txt`](docs/screenshots/03-ack-contract-bare.txt) | 方言差异 | 传输级实测 `0x0B 0x06 0x1C 0x0D` 与裸 `0x06`；应用级 MSA 报文逐字节相同 |
| [`04-capacity-benchmark.txt`](docs/screenshots/04-capacity-benchmark.txt) | 规模 | 500 条端到端：P50=3.56ms / P95=13.45ms / P99=33.09ms（阈值 200ms 通过）、处理链异常 0 帧 |
| [`05-api-smoke.txt`](docs/screenshots/05-api-smoke.txt) | 端到端接口 | 真实 HTTP/1.1 八步：healthz → 401 鉴权 → 待办查询 → confirm → 重复确认 409 → revert → 四态统计 → 对账差集 |
| [`06-batch-four-state-stats.json`](docs/screenshots/06-batch-four-state-stats.json) / [`07-pending-confirm-list.json`](docs/screenshots/07-pending-confirm-list.json) / [`08-reconcile-diff.json`](docs/screenshots/08-reconcile-diff.json) | 导出门面 | 三段真实响应；待确认清单不含姓名与证件号，`AR` 类单列 `uncomparableControlIds` 不入重投 |
| [`09-obs-scan-plaintext.txt`](docs/screenshots/09-obs-scan-plaintext.txt) | 安全出口 | 8 段真实形态（身份证号 / 手机号 / 中文姓名）扫描结果，连续数字与非 ASCII 全部命中并替换 |
| [`10-verify-script-run.txt`](docs/screenshots/10-verify-script-run.txt) | 一键复现 | `bash scripts/verify.sh` 完整一次真实运行：构建 → 配置自检 → 起常驻进程 → 打 4 条真实报文 → curl 打端点，42 项断言逐条 PASS |

> **诚实标注**：`docs/concepts/` 侧是**结构化视觉目标卡**（无图像生成能力，不放概念图），`docs/screenshots/` 侧是**真实运行产物的回显与导出片段**，两者是「目标叙述 vs 真实文本片段」的一一对应，**不是**「目标图 vs 实测图」的并排布局。规模上实测跑的是 500 条而不是 8 条（8 条暴露不出瓶颈）；未做跨进程、多实例与长时间稳定性压测，这部分结论尚未有实测证据。

---

## 架构与数据流

```
  区域健康档案平台          院内前置机（本系统）                    院内既有系统 / HIS
       │                          │                                     │
       │ 0B [MSH PID PV1 ZX] 1C 0D ▼                                     │
       │                 ┌────────────────────┐                           │
       │                 │ mllp 拆帧组帧        │ 真实 Socket / SOM 0x0B    │
       │                 │ hl7 结构化解析        │ 分隔符与字符集从 MSH 自读  │
       │                 │ model Z 段对齐+候选   │ 模型不可达即降级            │
       │                 │ ledger 接收台账       │ 四态 + 幂等键 + 乐观锁      │
       │                 └──────────┬─────────┘ 每次迁移写一条审计行       │
       │◀─ 0B 06 1C 0D + MSA AA/AE/AR ┤（逐条一条回执，AE/AR 附 ERR）      │
       │                            ▼ 待确认                              │
       │                 ┌────────────────────┐                            │
       │                 │ ConfirmGate 确认门   │◀── operator 来自令牌 ─────┤
       │                 └──────────┬─────────┘   POST /batches/{id}/confirm
       │        HTTP PUT {HIS_MPI_ENDPOINT}/patient-index ───────────────▶ │
       │  次日追问 GET /batches/{id}/acks ─▶ redelivery 差集 → 只重投缺的几条
       │                              指数退避 + 上限 + 人工放行；AR 类永不重投
       ▼                            ▼                                     ▼
  回执列表（对账）      GET /messages?state= · GET /batches/{id}/stats   批次四态统计 / 待确认清单
```

单条报文的完整旅程（每一步的代码位置、产物与失败去向、台账表清单）见 [`docs/architecture.md`](docs/architecture.md)。

### 关键不变量

- **幂等键 = 消息类型 + 控制号 + 处理标识**，数据库唯一约束兜底；同一条重发 100 次，写进院内档案的次数恰好为 1。
- **待确认 → 已落地只能经确认门**：没有人工确认动作时写入调用次数必须为 0；模型输出永远只是候选。
- **拒收不进重投队列**：`AR` 永不自动重投，`AE` 才进；超退避上限转永久失败，须人工放行。
- **失败不静默改数据**：状态迁移与审计写在同一事务里，任一步失败整体回滚；写 HIS 失败时保留报文原文与幂等键，状态留在待确认可重放。
- **迁移级 `ACK` 不等于业务已落地**：传输层的 `0B 06 1C 0D` 只确认收到字节，应用层的 `MSA` 才是可对账结果；审计链亦不得断裂（审计行数须等于行版本号，裸改状态会在取数前被一致性校验挡住）。

---

## 安全与合规边界

- 本系统只做**字段对齐与数据流转**，不输出诊断、治疗建议或任何临床结论，不自动签名或代签。
- **人工确认是唯一落地通道**：模型给出的身份候选与字段映射只是候选，状态推进必须经确认门的显式动作，操作人来自接口令牌而非请求体。
- **凭证与标识**只从环境变量读取，启动日志不打印令牌明文；姓名、证件号、手机号在日志与台账导出中只出 SHA-256 前 8 位与主键片段，可用 `obs scan-plaintext` 自证；身份证硬校验冲突的条目一律不写 HIS。

---

## 验收与自检

| 验收项 | 命令 | 期望结果 |
|--------|------|----------|
| 依赖可离线解析 | `mvn -o -q dependency:resolve` | 退出码 0 |
| 全量测试通过 | `mvn -o test` | `Tests run: 649, Failures: 0, Errors: 0` |
| 真实帧往返 | `java -jar target/regional-archive-ingest-ack.jar --mllp-smoke` | `received=1 acked=1`，回执 `MSA-1=AA` |
| 两种回执方言 | `java -jar target/regional-archive-ingest-ack.jar --ack-contract-smoke bare_ack` | 传输级实测 `0x06`；应用级两条 MSA（`AA` / `AE+ERR`） |
| 单批 500 条容量 | `java -jar target/regional-archive-ingest-ack.jar --capacity-benchmark` | `P99 阈值 200ms → 通过（实测 ≈33ms）`，处理链异常 0 帧 |
| 院内接口八步契约 | `java -jar target/regional-archive-ingest-ack.jar --api-smoke` | `API 自检 PASS`（含 401、409、四态统计、对账差集） |
| 重放 100 次只写 1 次 | `mvn -o test -Dtest=IdempotencyConcurrencyTest` | `test_100_replays_write_count_is_1` 通过 |
| 无确认零写入 | `mvn -o test -Dtest=ConfirmGateTest` | `test_no_confirm_means_zero_writes` 通过 |
| 明文扫描为零 | `java -jar target/regional-archive-ingest-ack.jar obs scan-plaintext ../logs` | 打印「明文命中 0 处」，退出码 0 |
| 缺凭据即退出 | `( set -a; . ./.env.example; set +a; unset HIS_MPI_TOKEN; java -jar target/regional-archive-ingest-ack.jar --check-config )` | 退出码 2，stderr 含「缺失环境变量 HIS_MPI_TOKEN」 |

---

## 项目结构

```
regional-archive-ingest-ack/
├── pom.xml / .env.example / .gitignore   # 构建（Java 17，4 个依赖）/ 环境变量样例 / 排除运行时产物
├── README.md / qrcode.jpg                # 本文件 / 关注公众号二维码
├── docs/
│   ├── architecture.md                    # 完整数据流、单条报文旅程、台账表清单
│   ├── cli.md                             # 全部子命令、参数与退出码速查
│   ├── concepts/                          # 结构化视觉目标卡（无图像生成能力，不放概念图）
│   └── screenshots/                       # 真实运行产物：终端回显与接口导出片段
├── scripts/                               # verify.sh：安装→配置→使用→验证一条命令跑完（42 项断言）；fetch_fixtures.sh：可重跑的语料生成（断网降级到本地缓存）；verify/VerifyLive.java：验证侧常驻进程与真实帧发送器
└── src/
    ├── main/java/cn/hospital/region/archive/
    │   ├── Main.java                      # 进程入口与退出码：0 正常 / 2 缺凭据 / 64 用法错
    │   ├── config/                        # 全环境变量解析、只读库配置、fail fast
    │   ├── mllp/                          # 真实 Socket 拆帧组帧、监听、重连、回执方言、容量基准
    │   ├── hl7/                           # 分隔符自读、段-字段索引、转义还原、字符集、错误定位
    │   ├── ack/                           # MSA 三值回执组装、ERR 段、HL7 标准错误码目录
    │   ├── ledger/                        # 接收台账：四态状态机、幂等守卫、乐观锁、审计链、导出
    │   ├── model/                         # 模型网关、Z 段对齐、身份候选、确定性基线、置信度审计
    │   ├── identity/                      # 身份证硬校验、确认门、HIS 患者主索引真实 HTTP 写入
    │   ├── redelivery/                    # 重投队列、指数退避与人工放行、回执对账差集
    │   ├── http/  obs/  cli/  startup/    # HTTP API 与契约 / 脱敏与明文扫描 / 只读排障 / 重启续跑
    ├── main/resources/                    # db/migration（V1–V5 建表索引，PG/H2 同源）+ http/openapi.yaml（6 个端点契约）
    └── test/                              # java：真实 socket / 帧字节 / HTTP / 事务契约测试
        └── resources/fixtures/             # 测试语料：合成 ER7、官方样例、中文变体（简繁/同音/GB18030）
```

---

## License

MIT

---

## 关注我们

欢迎扫码关注公众号，获取项目更新与交流加群：

![关注我们](qrcode.jpg)