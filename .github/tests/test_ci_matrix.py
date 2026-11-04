"""Offline regression tests for CI selection and dependency propagation."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "ci-matrix.py"
spec = importlib.util.spec_from_file_location("ci_matrix", SCRIPT)
ci = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ci)


class MatrixTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.previous = Path.cwd()
        os.chdir(self.tmp.name)
        self.addCleanup(os.chdir, self.previous)
        self.environment = patch.dict(os.environ, {
            "IS_PR_CHECK": "true", "CI_CHUNK_SIZE": "18", "CI_MAX_RUNNERS": "4",
        })
        self.environment.start()
        self.addCleanup(self.environment.stop)
        ci.git("init", "-q")
        self.write("src/zh/mugua/build.gradle", "extName = 'Mugua'\n")
        self.write("src/en/other/build.gradle", "extName = 'Other'\n")
        self.commit()
        self.base = ci.git("rev-parse", "HEAD")

    def write(self, path, text="changed\n"):
        target = Path(path)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text, encoding="utf-8")

    def commit(self):
        ci.git("add", "-A")
        ci.git("-c", "user.name=CI Test", "-c", "user.email=ci@example.invalid",
               "commit", "-qm", "fixture")

    def tasks(self, scope="changed", base=None):
        matrix = ci.plan(scope, base or self.base)
        return [task for chunk in matrix["chunk"] for task in chunk["modules"]]

    def test_docs_only_skips_apk_build(self):
        self.write("README.md")
        self.commit()
        self.assertEqual(self.tasks(), [])

    def test_source_change_selects_only_affected_extension(self):
        self.write("src/en/other/src/Other.kt")
        self.commit()
        self.assertEqual(self.tasks(), [":src:en:other:assembleDebug"])

    def test_shared_library_change_follows_transitive_theme_dependencies(self):
        self.write("lib/base/build.gradle", "")
        self.write("lib/bridge/build.gradle", "implementation(project(':lib:base'))")
        self.write("lib-multisrc/theme/build.gradle", "implementation(project(':lib:bridge'))")
        self.write("src/en/other/build.gradle", "ext { themePkg = 'theme' }")
        self.commit()
        base = ci.git("rev-parse", "HEAD")
        self.write("lib/base/src/Base.kt")
        self.commit()
        self.assertEqual(self.tasks(base=base), [":src:en:other:assembleDebug"])

    def test_core_change_builds_all_extensions(self):
        self.write("core/src/Shared.kt")
        self.commit()
        self.assertEqual(set(self.tasks()), {
            ":src:en:other:assembleDebug", ":src:zh:mugua:assembleDebug",
        })

    def test_ci_change_runs_mugua_smoke_build(self):
        self.write(".github/workflows/build_push.yml")
        self.commit()
        self.assertEqual(self.tasks(), [":src:zh:mugua:assembleDebug"])

    def test_deleted_extension_is_not_built(self):
        Path("src/en/other/build.gradle").unlink()
        Path("src/en/other").rmdir()
        self.commit()
        self.assertEqual(self.tasks(), [])

    def test_missing_base_fails_instead_of_silently_skipping_changes(self):
        with self.assertRaises(subprocess.CalledProcessError):
            ci.plan("changed", "missing-ref")

    def test_first_push_builds_all_extensions(self):
        self.assertEqual(len(self.tasks(base="0" * 40)), 2)

    def test_manual_mugua_selection_does_not_require_base(self):
        self.assertEqual(self.tasks(scope="mugua", base="missing-ref"),
                         [":src:zh:mugua:assembleDebug"])

    def test_full_build_limits_parallel_chunks_without_losing_modules(self):
        for i in range(12):
            self.write(f"src/en/fixture{i}/build.gradle", "")
        with patch.dict(os.environ, {"CI_CHUNK_SIZE": "2", "CI_MAX_RUNNERS": "4"}):
            matrix = ci.plan("all", "")
        tasks = [task for chunk in matrix["chunk"] for task in chunk["modules"]]
        self.assertEqual(len(matrix["chunk"]), 4)
        self.assertEqual(len(tasks), 14)
        self.assertEqual(len(set(tasks)), 14)

    def test_cli_emits_github_outputs_and_summary(self):
        output = Path("output.txt").resolve()
        summary = Path("summary.md").resolve()
        environment = dict(os.environ, GITHUB_OUTPUT=str(output),
                           GITHUB_STEP_SUMMARY=str(summary))
        subprocess.run([sys.executable, str(SCRIPT), "--scope", "mugua"],
                       check=True, env=environment, capture_output=True)
        values = dict(line.split("=", 1) for line in output.read_text().splitlines())
        self.assertEqual(values["has_build"], "true")
        self.assertEqual(json.loads(values["matrix"])["chunk"][0]["modules"],
                         [":src:zh:mugua:assembleDebug"])
        self.assertIn("1 extension(s)", summary.read_text())


if __name__ == "__main__":
    unittest.main()
