"""Catalogue merging, version immutability, scope and signer regression checks."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from zipfile import ZipFile

SCRIPT = Path(__file__).resolve().parents[1] / 'scripts/publish-repo.py'
spec = importlib.util.spec_from_file_location('publish_repo', SCRIPT)
repo = importlib.util.module_from_spec(spec)
spec.loader.exec_module(repo)
PREFIX = repo.PACKAGE_PREFIX
PIN = 'a' * 64
BASE = 'https://raw.githubusercontent.com/example/extensions/repo'


class PublicationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.input = self.root / 'incoming'
        self.output = self.root / 'published'
        self.input.mkdir()
        self.output.mkdir()
        self.allowed = {PREFIX + 'zh.mugua', PREFIX + 'all.x1hub'}
        self.fingerprint = PIN
        self.debuggable = False
        self.package_override = None

    def apk(self, name='mugua', code=6, extra=b''):
        lang = 'zh' if name == 'mugua' else 'all'
        path = self.input / f'aniyomi-{lang}.{name}-v16.{code}-release.apk'
        with ZipFile(path, 'w') as archive:
            archive.writestr('res/icon.png', b'\x89PNG\r\n\x1a\n' + extra)
        return path

    def runner(self, command):
        if 'verify' in command:
            return 'Number of signers: 1\nSigner #1 certificate SHA-256 digest: ' + self.fingerprint + '\n'
        path = Path(command[-1])
        lang, source = repo.APK_PATTERN.fullmatch(path.name).groups()
        version = path.name.split('-v')[1].removesuffix('-release.apk')
        code = version.split('.')[-1]
        package = self.package_override or PREFIX + lang + '.' + source
        return (f"package: name='{package}' versionCode='{code}' versionName='{version}'\n"
                f"application-label:'Aniyomi: {source}'\napplication-icon-320:'res/icon.png'\n"
                "uses-feature: name='tachiyomi.animeextension'\n"
                "meta-data: name='tachiyomi.animeextension.nsfw' value='1'\n"
                + ('application-debuggable\n' if self.debuggable else ''))

    def publish(self):
        return repo.publish(self.input, self.output, self.allowed, PIN, BASE,
                            Path('aapt'), Path('apksigner'), self.runner)

    def clear_input(self):
        for path in self.input.iterdir():
            path.unlink()

    def snapshot(self):
        return {p.relative_to(self.output).as_posix(): p.read_bytes()
                for p in self.output.rglob('*') if p.is_file()}

    def test_initial_index_has_host_fields_and_correct_fingerprint(self):
        self.apk()
        self.assertEqual(self.publish(), 1)
        index = json.loads((self.output / 'index.min.json').read_text())
        self.assertEqual(index[0]['lang'], 'zh')
        self.assertEqual(index[0]['code'], 6)
        self.assertEqual(index[0]['nsfw'], 1)
        self.assertEqual(index[0]['sources'], [])
        meta = json.loads((self.output / 'repo.json').read_text())
        self.assertEqual(meta['meta']['signingKeyFingerprint'], PIN)
        self.assertNotIn('index_v2', meta)
        self.assertIn('anizen%3A%2F%2Fadd-repo', (self.output / 'index.html').read_text(encoding='utf-8'))
        self.assertEqual(set(self.snapshot()), {
            'index.min.json', 'index.json', 'repo.json', 'index.html', 'SHA256SUMS',
            'SIGNING_CERTIFICATE_SHA256.txt', 'apk/aniyomi-zh.mugua-v16.6-release.apk',
            'icon/' + PREFIX + 'zh.mugua.png',
        })

    def test_partial_build_preserves_unaffected_extension(self):
        self.apk('x1hub', 1)
        self.publish()
        self.clear_input()
        self.apk()
        self.assertEqual(self.publish(), 2)
        self.assertTrue((self.output / 'apk/aniyomi-all.x1hub-v16.1-release.apk').is_file())

    def test_update_replaces_old_apk_but_does_not_mutate_same_version(self):
        original = self.apk().read_bytes()
        self.publish()
        self.clear_input()
        self.apk(extra=b'different build')
        self.publish()
        self.assertEqual((self.output / 'apk/aniyomi-zh.mugua-v16.6-release.apk').read_bytes(), original)
        self.clear_input()
        self.apk(code=7)
        self.publish()
        self.assertFalse((self.output / 'apk/aniyomi-zh.mugua-v16.6-release.apk').exists())
        self.assertEqual(json.loads((self.output / 'index.min.json').read_text())[0]['code'], 7)

    def test_bad_signer_or_downgrade_leaves_catalogue_unchanged(self):
        self.apk()
        self.publish()
        previous = self.snapshot()
        self.clear_input()
        self.apk(code=5)
        with self.assertRaises(repo.PublishError):
            self.publish()
        self.assertEqual(self.snapshot(), previous)
        self.fingerprint = 'b' * 64
        with self.assertRaises(repo.signer.SigningError):
            self.publish()
        self.assertEqual(self.snapshot(), previous)

    def test_debuggable_or_wrong_package_or_upstream_module_is_rejected(self):
        self.apk()
        self.debuggable = True
        with self.assertRaises(repo.PublishError):
            self.publish()
        self.debuggable = False
        self.package_override = PREFIX + 'zh.wrong'
        with self.assertRaises(repo.PublishError):
            self.publish()
        self.package_override = None
        self.allowed = set()
        with self.assertRaises(repo.PublishError):
            self.publish()
        self.assertEqual(self.snapshot(), {})

    def test_empty_input_and_duplicate_packages_fail_before_writing(self):
        with self.assertRaises(repo.PublishError):
            self.publish()
        self.apk(code=6)
        self.apk(code=7)
        with self.assertRaises(repo.PublishError):
            self.publish()
        self.assertEqual(self.snapshot(), {})

    def test_removed_module_is_pruned_from_next_publication(self):
        self.apk('x1hub', 1)
        self.apk()
        self.publish()
        self.clear_input()
        self.allowed.remove(PREFIX + 'all.x1hub')
        self.apk(code=7)
        self.assertEqual(self.publish(), 1)
        self.assertFalse((self.output / 'apk/aniyomi-all.x1hub-v16.1-release.apk').exists())

    def test_corrupt_existing_index_cannot_escape_output(self):
        (self.output / 'index.min.json').write_text(json.dumps([{
            'pkg': PREFIX + 'zh.mugua', 'apk': '../../outside.apk',
        }]))
        self.apk()
        with self.assertRaises(repo.PublishError):
            self.publish()


    def test_workflow_requires_trusted_signing_and_publication(self):
        workflow = (SCRIPT.parents[1] / 'workflows/build_push.yml').read_text(encoding='utf-8')
        publisher = workflow.split('  publish:', 1)[1].split('  result:', 1)[0]
        self.assertIn('needs: [prepare, sign]', publisher)
        self.assertIn("github.event_name != 'pull_request' && github.ref == 'refs/heads/dev'", publisher)
        self.assertIn('contents: write', publisher)
        self.assertNotIn('EXTENSION_SIGNING', publisher)
        self.assertIn('name: signed-apks-${{ github.run_number }}', publisher)
        self.assertIn('git push origin HEAD:refs/heads/repo', publisher)
        self.assertNotIn('--force', publisher)
        result = workflow.split('  result:', 1)[1]
        self.assertIn('needs: [prepare, build, sign, publish]', result)
        self.assertIn('[[ "$PUBLISH_RESULT" == success ]]', result)


if __name__ == '__main__':
    unittest.main()
