# 對照手機與網關的量測程序

從倉庫根目錄執行。Windows 可將 `python` 換成網關虛擬環境的 `Scripts\python.exe`。

1. 選同一手機、網路、App／插件版號、列表與公開影片畫質。記錄操作者採用的測試順序、首度開啟或重複開啟、預期操作次數、實際失敗次數。各次條件獨立留存。手機磁碟／記憶體快取、網關圖片快取與瀏覽器冷啟動彼此獨立。不清除 App 資料或系統日誌。
2. 需要網關內部計時時，準備一個倉庫外的新 JSONL 檔。既有服務暫停後可用同一份設定手動啟動短時間的診斷程序。若網關仍在線，僅在確定可中斷目前使用時安排切換。

   ```powershell
   python services/challenge-gateway/run.py --config C:\private\config.json --timing-log C:\private\cold.jsonl
   ```

   計時會累積至程序停止。以 Ctrl+C 結束該診斷程序，再使用平常的啟動方式。若要在同一程序比較暖快取，避免重啟，改以 `--since`／`--until` 指定具時區的 ISO 8601 時間區間。盡量在區間內只跑指定測試，避免將其他客戶端的請求混入。

3. 已授權 ADB 的裝置可在另一個終端執行下列命令。見到收集提示後，在手機重新整理列表並開播固定樣本。等首幀後以 Ctrl+C 結束收集。若多台裝置已連線，用 `--serial` 指定那一台。也可在 AniZen 匯出當次日誌後使用 `report --phone-log`。

   ```powershell
   python .agents/skills/gateway-and-streaming-diagnostics/scripts/measure.py capture --adb C:\platform-tools\adb.exe --label cold --gateway-log C:\private\cold.jsonl
   python .agents/skills/gateway-and-streaming-diagnostics/scripts/measure.py report --phone-log C:\private\AniZen.log --gateway-log C:\private\cold.jsonl --label cold
   ```

4. 報表是 JSON。每個計時欄位包含樣本數與 P50。P95 採最近秩法，至少 20 個觀測值才顯示。狀態碼及取消次數單獨呈現。對照這些階段。

   | 階段 | 觀察欄位及限制 |
   | --- | --- |
   | 列表 | `page.navigation_ms` 是瀏覽器抓取 HTML；`page.headers_ms` 和 `first_body_ms` 包含網關其他處理。手機列表解析需要獨立 App 計時。 |
   | 縮圖 | 網關 `image` 分快取命中與未命中。比較 `queue_ms`、`pacing_ms`、`image_fetch_ms` 與首次寫出。RealImageLoader 成功事件顯示手機快取層，快取命中通常沒有網關請求。若 OkHttp 有記錄，`x1hub_asset_http_response_ms` 提供資源的 HTTP 回應耗時，範圍涵蓋該日誌中可辨識的網關資源請求。 |
   | 影片入口 | `media.media_open_ms` 包含資源額度、會話、DNS、節流、Cookie 與轉址；`upstream_headers_ms` 累計各上游回應標頭等待，`upstream_attempts` 記上游嘗試次數。 |
   | Range | 篩選 `media.range`、206。網關標頭與首塊是 ASGI 開始交付的時間，`probe.py` 另測探測端收到的標頭與先收到的資料。兩段 1 KiB 只能驗證 Range，不能代表完整 MP4 索引讀取或手機首幀。 |
   | 首幀 | `mpv_start_to_first_frame_ms` 從 AniZen 的 MPV `start-file` 至該次第一筆顯示事件。用 `starts` 和 `without_first_frame` 解讀，但後者可能源自退出播放或日誌截斷。 |

相同手機與網關的端內耗時不相互扣減。關聯用網關記錄的識別碼與受控測試區段。時間過濾適用於網關記錄，ADB 收集另外按開始及停止時間限制，以主機收集區間選擇網關記錄。網關若還在處理取消收尾，結束後再以 `report` 讀取完整的計時檔。
