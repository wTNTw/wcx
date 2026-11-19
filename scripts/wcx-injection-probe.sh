#!/system/bin/sh
# Android 16 / WeChat 8.0.80 WCX cold-start injection regression probe.
#
# Usage after pushing to the device:
#   su -c 'sh /data/local/tmp/wcx-injection-probe.sh direct 25'
#   su -c 'sh /data/local/tmp/wcx-injection-probe.sh screenoff 25'
#   su -c 'sh /data/local/tmp/wcx-injection-probe.sh boot 25'
#
# "boot" reproduces the failure-prone path: an exported BootReceiver starts the
# main process in the background, then LauncherUI is opened after injection had
# a chance to initialize. PASS requires no LSPlant initialization before
# Activity open, and requires the first LauncherUI to pass through
# ProxyInstrumentation.
#
# Host-side summary mode performs no adb/device operations:
#   sh scripts/wcx-injection-probe.sh --summarize-startup captured.log
if [ "${1:-}" = "--summarize-startup" ]; then
  INPUT=${2:-}
  [ -n "$INPUT" ] && [ -f "$INPUT" ] || {
    echo "RESULT=HARNESS_ERROR startup_log_missing"
    exit 2
  }
  VALUES=$(mktemp)
  trap 'rm -f "$VALUES"' EXIT HUP INT TERM
  sed -n 's/.*StartupPerf: startup .* total=\([0-9][0-9]*\)ms.*/\1/p' "$INPUT" \
    | sort -n > "$VALUES"
  COUNT=$(wc -l < "$VALUES" | tr -d ' ')
  if [ "$COUNT" -eq 0 ]; then
    echo "count=0 p50=NA p95=NA max=NA"
    exit 1
  fi
  P50_INDEX=$(( (50 * COUNT + 99) / 100 ))
  P95_INDEX=$(( (95 * COUNT + 99) / 100 ))
  P50=$(sed -n "${P50_INDEX}p" "$VALUES")
  P95=$(sed -n "${P95_INDEX}p" "$VALUES")
  MAX=$(tail -1 "$VALUES")
  echo "count=$COUNT p50=${P50}ms p95=${P95}ms max=${MAX}ms"
  exit 0
fi

MODE=${1:-direct}
WAIT=${2:-25}
LOGDIR=/sdcard/Android/data/com.tencent.mm/wcx/logs
FIRST_LOG=$(ls -t "$LOGDIR"/wcx-*.log 2>/dev/null | head -1)
[ -n "$FIRST_LOG" ] || { echo "RESULT=HARNESS_ERROR no_log"; exit 2; }
BASE4=$(dumpsys activity exit-info com.tencent.mm 2>/dev/null | grep -c 'reason=4 (APP CRASH(EXCEPTION))')
BASE5=$(dumpsys activity exit-info com.tencent.mm 2>/dev/null | grep -c 'reason=5 (APP CRASH(NATIVE))')
BASE6=$(dumpsys activity exit-info com.tencent.mm 2>/dev/null | grep -c 'status=6')
BASE11=$(dumpsys activity exit-info com.tencent.mm 2>/dev/null | grep -c 'status=11')
START=$(date '+%Y-%m-%d_%H-%M-%S')
OUT=/data/local/tmp/wcx-injection-${START}-${MODE}.log
PRE=/data/local/tmp/wcx-injection-${START}-${MODE}-preopen.log
OFFSETS=/data/local/tmp/wcx-injection-${START}-${MODE}.offsets

# Kill every WeChat process so surviving appbrand/push log lines cannot pollute
# the per-run window. Do not force-stop the package: the boot receiver must stay callable.
ps -A -o PID,NAME 2>/dev/null \
  | awk '$2 == "com.tencent.mm" || index($2, "com.tencent.mm:") == 1 { print $1 }' \
  | while read -r pid; do kill -9 "$pid" 2>/dev/null; done
sleep 3

# Snapshot per-file byte sizes after killing processes; postAppSpecialize logs in UTC
# before RuntimeInit sets local timezone, so 00:00-08:00 UTC+8 spans two daily files.
: > "$OFFSETS"
for f in $(ls -1tr "$LOGDIR"/wcx-*.log 2>/dev/null | tail -3); do
  printf '%s\t%s\n' "$f" "$(wc -c < "$f" | tr -d ' ')" >> "$OFFSETS"
done

collect_window() {
  DEST=$1
  : > "$DEST"
  for f in $(ls -1tr "$LOGDIR"/wcx-*.log 2>/dev/null | tail -3); do
    OLD=$(awk -F '\t' -v target="$f" '$1 == target { print $2 }' "$OFFSETS")
    [ -n "$OLD" ] || OLD=0
    CUR=$(wc -c < "$f" | tr -d ' ')
    if [ "$CUR" -gt "$OLD" ]; then
      tail -c +$((OLD + 1)) "$f" >> "$DEST" 2>/dev/null
    fi
  done
}

PRE_MAIN=
PRE_LSPLANT=0
PRE_STARTED=0
case "$MODE" in
  direct)
    input keyevent KEYCODE_WAKEUP
    wm dismiss-keyguard >/dev/null 2>&1
    am start -n com.tencent.mm/.ui.LauncherUI >/dev/null 2>&1
    ;;
  screenoff)
    input keyevent KEYCODE_SLEEP
    sleep 2
    am start -n com.tencent.mm/.ui.LauncherUI >/dev/null 2>&1
    sleep 8
    input keyevent KEYCODE_WAKEUP
    wm dismiss-keyguard >/dev/null 2>&1
    am start -n com.tencent.mm/.ui.LauncherUI >/dev/null 2>&1
    ;;
  boot)
    am broadcast -n 'com.tencent.mm/.booter.MMReceivers$BootReceiver' \
      -a android.intent.action.BOOT_COMPLETED >/dev/null 2>&1
    sleep 8
    collect_window "$PRE"
    PRE_MAIN=$(pidof com.tencent.mm)
    PRE_LSPLANT=$(grep -ac 'deferred startup: LSPlant initialized' "$PRE")
    PRE_STARTED=$(grep -ac 'startup_gate_result=started' "$PRE")
    echo "PREOPEN main=$PRE_MAIN push=$(pidof com.tencent.mm:push) pre_lsplant=$PRE_LSPLANT pre_started=$PRE_STARTED lines=$(wc -l < "$PRE")"
    input keyevent KEYCODE_WAKEUP
    wm dismiss-keyguard >/dev/null 2>&1
    am start -n com.tencent.mm/.ui.LauncherUI >/dev/null 2>&1
    ;;
  *)
    rm -f "$OFFSETS"
    echo "RESULT=HARNESS_ERROR unknown_mode=$MODE"
    exit 2
    ;;
esac

sleep "$WAIT"
collect_window "$OUT"
rm -f "$OFFSETS"

ENTRY=$(grep -ac 'ZygiskEntry.init: process=com.tencent.mm apk=' "$OUT")
LIBAFF=$(grep -ac 'deferred startup: libaff_biz loaded' "$OUT")
GATE=$(grep -ac 'startup_gate_installed' "$OUT")
TRIGGER=$(grep -ac 'startup_gate_activity_arrived' "$OUT")
READY=$(grep -ac 'startup_gate_ready' "$OUT")
STARTED=$(grep -ac 'startup_gate_result=started' "$OUT")
PREP_FAILED=$(grep -ac 'startup_gate_result=prepare_failed' "$OUT")
LSPLANT_FAILED=$(grep -ac 'startup_gate_result=lsplant_failed' "$OUT")
STARTUP_FAILED=$(grep -ac 'startup_gate_result=startup_failed' "$OUT")
LSPLANT=$(grep -ac 'deferred startup: LSPlant initialized' "$OUT")
PROXY=$(grep -ac 'ProxyInstrumentation: callActivityOnCreate: com.tencent.mm.ui.LauncherUI' "$OUT")
ERRORS=$(grep -ac ' E/' "$OUT")
ENABLED=$(grep -aE 'FeaturesLoader: 本批 [0-9]+ 个：已启用 [0-9]+ 个' "$OUT" | sed 's/.*已启用 //; s/ 个.*//' | sort -n | tail -1)
[ -n "$ENABLED" ] || ENABLED=0
FOCUSED=$(dumpsys activity activities 2>/dev/null | grep 'Hist  #0:' | head -1 | grep -c 'com\.tencent\.mm/\.ui\.LauncherUI')
MAIN=$(pidof com.tencent.mm)
PUSH=$(pidof com.tencent.mm:push)
RECOVERY=$(pidof com.tencent.mm:recovery)
NEW4=$(( $(dumpsys activity exit-info com.tencent.mm 2>/dev/null | grep -c 'reason=4 (APP CRASH(EXCEPTION))') - BASE4 ))
NEW5=$(( $(dumpsys activity exit-info com.tencent.mm 2>/dev/null | grep -c 'reason=5 (APP CRASH(NATIVE))') - BASE5 ))
NEW6=$(( $(dumpsys activity exit-info com.tencent.mm 2>/dev/null | grep -c 'status=6') - BASE6 ))
NEW11=$(( $(dumpsys activity exit-info com.tencent.mm 2>/dev/null | grep -c 'status=11') - BASE11 ))
# exit-info is a bounded rolling history; old entries may disappear during a run.
[ "$NEW4" -lt 0 ] && NEW4=0
[ "$NEW5" -lt 0 ] && NEW5=0
[ "$NEW6" -lt 0 ] && NEW6=0
[ "$NEW11" -lt 0 ] && NEW11=0
SAME_MAIN=1
if [ "$MODE" = boot ] && { [ -z "$PRE_MAIN" ] || [ "$PRE_MAIN" != "$MAIN" ]; }; then
  SAME_MAIN=0
fi

STAGE=ok
[ "$ENTRY" -lt 1 ] && STAGE=no_java_entry
[ "$ENTRY" -ge 1 ] && [ "$GATE" -lt 1 ] && STAGE=startup_gate_missing
[ "$GATE" -ge 1 ] && [ "$TRIGGER" -lt 1 ] && STAGE=startup_gate_not_triggered
[ "$PREP_FAILED" -gt 0 ] && STAGE=startup_prepare_failed
[ "$LSPLANT_FAILED" -gt 0 ] && STAGE=lsplant_failed
[ "$STARTUP_FAILED" -gt 0 ] && STAGE=module_start_failed
[ "$TRIGGER" -ge 1 ] && [ "$READY" -lt 1 ] && [ "$PREP_FAILED" -eq 0 ] && STAGE=startup_not_ready
[ "$READY" -ge 1 ] && [ "$STARTED" -lt 1 ] && STAGE=startup_not_completed
[ "$STARTED" -ge 1 ] && [ "$LSPLANT" -lt 1 ] && STAGE=lsplant_log_missing
[ "$LSPLANT" -ge 1 ] && [ "$ENABLED" -lt 10 ] && STAGE=features_missing
[ "$ENABLED" -ge 10 ] && [ "$PROXY" -lt 1 ] && STAGE=first_activity_missed
[ "$PROXY" -ge 1 ] && [ "$FOCUSED" -lt 1 ] && STAGE=launcher_not_focused
[ "$MODE" = boot ] && [ "$PRE_LSPLANT" -gt 0 ] && STAGE=background_initialized_early
[ "$MODE" = boot ] && [ "$PRE_STARTED" -gt 0 ] && STAGE=background_initialized_early
[ "$NEW4" -gt 0 ] && STAGE=java_crash
[ "$NEW5" -gt 0 ] && STAGE=native_crash
[ "$NEW6" -gt 0 ] && STAGE=sigabrt
[ "$NEW11" -gt 0 ] && STAGE=sigsegv
[ "$SAME_MAIN" -eq 0 ] && STAGE=background_process_restarted

OK=1
[ "$STAGE" != ok ] && OK=0
[ -z "$MAIN" ] && OK=0
[ -n "$RECOVERY" ] && OK=0
[ "$ERRORS" -gt 0 ] && OK=0
[ "$NEW4" -gt 0 ] && OK=0
[ "$NEW5" -gt 0 ] && OK=0
[ "$NEW6" -gt 0 ] && OK=0
[ "$NEW11" -gt 0 ] && OK=0
[ "$SAME_MAIN" -eq 0 ] && OK=0

echo "mode=$MODE pre_main=[$PRE_MAIN] main=[$MAIN] same_main=$SAME_MAIN push=[$PUSH] recovery=[$RECOVERY] pre_lsplant=$PRE_LSPLANT pre_started=$PRE_STARTED focused=$FOCUSED"
echo "entry=$ENTRY libaff=$LIBAFF gate=$GATE trigger=$TRIGGER ready=$READY started=$STARTED prep_failed=$PREP_FAILED lsplant_failed=$LSPLANT_FAILED startup_failed=$STARTUP_FAILED lsplant=$LSPLANT enabled_max=$ENABLED proxy=$PROXY E=$ERRORS new_java_crash=$NEW4 new_native_crash=$NEW5 new_status6=$NEW6 new_status11=$NEW11 stage=$STAGE"
if [ "$OK" = 1 ]; then
  echo "RESULT=PASS"
else
  echo "RESULT=FAIL"
  echo "ARTIFACT=$OUT"
  echo "---- failure window ----"
  grep -aE 'ZygiskEntry|LSPlant|WCX module started|FeaturesLoader: 本批|ProxyInstrumentation|StartupAgent|StartupCrashGuard| E/' "$OUT" | tail -80
fi
