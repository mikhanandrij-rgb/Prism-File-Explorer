#!/usr/bin/env bash
# Runs only the upscaler benchmark on the CI emulator (~10 minutes), then collects results.
set -o pipefail
adb logcat -G 32M || true
adb logcat -c || true
ONLY=$(tr -d ' \n' < scripts/bench_only.txt 2>/dev/null)
timeout 12m ./gradlew --console=plain :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.raival.compose.file.explorer.viewer.video.UpscalerBenchmarkTest \
  ${ONLY:+-Pandroid.testInstrumentationRunnerArguments.bench_only=$ONLY} 2>&1 | tee connected.log
status=$?
adb logcat -d -v threadtime > logcat.txt 2>/dev/null || true
adb pull /sdcard/Download/facet-shader-test shader-test >/dev/null 2>&1 || true
exit $status
