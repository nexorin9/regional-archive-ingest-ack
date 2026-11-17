# 结构化视觉目标卡 · 区域健康档案接入与回执系统

> 本产品**无图形界面**。交付物是**报文、接口与终端运行日志**，不是网页。因此视觉目标锁定在
> **服务运行日志**与 **curl 终端会话**两种画面上，而不是设计网页截图。
>
> **本环境不具备图像生成能力**，故本文件只保留结构化目标卡，**不生成概念图**，
> 也不放置任何占位图 / 示意图冒充视觉目标。

---

## 画面类型

| 画面 | 对应内容 | 出现时机 |
|------|---------|---------|
| 服务运行日志 | 进程启动摘要、批次处理摘要、重投摘要 | 常驻进程 stdout |
| 终端会话（curl） | 五个 HTTP 端点的真实请求与响应 | 运维排障 / 契约验证 |

---

## 三个状态

### 起始态 —— 进程刚起来的一屏启动摘要

```
[archive-ingest] 启动中…
[archive-ingest]   MLLP 监听        0.0.0.0:2575
[archive-ingest]   本院 AE Title    CHSCOMMUNITY
[archive-ingest]   对端 AE 白名单   REGIONAL_ARCHIVE
[archive-ingest]   回执方言         framed_ack  (0B 06 1C 0D)
[archive-ingest]   台账 schema      V1 (H2 PG 兼容模式)
[archive-ingest]   HIS 端点主机     his-mpi.example.internal:8080
[archive-ingest]   模型网关         model-gateway.example.internal/v1 (model=<NAME>)
[archive-ingest]   恢复扫描         待重投 0 条 / 未终态 0 条 (耗时 12ms)
[archive-ingest] 就绪。等待区域平台推包。
```

**画面上有**：一行一个配置项，左对齐冒号对齐，令牌一律不打印明文。

### 首个可用瞬间 —— 一帧真实字节进监听口，回一帧回执

```
[archive-ingest] <- 0B ... 1C 0D   帧长 428 字节  控制号 MSG000123
[archive-ingest]    MSH-9=ADT^A01 MSH-11=P MSH-12=2.3 MSH-18=GB18030 分隔符自读 OK
[archive-ingest]    PID-3=PAT-2024-000731 PID-5=<脱敏> PID-7=<脱敏>
[archive-ingest] -> 0B 06 1C 0D   MSA-1=AA
[archive-ingest]    台账 已接收 → 待确认   (batch=B20241018-01)
[archive-ingest] received=1 acked=1
```

**画面上有**：入站帧的字节长度与控制号、解析出的关键字段位（脱敏）、出站回执的 MSA-1、台账状态迁移。

### 成形态 —— 一次 8 条批次的运行摘要 + 次日差集重投

```
[archive-ingest] 批次 B20241018-01 处理完成
[archive-ingest]   已落地 5   待确认 2   拒收 1        回执条数 8  (= 报文条数 8 ✓)
[archive-ingest]   待确认明细:  MSG000124  anchor=MISSING  候选置信度 0.81
[archive-ingest]                  MSG000127  Z 段字段对齐未过阈值 0.62
[archive-ingest]   拒收明细:    MSG000131  AR 103^Table value not found^HL70357  ERR-3=PID-3
[archive-ingest] 次日对账 B20241018-01
[archive-ingest]   应发 8  对端已收 6  差集 2 → 重投队列
[archive-ingest]   重投 MSG000124 → AA  待确认 → 已落地
[archive-ingest]   重投 MSG000127 → AE  退避 30s 后第 2 次
[archive-ingest] 对账完成。其余 6 条台账状态未变更，写入次数仍为 3。
```

**画面上有**：四态分布与回执条数**并列**（这是可核对的关键）、待确认与拒收的逐条依据、差集条数、重投结果与「其余未变更」的断言。

---

## 视觉语言

| 维度 | 规定 |
|------|------|
| 前景色 | `#1F2933`（深灰蓝）——终端默认前景 |
| 成功 | `#2E7D32`（已落地 / `AA`） |
| 待人工 | `#B26A00`（待确认 / 降级） |
| 危险 | `#C62828`（拒收 / `AR` / 错误码） |
| 提示 | `#1565C0`（启动摘要字段名、区块标题） |
| 字体 | 系统中文栈 `-apple-system, "PingFang SC", "Microsoft YaHei"`；日志用等宽字体栈 |
| 数字 | `tabular-nums` 等宽对齐，控制号与计数纵向对齐 |
| 留白 | 8px 栅格；摘要每项之间不空行，同区块内密集排列，区块之间空一行 |
| 层级 | ①批次四态分布（一行汇总，最显眼）②逐条明细（缩进两格）③配置摘要（字段名提示色 + 值前景色） |
| 时间戳 | 统一 `YYYY-MM-DD HH:mm:ss` |

---

## 必须可达的约束

本环境**只有** JDK 17+、Maven、JUnit 5、PostgreSQL 驱动与 H2，**没有**任何 Web 框架、图形库或前端构建链。因此：

- ✅ 可做：终端日志着色、`java.net.http.HttpClient` 的 curl 会话、真实 MLLP 帧字节回显、CSV 导出片段。
- ✅ 可做：OpenAPI 契约文档 + 契约测试（产物是文件，不是界面）。
- ❌ **不做**：Web 面板、看板、大屏、ECharts 图表 —— 编排层已锁定「不做界面」，确认动作由院内既有系统承担。
- ❌ **不做**：任何依赖现有技术栈做不出来的视觉效果。

> 目标画面要**像真实可运行的日志与会话回显**，不是宣传图。README 的「概念 / 实测对照」小节
> 会并排展示本卡与 `docs/screenshots/` 下的真实产物，并**诚实写出两者差距**（例如启动摘要
> 某些字段在实测那一步尚未落地、实测批次条数与本卡举例不同）。