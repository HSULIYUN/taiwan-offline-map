#!/bin/bash
# Mac：雙擊這個檔案啟動地圖服務。關閉這個視窗（或按 Ctrl+C）就會停止服務。
# 想開機自動啟動、不用每次雙擊：在終端機執行 bin/install-service.sh
cd "$(dirname "$0")" || exit 1
echo "地圖服務啟動中…（關閉這個視窗就會停止服務）"
echo
bin/run.sh run
rc=$?
echo
read -r -n 1 -s -p "地圖服務已停止，按任意鍵關閉視窗…"
echo
exit $rc
