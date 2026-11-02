#!/system/bin/sh
# Zygisk - WCX 模块控制脚本（WebUI / 终端共用）
#
# 本模块只注入微信，作用域按「Android 用户 + 包名」记录，所以双开/分身可以单独开关：
#   <模块目录>/target.list   每行: <userId>\t<package>
#                            文件不存在 = 默认注入 com.tencent.mm（所有用户）
#                            文件存在但为空 = 一个都不注入
#
# 想彻底停用模块请用 Root 管理器里的模块开关（会写 <模块目录>/disable，注入器同样识别）。
#
# 用法：
#   config.sh targets             # 每行: <userId>\t<package>\t<0|1>
#   config.sh set <userId> <package> <0|1>
#   config.sh set all <package> <0|1>     # 所有用户一起开关
#   config.sh reset               # 恢复默认（com.tencent.mm，所有用户）

set -u
MODDIR=${0%/*}
TARGETS="$MODDIR/target.list"
PATH=/system/bin:/system/xbin:/vendor/bin:/product/bin:$PATH
export PATH

DEFAULT_TARGET=com.tencent.mm
PKG_PREFIX=com.tencent.mm

known_users() {
  users=$(pm list users 2>/dev/null | sed -n 's/.*UserInfo{\([0-9][0-9]*\):.*/\1/p')
  if [ -n "$users" ]; then
    echo "$users"
  else
    echo 0
  fi
}

# 输出「显式 (user, package)」行：旧格式（只有包名）展开成所有已知用户
materialized() {
  [ -f "$TARGETS" ] || return 0
  tab=$(printf '\t')
  while IFS="$tab" read -r first second; do
    first=$(printf '%s' "$first" | sed -e 's/\r$//' -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')
    second=$(printf '%s' "${second:-}" | sed -e 's/\r$//' -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')
    [ -n "$first" ] || continue
    case "$first" in \#*) continue ;; esac
    if [ -n "$second" ]; then
      printf '%s\t%s\n' "$first" "$second"
    else
      # 旧格式：只有包名 → 展开成所有已知用户
      for uid in $(known_users); do
        printf '%s\t%s\n' "$uid" "$first"
      done
    fi
  done < "$TARGETS"
}

# 当前生效条目（供 targets/list 使用）：文件不存在 → 默认目标
effective_rows() {
  if [ -f "$TARGETS" ]; then
    materialized
  else
    for uid in $(known_users); do
      printf '%s\t%s\n' "$uid" "$DEFAULT_TARGET"
    done
  fi
}

is_enabled() {
  # $1=userId $2=package
  effective_rows | grep -qxF "$1$(printf '\t')$2"
}

emit_row() {
  if is_enabled "$1" "$2"; then state=1; else state=0; fi
  printf '%s\t%s\t%s\n' "$1" "$2" "$state"
}

# 列出设备上所有用户里、包名以 com.tencent.mm 开头的应用（含分身）
cmd_targets() {
  rows=""
  for uid in $(known_users); do
    pkgs=$(pm list packages --user "$uid" 2>/dev/null | sed -n 's/^package://p')
    [ -n "$pkgs" ] || pkgs=$(pm list packages 2>/dev/null | sed -n 's/^package://p')
    for pkg in $pkgs; do
      case "$pkg" in
        "$PKG_PREFIX"*) rows="$rows$(emit_row "$uid" "$pkg")""
" ;;
      esac
    done
  done

  if [ -z "$rows" ]; then
    return 0
  fi
  printf '%s' "$rows" | grep -v '^$' | sort -u
}

cmd_list() {
  effective_rows | sort -u
}

write_targets() {
  tmp="$TARGETS.$$.tmp"
  cat > "$tmp" || exit 1
  chmod 0644 "$tmp" || exit 1
  mv -f "$tmp" "$TARGETS" || exit 1
}

cmd_set() {
  user="${1:-}"
  pkg="${2:-}"
  want="${3:-}"
  [ -n "$user" ] && [ -n "$pkg" ] || {
    echo "usage: config.sh set <userId|all> <pkg> <0|1>" >&2
    exit 2
  }
  case "$want" in 0|1) ;; *) echo "state must be 0 or 1" >&2; exit 2 ;; esac

  current=$(effective_rows | grep -v '^$')
  tab=$(printf '\t')

  # 先删掉要改的那一条（all = 该包所有用户）
  if [ "$user" = "all" ]; then
    current=$(printf '%s\n' "$current" | grep -vF "${tab}${pkg}")
  else
    current=$(printf '%s\n' "$current" | grep -vxF "${user}${tab}${pkg}")
  fi

  if [ "$want" = "1" ]; then
    if [ "$user" = "all" ]; then
      for uid in $(known_users); do
        current=$(printf '%s\n%s' "$current" "$uid${tab}${pkg}")
      done
    else
      current=$(printf '%s\n%s' "$current" "$user${tab}${pkg}")
    fi
  fi

  printf '%s\n' "$current" | grep -v '^[[:space:]]*$' | sort -u | write_targets
  cmd_targets
}

cmd_reset() {
  rm -f "$TARGETS"
  cmd_targets
}

case "${1:-targets}" in
  targets) cmd_targets ;;
  list)    cmd_list ;;
  set)     shift; cmd_set "$@" ;;
  reset)   cmd_reset ;;
  *) echo "usage: config.sh {targets|list|set|reset}" >&2; exit 2 ;;
esac

# 成功路径统一 exit 0：WebUI 用 errno 判定成败
exit 0
