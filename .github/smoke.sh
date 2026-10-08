#!/bin/bash
# Called by smoke.yml inside the emulator runner.
set -uo pipefail
PKG=com.umair.purpose
# The emulator's adb connection occasionally drops ("device offline"); that is not an app crash.
wait_adb() { adb wait-for-device; for _ in $(seq 1 30); do [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && return 0; sleep 2; done; return 1; }
running() { for _ in 1 2 3 4 5; do wait_adb; adb shell pidof $PKG >/dev/null 2>&1 && return 0; sleep 3; done; return 1; }
open_app() {
  wait_adb
  adb logcat -c
  adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null
  sleep 25
  wait_adb
  adb logcat -d > "logcat-$1.txt"
  echo "===== $1: crashes ====="
  grep -E "FATAL EXCEPTION|AndroidRuntime|Process: $PKG|Caused by|^\s+at com\.umair" -A0 "logcat-$1.txt" | head -80 || true
  if running; then echo "$1: app is running"; else echo "$1: APP IS NOT RUNNING"; return 1; fi
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
running || fail=1
exit $fail
