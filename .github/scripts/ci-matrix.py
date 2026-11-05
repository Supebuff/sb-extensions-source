#!/usr/bin/env python3
"""Build only extensions added by this fork relative to upstream."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import subprocess

HELPER_PATH = Path(__file__).with_name("generate-build-matrices.py")
spec = importlib.util.spec_from_file_location("upstream_matrix", HELPER_PATH)
upstream = importlib.util.module_from_spec(spec)
spec.loader.exec_module(upstream)

CI_PATHS = (
    ".github/workflows/build_push.yml",
    ".github/scripts/ci-matrix.py",
    ".github/scripts/sign-apks.py",
    ".github/signing-certificate.sha256",
    ".github/scripts/generate-build-matrices.py",
    ".github/tests/",
)


def git(*args):
    return subprocess.run(
        ["git", *args], check=True, capture_output=True, text=True,
    ).stdout.strip()


def added_modules(upstream_ref):
    resolved = git("rev-parse", "--verify", "--end-of-options", upstream_ref + "^{commit}")
    existing = set()
    for path in git("ls-tree", "-r", "--name-only", resolved, "--", "src").splitlines():
        parts = path.split("/")
        if len(parts) == 4 and parts[3] in ("build.gradle", "build.gradle.kts"):
            existing.add(f":src:{parts[1]}:{parts[2]}")
    current, _ = upstream.get_all_modules()
    return set(current) - existing


def plan(scope, base, upstream_ref="origin/upstream"):
    added = added_modules(upstream_ref)
    if scope == "added" or not base or set(base) == {"0"}:
        modules = added
    else:
        # Fail on missing history instead of silently checking the wrong commit.
        resolved = git("rev-parse", "--verify", "--end-of-options", base + "^{commit}")
        changed = git("diff", "--name-only", resolved, "HEAD").splitlines()
        os.environ["IS_PR_CHECK"] = "true"
        modules, _ = upstream.get_module_list(resolved)
        if any(path.startswith(CI_PATHS) for path in changed):
            modules = set(modules) | added

    # Core/build changes and upstream merges must not widen this fork's scope.
    modules = sorted(set(modules) & added)

    for module in modules:
        directory = Path(*module.lstrip(":").split(":"))
        if not any((directory / name).is_file() for name in ("build.gradle", "build.gradle.kts")):
            raise ValueError(f"Not a buildable extension: {module}")
    tasks = [module + ":assembleDebug" for module in modules]
    return {"chunk": upstream.balance_chunks(
        tasks,
        target_chunk_size=int(os.getenv("CI_CHUNK_SIZE", "18")),
        max_runners=int(os.getenv("CI_MAX_RUNNERS", "4")),
    )}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scope", choices=["changed", "added"], default="changed")
    parser.add_argument("--base", default="origin/upstream")
    parser.add_argument("--upstream", default="origin/upstream")
    args = parser.parse_args()
    matrix = plan(args.scope, args.base, args.upstream)
    has_build = bool(matrix["chunk"])
    print(json.dumps(matrix, indent=2))
    if output := os.getenv("GITHUB_OUTPUT"):
        with open(output, "a", encoding="utf-8") as handle:
            handle.write(f"matrix={json.dumps(matrix)}\n")
            handle.write(f"has_build={str(has_build).lower()}\n")
    if summary := os.getenv("GITHUB_STEP_SUMMARY"):
        tasks = [task for chunk in matrix["chunk"] for task in chunk["modules"]]
        with open(summary, "a", encoding="utf-8") as handle:
            handle.write("## Fork-added extension build plan\n\n")
            handle.write(f"{len(tasks)} extension(s), {len(matrix['chunk'])} runner(s).\n\n")
            handle.write("\n".join(f"- `{task}`" for task in tasks))
            if not tasks:
                handle.write("No affected fork-added extensions; APK build skipped.\n")


if __name__ == "__main__":
    main()
