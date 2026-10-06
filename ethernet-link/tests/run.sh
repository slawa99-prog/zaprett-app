#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/checks
cc -Wall -Wextra -Werror -fsanitize=address,undefined -fno-omit-frame-pointer tests/native_test.c -o build/checks/native_test
build/checks/native_test
sources=(LinkDecision Rtl8153Status AsixStatus UsbAdapterCatalog UsbPermissionGate BoundProxy)
files=()
for name in "${sources[@]}"; do files+=("app/src/main/java/com/slawa/ethernetlink/$name.java"); done
javac -d build/checks "${files[@]}" tests/*Test.java
java -cp build/checks com.slawa.ethernetlink.LinkDecisionTest
java -cp build/checks com.slawa.ethernetlink.Rtl8153StatusTest
java -cp build/checks com.slawa.ethernetlink.UsbSupportTest
java -cp build/checks com.slawa.ethernetlink.BoundProxyTest
python3 - <<'PY'
import xml.etree.ElementTree as ET
import re
from pathlib import Path
root=ET.parse('app/src/main/AndroidManifest.xml').getroot()
permissions={e.attrib['{http://schemas.android.com/apk/res/android}name'] for e in root.findall('uses-permission')}
assert 'android.permission.INTERNET' in permissions, 'Native socket requires INTERNET'
assert 'android.permission.ACCESS_NETWORK_STATE' in permissions
ns='{http://schemas.android.com/apk/res/android}'
activity=root.find('application/activity')
assert activity.attrib[ns+'launchMode']=='singleTop'
assert any(x.attrib.get(ns+'name')=='android.hardware.usb.action.USB_DEVICE_ATTACHED' for x in activity.findall('intent-filter/action'))
assert any(x.attrib.get(ns+'resource')=='@xml/usb_devices' for x in activity.findall('meta-data'))
catalog=Path('app/src/main/java/com/slawa/ethernetlink/UsbAdapterCatalog.java').read_text()
expected={(int(a,16),int(b,16)) for a,b in re.findall(r'\{(0x[0-9a-f]+),(0x[0-9a-f]+)\}',catalog)}
entries=ET.parse('app/src/main/res/xml/usb_devices.xml').getroot().findall('usb-device')
actual={(int(e.attrib['vendor-id']),int(e.attrib['product-id'])) for e in entries}
assert expected==actual and len(entries)==40,'USB default filters must match reader whitelist exactly'
asix=catalog.split('ASIX_IDS={',1)[1].split('};',1)[0]
asix_ids={(int(a,16),int(b,16)) for a,b in re.findall(r'\{(0x[0-9a-f]+),(0x[0-9a-f]+)\}',asix)}
for e in entries:
    if (int(e.attrib['vendor-id']),int(e.attrib['product-id'])) in asix_ids:
        assert (e.attrib['class'],e.attrib['subclass'],e.attrib['protocol'])==('255','255','0')
print('Manifest permissions, USB auto-launch and exact device filters passed')
PY
