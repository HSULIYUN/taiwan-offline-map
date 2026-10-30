# Windows 腳本共用的函式

$Root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$TaskName = 'OfflineMapService'

function Say([string]$m) { Write-Host ('[{0}] {1}' -f (Get-Date -Format 'HH:mm:ss'), $m) }

function Get-JavaMajor([string]$exe) {
    $out = (& cmd /c "`"$exe`" -version 2>&1") -join "`n"
    $m = [regex]::Match($out, 'version "(\d+)(?:\.(\d+))?')
    if (-not $m.Success) { return 0 }
    $major = [int]$m.Groups[1].Value
    if ($major -eq 1 -and $m.Groups[2].Success) { $major = [int]$m.Groups[2].Value }
    return $major
}

# 找 Java 21 以上：JAVA_HOME → PATH → 常見安裝位置
function Find-Java {
    $candidates = @()
    if ($env:JAVA_HOME) { $candidates += Join-Path $env:JAVA_HOME 'bin\java.exe' }
    $cmd = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($cmd) { $candidates += $cmd.Source }
    $dirs = @("$env:ProgramFiles\Eclipse Adoptium", "$env:ProgramFiles\Java", "$env:ProgramFiles\Microsoft",
              "$env:ProgramFiles\Zulu", "$env:ProgramFiles\Amazon Corretto", "$env:USERPROFILE")
    foreach ($d in $dirs) {
        if (Test-Path $d) {
            Get-ChildItem $d -Directory -ErrorAction SilentlyContinue |
                Where-Object { $_.Name -match '^(jdk|zulu|corretto)' } |
                Sort-Object Name -Descending |
                ForEach-Object { $candidates += Join-Path $_.FullName 'bin\java.exe' }
        }
    }
    foreach ($e in $candidates) {
        if ((Test-Path $e) -and ((Get-JavaMajor $e) -ge 21)) { return $e }
    }
    return $null
}

function Get-MapPort {
    $line = Get-Content (Join-Path $Root 'config\map.properties') -Encoding UTF8 -ErrorAction SilentlyContinue |
        Where-Object { $_ -match '^\s*port\s*=\s*(\d+)' } | Select-Object -First 1
    if ($line -and $line -match '(\d+)') { return [int]$Matches[1] }
    return 8989
}

function Test-MapHealthy([int]$port) {
    try {
        $wc = New-Object System.Net.WebClient
        $wc.Proxy = $null
        return ($wc.DownloadString("http://127.0.0.1:$port/health") -ne $null)
    } catch { return $false }
}

# 停掉路線服務（地圖服務被強制結束時，路線服務可能還在）
function Stop-MapGraphHopper {
    $pidFile = Join-Path $Root 'logs\graphhopper.pid'
    if (Test-Path $pidFile) {
        $p = Get-Content $pidFile -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($p -match '^\d+$') { Stop-Process -Id ([int]$p) -Force -ErrorAction SilentlyContinue }
        Remove-Item $pidFile -Force -ErrorAction SilentlyContinue
    }
}
