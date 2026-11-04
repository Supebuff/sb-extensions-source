# Development CI

The `CI` workflow runs for pushes to `dev`, pull requests targeting `dev`, and manual runs from the Actions tab. The `upstream` branch remains a mirror of the upstream repository.

## Automatic checks

- Validate the workflow with actionlint and run the offline CI selector regression tests.
- Build Debug APKs only for extensions added by this fork, identified by comparing module build files with `origin/upstream`. Currently this is `zh/mugua`; newly added modules are discovered automatically.
- Within that scope, select affected extensions and transitive dependents. Core, build configuration, and CI changes rebuild fork-added extensions without building the upstream catalog. Documentation-only or upstream-extension-only changes skip APK builds.
- Run the existing core JVM unit tests in the first build chunk.
- Require a successful `CI result` check; failed or cancelled builds cannot produce a passing result.

Changes are compared against the push's previous commit, or the pull request's base commit. Missing history or a missing upstream reference fails explicitly. A first push with no previous commit builds only fork-added extensions. Libraries required by those extensions are still compiled as dependencies.

## Manual runs and APKs

Open **Actions → CI → Run workflow**, select `dev`, then choose:

| Scope | Builds |
| --- | --- |
| `added` (default) | All fork-added extensions and core tests |
| `changed` | Only affected fork-added extensions, compared with `origin/upstream` |

Download `debug-apks-<run>-<chunk>` from the run's **Artifacts** section. APKs are retained for 14 days. Failure reports are retained for 7 days.

No repository secrets or release signing key are required. Debug APKs use each runner's generated debug key, so they are for testing and may require uninstalling a differently signed installed extension. Save any needed app configuration before doing so.

CI performs builds and validation only. It does not publish releases, update an extension repository, change version codes, or commit formatting changes. Production signing and repository publishing are separate tasks.

## Local checks

Requires Python 3.12+ and Git:

```sh
python -m unittest discover -s .github/tests -v
git fetch origin upstream
python .github/scripts/ci-matrix.py --scope added
python .github/scripts/ci-matrix.py --scope changed --base origin/upstream
```

For Android builds, install JDK 17 and Android SDK 34, then run:

```sh
bash ./gradlew :src:zh:mugua:assembleDebug :core:testDebugUnitTest
```

For a fork where Actions has never been enabled, open the Actions tab and enable workflows, then run CI manually.
