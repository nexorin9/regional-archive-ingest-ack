#!/usr/bin/env bash
#
# 发布前可复现验证：安装 → 配置 → 使用 → 验证，一条命令跑完。
#
#   bash scripts/verify.sh
#
# 它做什么（全部真跑，不打桩）：
#   1. mvn -o -q package 构建出可执行 jar；
#   2. 加载 .env.example 走一次 --check-config，再故意抽掉一个凭据证明 fail fast；
#   3. 用产品自身的真实部件装配一个常驻进程：真实 ServerSocket 的 MLLP 监听口 +
#      真实 HttpApiServer + 内嵌 H2 的同一份迁移 SQL（详见 scripts/verify/VerifyLive.java）；
#   4. 从真实 socket 打 4 条报文（3 条齐全 + 1 条故意缺 MSH-10），逐条读回 MSA 回执；
#   5. 用 curl 打 7 个端点：/healthz、/messages（无令牌 401 与带令牌 200）、
#      /batches/{id}/confirm（含重复确认 409）、/batches/{id}/stats、
#      /batches/{id}/revert、/batches/{id}/reconcile（对端不可达 503）；
#   6. 断言「回执条数 == 台账条数」——这是本产品的核心闭环结论；
#   7. 打印逐项 PASS/FAIL 摘要，退出码 0 表示全过。
#
# 不连任何院内系统、不需要任何真实凭据：
#   - 台账走内嵌 H2 的 PG 兼容模式（同一份迁移 SQL，不分叉）；
#   - 患者主索引端口用只记日志的本地实现（生产是真实 HTTP PUT，验证期不发外呼）；
#   - 回执对账指向一个没人监听的端口，因此 /reconcile 必然走「对端不可达」降级 —— 这正是要验的；
#   - 所有绑定地址都是 127.0.0.1，唯一对外连接目标也是 127.0.0.1。
#
# 环境变量：
#   VERIFY_WITH_TESTS=1   构建时连跑全部单元/契约测试（默认只构建，跳过测试以省时间；
#                         全量测试请单独跑 `mvn -o test`）
#   VERIFY_MESSAGES=N     合法报文条数，默认 3（另加 1 条故意缺陷报文）
#
set -uo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_DIR" || exit 1

WORK="$(mktemp -d "${TMPDIR:-/tmp}/archive-verify.XXXXXX")"
SERVER_PID=""
PASS_COUNT=0
FAIL_COUNT=0
declare -a RESULTS=()

cleanup() {
  if [ -n "$SERVER_PID" ] && kill -0 "$SERVER_PID" 2>/dev/null; then
    kill "$SERVER_PID" 2>/dev/null
    wait "$SERVER_PID" 2>/dev/null
  fi
  rm -rf "$WORK"
}
trap cleanup EXIT

# ---------------------------------------------------------------------------
# 小工具
# ---------------------------------------------------------------------------

step() { printf '\n══ %s\n' "$1"; }

# check <名称> <期望描述> <实际值> <实际值是否等于期望>
check() {
  local name="$1" expect="$2" actual="$3" ok="$4"
  if [ "$ok" = "1" ]; then
    PASS_COUNT=$((PASS_COUNT + 1))
    RESULTS+=("PASS|$name|$expect|$actual")
    printf '   [PASS] %-38s 期望=%s 实际=%s\n' "$name" "$expect" "$actual"
  else
    FAIL_COUNT=$((FAIL_COUNT + 1))
    RESULTS+=("FAIL|$name|$expect|$actual")
    printf '   [FAIL] %-38s 期望=%s 实际=%s\n' "$name" "$expect" "$actual"
  fi
}

# 找一个能真正运行的 java（本机 /usr/bin/java 可能是坏掉的 stub）
resolve_java() {
  local candidates=()
  [ -n "${JAVA_HOME:-}" ] && candidates+=("$JAVA_HOME/bin/java")
  candidates+=("$(command -v java 2>/dev/null)")
  local home
  for home in /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
              /usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
              /usr/lib/jvm/*/Contents/Home /usr/lib/jvm/*; do
    candidates+=("$home/bin/java")
  done
  local c
  for c in "${candidates[@]}"; do
    [ -n "$c" ] && [ -x "$c" ] || continue
    if "$c" -version >/dev/null 2>&1; then
      printf '%s' "$c"
      return 0
    fi
  done
  return 1
}

# ---------------------------------------------------------------------------
# 0. 前置
# ---------------------------------------------------------------------------

step "0. 环境前置"
JAVA="$(resolve_java)" || { echo "找不到可运行的 JDK 17，请先安装或设置 JAVA_HOME"; exit 1; }
MVN="$(command -v mvn || true)"
CURL="$(command -v curl || true)"

printf '   java   = %s（%s）\n' "$JAVA" "$("$JAVA" -version 2>&1 | head -1)"
printf '   mvn    = %s\n' "${MVN:-<未找到>}"
printf '   curl   = %s\n' "${CURL:-<未找到>}"

check "JDK 17+ 可用" "版本号 ≥ 17" "$("$JAVA" -version 2>&1 | head -1)" \
  "$([ "$("$JAVA" -version 2>&1 | head -1 | sed 's/.*version "\([0-9]*\).*/\1/')" -ge 17 ] && echo 1 || echo 0)"
check "mvn 可用" "非空" "${MVN:-空}" "$([ -n "$MVN" ] && echo 1 || echo 0)"
check "curl 可用" "非空" "${CURL:-空}" "$([ -n "$CURL" ] && echo 1 || echo 0)"
if [ "$FAIL_COUNT" -gt 0 ]; then
  echo "前置不齐，终止。"; exit 1
fi

# ---------------------------------------------------------------------------
# 1. 构建
# ---------------------------------------------------------------------------

step "1. 构建可执行 jar"
MVN_ARGS=(-o -q package)
if [ "${VERIFY_WITH_TESTS:-0}" != "1" ]; then
  MVN_ARGS+=(-DskipTests)
  echo "   （默认跳过测试；全量测试请跑 mvn -o test，或设 VERIFY_WITH_TESTS=1）"
fi
"$MVN" "${MVN_ARGS[@]}" > "$WORK/build.log" 2>&1
BUILD_RC=$?
JAR="target/regional-archive-ingest-ack.jar"
check "mvn -o package" "退出码 0" "退出码 $BUILD_RC" "$([ $BUILD_RC -eq 0 ] && echo 1 || echo 0)"
check "可执行 jar 存在" "存在" "$([ -f "$JAR" ] && echo 存在 || echo 缺失)" \
  "$([ -f "$JAR" ] && echo 1 || echo 0)"
if [ $BUILD_RC -ne 0 ]; then
  tail -20 "$WORK/build.log"
fi

# ---------------------------------------------------------------------------
# 2. 配置自检：齐全可通过、缺一项即非零退出
# ---------------------------------------------------------------------------

step "2. 配置自检（凭据只从环境变量读，缺失即退出码 2）"
set -a
# shellcheck disable=SC1091
. "$PROJECT_DIR/.env.example"
set +a

"$JAVA" -jar "$JAR" --check-config > "$WORK/check-config.log" 2>&1
CFG_RC=$?
check "配置齐全时 --check-config" "退出码 0" "退出码 $CFG_RC" "$([ $CFG_RC -eq 0 ] && echo 1 || echo 0)"
CFG_LINE="$(head -1 "$WORK/check-config.log")"
check "启动摘要含 MLLP 监听端口" "mllp=<主机>:<端口>" \
  "$(printf '%s' "$CFG_LINE" | sed -n 's/.*\(mllp=[^ |]*\).*/\1/p')" \
  "$(printf '%s' "$CFG_LINE" | grep -q 'mllp=[^ |]*:[0-9]' && echo 1 || echo 0)"
MASKED_COUNT="$(printf '%s' "$CFG_LINE" | sed -n 's/.*secrets=\[\([^]]*\)\].*/\1/p' | grep -o '\*\*\*' | wc -l | tr -d ' ')"
check "五项凭据一律打码" "5 个 ***" "${MASKED_COUNT:-0} 个 ***" \
  "$([ "${MASKED_COUNT:-0}" = "5" ] && echo 1 || echo 0)"
LEAKED=""
for name in ARCHIVE_DB_PASSWORD HIS_MPI_TOKEN ARCHIVE_ACK_API_TOKEN ARCHIVE_API_TOKEN MODEL_API_KEY; do
  value="${!name:-}"
  if [ -n "$value" ] && printf '%s' "$CFG_LINE" | grep -qF "$value"; then LEAKED="$LEAKED $name"; fi
done
check "凭据明文不出现在启动日志" "0 处泄漏" "${LEAKED:-0 处泄漏}" \
  "$([ -z "$LEAKED" ] && echo 1 || echo 0)"

( unset HIS_MPI_TOKEN; exec "$JAVA" -jar "$JAR" --check-config ) > "$WORK/check-config-missing.log" 2>&1
MISS_RC=$?
check "缺 HIS_MPI_TOKEN 即退出" "退出码 2" "退出码 $MISS_RC" "$([ $MISS_RC -eq 2 ] && echo 1 || echo 0)"
check "错误信息点名变量" "含「缺失环境变量 HIS_MPI_TOKEN」" \
  "$(grep -o '缺失环境变量 HIS_MPI_TOKEN' "$WORK/check-config-missing.log" | head -1)" \
  "$(grep -q '缺失环境变量 HIS_MPI_TOKEN' "$WORK/check-config-missing.log" && echo 1 || echo 0)"

# ---------------------------------------------------------------------------
# 3. 起常驻进程（真实 MLLP 监听口 + 真实 HTTP API + 真实台账）
# ---------------------------------------------------------------------------

step "3. 起常驻进程：MLLP 监听口 + 院内 HTTP API + 台账"
CP="$JAR:target/lib/*"
VERIFY_API_TOKEN="verify-token-local-only"
"$JAVA" -cp "$CP" scripts/verify/VerifyLive.java serve 0 0 "$VERIFY_API_TOKEN" ARCHIVE-20261020-VERIFY \
  > "$WORK/serve.log" 2>&1 &
SERVER_PID=$!

READY=""
for _ in $(seq 1 90); do
  if grep -q '^READY ' "$WORK/serve.log" 2>/dev/null; then READY="$(grep '^READY ' "$WORK/serve.log" | head -1)"; break; fi
  kill -0 "$SERVER_PID" 2>/dev/null || break
  sleep 1
done
MLLP_PORT="$(printf '%s' "$READY" | sed -n 's/.*mllp=\([0-9][0-9]*\).*/\1/p')"
HTTP_PORT="$(printf '%s' "$READY" | sed -n 's/.*http=\([0-9][0-9]*\).*/\1/p')"
BATCH_ID="$(printf '%s' "$READY" | sed -n 's/.*batch=\([^ ]*\).*/\1/p')"
echo "   $READY"
check "MLLP 监听口已绑定" "本机回环端口" "127.0.0.1:${MLLP_PORT:-无}" \
  "$([ -n "$MLLP_PORT" ] && echo 1 || echo 0)"
check "院内 HTTP API 已监听" "本机回环端口" "127.0.0.1:${HTTP_PORT:-无}" \
  "$([ -n "$HTTP_PORT" ] && echo 1 || echo 0)"
if [ -z "$MLLP_PORT" ] || [ -z "$HTTP_PORT" ]; then
  echo "常驻进程未就绪，终止。"; tail -20 "$WORK/serve.log"; exit 1
fi
BASE="http://127.0.0.1:${HTTP_PORT}"
API_H=(-H "Authorization: Bearer clerk-verify:${VERIFY_API_TOKEN}")

# ---------------------------------------------------------------------------
# 4. 打真实 MLLP 报文：逐条一个应用级回执
# ---------------------------------------------------------------------------

step "4. 真实 MLLP 报文往返（3 条齐全 + 1 条故意缺 MSH-10）"
VALID_COUNT="${VERIFY_MESSAGES:-3}"
"$JAVA" -cp "$CP" scripts/verify/VerifyLive.java send 127.0.0.1 "$MLLP_PORT" "$VALID_COUNT" \
  > "$WORK/send.log" 2>&1
SEND_RC=$?
SUMMARY="$(grep '^SENT=' "$WORK/send.log" | head -1)"
echo "   $SUMMARY"
sed -n 's/^ACK /   /p' "$WORK/send.log"
EXPECTED_TOTAL=$((VALID_COUNT + 1))
check "每条报文一个应用级回执" "SENT=$EXPECTED_TOTAL ACKS=$EXPECTED_TOTAL" "${SUMMARY:-无回执摘要}" \
  "$(printf '%s' "$SUMMARY" | grep -q "SENT=$EXPECTED_TOTAL ACKS=$EXPECTED_TOTAL" && echo 1 || echo 0)"
check "齐全报文回执 MSA-1=AA" "MSA_AA=$VALID_COUNT" \
  "$(printf '%s' "$SUMMARY" | sed -n 's/.*MSA_AA=\([0-9]*\).*/\1/p')" \
  "$(printf '%s' "$SUMMARY" | grep -q "MSA_AA=$VALID_COUNT" && echo 1 || echo 0)"
check "缺 MSH-10 的报文回 AE+ERR" "MSA_AE=1 且 ERR 段 1 个" \
  "$(printf '%s' "$SUMMARY" | sed -n 's/.*\(MSA_AE=[0-9]* ERR_SEGMENTS=[0-9]*\).*/\1/p')" \
  "$(printf '%s' "$SUMMARY" | grep -q 'MSA_AE=1' && printf '%s' "$SUMMARY" | grep -q 'ERR_SEGMENTS=1' && echo 1 || echo 0)"
check "ERR 段指向出错段字段" "ERR-1 含 MSH^10" \
  "$(grep -o 'control-id-missing\^MSH\^10' "$WORK/send.log" | head -1)" \
  "$(grep -q 'control-id-missing\^MSH\^10' "$WORK/send.log" && echo 1 || echo 0)"

# ---------------------------------------------------------------------------
# 5. curl 打端点：探针 / 鉴权 / 待办 / 确认 / 统计 / 撤回 / 对账
# ---------------------------------------------------------------------------

step "5. curl 打院内 HTTP 端点"

code_healthz="$($CURL -s -o "$WORK/healthz.json" -w '%{http_code}' "$BASE/healthz")"
check "GET /healthz（探针不带令牌）" "200" "$code_healthz" \
  "$([ "$code_healthz" = "200" ] && echo 1 || echo 0)"
check "healthz 带本院 AE" "CHSCOMMUNITY" \
  "$(grep -o '"localAet":"[^"]*"' "$WORK/healthz.json" | head -1)" \
  "$(grep -q '"localAet":"CHSCOMMUNITY"' "$WORK/healthz.json" && echo 1 || echo 0)"

code_401="$($CURL -s -o "$WORK/no-token.json" -w '%{http_code}' "$BASE/messages?state=PENDING_CONFIRM")"
check "GET /messages 不带令牌" "401" "$code_401" \
  "$([ "$code_401" = "401" ] && echo 1 || echo 0)"

code_msgs="$($CURL -s -o "$WORK/messages.json" -w '%{http_code}' \
  -H "Authorization: Bearer ${VERIFY_API_TOKEN}" "$BASE/messages?state=PENDING_CONFIRM&batchId=${BATCH_ID}")"
TOTAL_PENDING="$(grep -o '"total":[0-9]*' "$WORK/messages.json" | head -1 | cut -d: -f2)"
check "GET /messages 带令牌" "200" "$code_msgs" "$([ "$code_msgs" = "200" ] && echo 1 || echo 0)"
check "待办条数 = 合法报文数" "$VALID_COUNT" "${TOTAL_PENDING:-无}" \
  "$([ "${TOTAL_PENDING:-x}" = "$VALID_COUNT" ] && echo 1 || echo 0)"
check "确认前 localMrn 显式为空" "\"localMrn\":\"\"" \
  "$(grep -o '"localMrn":""' "$WORK/messages.json" | head -1)" \
  "$(grep -q '"localMrn":""' "$WORK/messages.json" && echo 1 || echo 0)"

code_stats="$($CURL -s -o "$WORK/stats-before.json" -w '%{http_code}' \
  -H "Authorization: Bearer ${VERIFY_API_TOKEN}" "$BASE/batches/${BATCH_ID}/stats")"
STATS_TOTAL="$(grep -o '"total":[0-9]*' "$WORK/stats-before.json" | head -1 | cut -d: -f2)"
STATS_PENDING="$(grep -o '"pendingConfirm":[0-9]*' "$WORK/stats-before.json" | head -1 | cut -d: -f2)"
STATS_REJECTED="$(grep -o '"rejected":[0-9]*' "$WORK/stats-before.json" | head -1 | cut -d: -f2)"
STATS_LANDED="$(grep -o '"landed":[0-9]*' "$WORK/stats-before.json" | head -1 | cut -d: -f2)"
echo "   四态统计：$(head -c 220 "$WORK/stats-before.json")"
check "回执条数 == 台账条数" "$EXPECTED_TOTAL" "${STATS_TOTAL:-无}" \
  "$([ "${STATS_TOTAL:-x}" = "$EXPECTED_TOTAL" ] && echo 1 || echo 0)"
check "待确认 / 拒收分布" "$VALID_COUNT / 1" "${STATS_PENDING:-?} / ${STATS_REJECTED:-?}" \
  "$([ "${STATS_PENDING:-x}" = "$VALID_COUNT" ] && [ "${STATS_REJECTED:-x}" = "1" ] && echo 1 || echo 0)"
check "未确认时已落地为 0（零写入）" "0" "${STATS_LANDED:-无}" \
  "$([ "${STATS_LANDED:-x}" = "0" ] && echo 1 || echo 0)"

LEDGER_ID="$(grep -o '"ledgerId":[0-9]*' "$WORK/messages.json" | head -1 | cut -d: -f2)"
code_confirm="$($CURL -s -o "$WORK/confirm.json" -w '%{http_code}' -X POST \
  -H "Content-Type: application/json" "${API_H[@]}" \
  -d "{\"ledgerId\":${LEDGER_ID},\"patientRef\":\"PAT-0001\",\"localMrn\":\"MRN-VERIFY-001\",\"candidateSource\":\"verify-manual\"}" \
  "$BASE/batches/${BATCH_ID}/confirm")"
check "POST /confirm（唯一落地通道）" "200" "$code_confirm" \
  "$([ "$code_confirm" = "200" ] && echo 1 || echo 0)"
check "确认后状态为已落地" "\"state\":\"LANDED\"" \
  "$(grep -o '"state":"LANDED"' "$WORK/confirm.json" | head -1)" \
  "$(grep -q '"state":"LANDED"' "$WORK/confirm.json" && echo 1 || echo 0)"
check "操作人取自令牌而非请求体" "\"operator\":\"clerk-verify\"" \
  "$(grep -o '"operator":"clerk-verify"' "$WORK/confirm.json" | head -1)" \
  "$(grep -q '"operator":"clerk-verify"' "$WORK/confirm.json" && echo 1 || echo 0)"
check "确认后才写院内主索引" "wrotePatientIndex=true" \
  "$(grep -o '"wrotePatientIndex":[a-z]*' "$WORK/confirm.json" | head -1)" \
  "$(grep -q '"wrotePatientIndex":true' "$WORK/confirm.json" && echo 1 || echo 0)"

code_repeat="$($CURL -s -o "$WORK/confirm-repeat.json" -w '%{http_code}' -X POST \
  -H "Content-Type: application/json" "${API_H[@]}" \
  -d "{\"ledgerId\":${LEDGER_ID},\"patientRef\":\"PAT-0001\",\"localMrn\":\"MRN-VERIFY-001\"}" \
  "$BASE/batches/${BATCH_ID}/confirm")"
check "重复确认不静默成功" "409" "$code_repeat" \
  "$([ "$code_repeat" = "409" ] && echo 1 || echo 0)"
check "重复确认拒绝码" "ALREADY_LANDED" \
  "$(grep -o '"refusal":"[A-Z_]*"' "$WORK/confirm-repeat.json" | head -1)" \
  "$(grep -q 'ALREADY_LANDED' "$WORK/confirm-repeat.json" && echo 1 || echo 0)"

$CURL -s -o "$WORK/stats-after-confirm.json" -H "Authorization: Bearer ${VERIFY_API_TOKEN}" \
  "$BASE/batches/${BATCH_ID}/stats" >/dev/null
LANDED_AFTER="$(grep -o '"landed":[0-9]*' "$WORK/stats-after-confirm.json" | head -1 | cut -d: -f2)"
check "确认后台账已落地 1 条" "1" "${LANDED_AFTER:-无}" \
  "$([ "${LANDED_AFTER:-x}" = "1" ] && echo 1 || echo 0)"

code_revert="$($CURL -s -o "$WORK/revert.json" -w '%{http_code}' -X POST \
  -H "Content-Type: application/json" "${API_H[@]}" \
  -d "{\"ledgerId\":${LEDGER_ID},\"reason\":\"验证脚本撤回\"}" \
  "$BASE/batches/${BATCH_ID}/revert")"
check "POST /revert" "200" "$code_revert" "$([ "$code_revert" = "200" ] && echo 1 || echo 0)"
check "撤回后回到待确认" "\"state\":\"PENDING_CONFIRM\"" \
  "$(grep -o '"state":"PENDING_CONFIRM"' "$WORK/revert.json" | head -1)" \
  "$(grep -q '"state":"PENDING_CONFIRM"' "$WORK/revert.json" && echo 1 || echo 0)"

$CURL -s -o "$WORK/stats-after-revert.json" -H "Authorization: Bearer ${VERIFY_API_TOKEN}" \
  "$BASE/batches/${BATCH_ID}/stats" >/dev/null
LANDED_FINAL="$(grep -o '"landed":[0-9]*' "$WORK/stats-after-revert.json" | head -1 | cut -d: -f2)"
PENDING_FINAL="$(grep -o '"pendingConfirm":[0-9]*' "$WORK/stats-after-revert.json" | head -1 | cut -d: -f2)"
check "撤回后已落地归 0、待确认复原" "0 / $VALID_COUNT" "${LANDED_FINAL:-?} / ${PENDING_FINAL:-?}" \
  "$([ "${LANDED_FINAL:-x}" = "0" ] && [ "${PENDING_FINAL:-x}" = "$VALID_COUNT" ] && echo 1 || echo 0)"

code_reconcile="$($CURL -s -o "$WORK/reconcile.json" -w '%{http_code}' \
  -H "Authorization: Bearer ${VERIFY_API_TOKEN}" "$BASE/batches/${BATCH_ID}/reconcile")"
check "对账对端不可达时降级" "503" "$code_reconcile" \
  "$([ "$code_reconcile" = "503" ] && echo 1 || echo 0)"
check "降级响应带错误码" "REMOTE_UNAVAILABLE" \
  "$(grep -o '"errorCode":"[A-Z_]*"' "$WORK/reconcile.json" | head -1)" \
  "$(grep -q 'REMOTE_UNAVAILABLE' "$WORK/reconcile.json" && echo 1 || echo 0)"
check "对账失败不产出差集" "响应体不含差集字段" \
  "$(grep -q '"missing' "$WORK/reconcile.json" && echo 含差集 || echo 无差集)" \
  "$(grep -q '"missing' "$WORK/reconcile.json" && echo 0 || echo 1)"

# ---------------------------------------------------------------------------
# 6. 自证：验证过程不碰院内系统、不用真实凭据
# ---------------------------------------------------------------------------

step "6. 隔离自证"
check "回执对账只指向本机关闭端口" "127.0.0.1:9" \
  "$(grep -o '目标=127\.0\.0\.1:[0-9]*' "$WORK/reconcile.json" | head -1 | cut -d= -f2)" \
  "$(grep -q '目标=127.0.0.1:' "$WORK/reconcile.json" && echo 1 || echo 0)"
check "确认动作只触发本地写端口" "验证模式：触发院内患者主索引写入" \
  "$(grep -o '验证模式：触发院内患者主索引写入' "$WORK/serve.log" | head -1)" \
  "$(grep -q '验证模式：触发院内患者主索引写入' "$WORK/serve.log" && echo 1 || echo 0)"
CRED_LEAKS="$(grep -c 'REPLACE_ME' "$WORK/serve.log" 2>/dev/null || true)"
CRED_LEAKS="${CRED_LEAKS:-0}"
check "运行日志里没有占位凭据原文" "0 处" "$CRED_LEAKS 处" \
  "$([ "$CRED_LEAKS" = "0" ] && echo 1 || echo 0)"

# ---------------------------------------------------------------------------
# 摘要
# ---------------------------------------------------------------------------

step "验证摘要"
printf '   通过 %d 项 / 失败 %d 项\n\n' "$PASS_COUNT" "$FAIL_COUNT"
if [ "$FAIL_COUNT" -gt 0 ]; then
  echo "FAIL 明细："
  for row in "${RESULTS[@]}"; do
    IFS='|' read -r st name expect actual <<< "$row"
    [ "$st" = "FAIL" ] && printf '   - %s：期望 %s，实际 %s\n' "$name" "$expect" "$actual"
  done
  echo
  echo "结论：FAIL"
  exit 1
fi

cat <<'SUMMARY'
   结论摘要（真实运行，不是文档承诺）：
   - 一条形状完整的入站档案报文 → 拆帧 → 结构化 → 逐条 MSA 回执（AA）+ 一条台账，
     全链路在本机回环口上跑通，回执条数与台账条数相等；
   - 故意缺 MSH-10 的报文 → MSA-1=AE + ERR 段指向 MSH^10，单独入账为拒收、不进重投；
   - 待确认条目在人工确认动作之前，院内患者主索引写入次数为 0；确认一次写一次，
     重复确认 409 不静默成功，撤回后回到待确认可重走；
   - 区域平台回执接口不可达时 /reconcile 返回 503 且不出差集，失败不静默。

   结论：PASS
SUMMARY
exit 0