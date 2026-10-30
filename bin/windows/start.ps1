# Windows：在目前視窗執行地圖服務（測試用），關閉視窗就停止。
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')

$java = Find-Java
if (-not $java) {
    Say '找不到 Java 21 以上。請先安裝 JDK 21（例如 https://adoptium.net），再重新執行。'
    exit 1
}
Say '地圖服務啟動中…（關閉這個視窗就會停止服務）'
$ErrorActionPreference = 'Continue'
Set-Location $Root
& $java 'bin\MapService.java' run
