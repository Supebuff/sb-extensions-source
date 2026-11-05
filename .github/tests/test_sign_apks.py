"""Signing must fail closed and keep private material out of artifacts and subprocess arguments."""
import base64
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[1] / 'scripts/sign-apks.py'
spec = importlib.util.spec_from_file_location('sign_apks', SCRIPT)
signing = importlib.util.module_from_spec(spec)
spec.loader.exec_module(signing)


class SigningTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.input = self.root / 'input'
        self.input.mkdir()
        self.apk = self.input / 'aniyomi-zh.mugua-v16.5-release-unsigned.apk'
        self.apk.write_bytes(b'fixture')
        self.output = self.root / 'signed'
        self.pin = self.root / 'certificate.sha256'
        self.fingerprint = 'a' * 64
        self.pin.write_text(self.fingerprint + '\n', encoding='ascii')
        self.secret = json.dumps({'keystore_base64': base64.b64encode(b'private-fixture').decode(),
                                  'store_password': 'store-secret', 'key_alias': 'extensions',
                                  'key_password': 'key-secret'})
        self.calls = []

    def runner(self, command, env):
        self.calls.append((command, env))
        if 'sign' in command:
            Path(command[command.index('--out') + 1]).write_bytes(b'signed-fixture')
            self.key_path = Path(command[command.index('--ks') + 1])
            self.assertEqual(self.key_path.read_bytes(), b'private-fixture')
            return ''
        return f'Verifies\nNumber of signers: 1\nSigner #1 certificate SHA-256 digest: {self.fingerprint}\n'

    def sign(self, runner=None):
        return signing.sign_apks(self.input, self.output, self.pin, Path('/tools/apksigner'),
                                 self.secret, runner or self.runner)

    def test_success_publishes_only_verified_apks_and_public_checksums(self):
        with patch.dict(os.environ, {'EXTENSION_SIGNING': self.secret}):
            self.assertEqual(self.sign(), 1)
        self.assertEqual({p.name for p in self.output.iterdir()}, {
            'aniyomi-zh.mugua-v16.5-release.apk', 'SHA256SUMS', 'SIGNING_CERTIFICATE_SHA256.txt',
        })
        self.assertFalse(self.key_path.exists())
        for command, env in self.calls:
            self.assertNotIn('store-secret', command)
            self.assertNotIn('key-secret', command)
            self.assertNotIn('EXTENSION_SIGNING', env)
            if 'verify' in command:
                self.assertNotIn('EXTENSION_KEYSTORE_PASSWORD', env)
                self.assertNotIn('EXTENSION_KEY_PASSWORD', env)
        self.assertIn('aniyomi-zh.mugua-v16.5-release.apk', (self.output / 'SHA256SUMS').read_text())

    def test_missing_and_malformed_secret_fail_without_echoing_secret(self):
        for value in ('', 'secret-looking-invalid-json', '[]', '{}',
                      self.secret.replace('cHJpdmF0ZS1maXh0dXJl', 'bad!')):
            with self.subTest(value_length=len(value)):
                with self.assertRaises(signing.SigningError) as error:
                    signing.credentials(value)
                self.assertNotIn('secret-looking', str(error.exception))
        self.assertFalse(self.output.exists())

    def test_multiline_passwords_are_rejected(self):
        data = json.loads(self.secret)
        data['store_password'] = 'line1\nline2'
        with self.assertRaises(signing.SigningError):
            signing.credentials(json.dumps(data))

    def test_wrong_signer_prevents_publishing_and_cleans_private_files(self):
        def wrong(command, env):
            result = self.runner(command, env)
            return result.replace(self.fingerprint, 'b' * 64)
        with self.assertRaises(signing.SigningError) as error:
            self.sign(wrong)
        self.assertIn('reported certificate SHA256=' + 'b' * 64, str(error.exception))
        self.assertNotIn('store-secret', str(error.exception))
        self.assertFalse(self.output.exists())
        self.assertFalse(self.key_path.exists())

    def test_multiple_or_missing_signers_are_rejected(self):
        for result in ('no certificate output',
                       f'Signer #1 certificate SHA-256 digest: {self.fingerprint}\n'
                       f'Signer #2 certificate SHA-256 digest: {self.fingerprint}\n'):
            with self.assertRaises(signing.SigningError):
                signing.verify_fingerprint(result, self.fingerprint)

    def test_unrecognized_output_reports_public_digest_but_still_refuses_publishing(self):
        with self.assertRaises(signing.SigningError) as error:
            signing.verify_fingerprint(
                f'Signer (SDK range) certificate SHA-256 digest: {self.fingerprint}\n', self.fingerprint,
            )
        self.assertIn('recognized certificate records=0', str(error.exception))
        self.assertIn('reported certificate SHA256=' + self.fingerprint, str(error.exception))

    def test_sdk37_scheme_labels_are_supported_with_the_same_pinned_certificate(self):
        signing.verify_fingerprint(
            f'Number of signers: 1\nV3.0 Signer: certificate SHA-256 digest: {self.fingerprint}\n',
            self.fingerprint,
        )
        signing.verify_fingerprint(
            f'Number of signers: 1\r\nV2.0 Signer #1: certificate SHA-256 digest: {self.fingerprint}\r\n'
            f'V3.0 Signer: certificate SHA-256 digest: {self.fingerprint.upper()}\r\n', self.fingerprint,
        )

    def test_sdk37_multiple_signers_or_unexpected_scheme_certificate_are_rejected(self):
        for output in (
            f'Number of signers: 2\nV3.0 Signer: certificate SHA-256 digest: {self.fingerprint}\n',
            f'Number of signers: 1\nV2.0 Signer #2: certificate SHA-256 digest: {self.fingerprint}\n',
            f'Number of signers: 1\nV3.0 Signer: certificate SHA-256 digest: {self.fingerprint}\n'
            f'V3.1 Signer: certificate SHA-256 digest: {"b" * 64}\n',
            f'Number of signers: 1\nV3.0 Signer: certificate SHA-256 digest: {self.fingerprint}\n'
            f'Unknown Signer: certificate SHA-256 digest: {self.fingerprint}\n',
        ):
            with self.subTest(output_length=len(output)):
                with self.assertRaises(signing.SigningError):
                    signing.verify_fingerprint(output, self.fingerprint)

    def test_missing_signer_count_is_rejected_even_if_a_digest_matches(self):
        with self.assertRaises(signing.SigningError):
            signing.verify_fingerprint(f'Signer #1 certificate SHA-256 digest: {self.fingerprint}\n', self.fingerprint)

    def test_tool_failure_cleans_key_and_publishes_nothing(self):
        def failing(command, env):
            self.runner(command, env)
            raise signing.SigningError('fixture tool failure')
        with self.assertRaises(signing.SigningError):
            self.sign(failing)
        self.assertFalse(self.output.exists())
        self.assertFalse(self.key_path.exists())

    def test_debug_apks_and_duplicate_release_names_are_rejected(self):
        debug = self.input / 'debug.apk'
        debug.write_bytes(b'debug')
        with self.assertRaises(signing.SigningError):
            self.sign()
        debug.unlink()
        other = self.input / 'another-module'
        other.mkdir()
        (other / self.apk.name).write_bytes(b'duplicate')
        with self.assertRaises(signing.SigningError):
            self.sign()
        self.assertFalse(self.output.exists())

    def test_empty_input_invalid_pin_and_existing_output_are_rejected(self):
        self.pin.write_text('invalid', encoding='ascii')
        with self.assertRaises(signing.SigningError):
            self.sign()
        self.pin.write_text(self.fingerprint, encoding='ascii')
        self.output.mkdir()
        with self.assertRaises(signing.SigningError):
            self.sign()
        self.output.rmdir()
        self.apk.unlink()
        with self.assertRaises(signing.SigningError):
            self.sign()

    def test_workflow_keeps_secret_out_of_pr_builds_and_requires_release_signing(self):
        workflow = SCRIPT.parents[1] / 'workflows/build_push.yml'
        content = workflow.read_text(encoding='utf-8')
        build = content.split('\n  build:', 1)[1].split('\n  sign:', 1)[0]
        signer = content.split('\n  sign:', 1)[1].split('\n  result:', 1)[0]
        self.assertNotIn('secrets.', build)
        self.assertEqual(content.count('secrets.EXTENSION_SIGNING'), 1)
        self.assertIn("github.event_name != 'pull_request' && github.ref == 'refs/heads/dev'", signer)
        self.assertIn('[[ "$SIGN_RESULT" == success ]]', content)


if __name__ == '__main__':
    unittest.main()
