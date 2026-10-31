"""Create one private release key. Passwords only enter keytool through its environment."""
import base64
import getpass
import hashlib
import os
from pathlib import Path
import secrets
import shutil
import subprocess

root = Path(__file__).resolve().parents[1]
local = root / '.local'
key = local / 'release.p12'
properties = local / 'signing.properties'
if key.exists() or properties.exists():
    raise SystemExit('Signing material already exists; refusing to overwrite it.')
local.mkdir(exist_ok=True)
if os.name == 'nt':
    subprocess.run(['icacls', str(local), '/inheritance:r', '/grant:r', f'{getpass.getuser()}:(OI)(CI)F'], check=True, capture_output=True)
else:
    local.chmod(0o700)
java_home = Path(os.environ.get('JAVA_HOME', r'C:\Program Files\Android\Android Studio\jbr'))
keytool = str(java_home / 'bin' / ('keytool.exe' if os.name == 'nt' else 'keytool'))
if not Path(keytool).exists():
    keytool = shutil.which('keytool') or ''
if not keytool:
    raise SystemExit('Set JAVA_HOME to Android Studio jbr first.')
password = secrets.token_urlsafe(48)
env = dict(os.environ, DUALKEY_KEYPASS=password)
subprocess.run([keytool, '-genkeypair', '-keystore', str(key), '-storetype', 'PKCS12',
    '-storepass:env', 'DUALKEY_KEYPASS', '-keypass:env', 'DUALKEY_KEYPASS', '-alias', 'dualkey-release',
    '-keyalg', 'RSA', '-keysize', '3072', '-validity', '10000', '-dname', 'CN=DualKey IME Release', '-noprompt'],
    env=env, check=True, capture_output=True)
cert = subprocess.run([keytool, '-exportcert', '-keystore', str(key), '-storepass:env', 'DUALKEY_KEYPASS',
    '-alias', 'dualkey-release'], env=env, check=True, capture_output=True).stdout
fingerprint = hashlib.sha256(cert).hexdigest()
properties.write_text(f'storeFile=.local/release.p12\nstorePassword={password}\nkeyAlias=dualkey-release\nkeyPassword={password}\ncertSha256={fingerprint}\n', encoding='utf-8')
if os.name != 'nt':
    key.chmod(0o600)
    properties.chmod(0o600)
print('Created a private key in .local/. Back up BOTH release.p12 and signing.properties securely.')
print('Public signing certificate SHA-256:', fingerprint)
