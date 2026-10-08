#!/bin/bash
# Called by smoke.yml inside the emulator runner.
set -uo pipefail
PKG=com.umair.purpose
open_app() {
  adb logcat -c
  adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null
  sleep 25
  adb logcat -d > "logcat-$1.txt"
  echo "===== $1: crashes ====="
  grep -E "FATAL EXCEPTION|AndroidRuntime|Process: $PKG|Caused by|^\s+at com\.umair" -A0 "logcat-$1.txt" | head -80 || true
  if adb shell pidof $PKG >/dev/null; then echo "$1: app is running"; else echo "$1: APP IS NOT RUNNING"; return 1; fi
  adb shell dumpsys activity activities | grep -m3 -E "mResumedActivity|topResumedActivity" || true
  ! grep -q "FATAL EXCEPTION" "logcat-$1.txt"
}
fail=0
if ls apks/old/*.apk >/dev/null 2>&1; then
  if adb install -r apks/old/*.apk; then open_app old || fail=1; else fail=1; fi
fi
adb install -r apks/new/*.apk || fail=1
open_app new || fail=1
grep -q "FATAL EXCEPTION" logcat-new.txt && fail=1
adb shell pidof $PKG >/dev/null || fail=1
exit $fail
