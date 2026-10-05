"""Exercise the real POSIX bridge against a fake Pocket CLI, without firewall changes."""
import os
import pathlib
import subprocess
import tempfile
import time
import unittest

BRIDGE = pathlib.Path(__file__).resolve().parents[1] / 'src/main/assets/pocket_bridge.sh'
FAKE = r'''#!/bin/sh
mod="$POCKET_TV_MODULE"
echo "$1" >> "$mod/calls"
case "$1" in
 status) cat "$mod/state"; [ "$(cat "$mod/state")" = running ] ;;
 start|restart) echo running > "$mod/state" ;;
 stop) echo stopped > "$mod/state" ;;
 strategy) IFS= read -r name; printf '%s\n' "$name" > "$mod/config/current-strategy" ;;
 test)
   MODPATH="$mod"; . "$mod/common.sh"
   downloader="$(resolve_downloader)" || { echo '! curl not found or not functional'; exit 1; }
   echo "Using curl: $downloader"
   [ "${FAKE_STUBBORN:-0}" != 1 ] || trap '' TERM
   while IFS= read -r name; do
     printf '@@TESTING %s\n' "$name"
     sleep "${FAKE_TEST_DELAY:-0.2}"
     printf '@@RESULT OK 3 4 2 4 4 4 %s\n' "$name"
   done < "$ZAPRET_TEST_STRATEGIES"
   exit "${FAKE_TEST_EXIT:-0}"
   ;;
esac
'''
COMMON = r'''resolve_downloader() {
 for candidate in "$MODPATH/curl" "$(command -v curl)"; do
  [ -x "$candidate" ] || continue
  "$candidate" --version >/dev/null 2>&1 || continue
  printf '%s\n' "$candidate"; return 0
 done
 return 1
}
'''

class BridgeTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.base = pathlib.Path(self.tmp.name)
        self.mod = self.base / 'module'
        for directory in ['system/bin', 'config', 'strategy']:
            (self.mod / directory).mkdir(parents=True)
        (self.mod / 'module.prop').write_text('id=zapret\nauthor=sevcator\nversion=v71\n')
        (self.mod / 'system/bin/zapret').write_text(FAKE)
        (self.mod / 'common.sh').write_text(COMMON)
        native = self.base / 'curl-native'
        native.write_text('#!/bin/sh\necho "curl 8.22.0 Android OpenSSL/3.5.8"\necho "Protocols: http https"\n')
        native.chmod(0o700)
        certificates = self.base / 'ca.pem'
        certificates.write_text('test certificate fixture\n')
        (self.mod / 'state').write_text('running\n')
        (self.mod / 'config/current-strategy').write_text('general\n')
        self.names = ['general', "name's special $thing"]
        for name in self.names:
            (self.mod / 'strategy' / (name + '.sh')).write_text('# strategy\n')
        self.env = dict(os.environ, POCKET_TV_MODULE=str(self.mod), POCKET_TV_BASE=str(self.base / 'data'), POCKET_TV_SHELL='/bin/sh', POCKET_TV_CURL_SOURCE=str(native), POCKET_TV_CA_SOURCE=str(certificates))

    def tearDown(self):
        # A failed assertion must not leak the detached runner.
        self.call('cancel-test', check=False)
        try: self.done(timeout=20)
        except AssertionError: pass
        self.tmp.cleanup()

    def call(self, *args, check=True):
        return subprocess.run(['/bin/sh', str(BRIDGE), *args], env=self.env, capture_output=True, text=True, timeout=15, check=check)

    def done(self, timeout=12):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            out = self.call('poll').stdout
            if any('@@STATE ' + state in out for state in ['completed', 'cancelled', 'failed', 'interrupted', 'idle']):
                return out
            time.sleep(.15)
        raise AssertionError(self.call('log').stdout)

    def test_catalog_selects_literal_name_and_restarts(self):
        out = self.call('inspect').stdout
        self.assertIn('@@COMPATIBLE 1', out)
        self.assertIn('@@STRATEGY ' + self.names[1], out)
        self.call('apply', self.names[1])
        self.assertEqual(self.names[1], (self.mod / 'config/current-strategy').read_text().strip())
        self.assertEqual('restart', (self.mod / 'calls').read_text().splitlines()[-1])
        self.assertNotEqual(0, self.call('apply', '../general', check=False).returncode)

    def test_detached_test_survives_client_exit_and_preserves_strategy(self):
        self.call('launch-test', 'youtube', 'test-one')
        out = self.done()
        self.assertIn('@@STATE completed', out)
        self.assertEqual(2, sum(line.startswith('@@RESULT ') for line in out.split('@@LOGTAIL')[0].splitlines()))
        self.assertIn('@@RESTORE ok', out)
        self.assertEqual('running', (self.mod / 'state').read_text().strip())
        self.assertEqual('general', (self.mod / 'config/current-strategy').read_text().strip())
        self.assertIn('@@ID test-one', self.call('poll').stdout)

    def test_cancellation_and_busy_guard_restore_service(self):
        self.env['FAKE_TEST_DELAY'] = '6'
        self.call('launch-test', 'all', 'cancel-test')
        self.assertNotEqual(0, self.call('apply', 'general', check=False).returncode)
        self.assertNotEqual(0, self.call('launch-test', 'all', 'second', check=False).returncode)
        self.call('cancel-test')
        self.assertIn('@@STATE cancelled', self.done(timeout=22))
        self.assertEqual('running', (self.mod / 'state').read_text().strip())

    def test_failure_keeps_results_and_stopped_service_stopped(self):
        self.env['FAKE_TEST_EXIT'] = '7'
        (self.mod / 'state').write_text('stopped\n')
        self.call('launch-test', 'all', 'failure')
        out = self.done()
        self.assertIn('@@STATE failed', out)
        self.assertIn('@@EXIT 7', out)
        self.assertIn('@@RESULT OK', out)
        self.assertEqual('stopped', (self.mod / 'state').read_text().strip())

    def test_cancel_can_stop_a_tester_ignoring_term(self):
        self.env['FAKE_STUBBORN'] = '1'
        self.env['FAKE_TEST_DELAY'] = '30'
        self.call('launch-test', 'all', 'stubborn')
        self.call('cancel-test')
        out = self.done(timeout=25)
        self.assertIn('@@STATE cancelled', out)
        self.assertEqual('running', (self.mod / 'state').read_text().strip())

    def test_stale_owner_is_not_running_or_killed(self):
        self.call('launch-test', 'youtube', 'stale')
        self.done()
        session = self.base / 'data/session'
        (session / 'state').write_text('running\n')
        (session / 'owner').write_text(f'{os.getpid()} 1 previous-boot\n')
        self.assertIn('@@STATE interrupted', self.call('poll').stdout)
        self.assertNotEqual(0, self.call('cancel-test', check=False).returncode)

    def test_wrong_module_is_rejected_without_actions(self):
        (self.mod / 'module.prop').write_text('author=someone_else\n')
        self.assertNotEqual(0, self.call('start', check=False).returncode)
        self.assertFalse((self.mod / 'calls').exists())

    def test_missing_termux_library_uses_bundled_runtime(self):
        broken = self.mod / 'curl'
        broken.write_text('#!/bin/sh\necho \'CANNOT LINK EXECUTABLE curl: library "libcurl.so" not found\' >&2\nexit 127\n')
        broken.chmod(0o700)
        self.call('launch-test', 'youtube', 'fallback')
        self.assertIn('@@STATE completed', self.done())
        log = self.call('log').stdout
        self.assertIn('libcurl.so', log)
        self.assertIn('Using curl: ' + str(self.base / 'data/runtime/curl'), log)
        self.assertEqual('running', (self.mod / 'state').read_text().strip())

    def test_preflight_failure_preserves_previous_results_and_service(self):
        self.call('launch-test', 'youtube', 'previous')
        self.assertIn('@@STATE completed', self.done())
        calls = (self.mod / 'calls').read_text()
        native = pathlib.Path(self.env['POCKET_TV_CURL_SOURCE'])
        native.write_text('#!/bin/sh\necho "CANNOT LINK: missing dependency" >&2\nexit 127\n')
        result = self.call('launch-test', 'youtube', 'must-not-replace', check=False)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('сервис и прошлый рейтинг сохранены', result.stderr)
        self.assertEqual(calls, (self.mod / 'calls').read_text())
        out = self.call('poll').stdout
        self.assertIn('@@ID previous', out)
        self.assertIn('@@STATE completed', out)
        self.assertEqual('running', (self.mod / 'state').read_text().strip())
        self.assertIn('missing dependency', self.call('log').stdout)

if __name__ == '__main__':
    unittest.main(verbosity=2)
