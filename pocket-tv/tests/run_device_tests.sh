#!/bin/bash
set -u
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 0
adb shell wm size 1920x1080
adb shell wm density 320
cd pocket-tv || exit 1
result=0
../gradlew connectedDebugAndroidTest || result=$?
mkdir -p screenshots
adb pull /sdcard/Download/pocket-tv-qa/. screenshots/ || true
adb logcat -d -s AndroidRuntime > screenshots/android-runtime.txt
exit "$result"
