<div align="center">

# Supebuff Extensions

A collection of extension catalogues for compatible Android anime players.

| Install Repo | Build Status | Upstream Discord |
|:------------:|:------------:|:-------:|
| [![Install](https://img.shields.io/badge/Click%20here%20to%20install%20repo-gray?style=flat&labelColor=red)](https://intradeus.github.io/http-protocol-redirector/?r=anizen%3A%2F%2Fadd-repo%3Furl%3Dhttps%3A%2F%2Fraw.githubusercontent.com%2FSupebuff%2Fsb-extensions-source%2Frepo%2Findex.min.json) | ![CI](https://github.com/Supebuff/sb-extensions-source/actions/workflows/build_push.yml/badge.svg?branch=dev) | [![Discord](https://img.shields.io/discord/1377136877491982366.svg?label=&labelColor=6A7EC2&color=7389D8&logo=discord&logoColor=FFFFFF)](https://discord.gg/85MZhUX688) |

</div>

---

This repository contains the source code for various anime extensions.

本 fork 的套件庫為 **Supebuff Extensions**，提供木瓜、X1hub 與後續新增的自有套件。`dev` 維護原始碼，`upstream` 同步上游，`repo` 發布通過固定簽名驗證的 APK、圖示與索引。

最新發布、Scrapling 網關與手機驗證進度見[專案現況](docs/CURRENT_STATUS.md)。

## 🚀 How to Add the Repository

### Option 1: Automatic
在已安裝 **AniZen 0.5.220** 的 Android 手機點擊上方 **Click here to install repo**，允許瀏覽器開啟 AniZen，再確認加入 **Supebuff Extensions**。回到套件列表重新整理，即可安裝木瓜／X1hub；之後可在 App 內檢查更新。

Automatic 會帶入套件庫網址，仍需在 App 確認並選擇要安裝的套件。若瀏覽器未開啟 App，請使用下方 Manual Link。從本 fork 固定簽名 APK 更新可保留既有安裝；較早使用其他簽名的 APK 仍有一次性簽名轉換限制，見 [CI 說明](docs/CI.md#one-time-fixed-signing-setup)。

### Option 2: Manual Link
Copy and paste the repository URL into your app's extension settings:

```text
https://raw.githubusercontent.com/Supebuff/sb-extensions-source/repo/index.min.json
```

### Option 3: Direct APK Download
需要直接安裝 APK 時，可從本倉庫 [`repo` 分支的 apk 目錄](https://github.com/Supebuff/sb-extensions-source/tree/repo/apk)下載。該分支只發布驗證成功的固定簽名 Release APK，無須下載 Actions 壓縮檔；套件版本遞增且 CI 發布成功後，索引會自動更新。

---

## 🛠️ Requests and Issues

To request a new source, suggest features, or report bug fixes:
* Please [create an issue](https://github.com/salmanbappi/sb-extensions-source/issues/new/choose).
* Work is done entirely on a voluntary basis. Creating an issue does not guarantee immediate implementation or support.
* Some sources might be too complex or difficult to maintain due to changes in source websites.

---

## 🤝 Contributing

Contributions are highly appreciated!
* Check out the [issue backlog](https://github.com/salmanbappi/sb-extensions-source/issues) for pending requests and bug reports.
* Read the [CONTRIBUTING.md](./CONTRIBUTING.md) guide to get started with setup and development.
* Please review and respect our [CODE_OF_CONDUCT.md](./CODE_OF_CONDUCT.md).

---

## ⚖️ License

```text
Copyright 2015 Javier Tomás

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

---

## ⚠️ Disclaimer

* This repository does not host any video content. It merely contains scripts to crawl publicly available websites.
* This project is not affiliated with the content providers or websites indexed.
* All credits for the base extension structure go to the original contributors of the upstream ecosystem.
