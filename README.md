# 台灣離線地圖服務

提供台灣的**路線規劃**、**底圖圖磚**和**地址資料**，給各個專案（例如 Tomcat 網站）共用。

- 地圖資料由 GitHub Actions **每月自動建置**，發布在本 repo 的 [Releases](../../releases)
- 各台電腦上的地圖服務**開機時和每天自動檢查**，有新版就下載、驗證、切換，有問題自動退回舊版
- Mac、Windows、Linux 都能用，不需要自己建置資料、也不用設定排程

| 內容 | 位置 | 用途 |
|---|---|---|
| 路線服務 | `http://127.0.0.1:8989` | GraphHopper 汽車路線規劃（[API 說明](https://docs.graphhopper.com/openapi/routing/getroute)） |
| 底圖圖磚 | `current/taiwan.mbtiles` | OpenMapTiles 格式，zoom 0–14 |
| 地址資料庫 | `current/taiwan_address.db` | 門牌查座標（SQLite） |

## 在新電腦上安裝

### 1. 事前準備

- **Java 21 以上**：到 [adoptium.net](https://adoptium.net) 下載 Temurin 21，安裝時一路下一步即可
- **Git**：Mac 第一次在終端機打 `git` 會提示安裝；Windows 安裝 [Git for Windows](https://git-scm.com/download/win)
- 硬碟留 2 GB 以上

### 2. 下載專案

```bash
git clone https://github.com/<帳號>/<repo名稱>.git
```

### 3. 啟動

| 系統 | 做法 | 之後 |
|---|---|---|
| **Windows** | 雙擊 `install.bat`，跳出管理員權限詢問時按「是」 | 開機自動在背景執行，不用再管 |
| **Mac** | 雙擊 `start.command` | 關閉視窗就停止；想開機自動執行，在終端機跑 `bin/install-service.sh` |
| **Linux** | 執行 `bin/install-service.sh`（需要 sudo） | 開機自動執行，不用再管 |

第一次會下載約 400 MB 的地圖資料，看網速大約 3～10 分鐘。畫面出現「完成」或「地圖服務就緒」就可以用了，用瀏覽器打開 `http://127.0.0.1:8989` 確認。

### 移除

- Windows：雙擊 `uninstall.bat`
- Mac / Linux：`bin/install-service.sh --remove`

地圖資料會保留在 `releases/`，不需要的話可以整個資料夾刪掉。

## 日常運作

```
GitHub Actions（每月 2 日）                       各台電腦上的地圖服務
  下載台灣 OSM → 建置 → 驗證 → 發布 Release  ──▶  開機時、每天檢查一次
                                                   有新版 → 下載、檢查碼核對
                                                   → 切換 → 實際查一條路線
                                                   → 正常就清掉更舊的版本
                                                   → 有問題就退回舊版
```

- **電腦關機時不會更新**，但也沒人在用；下次開機會自動補上
- **沒有網路時**照常使用本機現有的版本
- 驗證失敗過的版本會記在 `logs/bad-releases.txt`，不會一直重試
- 執行紀錄在 `logs/service.log`，路線服務本身的紀錄在 `logs/graphhopper.log`

查看狀態：

```bash
bin/run.sh status                      # Mac / Linux
java bin\MapService.java status        # Windows（在專案資料夾裡執行）
```

## 設定

都在 `config/map.properties`，改完重新啟動服務即生效：

| 設定 | 預設 | 說明 |
|---|---|---|
| `github.repo` | 自動判斷 | 從哪個 repo 下載，用 git clone 的不用填 |
| `port` | 8989 | 路線服務的埠號 |
| `bind.host` | 127.0.0.1 | 只有本機能連；改成 `0.0.0.0` 可讓其他電腦連，請自行用防火牆限制來源 |
| `java.xmx` | 2g | GraphHopper 最多可用記憶體 |
| `check.interval.hours` | 24 | 多久檢查一次新版 |
| `keep.releases` | 2 | 本機保留幾個版本 |

`config/config.yml` 是 GraphHopper 的設定，`config/visitmap_car.json` 是汽車路線規則。改了這兩個檔案的路線規則，需要等下一次 GitHub Actions 重新建置才會生效。

## 給使用這個服務的專案

- 路線規劃：呼叫 `http://127.0.0.1:8989/route?profile=car&point=緯度,經度&point=緯度,經度`
- 直接讀圖磚或地址資料庫的程式，請用 `current/` 底下的路徑，更新後會自動指向新版；已經開著的檔案要重新開啟才會讀到新版
- 地圖畫面上請標示「© OpenStreetMap contributors」和「© OpenMapTiles」

## 維護者：第一次設定 GitHub

只需要做一次。

1. **推上 GitHub**：建立公開 repo，把這個資料夾推上去
2. **上傳地址資料庫**：地址資料庫的原始來源目前無法自動下載，所以先手動上傳一份，之後每月建置都沿用它
   1. repo 頁面右側 **Releases** → **Draft a new release**
   2. **Choose a tag** 輸入 `address-seed`，選「Create new tag」
   3. 標題隨意，例如「地址資料庫（初始）」
   4. 把 `taiwan_address.db` 拖進附件區（可以先壓成 `taiwan_address.db.gz` 比較快）
   5. **勾選「Set as a pre-release」**（很重要，否則各台電腦會誤以為這是地圖版本）
   6. **Publish release**
3. **跑第一次建置**：**Actions** 分頁 → 左邊「每月建置地圖資料」→ **Run workflow**。大約 20～30 分鐘，完成後 Releases 會出現 `map-日期` 的版本
4. 之後每月 2 日會自動建置，GitHub 上保留最近 3 版

### 更新地址資料庫

找到地址資料的來源後，寫一支 `bin/build-address.sh`（參數是輸出的 `.db` 路徑），建置時就會自動改用它重建。

只是要換一份新的地址資料庫：把新檔案重新上傳到 `address-seed`（先刪掉舊附件），然後 **Actions → 每月建置地圖資料 → Run workflow**，勾選「改用 address-seed 裡的地址資料庫」再執行。

### 手動建置（除錯用）

```bash
ADDRESS_DB=/path/to/taiwan_address.db bin/build.sh dist
```

需要 Java 21、curl、sqlite3、zip。建置設定（工具版本、驗證路線）在 `bin/common.sh` 最上面。換 GraphHopper 版本時，各台電腦會在下次更新時自動下載對應版本。

## 檔案結構

```
.github/workflows/build-map.yml   每月自動建置
bin/MapService.java               地圖服務本體（下載、驗證、切換、執行路線服務）
bin/run.sh                        Mac / Linux 啟動 MapService
bin/install-service.sh            Mac / Linux 設定開機自動啟動
bin/windows/                      Windows 安裝、移除、啟動
bin/build.sh、bin/common.sh       建置地圖資料
config/                           設定檔
install.bat、uninstall.bat、start.bat   Windows 雙擊用
start.command                     Mac 雙擊用
LATEST.md                         目前最新版本資訊（自動更新）

以下由程式自動產生，不在 git 裡：
releases/日期/                    各版本的地圖資料
current、current.txt              指向使用中的版本
tools/                            GraphHopper 程式
logs/                             執行紀錄
```

## 授權

地圖資料 © [OpenStreetMap](https://www.openstreetmap.org/copyright) contributors，採 ODbL 授權；底圖樣式 © [OpenMapTiles](https://openmaptiles.org)。
