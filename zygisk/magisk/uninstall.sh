#!/system/bin/sh
# WCX Zygisk 模块卸载脚本
#
# 本模块不安装任何 APK，也没有 system 覆盖，因此只需清理运行时产物。
# 注意：不删除 /data/adb/modules/<id> 之外的任何用户配置（避免误删）。
MODDIR=${0%/*}

rm -f "$MODDIR/inject.log" 2>/dev/null
exit 0
