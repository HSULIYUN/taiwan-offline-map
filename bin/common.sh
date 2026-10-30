#!/usr/bin/env bash
# 建置與啟動共用的設定和函式，由 build.sh / run.sh / install-service.sh 載入。
# shellcheck disable=SC2034  # 這裡的變數給載入它的腳本使用

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# ---- 建置設定（GitHub Actions 每月用這些設定建置） --------------------------
PBF_URL="https://download.geofabrik.de/asia/taiwan-latest.osm.pbf"
BUILD_XMX="${BUILD_XMX:-4g}"     # 建置時 Java 最多可用記憶體
MIN_RATIO_PERCENT=90             # 新版筆數至少要是上一版的幾 %，否則視為資料異常

GH_VERSION="10.0"                # 換版本時路網會整個重建，各台電腦也會跟著換
GH_JAR="$ROOT/tools/graphhopper-web-$GH_VERSION.jar"
GH_JAR_URL="https://github.com/graphhopper/graphhopper/releases/download/$GH_VERSION/graphhopper-web-$GH_VERSION.jar"
GH_TEST_PORT=8999                # 建置驗證時暫時用的埠
GH_TEST_ADMIN_PORT=9000

PLANETILER_VERSION="0.10.2"
PLANETILER_JAR="$ROOT/tools/planetiler-$PLANETILER_VERSION.jar"
PLANETILER_JAR_URL="https://github.com/onthegomap/planetiler/releases/download/v$PLANETILER_VERSION/planetiler.jar"

# 驗證路線用的起訖點（緯度,經度）與合理距離範圍（公尺）
ROUTE_TESTS=(
  "台北車站→高雄車站|25.0478,121.5170|22.6394,120.3025|300000|450000"
  "台中車站→彰化車站|24.1372,120.6869|24.0817,120.5383|10000|40000"
  "花蓮車站→台東車站|23.9933,121.6011|22.7937,121.1235|120000|220000"
)
# ---------------------------------------------------------------------------

CONFIG_DIR="$ROOT/config"

log()  { printf '[%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*"; }
step() { printf '\n[%s] ===== %s =====\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*"; }
die()  { log "錯誤：$*"; exit 1; }

# Java 主版本號，例如 21；1.8 回傳 8
java_major() {
  local v
  v=$("$1" -version 2>&1 | awk -F'"' '/version/ {print $2; exit}')
  case "$v" in
    1.*) v=${v#1.}; echo "${v%%.*}" ;;
    *)   echo "${v%%[.+-]*}" ;;
  esac
}

# 找 Java 21 以上
find_java() {
  local c v mac_home=""
  [ -x /usr/libexec/java_home ] && mac_home=$(/usr/libexec/java_home -v 21+ 2>/dev/null || true)
  for c in "${JAVA_CMD:-}" "${JAVA_HOME:+$JAVA_HOME/bin/java}" "${mac_home:+$mac_home/bin/java}" \
           "$HOME"/jdk-2*/Contents/Home/bin/java "$HOME"/jdk-2*/bin/java \
           /usr/lib/jvm/*-2[1-9]*/bin/java \
           "$(command -v java 2>/dev/null || true)"; do
    [ -n "$c" ] && [ -x "$c" ] || continue
    v=$(java_major "$c")
    if [ "${v:-0}" -ge 21 ] 2>/dev/null; then echo "$c"; return 0; fi
  done
  return 1
}

md5_of() {
  if command -v md5sum >/dev/null 2>&1; then md5sum "$1" | awk '{print $1}'; else md5 -q "$1"; fi
}

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | awk '{print $1}'; else shasum -a 256 "$1" | awk '{print $1}'; fi
}

file_size() { wc -c < "$1" | tr -d ' '; }

# 由基本設定產生一份 GraphHopper 設定檔
#   make_gh_config 輸出檔 graph目錄 pbf(可空) 埠 管理埠
make_gh_config() {
  awk -v g="$2" -v f="$3" -v p="$4" -v ap="$5" '
    /^[[:space:]]*datareader\.file:/ { next }
    /^[[:space:]]*graph\.location:/  { print "  graph.location: " g; next }
    /^graphhopper:/                  { print; if (f != "") print "  datareader.file: " f; next }
    /^[[:space:]]*application_connectors:/ { section = "app" }
    /^[[:space:]]*admin_connectors:/       { section = "admin" }
    /^[[:space:]]*port:/ { sub(/port:.*/, "port: " (section == "admin" ? ap : p)) }
    { print }
  ' "$CONFIG_DIR/config.yml" > "$1"
}

gh_healthy() { curl -fs -m 3 "http://127.0.0.1:$1/health" >/dev/null 2>&1; }

# 等 GraphHopper 啟動完成：wait_gh_healthy 埠 pid 秒數
wait_gh_healthy() {
  local i
  for ((i = 0; i < $3; i++)); do
    gh_healthy "$1" && return 0
    kill -0 "$2" 2>/dev/null || return 1
    sleep 1
  done
  return 1
}

stop_pid() {
  local pid=$1 i
  kill -0 "$pid" 2>/dev/null || return 0
  kill "$pid" 2>/dev/null || true
  for ((i = 0; i < 30; i++)); do kill -0 "$pid" 2>/dev/null || return 0; sleep 1; done
  kill -9 "$pid" 2>/dev/null || true
}
