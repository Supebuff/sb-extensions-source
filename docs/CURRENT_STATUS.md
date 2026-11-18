# 專案現況

最後核對於 **2026-11-18 23:14（Asia/Taipei）**。相容基準為 AniZen 0.5.220、Android 16。

## 已發布與目前環境

| 項目 | 現況 | 證據／入口 |
| --- | --- | --- |
| X1hub | **16.6**（版號代碼 6）已發布，本次 ADB 查驗也確認手機已安裝 16.6。Scrapling 模式會合併同一影片的取頁請求，開播可重用 20 秒內的播放資料。 | [APK](https://raw.githubusercontent.com/Supebuff/sb-extensions-source/repo/apk/aniyomi-all.x1hub-v16.6-release.apk) · [PR #5](https://github.com/Supebuff/sb-extensions-source/pull/5) · [來源說明](../src/all/x1hub/README.md) |
| 木瓜 | 套件庫發布 **16.6**。 | [來源說明](../src/zh/mugua/README.md) |
| Scrapling 網關 | Windows 服務以日常模式背景執行。本次電腦直接呼叫 `/healthz` 回傳 `status: ok`、`api: 1`。實機量測完成後已關閉分段記錄。 | [網關操作說明](../services/challenge-gateway/README.md) |
| 共用 Kotlin 用戶端 | X1hub 已接入頁面、圖片票證與 MP4 票證。其他來源尚未接入。 | [共用模組](../lib/challenge-gateway/) |
| 專案技能 | 已加入 Scrapling Official 0.5.1、Gradle Best Practices 1.0.1，以及可透過 ADB 收集手機日誌的診斷 Skill。Synaptic 1.2.0 專用 MCP 可查詢本專案。 | [PR #4](https://github.com/Supebuff/sb-extensions-source/pull/4) · [診斷操作](../.agents/skills/gateway-and-streaming-diagnostics/references/measurement.md) |

## 已驗證

| 類型 | 結果 |
| --- | --- |
| X1hub 16.6 建置與發布 | 43 項本機 JVM 測試通過。CI 通過 Android 建置、X1hub／共用網關／core 測試、固定簽名及套件庫發布。[發布 CI](https://github.com/Supebuff/sb-extensions-source/actions/runs/44331425133) |
| 網關診斷 | 26 項網關離線測試通過，已用兩輪實站圖片與 1 KiB Range 樣本驗證快取分類、首塊及正常完成。其後也在手機上實際收集 ADB 與網關計時。[診斷 CI](https://github.com/Supebuff/sb-extensions-source/actions/runs/44318860686) |
| X1hub 16.5 手機基準 | 2026-11-18，手機透過 Wi-Fi 可存取網關。熱門與最新列表成功，10 筆縮圖網路請求均為 HTTP 200。內建 MPV 有 4 次有效首幀紀錄，約 **4.11 至 9.01 秒**。 |
| 16.5 同片 720p 對照 | 相隔約 18 秒重播。開播前取頁 **6.61 → 6.27 秒**，第一個影片 Range 首塊 **1.44 → 0.94 秒**，MPV 開始載入至首幀 **4.54 → 4.11 秒**。重播的第一個 Range 命中轉址快取。 |

手機數據記錄的是 **16.5** 的小樣本。MPV 首幀從播放器開始載入算起，另有一段播放前取頁的等待。[實作背景與歷史量測](EXTENSION_NOTES.md)

## 尚待確認

- **優先驗收 16.6 共用影片頁。** 開啟詳情後在 20 秒內播放同一影片，核對網關只擷取一次影片頁，同時量測「按播放到首幀」。超過 20 秒再播及手動刷新詳情應重新取頁。保留同一手機、影片、畫質及網路作對照。
- **持續使用。** 分頁、閒置後返回、快速捲動取消縮圖、換畫質、續播、下載、外部播放器與長時間播放仍待逐項確認。
- **連線失敗與切網路。** 先前偶發列表失敗的根因未定；若再出現，記錄畫面上的錯誤分類代碼、操作及時間。手機切換網路或經私人 VPN 連網關仍待實測。
- **真實網站驗證。** 尚無本站提出 Cloudflare 挑戰且 Scrapling 自動解開的成功紀錄。

網關目前以手動方式啟動。測試時可依診斷 Skill 暫時開啟分段記錄，完成後恢復日常模式。服務金鑰、原始手機日誌及帶權杖的網址保留在倉庫外。[啟停與設定](../services/challenge-gateway/README.md#windows-執行)
