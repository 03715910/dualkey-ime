"""Use only the task's emulator to verify settings survive an actual same-key APK update."""
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from package_release import ROOT
import os

sdk = Path(os.environ.get('ANDROID_HOME') or Path.home() / 'AppData/Local/Android/Sdk')
adb = str(sdk / 'platform-tools' / ('adb.exe' if os.name == 'nt' else 'adb'))
serial = 'emulator-5554'
package = 'io.github.user03715910.dualkey'

def call(*args):
    return subprocess.check_output([adb, '-s', serial, *args], text=True, encoding='utf-8')

def snapshot():
    call('shell', 'uiautomator', 'dump', '/sdcard/dualkey-retention.xml')
    return ET.fromstring(call('shell', 'cat', '/sdcard/dualkey-retention.xml'))

def node(label):
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        for item in snapshot().iter('node'):
            if item.attrib.get('text') == label:
                return item
        time.sleep(.3)
    raise RuntimeError('Setting not visible: ' + label)

def set_checked(label, checked):
    item = node(label)
    if item.attrib['checked'] != str(checked).lower():
        x1, y1, x2, y2 = map(int, re.findall(r'\d+', item.attrib['bounds']))
        call('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
        time.sleep(.3)
    assert node(label).attrib['checked'] == str(checked).lower()

if call('shell', 'getprop', 'ro.kernel.qemu').strip() != '1':
    raise SystemExit('This script only operates on an emulator.')
call('shell', 'am', 'start', '-S', '-n', package + '/io.github.user03715910.dualkey.settings.MainActivity')
set_checked('初期モードを英語にする', True)
set_checked('数字専用行を表示', False)
before = snapshot()
(ROOT / '.local/update-test/before-update.xml').write_bytes(ET.tostring(before, encoding='utf-8'))
print(call('install', '-r', str(ROOT / '.local/update-test/next.apk')).strip())
call('shell', 'am', 'start', '-S', '-n', package + '/io.github.user03715910.dualkey.settings.MainActivity')
assert node('初期モードを英語にする').attrib['checked'] == 'true'
assert node('数字専用行を表示').attrib['checked'] == 'false'
after = snapshot()
(ROOT / '.local/update-test/after-update.xml').write_bytes(ET.tostring(after, encoding='utf-8'))
print('PASS: versionCode 1 → 2 retained initial English mode and the hidden number row.')
