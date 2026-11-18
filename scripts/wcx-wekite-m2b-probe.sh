#!/system/bin/sh
# Capture BEFORE starting target processes. Unlike the initial plan sketch,
# observe waits the full interval and matches each process, not aggregate counts.
# su -c 'sh /data/local/tmp/wcx-wekite-m2b-probe.sh begin'
# su -c 'sh /data/local/tmp/wcx-wekite-m2b-probe.sh check 600'
set -u
STATE=/data/local/tmp/wcx-m2b-probe
MODE=${1:-check}
WAIT=${2:-600}
case "$MODE" in
  begin)
    mkdir -p "$STATE"
    date '+%m-%d %H:%M:%S.000' > "$STATE/logcat-start"
    LOG=$(ls -t /sdcard/Android/data/com.tencent.mm/wcx/logs/wcx-*.log 2>/dev/null | head -1)
    [ -n "$LOG" ] || { echo 'RESULT=HARNESS_ERROR no_log'; exit 2; }
    printf '%s\n' "$LOG" > "$STATE/log-path"
    wc -l < "$LOG" > "$STATE/log-base"
    dumpsys activity exit-info com.tencent.mm > "$STATE/exit-before.txt"
    : > "$STATE/pids.txt"
    echo "RESULT=BASELINE_CAPTURED state=$STATE"
    exit 0
    ;;
  check) ;;
  *) echo 'RESULT=HARNESS_ERROR expected_begin_or_check'; exit 2 ;;
esac
[ -f "$STATE/logcat-start" ] || { echo 'RESULT=HARNESS_ERROR baseline_missing'; exit 2; }
MARK=$(cat "$STATE/logcat-start")
START=$(date +%s)
END=$((START + WAIT))
PUSH=$(pidof com.tencent.mm:push)
AB_NAME=com.tencent.mm:appbrand0
AB=$(pidof "$AB_NAME")
if [ -z "$AB" ]; then AB_NAME=com.tencent.mm:appbrand1; AB=$(pidof "$AB_NAME"); fi
MAIN=$(pidof com.tencent.mm)
OK=1
[ -n "$PUSH" ] && [ -n "$AB" ] && [ -n "$MAIN" ] || OK=0
while [ "$OK" = 1 ] && [ "$(date +%s)" -lt "$END" ]; do
  NOW_MAIN=$(pidof com.tencent.mm)
  NOW_PUSH=$(pidof com.tencent.mm:push)
  NOW_AB=$(pidof "$AB_NAME")
  printf '%s main=%s push=%s %s=%s\n' "$(date +%s)" "$NOW_MAIN" "$NOW_PUSH" "$AB_NAME" "$NOW_AB" >> "$STATE/pids.txt"
  if [ "$MAIN" != "$NOW_MAIN" ] || [ "$PUSH" != "$NOW_PUSH" ] || [ "$AB" != "$NOW_AB" ] || [ -n "$(pidof com.tencent.mm:recovery)" ]; then
    echo 'PID_CHANGED_OR_RECOVERY'
    OK=0
    break
  fi
  sleep 5
done
logcat -d -v threadtime -T "$MARK" > "$STATE/logcat.txt"
logcat -b crash -d -v threadtime -T "$MARK" > "$STATE/crash.txt"
dumpsys activity exit-info com.tencent.mm > "$STATE/exit-after.txt"
LOG=$(cat "$STATE/log-path")
BASE=$(cat "$STATE/log-base")
LINES=$(wc -l < "$LOG")
if [ "$LINES" -lt "$BASE" ]; then echo 'RESULT=HARNESS_ERROR log_rotated'; exit 2; fi
tail -n +$((BASE + 1)) "$LOG" > "$STATE/wcx.txt"
# The target ROM can suppress WCX app logcat records. WeLogger's file queue
# retains early selector records too (verified against the real smoke capture).
# Keep logcat separately for system/crash diagnostics, not backend acceptance.
for NAME in com.tencent.mm:push "$AB_NAME"; do
  grep -a "WeKiteNativeSelfTest.*SELFTEST PASS process=$NAME " "$STATE/wcx.txt" || OK=0
  grep -a "WeKiteBackendSelector.*backend=WeKite process=$NAME " "$STATE/wcx.txt" || OK=0
  grep -a "WeKiteBackendSelector.*native init PASS process=$NAME" "$STATE/wcx.txt" || OK=0
done
if grep -aE 'SELFTEST FAIL|backend=LSPlant fallback| E/WCX|WCX +E ' "$STATE/logcat.txt"; then OK=0; fi
if grep -aE 'SELFTEST FAIL|backend=LSPlant fallback| E/' "$STATE/wcx.txt"; then OK=0; fi
# Each complete fixture run deliberately throws twice in before and twice in
# after. Count only the exact private-test markers; never exclude any WCX E.
SELFTESTS=$(grep -ac 'I/WCX WeKiteNativeSelfTest: SELFTEST PASS process=' "$STATE/wcx.txt")
EXPECTED_BEFORE=$(grep -ac 'I/WCX WeKiteHookBridgeRuntime: SELFTEST expected before callback fault restored$' "$STATE/wcx.txt")
EXPECTED_AFTER=$(grep -ac 'I/WCX WeKiteHookBridgeRuntime: SELFTEST expected after callback fault restored$' "$STATE/wcx.txt")
UNEXPECTED_E=$(grep -ac ' E/' "$STATE/wcx.txt")
[ "$EXPECTED_BEFORE" -eq "$((SELFTESTS * 2))" ] || OK=0
[ "$EXPECTED_AFTER" -eq "$((SELFTESTS * 2))" ] || OK=0
echo "selftests=$SELFTESTS expected_callback_before=$EXPECTED_BEFORE expected_callback_after=$EXPECTED_AFTER expected_callback_total=$((EXPECTED_BEFORE + EXPECTED_AFTER)) unexpected_wcx_E=$UNEXPECTED_E"
if grep -aE 'FATAL EXCEPTION|SIGABRT|SIGSEGV|Fatal signal|>>> com.tencent.mm' "$STATE/crash.txt"; then OK=0; fi
# Compare identities, not bounded-history counts: old records can roll off.
awk '/timestamp=|reason=|status=|process=/' "$STATE/exit-before.txt" > "$STATE/exit-before-key.txt"
awk '/timestamp=|reason=|status=|process=/' "$STATE/exit-after.txt" > "$STATE/exit-after-key.txt"
diff -u "$STATE/exit-before-key.txt" "$STATE/exit-after-key.txt" > "$STATE/exit-diff.txt" || true
if grep -aE '^\+.*(reason=4 |reason=5 |status=6|status=11)' "$STATE/exit-diff.txt"; then OK=0; fi
ELAPSED=$(( $(date +%s) - START ))
echo "main=[$MAIN] push=[$PUSH] $AB_NAME=[$AB] observed_seconds=$ELAPSED artifacts=$STATE"
if [ "$OK" = 1 ] && [ "$ELAPSED" -ge "$WAIT" ]; then
  echo 'RESULT=PASS'
else
  echo 'RESULT=FAIL'
  exit 1
fi
