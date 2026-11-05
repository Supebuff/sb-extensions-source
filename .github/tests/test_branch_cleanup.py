"""Execute the cleanup workflow with intercepted Git calls, without a remote repository."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import textwrap
import unittest

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github/workflows/delete_merged_branch.yml"


def find_bash():
    if os.name == "nt":
        git = shutil.which("git")
        if git:
            candidate = Path(git).resolve().parents[1] / "bin/bash.exe"
            if candidate.is_file():
                return str(candidate)
    return shutil.which("bash")


BASH = find_bash()
GIT_STUB = r"""
git() {
    printf '%s\0' "$#" "$@" >> "$TEST_GIT_CALLS"
    case "$1" in
        check-ref-format) command git "$@" ;;
        push) return "${TEST_PUSH_EXIT:-0}" ;;
        *) return 99 ;;
    esac
}
"""


class BranchCleanupTests(unittest.TestCase):
    def run_cleanup(self, branch, push_exit=0):
        if BASH is None:
            self.fail("Bash is required to test the branch cleanup workflow")
        content = WORKFLOW.read_text(encoding="utf-8")
        script = textwrap.dedent(content.split("        run: |", 1)[1])
        with tempfile.TemporaryDirectory(prefix="branch-cleanup-") as directory:
            calls_file = Path(directory) / "git-calls"
            env = os.environ.copy()
            # Even an accidental bypass of the stub must not find the real checkout or origin.
            for key in ("GIT_DIR", "GIT_WORK_TREE", "GIT_COMMON_DIR"):
                env.pop(key, None)
            env.update({
                "TEST_GIT_CALLS": calls_file.as_posix(),
                "TEST_PUSH_EXIT": str(push_exit),
                "GITHUB_TOKEN": "test-only-no-network",
            })
            if branch is None:
                env.pop("BRANCH_NAME", None)
            else:
                env["BRANCH_NAME"] = branch
            result = subprocess.run(
                [BASH, "--noprofile", "--norc", "-e", "-o", "pipefail", "-c",
                 GIT_STUB + "\n" + script],
                cwd=directory, env=env, text=True, capture_output=True, timeout=10,
            )
            tokens = calls_file.read_bytes().split(b"\0")[:-1] if calls_file.exists() else []
            calls = []
            index = 0
            while index < len(tokens):
                count = int(tokens[index])
                calls.append([part.decode("utf-8") for part in tokens[index + 1:index + 1 + count]])
                index += count + 1
            return result, calls

    def test_protected_and_empty_branches_never_call_git(self):
        for branch in ("main", "master", "dev", "upstream", "", None):
            with self.subTest(branch=branch):
                result, calls = self.run_cleanup(branch)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(calls, [])

    def test_feature_branches_delete_only_the_exact_head_ref(self):
        for branch in (
            "feature/x1hub", "fix/dev-headers", "upstream-sync", "dev-fix",
            "refs/heads/dev", "refs/tags/release", "feature/$(false)",
            "feature/cleanup;false", "feature/it's-ok",
        ):
            with self.subTest(branch=branch):
                result, calls = self.run_cleanup(branch)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(calls, [
                    ["check-ref-format", f"refs/heads/{branch}"],
                    ["push", "origin", "--delete", "--", f"refs/heads/{branch}"],
                ])

    def test_invalid_branch_names_fail_without_push(self):
        for branch in ("../dev", "dev..cleanup", "feature with spaces",
                       "feature\nother", "topic:", "feature/*", "feature/", "topic.lock"):
            with self.subTest(branch=branch):
                result, calls = self.run_cleanup(branch)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(calls, [["check-ref-format", f"refs/heads/{branch}"]])

    def test_push_failure_is_reported(self):
        result, calls = self.run_cleanup("feature/test", push_exit=17)
        self.assertEqual(result.returncode, 17)
        self.assertEqual(calls[-1], [
            "push", "origin", "--delete", "--", "refs/heads/feature/test",
        ])

    def test_workflow_keeps_event_guards_and_is_linted_by_ci(self):
        content = WORKFLOW.read_text(encoding="utf-8")
        condition = next(line for line in content.splitlines() if line.strip().startswith("if:"))
        self.assertIn("types: [closed]", content)
        self.assertIn("github.event.pull_request.merged == true", condition)
        self.assertIn("github.repository == github.event.pull_request.head.repo.full_name", condition)
        self.assertIn("BRANCH_NAME: ${{ github.event.pull_request.head.ref }}", content)
        self.assertIn("shell: bash", content)
        script = content.split("        run: |", 1)[1]
        self.assertNotIn("${{", script)
        ci = (ROOT / ".github/workflows/build_push.yml").read_text(encoding="utf-8")
        lint_command = next(line for line in ci.splitlines() if '"$RUNNER_TEMP/actionlint" -color' in line)
        self.assertIn(".github/workflows/delete_merged_branch.yml", lint_command)


if __name__ == "__main__":
    unittest.main()
