"""Exercise the full visible TV flow; only module calls/provider are substituted.
Last run uses the shipping Google IFrame adapter and records actual lab availability.
"""
import json
import shlex
import subprocess
import sys
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
def failure_trace(ty,value,tb):
    try:
        log=adb('logcat','-d','-s','SVVideo:I','AndroidRuntime:E')
        print(log[-5500:],flush=True)
        (REPORT.parent/'video-logcat.txt').write_text(log[-20000:])
        screenshot('video-failure-qa.png')
    finally:sys.__excepthook__(ty,value,tb)
sys.excepthook=failure_trace
sh('settings','put','system','accelerometer_rotation','0')
sh('settings','put','system','user_rotation','0')
sh('wm','size','1920x1080');sh('wm','density','320')
adb('uninstall',PKG,check=False)
assert 'Success' in adb('install',QA)
priv('sh','-c','mkdir -p files && touch files/qa-video-fixture')
launch();open_tests();tap('Подбор: YouTube');tap('Начать подбор')
until(lambda:record().get('p')=='playing',35,'Player never opened')
time.sleep(3);screenshot('video-visible-qa.png')
until(lambda:record().get('p') in ('done','stopped','recover','paused'),240,'No terminal state')
v=record();events=data('qa-events')
passes=[x for x in v['rows'] if x[1]=='pass']
best=min(passes,key=lambda x:x[2])[0]
assert v['p']=='done' and v['win']==best and data('qa-selection')==best,(v,events)
assert [x[0] for x in passes]==['B-slow','C-mid','E','F-late','G-reserve'] and len(v['rows'])==8,(v,events)
assert events.splitlines()[-2:]==['try H-last','commit '+best],events
assert any(row[:2]==['D','player_error'] for row in v['rows']),v
assert v['clip']==1 and '101' in v['firstClipError'] and events.count('try B-slow')==2,(v,events)
note('Three passes in the first five. Candidates six and seven also passed, then candidate eight was tried before committing the fastest of five. '+str(v['rows']))
screenshot('video-results-qa.png')
sh('am','force-stop',PKG);launch();open_tests()
assert record()['win']==best
tap('Подбор: YouTube');tap('Только видеотест')
until(lambda:(lambda r:r.get('p')=='playing' and r.get('clip')==1)(record()),45,'Saved ranking not reusable')
time.sleep(2);sh('input','keyevent','KEYCODE_HOME')
until(lambda:record().get('p')=='paused',15,'Leaving visible player failed to restore')
assert data('qa-selection')==best and data('qa-live')==best and data('qa-events').splitlines().count('network')==1
note('Saved video ranking survived process restart. Home during a retest restored the previous winner without a second network scan.')
priv('touch','files/qa-second-group')
launch();open_tests();tap('Подбор: YouTube');tap('Продолжить видеотест')
until(lambda:record().get('p') in ('playing','switch','ready'),35,'Could not resume next group')
until(lambda:record().get('p') in ('done','stopped','recover','paused'),95,'Second group never finished')
v=record()
assert v['p']=='stopped' and not v['win'] and len(v['rows'])==8 and data('qa-live')==data('qa-selection')==best,v
assert [row[0] for row in v['rows'] if row[1]=='pass']==['G-reserve'],v
assert 'вручную' in v['msg'] and data('qa-events').splitlines().count('network')==1,v
assert [line for line in data('qa-events').splitlines() if line.startswith('commit ')]==['commit '+best]
note('One pass across eight candidates. No second commit, previous winner restored, manual result retained, no network rerun.')
# Upstream YouTube is outside CI control. Do not silently substitute local media
# or label a blocked embed, timeout or rate limit as a successful real playback.
sh('am','force-stop',PKG); priv('touch','files/qa-real-player')
launch();open_tests();tap('Подбор: YouTube');tap('Только видеотест')
until(lambda:record().get('p') in ('playing','switch'),30,'Shipping player not started')
until(lambda:record().get('p') in ('done','stopped','recover','paused'),180,'Shipping player did not return')
live=record()
assert live.get('p') in ('done','stopped','paused'),live
note('Actual YouTube IFrame, no Magisk or Russian ISP on this AOSP emulator: '+str({k:live.get(k) for k in ['p','msg','rows','win','clip','firstClipError']}))
adb('uninstall',PKG)
