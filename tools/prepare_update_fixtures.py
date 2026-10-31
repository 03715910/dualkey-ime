"""Local emulator-only negative fixtures. NEVER publish these APKs."""
import os
from pathlib import Path
import shutil
import struct
import subprocess
import zipfile
from package_release import ROOT, properties, sdk_tool

stage = ROOT / '.local/update-test'
stage.mkdir(parents=True, exist_ok=True)
release = ROOT / 'app/build/outputs/apk/release/app-release.apk'
debug = ROOT / 'app/build/outputs/apk/debug/app-debug.apk'
if not (stage / 'installed-v1.apk').exists():
    raise SystemExit('First copy the original versionCode 1 release APK to .local/update-test/installed-v1.apk.')
shutil.copy2(release, stage / 'next.apk')
badging = subprocess.check_output([sdk_tool('aapt'), 'dump', 'badging', str(release)], text=True, encoding='utf-8')
if "versionCode='2'" not in badging or "versionName='0.1.1'" not in badging:
    raise SystemExit('Build the test-only versionCode 2 / versionName 0.1.1 first.')
signing = properties(ROOT / '.local/signing.properties')
env = dict(os.environ, FIXTURE_STOREPASS=signing['storePassword'], FIXTURE_KEYPASS=signing['keyPassword'])
subprocess.run([sdk_tool('apksigner'), 'sign', '--ks', str(Path.home() / '.android/debug.keystore'),
    '--ks-pass', 'pass:android', '--key-pass', 'pass:android', '--out', str(stage / 'wrong-signer.apk'), str(release)], check=True, capture_output=True)
subprocess.run([sdk_tool('apksigner'), 'sign', '--ks', str(ROOT / signing['storeFile']), '--ks-key-alias', signing['keyAlias'],
    '--ks-pass', 'env:FIXTURE_STOREPASS', '--key-pass', 'env:FIXTURE_KEYPASS', '--out', str(stage / 'foreign.apk'), str(debug)], env=env, check=True, capture_output=True)
data = bytearray(release.read_bytes())
with zipfile.ZipFile(release) as archive:
    entry = archive.getinfo('classes.dex')
    offset = entry.header_offset
    filename_length, extra_length = struct.unpack_from('<HH', data, offset + 26)
    data[offset + 30 + filename_length + extra_length + 32] ^= 1
(stage / 'corrupt.apk').write_bytes(data)
sdk = Path(os.environ.get('ANDROID_HOME') or Path.home() / 'AppData/Local/Android/Sdk')
adb = str(sdk / 'platform-tools' / ('adb.exe' if os.name == 'nt' else 'adb'))
devices = subprocess.check_output([adb, 'devices'], text=True)
if 'emulator-5554\tdevice' not in devices:
    raise SystemExit('Expected emulator-5554. Fixtures will not be installed on a real device automatically.')
subprocess.run([adb, '-s', 'emulator-5554', 'install', '-r', str(stage / 'installed-v1.apk')], check=True)
for name in ['next', 'wrong-signer', 'foreign', 'corrupt']:
    subprocess.run([adb, '-s', 'emulator-5554', 'push', str(stage / f'{name}.apk'), f'/data/local/tmp/dualkey-{name}.apk'], check=True)
print('Local emulator-only update fixtures are ready. They are excluded from Git and publication.')
