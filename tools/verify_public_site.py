"""Check the public download page and its assets without requiring browser automation."""
import urllib.request
from package_release import ROOT, properties

base = properties(ROOT / 'distribution.properties')['websiteUrl']
for name in ('', 'app.js', 'style.css', 'icon.svg'):
    with urllib.request.urlopen(base + name, timeout=30) as response:
        body = response.read()
        assert response.status == 200
    if name == '':
        page = body.decode('utf-8')
        assert 'id="download"' in page and 'APKをダウンロード' in page and 'app.js' in page
    print('HTTP 200:', base + name, len(body), 'bytes')
print('Public download HTML and its required assets are available. Browser rendering is not covered by this check.')
