#!/bin/bash
set -u
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 1
cd pocket-tv || exit 1
result=0
../gradlew connectedDebugAndroidTest || result=$?
mkdir -p screenshots
adb pull /sdcard/Android/data/com.slawa99.pockettv/files/. screenshots/ || true
adb logcat -d -s AndroidRuntime > screenshots/android-runtime.txt
exit "$result"
