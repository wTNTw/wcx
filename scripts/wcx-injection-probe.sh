#!/system/bin/sh
# Android 16 / WeChat 8.0.80 WCX cold-start injection regression probe.
#
# Usage after pushing to the device:
#   su -c 'sh /data/local/tmp/wcx-injection-probe.sh direct 25'
#   su -c 'sh /data/local/tmp/wcx-injection-probe.sh boot 25'
#
# "boot" reproduces the failure-prone path: an exported BootReceiver starts the
# main process in the background, then LauncherUI is opened after injection had
# a chance to initialize. PASS requires the first LauncherUI to pass through
# ProxyInstrumentation, not merely that features eventually load.
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
LOG=$(ls -t /sdcard/Android/data/com.tencent.mm/wcx/logs/wcx-*.log 2>/dev/null | head -1)
[ -n "$LOG" ] || { echo "RESULT=HARNESS_ERROR no_log"; exit 2; }
BASE=$(wc -l < "$LOG")
BASE4=$(dumpsys activity exit-info com.tencent.mm 2>/dev/null | grep -c 'reason=4 (APP CRASH(EXCEPTION))')
BASE6=$(dumpsys activity exit-info com.tencent.mm 2>/dev/null | grep -c 'status=6')
BASE11=$(dumpsys activity exit-info com.tencent.mm 2>/dev/null | grep -c 'status=11')
START=$(date '+%Y-%m-%d_%H-%M-%S')
OUT=/data/local/tmp/wcx-injection-${START}-${MODE}.log
PRE=/data/local/tmp/wcx-injection-${START}-${MODE}-preopen.log

# Kill every WeChat process so surviving appbrand/push log lines cannot pollute
# the per-run window. Do not force-stop the package: the boot receiver must stay callable.
ps -A -o PID,NAME 2>/dev/null \
  | awk '$2 == "com.tencent.mm" || index($2, "com.tencent.mm:") == 1 { print $1 }' \
  | while read -r pid; do kill -9 "$pid" 2>/dev/null; done
sleep 3

PRE_MAIN=
case "$MODE" in
  direct)
    input keyevent KEYCODE_WAKEUP
    wm dismiss-keyguard >/dev/null 2>&1
    am start -n com.tencent.mm/.ui.LauncherUI >/dev/null 2>&1
    ;;
  boot)
    am broadcast -n 'com.tencent.mm/.booter.MMReceivers$BootReceiver' \
      -a android.intent.action.BOOT_COMPLETED >/dev/null 2>&1
    sleep 8
    tail -n +$((BASE + 1)) "$LOG" > "$PRE" 2>/dev/null
    PRE_MAIN=$(pidof com.tencent.mm)
    echo "PREOPEN main=$PRE_MAIN push=$(pidof com.tencent.mm:push) lines=$(wc -l < "$PRE")"
    input keyevent KEYCODE_WAKEUP
    wm dismiss-keyguard >/dev/null 2>&1
    am start -n com.tencent.mm/.ui.LauncherUI >/dev/null 2>&1
    ;;
  *)
    echo "RESULT=HARNESS_ERROR unknown_mode=$MODE"
    exit 2
    ;;
esac

sleep "$WAIT"
tail -n +$((BASE + 1)) "$LOG" > "$OUT" 2>/dev/null

ENTRY=$(grep -ac 'ZygiskEntry.init: process=com.tencent.mm apk=' "$OUT")
LIBAFF=$(grep -ac 'deferred startup: libaff_biz loaded' "$OUT")
GATE=$(grep -ac 'deferred startup: 首个 Activity 启动门已安装' "$OUT")
TRIGGER=$(grep -ac 'deferred startup: 首个 Activity 即将创建' "$OUT")
LSPLANT=$(grep -ac 'deferred startup: LSPlant initialized' "$OUT")
PROXY=$(grep -ac 'ProxyInstrumentation: callActivityOnCreate: com.tencent.mm.ui.LauncherUI' "$OUT")
ERRORS=$(grep -ac ' E/' "$OUT")
ENABLED=$(grep -a '本批 169 个：已启用' "$OUT" | sed 's/.*已启用 //; s/ 个.*//' | sort -n | tail -1)
[ -n "$ENABLED" ] || ENABLED=0
MAIN=$(pidof com.tencent.mm)
PUSH=$(pidof com.tencent.mm:push)
RECOVERY=$(pidof com.tencent.mm:recovery)
NEW4=$(( $(dumpsys activity exit-info com.tencent.mm 2>/dev/null | grep -c 'reason=4 (APP CRASH(EXCEPTION))') - BASE4 ))
NEW6=$(( $(dumpsys activity exit-info com.tencent.mm 2>/dev/null | grep -c 'status=6') - BASE6 ))
NEW11=$(( $(dumpsys activity exit-info com.tencent.mm 2>/dev/null | grep -c 'status=11') - BASE11 ))
# exit-info is a bounded rolling history; old entries may disappear during a run.
[ "$NEW4" -lt 0 ] && NEW4=0
[ "$NEW6" -lt 0 ] && NEW6=0
[ "$NEW11" -lt 0 ] && NEW11=0
SAME_MAIN=1
if [ "$MODE" = boot ] && { [ -z "$PRE_MAIN" ] || [ "$PRE_MAIN" != "$MAIN" ]; }; then
  SAME_MAIN=0
fi

STAGE=ok
[ "$ENTRY" -lt 1 ] && STAGE=no_java_entry
[ "$ENTRY" -ge 1 ] && [ "$LIBAFF" -lt 1 ] && STAGE=waiting_libaff
[ "$LIBAFF" -ge 1 ] && [ "$GATE" -lt 1 ] && STAGE=startup_gate_missing
[ "$GATE" -ge 1 ] && [ "$TRIGGER" -lt 1 ] && STAGE=startup_gate_not_triggered
[ "$TRIGGER" -ge 1 ] && [ "$LSPLANT" -lt 1 ] && STAGE=lsplant_failed
[ "$LSPLANT" -ge 1 ] && [ "$ENABLED" -lt 100 ] && STAGE=features_missing
[ "$ENABLED" -ge 100 ] && [ "$PROXY" -lt 1 ] && STAGE=first_activity_missed
[ "$NEW4" -gt 0 ] && STAGE=java_crash
[ "$NEW6" -gt 0 ] && STAGE=sigabrt
[ "$NEW11" -gt 0 ] && STAGE=sigsegv
[ "$SAME_MAIN" -eq 0 ] && STAGE=background_process_restarted

OK=1
[ "$STAGE" != ok ] && OK=0
[ -z "$MAIN" ] && OK=0
[ -n "$RECOVERY" ] && OK=0
[ "$ERRORS" -gt 0 ] && OK=0
[ "$NEW4" -gt 0 ] && OK=0
[ "$NEW6" -gt 0 ] && OK=0
[ "$NEW11" -gt 0 ] && OK=0
[ "$SAME_MAIN" -eq 0 ] && OK=0

echo "mode=$MODE pre_main=[$PRE_MAIN] main=[$MAIN] same_main=$SAME_MAIN push=[$PUSH] recovery=[$RECOVERY]"
echo "entry=$ENTRY libaff=$LIBAFF gate=$GATE trigger=$TRIGGER lsplant=$LSPLANT enabled_max=$ENABLED proxy=$PROXY E=$ERRORS new_java_crash=$NEW4 new_status6=$NEW6 new_status11=$NEW11 stage=$STAGE"
if [ "$OK" = 1 ]; then
  echo "RESULT=PASS"
else
  echo "RESULT=FAIL"
  echo "ARTIFACT=$OUT"
  echo "---- failure window ----"
  grep -aE 'ZygiskEntry|LSPlant|WCX module started|本批 169|ProxyInstrumentation|StartupAgent|StartupCrashGuard| E/' "$OUT" | tail -80
fi
