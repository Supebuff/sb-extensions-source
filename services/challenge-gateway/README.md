# Scrapling challenge gateway

X1hub 16.4 的選用外部網關，宿主相容基準為 AniZen 0.5.220。API 與 Kotlin 用戶端可由後續來源共用；首版只啟用 X1hub，不會接管第三方插件。各網站必須新增獨立 `Site`／backend，不能共用 Cookie。

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

在手機更新至 X1hub 16.4 後，開啟來源設定，填入「Scrapling 服務位址」及「Scrapling 服務金鑰」。以下工具可在本機顯示位址並複製金鑰，避免把金鑰寫進聊天或文件：

```powershell
& .\services\challenge-gateway\Show-Connection.ps1 -StateDirectory C:\path\gateway-private
```

服務位址留空即使用原有直連流程；已填位址但連線或驗證失敗會明確報錯，不會悄悄退回手機直連或回傳空列表。金鑰不會傳給來源網站。切換位址／模式後重新整理列表及詳情；已儲存的舊縮圖網址不會立即重寫。網關模式的服務端間隔為 0.5 秒；原有「同一主機請求間隔」設定只控制手機直連。

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

頁面與縮圖由同一持續 Scrapling 瀏覽器會話取得。網站請求串行節流；縮圖重複請求合併，最多快取 128 張／64 MiB、10 分鐘。瀏覽器頁面及圖片各有 8 MiB 上限、90 秒操作期限；圖片不會另開 24 個瀏覽器，也不會把原站 Cookie 搬到手機。瀏覽器阻擋非本站／Cloudflare 驗證主機的請求、一般圖片自動載入、媒體自動載入及 WebSocket。

媒體由同一台電腦以瀏覽器 User-Agent、按目標 URL 篩選的 Cookie 及原站 Referer 轉送；HTTPX 的 TLS 指紋與瀏覽器並不相同，若 CDN 要求其他瀏覽器訊號，仍可能失敗。初始目標限本站 `/get_file/` 或明確允許的 CDN；重新導向逐跳驗證公開 DNS，媒體連線固定至已檢查 IP，保留原主機的 Host／TLS SNI。網站／網關金鑰、Authorization 與手機 Cookie 不會轉交 CDN。

支援單一 `Range`、`206`、`Content-Range`、`416`、HEAD，以及播放器取消時關閉上游連線。最多兩個媒體串流，按接收速度讀取 64 KiB 區塊，不緩衝整部影片。首版提供 MP4 轉送，不實作 HLS 子清單重寫，也不保證投放接收器能連到私有網關。來源 URL 到期、403 或驗證失敗會回報錯誤；重新開播會重新取得來源影片頁及新票證，未實作長時間播放中自動刷新來源網址。

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
```

Android 用戶端測試為 `:lib:challenge-gateway:testDebugUnitTest`；X1hub／core 既有測試、Release 建置與固定簽名沿用主 CI。Python 網關測試加入 CI prepare，失敗會阻止整體 CI 成功。

2026-11-14 的開發樣本已取得列表、24 張縮圖，以及影片起始／位移後的兩段 1024 bytes，均符合預期。此煙霧測試沒有建立真實 Cloudflare 挑戰自動解開的證據，也不等於手機起播、長時間播放、切網路、下載或外部播放器已驗證。其他來源必須逐一接入及實測，不會因啟動此服務而自動生效。

Scrapling 介面依據：[0.5.1 瀏覽器文件](https://github.com/D4Vinci/Scrapling/blob/bc602488b44e978da3266841e913af17577ec0fd/docs/fetching/stealthy.md)。原先比較與裝置回報見 [評估紀錄](../../docs/X1HUB_CLOUDFLARE_OPTIONS.md)。
