"""Exercise the full visible TV flow; only module calls/provider are substituted.
Last run uses the shipping Google IFrame adapter and records actual lab availability.
"""
import json
import shlex
import subprocess
import time
from pathlib import Path
import xml.etree.ElementTree as ET

PKG='com.slawa99.pockettv'
QA=next(Path('pocket-tv/build/outputs/apk/upgradeQa').glob('*.apk'))
REPORT=Path('pocket-tv/build/reports/native/video-tournament.txt')
REPORT.parent.mkdir(parents=True,exist_ok=True)
REPORT.write_text('')
def note(s):
    print(s,flush=True)
    with REPORT.open('a') as f: print(s,file=f)
def adb(*args, check=True):
    return subprocess.run(['adb',*map(str,args)],text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=45,check=check).stdout
def sh(*args,check=True): return adb('shell',shlex.join(args),check=check)
def priv(*args,check=True):return sh('run-as',PKG,*args,check=check)
def data(name):return priv('cat','files/'+name,check=False).strip()
def record():
    raw=priv('cat','shared_prefs/video_tournament.xml',check=False)
    try:
        return json.loads(ET.fromstring(raw).find("string[@name='run']").text)
    except (AttributeError,ValueError,ET.ParseError):return {}
def until(fun,seconds,why):
    deadline=time.monotonic()+seconds
    while time.monotonic()<deadline:
        result=fun()
        if result:return result
        time.sleep(.35)
    raise AssertionError(why+'; state='+repr(record())+'; events='+data('qa-events')+'; activity='+sh('dumpsys','activity','activities')[-1700:])
def key(name):
    sh('input','keyevent','KEYCODE_'+name);time.sleep(.24)
def screen():
    sh('uiautomator','dump','/sdcard/svp-video-window.xml',check=False)
    try:return list(ET.fromstring(sh('cat','/sdcard/svp-video-window.xml',check=False)).iter('node'))
    except ET.ParseError:return []
def focus(label, directions):
    target=label.casefold(); seen=[]
    for direction in [*directions,None]:
        nodes=screen()
        focused=[n.get('text','') for n in nodes if n.get('focused')=='true']
        seen.append(focused)
        if any(x.casefold()==target for x in focused):return
        if direction:key(direction)
    screenshot('video-focus-failure.png')
    raise AssertionError('D-pad could not focus '+label+'; focus='+str(seen)+'; visible='+str([n.get('text') for n in nodes if n.get('text')]))
def tap(label):
    # Android TV can consume the first touch just to move focus. Activate the
    # focused native control with OK; TAB cycles the standard AlertDialog buttons.
    focus(label,['TAB']*9);key('DPAD_CENTER')
def launch():sh('am','start','-W','-n',PKG+'/.MainActivity')
def open_tests():
    key('DPAD_LEFT')
    focus('Настройки',['DPAD_DOWN']*3+['DPAD_UP']*2);key('DPAD_CENTER')
    key('DPAD_RIGHT')
    focus('Расширенные настройки',['DPAD_DOWN']*4);key('DPAD_CENTER')
    focus('Подбор стратегий',['DPAD_DOWN']*3);key('DPAD_CENTER')
def screenshot(name):
    pic=Path('pocket-tv/screenshots')/name;pic.parent.mkdir(exist_ok=True)
    pic.write_bytes(subprocess.check_output(['adb','exec-out','screencap','-p']))
adb('uninstall',PKG,check=False)
assert 'Success' in adb('install',QA)
priv('sh','-c','mkdir -p files && touch files/qa-video-fixture')
launch();open_tests();tap('Подбор: YouTube');tap('Начать подбор')
until(lambda:record().get('p')=='playing',35,'Player never opened')
time.sleep(3);screenshot('video-visible-qa.png')
until(lambda:record().get('p') in ('done','stopped','recover','paused'),130,'No terminal state')
v=record();events=data('qa-events')
assert v['p']=='done' and v['win']=='C-fast' and data('qa-selection')=='C-fast', (v,events)
assert [x[0] for x in v['rows'] if x[1]=='pass']==['B-slow','C-fast'] and len(v['rows'])==5 and 'F-untested' not in events,(v,events)
note('Full YouTube flow, seven ranked candidates: first five exercised, two visible 20 s media passes. C-fast applied, B-slow retained. '+str(v['rows']))
screenshot('video-results-qa.png')
sh('am','force-stop',PKG);launch();open_tests()
assert record()['win']=='C-fast'
tap('Подбор: YouTube');tap('Только видеотест')
until(lambda:record().get('p')=='playing',35,'Saved ranking not reusable')
time.sleep(2);sh('input','keyevent','KEYCODE_HOME')
until(lambda:record().get('p')=='paused',15,'Leaving visible player failed to restore')
assert data('qa-selection')=='C-fast' and data('qa-live')=='C-fast' and data('qa-events').splitlines().count('network')==1
note('Saved video ranking survived process restart. Home during a retest restored C-fast without a second network scan.')
priv('touch','files/qa-second-group')
launch();open_tests();tap('Подбор: YouTube');tap('Продолжить видеотест')
until(lambda:record().get('p') in ('playing','switch','ready'),35,'Could not resume next group')
until(lambda:record().get('p') in ('done','stopped','recover','paused'),95,'Second group never finished')
v=record()
assert v['p']=='done' and v['win']=='F-untested' and len(v['rows'])==7 and data('qa-live')=='F-untested',v
note('No strategy passed in first five: second group ran automatically and the sixth strategy was applied without repeating the network scan.')
# Upstream YouTube is outside CI control. Do not silently substitute local media
# or label a blocked embed, timeout or rate limit as a successful real playback.
sh('am','force-stop',PKG); priv('touch','files/qa-real-player')
launch();open_tests();tap('Подбор: YouTube');tap('Только видеотест')
until(lambda:record().get('p') in ('playing','switch'),30,'Shipping player not started')
until(lambda:record().get('p') in ('done','stopped','recover','paused'),180,'Shipping player did not return')
live=record()
assert live.get('p') in ('done','stopped','paused'),live
note('Actual YouTube IFrame, no Magisk or Russian ISP on this AOSP emulator: '+str({k:live.get(k) for k in ['p','msg','rows','win']}))
adb('uninstall',PKG)
