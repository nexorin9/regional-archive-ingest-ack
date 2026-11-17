# 架构与数据流（完整版）

README 里放的是压缩图；这里是完整的主路径与分支，含每一步的产物与失败去向。

## 完整数据流

```
        区域健康档案平台                        院内前置机（本系统）                     院内既有系统
             │                                       │                                   │
             │  HL7 v2 over MLLP/TCP 2575             │                                   │
             ├─────── 0B [ MSH PID PV1 ZX ] 1C 0D ───▶│                                   │
             │                                       ▼                                   │
             │                              ┌─────────────────┐                            │
             │                              │  mllp  拆帧组帧  │  SOM/EOM 状态机              │
             │                              │  （真实 Socket）  │  方言：framed / bare         │
             │                              └────────┬────────┘                            │
             │                                       ▼                                     │
             │                              ┌─────────────────┐                            │
             │                              │ hl7  结构化解析  │  分隔符从 MSH 自读           │
             │                              │ 按 MSH-18 解码  │  转义还原 + 字段级错误定位   │
             │                              └────────┬────────┘                            │
             │                                       ▼                                     │
             │                              ┌─────────────────┐      确定性基线先跑           │
             │                              │ model Z 段对齐   │──────┐ （必填/字符集/身份证） │
             │                              │ 身份候选（只给候选）│      │                     │
             │                              └────────┬────────┘      │                     │
             │                                不可达 │ 降级           │                     │
             │                                       ▼      ▼                             │
             │                              ┌─────────────────┐                            │
             │                              │ ledger 接收台账  │  四态 + 幂等键 + 乐观锁      │
             │                              │  已接收→待确认   │  每次迁移写一条审计行       │
             │                              │  →已落地 / 拒收  │                            │
             │                              └────────┬────────┘                            │
             │                                       │                                     │
             │◀──── 0B 06 1C 0D + MSA AA/AE/AR ───────┤（逐条一条回执）                      │
             │      (+ ERR 段指向出错段字段)           │                                     │
             │                                       │ 待确认                              │
             │                                       ▼                                     │
             │                              ┌─────────────────┐                            │
             │  POST /batches/{id}/confirm │ 确认门 ConfirmGate │◀── operator from token ──┤
             │                              │  唯一落地通道     │                            │
             │                              └────────┬────────┘                            │
             │                                       ▼                                     │
             │                    HTTP PUT {HIS_MPI_ENDPOINT}/patient-index                 │
             │                            ┌──────────────────┐                              │
             │                            │ 院内 HIS 患者主索引 │                              │
             │                            └──────────────────┘                              │
             │                                                                               │
             │        次日追问 ── GET {ARCHIVE_ACK_API_BASE_URL}/batches/{id}/acks ──┐       │
             │                              ┌─────────────────┐                     │       │
             │                              │redelivery 补偿重投│◀────────────────────┘       │
             │                              │  差集 → 只重投缺的几条                            │
             │                              │  指数退避 + 上限 + 人工放行                       │
             │                              └─────────────────┘                            │
             │                                                                               │
             ▼                                        ▼                                    │
        区域平台回执列表                     GET /messages?state= · GET /batches/{id}/stats ────┘
                                             批次四态统计 / 待确认清单 / 拒收清单
```

## 一条报文在系统里的完整旅程

| 阶段 | 代码位置 | 产物 | 失败去向 |
|------|----------|------|----------|
| 收帧 | `mllp/MllpServer` → `mllp/InboundFramePipeline` | 完整一帧字节（半包/粘包已合并） | 帧中途断流抛 `MllpProtocolException`，连接关闭，不产生台账行 |
| 结构化 | `hl7/FrameDecoder` → `hl7/Hl7Parser` | `Hl7Message`（段/字段索引 + 转义已还原） | 字符集不支持 → `AR` + `unsupported-charset`；字段缺失 → `FieldLocator` 定位到段与字段序号 |
| 硬校验 | `identity/IdentityHardRules` | 三态硬判定（必填 / 字符集 / 身份证校验位） | 身份证冲突直接 `AR`，不写 HIS、不出候选 |
| 语义对齐 | `model/ZFieldAligner` + `model/IdentityCandidateMatcher` | 候选与置信度（**不推进状态**） | 模型不可达 → `DegradedResult`，该条进待确认并标 `degraded=true` |
| 入账 | `ledger/ArchiveIngestService.ingest` → `JdbcLedgerStore` | 台账一行 + 幂等键 | 唯一约束冲突 → 返回已处理，不重复入账 |
| 回执 | `ack/AckComposer` | 一条 MSA（+ 可选 ERR 段） | 组不出 ERR 的拒收一律拒绝发出（不发明错误码） |
| 确认 | `identity/ConfirmGate` → `ledger/ArchiveIngestService.land` | 台账 `LANDED` + HIS 写入一次 | 写 HIS 失败 → 退回 `PENDING_CONFIRM`，保留原文与幂等键，可重放 |
| 重投 | `redelivery/RedeliveryScheduler` | 对端回 `AA` → `RETRY_CONFIRMED` | 超退避上限 → `RETRY_PERMANENT_FAILED`，等人工放行；`AR` 类永不自动重投 |

## 台账表

| 表 | 作用 | 关键约束 |
|----|------|----------|
| `message_ledger` | 报文级四态主表，幂等键三列 | `uk_ledger_idem_key(MSH-9, MSH-10, MSH-11)` 唯一；`version` 乐观锁 |
| `message_audit` | 每次状态迁移一条审计行 | 审计行数须等于 `version`，否则 `LedgerAuditChain.verify` 报链断 |
| `identity_key_binding` | 区域主键 ↔ 院内主键双主键绑定 | 同区域主键不得绑到不同院内主键 |
| `model_call_audit` | 模型调用审计（可导出、可分桶） | 只落主键片段与摘要，不落明文 |
| `redelivery_queue` | 补偿重投队列 | 退避次数与下次执行时间持久化，重启后可续跑 |

迁移脚本在 `src/main/resources/db/migration/`，同一份 SQL 在 PostgreSQL 与 H2 PG 兼容模式下都能跑，不分叉。