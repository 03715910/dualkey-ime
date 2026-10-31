import hashlib
import json
from pathlib import Path
import time
import urllib.request
from package_release import ROOT, properties

url = properties(ROOT / 'distribution.properties')['metadataUrl']
expected = json.loads((ROOT / 'dist/site/version.json').read_text(encoding='utf-8'))
last_error = None
for attempt in range(6):
    try:
        req = urllib.request.Request(url + '?verify=' + str(int(time.time())), headers={'Cache-Control': 'no-cache'})
        with urllib.request.urlopen(req, timeout=30) as response:
            actual = json.load(response)
        if actual != expected:
            raise ValueError('Public version.json does not yet match the release artifact.')
        with urllib.request.urlopen(actual['apkUrl'], timeout=30) as response:
            digest = hashlib.sha256()
            size = 0
            while chunk := response.read(65536):
                size += len(chunk)
                if size > actual['sizeBytes']:
                    raise ValueError('Public APK size exceeds metadata.')
                digest.update(chunk)
        if size != actual['sizeBytes'] or digest.hexdigest() != actual['sha256']:
            raise ValueError('Public APK hash or size mismatch.')
        print('Verified public metadata and the complete downloaded APK:', actual['versionName'], size, digest.hexdigest())
        break
    except Exception as error:
        last_error = error
        if attempt == 5:
            raise
        time.sleep(10)
