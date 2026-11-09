#!/system/bin/sh
# WCX 报错收集：一条命令看「哪些功能在当前微信版本上出错」
# 用法：adb shell su -c 'sh /data/local/tmp/wcx-harvest.sh'

LOG=/data/local/tmp/wcx-harvest.log
logcat -d -s WCX:* > "$LOG" 2>/dev/null

echo "=== 模块日志行数: $(wc -l < "$LOG")"
echo "=== E 级日志: $(grep -c 'E/WCX' "$LOG")"
echo "=== hook 失败: $(grep -c 'failed to execute hook' "$LOG")"
echo "=== 熔断的功能:"
grep -oE '[^ ]+ 连续失败 [0-9]+ 次' "$LOG" | sort | uniq -c | sort -rn | head -10
echo "=== 报错的功能 + 异常类型（次数 | 功能 | 异常）:"
grep -A1 'failed to execute hook of ' "$LOG" \
  | grep -oE 'failed to execute hook of .*|java\.[A-Za-z.]+(Exception|Error)' \
  | paste - - 2>/dev/null \
  | sed 's/failed to execute hook of //' \
  | sort | uniq -c | sort -rn | head -20
echo "=== 其它 E 级首行（前 10 条）:"
grep 'E/WCX' "$LOG" | grep -vE 'failed to execute hook|java\.|^\s+at ' | sed 's/.*E\/WCX\s*: //' | sort | uniq -c | sort -rn | head -10
echo "=== 完整日志: $LOG"
