#!/usr/bin/env python3
"""Build a Magisk ZIP without running code from either input. Python 3 + patch."""
import argparse
import copy
import hashlib
import os
from pathlib import Path
import stat
import subprocess
import tempfile
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED

HERE = Path(__file__).resolve().parent
OFFICIAL = 'afdcd6a48ba97d3e43192bbed3b654e3e0afded4e4cd0e586a936b382cc9a3c2'
SV73 = 'ff8e5bf3793fae16b8b71e606839bd4edcb4bf3d08759adedd3c4984df906e88'
APK = '0139130d34462b6c06929b80f357392411aba900b32baf29abf5d85af2d1d170'
RUNTIME = {
    'curl-arm': ('armeabi-v7a', 'd1fdf5ef6273580f33bab79193769e6ee0be54a50dbf690d92d872be486cf106'),
    'curl-aarch64': ('arm64-v8a', 'ff4cc9375e5fd4a2c01e40bc062aa9075e78a7ab366df9f60ae731cbd9a378e6'),
    'curl-x86': ('x86', 'e34e8e5a05ccdc8aba71b57663ddd2513a34db6379f087a98ba745df40c7957d'),
    'curl-x86_64': ('x86_64', 'c743c3cfa550e456c94105e4223a57ecf9ff69f30d43661cc6e3ba34789ac1ea'),
}
TEXT = ('module.prop','customize.sh','dnscrypt/dnscrypt.sh','service.sh','uninstall.sh',
        'system/bin/zapret','zapret/zapret.sh','sv-curl.sh','sv-lists.sh','sv-process.sh','sv-refresh.sh')
META = 'META-INF/sv-pocket/'

def digest(data):
    return hashlib.sha256(data).hexdigest()

def needed(condition, message):
    if not condition:
        raise ValueError(message)

def build(stock, runtime, output):
    stock, runtime, output = map(Path, (stock,runtime,output))
    needed(output.resolve() not in (stock.resolve(),runtime.resolve()), 'Output must be a separate file')
    needed(digest(stock.read_bytes()) == OFFICIAL, 'Not the original tagged Pocket 74 zip')
    kind = digest(runtime.read_bytes())
    needed(kind in (SV73,APK), 'Supply the known v73-sv1 ZIP or signed 1.2.8 APK (same native runtime)')
    with tempfile.TemporaryDirectory(dir=output.resolve().parent) as tmp, ZipFile(stock) as upstream, ZipFile(runtime) as old:
        work = Path(tmp)
        for name in TEXT:
            if name in upstream.namelist():
                p = work/name; p.parent.mkdir(parents=True,exist_ok=True); p.write_bytes(upstream.read(name))
        subprocess.run(['patch','--batch','--forward','--fuzz=0','-p1','-i',str(HERE/'v74-sv1.patch')],cwd=work,check=True,stdout=subprocess.DEVNULL)
        changes = {n:(work/n).read_bytes() for n in TEXT}
        for n,(abi,expected) in RUNTIME.items():
            changes[n] = old.read(n if kind==SV73 else 'lib/'+abi+'/libpocketcurl.so')
            needed(digest(changes[n])==expected, 'Native runtime digest mismatch for '+abi)
        for name,source in [('curl-LICENSE.txt','curl.txt'),('openssl-LICENSE.txt','openssl.txt')]:
            changes[META+name] = old.read(META+name if kind==SV73 else 'assets/licenses/'+source)
        for name in ('v74-sv1.patch','COPYING-GPL-2.txt','LICENSE-upstream.txt','README.md','build.py'):
            changes[META+name] = (HERE/name).read_bytes()
        changes[META+'build-curl.sh'] = (HERE/'../../scripts/build-curl.sh').read_bytes()
        # Preserve upstream paths, order, timestamps and Unix attrs. Reproducible new metadata.
        pending = work/'output.zip'
        with ZipFile(pending,'w',compression=ZIP_DEFLATED,compresslevel=6) as out:
            for info in upstream.infolist():
                data = changes.pop(info.filename) if info.filename in changes else upstream.read(info)
                out.writestr(copy.copy(info),data,compress_type=info.compress_type,compresslevel=6)
            for name,data in sorted(changes.items()):
                info=ZipInfo(name,(2026,11,22,0,0,0)); info.create_system=3
                info.external_attr=(stat.S_IFREG|(0o755 if name.endswith('.sh') and not name.startswith(META) else 0o644))<<16
                out.writestr(info,data,compress_type=ZIP_DEFLATED,compresslevel=6)
        with ZipFile(pending) as check:
            needed(check.testzip() is None and len(set(check.namelist()))==len(check.namelist()), 'ZIP validation failed')
            expected=set(TEXT)|set(RUNTIME)
            for name in upstream.namelist():
                if name not in expected:
                    needed(check.read(name)==upstream.read(name),'Unexpected upstream change: '+name)
        os.replace(pending,output)
    print(f'{digest(output.read_bytes())}  {output.name}')

if __name__ == '__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    for name in ('stock','runtime','output'): parser.add_argument(name,type=Path)
    a=parser.parse_args(); build(a.stock,a.runtime,a.output)
