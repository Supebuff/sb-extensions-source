# 專案現況

確認時間：**2026-11-14 21:55（Asia/Taipei）**。此頁記錄當次核對結果；裝置與服務狀態不代表持續監控。相容基準為 AniZen 0.5.220、Android 16。

## 已交付

| 項目 | 目前狀態 | 證據／入口 |
| --- | --- | --- |
| X1hub | **16.5**，固定簽名 APK 與套件庫索引已發布；套件／來源 ID 保留 | [APK](https://raw.githubusercontent.com/Supebuff/sb-extensions-source/repo/apk/aniyomi-all.x1hub-v16.5-release.apk)、[來源說明](../src/all/x1hub/README.md) |
| 木瓜 | 套件庫目前發布 **16.6** | [來源說明](../src/zh/mugua/README.md) |
| Scrapling 網關 | API v1 已實作並在目前 Windows 電腦背景執行，當次 `/healthz` 回應 `status: ok`、`api: 1` | [操作與限制](../services/challenge-gateway/README.md) |
| 共用 Kotlin 用戶端 | X1hub 已接入頁面、圖片票證及 MP4 票證；其他來源尚未接入 | `lib/challenge-gateway` |
| Synaptic | 1.2.0，已註冊此專案專用 MCP 並實際查詢成功；使用本機唯讀分析 | [專案工作指引](../AGENTS.md#synaptic) |

X1hub 16.5 的功能程式基準為 [7984f74](https://github.com/Supebuff/sb-extensions-source/commit/7984f74f2f0eb23ffd6cabf766e6f8c8fe9563ab)，由 [PR #2](https://github.com/Supebuff/sb-extensions-source/pull/2)合併。可辨識的 TCP 連線失敗／重設／過早關閉，會等待 350 毫秒後以新連線額外嘗試一次；持續失敗呈現固定分類代碼。原網關位址、金鑰及 API v1 可沿用。

## 已確認的驗證範圍

| 類型 | 結果及界線 |
| --- | --- |
| 16.5 正式 CI | [CI 43598796954](https://github.com/Supebuff/sb-extensions-source/actions/runs/43598796954)整體成功，含 Release APK 建置、固定簽名／憑證驗證與套件庫發布 |
| JVM 回歸 | 共用網關用戶端、X1hub 與 core 測試通過；涵蓋實際 loopback TCP 中斷、GET／票證 POST 重播、嘗試次數上限、敏感訊息遮蔽及重試前後取消 |
| Python／CI 工具 | 17 項網關離線測試、54 項 CI 工具回歸及工作流程 lint 通過 |
| 網站／HTTP 樣本 | 開發端經網關取得列表、24 張縮圖及影片起始／位移後各 1024 bytes 的有效 MP4；另一次帶金鑰的熱門列表請求 HTTP 200，約 6.3 秒 |
| 手機網路 | 使用者確認手機瀏覽器可取得網關健康回應，來源設定的位址正確 |
| 16.4 手機列表／圖片 | 使用者回報列表偶發連線失敗、刷新後恢復；20:54 日誌有 6 筆網關圖片磁碟快取成功及其後 6 筆記憶體快取成功 |

本輪功能 CI 的 APK 範圍為 X1hub；木瓜 16.6 是既有發布版本。網站樣本及圖片快取成功，不等於手機即時擷取、播放或全片庫通過。

## 尚待確認

- **16.5 手機回報**：尚未確認已安裝此版後，首次進入列表、閒置後返回、刷新、分頁及縮圖是否持續正常。
- **原始連線失敗根因**：兩份手機日誌均未保留該次失敗的例外堆疊，不能認定為失效連線重用、AdGuard 或其他特定原因。16.5 提供有界恢復及分類診斷，仍需現場驗證改善效果。
- **手機媒體流程**：16.4／16.5 的內建與外部播放器開播、拖曳、換畫質、續播、下載、長時間播放及連結到期恢復尚待逐項確認；先前 16.1 的結果不沿用為新版通過證據。
- **真實 Cloudflare 挑戰**：尚無本站實際提出挑戰且 Scrapling 自動解開的成功紀錄。
- **行動數據／切網路**：手機到網關的私人 VPN／隧道路由尚未驗證，不能只憑同一區域網路可用就推定行動數據可用。

## 操作與後續追查

- 網關保持電腦在線；目前為手動背景啟動，沒有設定開機啟動、排程、對外連接埠轉送或自動防火牆例外。Windows 啟停與連線設定工具見[網關說明](../services/challenge-gateway/README.md#windows-執行)。
- 服務金鑰及私有設定保留在 checkout 外，不放入本頁、程式庫、聊天或公開產物。原始手機日誌及帶權杖的媒體網址不加入倉庫。
- 下一次手機連線失敗，優先記錄 16.5 的畫面分類代碼、操作與時間；在同一次 App 執行期間匯出日誌，區分列表、圖片與播放問題。
- 技術原因、限制及歷史證據見[插件技術紀錄](EXTENSION_NOTES.md)，正式建置／簽名／發布操作見 [CI 說明](CI.md)。
