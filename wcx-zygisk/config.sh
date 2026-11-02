#!/system/bin/sh
# Zygisk - WCX 模块控制脚本（WebUI / action.sh / 终端共用）
#
# 状态都放在模块目录里，注入器在 pre-specialize 阶段直接读，改完立即生效（无需重启）：
#   <模块目录>/target.list   一行一个包名（缺省 = 只注入 com.tencent.mm；存在但为空 = 都不注入）
#   <模块目录>/disable       存在 = 总开关关闭（同时 Root 管理器也会显示为停用）
#
# 用法：
#   config.sh status                 # enabled=0|1，然后 ----
#   config.sh targets                # 每行: <userId>\t<packageName>\t<0|1>
#   config.sh set <pkg> <0|1>        # 设置某个包是否注入
#   config.sh master on|off          # 总开关
#   config.sh reset                  # 恢复默认（只注入 com.tencent.mm）

set -u
MODDIR=${0%/*}
TARGETS="$MODDIR/target.list"
DISABLE="$MODDIR/disable"
PATH=/system/bin:/system/xbin:/vendor/bin:/product/bin:$PATH
export PATH

DEFAULT_TARGET=com.tencent.mm
PKG_PREFIX=com.tencent.mm

list_enabled() {
  if [ -f "$TARGETS" ]; then
    # 末尾的 `|| true`：空列表时 grep 会返回 1，不能让整个脚本/子命令以非 0 退出
    { sed -e 's/\r$//' -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' "$TARGETS" 2>/dev/null |
      grep -v '^#' | grep -v '^$'; } || true
  else
    echo "$DEFAULT_TARGET"
  fi
}

is_enabled() {
  list_enabled | grep -qx "$1"
}

emit_row() {
  if is_enabled "$2"; then state=1; else state=0; fi
  printf '%s\t%s\t%s\n' "$1" "$2" "$state"
}

cmd_status() {
  if [ -e "$DISABLE" ]; then echo "enabled=0"; else echo "enabled=1"; fi
  echo "----"
  list_enabled
  return 0
}

# 列出设备上所有用户里、包名以 com.tencent.mm 开头的应用（含分身）。
# 优先按用户枚举（能区分分身）；个别 ROM 上 `pm list users` 不可用则回落到当前用户。
cmd_targets() {
  rows=""
  users=$(pm list users 2>/dev/null | sed -n 's/.*UserInfo{\([0-9][0-9]*\):.*/\1/p')

  if [ -n "$users" ]; then
    for uid in $users; do
      pkgs=$(pm list packages --user "$uid" 2>/dev/null | sed -n 's/^package://p')
      [ -n "$pkgs" ] || pkgs=$(pm list packages 2>/dev/null | sed -n 's/^package://p')
      for pkg in $pkgs; do
        case "$pkg" in
          "$PKG_PREFIX"*) rows="$rows$(emit_row "$uid" "$pkg")""
" ;;
        esac
      done
    done
  fi

  if [ -z "$rows" ]; then
    for pkg in $(pm list packages 2>/dev/null | sed -n 's/^package://p'); do
      case "$pkg" in
        "$PKG_PREFIX"*) rows="$rows$(emit_row 0 "$pkg")""
" ;;
      esac
    done
  fi

  if [ -z "$rows" ]; then
    return 0
  fi
  printf '%s' "$rows" | grep -v '^$' | sort -u
}

write_targets() {
  tmp="$TARGETS.$$.tmp"
  cat > "$tmp" || exit 1
  chmod 0644 "$tmp" || exit 1
  mv -f "$tmp" "$TARGETS" || exit 1
}

cmd_set() {
  if [ "${1:-}" = "--user" ]; then shift 2; fi
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
  printf '%s\n' "$current" | grep -v '^[[:space:]]*$' | sort -u | write_targets
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

# 成功路径统一 exit 0：root manager 的 WebUI 用 errno 判定成败，
# 任何「命令成功但退出码非 0」都会在页面上显示成操作失败
exit 0
