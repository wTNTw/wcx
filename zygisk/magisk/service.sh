#!/system/bin/sh
# WCX Zygisk 模块 —— 开机后置脚本
#
# 目前只做一件事：把上一轮的注入结果落到模块目录，方便通过管理器 / adb 查看。
# （崩溃自愈（连续 N 次启动失败自动 disable）在后续里程碑实现，届时也放在这里。）
MODDIR=${0%/*}
LOG="$MODDIR/inject.log"

{
  echo "boot: $(date)"
  echo "zygisk: ${ZYGISK_ENABLED:-unknown}"
  echo "wcx: $(grep -m1 '^version=' "$MODDIR/module.prop" 2>/dev/null)"
} >> "$LOG" 2>/dev/null

# 日志不要无限增长
if [ -f "$LOG" ]; then
  tail -n 200 "$LOG" > "$LOG.tmp" && mv -f "$LOG.tmp" "$LOG"
fi
