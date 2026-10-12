#!/usr/bin/env bash
# Runs the instrumented tests on the CI emulator, then collects logcat and test screenshots.
set -o pipefail
adb logcat -c || true
timeout 25m ./gradlew --stacktrace --console=plain :app:connectedDebugAndroidTest 2>&1 | tee connected.log
status=$?
adb logcat -d -v threadtime > logcat.txt 2>/dev/null || true
adb pull /sdcard/Download/facet-shader-test shader-test >/dev/null 2>&1 || true
exit $status
