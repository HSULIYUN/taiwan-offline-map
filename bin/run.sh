#!/usr/bin/env bash
# 執行地圖服務（Mac / Linux）
#   bin/run.sh            啟動服務（先檢查更新再啟動）
#   bin/run.sh update     只下載最新版地圖資料
#   bin/run.sh status     顯示目前狀態
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

JAVA=$(find_java) || die "找不到 Java 21 以上。請先安裝 JDK 21（例如 https://adoptium.net），或設定 JAVA_HOME"
cd "$ROOT"
exec "$JAVA" bin/MapService.java "${@:-run}"
