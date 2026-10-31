"""Explicitly publish a locally signed APK and Pages site. Never upload the signing key."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
from package_release import ROOT, properties


def run(*args, cwd=ROOT):
    return subprocess.check_output(list(map(str, args)), cwd=cwd, text=True, encoding='utf-8').strip()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--confirm', required=True, choices=['PUBLISH'])
    args = parser.parse_args()
    config = properties(ROOT / 'distribution.properties')
    repo = config['repository']
    if run('git', 'status', '--porcelain'):
        raise SystemExit('Commit all source changes before publishing.')
    head = run('git', 'rev-parse', 'HEAD')
    if run('gh', 'api', f'repos/{repo}/commits/main', '--jq', '.sha') != head:
        raise SystemExit('Push this exact commit to the dedicated repository first.')
    tag = 'v' + properties(ROOT / 'version.properties')['versionName']
    for command in (['gh', 'release', 'view', tag, '--repo', repo], ['gh', 'api', f'repos/{repo}/git/ref/tags/{tag}']):
        if subprocess.run(command, cwd=ROOT, capture_output=True).returncode == 0:
            raise SystemExit('This release or tag already exists; never overwrite a published version.')
    # Stage all metadata from the validated APK, after reading the existing public history.
    subprocess.run([sys.executable, 'tools/fetch_history.py'], cwd=ROOT, check=True)
    subprocess.run([sys.executable, 'tools/package_release.py', '--history', 'previous-history.json'], cwd=ROOT, check=True)
    metadata = json.loads((ROOT / 'dist/site/version.json').read_text(encoding='utf-8'))
    filename = 'DualKeyIME-' + metadata['versionName'] + '.apk'
    assets = [ROOT / 'dist/release' / name for name in (filename, 'version.json', 'release-notes.txt')]
    run('gh', 'release', 'create', tag, '--repo', repo, '--target', head, '--draft',
        '--title', 'DualKey IME ' + tag + ' (開発版)', '--notes-file', ROOT / 'release-notes.txt', *assets)
    downloaded = ROOT / '.local' / ('published-assets-' + tag)
    downloaded.mkdir(parents=True, exist_ok=False)
    run('gh', 'release', 'download', tag, '--repo', repo, '--dir', downloaded)
    apk = downloaded / filename
    with apk.open('rb') as stream:
        digest = hashlib.file_digest(stream, 'sha256').hexdigest()
    if apk.stat().st_size != metadata['sizeBytes'] or digest != metadata['sha256']:
        raise SystemExit('Uploaded APK differs from the signed artifact. The draft remains unpublished.')
    if json.loads((downloaded / 'version.json').read_text(encoding='utf-8')) != metadata:
        raise SystemExit('Uploaded metadata mismatch. The draft remains unpublished.')
    run('gh', 'release', 'edit', tag, '--repo', repo, '--draft=false', '--latest')

    # A separate branch contains only the generated website, never any APK or signing material.
    checkout = ROOT / '.local/pages-checkout'
    remote = f'https://github.com/{repo}.git'
    if not checkout.exists():
        run('git', 'init', '--initial-branch=gh-pages', checkout)
        run('git', 'remote', 'add', 'origin', remote, cwd=checkout)
        run('git', 'config', 'user.name', run('git', 'config', 'user.name'), cwd=checkout)
        run('git', 'config', 'user.email', run('git', 'config', 'user.email'), cwd=checkout)
        existing = subprocess.run(['git', 'ls-remote', '--exit-code', remote, 'refs/heads/gh-pages'], capture_output=True)
        if existing.returncode == 0:
            run('git', 'fetch', 'origin', 'gh-pages', cwd=checkout)
            run('git', 'checkout', '-B', 'gh-pages', 'FETCH_HEAD', cwd=checkout)
        elif existing.returncode != 2:
            raise SystemExit('Could not check the existing Pages branch. The release is public, but Pages was not changed.')
    if run('git', 'remote', 'get-url', 'origin', cwd=checkout) != remote or run('git', 'branch', '--show-current', cwd=checkout) != 'gh-pages':
        raise SystemExit('Unexpected website checkout; refusing to push.')
    names = ['.nojekyll', 'index.html', 'style.css', 'app.js', 'icon.svg', 'version.json', 'history.json']
    for name in names:
        shutil.copy2(ROOT / 'dist/site' / name, checkout / name)
    run('git', 'add', '--', *names, cwd=checkout)
    run('git', 'commit', '-m', 'Publish DualKey IME ' + tag, cwd=checkout)
    run('git', 'push', 'origin', 'gh-pages', cwd=checkout)

    page = subprocess.run(['gh', 'api', f'repos/{repo}/pages'], capture_output=True, text=True)
    if page.returncode != 0:
        if '404' not in page.stderr:
            raise SystemExit('Could not inspect Pages settings; configure the gh-pages source manually.')
        run('gh', 'api', '--method', 'POST', f'repos/{repo}/pages', '-f', 'build_type=legacy',
            '-f', 'source[branch]=gh-pages', '-f', 'source[path]=/')
    else:
        current = json.loads(page.stdout)
        if current.get('build_type') != 'legacy' or current.get('source', {}).get('branch') != 'gh-pages':
            raise SystemExit('Pages uses a different publishing source. Inspect the configuration before changing it.')
    print('Release published; GitHub Pages build requested:', config['websiteUrl'])
    print('The private signing key stayed on this PC. Verify the public site after the Pages deployment succeeds.')


if __name__ == '__main__':
    main()
