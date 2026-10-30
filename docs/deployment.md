# 佈署手冊

依照使用情境，選擇地圖服務要裝在哪裡、怎麼讓其他專案（例如 Tomcat 網站）連上。安裝本身的步驟見 [README](../README.md#在新電腦上安裝)，這份文件說明各情境需要額外注意的地方。

> 本文件中的 `192.168.1.50`、`map.example.com` 等都是範例，請換成你們自己的環境。實際佈署的主機位址、帳號等資訊請記錄在內部文件，不要寫進這個公開的 repo。

## 目錄

- [選擇情境](#選擇情境)
- [所有情境的共同條件](#所有情境的共同條件)
- [情境一：單機（地圖和 Tomcat 在同一台）](#情境一單機地圖和-tomcat-在同一台)
- [情境二：地端共用伺服器（內網多台 Tomcat 共用）](#情境二地端共用伺服器內網多台-tomcat-共用)
- [情境三：雲端主機](#情境三雲端主機)
- [日常維運](#日常維運)
- [疑難排解](#疑難排解)

## 選擇情境

| 情境 | 適合 | 對外開放 | 難度 |
|---|---|---|---|
| 一、單機 | 只有一個 Tomcat 專案，或開發測試 | 不開放 | 最簡單 |
| 二、地端共用伺服器 | 機房或辦公室內網，多個 Tomcat 共用一份地圖 | 只開放給內網 | 中等 |
| 三、雲端主機 | 專案部署在 GCP、AWS 等雲端，或需要從外部連線 | 透過反向代理，加存取限制 | 中等 |

拿不定主意時：**Tomcat 在哪台，地圖就裝在哪台**（情境一）。地圖服務會自己更新，每台各裝一份也不會增加維護負擔，只是多佔約 1 GB 硬碟。

## 所有情境的共同條件

| 項目 | 需求 |
|---|---|
| 作業系統 | Windows 10 / 11 / Server 2016 以上、macOS、Linux（有 systemd） |
| Java | 21 以上 |
| 記憶體 | 主機至少 4 GB（地圖服務預設使用最多 2 GB，可在 `config/map.properties` 的 `java.xmx` 調整） |
| 硬碟 | 至少 2 GB 可用空間（穩定後約佔 1 GB） |
| 網路 | 能連到 `github.com` 及 GitHub 下載檔案用的 `*.githubusercontent.com`（HTTPS 443），用來檢查和下載新版 |

連不上 GitHub 時，服務會繼續使用本機現有的版本，不會停止；但**第一次安裝一定要能連上**。

## 情境一：單機（地圖和 Tomcat 在同一台）

```
┌─────────────── 同一台主機 ───────────────┐
│  Tomcat 專案 ──▶ http://127.0.0.1:8989   │
│                  地圖服務（只綁本機）       │
│  Tomcat 專案 ──▶ current/taiwan.mbtiles  │
│                  current/taiwan_address.db│
└──────────────────────────────────────────┘
```

### 步驟

1. 照 [README](../README.md#在新電腦上安裝) 安裝。正式環境請用開機自動啟動的方式：Windows 用 `install.bat`、Linux 用 `bin/install-service.sh`。
2. `config/map.properties` 維持預設，`bind.host=127.0.0.1`，外部連不進來。
3. Tomcat 專案的設定：
   - 路線規劃網址：`http://127.0.0.1:8989`
   - 圖磚檔案：`<專案資料夾>/current/taiwan.mbtiles`
   - 地址資料庫：`<專案資料夾>/current/taiwan_address.db`

### 注意事項

- 一定要用 `current/` 底下的路徑。更新後 `current` 會自動指向新版本；不要寫死 `releases/2026-11-02/` 這種含日期的路徑。
- 開著 mbtiles 或地址資料庫的程式，要重新開啟檔案才會讀到新版（例如每天重開連線，或偵測 `current.txt` 內容改變時重開）。
- 呼叫路線服務的範例（Java 11 以上）：

  ```java
  HttpClient client = HttpClient.newHttpClient();
  String url = "http://127.0.0.1:8989/route?profile=car&calc_points=false"
          + "&point=25.0478,121.5170&point=22.6394,120.3025";
  HttpResponse<String> resp = client.send(
          HttpRequest.newBuilder(URI.create(url)).build(),
          HttpResponse.BodyHandlers.ofString());
  // 回傳 JSON：paths[0].distance（公尺）、paths[0].time（毫秒）
  ```

  完整參數見 [GraphHopper Routing API](https://docs.graphhopper.com/openapi/routing/getroute)。

## 情境二：地端共用伺服器（內網多台 Tomcat 共用）

```
Tomcat 主機 A ─┐
Tomcat 主機 B ─┼──▶ 地圖主機 http://192.168.1.50:8989
Tomcat 主機 C ─┘      （防火牆只允許 A、B、C 連入）
```

### 步驟

1. 在地圖主機上照 [README](../README.md#在新電腦上安裝) 安裝，使用開機自動啟動的方式。
2. 編輯 `config/map.properties`，把路線服務開放給其他電腦：

   ```properties
   bind.host=0.0.0.0
   ```

3. 重新啟動服務（見[日常維運](#重新啟動服務)）。
4. **設定防火牆，只允許 Tomcat 主機連入 8989 埠**：

   Windows（以系統管理員身分開啟 PowerShell）：

   ```powershell
   New-NetFirewallRule -DisplayName "離線地圖服務" -Direction Inbound -Protocol TCP `
       -LocalPort 8989 -RemoteAddress 192.168.1.21,192.168.1.22 -Action Allow
   ```

   Linux（ufw）：

   ```bash
   sudo ufw allow from 192.168.1.21 to any port 8989 proto tcp
   sudo ufw allow from 192.168.1.22 to any port 8989 proto tcp
   ```

5. 各 Tomcat 專案的路線規劃網址改成 `http://192.168.1.50:8989`。
6. 在 Tomcat 主機上確認連得到：瀏覽器打開 `http://192.168.1.50:8989/health`，應該看到 `OK`。

### 注意事項

- **只有路線服務能透過網路共用**。圖磚和地址資料庫是檔案，其他主機讀不到。如果 Tomcat 專案需要直接讀這兩個檔案，請在那台 Tomcat 主機上也裝一份地圖服務（情境一），各自更新。
- 管理用的 8990 埠永遠只綁本機，不會因為 `bind.host` 而對外開放，防火牆也不需要開。
- 地圖主機請固定 IP，否則各 Tomcat 專案的設定會失效。
- 路線服務本身沒有帳號密碼，存取控制完全靠防火牆。不要把 `bind.host=0.0.0.0` 的主機直接接到網際網路上。

## 情境三：雲端主機

```
外部 / 其他雲端服務
        │ HTTPS
        ▼
┌──────────── 雲端主機 ────────────┐
│ 反向代理（Caddy / nginx）         │
│   map.example.com，限制來源 IP    │
│        │                         │
│        ▼                         │
│ 地圖服務 127.0.0.1:8989（只綁本機）│
└──────────────────────────────────┘
```

如果 Tomcat 專案也在同一台雲端主機上，直接照[情境一](#情境一單機地圖和-tomcat-在同一台)即可，不需要反向代理。

### 建議規格

| 項目 | 建議 |
|---|---|
| 作業系統 | Ubuntu 22.04 / 24.04 LTS |
| 規格 | 2 vCPU、4 GB 記憶體（例如 GCP e2-medium） |
| 硬碟 | 20 GB |

### 步驟

1. 安裝 Java 21 和 Git：

   ```bash
   sudo apt update
   sudo apt install -y openjdk-21-jre-headless git
   ```

2. 下載專案並安裝成開機自動啟動：

   ```bash
   git clone https://github.com/<帳號>/<repo名稱>.git ~/offline-map
   cd ~/offline-map
   bin/install-service.sh
   ```

3. `config/map.properties` **維持 `bind.host=127.0.0.1`**，地圖服務不直接對外。
4. 安裝反向代理，負責 HTTPS 和存取限制。以 Caddy 為例（會自動申請 HTTPS 憑證），`/etc/caddy/Caddyfile`：

   ```
   map.example.com {
       @allowed remote_ip 203.0.113.10 203.0.113.11
       handle @allowed {
           reverse_proxy 127.0.0.1:8989
       }
       respond "Forbidden" 403
   }
   ```

   `remote_ip` 填允許連線的來源 IP（例如其他雲端主機的固定 IP）。改完執行 `sudo systemctl reload caddy`。

5. 雲端防火牆只開放 80、443 給反向代理，**不要開放 8989、8990**。
6. 各專案的路線規劃網址改成 `https://map.example.com`。

### 注意事項

- 來源 IP 不固定、無法用 IP 限制時，可以改用 Caddy 的 `basic_auth` 加上帳號密碼，帳號密碼不要寫進這個 repo。
- 每月下載新版約 330 MB，屬於雲端主機的連入流量，各大雲端平台通常不收費。

## 日常維運

### 查看狀態

```bash
bin/run.sh status                  # Mac / Linux
java bin\MapService.java status    # Windows，在專案資料夾執行
```

會顯示本機使用的版本、OSM 資料日期、服務是否執行中，以及 GitHub 上的最新版。

### 紀錄檔

| 檔案 | 內容 |
|---|---|
| `logs/service.log` | 檢查更新、下載、切換、退回等所有動作 |
| `logs/graphhopper.log` | 路線服務本身的紀錄，啟動失敗時看這裡 |
| `logs/bad-releases.txt` | 在這台主機驗證失敗、不會再嘗試的版本 |

紀錄檔超過 5 MB 會自動換新檔，舊的保留一份（`.1`）。

### 重新啟動服務

改了 `config/map.properties`，或想立刻檢查更新時：

| 系統 | 指令 |
|---|---|
| Windows | 以系統管理員身分開啟 PowerShell：`Stop-ScheduledTask OfflineMapService; Start-ScheduledTask OfflineMapService` |
| Mac | `launchctl kickstart -k gui/$(id -u)/local.offline-map.service` |
| Linux | `sudo systemctl restart offline-map` |
| 用 `start.command` / `start.bat` 啟動的 | 關閉視窗再重新開啟 |

### 手動退回舊版

新版自動驗證通過、但實際使用發現問題時：

1. 把有問題的版本名稱加到 `logs/bad-releases.txt`（一行一個，例如 `2026-12-02`），避免服務又自動更新回去
2. 把 `current.txt` 的內容改成要使用的舊版本名稱（`releases/` 底下的資料夾名稱）
3. 重新啟動服務

### 更新地圖服務程式本身

程式有新版本時（不是地圖資料）：

```bash
cd <專案資料夾>
git pull
```

然後重新啟動服務。地圖資料和設定不受影響。如果 `config/map.properties` 有自己改過，`git pull` 可能會提示衝突，請保留自己的設定。

## 疑難排解

| 狀況 | 原因與處理 |
|---|---|
| 找不到 Java 21 以上 | 安裝 [Temurin 21](https://adoptium.net)；已安裝但找不到時，設定環境變數 `JAVA_HOME` 指向 JDK 資料夾 |
| 埠 8989 已被其他程式占用 | 關掉占用的程式，或在 `config/map.properties` 改 `port` |
| 無法檢查新版（連不上網路） | 確認主機能連到 `github.com`；公司網路需要 proxy 時，請網管開放上述網域 |
| GitHub 上還沒有地圖版本，或 github.repo 填錯 | 確認 repo 的 Releases 有 `map-` 開頭的版本；不是用 git clone 下載的，要在 `config/map.properties` 填 `github.repo` |
| 硬碟空間不足 | 清出至少 2 GB，或把 `keep.releases` 改成 `1` |
| 路線服務啟動失敗 | 看 `logs/graphhopper.log` 最後幾行；常見原因是記憶體不足，調低 `java.xmx` 或增加主機記憶體 |
| 其他電腦連不到（情境二） | 確認 `bind.host=0.0.0.0` 且已重新啟動、防火牆有開放、地圖主機 IP 正確 |
