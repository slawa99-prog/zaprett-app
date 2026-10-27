"""Exercise native curl and the bridge on Android with the real Pocket v71 resolver.

Only a temporary fake module is controlled. No firewall or installed module is changed.
HTTPS uses a local test server with certificate verification enabled.
"""
import hashlib
import http.server
import pathlib
import shlex
import ssl
import subprocess
import tempfile
import threading
import time
import urllib.request
import zipfile

PROJECT = pathlib.Path(__file__).resolve().parents[1]
DEVICE = '/data/local/tmp/pocket-tv-runtime-qa'
REPORT = PROJECT / 'build/reports/native/android-runtime.txt'
REPORT.parent.mkdir(parents=True, exist_ok=True)
messages = []


def run(*args, check=True):
    p = subprocess.run(args, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=120)
    if check and p.returncode:
        raise AssertionError(f'{args}: {p.returncode}\n{p.stdout}')
    return p


def shell(*args, check=True):
    return run('adb', 'shell', shlex.join(args), check=check)


def push(path, remote):
    run('adb', 'push', str(path), remote)


def note(text):
    messages.append(text)
    print(text, flush=True)
    REPORT.write_text('\n'.join(messages))


class Handler(http.server.BaseHTTPRequestHandler):
    def do_HEAD(self):
        self.send_response(200)
        self.end_headers()

    def log_message(self, *_args):
        pass


with tempfile.TemporaryDirectory() as temp:
    tmp = pathlib.Path(temp)
    # Test certificates have no relation to Android's production trust store.
    for name in ['trusted', 'untrusted']:
        run('openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '2',
            '-subj', '/CN=localhost', '-addext', 'subjectAltName=DNS:localhost,IP:127.0.0.1',
            '-keyout', str(tmp / (name + '.key')), '-out', str(tmp / (name + '.pem')))
    server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
    tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    tls.load_cert_chain(tmp / 'trusted.pem', tmp / 'trusted.key')
    server.socket = tls.wrap_socket(server.socket, server_side=True)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    port = server.server_port
    run('adb', 'root')
    run('adb', 'wait-for-device')
    assert shell('id', '-u').stdout.strip() == '0', 'AOSP rooted test emulator required'
    shell('mkdir', '-p', DEVICE)
    run('adb', 'reverse', f'tcp:{port}', f'tcp:{port}')
    push(PROJECT / 'src/main/jniLibs/x86_64/libpocketcurl.so', DEVICE + '/curl-native')
    push(tmp / 'trusted.pem', DEVICE + '/trusted.pem')
    push(tmp / 'untrusted.pem', DEVICE + '/untrusted.pem')
    shell('chmod', '700', DEVICE + '/curl-native')
    base_command = [DEVICE + '/curl-native', '--disable', '--cacert', DEVICE + '/trusted.pem',
                    '--noproxy', '*', '--head', '-sS', '-m', '10', '-o', '/dev/null', '-w', '%{http_code}',
                    f'https://localhost:{port}']
    version = shell(DEVICE + '/curl-native', '--version').stdout
    assert 'curl 8.22.0' in version and 'OpenSSL/3.5.8' in version, version
    note('Native Android executable starts without Termux: ' + version.splitlines()[0])
    for flags in [('--tlsv1.2', '--tls-max', '1.2'), ('--tlsv1.3',)]:
        result = shell(*base_command, *flags)
        assert result.stdout.strip() == '200', result.stdout
        note('HTTPS with verified certificate: ' + ' '.join(flags) + ' = 200')
    bad_command = [x.replace('/trusted.pem', '/untrusted.pem') for x in base_command]
    result = shell(*bad_command, check=False)
    assert result.returncode == 60, result.stdout
    note('Untrusted TLS certificate correctly rejected (curl exit 60)')

    release = tmp / 'pocket-v71.zip'
    urllib.request.urlretrieve('https://github.com/sevcator/zapret-pocket/releases/download/71/zapret-pocket.zip', release)
    assert hashlib.sha256(release.read_bytes()).hexdigest() == '1365a4c93a211bdff29d374e0dd58194066e4847ab9355e2bf3e7fec2a81928d'
    mod = tmp / 'module'
    (mod / 'system/bin').mkdir(parents=True)
    (mod / 'strategy').mkdir()
    (mod / 'config').mkdir()
    with zipfile.ZipFile(release) as archive:
        (mod / 'common.sh').write_bytes(archive.read('common.sh'))
        (mod / 'curl').write_bytes(archive.read('curl-x86_64'))
    (mod / 'module.prop').write_text('id=zapret\nauthor=sevcator\nversion=v71\n')
    (mod / 'state').write_text('running\n')
    (mod / 'config/current-strategy').write_text('fixture\n')
    (mod / 'strategy/fixture.sh').write_text('# Fixture, never executed\n')
    (mod / 'system/bin/zapret').write_text('''#!/system/bin/sh
MODPATH="$POCKET_TV_MODULE"
. "$MODPATH/common.sh"
echo "$1" >> "$MODPATH/calls"
case "$1" in
 status) cat "$MODPATH/state" ;;
 stop) echo stopped > "$MODPATH/state" ;;
 start) echo running > "$MODPATH/state" ;;
 test)
   downloader="$(resolve_downloader)" || { echo '! curl not found or not functional'; exit 1; }
   echo "Using real Pocket resolver: $downloader"
   case "$downloader" in */data/runtime/curl) ;; *) echo 'Unexpected fallback'; exit 2 ;; esac
   "$downloader" --head -sS -m 10 --noproxy '*' "$POCKET_TV_TEST_URL" || exit 3
   echo '@@TESTING fixture'
   echo '@@RESULT OK 1 1 1 1 1 1 fixture'
   ;;
esac
''')
    push(mod, DEVICE + '/module')
    push(PROJECT / 'src/main/assets/pocket_bridge.sh', DEVICE + '/bridge.sh')
    shell('chmod', '700', DEVICE + '/module/curl')
    broken = shell(DEVICE + '/module/curl', '--version', check=False)
    assert broken.returncode != 0, 'Expected the v71 Termux executable to lack its libraries'
    note('Original Pocket v71 executable failure reproduced: ' + broken.stdout.strip())
    deps = run('readelf', '-d', str(mod / 'curl')).stdout
    note('Original binary dependencies: ' + '\n'.join(line.strip() for line in deps.splitlines() if 'NEEDED' in line))

    env = dict(POCKET_TV_MODULE=DEVICE + '/module', POCKET_TV_BASE=DEVICE + '/data',
               POCKET_TV_CURL_SOURCE=DEVICE + '/curl-native', POCKET_TV_CA_SOURCE=DEVICE + '/trusted.pem',
               POCKET_TV_TEST_URL=f'https://localhost:{port}')
    def bridge(*args, check=True, overrides=None):
        variables = env | (overrides or {})
        return shell('env', *(f'{k}={v}' for k, v in variables.items()),
                     '/system/bin/sh', DEVICE + '/bridge.sh', *args, check=check)

    bridge('launch-test', 'youtube', 'android-runtime-verified')
    for _ in range(30):
        state = bridge('poll').stdout
        if '@@STATE completed' in state:
            break
        if '@@STATE failed' in state or '@@STATE interrupted' in state:
            raise AssertionError(bridge('log').stdout + '\n' + state)
        time.sleep(1)
    else:
        raise AssertionError('Bridge timeout: ' + bridge('log').stdout)
    assert '@@RESULT OK 1 1 1 1 1 1 fixture' in state, state
    assert shell('cat', DEVICE + '/module/state').stdout.strip() == 'running'
    note('Android root bridge + genuine v71 resolver: fallback selected, HTTPS test completed, service state restored')
    old_calls = shell('cat', DEVICE + '/module/calls').stdout
    failed = bridge('launch-test', 'youtube', 'must-not-replace', check=False,
                    overrides={'POCKET_TV_CURL_SOURCE': DEVICE + '/module/curl'})
    assert failed.returncode != 0, failed.stdout
    assert '@@ID android-runtime-verified' in bridge('poll').stdout
    assert shell('cat', DEVICE + '/module/calls').stdout == old_calls
    assert 'curl 8.22.0' in shell(DEVICE + '/data/runtime/curl', '--version').stdout
    note('Failed preflight preserves prior session and does not stop the service')
    server.shutdown()
    run('adb', 'reverse', '--remove', f'tcp:{port}')
    shell('rm', '-rf', DEVICE)
    note('All Android runtime regression checks passed')
