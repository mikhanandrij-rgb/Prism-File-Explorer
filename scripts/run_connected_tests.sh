#!/usr/bin/env bash
# Runs the instrumented tests on the CI emulator and always dumps logcat afterwards.
set -o pipefail
adb logcat -c || true
timeout 25m ./gradlew --stacktrace --console=plain :app:connectedDebugAndroidTest 2>&1 | tee connected.log
status=$?
adb logcat -d -v threadtime > logcat.txt 2>/dev/null || true
exit $status
