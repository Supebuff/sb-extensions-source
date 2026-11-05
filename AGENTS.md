# 專案工作指引

## 目標與相容範圍

- 本倉庫是 `Supebuff/sb-extensions-source`，用來維護多個自有 Android 動畫／影片來源套件，並保留上游同步能力。
- 目前相容基準為 **AniZen 0.5.220**；使用者提供新的目標版本時，以新要求為準並核對來源 API。預設在套件端處理相容性。
- 以繁體中文與使用者溝通。開始工作先讀取相關檔案與 Git 狀態，以目前程式碼為準，不假設新對話具備舊對話全部細節。

## 分支與變更範圍

- `dev`：自有套件及 CI 修改的主要分支。
- `upstream`：同步 `salmanbappi/sb-extensions-source` 的 `master`，保持上游內容；自有改動放在 `dev`，上游更新合併至 `dev`。
- `repo`：專用發布分支，放固定簽名 Release APK、圖示與 AniZen 相容索引，由成功 CI 更新。
- 保護 `dev`、`upstream` 與 `repo`；保留使用者尚未提交的修改，避免覆寫工作區、強制推送或改寫既有歷史。
- 自動分支清理保留 `main`、`master`、`dev`、`upstream`、`repo`，空值也略過；其他名稱先驗證，再精確刪除對應的 `refs/heads/`。保護規則位於 `.github/workflows/delete_merged_branch.yml`，回歸測試位於 `.github/tests/test_branch_cleanup.py`。
- 保留共用函式庫、開發範本及發布工具，供後續新增套件使用。大範圍精簡需有明確需求與依賴檢查。

## 先讀哪些檔案

- [docs/CI.md](docs/CI.md)：建置範圍、正式簽名、產物與操作方式。
- [docs/EXTENSION_NOTES.md](docs/EXTENSION_NOTES.md)：各來源的技術差異、共通流程、踩坑證據及裝置驗證狀態。
- [development_guide.md](development_guide.md)、[CONTRIBUTING.md](CONTRIBUTING.md)：專案結構與開發慣例；其中舊路徑、數量或上游連結須以實際檔案核對。
- [common.gradle](common.gradle)、[settings.gradle.kts](settings.gradle.kts)、[gradle/libs.versions.toml](gradle/libs.versions.toml)：模組、來源集、編譯依賴與版本。
- `src/<語言>/<來源>/`：各套件；依來源需求選用 `core/`、`lib/`、`lib-multisrc/` 的既有功能。
- [木瓜說明](src/zh/mugua/README.md)與其實作可作為自有套件範例；網站專屬的解析、縮圖與節流行為依新來源需求選用。

## 新增與更新套件

- 新來源放在 `src/<lang>/<source>/`，沿用附近套件的 Gradle、namespace、manifest 與資源慣例，填妥名稱、類別、語言及內容分級。
- 先確認網站入口、分類／搜尋／詳情／集數／影片解析流程，以及必要的標頭、cookies、分頁與連結到期行為。
- 核對實際宿主 API 與編譯依賴；維持第三方套件的宿主相容性，適當使用 `compileOnly`。
- 更新既有套件時保留套件 ID、來源 ID 與已儲存網址的相容性；交付可安裝更新時遞增 `extVersionCode`。
- 參考木瓜的來源設定呈現方式，提供使用者能理解的功能與選項說明；標明由 App 控制的播放／下載行為。
- 模組有 JVM 測試時，參考木瓜把測試放在模組的 `test/` 並設定 test sourceSets。`common.gradle` 將整個 `src/` 當成正式來源，不要將測試放入 `src/test/`。
- 沿用協程取消語意；避免吞掉取消例外、無限重試，或把解析失敗偽裝成正常空列表。
- 依主機採用適當的請求間隔、快取與並行限制；遇到 429／`Retry-After` 時遵守冷卻。以少量樣本驗證網站功能。

## CI 與固定簽名

- 目前 CI 以 `origin/upstream` 辨識本 fork 新增的模組，新增來源可自動納入。一般推送只選受影響的新增模組及所需依賴，不建置全部上游套件。
- 一般 `changed` 範圍依 `docs/CI.md` 的位置規則排除純說明文件，包含模組 README；不可因副檔名而全域忽略程式資源或測試資料。手動 `added` 範圍仍建置所有本 fork 新增模組。
- 修改上游既有模組時，另外確認並執行該模組的驗證，避免把 CI 未選取的模組說成已通過。
- 主要流程位於 `.github/workflows/build_push.yml`；選取器與簽名程式位於 `.github/scripts/`，其測試位於 `.github/tests/`。
- `dev` 的正式建置先產生 unsigned Release APK，再由獨立工作簽名；PR 測試不接觸正式簽名 Secret。
- 固定金鑰由 GitHub Actions Secret **`EXTENSION_SIGNING`** 提供，公開憑證指紋記錄在 `.github/signing-certificate.sha256`。日常更新與新增套件沿用同一金鑰。
- 私鑰、密碼與 Secret 全文不得寫入程式庫、日誌、對話、快取或公開產物；倉庫外的金鑰備份也不能當作普通暫存清除。
- 簽名必須通過 APK 驗證，且所有簽名憑證符合固定指紋。格式解析失敗時查明工具差異並補驗證，不更換金鑰或略過指紋檢查。
- 提供使用者安裝的是 **`signed-apks-*`**；`unsigned-apks-*` 是中間產物，`test-only-apks-*` 僅供測試。
- 成功的 `dev` 簽名建置會再驗證並發布至 `repo`；README Automatic／Manual Link 都加入本 fork 的套件庫。使用同倉庫 `GITHUB_TOKEN`，只在發布 job 給 `contents: write`，不新增私鑰 Secret。
- `.github/scripts/publish-repo.py` 只接受 fork 新增模組，合併部分更新時保留其他套件；同版號保留原檔、拒絕降版。新程式行為必須遞增版本。純文件 CI 略過建置、簽名與發布。

## 驗證與交付

- 執行與變更相關的既有檢查。CI／簽名程式修改時執行：

  ```sh
  python -m unittest discover -s .github/tests -v
  ```

- 套件變更執行受影響模組的 JVM 測試及 APK 建置；共用程式變更亦驗證相關依賴與 core 測試。環境使用 JDK 17、Android SDK 34、Python 3.12+，依平台選用 `./gradlew` 或 `gradlew.bat`。
- 需要無私鑰的 Release 建置時，使用 `:src:<lang>:<source>:assembleRelease -PunsignedRelease=true`；正式簽名依 CI 文件處理。
- 純文件變更檢查內容、引用與 `git diff --check` 即可，不另增測試；CI 可依範圍略過 APK 建置。
- 本機缺少 SDK 或其他環境時，明確說明限制，透過 CI 完成可執行的驗證。
- 分別回報解析測試、網站 HTTP／播放清單檢查、APK 建置及手機實測。HTTP 200 或成功取得 HLS 連結不等於手機播放已驗證。
- 延續既有授權的提交／推送流程；回報實際變更、測試結果、提交與 CI 連結，以及仍待裝置驗證的項目。

## 跨對話延續

- 將來源專屬的行為與限制寫入該套件 README；技術決策、踩坑原因與手機回報摘要同步至 `docs/EXTENSION_NOTES.md`，註明日期、版本、證據及仍待確認的部分。CI 操作寫入 `docs/CI.md`；此檔只保留長期適用的專案規則。
- 文件記錄重要決策及可查證的檔案／提交／CI 參考，避免堆積完整對話、重複日誌或過期狀態。
