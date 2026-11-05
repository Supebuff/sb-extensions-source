#!/usr/bin/env python3
"""Merge verified APKs into the fork's AniZen-compatible legacy catalogue."""
import argparse
import hashlib
import html
import importlib.util
import json
import re
import shutil
import subprocess
import tempfile
from pathlib import Path
from urllib.parse import quote
from zipfile import ZipFile


def load_script(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


signer = load_script('repo_signer', 'sign-apks.py')
PACKAGE_PREFIX = 'eu.kanade.tachiyomi.animeextension.'
APK_PATTERN = re.compile(r'aniyomi-([a-z]+)\.([a-z0-9_]+)-v[0-9.]+-release\.apk')


class PublishError(Exception):
    pass


def tool(command):
    result = subprocess.run(command, capture_output=True, text=True, encoding='utf-8')
    if result.returncode:
        raise PublishError('APK inspection or signature verification failed.')
    return result.stdout


def regular_file(path):
    if path.is_symlink() or not path.is_file():
        raise PublishError(f'Expected a regular file: {path.name}')
    return path


def inspect_apk(apk, allowed, pin, aapt, apksigner, runner=tool):
    regular_file(apk)
    match = APK_PATTERN.fullmatch(apk.name)
    if not match:
        raise PublishError('Only named Release APKs may be published.')
    lang, source = match.groups()
    package = PACKAGE_PREFIX + lang + '.' + source
    if package not in allowed:
        raise PublishError('APK is not a current fork-added extension.')
    command = ['java', '-jar', str(apksigner)] if str(apksigner).endswith('.jar') else [str(apksigner)]
    signer.verify_fingerprint(runner(command + ['verify', '--verbose', '--print-certs', str(apk)]), pin)
    badging = runner([str(aapt), 'dump', '--include-meta-data', 'badging', str(apk)])
    info = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging, re.M)
    if not info or info[1] != package or int(info[2]) <= 0:
        raise PublishError('APK package or version does not match its file name.')
    if apk.name != f'aniyomi-{lang}.{source}-v{info[3]}-release.apk':
        raise PublishError('APK filename version differs from its manifest.')
    if 'application-debuggable' in badging or "uses-feature: name='tachiyomi.animeextension'" not in badging:
        raise PublishError('APK must be a non-debuggable anime extension.')
    label = re.search(r"^application-label:'([^']+)'", badging, re.M)
    icon = re.search(r"^application-icon-(?:320|480|640|240|160):'([^']+)'", badging, re.M)
    if not label or not icon:
        raise PublishError('APK has no catalogue label or PNG launcher icon.')
    with ZipFile(apk) as archive:
        icon_info = archive.getinfo(icon[1])
        if icon_info.file_size > 2 * 1024 * 1024:
            raise PublishError('APK icon is too large.')
        png = archive.read(icon_info)
    if not png.startswith(b'\x89PNG\r\n\x1a\n'):
        raise PublishError('Catalogue icon must be PNG.')
    def metadata(key, default):
        found = re.search(r"name='" + re.escape(key) + r"' value='([^']+)'", badging)
        return found[1] if found else default
    warning = int(metadata('aniyomix.contentWarning', '0'))
    nsfw = warning > 0 or metadata('tachiyomi.animeextension.nsfw', '0') == '1'
    entry = {
        'name': label[1], 'pkg': package, 'apk': apk.name, 'lang': lang,
        'code': int(info[2]), 'version': info[3], 'nsfw': int(nsfw),
        'contentWarning': warning if warning else (2 if nsfw else 0),
        'extensionLib': metadata('aniyomix.extensionLib', info[3].rsplit('.', 1)[0]),
        # Optional legacy field. Do not invent runtime source IDs without an inspector.
        'sources': [],
    }
    return entry, png


def publish(input_dir, output_dir, allowed, pin, base_url, aapt, apksigner, runner=tool):
    output_dir = output_dir.resolve()
    if not output_dir.is_dir() or input_dir.is_symlink() or not input_dir.is_dir():
        raise PublishError('Input and checked-out publication directories must exist.')
    for directory in ('apk', 'icon'):
        path = output_dir / directory
        if path.is_symlink() or (path.exists() and not path.is_dir()):
            raise PublishError('Invalid publication resource directory.')
    index_path = output_dir / 'index.min.json'
    previous = json.loads(regular_file(index_path).read_text(encoding='utf-8')) if index_path.exists() else []
    if not isinstance(previous, list):
        raise PublishError('Existing index must be an array.')
    entries, files, icons = {}, {}, {}
    for entry in previous:
        pkg = entry.get('pkg', '')
        name = entry.get('apk', '')
        if not isinstance(name, str) or not APK_PATTERN.fullmatch(name) or pkg in entries:
            raise PublishError('Invalid or duplicate entry in existing index.')
        if pkg not in allowed:
            continue
        path = output_dir / 'apk' / name
        checked, png = inspect_apk(path, allowed, pin, aapt, apksigner, runner)
        if checked['pkg'] != pkg:
            raise PublishError('Existing index package does not match its APK.')
        entries[pkg], files[pkg], icons[pkg] = checked, path, png
    incoming = sorted(input_dir.glob('*.apk'))
    if not incoming:
        raise PublishError('No signed Release APKs to publish.')
    seen = set()
    for path in incoming:
        entry, png = inspect_apk(path, allowed, pin, aapt, apksigner, runner)
        pkg = entry['pkg']
        if pkg in seen:
            raise PublishError('Duplicate package in incoming APKs.')
        seen.add(pkg)
        old = entries.get(pkg)
        if old and entry['code'] < old['code']:
            raise PublishError('Refusing to downgrade a published extension.')
        if old and entry['code'] == old['code']:
            # Keep the installed version immutable; code changes require a version bump.
            continue
        entries[pkg], files[pkg], icons[pkg] = entry, path, png
    if not entries:
        raise PublishError('Refusing to publish an empty catalogue.')
    index = sorted(entries.values(), key=lambda entry: entry['pkg'])
    base_url = base_url.rstrip('/')
    site = 'https://github.com/' + '/'.join(base_url.split('/')[3:5])
    auto_url = 'https://intradeus.github.io/http-protocol-redirector/?r=' + quote('anizen://add-repo?url=' + base_url + '/index.min.json', safe='')
    # Prepare all files before touching the checkout; the subsequent Git push is atomic.
    with tempfile.TemporaryDirectory(prefix='extension-catalogue-') as temporary:
        stage = Path(temporary)
        (stage / 'apk').mkdir()
        (stage / 'icon').mkdir()
        for entry in index:
            pkg = entry['pkg']
            shutil.copyfile(files[pkg], stage / 'apk' / entry['apk'])
            (stage / 'icon' / (pkg + '.png')).write_bytes(icons[pkg])
        (stage / 'index.min.json').write_text(json.dumps(index, ensure_ascii=False, separators=(',', ':')), encoding='utf-8')
        (stage / 'index.json').write_text(json.dumps(index, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        meta = {'meta': {'name': 'Supebuff Extensions', 'shortName': 'Supebuff', 'website': site, 'author': 'Supebuff', 'signingKeyFingerprint': pin}}
        (stage / 'repo.json').write_text(json.dumps(meta, indent=2) + '\n', encoding='utf-8')
        rows = ''.join('<li><a href="apk/' + html.escape(e['apk'], quote=True) + '">' + html.escape(e['name']) + ' ' + html.escape(e['version']) + '</a></li>' for e in index)
        (stage / 'index.html').write_text('<!doctype html><html lang="zh-Hant"><meta charset="utf-8"><title>Supebuff Extensions</title><h1>Supebuff Extensions</h1><p><a href="' + html.escape(auto_url, quote=True) + '">加入 AniZen 套件庫</a></p><ul>' + rows + '</ul></html>\n', encoding='utf-8')
        sums = ''.join(hashlib.sha256((stage / 'apk' / e['apk']).read_bytes()).hexdigest() + '  apk/' + e['apk'] + '\n' for e in index)
        (stage / 'SHA256SUMS').write_text(sums, encoding='ascii')
        (stage / 'SIGNING_CERTIFICATE_SHA256.txt').write_text(pin + '\n', encoding='ascii')
        for directory in ('apk', 'icon'):
            destination = output_dir / directory
            destination.mkdir(exist_ok=True)
            expected = {p.name for p in (stage / directory).iterdir()}
            for old in destination.iterdir():
                regular_file(old)
                if old.name not in expected:
                    old.unlink()
        for path in stage.rglob('*'):
            if path.is_file():
                target = output_dir / path.relative_to(stage)
                if target.is_symlink():
                    raise PublishError('Refusing to overwrite a symbolic link.')
                shutil.copyfile(path, target)
    return len(index)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--aapt', required=True, type=Path)
    parser.add_argument('--apksigner', required=True, type=Path)
    parser.add_argument('--repository', required=True)
    parser.add_argument('--upstream', default='origin/upstream')
    parser.add_argument('--fingerprint', type=Path, default=Path('.github/signing-certificate.sha256'))
    args = parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', args.repository):
        raise PublishError('Invalid GitHub repository name.')
    matrix = load_script('repo_matrix', 'ci-matrix.py')
    allowed = {PACKAGE_PREFIX + '.'.join(module.split(':')[-2:]) for module in matrix.added_modules(args.upstream)}
    count = publish(args.input, args.output, allowed, signer.expected_fingerprint(args.fingerprint),
                    f'https://raw.githubusercontent.com/{args.repository}/repo', args.aapt, args.apksigner)
    print(f'Published catalogue contains {count} verified extension(s).')


if __name__ == '__main__':
    main()
