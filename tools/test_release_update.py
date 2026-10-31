"""Verify all settings across an APK update, only on this project's disposable emulator."""
import argparse
import os
from pathlib import Path
import re
import subprocess
from package_release import ROOT, sdk_tool


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--after', required=True, type=Path)
    parser.add_argument('--test-apk', type=Path, default=ROOT / 'app/build/outputs/apk/androidTest/release/app-release-androidTest.apk')
    parser.add_argument('--serial', default='emulator-5554')
    parser.add_argument('--public-feed', action='store_true', help='Also download and validate the real published update before installing')
    args = parser.parse_args()
    sdk = Path(os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT') or Path.home() / 'AppData/Local/Android/Sdk')
    adb = str(sdk / 'platform-tools' / ('adb.exe' if os.name == 'nt' else 'adb'))

    def command(*parts):
        return subprocess.check_output([adb, '-s', args.serial, *map(str, parts)], text=True, encoding='utf-8')

    # Resolve the named AVD before any install or setting change. Real devices are rejected.
    if not args.serial.startswith('emulator-') or command('shell', 'getprop', 'ro.kernel.qemu').strip() != '1':
        raise SystemExit('Use only the project\'s disposable emulator.')
    if command('emu', 'avd', 'name').splitlines()[0].strip() != 'DualKeyTest':
        raise SystemExit('Only the dedicated DualKeyTest AVD may be modified.')

    apk = args.after.resolve(strict=True)
    badging = subprocess.check_output([sdk_tool('aapt'), 'dump', 'badging', str(apk)], text=True, encoding='utf-8')
    package = re.search(r"package: name='([^']+)' versionCode='(\d+)'", badging)
    if not package or package[1] != 'io.github.user03715910.dualkey' or 'application-debuggable' in badging:
        raise SystemExit('The update must be a non-debug DualKey release APK.')
    version_code = package[2]
    installed = command('shell', 'dumpsys', 'package', package[1])
    before_code = re.search(r'\bversionCode=(\d+)', installed)
    if not before_code or int(before_code[1]) >= int(version_code):
        raise SystemExit('Install an older signed DualKey release first. This script never removes existing data to downgrade.')

    log = []

    def run_test(method):
        output = command('shell', 'am', 'instrument', '-w', '-r', '-e', 'publicUpdate', 'true',
            '-e', 'publishedVersionCode', version_code, '-e', 'class',
            'io.github.user03715910.dualkey.PublishedUpdateDeviceTest#' + method,
            'io.github.user03715910.dualkey.test/androidx.test.runner.AndroidJUnitRunner')
        log.append(output)
        if 'OK (1 test)' not in output or 'FAILURES' in output or 'INSTRUMENTATION_FAILED' in output:
            raise RuntimeError('Release update test failed: ' + method + '\n' + output)
        print('PASS:', method)

    try:
        command('install', '-r', args.test_apk.resolve(strict=True))
        run_test('seedEverySettingAndDictionary')
        if args.public_feed:
            run_test('downloadAndVerifyPublishedUpdate')
        # ADB performs a same-signature update; this does not prove completion in the phone installer UI.
        log.append(command('install', '-r', apk))
        run_test('retainsEverySettingAndDictionaryAfterUpdate')
        print(f'PASS: release versionCode {before_code[1]} -> {version_code}; all 21 saved settings/dictionary entries retained.')
        print('Installation used ADB. User confirmation in Android\'s installer must be checked separately.')
    finally:
        (ROOT / 'release-update-retention.log').write_text('\n'.join(log), encoding='utf-8')


if __name__ == '__main__':
    main()
