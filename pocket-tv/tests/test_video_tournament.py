"""Exercise the full visible TV flow; only module calls/provider are substituted.
Last run uses the shipping Google IFrame adapter and records actual lab availability.
"""
import json
import re
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
def tap(label):
    last=[]
    def find():
        sh('uiautomator','dump','/sdcard/svp-video-window.xml',check=False)
        raw=sh('cat','/sdcard/svp-video-window.xml',check=False)
        try:root=ET.fromstring(raw)
        except ET.ParseError:return None
        last[:]=[n.get('text','') for n in root.iter('node') if n.get('text')]
        for n in root.iter('node'):
            if n.get('text','').strip().casefold()!=label.casefold():continue
            xy=[int(t) for t in re.findall(r'\d+',n.get('bounds',''))]
            if len(xy)==4 and xy[2]>xy[0] and xy[3]>xy[1]:return (str((xy[0]+xy[2])//2),str((xy[1]+xy[3])//2))
        return None
    try:x,y=until(find,20,'Missing '+label)
    except AssertionError:
        print('Visible:',last,flush=True);raise
    sh('input','tap',x,y)
def launch():sh('am','start','-W','-n',PKG+'/.MainActivity')
def open_tests():
    tap('Настройки');tap('Расширенные настройки');tap('Подбор стратегий')
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
# Upstream YouTube is outside CI control. Do not silently substitute local media
# or label a blocked embed, timeout or rate limit as a successful real playback.
sh('am','force-stop',PKG); priv('touch','files/qa-real-player')
launch();open_tests();tap('Подбор: YouTube');tap('Продолжить видеотест')
until(lambda:record().get('p') in ('playing','switch'),30,'Shipping player not started')
until(lambda:record().get('p') in ('done','stopped','recover','paused'),180,'Shipping player did not return')
live=record()
assert live.get('p') in ('done','stopped','paused'),live
note('Actual YouTube IFrame, no Magisk or Russian ISP on this AOSP emulator: '+str({k:live.get(k) for k in ['p','msg','rows','win']}))
adb('uninstall',PKG)
