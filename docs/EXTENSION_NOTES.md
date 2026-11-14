# 插件技術與踩坑紀錄

## 2026-11-14 Scrapling 網關試點

- 16.4 手機回報：列表偶發顯示「無法連線網關」，重新刷新後正常；手機瀏覽器 `/healthz` 回應正常，服務位址為正確的 HTTP／連接埠。開發端以專用金鑰請求熱門列表回傳 HTTP 200，約 6.3 秒。20:38／20:54 的手機日誌都從新程序啟動開始，未保留失敗例外；20:54 有 6 筆網關圖片磁碟快取成功，其後同批 6 筆記憶體快取成功，不能視為即時重新擷取或手機播放已通過。回應中曾出現 `local.adguard.org`，不足以判定它是原因。原始日誌不加入倉庫。
- 16.5 改善共用用戶端：可辨識的 TCP 連線失敗／重設／過早關閉最多額外嘗試一次，350 毫秒等待可取消，恢復使用獨立的零閒置連線池。只有頁面 GET 與可重播、無持久副作用的票證 POST 適用；HTTP 回應、慢速讀取、TLS／權限／DNS／協程取消不由此流程重試，避免重新執行耗時驗證或擴大限流。最終例外保留原始 cause 供私有診斷，畫面僅呈現固定分類與提示，排除網址、金鑰及 Cookie。測試使用真實 TCP 斷線及合成例外，未把失效連線認定為本次手機問題的已證實根因。
- 使用者已要求實作並選擇目前 Windows 電腦，X1hub 升至 16.4；來源／套件 ID 與既有作品、集數網址保留，直連為預設選項。
- `lib/challenge-gateway` 定義 API v1 薄用戶端，傳輸獨立於宿主 Cloudflare 攔截器；頁面保留原站 URL 供既有 Parser 解析，圖片與影片取得個別簽章票證。服務金鑰不傳給來源或播放器。
- `services/challenge-gateway` 使用 Scrapling 0.5.1 持續會話取得頁面／縮圖；各站隔離 backend。整批圖片序列共用會話、合併重複擷取並有大小／TTL／總量限制。MP4 以 HTTPX 背壓串流轉送，逐跳公開 DNS 檢查及 IP 固定，按目標 URL 篩選瀏覽器 Cookie，保留 Range／206／Content-Range／416／HEAD，取消時關閉上游並釋放串流額度。
- HTTPX 與瀏覽器的 TLS 指紋不同；不能保證 Cookie／UA 即足以通過所有媒體主機驗證。初始 CDN 主機需明確允許，本站公開媒體入口的重新導向僅能指向公開位址。拒絕非本站頁面、非影片縮圖、私有媒體 DNS 及網關重新導向，不轉交 API 金鑰、手機 Cookie 或 Authorization。
- Windows 背景啟動不常駐可見視窗，不讀取個人 Chrome profile、不自動開機啟動或新增防火牆例外。私有設定與金鑰位於 checkout 外；啟停及本機設定工具見 [操作說明](../services/challenge-gateway/README.md)。
- 開發樣本通過列表、整批 24 張圖片及影片起始／位移後兩段 1024 bytes。沒有本站真實挑戰成功解除的證據，未手機實測。原站媒體 URL 到期仍需重新開播，未實作串流中自動刷新。其他來源未接入，不能推定相容。
- 網關離線測試涵蓋金鑰、票證竄改／到期／跨站、DNS、Range／HEAD、跨主機 Cookie 隔離、冷卻、取消與串流清理；與 Kotlin 傳輸、X1hub、core 測試及 APK 建置整合至 CI。歷史方案比較保留於 [評估紀錄](X1HUB_CLOUDFLARE_OPTIONS.md)。

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

#### 外部 Cloudflare 工具評估（2026-11-06）

- 新日誌在 RealImageLoader 有 30 次 Cloudflare 驗證失敗，圖片主機為 www.x1hub.com；本份未見新的 MPV 錯誤或 fatal crash，也未提供套件版號。縮圖仍由 App 直接載入，不能把頁面 solver 視為已涵蓋所有流量。
- 使用者提出 FlareSolverr／Scrapling，並選擇先比較再決定是否使用常駐電腦／NAS。已完成[方案比較與實測紀錄](X1HUB_CLOUDFLARE_OPTIONS.md)：兩者均需獨立瀏覽器執行環境，完整服務方案還要處理圖片及必要的媒體轉送；不能假設服務端 Cookie 可搬到手機。
- 使用者補充未來多個插件都可能遇到 Cloudflare。規劃建議為共用 Kotlin 用戶端／網關 API，以 Scrapling 作可替換的後端，按網站隔離會話、Cookie 與限流；各插件保留專屬解析並逐一接入。首版以 X1hub 驗證真實挑戰、整批縮圖與手機播放，再擴其他來源。這是擴充性建議，並非已證實解驗證成功率較高，也不是已決定部署。
- 執行方式已釐清：服務方案需背景常駐，但不必保留可見 CLI 視窗；電腦關機／睡眠或網關無法連線時，相關服務路徑不可用。現階段只有一次性隔離測試，沒有常駐程序部署或開機啟動設定。
- 工作區隔離安裝 Scrapling 0.5.1，以獨立 Chrome profile 取得 24 筆列表及一張實際影片縮圖（200、image/jpeg）；兩輪均未觀察到真正挑戰回應，因此沒有驗證「自動解開本站 Cloudflare」的能力。FlareSolverr 僅核對文件／程式，未安裝實測；未接入正式插件、未部署服務，版號維持 16.3。

#### 16.3 保留實際網路請求標頭（2026-11-06）

- 手機對照：使用者確認同一影片 53587 在獨立瀏覽器下，Wi-Fi 與行動數據均可播放，AniZen 16.2 仍失敗。這是將調查重點轉向 App 請求／交接流程的證據，不代表已確認所有失敗都是同一原因。
- 可重現的程式問題：AniZen 0.5.220 的 `NetworkHelper` 在 network interceptor 將 User-Agent 固定為驗證成功時的值；OkHttp 5.3.2 的 BridgeInterceptor 在外層 Response 放回網路攔截器之前的 request，實際請求保留在 `networkResponse.request`。舊 `HttpClient` 重新導向沿用原 request，`X1hub.videos` 沿用來源預設標頭，`MediaResolver` 只讀外層 request，可能把舊 User-Agent 帶到下一跳／播放器，也未帶入最終媒體主機已成功使用的 Cookie。
- 修正：`HttpClient.kt` 的 `requestOnWire` 優先讀取 `networkResponse.request`，沒有此資訊時退回一般 request；影片頁、手動重新導向、媒體交接都保留該 User-Agent。原網站明確 Cookie／授權仍在跨主機時移除，播放器只取得最後媒體主機成功請求使用的 Cookie，不複製網路層全部標頭或探測 Range／Host；未更動宿主或新增驗證器。
- 回歸證據：新增 2 個測試分別重現「驗證後 UA 在重新導向變回舊值」及「媒體交接讀錯 UA／遺失最終主機 Cookie」。原碼兩項失敗，修正後 37 項離線測試通過，另 1 項實站測試需手動啟用。以 `X1HUB_LIVE_VIDEO_URL` 指定影片 53587 的實站測試通過四種列表分頁與 720p／480p 最終網址、MP4 開頭、再次 Range 讀取及縮圖；未下載完整影片，也不等同 Android 起播或切網路已通過。
- 核對來源：[AniZen NetworkHelper](https://github.com/salmanbappi/AniZen/blob/v0.5.220/core/common/src/main/kotlin/eu/kanade/tachiyomi/network/NetworkHelper.kt)及 [OkHttp 5.3.2 官方 sources JAR](https://repo.maven.apache.org/maven2/com/squareup/okhttp3/okhttp-jvm/5.3.2/okhttp-jvm-5.3.2-sources.jar)內的 BridgeInterceptor／CacheInterceptor。保留套件／來源 ID 與作品／集數網址，版本遞增至 16.3；仍待同片內建／外部起播、雙向切網路、拖曳／畫質、下載與長時間／到期恢復的手機驗證。WebView 閃退需另查宿主，不宣稱本次修復。

#### 16.2 最終媒體網址解析（2026-11-06）

- 起因：16.1 使用者確認由行動數據切到 Wi-Fi 後，需進 WebView 驗證才能恢復播放。日誌顯示 MPV 直接開啟網站 `/get_file/` 入口失敗；媒體請求未經 AniZen 一般 OkHttp 的 Cloudflare 攔截器。
- 實站證據：少量公開樣本的 `/get_file/` 回傳 302，導向 `qoocdn.com` 的媒體主機；後者回傳 HTTP 206、`video/mp4`，前 1024 bytes 有 MP4 `ftyp`。不記錄完整媒體網址或權杖；此結果沒有驗證切換 IP 後 CDN 是否仍接受同一網址。
- 處理：新增 `MediaResolver.kt`，由 `X1hub.resolveVideo` 在宿主選定畫質時才執行。沿用既有 HTTP、重新導向、冷卻與取消流程，強制新請求並只讀 12-byte MP4 開頭；驗證成功後把最終網址與 Referer 交給播放器。移除探測 Range／If-Range，跨主機不轉送明確 Cookie／授權標頭，保留已完成請求的 User-Agent；不另建驗證器、代理或網路監聽服務。
- 邊界：減少播放與重連時經過網站驗證入口，不代表無條件解決 Cloudflare；列表／縮圖仍走原流程，MPV 重連由宿主控制。最終 CDN 網址可能到期，需重新開播解析；不能把 HTTP 成功宣稱為手機無感切網路已通過。
- 回歸範圍：簽名 query 保留、跨主機標頭隔離、同主機標頭、無副檔名媒體、Range 被忽略時限制讀取、HTML／非 MP4／短回應／錯誤範圍拒絕、HTTP 錯誤不無限重試與等待取消；`LiveSiteTest` 另檢查最終網址可再次讀取前 1024 bytes。
- 版本遞增至 16.2，保留套件／來源 ID 與已儲存作品／集數網址。手機待測：行動數據／Wi-Fi 雙向切換及續播、切換後拖曳／畫質切換、長時間與到期後恢復、下載及外部播放器；16.1 的已知樣本結果保留於下表。
- 驗證結果：2026-11-06，[提交 5db636e](https://github.com/Supebuff/sb-extensions-source/commit/5db636ee234e6a618b39afe4434b1456d5d4568e)的 [CI #22](https://github.com/Supebuff/sb-extensions-source/actions/runs/42129100222)通過來源與 core JVM 測試、Release 建置、固定簽名及套件庫發布，索引為 X1hub 16.2。以 JDK 17／OkHttp JVM 5.3.2 直接執行相同 `LiveSiteTest` 亦通過：四種列表各 24 + 24 筆，720p／480p 都解析到不同的最終網址，並能再次取得 HTTP 206 與前 1024 bytes 的 MP4 ftyp；手機切網路結果仍待確認。

- 後續手機結果（2026-11-06）：使用者確認 16.2 已更新且重新開播，影片 53587 在未切網路前即失敗；日誌 07:33:48 有 `contradictionary STSC and STCO`／`error reading header`，07:34:09 有 `mbedtls_ssl_read returned -0x0` 與再次讀取標頭失敗。外部播放器持續轉圈，WebView 網站播放器有閃退回報；日誌保留的 00:07:29 SIGTRAP 是前份已有事件，不能當作本次新閃退證據。沒有新的 CloudflareBypassException，也不代表驗證問題已排除。
- 同片 HTTP 對照：720p／480p 的 CDN 均回傳 206，最初 64 KiB 與一般 GET 的前段相同；兩畫質約 4.7 MB 的 moov 索引均完整讀取並檢查，兩軌的 stsc 最後 first_chunk 均未超過 stco chunk_count，未重現 FFmpeg 報出的條件。第二輪共讀約 9.7 MB 標頭／索引，未下載完整影片；這只代表開發端當時的回應，不證明手機端或整部影片正常。
- 網站流程核對：KVS 5.5.1 播放器在開播入口加上新的 `rnd`，插件目前未加入；但本次有／無 rnd 的入口均為 302、`CF-Cache-Status: DYNAMIC`、`Cache-Control: no-store, no-cache, must-revalidate`，沒有證據顯示缺少 rnd 造成這次失敗，因此未據此修改或發布新 APK。優先取得同手機、同網路的獨立 Chrome／Samsung Internet 播放結果；WebView 閃退與媒體讀取失敗可能需要分開追查，16.2 尚不能列為無感恢復已通過。

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
