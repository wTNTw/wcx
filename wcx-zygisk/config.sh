#!/system/bin/sh
# Zygisk - WCX 模块控制脚本（WebUI / action.sh / 终端共用）
#
# 状态都放在模块目录里，注入器在 pre-specialize 阶段直接读，改完立即生效（无需重启）：
#   <模块目录>/target.list   一行一个包名（缺省 = 只注入 com.tencent.mm）
#   <模块目录>/disable       存在 = 总开关关闭（同时 Root 管理器也会显示为停用）
#
# 用法：
#   config.sh status                 # enabled=0|1
#   config.sh targets                # 每行: <userId>\t<packageName>\t<0|1>
#   config.sh set <pkg> <0|1>        # 设置某个包是否注入
#   config.sh set --user <id> <pkg> <0|1>
#   config.sh master on|off          # 总开关
#   config.sh reset                  # 恢复默认（只注入 com.tencent.mm）

set -u
MODDIR=${0%/*}
TARGETS="$MODDIR/target.list"
DISABLE="$MODDIR/disable"
PATH=/system/bin:/system/xbin:/vendor/bin:/product/bin:$PATH
export PATH

DEFAULT_TARGET=com.tencent.mm

log_state() {
  if [ -e "$DISABLE" ]; then echo "enabled=0"; else echo "enabled=1"; fi
}

# 读取当前生效的目标列表（缺省回落到默认）
list_enabled() {
  if [ -f "$TARGETS" ]; then
    grep -v '^[[:space:]]*#' "$TARGETS" 2>/dev/null | sed '/^[[:space:]]*$/d'
  else
    echo "$DEFAULT_TARGET"
  fi
}

is_enabled() {
  list_enabled | grep -qx "$1"
}

cmd_status() {
  log_state
  echo "----"
  list_enabled
}

# 列出设备上所有用户里、包名以 com.tencent.mm 开头的应用（含分身）
cmd_targets() {
  pm list users 2>/dev/null | sed -n 's/.*UserInfo{\([0-9][0-9]*\):.*/\1/p' | while read -r uid; do
    [ -n "$uid" ] || continue
    pm list packages --user "$uid" 2>/dev/null | sed -n 's/^package://p' | while read -r pkg; do
      case "$pkg" in
        com.tencent.mm*) ;;
        *) continue ;;
      esac
      if is_enabled "$pkg"; then state=1; else state=0; fi
      printf '%s\t%s\t%s\n' "$uid" "$pkg" "$state"
    done
  done
}

write_targets() {
  # 用 Python 不可靠（部分系统没有），这里用临时文件 + mv 保证原子性
  tmp="$TARGETS.$$.tmp"
  cat > "$tmp" || exit 1
  chmod 0644 "$tmp" || exit 1
  mv -f "$tmp" "$TARGETS" || exit 1
}

cmd_set() {
  user_filter=""
  if [ "${1:-}" = "--user" ]; then user_filter="$2"; shift 2; fi
  pkg="${1:-}"
  want="${2:-}"
  [ -n "$pkg" ] || { echo "usage: config.sh set <pkg> <0|1>" >&2; exit 2; }
  case "$want" in 0|1) ;; *) echo "state must be 0 or 1" >&2; exit 2 ;; esac

  current=$(list_enabled)
  case "$want" in
    1)
      printf '%s\n' "$current" | grep -qx "$pkg" || current=$(printf '%s\n%s\n' "$current" "$pkg")
      ;;
    0)
      current=$(printf '%s\n' "$current" | grep -vx "$pkg")
      ;;
  esac
  printf '%s\n' "$current" | sed '/^[[:space:]]*$/d' | sort -u | write_targets
  cmd_status
}

cmd_master() {
  case "${1:-}" in
    on)  rm -f "$DISABLE" ;;
    off) : > "$DISABLE" ;;
    *) echo "usage: config.sh master on|off" >&2; exit 2 ;;
  esac
  cmd_status
}

cmd_reset() {
  rm -f "$TARGETS"
  cmd_status
}

case "${1:-status}" in
  status)  cmd_status ;;
  targets) cmd_targets ;;
  set)     shift; cmd_set "$@" ;;
  master)  shift; cmd_master "$@" ;;
  reset)   cmd_reset ;;
  *) echo "usage: config.sh {status|targets|set|master|reset}" >&2; exit 2 ;;
esac
