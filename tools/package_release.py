"""Validate a signed APK and stage an internally consistent Pages/Release artifact."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = 'io.github.user03715910.dualkey'

def properties(path):
    return dict(line.split('=', 1) for line in Path(path).read_text(encoding='utf-8').splitlines() if '=' in line and not line.lstrip().startswith('#'))

def sdk_tool(name):
    sdk = Path(os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT') or Path.home() / 'AppData/Local/Android/Sdk')
    tool = sdk / 'build-tools/35.0.0' / (name + ('.bat' if name == 'apksigner' else '.exe') if os.name == 'nt' else name)
    if not tool.exists():
        raise SystemExit(f'Missing SDK tool: {tool}')
    return str(tool)

def stage(args):
    config = properties(ROOT / 'distribution.properties')
    website = config['websiteUrl']
    repo = config['repository']
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repo):
        raise SystemExit('Create the dedicated repository and configure distribution.properties first.')
    owner, name = repo.split('/')
    if website != f'https://{owner.lower()}.github.io/{name}/' or config['metadataUrl'] != website + 'version.json':
        raise SystemExit('Distribution configuration does not match the dedicated GitHub Pages repository.')
    apk = Path(args.apk).resolve()
    output = Path(args.output).resolve()
    if output == ROOT or ROOT in output.parents and output.name not in ('dist', 'release-stage'):
        raise SystemExit('Use a dedicated dist or release-stage directory.')
    badging = subprocess.check_output([sdk_tool('aapt'), 'dump', 'badging', str(apk)], text=True, encoding='utf-8')
    package = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
    if not package or package[1] != PACKAGE or 'application-debuggable' in badging:
        raise SystemExit('Only a non-debug release APK of DualKey IME may be distributed.')
    code, version = int(package[2]), package[3]
    committed = properties(ROOT / 'version.properties')
    if code != int(committed['versionCode']) or version != committed['versionName']:
        raise SystemExit('APK version differs from committed version.properties.')
    if not re.fullmatch(r'\d+\.\d+\.\d+', version):
        raise SystemExit('Use a semantic display version, such as 0.1.0.')
    verification = subprocess.check_output([sdk_tool('apksigner'), 'verify', '--verbose', '--print-certs', str(apk)], text=True, encoding='utf-8')
    expected = os.environ.get('DUALKEY_CERTSHA256') or properties(ROOT / '.local/signing.properties').get('certSha256', '')
    certificates = re.findall(r'Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]+)', verification)
    if len(certificates) != 1 or certificates[0].lower() != expected.lower() or not re.fullmatch(r'[a-fA-F0-9]{64}', expected):
        raise SystemExit('Release certificate does not match the pinned signing certificate.')
    sdk = re.search(r"sdkVersion:'(\d+)'", badging)
    notes = [line.strip('- ').strip() for line in Path(args.notes).read_text(encoding='utf-8').splitlines() if line.strip()]
    if not notes:
        raise SystemExit('Release notes are required.')
    size = apk.stat().st_size
    if not 0 < size <= 150 * 1024 * 1024:
        raise SystemExit('APK exceeds the supported size.')
    metadata = dict(packageName=PACKAGE, versionCode=code, versionName=version,
        releaseDate=datetime.now(timezone.utc).strftime('%Y-%m-%d'),
        apkUrl=f'https://github.com/{repo}/releases/download/v{version}/DualKeyIME-{version}.apk',
        websiteUrl=website, sha256=hashlib.file_digest(apk.open('rb'), 'sha256').hexdigest(),
        sizeBytes=size, minSdk=int(sdk[1]), required=bool(args.required), releaseNotes=notes)
    history = json.loads(Path(args.history).read_text(encoding='utf-8')) if args.history else []
    if not isinstance(history, list) or any(h['versionCode'] >= code for h in history):
        raise SystemExit('versionCode must exceed every published release. Do not overwrite an existing release.')
    pages = output / 'site'
    release = output / 'release'
    # No recursive cleanup: copy only the explicitly generated site/release files.
    shutil.copytree(ROOT / 'site', pages, dirs_exist_ok=True)
    release.mkdir(parents=True, exist_ok=True)
    for path in (pages / 'version.json', release / 'version.json'):
        path.write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    (pages / 'history.json').write_text(json.dumps([metadata] + history, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    shutil.copy2(apk, release / f'DualKeyIME-{version}.apk')
    shutil.copy2(args.notes, release / 'release-notes.txt')
    print(json.dumps(dict(versionName=version, versionCode=code, sha256=metadata['sha256'], sizeBytes=size, site=str(pages), release=str(release)), ensure_ascii=False))

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--apk', default=str(ROOT / 'app/build/outputs/apk/release/app-release.apk'))
    parser.add_argument('--output', default=str(ROOT / 'dist'))
    parser.add_argument('--notes', default=str(ROOT / 'release-notes.txt'))
    parser.add_argument('--history')
    parser.add_argument('--required', action='store_true')
    stage(parser.parse_args())
