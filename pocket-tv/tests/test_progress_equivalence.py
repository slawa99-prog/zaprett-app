"""Compare complete status replies from this release and the deployed bridge, without network access."""
import argparse
import os
from pathlib import Path
import statistics
import subprocess
import tempfile
import time

HERE = Path(__file__).resolve().parents[1]
CURRENT = HERE / 'src/main/assets/pocket_bridge.sh'
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('previous', type=Path)
parser.add_argument('--measure', action='store_true')
opt = parser.parse_args()
bridges = [opt.previous.resolve(), CURRENT]
with tempfile.TemporaryDirectory() as td:
    root = Path(td)
    sd = root/'session'
    sd.mkdir()
    env = dict(os.environ, POCKET_TV_BASE=td, POCKET_TV_MODULE=str(root/'module'),
               POCKET_TV_STAGING=str(root/'staging'), POCKET_TV_SHELL='/bin/sh')
    def call(bridge, args):
        return subprocess.run(['/bin/sh',bridge,*args],env=env,check=True,timeout=10,
                              stdout=subprocess.PIPE,stderr=subprocess.PIPE).stdout
    def same():
        for args in (['poll'], ['state','quick']):
            a,b=(call(bridge,args) for bridge in bridges)
            assert a==b, (args,a[-2000:],b[-2000:])
        return a
    assert b'@@STATE idle' in same()
    stat = Path('/proc/self/stat').read_text().rsplit(') ',1)[1].split()
    owner = f"{os.getpid()} {stat[19]} {Path('/proc/sys/kernel/random/boot_id').read_text().strip()}"
    for name,val in {'id':'run-300','state':'running','total':'300','started':'1790000000','profile':'youtube',
                     'owner':owner,'module-version':'v72','catalog-id':'a'*64}.items():
        (sd/name).write_text(val+'\n')
    (sd/'test.log').write_text(''.join(f'@@TESTING ALT{i}\n'+
                        (f'@@RESULT SKIP 0 0 0 0 0 0 ALT{i}\n' if i%4==0 else f'@@RESULT OK 3 4 4 4 2 4 ALT{i}\n') for i in range(300)))
    assert b'@@STATE running' in same()
    (sd/'owner').write_text(f'{os.getpid()} 1 old-boot\n')
    assert b'@@STATE interrupted' in same()
    (sd/'owner').write_text(owner+'\n')
    (sd/'cancel').touch()
    for state in ['cancelling','restoring','cancelled','failed','completed']:
        (sd/'state').write_text(state)
        (sd/'restore-step').write_text('start' if state=='restoring' else '')
        (sd/'error').write_bytes(b'  details\\literal\nsecond line  ')
        (sd/'recovery.log').write_text('recovery log\n')
        assert f'@@STATE {state}\n'.encode() in same()
    if opt.measure:
        (sd/'state').write_text('running\n')
        (sd/'error').unlink()
        (sd/'cancel').unlink()
        assert b'@@STATE running' in same()
        times=[[],[]]
        for i in range(25):
            for j in ((0,1) if i%2==0 else (1,0)):
                start=time.perf_counter()
                call(bridges[j],['state','quick'])
                times[j].append((time.perf_counter()-start)*1000)
        print('300-row active progress on this host (median of 25 interleaved reads per build, no network), old/new ms:',*(round(statistics.median(v),1) for v in times))
print('Old and new poll + quick-state reports are byte-identical for 8 states, including SKIP, partial metadata, recovery and old owner.')
