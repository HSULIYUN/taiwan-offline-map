#!/usr/bin/env bash
# 建置一版地圖資料，輸出成可以發布到 GitHub Release 的檔案。
# 平常由 GitHub Actions 每月自動執行（.github/workflows/build-map.yml），
# 也可以在 Mac / Linux 上手動跑來除錯。
#
#   bin/build.sh [輸出資料夾]          預設輸出到 dist/
#
# 環境變數：
#   RELEASE_NAME      這一版的名稱，預設是今天日期（台灣時間）
#   ADDRESS_DB        地址資料庫路徑（.db 或 .db.gz）；有 bin/build-address.sh 時不需要
#   PREV_PROPERTIES   上一版的 release.properties，用來比對筆數有沒有暴跌
#   PBF_FILE          已經下載好的 OSM pbf，有給就不重新下載
#
# 輸出：taiwan.mbtiles、graph-cache.zip、taiwan_address.db.gz、release.properties、NOTES.md
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

OUT="${1:-$ROOT/dist}"
WORK="$ROOT/work"
RELEASE_NAME="${RELEASE_NAME:-$(TZ=Asia/Taipei date +%Y-%m-%d)}"
TEST_PID=""
cleanup() { [ -n "$TEST_PID" ] && stop_pid "$TEST_PID"; return 0; }
trap cleanup EXIT

log "建置地圖資料 $RELEASE_NAME"

# ---- 1. 工具 ---------------------------------------------------------------
step "1/7 檢查工具"
JAVA=$(find_java) || die "找不到 Java 21 以上"
log "Java：$JAVA（版本 $(java_major "$JAVA")）"
for c in curl sqlite3 zip gzip awk; do command -v "$c" >/dev/null 2>&1 || die "缺少指令：$c"; done
mkdir -p "$ROOT/tools"
fetch_tool() {
  [ -s "$1" ] && { log "已有 $(basename "$1")"; return 0; }
  log "下載 $(basename "$1") …"
  curl -fsSL --retry 3 -o "$1.part" "$2" || die "下載失敗：$2"
  mv -f "$1.part" "$1"
}
fetch_tool "$GH_JAR" "$GH_JAR_URL"
fetch_tool "$PLANETILER_JAR" "$PLANETILER_JAR_URL"

rm -rf "$WORK"
mkdir -p "$WORK"

# ---- 2. 台灣 OSM -------------------------------------------------------------
step "2/7 取得台灣 OSM 資料"
if [ -n "${PBF_FILE:-}" ]; then
  PBF="$PBF_FILE"
  [ -s "$PBF" ] || die "找不到 PBF_FILE：$PBF"
  log "使用指定的檔案：$PBF"
else
  PBF="$WORK/taiwan-latest.osm.pbf"
  ok=0
  for _ in 1 2; do
    curl -fsSL --retry 3 -o "$PBF" "$PBF_URL" || die "下載失敗：$PBF_URL"
    curl -fsSL --retry 3 -o "$PBF.md5" "$PBF_URL.md5" || die "下載 md5 失敗"
    if [ "$(awk '{print $1}' "$PBF.md5")" = "$(md5_of "$PBF")" ]; then ok=1; break; fi
    log "檔案檢查碼不符（可能剛好遇到對方更新），重新下載…"
  done
  [ "$ok" = 1 ] || die "下載的檔案檢查碼一直不符"
  log "下載完成：$(du -h "$PBF" | cut -f1)"
fi

# ---- 3. 底圖圖磚 -------------------------------------------------------------
step "3/7 產生底圖圖磚"
mkdir -p "$ROOT/tools/planetiler-data"
( cd "$ROOT/tools/planetiler-data" &&
  "$JAVA" -Xmx"$BUILD_XMX" -jar "$PLANETILER_JAR" \
    --osm_path="$PBF" --output="$WORK/taiwan.mbtiles" \
    --download --force --tmpdir="$WORK/tmp-planetiler" ) || die "產生圖磚失敗"
rm -rf "$WORK/tmp-planetiler"
[ -s "$WORK/taiwan.mbtiles" ] || die "沒有產生 taiwan.mbtiles"

# ---- 4. 路網 -----------------------------------------------------------------
step "4/7 建立 GraphHopper 路網"
make_gh_config "$WORK/gh-import.yml" "$WORK/graph-cache" "$PBF" "$GH_TEST_PORT" "$GH_TEST_ADMIN_PORT"
( cd "$CONFIG_DIR" && "$JAVA" -Xmx"$BUILD_XMX" -jar "$GH_JAR" import "$WORK/gh-import.yml" ) || die "建立路網失敗"
[ -f "$WORK/graph-cache/properties.txt" ] || die "路網沒有建好"

# ---- 5. 地址資料庫 -----------------------------------------------------------
step "5/7 地址資料庫"
if [ -x "$ROOT/bin/build-address.sh" ]; then
  "$ROOT/bin/build-address.sh" "$WORK/taiwan_address.db" || die "地址資料庫建置失敗"
  ADDR_SRC="bin/build-address.sh 重新建置"
elif [ -n "${ADDRESS_DB:-}" ]; then
  [ -s "$ADDRESS_DB" ] || die "找不到 ADDRESS_DB：$ADDRESS_DB"
  case "$ADDRESS_DB" in
    *.gz) gzip -dc "$ADDRESS_DB" > "$WORK/taiwan_address.db" ;;
    *)    cp "$ADDRESS_DB" "$WORK/taiwan_address.db" ;;
  esac
  ADDR_SRC="沿用上一版（尚無地址重建程式）"
else
  die "沒有地址資料庫：請設定 ADDRESS_DB，或加上 bin/build-address.sh"
fi

# ---- 6. 驗證 -----------------------------------------------------------------
step "6/7 驗證"
sq() { sqlite3 -readonly "$1" "$2"; }
prev() { [ -n "${PREV_PROPERTIES:-}" ] && [ -f "$PREV_PROPERTIES" ] && sed -n "s/^$1=//p" "$PREV_PROPERTIES" | head -1; return 0; }
check_count() {   # 名稱 新筆數 舊筆數
  [ "${2:-0}" -gt 0 ] || die "$1 是 0 筆"
  if [ -n "${3:-}" ] && [ "$3" -gt 0 ] && [ $(( $2 * 100 / $3 )) -lt "$MIN_RATIO_PERCENT" ]; then
    die "$1 只有 $2 筆，比上一版的 $3 筆少太多，判斷資料有問題"
  fi
  log "$1：$2 筆（上一版 ${3:-無}）✓"
}
TILES=$(sq "$WORK/taiwan.mbtiles" "select count(*) from tiles") || die "讀不到圖磚"
check_count "圖磚" "$TILES" "$(prev tiles)"
ADDRESSES=$(sq "$WORK/taiwan_address.db" "select count(*) from p") || die "讀不到地址資料庫"
check_count "地址" "$ADDRESSES" "$(prev addresses)"
OSM_DATE=$(sq "$WORK/taiwan.mbtiles" "select value from metadata where name='planetiler:osm:osmosisreplicationtime'" || true)
log "OSM 資料日期：${OSM_DATE:-不明}"

gh_healthy "$GH_TEST_PORT" && die "驗證用的埠 $GH_TEST_PORT 已被其他程式占用"
make_gh_config "$WORK/gh-test.yml" "$WORK/graph-cache" "" "$GH_TEST_PORT" "$GH_TEST_ADMIN_PORT"
( cd "$CONFIG_DIR" && exec "$JAVA" -Xmx"$BUILD_XMX" -jar "$GH_JAR" server "$WORK/gh-test.yml" ) > "$WORK/gh-test.log" 2>&1 &
TEST_PID=$!
log "啟動暫時的路線服務來測試…"
if ! wait_gh_healthy "$GH_TEST_PORT" "$TEST_PID" 180; then
  tail -30 "$WORK/gh-test.log"
  die "路網無法啟動"
fi
ROUTE_LOG=""
for t in "${ROUTE_TESTS[@]}"; do
  IFS='|' read -r label from to lo hi <<< "$t"
  resp=$(curl -fs -m 30 "http://127.0.0.1:$GH_TEST_PORT/route?profile=car&calc_points=false&point=$from&point=$to") \
    || die "路線查詢失敗：$label"
  dist=$(printf '%s' "$resp" | grep -o '"distance" *: *[0-9.]*' | head -1 | sed 's/.*: *//')
  dist=${dist%.*}
  if [ -z "$dist" ] || [ "$dist" -lt "$lo" ] || [ "$dist" -gt "$hi" ]; then
    die "路線距離不合理：$label = ${dist:-無} 公尺（應介於 $lo～$hi）"
  fi
  log "路線 $label：約 $(( dist / 1000 )) 公里 ✓"
  ROUTE_LOG="$ROUTE_LOG- $label：約 $(( dist / 1000 )) 公里"$'\n'
done
stop_pid "$TEST_PID"; TEST_PID=""

# ---- 7. 打包 -----------------------------------------------------------------
step "7/7 打包"
rm -rf "$OUT"
mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"   # 轉成完整路徑，下面切換資料夾壓縮時才找得到
mv "$WORK/taiwan.mbtiles" "$OUT/taiwan.mbtiles"
( cd "$WORK" && zip -qr -1 "$OUT/graph-cache.zip" graph-cache )
gzip -c "$WORK/taiwan_address.db" > "$OUT/taiwan_address.db.gz"

{
  echo "# 地圖資料版本資訊，各台電腦用它判斷要不要更新、檢查下載的檔案是否完整"
  echo "release=$RELEASE_NAME"
  echo "built_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "osm_data_date=$OSM_DATE"
  echo "graphhopper_version=$GH_VERSION"
  echo "planetiler_version=$PLANETILER_VERSION"
  echo "tiles=$TILES"
  echo "addresses=$ADDRESSES"
  echo "address_source=$ADDR_SRC"
  echo "files=taiwan.mbtiles,graph-cache.zip,taiwan_address.db.gz"
  for f in taiwan.mbtiles graph-cache.zip taiwan_address.db.gz; do
    echo "sha256.$f=$(sha256_of "$OUT/$f")"
    echo "size.$f=$(file_size "$OUT/$f")"
  done
} > "$OUT/release.properties"

cat > "$OUT/NOTES.md" <<NOTES
台灣離線地圖資料 $RELEASE_NAME

- OSM 資料日期：${OSM_DATE:-不明}
- 圖磚：$TILES 筆
- 地址：$ADDRESSES 筆（$ADDR_SRC）
- GraphHopper $GH_VERSION、Planetiler $PLANETILER_VERSION

路線驗證：
$ROUTE_LOG
地圖資料 © OpenStreetMap contributors（ODbL）；底圖樣式 © OpenMapTiles。
NOTES

rm -rf "$WORK"
log "完成，輸出在 $OUT："
ls -lh "$OUT"
