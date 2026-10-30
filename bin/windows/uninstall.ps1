# Windows 移除：取消開機自動啟動並停止服務。地圖資料會保留。
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')

Say '台灣離線地圖服務：移除'
if (Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue) {
    Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
    Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false
    Say '已取消開機自動啟動。'
} else {
    Say '沒有找到已安裝的服務。'
}
Start-Sleep -Seconds 2
Stop-MapGraphHopper
Say "服務已停止。地圖資料仍在 $Root\releases，不需要的話可以整個資料夾刪掉。"
