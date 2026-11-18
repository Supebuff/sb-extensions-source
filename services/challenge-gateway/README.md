# Scrapling challenge gateway

X1hub 16.4 起提供的選用外部網關，宿主相容基準為 AniZen 0.5.220。API 與 Kotlin 用戶端可由後續來源共用；首版只啟用 X1hub，不會接管第三方插件。各網站必須新增獨立 `Site`／backend，不能共用 Cookie。

## Windows 執行

需要 Python 3.12+、已安裝的 Google Chrome，以及手機能連到的固定私有網路位址。首次安裝：

```powershell
python -m venv C:\path\gateway-venv
C:\path\gateway-venv\Scripts\python.exe -m pip install -r services\challenge-gateway\requirements.txt
& .\services\challenge-gateway\Start-Gateway.ps1 `
  -Python C:\path\gateway-venv\Scripts\python.exe `
  -StateDirectory C:\path\gateway-private `
  -BindAddress 192.168.0.70 -Port 8787
```

請以本機實際私有位址取代範例。預設只監聽 `127.0.0.1`，手機無法使用 loopback；不要將服務直接暴露至公網。啟動器以隱藏視窗執行，不建立開機啟動或排程，不會自動修改防火牆。電腦重新開機後需再啟動。啟動器會沿用既有私有設定，不會因再次執行而更換金鑰或監聽位址。

`StateDirectory` 必須位於原始碼 checkout 之外。首次啟動自動建立 `config.json`，包含隨機 API 金鑰、`bind`、`port`、`real_chrome` 和可選的 `x1hub_media_hosts`（精確 HTTPS CDN 主機清單，不接受萬用字元）。設定檔及 PID／日誌都留在此私有目錄；不要提交、上傳或貼出金鑰。瀏覽器使用獨立暫存 profile，不讀取個人 Chrome 登入或永久保存來源 Cookie。

在手機更新至 X1hub 16.5 後，開啟來源設定，填入「Scrapling 服務位址」及「Scrapling 服務金鑰」。由 16.4 更新可沿用相同設定與 API v1 服務。以下工具可在本機顯示位址並複製金鑰，避免把金鑰寫進聊天或文件：

```powershell
& .\services\challenge-gateway\Show-Connection.ps1 -StateDirectory C:\path\gateway-private
```

服務位址留空即使用原有直連流程；已填位址但連線或驗證失敗會明確報錯，不會悄悄退回手機直連或回傳空列表。金鑰不會傳給來源網站。切換位址／模式後重新整理列表及詳情；已儲存的舊縮圖網址不會立即重寫。網關模式的服務端間隔為 0.5 秒；原有「同一主機請求間隔」設定只控制手機直連。

16.5 用戶端遇到可辨識的 TCP 失敗／重設／過早關閉時，對讀取頁面及無持久副作用的票證建立最多額外嘗試一次，等待 350 毫秒後使用新連線池。這不會重送影片串流、慢速瀏覽器讀取或 HTTP 錯誤回應，也不會把取消當成可重試錯誤。持續失敗會顯示去除敏感資訊的分類代碼：`HTTP_BLOCKED`（明文政策）、`ACCESS_DENIED`（系統權限）、`DNS_FAILED`、`TLS_FAILED`、`NO_ROUTE`、`HTTP_PROTOCOL`、`TIMEOUT`、`CONNECT_FAILED`、`CONNECTION_CLOSED`、`CONNECTION_RESET` 或 `IO_FAILED`。單次恢復的回歸測試涵蓋真實 TCP 中斷、票證 POST 重播、嘗試次數上限、敏感訊息隔離及重試前／進行中的取消。

手機使用同一區域網路時，需能連到所選電腦位址／連接埠。若 Windows 防火牆阻擋，僅允許此 Python 程序在私用設定檔、所需的本地位址與手機來源範圍連入。此專案不自動建立廣泛的防火牆例外或路由器連接埠轉送。行動數據需要使用者另行設定可到達這台電腦的私人 VPN／隧道；公開主機的插件設定必須使用 HTTPS。HTTP 私有網路模式亦受宿主 Android 的明文連線政策影響。

正常停止會關閉本服務建立的瀏覽器：

```powershell
& .\services\challenge-gateway\Stop-Gateway.ps1 -StateDirectory C:\path\gateway-private
```

若舊版本不支援正常停止，可加 `-Force`，只會在 PID 與此設定檔相符時終止該服務及其子程序。修改程式或設定後需重新啟動。不要啟動多個 worker；會話、快取及冷卻目前存在單一程序內。

## API v1

| 路徑 | 驗證／用途 |
| --- | --- |
| `GET /healthz` | 不含來源資料的存活／API 版本檢查 |
| `GET /v1/sites/x1hub/page?url=...` | Bearer 金鑰；回傳 HTML，`X-Source-Url` 為原站最終頁面網址 |
| `POST /v1/sites/x1hub/tickets` | Bearer 金鑰；`{kind: image或media, urls: [...], referer: 原站頁面}`；回傳對應相對資源網址 |
| `GET/HEAD /v1/sites/x1hub/asset?ticket=...` | 簽章資源票證；提供圖片或 MP4 串流，播放器無須 API 金鑰 |
| `POST /v1/shutdown` | Bearer 金鑰；由本機停止工具呼叫，正常結束服務 |

圖片票證有效 7 天，影片票證有效 12 小時；過期請重新整理列表或重新開播。票證只授權單一網站、資源、類型與 Referer，持有者可在有效期內讀取該資源，應視為私人連結。更換 API 金鑰並重啟會使所有舊票證失效。

頁面與縮圖由同一持續 Scrapling 瀏覽器會話取得。2026-11-14 效能修正將圖片放在同一 context 的獨立資源頁，最多兩張並行；已有資源頁時不等待主頁導航，圖片擷取也不占用影片初始化的鎖。整體請求起始間隔仍至少 0.5 秒，等候後再次檢查主機冷卻。縮圖重複請求合併，最多快取 128 張／64 MiB、10 分鐘。圖片取消只中止對應 fetch；頁面取消只關閉導航頁，保留會話驗證狀態與資源頁。

每站最多一個導航頁及一個資源頁；資源頁以本地空白 HTML 建立本站 origin，實際圖片仍透過瀏覽器擷取。頁面及圖片各有 8 MiB 上限、90 秒操作期限（圖片 fetch 另有 60 秒中止計時）。不會把原站 Cookie 搬到手機。瀏覽器阻擋非本站／Cloudflare 驗證主機的請求、一般圖片自動載入、媒體自動載入及 WebSocket。

媒體由同一台電腦以瀏覽器 User-Agent、按目標 URL 篩選的 Cookie 及原站 Referer 轉送；HTTPX 的 TLS 指紋與瀏覽器並不相同，若 CDN 要求其他瀏覽器訊號，仍可能失敗。初始目標限本站 `/get_file/` 或明確允許的 CDN；重新導向逐跳驗證公開 DNS，媒體連線固定至已檢查 IP，保留原主機的 Host／TLS SNI。網站／網關金鑰、Authorization 與手機 Cookie 不會轉交 CDN。

支援單一 `Range`、`206`、`Content-Range`、`416`、HEAD，以及播放器取消時關閉上游連線。最多兩個媒體串流，按接收速度讀取 64 KiB 區塊，不緩衝整部影片。首版提供 MP4 轉送，不實作 HLS 子清單重寫，也不保證投放接收器能連到私有網關。來源 URL 到期、403 或驗證失敗會回報錯誤；重新開播會重新取得來源影片頁及新票證，未實作長時間播放中自動刷新來源網址。

成功的媒體轉址按「入口 URL＋Referer」快取 60 秒、最多 128 筆，減少播放器探測／拖曳時反覆經過 `/get_file/`。命中不延長到期時間，每次仍重新驗證目標 DNS、固定 IP 並按 URL 取 Cookie；不快取影片內容。快取目標回傳 403／404／410 時最多回原入口重新解析一次，整體仍最多六次上游請求；429／Retry-After 不觸發此恢復。這是短期轉址恢復，並未重新擷取影片頁刷新原始媒體 URL。

429／含 Retry-After 的 503 會進入有界退避；網站指定的長冷卻不會縮短。服務不無限重試擷取或媒體請求，不以 HTML 驗證頁偽裝成圖片／影片。正常執行關閉 HTTP access log 與第三方 URL 日誌；不記錄 Cookie、API 金鑰、標題或完整媒體連結。

## 驗證

```powershell
# API／票證／DNS／取消／Range 與跨主機標頭隔離的離線測試
python -m pip install -r services\challenge-gateway\requirements-test.txt
Push-Location services\challenge-gateway
python -m unittest discover -s tests -v
Pop-Location

# 需已啟動服務；少量實站測試，不下載完整影片，不輸出私人資料
python services\challenge-gateway\probe.py --config C:\path\gateway-private\config.json --live --images 24
# 四個用戶端同時要求圖片，輸出各階段毫秒耗時，仍受服務端兩張並行與 0.5 秒節流限制
python services\challenge-gateway\probe.py --config C:\path\gateway-private\config.json --live --images 4 --image-workers 4
```

Android 用戶端測試為 `:lib:challenge-gateway:testDebugUnitTest`；X1hub／core 既有測試、Release 建置與固定簽名沿用主 CI。Python 網關測試加入 CI prepare，失敗會阻止整體 CI 成功。

需要量測網關內部排隊、瀏覽器擷取、轉址與首塊時，可用 `run.py --config <私有設定> --timing-log <倉庫外的新 JSONL 檔>` 暫時開啟分段計時。搭配 [診斷 Skill 的量測程序](../../.agents/skills/gateway-and-streaming-diagnostics/references/measurement.md) 匯總，並對照已匯出的 AniZen 日誌或即時 ADB 收集。網關只寫入識別碼、時間、狀態、快取與請求類型，不記錄標頭、網址或票證。

2026-11-14 的開發樣本已取得列表、24 張縮圖，以及影片起始／位移後的兩段 1024 bytes，均符合預期。此煙霧測試沒有建立真實 Cloudflare 挑戰自動解開的證據，也不等於手機起播、長時間播放、切網路、下載或外部播放器已驗證。其他來源必須逐一接入及實測，不會因啟動此服務而自動生效。

同日效能修正的小樣本：修正前兩段 1 KiB Range 約 4688／5422 ms，修正後約 1125／625 ms；四張圖片整批分別約 1531／1672 ms，沒有觀察到一般圖片批次明顯加速。來源網路與快取會影響耗時，不能把這組結果當成固定加速比例或手機首幀時間；圖片改動主要避免取消重建會話及跨工作互相等待。此更新只改電腦服務，沿用 API v1、原金鑰及 X1hub 16.5，需重啟服務載入。

Scrapling 介面依據：[0.5.1 瀏覽器文件](https://github.com/D4Vinci/Scrapling/blob/bc602488b44e978da3266841e913af17577ec0fd/docs/fetching/stealthy.md)。原先比較與裝置回報見 [評估紀錄](../../docs/X1HUB_CLOUDFLARE_OPTIONS.md)。
