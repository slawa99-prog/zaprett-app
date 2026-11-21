"""Real Android package replacement; do not start 1.2.10, send the broadcast or force its job."""
from pathlib import Path
import shlex
import subprocess
import time
import xml.etree.ElementTree as ET

PKG = 'com.slawa99.pockettv'
PREVIOUS = next(Path('qa-previous/pocket-tv/build/outputs/apk/debug').glob('*.apk'))
CURRENT = next(Path('pocket-tv/build/outputs/apk/debug').glob('*.apk'))
QA = next(Path('pocket-tv/build/outputs/apk/upgradeQa').glob('*.apk'))
REPORT = Path('pocket-tv/build/reports/native/apk-upgrade.txt')
REPORT.parent.mkdir(parents=True, exist_ok=True)


def adb(*args, check=True):
    return subprocess.run(['adb', *map(str, args)], text=True, stdout=subprocess.PIPE,
                          stderr=subprocess.STDOUT, check=check, timeout=75).stdout


def shell(*args, check=True):
    return adb('shell', shlex.join(args), check=check)


def priv(*args, check=True):
    return shell('run-as', PKG, *args, check=check)


def prefs():
    try:
        return {e.get('name'): e.get('value', e.text or '') for e in
                ET.fromstring(priv('cat', 'shared_prefs/friendly.xml', check=False))}
    except ET.ParseError:
        return {}


def note(msg):
    print(msg, flush=True)
    with REPORT.open('a') as f:
        print(msg, file=f)


def old_install():
    adb('uninstall', PKG, check=False)
    assert 'Success' in adb('install', PREVIOUS)
    assert 'versionCode=15 ' in shell('dumpsys', 'package', PKG)
    shell('am', 'start', '-W', '-n', PKG+'/.MainActivity')
    shell('input', 'keyevent', 'KEYCODE_HOME')
    shell('am', 'kill', PKG)  # force-stop would intentionally suppress background work.
    priv('sh', '-c', 'mkdir -p files; echo ALT12 > files/qa-selected; echo off > files/qa-mode')


def replaced(apk):
    assert 'Success' in adb('install', '-r', apk)
    assert 'versionCode=16 ' in shell('dumpsys', 'package', PKG)
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        data = prefs()
        if data.get('fast_start_last', '').startswith('После обновления APK'):
            break
        time.sleep(1)
    else:
        raise AssertionError('No automatic result from real package replacement; '+str(prefs())+
                             '\n'+shell('cmd', 'jobscheduler', 'get-job-state', PKG, str(0x535650), check=False))
    resumed = [x for x in shell('dumpsys', 'activity', 'activities').splitlines()
               if 'mResumedActivity' in x or 'topResumedActivity' in x]
    assert resumed and PKG not in '\n'.join(resumed), resumed
    assert priv('cat', 'files/qa-selected').strip() == 'ALT12'
    assert data['fast_job_attempts'] == '1', data
    return data['fast_start_last']


REPORT.write_text('')
old_install()
result = replaced(CURRENT)  # No grant for the production app on this AOSP emulator.
assert 'не применён' in result or 'нет активного модуля' in result, result
note('Shipped variant: real 1.2.9 → 1.2.10 replacement; closed app ran once, handled unavailable root/module, retained data.')
old_install()
result = replaced(QA)
assert result == 'После обновления APK — включён', result
assert priv('cat', 'files/qa-mode').strip() == 'on'
note('Grant fixture: real 1.2.9 → 1.2.10 replacement enabled fast mode without opening the new UI or forcing a job.')
adb('uninstall', PKG)
