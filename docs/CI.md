# Development CI

The `CI` workflow runs for pushes to `dev`, pull requests targeting `dev`, and manual runs from the Actions tab. The `upstream` branch remains a mirror of the upstream repository.

For source-specific implementation differences, troubleshooting evidence, and device verification status, see [Extension technical notes](EXTENSION_NOTES.md). CI behavior and signing operations remain documented here.

## Automatic checks

- Validate the workflow with actionlint and run the offline CI selector regression tests.
- Build APKs only for extensions added by this fork, identified by comparing module build files with `origin/upstream`. Currently these are `zh/mugua` and `all/x1hub`; newly added modules are discovered automatically.
- Within that scope, select affected extensions and transitive dependents. Core, build configuration, and CI changes rebuild fork-added extensions without building the upstream catalog. Documentation-only or upstream-extension-only changes skip APK builds.
- Run each selected extension's JVM unit tests and the existing core JVM unit tests in the first build chunk.
- Build unsigned Release APKs on trusted `dev` pushes/manual runs. Sign them in a separate job using a fixed key, verify their signatures and the pinned certificate, then upload verified APKs.
- PRs and manual runs on other branches build Debug APKs for testing and never receive signing credentials.
- Require a successful `CI result` check, including signing when required; failed or cancelled builds cannot produce a passing result.

Changes are compared against the push's previous commit, or the pull request's base commit. Missing history or a missing upstream reference fails explicitly. A first push with no previous commit builds only fork-added extensions. Libraries required by those extensions are still compiled as dependencies.

## Manual runs and APKs

Open **Actions → CI → Run workflow**, select `dev`, then choose:

| Scope | Builds |
| --- | --- |
| `added` (default) | All fork-added extensions and core tests |
| `changed` | Only affected fork-added extensions, compared with `origin/upstream` |

Download **`signed-apks-<run>`** from the run's **Artifacts** section for installation. It contains verified Release APKs, `SHA256SUMS`, and the public certificate SHA-256 fingerprint, and is retained for 30 days.

`unsigned-apks-<run>-<chunk>` is an intermediate artifact and cannot be installed as an update. `test-only-apks-<run>-<chunk>` uses temporary Debug signatures and is for PR/non-dev testing only. Both are retained for 14 days. Failure reports are retained for 7 days.

CI builds, tests, and signs artifacts. It does not publish GitHub releases, update an extension repository, change version codes, or commit formatting changes.

## One-time fixed signing setup

Create the repository Actions secret **`EXTENSION_SIGNING`** under **Settings → Secrets and variables → Actions**. Its value is one JSON object:

```json
{
  "keystore_base64": "BASE64_ENCODED_PRIVATE_KEYSTORE",
  "store_password": "KEYSTORE_PASSWORD",
  "key_alias": "KEY_ALIAS",
  "key_password": "KEY_PASSWORD"
}
```

Base64 is an encoding, not encryption. This JSON contains the private key and passwords: never commit it, paste it into issues, or upload it as an artifact. Keep a separate encrypted/offline backup under your control. The project's configured public certificate fingerprint is in `.github/signing-certificate.sha256`. Reuse the same key for all routine updates; do not regenerate a key when rerunning CI or changing versions.

The signing job requires this Secret. Missing/invalid credentials, a failed APK signature verification, an unexpected signer, or a missing APK fails the job without uploading installable APKs. There is no temporary-key fallback. The private keystore is created in a temporary directory used only by the signer and removed afterwards. It is never available to Gradle, included in caches, or copied into artifacts. Passwords are passed through child-process environment variables, not command-line arguments.

The initial transition from earlier randomly signed Debug APKs requires reinstalling the extension once because those APKs have different signer identities. Back up AniZen first and remove only the affected extension, keeping the AniZen application. Subsequent APKs signed with this fixed key and a higher version code can update normally. APK fingerprints alone cannot recover a missing private key.

## Local checks

Requires Python 3.12+ and Git:

```sh
python -m unittest discover -s .github/tests -v
git fetch origin upstream
python .github/scripts/ci-matrix.py --scope added
python .github/scripts/ci-matrix.py --scope changed --base origin/upstream
```

For local test builds, install JDK 17 and Android SDK 34, then run:

```sh
bash ./gradlew :src:zh:mugua:assembleDebug :core:testDebugUnitTest
```

To build an unsigned release without exposing a key to Gradle:

```sh
bash ./gradlew :src:zh:mugua:assembleRelease -PunsignedRelease=true
```

The signing script accepts the `EXTENSION_SIGNING` environment variable, the input directory, an initially nonexistent output directory, and the official Android SDK `apksigner` executable. It verifies every APK before publishing any output:

```sh
python .github/scripts/sign-apks.py --input unsigned-apks --output signed-apks --apksigner /path/to/apksigner
```

For a fork where Actions has never been enabled, open the Actions tab and enable workflows, then run CI manually.
