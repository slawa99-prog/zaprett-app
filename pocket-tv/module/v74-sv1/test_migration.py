#!/usr/bin/env python3
"""Exercise the complete packaged installer in a disposable tree, without root/network.
The only patched paths are Magisk's two fixed data paths and the two kernel feature
files. Magisk framework/Android-only calls and the on-device curl probe are fixtures.
Boot/services, netfilter and external programs from input archives are never executed.
"""
import argparse
from hashlib import sha256
import os
import re
from pathlib import Path
from tempfile import TemporaryDirectory
import subprocess
from zipfile import ZipFile

def hash_file(p): return sha256(p.read_bytes()).hexdigest()
def check(ok,msg):
    if not ok: raise AssertionError(msg)

def run(args):
    pkg,old,stock73,stock,sv73=[ZipFile(p) for p in (args.module,args.stock72,args.stock73,args.stock74,args.sv73)]
    fast=(Path(__file__).resolve().parents[2]/'src/main/assets/fast_network_wait.sh').read_bytes()
    check(sha256(old.read('common.sh')+fast).hexdigest()=='368bc0d99c816790251048c74bd2d1782534af42267e4d629a3b062021a47a44','wrong fast-start fixture')
    abis={'armeabi-v7a':('arm','arm','arm'),'arm64-v8a':('aarch64','arm64','aarch64'),
          'x86':('x86','i386','x86'),'x86_64':('x86_x64','x86_64','x86_64')}
    prof={p for p in stock.namelist() if p.startswith('zapret/') and p.count('/')==1 and p.endswith('.sh') and p not in ('zapret/zapret.sh','zapret/make-unkillable.sh')}
    check(len(prof)==300 and sum(n.endswith('_z2.sh') for n in prof)==125,'bad profile inventory')
    check(all(pkg.read(n)==stock.read(n) for n in prof), 'upstream profiles changed')
    for n in ('sv-curl.sh','sv-process.sh','sv-refresh.sh','service.sh','uninstall.sh','dnscrypt/dnscrypt.sh','system/bin/zapret','zapret/zapret.sh'):
        check(pkg.read(n)==sv73.read(n), n+' differs from tested v73-sv1')
    # The v74 list seeding adjustment is installer-only; all runtime list methods stay identical.
    runtime=lambda z:z.read('sv-lists.sh').split(b'sv_seed_lists()')[0]+z.read('sv-lists.sh').split(b'sv_schedule_refresh()')[1]
    check(runtime(pkg)==runtime(sv73),'boot list processing changed')
    references={v for p in prof if p.endswith('_z2.sh') for v in re.findall(rb'--lua-desync=([\w-]+)',pkg.read(p))}
    defined=set(re.findall(rb'(?m)^function\s+(\w+)\(',pkg.read('lua/zapret-antidpi.lua')))
    check(references and references<=defined,'undefined Lua functions: '+str(references-defined))
    cli=pkg.read('system/bin/zapret')
    check(b'for _lua in "$LUA_DIR/zapret-lib.lua" "$LUA_DIR/zapret-antidpi.lua"' in cli and b'"$@"' in cli[cli.index(b'set -- "$@" "--lua-init='):], 'tester missing Lua initialization')
    dnsbase='dnscrypt/cloaking-rules.txt'
    dif=set(stock.read(dnsbase).splitlines())-set(stock73.read(dnsbase).splitlines())
    check(len(dif)==8 and all(b'discordapp.net ' in x for x in dif),'wrong upstream DNS records')
    check(sum(bool(x.strip()) and not x.startswith(b'#') for x in pkg.read('.service/ipset-service.txt').splitlines())==54267,'wrong v74 IP list')
    for n in stock.namelist():
        if n.startswith('lua/') or n.startswith('zapret/nfqws') or n.startswith('dnscrypt/dnscrypt-proxy-'):
            check(stock.read(n)==pkg.read(n),'upstream runtime differs: '+n)
    for arch,(_,__,suffix) in abis.items():
        n='curl-'+suffix; data=pkg.read(n); check(data==sv73.read(n),'native curl changed:'+arch)
        with TemporaryDirectory() as td:
            f=Path(td)/n;f.write_bytes(data)
            dynamic=subprocess.check_output(['readelf','-d',str(f)],text=True)
            deps={x.split('[')[-1].split(']')[0] for x in dynamic.splitlines() if '(NEEDED)' in x}
            check(deps=={'libc.so','libdl.so','libm.so'},'unexpected native dependencies: '+str(deps))
    print('Release: 300 upstream profiles (125 z2), both engines, Lua, DNSCrypt, and every v73-sv1 runtime fix verified; all four curl ELFs checked.')
    raw='.service/ipset-service.txt'
    fakecurl=b'#!/bin/sh\necho "curl host fixture OpenSSL/qa"; echo "Protocols: http https"\n'
    suffix_rules=b'### ZAPRET-USER-OVERLAY ###\n=custom.example 192.0.2.19'
    side_rules=suffix_rules.split(b'\n',1)[1]
    old_dns=stock73.read(dnsbase); new_dns=stock.read(dnsbase)
    # case, previous release, ABI, IP fixture, fast mode, DNS fixture, expected refusal
    cases=[
        ('v73-sv1 + old seeds','sv73','armeabi-v7a','stock',True,'stock',None),
        ('v73-sv1 + saved overlay','sv73','arm64-v8a','default_url',False,'overlay',None),
        ('stock v72 + old overlay separator','72','arm64-v8a','stock',False,'stock_overlay',None),
        ('stock v73 + downloaded data','73','x86','downloaded',False,'edited',None),
        ('stock v74 same versionCode','74','x86_64','stock',True,'stock',None),
        ('v74-sv1 reinstall keeps custom URLs','new','x86_64','custom',True,'custom',None),
        ('v73-sv1 explicit DNS URL + untouched old bytes','sv73','arm64-v8a','custom',False,'custom',None),
        ('v73-sv1 legacy DNS URL','sv73','arm64-v8a','stock',True,'legacy',None),
        ('v73-sv1 changed base plus embedded overlay','sv73','armeabi-v7a','stock',True,'edited_overlay',None),
        ('v73-sv1 missing custom IP and DNS copies','sv73','armeabi-v7a','missing_custom',True,'missing_custom',None),
        ('v73-sv1 orphaned DNS overlay','sv73','armeabi-v7a','stock',True,'orphan',None),
        ('unknown future version','future','arm64-v8a','stock',False,'stock','only verified'),
        ('unknown same-code v74 edition','variant','arm64-v8a','stock',False,'stock','only verified'),
        ('unrecognized previous scripts','unknown','x86_64','stock',False,'stock','unrecognized common'),
        ('linked settings file','sv73','armeabi-v7a','stock',True,'stock','cannot copy linked'),
        ('incomplete engine','sv73','armeabi-v7a','stock',True,'stock','missing or empty component'),
        ('missing Lua library','sv73','armeabi-v7a','stock',True,'stock','missing or empty component'),
        ('failed native probe','sv73','armeabi-v7a','stock',True,'stock','did not start'),
        ('pending reboot','sv73','arm64-v8a','stock',True,'stock','pending operation'),
        ('damaged staged DNS on update','sv73','arm64-v8a','stock',True,'stock','incomplete v74 bundled list'),
        ('damaged staged IP on fresh setup','fresh','armeabi-v7a','stock',False,'stock','incomplete v74 bundled list'),
        ('fresh incomplete ZIP','fresh','armeabi-v7a','stock',False,'stock','missing or empty component'),
        ('fresh companion fallback','fresh','arm64-v8a','stock',False,'stock',None),
        ('fresh install','fresh','armeabi-v7a','stock',False,'stock',None),
    ]
    for label,kind,arch,mode,quick,dnsmode,fail in cases:
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
            z={'sv73':sv73,'72':old,'73':stock73,'74':stock,'new':pkg,'future':stock,'variant':stock,'unknown':stock}[kind]
            oldraw=z.read(raw); base=z.read(dnsbase)
            meta=z.read('module.prop')
            if kind=='future': meta=meta.replace(b'versionCode=74',b'versionCode=75')
            if kind=='variant': meta=meta.replace(b'version=v74',b'version=v74-sv9')
            write(live,'module.prop',meta)
            write(live,'common.sh',z.read('common.sh')+(fast if quick else b'')+(b'# not from release\n' if kind=='unknown' else b''))
            write(live,'system/bin/zapret',z.read('system/bin/zapret'))
            write(live,'config/current-strategy',b'ALT12\n');write(live,'config/ipset-filter',b'any\n')
            write(live,'strategy/custom profile.sh',b'# custom override\n');write(live,'strategy/ALT12.sh',b'# local profile for selected name\n')
            write(live,'list/ipset-all-user.txt',b'192.0.2.7/32\n');write(live,'list/ipset-all.txt',b'198.51.100.6/32\n')
            write(live,'dnscrypt/blocked-names-user.txt',b'local.invalid\n')
            write(live,'.service/state/previous.pid',b'123\n')
            if mode.startswith('custom') or mode=='missing_custom':write(live,'config/ipset-link',b'https://example.invalid/my-list\n')
            if mode=='default_url':write(live,'config/ipset-link',b'https://raw.githubusercontent.com/sevcator/zapret-lists/refs/heads/main/ipset-service.txt\n')
            if mode!='missing_custom':write(live,raw,b'198.51.100.16/28\n' if mode=='downloaded' else oldraw)
            config_path='config/dnscrypt-cloaking-rules-link'
            if dnsmode=='legacy':config_path='config/custom-cloaking-rules-url'
            if dnsmode in ('legacy','custom','missing_custom'):write(live,config_path,b'# saved source\nhttps://example.invalid/my-dns\n')
            if dnsmode in ('edited','edited_overlay'):base+=b'=manual.example 192.0.2.41\n'
            if dnsmode in ('overlay','stock_overlay','orphan','edited_overlay'):
                base+=(b'\n\n' if dnsmode=='stock_overlay' else b'')+suffix_rules
                if dnsmode!='orphan':write(live,'dnscrypt/cloaking-rules-user.txt',side_rules)
            if dnsmode!='missing_custom':write(live,dnsbase,base)
        if label=='damaged staged DNS on update':(stage/dnsbase).write_bytes(b'altered')
        if label=='damaged staged IP on fresh setup':(stage/raw).write_bytes(b'altered')
        if label=='linked settings file':
            write(root,'outside',b'must not change\n');(live/'config/link').symlink_to(root/'outside')
        if label=='pending reboot':write(live,'update',b'')
        suffix,dns,curl=abis[arch]
        if label=='incomplete engine': (stage/f'zapret/nfqws2-{suffix}').unlink()
        if label in ('missing Lua library','fresh incomplete ZIP'): (stage/'lua/zapret-antidpi.lua').write_bytes(b'')
        (stage/('curl-'+curl)).write_bytes(b'#!/bin/sh\nexit 1\n' if label=='failed native probe' else fakecurl)
        (root/'kernel-targets').write_text('NFQUEUE\n')
        script=(stage/'customize.sh').read_text()
        for text,new in [('/data/adb/modules/zapret',str(live)),('/data/adb/modules_update/zapret',str(stage)),('/data/local/tmp/sv-pocket-v74.',str(root/'sv-pocket-v74.')),('/proc/net/ip_tables_targets',str(root/'kernel-targets')),('/proc/net/ip6_tables_targets',str(root/'kernel-targets'))]:
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
            check((stage/'curl').read_bytes()==sv73.read('sv-curl.sh') and (stage/'curl.bin').read_bytes()==fakecurl,label+': curl wrapper/ABI')
            if mode=='missing_custom':
                check(not (stage/raw).exists() and (stage/'list/ipset-all.txt').read_bytes()==b'198.51.100.6/32\n',label)
            else:
                expected=b'198.51.100.16/28\n' if mode=='downloaded' else oldraw if mode=='custom' else currentraw
                check((stage/raw).read_bytes()==expected,label+': wrong IP seed')
            if label=='fresh companion fallback':
                check((stage/'system/app/VpnHotspot.apk').is_file() and not list(root.glob('sv-pocket-v74.*')),label+': missing fallback or leaked temp file')
                check('pm install' in (root/'calls').read_text(),label+': did not exercise PackageManager')
            if kind!='fresh':
                for n in ('config/current-strategy','strategy/custom profile.sh','strategy/ALT12.sh','list/ipset-all-user.txt','dnscrypt/blocked-names-user.txt','common.sh'):
                    check((stage/n).read_bytes()==(live/n).read_bytes(),(label,n))
                check(not (stage/'.service/state/previous.pid').exists(),label)
                expected_dns=(new_dns+suffix_rules if dnsmode in ('overlay','stock_overlay','orphan') else base if dnsmode in ('custom','legacy','edited','edited_overlay') else new_dns)
                check((stage/dnsbase).read_bytes()==expected_dns,label+': DNS lost a rule or used a wrong base')
                if dnsmode in ('custom','legacy','missing_custom'):
                    check((stage/config_path).read_bytes()==(live/config_path).read_bytes(),label+': DNS source changed')
                    check((stage/'.service/sv-list-cache/used-cloaking').exists()==(dnsmode!='missing_custom'),label+': DNS provenance incorrect')
                if dnsmode in ('overlay','stock_overlay','orphan','edited_overlay'):
                    check((stage/'dnscrypt/cloaking-rules-user.txt').read_bytes()==side_rules,label+': user DNS sidecar')
                    rc=subprocess.run(['/bin/sh','-c','. "$MODPATH/common.sh" && . "$MODPATH/sv-lists.sh" && ensure_runtime_files && apply_dnscrypt_user_overlays'],env=env,capture_output=True,text=True)
                    check(rc.returncode==0 and (stage/dnsbase).read_bytes()==expected_dns,(label,'first boot overlay',rc.stderr))
                if mode in ('custom','default_url','missing_custom'):check((stage/'config/ipset-link').read_bytes()==(live/'config/ipset-link').read_bytes(),label+': IP source changed')
            else:
                check((stage/dnsbase).read_bytes()==new_dns,label+': missing fresh v74 DNS')

        print('PASS:',label)
    print(f'{len(cases)} installer scenarios passed (Android interfaces simulated).')

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    for arg in ('module','stock72','stock73','stock74','sv73'):p.add_argument('--'+arg,required=True,type=Path)
    run(p.parse_args())
