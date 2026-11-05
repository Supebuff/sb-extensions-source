# 插件技術與踩坑紀錄

本檔用於開發交接與跨插件比對，先收錄本 fork 的木瓜與 X1hub；其他來源在新增或實際維護時依末尾格式追加。核對基準為 2026-11-05 的程式碼與本次對話提供的手機回報，並非上游整個片庫的驗證報告。

## 文件分工與更新時機

| 檔案 | 維護內容 | 何時同步 |
| --- | --- | --- |
| [AGENTS.md](../AGENTS.md) | 長期工作規則、閱讀入口、驗證與交付原則 | 共通開發規範改變 |
| 本檔 | 插件技術差異、踩坑原因／處理、證據及尚待確認項目 | 新來源、重要修正、手機回報或技術限制改變 |
| `src/<lang>/<source>/README.md` | 該來源目前功能、設定、使用方式、已驗證範圍 | 使用者可見行為或裝置實測結果改變 |
| [docs/CI.md](CI.md) | CI 範圍、簽名、產物與執行方式的權威說明 | 建置／交付流程改變 |
| [development_guide.md](../development_guide.md)、[CONTRIBUTING.md](../CONTRIBUTING.md) | 開發慣例與範例 | 通用指引與實際程式碼有落差時修正相關段落 |

修正問題時同時檢查「程式、相關回歸測試、來源 README、本檔」；共通流程有改變才更新 AGENTS 或 CI 文件。記錄日期、套件／宿主版本及證據，把現象、原因與推測分開。不複製完整日誌、影片標題、私鑰、Cookie 或帶權杖的媒體網址。

## 木瓜與 X1hub 的同步範圍

| 項目／檔案 | 木瓜 | X1hub | 後續做法 |
| --- | --- | --- | --- |
| 模組／版本 | `src/zh/mugua`；16.6 | `src/all/x1hub`；16.1 | 保留各自名稱、語言、套件 ID、來源 ID 與儲存網址；版本獨立遞增 |
| `build.gradle` | 套用 `common.gradle`；正式來源 `src/`、測試 `test/` | 相同；另有實站測試環境開關輸入 | 維持測試來源集與依賴規範一致；有實站測試才加入開關 |
| `AndroidManifest.xml`、`res/` | 沿用來源 manifest；允許明文連線；16.6 新增使用者提供的紅白專屬圖示 | 來源 manifest；提供網站 favicon 衍生圖示 | 檢查套件類別、成人標記、圖示及備份設定；依站點需求決定明文連線 |
| 解析與播放 | JSP 列表／不透明分頁；DPlayer 明文或 XOR 0x80 腳本中的 HLS | KVS 列表、搜尋／分類 AJAX 分頁；`flashvars` 與 `function/0` MP4 | 解析器與測試樣本依來源維護，不共用網站選擇器與解碼器 |
| HTTP／節流 | `MuguaHttpClient.kt`、`MuguaRequestPolicy.kt` | `HttpClient.kt`、`RequestPolicy.kt` | 同步檢查取消、重新導向、敏感標頭、Retry-After、無限重試；有意義的來源差異保留 |
| 請求設定 | 0.5／1／2 秒；頁面快取允許 10 分鐘 | 0.5／1／2 秒；頁面快取允許 5 分鐘 | 基本保護原則一致；快取時間依來源獨立評估，播放頁均強制重新取得 |
| 縮圖 | 預設本機相容模式；2 個並行、失敗暫停 5 分鐘、約 64 MiB 快取 | 由 App 直接載入，沒有本機縮圖快取 | 只有重現標頭／相容問題才移植木瓜方案 |
| 設定呈現 | 功能列表、請求間隔、本機縮圖、最新分類與保護範圍 | 功能列表、請求間隔與使用提示 | 說清楚功能與 App 控制範圍；不要顯示尚未實作的選項 |
| JVM 測試 | 解析、網路、節流、縮圖快取 | 解析、網路、節流、手動啟用的網站測試 | 網站錯誤／空結果／取消等重要路徑應有對應驗證；縮圖與實站測試按需要增加 |
| CI／簽名 | fork 新增模組 | fork 新增模組 | 沿用同一選取器與固定簽名；新增來源自動發現，不另加硬編碼清單 |
| 手機結果 | 本次僅核對程式與現有文件，未重新手機實測 | 使用者已回報列表、播放、切畫質、拖曳、續播、下載及外部播放器可用 | 分別記錄各來源、各功能的驗證範圍，不互相推定通過 |

HTTP 與節流目前是各模組內的實作，尚未抽成共用函式庫。修改其中一份時，應檢查另一份是否有同類問題並執行受影響測試；不直接把整個檔案互相覆蓋。木瓜有縮圖失敗狀態管理，兩份 policy 的最小間隔約束也有差異。

### 共通開發與交付順序

1. 讀取 AGENTS、來源 README、本檔與 CI 文件，檢查分支、工作區及遠端最新提交；保留未提交內容。
2. 核對 `common.gradle`、`settings.gradle.kts`、`gradle/libs.versions.toml` 與實際宿主 API。新來源按目錄自動載入，無須在 settings 手動逐項 include。宿主提供的依賴優先 `compileOnly`；編譯版本較新也不能假定宿主具備新方法。
3. 用少量樣本確認列表、搜尋、分類、分頁、詳情、集數及媒體流程。來源設定要說明支援功能與節流範圍。
4. JVM 測試放在模組 `test/` 並設定來源集。純文件變更依 AGENTS 檢查內容、引用與 `git diff --check`，不必重建 APK。
5. 程式變更執行該模組 JVM 測試及 APK 建置；共用程式變更另驗證相依來源與 core。線上樣本檢查、APK 建置／簽名、手機實測分開回報。
6. 確認變更範圍後提交／推送，查看實際 CI 選取模組與最終結果。一般 `changed` 範圍會排除符合位置規則的純文件（包含模組 README）；手動 `added` 仍建置全部新增模組。依實際任務回報，不因文件 CI 通過就聲稱手機已重新驗證。
7. 交付簽名產物、提交與 CI 連結。純文件更新不遞增套件版號；交付程式更新時各自遞增 `extVersionCode`，沿用固定金鑰。

建置與簽名命令以 [CI 文件](CI.md)為準。更換模組任務時，例如木瓜 `:src:zh:mugua:testDebugUnitTest`、X1hub `:src:all:x1hub:testDebugUnitTest`，兩者共用 `:core:testDebugUnitTest`。

## 共通踩坑與判讀

| 現象／陷阱 | 已知原因或界線 | 處理原則與證據 |
| --- | --- | --- |
| 測試被當成正式程式編譯 | `common.gradle` 把整個模組 `src/` 當正式來源 | 使用模組 `test/`；核對兩來源 `build.gradle` |
| 新模組沒有 manifest，乾淨建置失敗 | 本次 X1hub 建立時，共用腳本嘗試寫入尚不存在的 build 目錄 | 沿用來源的實體 `AndroidManifest.xml`；X1hub 初版已包含 |
| 使用 v16 編譯 stub 時，設定頁 AndroidX 方法不可直接呼叫 | stub 未宣告宿主提供的 `Preference(Context)` 與 `setSelectable` | 兩來源的說明列均沿用反射方式；仍需核對實際宿主和裝置呈現 |
| 請求取消後仍繼續下載、網路錯誤被當空結果 | 吞掉取消例外或以空列表掩蓋解析失敗會造成誤判 | 延續可取消等待與 Call.cancel；將真正無搜尋結果與結構變更分開 |
| 轉址繞過節流或帶出明確指定的敏感標頭 | 來源手動處理重新導向 | 每個目的地主機套用 policy；跨主機移除明確的 Authorization／Cookie／Proxy-Authorization |
| HTTP 429／503 後持續重試 | 來源頁面請求需遵守伺服器冷卻 | 支援秒數與 HTTP-date；保留較長期限；沒有有效 429 等待值時退避 30 秒起、最多 15 分鐘 |
| 把所有流量都當成已節流 | 頁面、遠端圖片、媒體緩衝及 App 下載分屬不同路徑 | 木瓜本機縮圖納入保護；X1hub 遠端圖片及兩來源 App 管理的媒體流量不在頁面 policy 範圍 |
| 格式檢查掃到其他模組或產生檔 | Gradle 未加冒號的任務選取可包含子專案；過寬 target 會納入 build 目錄 | 根任務明確使用 `:spotlessKotlinCheck` 等名稱；檢查改動的正式與 test 程式，排除 build |
| HTTP 200／206 或 CI 綠燈被當成手機功能通過 | HTTP、解析、建置與裝置行為是不同層級 | 每項結果註明測試方式、版本、日期與尚未涵蓋的功能 |

## 木瓜 Mugua

- 入口與結構：`https://91quanji.com`，`zh` 成人來源。`Mugua.kt` 負責宿主介面與設定，`MuguaParser.kt` 負責 JSP 列表、實際分頁連結及 HLS 解析。
- 分頁沿用網站給出的 URL／不透明參數；保留 `watch.jsp?v=...` 的既有儲存網址。不要套用 X1hub 的 AJAX block 參數。
- 播放解析支援目前 DPlayer 物件、XOR 0x80 包裝及 video/source 的 HLS；開播時重新取得播放頁，附上影片頁 Referer。
- 縮圖相容：受影響 AniZen 版本的遠端封面請求可能遺失來源標頭；本機模式由來源下載並回傳 App 私有檔案路徑，避免依賴外部圖片代理。
- 16.5 現行縮圖行為：2 個並行，單圖上限 2 MiB，約 64 MiB 持久快取；同圖請求共用快取／失敗判斷；失敗暫停 5 分鐘，失敗不回退到遠端 URL；快取可在主機冷卻時繼續使用。檔案放在 noBackupFilesDir。
- 文件與驗證：見 [來源 README](../src/zh/mugua/README.md)、[測試](../src/zh/mugua/test/)。本次不新增木瓜裝置通過宣告。

重要實作紀錄：[16.3 解析與分頁修正](https://github.com/Supebuff/sb-extensions-source/commit/5592b88c6)、[16.4 本機封面相容](https://github.com/Supebuff/sb-extensions-source/commit/59f02390a)、[16.5 節流與功能說明](https://github.com/Supebuff/sb-extensions-source/commit/52299a439)。

## X1hub

- 入口與結構：`https://www.x1hub.com`，`all` 成人來源；英文介面與多語系標題，不代表一定有字幕。`X1hub.kt`、`Parser.kt`、`HttpClient.kt`、`RequestPolicy.kt` 分別維護宿主流程、解析、HTTP 與節流。
- 一般列表沿實際 Next 網址；搜尋／分類的 `#search`、`#videos` 連結需要 `mode=async`、`function=get_block`、`block_id` 與 `data-parameters`。搜尋的 `from_videos+from_albums` 要拆為兩個計數參數，並排除相簿的分頁。
- KVS 5.5.1：讀取 `flashvars` 公開 MP4，`function/0` 依 license 還原 hash 前 32 個字元，保留後綴與 query。不執行網站 JavaScript；排除 `*_redirect=1` 的登入跳轉，不能把登入頁上的 1080p 標籤當作可播放畫質。
- 搜尋無結果時網站會改顯示推薦影片。已加入明確無結果判斷及回歸測試，避免將推薦內容當成搜尋結果。
- 本機 HTTP 樣本曾驗證熱門、最新、搜尋、中文分類前兩頁，各頁 24 筆且內容不同；720p／480p 回傳 HTTP 206 並通過前 1024 bytes 的 MP4 ftyp 檢查。這只代表當時少量樣本。
- 有手動啟用的 `LiveSiteTest`；正常 CI 略過，`X1HUB_LIVE_TEST` 作為 Gradle 測試輸入。實站測試只讀少量位元組，不下載完整影片或輸出帶權杖網址。
- 目前縮圖由 App 直接載入。沒有重現木瓜的縮圖標頭問題，不先搬入木瓜本機快取與其額外依賴／設定。

### 手機回報與處理結果

以下為 2026-11-05 使用者回報，並於 2026-11-06 補充網路切換結果；宿主 AniZen 0.5.220、Android 16、來源 16.1。不代表全片庫或所有網路環境。

| 項目 | 證據與結果 | 狀態／後續 |
| --- | --- | --- |
| Cloudflare 自動驗證 | 首次列表顯示無法繞過；WebView 可開啟，返回重試後列表正常 | 有可用的手動流程；自動失敗原因未定，未新增自製繞過器。若反覆要求驗證或 WebView 成功仍失敗，再調查 |
| 開播、切畫質、拖曳、續播、外部播放器 | 使用者明確回報可用 | 功能已有手機樣本驗證；切換／拖曳等待時間另列觀察 |
| 下載到 100% 未完成 | 日誌在 Downloader.finalizeDownload 出現 Operation not permitted 與 Could not create destination file | 重新授權原 AniZen 儲存位置後，使用者確認正常。此案例屬 App 儲存收尾／授權問題，未修改來源媒體解析 |
| 切畫質／拖曳轉圈較久 | 使用者確認沒有同時下載，決定再試、先觀察 | 原因未定；不歸因於網站限流、播放器或插件，也不先修改參數 |
| 搜尋分頁特殊字元 | 程式目前以分號拆分網站參數；含分號的搜尋字串有截斷風險 | 僅程式碼審查風險，尚未在有下一頁的實站樣本重現；未列為已確認故障 |
| 行動數據切至 Wi-Fi（2026-11-06） | 使用者確認切換後播放失敗，需進 WebView 完成網站驗證才恢復；同次日誌有圖片載入器的 CloudflareBypassException 及媒體開啟失敗，但沒有對應媒體 HTTP 狀態碼 | 已確認觸發條件與手動恢復流程；優先追查新網路下的驗證／Cookie 流程。尚未證實媒體網址綁定 IP，也不能據此解釋同份日誌中的 WebView 原生崩潰或集數重複插入；未修改解析、播放器或版號 |
| 其他手機範圍 | 成人篩選、設定頁、縮圖、持續分頁、長時間播放、重啟、其他網路切換方向與自動恢復、連結到期、背景播放與投放未逐項確認 | 保留待測，不沿用其他通過項目的結論 |

下載錯誤的判讀曾對照 [AniZen 0.5.220 的 finalizeDownload](https://github.com/salmanbappi/AniZen/blob/v0.5.220/app/src/main/java/eu/kanade/tachiyomi/data/download/Downloader.kt)與[儲存位置授權流程](https://github.com/salmanbappi/AniZen/blob/v0.5.220/app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsDataScreen.kt)。只記錄必要錯誤摘要，不提交原始手機日誌或截圖。

實作與交付證據：[初版提交 1b229a6f6](https://github.com/Supebuff/sb-extensions-source/commit/1b229a6f6b61fd459252de116a2319108bf66ce3)、[CI #13](https://github.com/Supebuff/sb-extensions-source/actions/runs/42024723682)；CI 曾完成測試、Release 建置、固定簽名與憑證驗證。歷史產物可能到期，後續安裝包以對應提交的有效 CI 產物為準。

來源操作與現行限制見 [X1hub README](../src/all/x1hub/README.md)。

## 合併後的分支清理（2026-11-05）

- [工作流程](../.github/workflows/delete_merged_branch.yml)僅處理已合併、來源分支在同一倉庫的 PR；保留原有事件條件。
- 刪除步驟以精確名稱保留 `main`、`master`、`dev`、`upstream`、`repo`；空值或未設定分支名稱也略過。其他名稱通過 `git check-ref-format` 後，只刪除完整 `refs/heads/<名稱>`，避免與其他 ref 混淆。
- [回歸測試](../.github/tests/test_branch_cleanup.py)在獨立暫存目錄攔截 Git 呼叫，覆蓋保護分支、空值、功能分支、相似名稱、字元轉義、無效名稱及刪除失敗回報；不連線或刪除遠端分支。
- 主 CI 的 actionlint 同時檢查建置與清理工作流程，unittest discover 會執行清理回歸測試。這是工作流程層的保護；GitHub 倉庫 ruleset／分支保護設定是另一層管理設定。

## 純文件 CI 選取（2026-11-05）

- 起因：文件 CI #14 仍選中了木瓜與 X1hub，因原選取器按模組路徑匹配，舊 docs-only 測試只覆蓋根目錄 README。
- 修正：[ci-matrix.py](../.github/scripts/ci-matrix.py)在一般 `changed` 範圍先過濾根目錄、模組根目錄說明文件及對應 `docs/` 文件／圖片，再將明確檔案清單傳給 [get_module_list](../.github/scripts/generate-build-matrices.py)。上游輔助腳本未傳清單時的行為保留。
- 邊界：僅依已知文件位置與格式排除，不全域忽略 `.md`／`.txt`；`src/`、`res/`、`assets/`、測試資料、manifest 與建置設定仍納入。混合修改依實際程式變更選模組；比較時停用 rename 合併，保留改名前後路徑，避免把「程式改名成 README」當成純文件。
- [回歸測試](../.github/tests/test_ci_matrix.py)使用實際 Git 提交樣本，涵蓋模組／共用函式庫說明、混合修改、資源與測試資料、文件新增／修改／刪除／改名，以及空矩陣的 `has_build=false`。手動 `added` 與首次推送 fallback 仍可完整建置。
- CI 仍執行工作流程 lint 與回歸測試；空矩陣時略過 APK 測試／建置與簽名。完整辨識位置及格式以 [CI 文件](CI.md)為準。

## 木瓜 16.6 圖示更新（2026-11-05）

- 原先沒有模組專屬 launcher 圖示，因此使用 `core/` 的共用書籤圖片。16.6 在木瓜的 `res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png` 提供 48／72／96／144／192 px 的透明 PNG，以模組資源優先序覆蓋共用圖片。
- 採用使用者提供的 32×32 PNG data URL 紅白圖示，保留圖案與 alpha 通道；僅以 Lanczos 縮放及 PNG 優化匯出。放大不增加原圖細節，不推定圖片的商標或授權歸屬。
- 版本遞增至 16.6，保持套件／來源 ID、網址與固定簽名不變；沒有調整網路、解析及播放實作。圖片尺寸／透明度、APK 打包與實體手機顯示應分開記錄驗證結果，裝置顯示仍待確認。

## Automatic 套件庫發布（2026-11-05）

- `dev` 成功建置、固定簽名後，獨立發布 job 再驗證 APK，將圖示、APK、`index.min.json`／`index.json`／`repo.json` 與公開校驗資料推送到同倉庫 `repo` 分支。README 一鍵加入與手動網址均指向本 fork。
- 核對 AniZen 0.5.220 `ExtensionRepoService`：陣列索引會讀取旁邊 `repo.json` 的 `meta`，採用 legacy 列表、語言及相對 APK／圖示路徑。採用此相容格式，不要求 Protobuf 或新增執行期依賴。`sources` 為可選空列表，沒有猜測來源 ID。
- 發布沿用固定憑證；只允許 fork 新增模組，拒絕 Debug、錯誤套件／憑證與降版。同版號保留已發布檔案；部分建置合併時保留其餘套件，移除的來源於下次成功發布清理。
- 只在發布 job 給 `contents: write`，使用內建 `GITHUB_TOKEN`，不傳簽名 Secret；一般文件、PR 與非 dev 建置不發布。發布失敗不能得到成功的 CI result。`repo` 同時加入自動分支清理保護。
- 初次導入未修改木瓜／X1hub 程式或版號；建置、HTTP／索引及手機加入安裝是不同驗證層級，實體手機 Automatic 流程待確認。操作與檔案契約見 [CI 文件](CI.md#automatic-installation-and-publication)。

## 共通待辦

- HTTP／節流共用：兩來源目前各自維護實作。若日後抽成共用模組，需明確檢查依賴、保留來源專屬縮圖邏輯，並一起驗證兩個來源與 core；目前未進行抽取。

## 新插件／新踩坑紀錄格式

新增來源時在本檔索引與來源 README 加入連結；沒有證據的欄位寫「待確認」，不要套用其他插件的通過狀態。

- 來源、模組路徑、語言／成人標記、套件版本、宿主版本與記錄日期：
- 技術路徑：列表、搜尋、分頁、詳情、集數、影片、標頭／Cookie、時效：
- 現象與觸發條件：
- 原因狀態：已確認／推測／尚未重現；證據為檔案、提交、CI、HTTP 樣本或使用者實測：
- 已採取的處理、結果與相應回歸測試：
- 可供其他來源參考的原則，以及不應直接複製的來源差異：
- 尚待裝置驗證、已知限制與重新調查的條件：
