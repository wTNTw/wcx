#!/system/bin/sh
# Zygisk - WCX 的自愈脚本（开机 late_start service 阶段由 Root 管理器执行）
#
# 背景：某个 feature/hook 若让微信在启动阶段必崩，用户就再也进不去微信。
# 应用侧（微信进程内的模块）每次主进程启动会把「连续启动次数」写到：
#   /data/media/0/Android/data/com.tencent.mm/WCX/selfheal/attempts
# 启动成功并稳定运行 30 秒后由模块清零。
#
# 这里在开机时检查：连续失败 ≥ 3 次 → 写模块目录的 disable，让 Zygisk 与本模块注入器
# 都跳过注入（微信恢复可用），并把原因记进 self-heal.log。

MODDIR=${0%/*}
EXT_DIR=/data/media/0/Android/data/com.tencent.mm/WCX/selfheal
INT_DIR=/data/data/com.tencent.mm/files/selfheal
THRESHOLD=3

# 计数可能在外部数据目录，也可能在微信 files 目录（外部不可写时的回退）
ATTEMPTS_FILE=""
for candidate in "$EXT_DIR/attempts" "$INT_DIR/attempts"; do
  if [ -f "$candidate" ]; then
    ATTEMPTS_FILE="$candidate"
    break
  fi
done
[ -n "$ATTEMPTS_FILE" ] || exit 0

attempts=$(cat "$ATTEMPTS_FILE" 2>/dev/null | tr -d ' \r\n')
case "$attempts" in
  ''|*[!0-9]*) exit 0 ;;
esac

if [ "$attempts" -lt "$THRESHOLD" ]; then
  exit 0
fi

{
  echo "time=$(date '+%Y-%m-%d %H:%M:%S')"
  echo "consecutive_start_attempts=$attempts"
  echo "threshold=$THRESHOLD"
  echo "action=写 disable 停用模块（微信恢复可用）；在管理器里重新启用即可清除"
} > "$MODDIR/self-heal.log" 2>/dev/null

# 停用模块：Zygisk 与本模块注入器都会跳过（本模块注入器每次进程启动都会检查该文件）
: > "$MODDIR/disable"

# 清掉计数，避免用户重新启用后立刻又被停用
echo 0 > "$ATTEMPTS_FILE" 2>/dev/null

logger -t WCX "self-heal: disabled module after $attempts consecutive startup attempts"
exit 0
