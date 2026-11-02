#!/system/bin/sh
# Magisk 模块"操作"按钮：快速切换总开关（KernelSU/APatch 用 WebUI，不需要这个）
MODDIR=${0%/*}
CONFIG="$MODDIR/config.sh"
PATH=/system/bin:/system/xbin:/vendor/bin:/product/bin:$PATH
export PATH

[ -x "$CONFIG" ] || exit 1

if [ -e "$MODDIR/disable" ]; then
  "$CONFIG" master on
  echo "Zygisk - WCX: 已启用（重启微信后生效）"
else
  "$CONFIG" master off
  echo "Zygisk - WCX: 已停用（重启微信后生效）"
fi
