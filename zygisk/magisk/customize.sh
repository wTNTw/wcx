#!/system/bin/sh
# WCX Zygisk 模块安装脚本
#
# 安装器（Magisk / KernelSU / APatch）会把 zip 里除 META-INF/ 之外的内容解压到 $MODPATH，
# 并把模块目录 chcon 成 u:object_r:system_file:s0 —— Zygisk 的 Api::getModuleDir()
# 依赖这个上下文，否则注入器在 pre-specialize 阶段读不到 payload。
#
# 本模块包是「APK 一体包」：改名为 .zip 刷入时，APK 自带的 classes*.dex 与
# lib/<abi>/*.so 会原样落到 $MODPATH 根，注入器直接读它们，不再单独打 payload/。

ui_print "**********************************************"
ui_print " WCX Zygisk 独立注入模块"
ui_print " 版本: ${VERSION_NAME} (${VERSION_CODE})"
ui_print "**********************************************"

if [ "$ARCH" != "arm64" ]; then
  ui_print "! 当前模块仅支持 arm64，检测到: $ARCH"
  abort "! 请使用 arm64-v8a 的一体包"
fi

if [ "$API" -lt 28 ]; then
  ui_print "! WCX 要求 Android 9 (API 28) 及以上，当前 API: $API"
  abort "! 系统版本过低"
fi

if [ ! -f "$MODPATH/zygisk/arm64-v8a.so" ]; then
  abort "! 包内缺少 zygisk/arm64-v8a.so，zip 可能损坏"
fi

if [ ! -f "$MODPATH/zygisk/liblsplant.so" ]; then
  abort "! 包内缺少 zygisk/liblsplant.so，zip 可能损坏"
fi

# dex：一体包里在模块根目录；独立 payload 布局里在 payload/
DEX_COUNT=$(ls -1 "$MODPATH"/classes*.dex 2>/dev/null | wc -l)
if [ "$DEX_COUNT" -lt 1 ]; then
  DEX_COUNT=$(ls -1 "$MODPATH"/payload/classes*.dex 2>/dev/null | wc -l)
fi
if [ "$DEX_COUNT" -lt 1 ]; then
  abort "! 包内缺少 classes*.dex，zip 可能损坏"
fi
ui_print "- payload dex: $DEX_COUNT 个"

LIB_COUNT=$(ls -1 "$MODPATH/lib/arm64-v8a"/*.so 2>/dev/null | wc -l)
ui_print "- payload 原生库: $LIB_COUNT 个"

# 权限：模块目录里现在还有 APK 的 res/assets 等文件，统一给只读即可
set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/zygisk/arm64-v8a.so" 0 0 0644
set_perm "$MODPATH/zygisk/liblsplant.so" 0 0 0644

ui_print "- 安装完成"
ui_print "- 请确认已开启 Zygisk（Magisk / KernelSU 设置内）"
ui_print "- 重启手机后强制停止微信，再打开"
ui_print "- 日志: adb logcat -s WCXZygisk"
ui_print "- 卸载: 在管理器里移除模块后重启"
