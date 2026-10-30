# Windows 安裝：下載地圖資料、設定開機自動啟動（工作排程器，以 SYSTEM 身分在背景執行）
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')

Say '台灣離線地圖服務：安裝'
$java = Find-Java
if (-not $java) {
    Say '找不到 Java 21 以上。請先安裝 JDK 21（例如 https://adoptium.net 下載 Temurin 21），再重新執行 install.bat。'
    exit 1
}
Say "Java：$java"

if (Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue) {
    Say '偵測到之前的安裝，先停止它…'
    Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
    Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false
    Start-Sleep -Seconds 2
}
Stop-MapGraphHopper

Say '1/3 下載地圖資料（第一次約 400 MB，請稍候）…'
$ErrorActionPreference = 'Continue'
Push-Location $Root
& $java 'bin\MapService.java' update
$code = $LASTEXITCODE
Pop-Location
$ErrorActionPreference = 'Stop'
if ($code -ne 0) {
    Say '下載失敗，請看上面的訊息。'
    exit 1
}

Say '2/3 設定開機自動啟動…'
$action    = New-ScheduledTaskAction -Execute $java -Argument '"bin\MapService.java" run' -WorkingDirectory $Root
$trigger   = New-ScheduledTaskTrigger -AtStartup
$settings  = New-ScheduledTaskSettingsSet -ExecutionTimeLimit ([TimeSpan]::Zero) `
                -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1) `
                -StartWhenAvailable -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -MultipleInstances IgnoreNew
$principal = New-ScheduledTaskPrincipal -UserId 'SYSTEM' -LogonType ServiceAccount -RunLevel Highest
Register-ScheduledTask -TaskName $TaskName -Description '台灣離線地圖服務：開機自動啟動，定期從 GitHub 更新地圖資料' `
    -Action $action -Trigger $trigger -Settings $settings -Principal $principal -Force | Out-Null

Say '3/3 啟動服務…'
Start-ScheduledTask -TaskName $TaskName
$port = Get-MapPort
for ($i = 0; $i -lt 240; $i++) {
    if (Test-MapHealthy $port) {
        Say "完成！路線服務：http://127.0.0.1:$port"
        Say '之後開機會自動啟動、每天檢查更新，不需要再做任何事。'
        Say "執行紀錄在 $Root\logs\service.log；要移除請雙擊 uninstall.bat。"
        exit 0
    }
    Start-Sleep -Seconds 1
}
Say "服務沒有在時間內啟動，請看 $Root\logs\service.log 和 graphhopper.log"
exit 1
