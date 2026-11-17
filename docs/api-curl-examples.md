# API curl 示例

> 本文档每条命令都已通过 `mvn -f pom.xml -o package` 后的 `java -cp` 自检
> 与 `ApiSmoke` 一致；**写在这里的每条命令都实跑过**，复制粘贴即可。

## 0. 启动服务

```bash
# 一次性：编译并起可执行 jar
mvn -o package

# 起进程：监听 127.0.0.1:8080，加载 .env.example 里的凭据
set -a; . .env.example; set +a
java -jar target/regional-archive-ingest-ack.jar --port 8080 &
sleep 2

# 验证活没活：探针不需要令牌
curl -sS http://127.0.0.1:8080/healthz
```

预期响应：

```json
{"apiVersion":"v1","localAet":"CHSCOMMUNITY","schemaVersion":"V5","service":"regional-archive-ingest-ack","status":"UP","time":"2026-10-21 11:30"}
```

下面所有调用都用环境变量复用同一组凭据：

```bash
BASE=http://127.0.0.1:8080
TOKEN=test-token-not-a-real-secret          # 与 .env.example 同
OPER=clerk-curl                              # 操作人主体名
AUTH="Authorization: Bearer ${OPER}:${TOKEN}"
CT='Content-Type: application/json'
```

## 1. `GET /healthz`

```bash
curl -sS "$BASE/healthz"
```

预期：200 + `status=UP` / `service=regional-archive-ingest-ack`。**不带令牌**。

## 2. `POST /batches/{batchId}/confirm` —— 唯一落地通道

```bash
# 假设台账里有 ledgerId=42、batchId=ARCHIVE-20261020
curl -sS -X POST "$BASE/batches/ARCHIVE-20261020/confirm" \
  -H "$AUTH" -H "$CT" \
  -d '{"ledgerId":42,"patientRef":"PAT-2026102042","localMrn":"MRN-42"}'
```

预期：200 + 响应体含 `state:"LANDED"` 与 `wrotePatientIndex:true`。

错误路径：

```bash
# 不带令牌 → 401 TOKEN_MISSING
curl -sS -X POST "$BASE/batches/ARCHIVE-20261020/confirm" \
  -H "$CT" -d '{"ledgerId":42,"patientRef":"X","localMrn":"Y"}'

# 缺 ledgerId → 400 MISSING_FIELD
curl -sS -X POST "$BASE/batches/ARCHIVE-20261020/confirm" \
  -H "$AUTH" -H "$CT" -d '{"patientRef":"X","localMrn":"Y"}'

# 跨批次 → 404 LEDGER_ROW_NOT_FOUND（实际批次号原样带回）
curl -sS -X POST "$BASE/batches/ARCHIVE-OTHER/confirm" \
  -H "$AUTH" -H "$CT" -d '{"ledgerId":42,"patientRef":"X","localMrn":"Y"}'

# 已落地重复确认 → 409 STATE_CONFLICT（响应体带当前 state="LANDED"）
curl -sS -X POST "$BASE/batches/ARCHIVE-20261020/confirm" \
  -H "$AUTH" -H "$CT" -d '{"ledgerId":42,"patientRef":"X","localMrn":"Y"}'
```

## 3. `POST /batches/{batchId}/revert` —— 撤回

```bash
curl -sS -X POST "$BASE/batches/ARCHIVE-20261020/revert" \
  -H "$AUTH" -H "$CT" \
  -d '{"ledgerId":42,"reason":"信息员发现合错了，撤回重走"}'
```

预期：200 + `reverted:true` + `state:"PENDING_CONFIRM"`。

错误路径：

```bash
# 拒收条目被撤回 → 409
curl -sS -X POST "$BASE/batches/ARCHIVE-20261020/revert" \
  -H "$AUTH" -H "$CT" -d '{"ledgerId":99,"reason":"撤回拒收条目"}'

# 缺 reason → 400 MISSING_FIELD
curl -sS -X POST "$BASE/batches/ARCHIVE-20261020/revert" \
  -H "$AUTH" -H "$CT" -d '{"ledgerId":42}'
```

## 4. `GET /messages`

```bash
# 列本批次所有待确认的条目
curl -sS "$BASE/messages?state=PENDING_CONFIRM&batchId=ARCHIVE-20261020" \
  -H "$AUTH"

# 不限批次，只取前 50 条 LANDED
curl -sS "$BASE/messages?state=LANDED&limit=50" -H "$AUTH"
```

错误路径：

```bash
# 缺 state → 400 MISSING_FIELD
curl -sS "$BASE/messages" -H "$AUTH"

# state 拼错 → 400 INVALID_PARAMETER，响应 message 里把合法取值列出来
curl -sS "$BASE/messages?state=RECEVIED" -H "$AUTH"
```

## 5. `GET /batches/{batchId}/stats`

```bash
curl -sS "$BASE/batches/ARCHIVE-20261020/stats" -H "$AUTH"
```

预期：200 + 四态分布 `counts.{RECEIVED, PENDING_CONFIRM, LANDED, REJECTED}` 与拒收原因占比
`rejectionReasons`。

## 6. `GET /batches/{batchId}/reconcile`

```bash
curl -sS "$BASE/batches/ARCHIVE-20261020/reconcile" -H "$AUTH"
```

预期：200 + `matched` / `missing` 两个清单；**不入队**，入队由重投调度决定。

## 7. 自带的冒烟链路

```bash
# 起一个真实可跑的进程，跑一遍八条混合批次（5 已落地 / 2 待确认 / 1 拒收），
# 然后立刻打印 stats / reconcile 的当前快照 —— 测试用，亦可在 CI 上跟。
java -jar target/regional-archive-ingest-ack.jar --api-smoke
```

## 8. 三点纪律

- **操作人只从令牌**：请求体里就算带了 `operator` 也会被忽略；
  总是由 `${OPER}` 这段字符串决定审计行里的「谁点的」。
- **`Content-Type` 必须 `application/json`**：本端按 MIME 选解析器，别的 MIME 一律 400。
- **错误体最小三栏**：`errorCode` / `message` / `status`。其他字段进 `detail`。
  `errorCode` 取值见 `src/main/resources/http/openapi.yaml` 的 `ErrorBody.enum`，
  它必须与 `src/main/java/cn/hospital/region/archive/http/ApiErrorCode.java`
  **双向相等**（`OpenApiConsistencyTest` 守住）。
