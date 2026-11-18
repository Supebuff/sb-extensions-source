---
name: gateway-and-streaming-diagnostics
description: Measure and diagnose AniZen X1hub list, thumbnail and MPV startup latency across Android and the Scrapling gateway. Use when comparing gateway performance, investigating slow thumbnails or playback, or verifying Range streaming, cache behavior, or request cancellation.
---

# AniZen 網關與串流診斷

先讀專案的 [現況](../../../docs/CURRENT_STATUS.md) 和 [網關操作說明](../../../services/challenge-gateway/README.md)。從倉庫根目錄執行下列工具。使用既有 Scrapling 虛擬環境，API 與依賴版本以目前 checkout 為準。

- **已有手機日誌**：用 [measure.py](scripts/measure.py) 的 `report --phone-log <file>`。以同一程序內的 MPV `start-file` 至第一筆顯示影格事件計算，略過拖曳後的後續影格。沒有可辨識的開播事件或首幀時回報缺失。這段時間不包括點擊後、播放器接手前的等待。
- **只驗證網關**：執行 `python services/challenge-gateway/probe.py --config <private-config.json> --live --images 4 --image-workers 4`。確認列表及少量圖片、MP4 開頭與第二段 Range。分別看回應標頭、首塊及完整小範圍耗時。
- **需要對照手機及伺服器**：按 [量測程序](references/measurement.md) 開啟私有計時記錄。用 `capture` 接收 ADB 日誌，或從 AniZen 匯出日誌後使用 `report`。分別記錄首次載入、重複載入，依報表中的樣本數、快取狀態與失敗情形解讀結果。

網關回傳 HTML，Android 的 Kotlin Parser 才產生列表。現有手機日誌缺少的解析、點擊或圖片解碼時間標為未量測。使用網關端與手機端各自的耗時，依測試區間與固定操作對照。MPV 沒有逐請求識別碼時，不能判定某筆 Range 對應哪次首幀。

取消測試應確認作業停止、鎖或串流額度釋放、後續請求可成功，且共享瀏覽器會話保留。使用現有回歸測試驗證清理，手機取消測試另外記錄結果。報表僅保留狀態、計數及耗時；原始日誌、完整網址、票證、Cookie、金鑰與標題留在倉庫外。
