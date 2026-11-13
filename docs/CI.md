# Development CI

The `CI` workflow runs for pushes to `dev`, pull requests targeting `dev`, and manual runs from the Actions tab. The `upstream` branch remains a mirror of the upstream repository.

For source-specific implementation differences, troubleshooting evidence, and device verification status, see [Extension technical notes](EXTENSION_NOTES.md). CI behavior and signing operations remain documented here.

## Automatic checks

- Validate the build and merged-branch cleanup workflows with actionlint, and run offline CI selection, signing, and branch cleanup regression tests.
- Run the challenge gateway's offline Python API/transport tests in `services/challenge-gateway`; these require only its `requirements-test.txt`, not Chrome or a live website. A failure blocks CI.
- Build APKs only for extensions added by this fork, identified by comparing module build files with `origin/upstream`. Currently these are `zh/mugua` and `all/x1hub`; newly added modules are discovered automatically.
- Within that scope, select affected extensions and transitive dependents. Core, build configuration, and CI changes rebuild fork-added extensions without building the upstream catalog. The normal `changed` scope excludes documentation in the locations below; documentation-only and upstream-extension-only changes skip APK builds.
- Run each selected extension's JVM unit tests, plus core and `lib:challenge-gateway` JVM tests in the first build chunk.
- Build unsigned Release APKs on trusted `dev` pushes/manual runs. Sign them in a separate job using a fixed key, verify their signatures and the pinned certificate, then upload verified APKs.
- PRs and manual runs on other branches build Debug APKs for testing and never receive signing credentials.
- Publish verified APKs, launcher icons and the AniZen-compatible catalogue to the dedicated `repo` branch after trusted `dev` signing succeeds.
- Require a successful `CI result` check, including signing and publication when required; failed or cancelled builds cannot produce a passing result.

Changes are compared against the push's previous commit, or the pull request's base commit. Missing history or a missing upstream reference fails explicitly. A first push with no previous commit builds only fork-added extensions. Libraries required by those extensions are still compiled as dependencies.

## Documentation-only changes

The fork's `ci-matrix.py` filters documentation paths before module/dependency selection in `changed` scope. It recognizes:

- Repository-root `.md`, `.markdown`, `.rst`, and `.adoc` files, plus the repository `docs/` tree.
- Those text formats directly inside extension roots (`src/<lang>/<source>/`), library roots (`lib/<name>/`, `lib-multisrc/<name>/`), `core/`, `buildSrc/`, `utils/`, `gradle/`, and `gradle/build-logic/`.
- A `docs/` directory directly inside those roots: the same text formats, `.txt`, `.png`, `.jpg`, `.jpeg`, `.gif`, `.svg`, `.webp`, and `.pdf`. Other file types remain eligible for build selection.

Matching is by location as well as suffix. Packaged files under `src/`, `res/`, or `assets/`, and test fixtures, are not excluded just because they use a documentation extension. Build files and manifests remain build inputs. Mixed changes select the modules affected by the remaining code/resource/build paths. Both sides of renames are considered, so renaming code into a README still selects its module.

For documentation-only pushes/PRs in `changed` scope, workflow lint and CI regression tests still run. An empty matrix sets `has_build=false`; APK tests/builds, release signing and publication are skipped, and `CI result` requires successful validation. Manual `added` scope and first-push fallback still build all fork-added extensions. The standalone upstream matrix helper keeps its default behavior; the fork passes its filtered file list explicitly.

## Merged branch cleanup

[Delete Merged Branch](../.github/workflows/delete_merged_branch.yml) runs only for merged pull requests whose head branch belongs to this repository. The deletion step always skips `main`, `master`, `dev`, `upstream`, `repo`, and empty/unset branch names. Other names must pass `git check-ref-format`; deletion targets the exact `refs/heads/<name>` ref, and a failed deletion fails the job.

Regression tests in [.github/tests/test_branch_cleanup.py](../.github/tests/test_branch_cleanup.py) execute the workflow's shell block with intercepted Git calls in an isolated directory. They verify protected names, normal feature branches, quoting, invalid refs, and failure propagation without deleting a remote branch. The main CI lints both the build and cleanup workflows and discovers these tests with the existing unittest command.

## Manual runs and APKs

Open **Actions → CI → Run workflow**, select `dev`, then choose:

| Scope | Builds |
| --- | --- |
| `added` (default) | All fork-added extensions and core tests |
| `changed` | Only affected fork-added extensions, compared with `origin/upstream` |

Download **`signed-apks-<run>`** from the run's **Artifacts** section for installation. It contains verified Release APKs, `SHA256SUMS`, and the public certificate SHA-256 fingerprint, and is retained for 30 days.

`unsigned-apks-<run>-<chunk>` is an intermediate artifact and cannot be installed as an update. `test-only-apks-<run>-<chunk>` uses temporary Debug signatures and is for PR/non-dev testing only. Both are retained for 14 days. Failure reports are retained for 7 days.

## Automatic installation and publication

The README Automatic button opens `anizen://add-repo?url=...` through the existing HTTPS protocol redirector. Both Automatic and Manual Link add:

```text
https://raw.githubusercontent.com/Supebuff/sb-extensions-source/repo/index.min.json
```

On AniZen 0.5.220 the array index resolves `repo.json` metadata, including the pinned signing fingerprint. This fork publishes the compatible legacy JSON catalogue (`index.min.json`, `index.json`, `repo.json`), PNG icons and signed Release APKs. The optional `sources` field is empty: runtime IDs are not guessed or changed. Catalogue language, version, content warning, package and icon come from the APK. This does not change the installed source's ID or functionality.

After signing, a separate trusted `dev` job downloads only `signed-apks-<run>`, verifies all incoming and retained APKs against the pinned certificate, and merges affected packages into `repo`. Fork-added modules are discovered against `origin/upstream`; unrelated upstream APKs are rejected. Other published packages remain available. A lower version fails; an equal version keeps the existing APK immutable. Increment `extVersionCode` to publish code changes. Removed modules are pruned on the next successful nonempty publication.

The publisher prepares the complete snapshot before updating files and pushes a normal fast-forward commit. A push failure fails CI; no forced history replacement is used. Only the publication job has `contents: write`; build/sign jobs and PR checks retain read-only repository access. Publication uses the same repository's `GITHUB_TOKEN` and receives no private signing Secret. The protected `repo` branch is initialized once with a README before the first run, and thereafter is maintained by CI. No additional PAT or repository is needed.

`SHA256SUMS` and `SIGNING_CERTIFICATE_SHA256.txt` are public verification data. APK installation remains subject to Android confirmation and AniZen's trust/settings. Existing APKs signed with the same fixed key can update when a higher version is available. Browser deep-link opening and in-app installation require phone verification; HTTP/index/build checks alone do not prove that flow.

Documentation-only pushes skip build/sign/publication. PRs and non-dev runs never publish. A required publication failure fails `CI result`. CI does not create GitHub releases, change module versions or commit formatting changes. Public files in `repo` are independent of expiring Actions artifacts.

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

Scrapling service setup, Windows background operation, connection settings, and the explicitly enabled live probe are documented in [Challenge gateway](../services/challenge-gateway/README.md). The service and its API key are not embedded in APKs or published to `repo`; the signed X1hub APK contains only the optional gateway client.

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
