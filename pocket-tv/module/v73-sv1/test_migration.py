#!/usr/bin/env python3
"""Exercise the complete packaged installer in a disposable tree, without root/network.
The only patched paths are Magisk's two fixed data paths and the two kernel feature
files. Magisk framework/Android-only calls and the on-device curl probe are fixtures.
Boot/services, netfilter and external programs from input archives are never executed.
"""
import argparse
from hashlib import sha256
import os
from pathlib import Path
from tempfile import TemporaryDirectory
import subprocess
from zipfile import ZipFile

def hash_file(p): return sha256(p.read_bytes()).hexdigest()
def check(ok,msg):
    if not ok: raise AssertionError(msg)

def run(args):
    pkg,old,stock,sv3=[ZipFile(p) for p in (args.module,args.stock72,args.stock73,args.sv3)]
    fast=(Path(__file__).resolve().parents[2]/'src/main/assets/fast_network_wait.sh').read_bytes()
    check(sha256(old.read('common.sh')+fast).hexdigest()=='368bc0d99c816790251048c74bd2d1782534af42267e4d629a3b062021a47a44','wrong fast-start fixture')
    abis={'armeabi-v7a':('arm','arm','arm'),'arm64-v8a':('aarch64','arm64','aarch64'),
          'x86':('x86','i386','x86'),'x86_64':('x86_x64','x86_64','x86_64')}
    prof={p for p in stock.namelist() if p.startswith('zapret/') and p.count('/')==1 and p.endswith('.sh') and p not in ('zapret/zapret.sh','zapret/make-unkillable.sh')}
    check(len(prof)==300 and sum(n.endswith('_z2.sh') for n in prof)==125,'bad profile inventory')
    check(all(pkg.read(n)==stock.read(n) for n in prof), 'upstream profiles changed')
    for n in ('sv-curl.sh','sv-process.sh','sv-refresh.sh','service.sh','uninstall.sh','dnscrypt/dnscrypt.sh','system/bin/zapret','zapret/zapret.sh'):
        check(pkg.read(n)==sv3.read(n), n+' differs from tested v72-sv3')
    # One comment in sv-lists documents the additional upgrade path; executable lines are unchanged.
    lines=lambda z:[l for l in z.read('sv-lists.sh').splitlines() if not l.lstrip().startswith(b'#')]
    check(lines(pkg)==lines(sv3),'runtime list processing changed')
    for n in stock.namelist():
        if n.startswith('lua/') or n.startswith('zapret/nfqws') or n.startswith('dnscrypt/dnscrypt-proxy-'):
            check(stock.read(n)==pkg.read(n),'upstream runtime differs: '+n)
    for arch,(_,__,suffix) in abis.items():
        n='curl-'+suffix; data=pkg.read(n); check(data==sv3.read(n),'native curl changed:'+arch)
        with TemporaryDirectory() as td:
            f=Path(td)/n;f.write_bytes(data)
            dynamic=subprocess.check_output(['readelf','-d',str(f)],text=True)
            deps={x.split('[')[-1].split(']')[0] for x in dynamic.splitlines() if '(NEEDED)' in x}
            check(deps=={'libc.so','libdl.so','libm.so'},'unexpected native dependencies: '+str(deps))
    print('Release: 300 upstream profiles (125 z2), both engines, Lua, DNSCrypt, and every v72-sv3 runtime fix verified; all four curl ELFs checked.')
    raw='.service/ipset-service.txt'
    fakecurl=b'#!/bin/sh\necho "curl host fixture OpenSSL/qa"; echo "Protocols: http https"\n'
    cases=[
        ('v72-sv3 fast + stock seed','sv3','armeabi-v7a','stock',True,None),
        ('stock v72 + explicit default URL','72','arm64-v8a','default_url',False,None),
        ('stock v73 + downloaded seed','73','x86','downloaded',False,None),
        ('sv73 reinstall + custom source','new','x86_64','custom',True,None),
        ('sv3 + missing custom cache with usable active list','sv3','armeabi-v7a','missing_custom',True,None),
        ('unknown future version','future','arm64-v8a','stock',False,'only verified'),
        ('unrecognized previous scripts','unknown','x86_64','stock',False,'unrecognized common'),
        ('linked settings file','sv3','armeabi-v7a','stock',True,'cannot copy linked'),
        ('incomplete engine','sv3','armeabi-v7a','stock',True,'missing or empty component'),
        ('missing Lua library','sv3','armeabi-v7a','stock',True,'missing or empty component'),
        ('failed native probe','sv3','armeabi-v7a','stock',True,'did not start'),
        ('pending reboot','sv3','arm64-v8a','stock',True,'pending operation'),
        ('fresh incomplete ZIP','fresh','armeabi-v7a','stock',False,'missing or empty component'),
        ('fresh companion fallback','fresh','arm64-v8a','stock',False,None),
        ('fresh install','fresh','armeabi-v7a','stock',False,None),
    ]
    for label,kind,arch,mode,quick,fail in cases:
      with TemporaryDirectory() as td:
        root=Path(td); live=root/'active'; stage=root/'staged'; tools=root/'bin'; tools.mkdir()
        # ZipFile.extractall only handles this build; reject a path or duplicate before extraction.
        names=pkg.namelist()
        check(len(names)==len(set(names)) and all(not Path(n).is_absolute() and '..' not in Path(n).parts for n in names),'bad paths')
        pkg.extractall(stage)
        def write(base,name,data):
            f=base/name; f.parent.mkdir(parents=True,exist_ok=True); f.write_bytes(data); return f
        oldraw=old.read(raw); currentraw=stock.read(raw)
        check(oldraw!=currentraw,'two distinct seed generations required')
        if kind!='fresh':
            z={'sv3':sv3,'72':old,'73':stock,'new':pkg,'future':stock,'unknown':stock}[kind]
            meta=z.read('module.prop')
            if kind=='future': meta=meta.replace(b'versionCode=73',b'versionCode=74')
            write(live,'module.prop',meta)
            write(live,'common.sh',z.read('common.sh')+(fast if quick else b'')+(b'# not from release\n' if kind=='unknown' else b''))
            write(live,'system/bin/zapret',z.read('system/bin/zapret'))
            write(live,'config/current-strategy',b'2alt3\n');write(live,'config/ipset-filter',b'any\n')
            write(live,'strategy/custom profile.sh',b'# custom override\n');write(live,'strategy/2alt3.sh',b'# local profile for selected name\n')
            write(live,'list/ipset-all-user.txt',b'192.0.2.7/32\n');write(live,'list/ipset-all.txt',b'198.51.100.6/32\n')
            write(live,'dnscrypt/blocked-names-user.txt',b'local.invalid\n')
            write(live,'.service/state/previous.pid',b'123\n')
            if mode.startswith('custom') or mode=='missing_custom':write(live,'config/ipset-link',b'https://example.invalid/my-list\n')
            if mode=='default_url':write(live,'config/ipset-link',b'https://raw.githubusercontent.com/sevcator/zapret-lists/refs/heads/main/ipset-service.txt\n')
            if mode!='missing_custom':write(live,raw,b'198.51.100.16/28\n' if mode=='downloaded' else oldraw)
        if label=='linked settings file':
            write(root,'outside',b'must not change\n');(live/'config/link').symlink_to(root/'outside')
        if label=='pending reboot':write(live,'update',b'')
        suffix,dns,curl=abis[arch]
        if label=='incomplete engine': (stage/f'zapret/nfqws2-{suffix}').unlink()
        if label in ('missing Lua library','fresh incomplete ZIP'): (stage/'lua/zapret-antidpi.lua').write_bytes(b'')
        (stage/('curl-'+curl)).write_bytes(b'#!/bin/sh\nexit 1\n' if label=='failed native probe' else fakecurl)
        (root/'kernel-targets').write_text('NFQUEUE\n')
        script=(stage/'customize.sh').read_text()
        for text,new in [('/data/adb/modules/zapret',str(live)),('/data/adb/modules_update/zapret',str(stage)),('/data/local/tmp/sv-pocket-v73.',str(root/'sv-pocket-v73.')),('/proc/net/ip_tables_targets',str(root/'kernel-targets')),('/proc/net/ip6_tables_targets',str(root/'kernel-targets'))]:
            check(text in script,'fixture relocation no longer matches '+text);script=script.replace(text,new)
        (root/'installer.sh').write_text(script)
        for cmd in ('mount','settings','pm','iptables','ip6tables','chcon'):
            fake=tools/cmd;fake.write_text('#!/bin/sh\necho '+cmd+' >> "$CALLS"\n'+('echo package:be.mygod.vpnhotspot\n' if cmd=='pm' else 'exit 0\n'));fake.chmod(0o755)
        if label=='fresh companion fallback':
            (tools/'pm').write_text('#!/bin/sh\necho "pm $1" >> "$CALLS"\n[ "$1" = list ] && exit 0\n[ -s "$2" ] || exit 2\nexit 1\n')
        wrapper='''abort() { echo "$*" >&2; exit 1; }; ui_print() { echo "$*"; }
set_perm_recursive() { :; }; grep_get_prop() { echo "$ARCH_FOR_TEST"; }
. "$1"
'''
        env=dict(os.environ,MODPATH=str(stage),ARCH_FOR_TEST=arch,CALLS=str(root/'calls'),PATH=str(tools)+':'+os.environ['PATH'])
        def snapshot():
            return {p.relative_to(live):(os.readlink(p) if p.is_symlink() else hash_file(p)) for p in live.rglob('*') if p.is_symlink() or p.is_file()}
        before=snapshot()
        r=subprocess.run(['/bin/sh','-c',wrapper,'install',str(root/'installer.sh')],env=env,text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=45)
        check(before==snapshot(),label+': live files changed')
        if kind!='fresh' or fail:check(not (root/'calls').exists(),label+': installer called a device setting or live service')
        if fail:
            check(r.returncode!=0 and fail in r.stdout,(label,r.returncode,r.stdout))
            if label=='linked settings file':check((root/'outside').read_bytes()==b'must not change\n',label)
        else:
            check(r.returncode==0,(label,r.stdout))
            for n,original in [('zapret/nfqws','zapret/nfqws-'+suffix),('zapret/nfqws2','zapret/nfqws2-'+suffix),('dnscrypt/dnscrypt-proxy','dnscrypt/dnscrypt-proxy-'+dns)]:
                check((stage/n).read_bytes()==stock.read(original),(label,n))
            check((stage/'curl').read_bytes()==sv3.read('sv-curl.sh') and (stage/'curl.bin').read_bytes()==fakecurl,label+': curl wrapper/ABI')
            if mode=='missing_custom':
                check(not (stage/raw).exists() and (stage/'list/ipset-all.txt').read_bytes()==b'198.51.100.6/32\n',label)
            else:
                expected=b'198.51.100.16/28\n' if mode=='downloaded' else oldraw if mode=='custom' else currentraw
                check((stage/raw).read_bytes()==expected,label+': wrong IP seed')
            if label=='fresh companion fallback':
                check((stage/'system/app/VpnHotspot.apk').is_file() and not list(root.glob('sv-pocket-v73.*')),label+': missing fallback or leaked temp file')
                check('pm install' in (root/'calls').read_text(),label+': did not exercise PackageManager')
            if kind!='fresh':
                for n in ('config/current-strategy','strategy/custom profile.sh','strategy/2alt3.sh','list/ipset-all-user.txt','dnscrypt/blocked-names-user.txt','common.sh'):
                    check((stage/n).read_bytes()==(live/n).read_bytes(),(label,n))
                check(not (stage/'.service/state/previous.pid').exists(),label)
        print('PASS:',label)
    print(f'{len(cases)} installer scenarios passed (Android interfaces simulated).')

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    for arg in ('module','stock72','stock73','sv3'):p.add_argument('--'+arg,required=True,type=Path)
    run(p.parse_args())
