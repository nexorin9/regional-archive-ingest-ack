#!/usr/bin/env bash
#
# fetch_fixtures.sh —— 测试数据层（fixtures）抓取 / 构造脚本
#
# 用法：
#   bash scripts/fetch_fixtures.sh              # 幂等：已有完整语料则只做校验
#   bash scripts/fetch_fixtures.sh --force      # 强制重建（先清空 fixtures/）
#   bash scripts/fetch_fixtures.sh --offline    # 跳过一切网络尝试
#
# 行为约定：
#   1. 可重跑：语料完整时直接做完整性校验并退出，不重复生成。
#   2. 带完整性校验：条数、编码、必需样例、GB18030/UTF-8 同内容，全部由
#      FixturesManifest 校验，不通过则非零退出。
#   3. 断网降级：网络取数失败时打印【降级】说明并回退到内置确定性合成器
#      （同种子、逐字节可复现），绝不因此中断整轮。
#
# 取数来源（可显式指定环境变量）：
#   SYNTHEA_JAR         指向 Synthea 可执行 jar（存在则真的跑 Synthea 引擎出 HL7 v2）
#   SYNTHEA_URL         未给 SYNTHEA_JAR 时的下载地址（默认 GitHub release 资产）
#   HAPI_SAMPLES_TARBALL HAPI HL7 官方样例集 tarball 地址（可达则优先抓取）
#
set -uo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_DIR" || exit 1

FIXTURES="$PROJECT_DIR/src/test/resources/fixtures"
CACHE="$PROJECT_DIR/target/fixture-cache"
GENERATOR="cn.hospital.region.archive.fixtures.FixtureCorpusGenerator"
VERIFIER="cn.hospital.region.archive.fixtures.FixturesManifest"
CP="$PROJECT_DIR/target/classes:$PROJECT_DIR/target/test-classes"

FORCE=0
OFFLINE=0
for arg in "$@"; do
  case "$arg" in
    --force)   FORCE=1 ;;
    --offline) OFFLINE=1 ;;
    -h|--help) sed -n '2,25p' "$0"; exit 0 ;;
    *) echo "未知参数：$arg（可用 --force / --offline）" >&2; exit 64 ;;
  esac
done

# macOS 上 /usr/bin/java 可能只是安装引导桩，直接跑会报 "Unable to locate a Java Runtime"。
# 依次回退：JAVA_HOME → 可用的 java → maven 实际使用的 JDK。
resolve_java() {
  if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVA_BIN="$JAVA_HOME/bin/java"; return 0
  fi
  if command -v java >/dev/null 2>&1 && java -version >/dev/null 2>&1; then
    JAVA_BIN="$(command -v java)"; return 0
  fi
  local rt
  rt="$(mvn -v 2>/dev/null | sed -n 's/.*runtime: //p' | head -1)"
  if [ -n "$rt" ] && [ -x "$rt/bin/java" ]; then
    JAVA_BIN="$rt/bin/java"; return 0
  fi
  echo "[fetch_fixtures] 找不到可用的 Java 运行时（请设置 JAVA_HOME）" >&2
  exit 1
}
resolve_java

log()  { printf '[fetch_fixtures] %s\n' "$*"; }
warn() { printf '[fetch_fixtures] 【降级】%s\n' "$*"; }

# ---------------------------------------------------------------- 0. 幂等短路
verify_corpus() {
  "$JAVA_BIN" -cp "$CP" "$VERIFIER" --print >/dev/null 2>&1
}

log "编译测试数据层（离线依赖）"
if ! mvn -o -q test-compile; then
  echo "[fetch_fixtures] 编译失败：mvn -o -q test-compile 返回非零" >&2
  exit 1
fi

if [ "$FORCE" -eq 0 ] && [ -d "$FIXTURES" ] && verify_corpus; then
  log "语料已存在且完整性校验通过，跳过生成（如需重建加 --force）"
  "$JAVA_BIN" -cp "$CP" "$VERIFIER" --print
  exit 0
fi

if [ "$FORCE" -eq 1 ]; then
  log "--force：清空 $FIXTURES"
  rm -rf "$FIXTURES"
fi
mkdir -p "$FIXTURES" "$CACHE"

# ---------------------------------------------------- 1. 网络取数（可失败可降级）
SOURCE_SYNTHEA="内置合成器（Synthea HL7 v2 输出格式）"
SOURCE_HAPI="内置合成器（HL7 v2 标准样例消息 → ACK 契约基线）"

try_synthea_engine() {
  [ "$OFFLINE" -eq 1 ] && return 1
  local jar="${SYNTHEA_JAR:-}"
  if [ -z "$jar" ]; then
    local url="${SYNTHEA_URL:-https://github.com/synthetichealth/synthea/releases/download/master-branch-latest/synthea-with-dependencies.jar}"
    log "尝试下载 Synthea 发行包：$url"
    if curl -fsSL --connect-timeout 8 --max-time 240 -o "$CACHE/synthea.jar" "$url" 2>/dev/null \
       && [ -s "$CACHE/synthea.jar" ]; then
      jar="$CACHE/synthea.jar"
    else
      return 1
    fi
  fi
  [ -s "$jar" ] || return 1
  log "用真实 Synthea 引擎生成 ER7（jar=$jar）"
  if "$JAVA_BIN" -Dfile.encoding=UTF-8 -jar "$jar" \
        -p "$CACHE/synthea-out" \
        -s "$CACHE/synthea-src" \
        -t "$CACHE/synthea-term" \
        -o "$FIXTURES/synthea" \
        -hl7 true \
        -exporter.hl7.export_style true \
        --patient 60 >"$CACHE/synthea-run.log" 2>&1; then
    local n
    n=$(find "$FIXTURES/synthea" -name '*.hl7' 2>/dev/null | wc -l | tr -d ' ')
    if [ "${n:-0}" -ge 50 ]; then
      SOURCE_SYNTHEA="Synthea 引擎实跑（$jar）"
      return 0
    fi
    warn "Synthea 引擎只产出 $n 条 <50，改用内置合成器"
  else
    warn "Synthea 引擎执行失败（日志 $CACHE/synthea-run.log），改用内置合成器"
  fi
  return 1
}

try_hapi_samples() {
  [ "$OFFLINE" -eq 1 ] && return 1
  local url="${HAPI_SAMPLES_TARBALL:-}"
  [ -n "$url" ] || return 1
  log "尝试抓取 HAPI HL7 官方样例集：$url"
  if curl -fsSL --connect-timeout 8 --max-time 120 -o "$CACHE/hapi.tar.gz" "$url" 2>/dev/null \
     && tar -tzf "$CACHE/hapi.tar.gz" >/dev/null 2>&1; then
    mkdir -p "$FIXTURES/hapi"
    tar -xzf "$CACHE/hapi.tar.gz" -C "$FIXTURES/hapi" 2>/dev/null
    local n
    n=$(find "$FIXTURES/hapi" -name '*.hl7' 2>/dev/null | wc -l | tr -d ' ')
    if [ "${n:-0}" -ge 8 ]; then
      SOURCE_HAPI="HAPI 官方样例集（$url）"
      return 0
    fi
    warn "HAPI 样例集只有 $n 条 <8，改用内置契约基线"
  else
    warn "HAPI 样例集不可达（$url），改用内置契约基线"
  fi
  return 1
}

if [ "$OFFLINE" -eq 1 ]; then
  warn "--offline：跳过全部网络尝试"
else
  try_synthea_engine || warn "Synthea 上游引擎不可达（发行资产下载在本环境被限流/阻断），改用内置确定性合成器"
  try_hapi_samples  || warn "HAPI 上游样例集不可达，改用内置确定性合成器"
fi

# --------------------------------------------------- 2. 内置确定性合成器（主路径）
log "生成测试语料：synthea / hapi / zh 三族"
rm -rf "$FIXTURES/synthea" "$FIXTURES/hapi" "$FIXTURES/zh"
"$JAVA_BIN" -cp "$CP" "$GENERATOR" "$FIXTURES" || {
  echo "[fetch_fixtures] 语料生成失败" >&2
  exit 1
}

printf '%s\n' "$SOURCE_SYNTHEA"  > "$FIXTURES/.source-synthea"
printf '%s\n' "$SOURCE_HAPI"    > "$FIXTURES/.source-hapi"

# ------------------------------------------------------- 3. 完整性校验（硬门槛）
log "完整性校验：条数 / 编码 / 必需样例 / GB18030-UTF-8 同内容"
if ! "$JAVA_BIN" -cp "$CP" "$VERIFIER" --print; then
  echo "[fetch_fixtures] 完整性校验未通过（非零退出）" >&2
  exit 1
fi

log "完成：$FIXTURES"
exit 0