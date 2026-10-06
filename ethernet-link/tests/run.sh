#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/checks
cc -Wall -Wextra -Werror -fsanitize=address,undefined -fno-omit-frame-pointer tests/native_test.c -o build/checks/native_test
build/checks/native_test
javac -d build/checks app/src/main/java/com/slawa/ethernetlink/LinkDecision.java tests/LinkDecisionTest.java
java -cp build/checks com.slawa.ethernetlink.LinkDecisionTest
python3 - <<'PY'
import xml.etree.ElementTree as ET
root=ET.parse('app/src/main/AndroidManifest.xml').getroot()
permissions={e.attrib['{http://schemas.android.com/apk/res/android}name'] for e in root.findall('uses-permission')}
assert 'android.permission.INTERNET' in permissions, 'Native socket requires INTERNET'
assert 'android.permission.ACCESS_NETWORK_STATE' in permissions
print('Manifest socket permission regression passed')
PY
