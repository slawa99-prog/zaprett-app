"""Exercise the installed wait function with controlled route, link and clock."""
import os
import pathlib
import re
import subprocess
import tempfile
import unittest

ASSET = pathlib.Path(__file__).resolve().parents[1] / 'src/main/assets/fast_network_wait.sh'

FIXTURE = r'''
. "$SCRIPT"
sv_pocket_uptime() { cat "$SERVICE_DIR/time"; }
advance() { echo $(( $(cat "$SERVICE_DIR/time") + $1 )) > "$SERVICE_DIR/time"; }
sleep() { advance "$1"; }
timeout() {
    echo "$3" >> "$SERVICE_DIR/probes"
    advance "$1"
    [ "$3" = "$RESPONDS" ]
}
ip() {
    clock=$(cat "$SERVICE_DIR/time")
    [ "$clock" -ge "$READY_AT" ] || return 1
    case "$1" in
        "-$FAMILY") printf '%s via gw dev %s src %s %s\n' "$4" "$IFACE" "$SOURCE" "$EXTRA" ;;
        link)
            if [ "$clock" -eq 1 ] && [ "$FLAP" = yes ]; then echo '2: eth0: <UP> state DOWN'
            else printf '2: %s: <%s> state UP\n' "$IFACE" "$FLAGS"; fi ;;
        *) return 1 ;;
    esac
}
wait_for_internet
'''

class NetworkWaitTest(unittest.TestCase):
    def run_case(self, **options):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            (root/'time').write_text('0\n')
            env = dict(os.environ, SCRIPT=str(ASSET), SERVICE_DIR=directory, FAMILY='4', SOURCE='192.0.2.7',
                       IFACE='eth0', READY_AT='0', FLAGS='BROADCAST,UP,LOWER_UP', FLAP='no', RESPONDS='', EXTRA='')
            env.update(options)
            subprocess.run(['/bin/sh', '-c', FIXTURE], env=env, check=True, timeout=10)
            method, elapsed = re.search(r'method=(\w+) elapsed_s=(\d+)', (root/'sv-network-wait.log').read_text()).groups()
            probes = (root/'probes').read_text().splitlines() if (root/'probes').exists() else []
            return method, int(elapsed), probes

    def test_ready_cable_and_ipv6_wifi_skip_unreachable_external_hosts(self):
        self.assertEqual(('local_route', 1, []), self.run_case())
        self.assertEqual(('local_route', 1, []), self.run_case(FAMILY='6', IFACE='wlan0', SOURCE='2001:db8::7'))

    def test_late_wifi_and_unstable_link_must_settle(self):
        self.assertEqual(('local_route', 7, ['1.1.1.1', '8.8.8.8']), self.run_case(READY_AT='6'))
        self.assertEqual(('local_route', 5, ['1.1.1.1']), self.run_case(FLAP='yes'))

    def test_loopback_tunnel_link_local_and_link_down_use_tcp_fallback(self):
        for params in [dict(IFACE='lo'), dict(IFACE='tun0'), dict(SOURCE='169.254.1.7'),
                       dict(SOURCE='::1', FAMILY='6'), dict(FLAGS='UP'), dict(EXTRA='linkdown')]:
            with self.subTest(params=params):
                self.assertEqual(('tcp', 5, ['1.1.1.1','8.8.8.8']), self.run_case(**params, RESPONDS='8.8.8.8'))

    def test_offline_budget_counts_probe_time(self):
        method, elapsed, probes = self.run_case(READY_AT='999')
        self.assertEqual(('deadline', 120), (method, elapsed))
        self.assertEqual(['1.1.1.1','8.8.8.8','9.9.9.9'], probes[:3])

if __name__ == '__main__':
    unittest.main()
