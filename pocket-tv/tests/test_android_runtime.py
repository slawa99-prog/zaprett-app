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
from test_bridge import FAKE_PS

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
    push(PROJECT / 'src/main/assets/fast_network_wait.sh', DEVICE + '/fast.sh')
    # Test cold startup against complete, unmodified Pocket archives using real
    # Android ps/awk/sha256sum. No source or CLI of either module may be executed.
    for version, stock_zip in [(71, release), (72, pathlib.Path('/tmp/pocket72-test.zip')), (73, pathlib.Path('/tmp/pocket73-test.zip')), (74, pathlib.Path('/tmp/pocket74-test.zip'))]:
        stock = tmp / f'stock-{version}'; stock.mkdir()
        with zipfile.ZipFile(stock_zip) as archive:
            for name in archive.namelist():
                assert not name.startswith('/') and '..' not in pathlib.PurePosixPath(name).parts
            archive.extractall(stock)
        remote = DEVICE + f'/stock-{version}'
        push(stock, remote)
        def fingerprint():
            return shell('/system/bin/sh', '-c', 'tar -cf - -C "$1" . | sha256sum', 'fingerprint', remote).stdout.strip()
        before = fingerprint()
        started = time.monotonic()
        cold = shell('env', f'POCKET_TV_MODULE={remote}', f'POCKET_TV_BASE={DEVICE}/stock-data-{version}',
                     '/system/bin/sh', DEVICE + '/bridge.sh', 'state', 'full')
        elapsed = time.monotonic() - started
        assert '@@SERVICE stopped' in cold.stdout, cold.stdout
        assert '@@STATE idle' in cold.stdout and '@@SECTION END' in cold.stdout, cold.stdout
        assert '@@PHASE complete' in cold.stdout and '@@PHASE service' in cold.stdout, cold.stdout
        assert cold.stdout.count('@@STRATEGY ') > 200, cold.stdout
        assert fingerprint() == before, 'Startup modified the installed module'
        assert elapsed < 15, f'Unexpected slow cold status: {elapsed:.2f}s'
        note(f'Cold startup with unmodified Pocket v{version}: {elapsed:.2f}s; no module changes; no test started')

        # Exercise only the network wait function, never the stock firewall or
        # service. Compare stock and opt-in with identical unreachable TCP peers.
        def stock_action(*args, check=True, source=DEVICE+'/fast.sh'):
            return shell('env', f'POCKET_TV_MODULE={remote}', f'POCKET_TV_BASE={DEVICE}/stock-data-{version}',
                         f'POCKET_TV_STAGING={DEVICE}/stock-stage-{version}', f'POCKET_TV_FAST_SOURCE={source}',
                         '/system/bin/sh', DEVICE+'/bridge.sh', *args, check=check)
        assert '@@FAST_START off' in cold.stdout
        original_hash = shell('sha256sum', remote+'/common.sh').stdout
        def metadata():
            return (shell('stat','-c','%u:%g:%a',remote+'/common.sh').stdout,
                    shell('ls','-Zd',remote+'/common.sh').stdout)
        original_meta = metadata()
        assert stock_action('fast-start', 'on', source=DEVICE+'/bridge.sh', check=False).returncode != 0
        assert shell('sha256sum', remote+'/common.sh').stdout == original_hash
        wait_dir = DEVICE + '/wait-qa'
        shell('mkdir', '-p', wait_dir)
        def wait_test(body, limit=10):
            return shell('env', f'MODPATH={remote}', f'SERVICE_DIR={wait_dir}', '/system/bin/timeout',
                         str(limit), '/system/bin/sh', '-c', '. "$MODPATH/common.sh"\n'+body, check=False)
        ready_with_unreachable_peers = """
ip() {
  case "$1" in -4) echo '1.1.1.1 via 192.0.2.1 dev eth0 src 192.0.2.5';;
    link) echo '2: eth0: <BROADCAST,MULTICAST,UP,LOWER_UP> state UP';; *) return 1;; esac
}
timeout() { return 1; }
wait_for_internet
"""
        old = wait_test(ready_with_unreachable_peers, 4)
        assert old.returncode != 0, 'Stock wait unexpectedly completed within 4s'
        def auto(expected):
            res = stock_action('auto-fast-start')
            assert '@@FAST_AUTO ' + expected in res.stdout, res.stdout
        for flag in ['disable', 'remove']:
            shell('touch', remote + '/' + flag); auto('unavailable'); shell('rm', remote + '/' + flag)
            assert shell('sha256sum', remote+'/common.sh').stdout == original_hash
        stage = DEVICE + f'/stock-stage-{version}'
        shell('mkdir', '-p', stage); shell('touch', stage+'/module.prop')
        auto('deferred'); assert shell('sha256sum', remote+'/common.sh').stdout == original_hash
        shell('rm', stage+'/module.prop')
        shell('mkdir', '-p', remote+'/config')
        shell('/system/bin/sh','-c','echo ALT12 > "$1"','fixture',remote+'/config/current-strategy')
        with_before = shell('cat', remote+'/config/current-strategy').stdout
        auto('applied')
        inode = shell('stat','-c','%i',remote+'/common.sh').stdout
        auto('already_on')
        assert shell('stat','-c','%i',remote+'/common.sh').stdout == inode
        assert shell('cat', remote+'/config/current-strategy').stdout == with_before
        assert '@@SERVICE stopped' in stock_action('inspect').stdout
        assert '@@FAST_START on' in stock_action('inspect').stdout
        assert metadata() == original_meta
        ok = wait_test(ready_with_unreachable_peers)
        assert ok.returncode == 0, ok.stdout
        report = shell('cat', wait_dir+'/sv-network-wait.log').stdout.strip()
        assert 'method=local_route' in report, report
        fast_elapsed = int(report.split('elapsed_s=')[-1])
        assert 1 <= fast_elapsed < 4, report
        note(f'Pocket v{version} network wait ONLY (controlled ready Ethernet, unreachable peers): '
             f'stock still waiting at 4s; fast = {fast_elapsed}s')
        # Also try the emulator's real Android route, without modifying networking.
        route_present = wait_test('sv_pocket_route >/dev/null', 4).returncode == 0
        if route_present:
            ok = wait_test('timeout() { return 1; }; wait_for_internet')
            assert ok.returncode == 0 and 'method=local_route' in shell('cat', wait_dir+'/sv-network-wait.log').stdout
            note(f'Pocket v{version}: real emulator route recognised without contacting external peers')
        stock_action('fast-start', 'off')
        stock_action('fast-start', 'off')
        assert shell('sha256sum', remote+'/common.sh').stdout == original_hash
        assert metadata() == original_meta
        assert '@@FAST_START off' in stock_action('inspect').stdout
        # Refuse both directions on vendor/user edits; never overwrite them.
        shell('/system/bin/sh','-c','echo "# user edit" >> "$1"','fixture',remote+'/common.sh')
        edited = shell('sha256sum',remote+'/common.sh').stdout
        auto('unsupported'); assert shell('sha256sum',remote+'/common.sh').stdout == edited
        for arg in ['on', 'off']:
            assert stock_action('fast-start',arg,check=False).returncode != 0
            assert shell('sha256sum',remote+'/common.sh').stdout == edited
        note(f'Pocket v{version}: verified opt-in and exact rollback; unknown file preserved')

    # The lifecycle fixture models running processes, while keeping the real
    # module resolver. Only this fixture gets a fake ps through its private PATH.
    fixtures = tmp / 'tools'; fixtures.mkdir()
    (fixtures / 'ps').write_text(FAKE_PS.replace('#!/bin/sh', '#!/system/bin/sh', 1))
    push(fixtures, DEVICE + '/tools')
    shell('chmod', '700', DEVICE + '/tools/ps')
    shell('chmod', '700', DEVICE + '/module/curl')
    broken = shell(DEVICE + '/module/curl', '--version', check=False)
    assert broken.returncode != 0, 'Expected the v71 Termux executable to lack its libraries'
    note('Original Pocket v71 executable failure reproduced: ' + broken.stdout.strip())
    deps = run('readelf', '-d', str(mod / 'curl')).stdout
    note('Original binary dependencies: ' + '\n'.join(line.strip() for line in deps.splitlines() if 'NEEDED' in line))

    env = dict(POCKET_TV_MODULE=DEVICE + '/module', POCKET_TV_BASE=DEVICE + '/data',
               POCKET_TV_CURL_SOURCE=DEVICE + '/curl-native', POCKET_TV_CA_SOURCE=DEVICE + '/trusted.pem',
               POCKET_TV_TEST_URL=f'https://localhost:{port}', PATH=DEVICE + '/tools:/system/bin:/system/xbin')
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
    # The APK merges both streams of su. Exercise the exact stock menu functions
    # on Android; service changes are modelled by a file, never by the stock CLI.
    for version in (71, 72, 73, 74):
        stock_cli = (tmp / f'stock-{version}/system/bin/zapret').read_text()
        def function(name):
            start = stock_cli.index(name + '() {')
            return stock_cli[start:stock_cli.index('\n}', start) + 2] + '\n'
        mini = tmp / f'video-module-{version}'
        for sub in ('system/bin', 'config', 'strategy'):
            (mini/sub).mkdir(parents=True)
        (mini/'module.prop').write_text(f'author=sevcator\nversion=v{version}\nversionCode={version}\n')
        (mini/'state').write_text('running\n')
        (mini/'config/current-strategy').write_text('ALT12\n')
        for name in ('ALT12','2alt2'):
            (mini/'strategy'/f'{name}.sh').write_text('# test fixture\n')
        (mini/'system/bin/zapret').write_text('''mod="$POCKET_TV_MODULE"; STRATEGY_DIR="$mod/strategy"
CURRENT_STRATEGY_FILE="$mod/config/current-strategy"
current_strategy() { cat "$CURRENT_STRATEGY_FILE"; }
''' + function('list_strategies') + function('strategy_command') + '''
case "$1" in
  strategy) strategy_command "$2";;
  start|restart) cat "$CURRENT_STRATEGY_FILE" > "$mod/live"; echo running > "$mod/state";;
  stop) echo stopped > "$mod/state";;
  *) exit 2;;
esac
''')
        remote = DEVICE + f'/video-module-{version}'
        base = DEVICE + f'/video-data-{version}'
        push(mini, remote)
        overrides = {'POCKET_TV_MODULE': remote, 'POCKET_TV_BASE': base}
        info = bridge('inspect', overrides=overrides).stdout.splitlines()
        digest = next(x.split()[1] for x in info if x.startswith('@@CATALOG_ID '))
        session = tmp / f'video-session-{version}'; session.mkdir()
        for key, val in {'id':'retained','state':'completed','profile':'youtube','catalog-id':digest}.items():
            (session/key).write_text(val+'\n')
        shell('mkdir','-p',base); push(session,base+'/session')
        def transition(action, result):
            wire = bridge('video-transition',action,'retained',digest,'ALT12','2alt2',overrides=overrides).stdout
            assert wire.splitlines() == ['@@VIDEO '+result], repr(wire)
        transition('try','ready')
        assert shell('cat',remote+'/live').stdout.strip() == '2alt2'
        assert shell('cat',remote+'/config/current-strategy').stdout.strip() == 'ALT12'
        transition('commit','committed')
        assert shell('cat',remote+'/config/current-strategy').stdout.strip() == '2alt2'
        transition('restore-on','restored')
        assert shell('cat',remote+'/live').stdout.strip() == 'ALT12'
        note(f'Pocket v{version} stock interactive selector with merged Android output: standalone ready, committed, restored; retained session, no network scan')
    server.shutdown()
    run('adb', 'reverse', '--remove', f'tcp:{port}')
    shell('rm', '-rf', DEVICE)
    note('All Android runtime regression checks passed')
