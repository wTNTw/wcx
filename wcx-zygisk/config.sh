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
#   config.sh targets             # 每行: <userId>\t<package>\t<0|1>（只列当前真正装着微信的用户）
#   config.sh set <userId> <package> <0|1>
#   config.sh set all <package> <0|1>     # 所有用户一起开关
#   config.sh reset               # 恢复默认（com.tencent.mm，所有用户）
#   config.sh inject              # 每行: <userId>\t<package>\t<0|1>（1 = 主进程已被注入）
#   config.sh error               # 0|1，1 = 最近的 WCX 日志里有注入级异常
#   config.sh state               # WebUI 用：作用域 + 注入状态 + 异常标志（一次调用拿齐）

set -u
MODDIR=${0%/*}
TARGETS="$MODDIR/target.list"
PATH=/system/bin:/system/xbin:/vendor/bin:/product/bin:$PATH
export PATH

DEFAULT_TARGET=com.tencent.mm
PKG_PREFIX=com.tencent.mm
TAB=$(printf '\t')

# 同一次调用内缓存 pm 结果：一次 WebUI 刷新要问好几遍「某用户装了哪些包」
CACHE=$(mktemp -d 2>/dev/null || echo /data/local/tmp/wcx-cache.$$)
mkdir -p "$CACHE" 2>/dev/null
trap 'rm -rf "$CACHE"' EXIT INT TERM

# 注入级异常（不是 feature 类名失效那种噪音）
FATAL_RE='LSPlant init failed|RegisterNatives failed|ZygiskEntry.init failed|UnsatisfiedLinkError|dlopen failed|cannot locate symbol|SIGILL|Fatal signal|注入失败'
# 正常但会打 E 日志的行
BENIGN_RE='文件式 dex 加载为空|次重试|回退到内存'

known_users() {
  users=$(pm list users 2>/dev/null | sed -n 's/.*UserInfo{\([0-9][0-9]*\):.*/\1/p')
  if [ -n "$users" ]; then
    echo "$users"
  else
    echo 0
  fi
}

# 某用户下已安装的包名；`pm list packages --user` 不支持时（有 Error 输出）才回落到全局列表。
# 注意：绝不能因为「这个用户没装包」就回落到全局列表，否则会把别的用户的微信也算进来。
user_packages() {
  cachef="$CACHE/pkgs.$1"
  if [ -s "$cachef" ]; then
    cat "$cachef"
    return 0
  fi
  out=$(pm list packages --user "$1" 2>&1)
  case "$out" in
    *Error*|*error*|*Exception*|*Unknown*|*unknown*)
      pm list packages 2>/dev/null | sed -n 's/^package://p' > "$cachef"
      ;;
    *)
      printf '%s\n' "$out" | sed -n 's/^package://p' > "$cachef"
      ;;
  esac
  cat "$cachef"
}

# 输出「显式 (user, package)」行：旧格式（只有包名）展开成所有已知用户
materialized() {
  [ -f "$TARGETS" ] || return 0
  while IFS="$TAB" read -r first second; do
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

# 只保留「该用户下确实装着这个包」的行：卸载分身/换用户后旧记录会自动消失
prune_rows() {
  while IFS="$TAB" read -r user pkg; do
    [ -n "$user" ] && [ -n "$pkg" ] || continue
    if user_packages "$user" | grep -qxF "$pkg" 2>/dev/null; then
      printf '%s\t%s\n' "$user" "$pkg"
    fi
  done
}

# 当前生效条目（供 targets/list 使用）：文件不存在 → 默认目标
effective_rows() {
  if [ -f "$TARGETS" ]; then
    materialized | prune_rows
  else
    for uid in $(known_users); do
      user_packages "$uid" | grep -qxF "$DEFAULT_TARGET" && printf '%s\t%s\n' "$uid" "$DEFAULT_TARGET"
    done
  fi
}

is_enabled() {
  # $1=userId $2=package
  effective_rows | grep -qxF "$1$TAB$2"
}

emit_row() {
  if is_enabled "$1" "$2"; then state=1; else state=0; fi
  printf '%s\t%s\t%s\n' "$1" "$2" "$state"
}

# 列出设备上所有用户里、包名以 com.tencent.mm 开头的应用（含分身）
cmd_targets() {
  rows=""
  for uid in $(known_users); do
    for pkg in $(user_packages "$uid"); do
      case "$pkg" in
        "$PKG_PREFIX"*) rows="$rows$(emit_row "$uid" "$pkg")""
" ;;
      esac
    done
  done

  [ -n "$rows" ] || return 0
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

  # 先删掉要改的那一条（all = 该包所有用户）
  if [ "$user" = "all" ]; then
    current=$(printf '%s\n' "$current" | grep -vF "${TAB}${pkg}")
  else
    current=$(printf '%s\n' "$current" | grep -vxF "${user}${TAB}${pkg}")
  fi

  if [ "$want" = "1" ]; then
    if [ "$user" = "all" ]; then
      for uid in $(known_users); do
        user_packages "$uid" | grep -qxF "$pkg" || continue
        current=$(printf '%s\n%s' "$current" "$uid${TAB}${pkg}")
      done
    else
      current=$(printf '%s\n%s' "$current" "$user${TAB}${pkg}")
    fi
  fi

  printf '%s\n' "$current" | grep -v '^[[:space:]]*$' | sort -u | write_targets
  cmd_targets
}

# 主进程是否已注入。**不要全量扫 /proc/<pid>/maps**：几百个进程读一遍要 6-7 秒，
# 这是 WebUI 打开很慢的元凶。这里先用一次 ps 找到目标包的 pid，只看这几个 maps（~15ms）。
cmd_inject() {
  pids=$(ps -A -o PID,NAME 2>/dev/null)
  effective_rows | while IFS="$TAB" read -r user pkg; do
    [ -n "$pkg" ] || continue
    for pid in $(printf '%s\n' "$pids" | awk -v want="$pkg" '$2 == want {print $1}'); do
      [ -r "/proc/$pid/status" ] || continue
      uid=$(awk '/^Uid:/{print $2; exit}' "/proc/$pid/status" 2>/dev/null)
      [ -n "$uid" ] || continue
      [ "$((uid / 100000))" = "$user" ] || continue
      if grep -qF 'wcx-payload' "/proc/$pid/maps" 2>/dev/null; then
        printf '%s\t%s\t1\n' "$user" "$pkg"
      fi
    done
  done | sort -u
}

cmd_error() {
  logs=$(logcat -d -s WCX:* -t 200 2>/dev/null)
  [ -n "$logs" ] || logs=$(logcat -d 2>/dev/null | grep -F ' WCX ' | tail -n 200)

  bad=$(printf '%s\n' "$logs" | grep -E "$FATAL_RE" | grep -vE "$BENIGN_RE" | head -n 1)
  crash=$(logcat -d -b crash -t 200 2>/dev/null | grep -E "$PKG_PREFIX" | grep -F 'SIGILL' | head -n 1)

  if [ -n "$bad" ] || [ -n "$crash" ]; then echo 1; else echo 0; fi
}

# WebUI 一次调用拿全部数据（减少 root 桥往返：每条命令都要跨 JS↔Java，很贵）
#   T<TAB><userId><TAB><pkg><TAB><0|1>   作用域条目（1 = 已启用）
#   I<TAB><userId><TAB><pkg><TAB><0|1>   主进程是否已注入
#   E<TAB><0|1>                          最近日志里有没有注入级异常
cmd_state() {
  enabled_set=$(effective_rows)
  users=$(known_users)

  for uid in $users; do
    for pkg in $(user_packages "$uid"); do
      case "$pkg" in
        "$PKG_PREFIX"*) ;;
        *) continue ;;
      esac
      if printf '%s\n' "$enabled_set" | grep -qxF "$uid$TAB$pkg"; then e=1; else e=0; fi
      printf 'T\t%s\t%s\t%s\n' "$uid" "$pkg" "$e"
    done
  done

  cmd_inject | while IFS="$TAB" read -r iuser ipkg ist; do
    printf 'I\t%s\t%s\t%s\n' "$iuser" "$ipkg" "$ist"
  done

  printf 'E\t%s\n' "$(cmd_error)"
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
  inject)  cmd_inject ;;
  error)   cmd_error ;;
  state)   cmd_state ;;
  *) echo "usage: config.sh {targets|list|set|reset|inject|error|state}" >&2; exit 2 ;;
esac

# 成功路径统一 exit 0：WebUI 用 errno 判定成败
exit 0
