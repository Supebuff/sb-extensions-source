#!/usr/bin/env python3
"""Sign unsigned release APKs with a private key and a pinned public certificate."""
import argparse
import base64
import binascii
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile


class SigningError(Exception):
    pass


def credentials(value):
    if not value:
        raise SigningError('Add the EXTENSION_SIGNING repository secret before signing releases.')
    try:
        data = json.loads(value)
        fields = ('keystore_base64', 'store_password', 'key_alias', 'key_password')
        if not isinstance(data, dict) or any(
            not isinstance(data.get(key), str) or not data[key] or
            any(char in data[key] for char in '\r\n\x00') for key in fields
        ):
            raise ValueError()
        key = base64.b64decode(data['keystore_base64'], validate=True)
        if not key:
            raise ValueError()
    except (ValueError, TypeError, binascii.Error):
        raise SigningError('EXTENSION_SIGNING must contain valid signing JSON and a Base64 keystore.') from None
    return data, key


def expected_fingerprint(path):
    value = path.read_text(encoding='ascii').strip().lower()
    if not re.fullmatch('[0-9a-f]{64}', value):
        raise SigningError('The pinned signing certificate must be a SHA-256 hex fingerprint.')
    return value


def verify_fingerprint(output, expected):
    # SDK 37 uses scheme labels such as "V3.0 Signer:" instead of "Signer #1".
    # One signer can be reported for several schemes; every certificate must match.
    records = re.findall(
        r'^(Signer #\d+|V[123]\.\d+ Signer(?: #\d+)?:) certificate SHA-256 digest: '
        r'([0-9a-fA-F]{64})[ \t]*\r?$', output, re.MULTILINE,
    )
    counts = re.findall(r'^Number of signers: (\d+)[ \t]*\r?$', output, re.MULTILINE)
    reported = re.findall(r'^.*certificate SHA-256 digest: ([0-9a-fA-F]{64})[ \t]*\r?$', output, re.MULTILINE)
    valid = counts == ['1'] and records and len(records) == len(reported) and all(
        digest.lower() == expected and all(number == '1' for number in re.findall(r'#(\d+)', label))
        for label, digest in records
    )
    if not valid:
        # Certificate hashes are public. Report only hashes to distinguish a different
        # key from a new apksigner output format without logging private credentials.
        raise SigningError(
            'APK signer does not match the pinned certificate; refusing to publish. '
            f'Expected SHA256={expected}; recognized certificate records={len(records)}; '
            f'reported certificate SHA256={",".join(reported) or "none"}.'
        )


def discover_apks(root):
    resolved = root.resolve()
    apks = sorted(root.rglob('*.apk'))
    if not apks:
        raise SigningError('No unsigned release APKs found.')
    names = set()
    for apk in apks:
        if apk.is_symlink() or not apk.resolve().is_relative_to(resolved):
            raise SigningError('APK path escapes the input directory.')
        if not apk.name.endswith('-release-unsigned.apk'):
            raise SigningError('Only Gradle unsigned release APKs may be signed.')
        name = apk.name.removesuffix('-unsigned.apk') + '.apk'
        if name in names:
            raise SigningError('Duplicate APK output names.')
        names.add(name)
    return apks


def run_tool(command, env):
    result = subprocess.run(command, env=env, capture_output=True, text=True, encoding='utf-8', errors='replace')
    if result.returncode != 0:
        # Tool diagnostics may contain paths or keystore details. Never echo secret input/commands.
        raise SigningError('apksigner failed; confirm the keystore, alias, passwords and APK validity.')
    return result.stdout


def sign_apks(input_dir, output_dir, pin, apksigner, secret, runner=run_tool):
    data, key = credentials(secret)
    expected = expected_fingerprint(pin)
    apks = discover_apks(input_dir)
    if output_dir.exists():
        raise SigningError('Signing output directory must not already exist.')
    tool = ['java', '-jar', str(apksigner)] if apksigner.suffix == '.jar' else [str(apksigner)]
    env = dict(os.environ)
    env.pop('EXTENSION_SIGNING', None)
    env['EXTENSION_KEYSTORE_PASSWORD'] = data['store_password']
    env['EXTENSION_KEY_PASSWORD'] = data['key_password']
    # Temporary key files are outside Gradle caches and never uploaded as artifacts.
    with tempfile.TemporaryDirectory(prefix='extension-signing-', dir=os.getenv('RUNNER_TEMP')) as temporary:
        directory = Path(temporary)
        store = directory / 'signing.p12'
        store.write_bytes(key)
        store.chmod(0o600)
        staged = directory / 'verified'
        staged.mkdir()
        for apk in apks:
            target = staged / (apk.name.removesuffix('-unsigned.apk') + '.apk')
            runner(tool + ['sign', '--ks', str(store), '--ks-key-alias', data['key_alias'],
                           '--ks-pass', 'env:EXTENSION_KEYSTORE_PASSWORD',
                           '--key-pass', 'env:EXTENSION_KEY_PASSWORD', '--out', str(target), str(apk)], env)
            # Verification needs no signing credentials.
            verification_env = {k: v for k, v in env.items() if k not in (
                'EXTENSION_KEYSTORE_PASSWORD', 'EXTENSION_KEY_PASSWORD',
            )}
            verified = runner(tool + ['verify', '--verbose', '--print-certs', str(target)], verification_env)
            verify_fingerprint(verified, expected)
        checksums = ''.join(
            f'{hashlib.sha256(apk.read_bytes()).hexdigest()}  {apk.name}\n' for apk in sorted(staged.glob('*.apk'))
        )
        (staged / 'SHA256SUMS').write_text(checksums, encoding='ascii')
        (staged / 'SIGNING_CERTIFICATE_SHA256.txt').write_text(expected + '\n', encoding='ascii')
        shutil.copytree(staged, output_dir)
    return len(apks)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--fingerprint', default='.github/signing-certificate.sha256', type=Path)
    parser.add_argument('--apksigner', required=True, type=Path)
    args = parser.parse_args()
    secret = os.environ.pop('EXTENSION_SIGNING', '')
    try:
        count = sign_apks(args.input, args.output, args.fingerprint, args.apksigner, secret)
    except (SigningError, OSError) as error:
        # OSError diagnostics are omitted; their filenames may include user-provided values.
        print(str(error) if isinstance(error, SigningError) else 'Signing file/tool access failed.', file=sys.stderr)
        return 1
    print(f'Signed and verified {count} release APK(s) against the pinned certificate.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
