#!/system/bin/sh
# WCX Zygisk 模块卸载：本模块不安装 APK，也没有持久状态目录，清理即可
MODDIR=/bin
rm -f "/module.apk" 2>/dev/null
rm -rf "/zygisk" 2>/dev/null
exit 0
