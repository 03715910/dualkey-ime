"""Fetch old metadata safely; only a repository with no public release can start empty."""
import json
import subprocess
import urllib.error
import urllib.request
from pathlib import Path
from package_release import ROOT, properties

config = properties(ROOT / 'distribution.properties')
repo = config['repository']
try:
    request = urllib.request.Request(config['websiteUrl'] + 'history.json', headers={'Cache-Control': 'no-cache'})
    with urllib.request.urlopen(request, timeout=30) as response:
        data = response.read(2 * 1024 * 1024 + 1)
    if len(data) > 2 * 1024 * 1024 or not isinstance(json.loads(data), list):
        raise SystemExit('Invalid history.')
except urllib.error.HTTPError as error:
    if error.code != 404:
        raise
    releases = json.loads(subprocess.check_output(['gh', 'api', f'repos/{repo}/releases?per_page=100'], text=True))
    if any(not r['draft'] and not r['prerelease'] for r in releases):
        raise SystemExit('Public releases exist, but history is unavailable. Recover history before another release.')
    data = b'[]\n'
Path('previous-history.json').write_bytes(data)
print('Previous release history prepared.')
