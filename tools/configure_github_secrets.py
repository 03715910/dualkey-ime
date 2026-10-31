"""Run ONLY with explicit authorization to register the dedicated repository's signing secrets."""
import argparse
import base64
import subprocess
from package_release import ROOT, properties

parser = argparse.ArgumentParser()
parser.add_argument('--repository', required=True)
args = parser.parse_args()
config = properties(ROOT / 'distribution.properties')
if args.repository != config['repository'] or not args.repository:
    raise SystemExit('The repository must match distribution.properties.')
signing = properties(ROOT / '.local/signing.properties')
values = {
    'RELEASE_KEYSTORE_BASE64': base64.b64encode((ROOT / signing['storeFile']).read_bytes()).decode(),
    'RELEASE_STORE_PASSWORD': signing['storePassword'],
    'RELEASE_KEY_ALIAS': signing['keyAlias'],
    'RELEASE_KEY_PASSWORD': signing['keyPassword'],
    'RELEASE_CERT_SHA256': signing['certSha256'],
}
for name, value in values.items():
    subprocess.run(['gh', 'secret', 'set', name, '--repo', args.repository], input=value.encode(), check=True, capture_output=True)
    print('Registered:', name)
