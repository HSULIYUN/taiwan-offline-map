#!/usr/bin/env bash
# 把地圖服務設定成開機自動啟動（Mac / Linux），之後會自己定期更新，不用再管它。
#   bin/install-service.sh            安裝
#   bin/install-service.sh --remove   移除（地圖資料會保留）
#
# Mac：登入後自動啟動（launchd）
# Linux：開機自動啟動（systemd，需要 sudo 權限）
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

LABEL="local.offline-map.service"
UNIT="offline-map.service"
PORT=$(sed -n 's/^[[:space:]]*port[[:space:]]*=[[:space:]]*\([0-9]*\).*/\1/p' "$CONFIG_DIR/map.properties" | head -1)
PORT=${PORT:-8989}

remove_mac() {
  launchctl bootout "gui/$(id -u)/$LABEL" 2>/dev/null || true
  rm -f "$HOME/Library/LaunchAgents/$LABEL.plist"
}
remove_linux() {
  sudo systemctl disable --now "$UNIT" 2>/dev/null || true
  sudo rm -f "/etc/systemd/system/$UNIT"
  sudo systemctl daemon-reload
}

wait_ready() {
  local i
  log "等待路線服務啟動…"
  for ((i = 0; i < 240; i++)); do
    if gh_healthy "$PORT"; then
      log "完成！路線服務：http://127.0.0.1:$PORT"
      log "之後開機會自動啟動、每天檢查更新，不需要再做任何事。紀錄在 logs/service.log"
      return 0
    fi
    sleep 1
  done
  die "服務沒有在時間內啟動，請看 logs/service.log 和 logs/graphhopper.log"
}

OS=$(uname)
if [ "${1:-}" = "--remove" ]; then
  if [ "$OS" = Darwin ]; then remove_mac; else remove_linux; fi
  log "已移除自動啟動。地圖資料仍在 releases/，不需要的話可以整個資料夾刪掉。"
  exit 0
fi

find_java >/dev/null || die "找不到 Java 21 以上。請先安裝 JDK 21（例如 https://adoptium.net）"

step "1/2 下載地圖資料（第一次約 400 MB，請稍候）"
"$ROOT/bin/run.sh" update || die "下載失敗，請看上面的訊息"

step "2/2 設定開機自動啟動"
mkdir -p "$ROOT/logs"
if [ "$OS" = Darwin ]; then
  remove_mac
  PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"
  mkdir -p "$(dirname "$PLIST")"
  cat > "$PLIST" <<XML
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>$LABEL</string>
  <key>ProgramArguments</key>
  <array>
    <string>/bin/bash</string>
    <string>$ROOT/bin/run.sh</string>
    <string>run</string>
  </array>
  <key>WorkingDirectory</key><string>$ROOT</string>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key>
  <dict><key>SuccessfulExit</key><false/></dict>
  <key>ThrottleInterval</key><integer>60</integer>
  <key>StandardOutPath</key><string>$ROOT/logs/launchd.log</string>
  <key>StandardErrorPath</key><string>$ROOT/logs/launchd.log</string>
</dict>
</plist>
XML
  launchctl bootstrap "gui/$(id -u)" "$PLIST"
else
  command -v systemctl >/dev/null 2>&1 || die "這台 Linux 沒有 systemd，請改用其他方式讓 bin/run.sh 開機執行"
  remove_linux
  sudo tee "/etc/systemd/system/$UNIT" >/dev/null <<UNITFILE
[Unit]
Description=台灣離線地圖服務
After=network-online.target
Wants=network-online.target

[Service]
User=$(id -un)
WorkingDirectory=$ROOT
ExecStart=/bin/bash $ROOT/bin/run.sh run
Restart=on-failure
RestartSec=60

[Install]
WantedBy=multi-user.target
UNITFILE
  sudo systemctl daemon-reload
  sudo systemctl enable --now "$UNIT"
fi
wait_ready
