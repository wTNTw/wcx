#!/system/bin/sh
# WCX Zygisk 模块安装脚本
#
# 安装器（Magisk / KernelSU / APatch）已经把 zip 除 META-INF 外的内容解压到 $MODPATH，
# 并把模块目录 chcon 成 u:object_r:system_file:s0 —— Zygisk 的 Api::getModuleDir()
# 依赖这个上下文，否则注入器在 pre-specialize 阶段读不到 payload。

ui_print "**********************************************"
ui_print " WCX Zygisk 独立注入模块"
ui_print " 版本: ${VERSION_NAME} (${VERSION_CODE})"
ui_print "**********************************************"

# ── 环境校验 ────────────────────────────────────────────────────────────────
if [ "$ARCH" != "arm64" ]; then
  ui_print "! 当前模块仅支持 arm64，检测到: $ARCH"
  abort "! 请下载对应架构的包（当前只发布 arm64-v8a）"
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

DEX_COUNT=$(ls "$MODPATH/payload"/classes*.dex 2>/dev/null | wc -l)
if [ "$DEX_COUNT" -lt 1 ]; then
  abort "! 包内缺少 payload/classes*.dex，zip 可能损坏"
fi
ui_print "- payload dex: $DEX_COUNT 个"

LIB_COUNT=$(ls "$MODPATH/lib/arm64-v8a"/*.so 2>/dev/null | wc -l)
ui_print "- payload 原生库: $LIB_COUNT 个"

# ── 权限 ────────────────────────────────────────────────────────────────────
set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/zygisk/arm64-v8a.so" 0 0 0644
set_perm "$MODPATH/zygisk/liblsplant.so" 0 0 0644
for f in "$MODPATH/lib/arm64-v8a"/*.so; do
  [ -f "$f" ] && set_perm "$f" 0 0 0644
done

ui_print "- 安装完成"
ui_print "- 请确认已开启 Zygisk（Magisk / KernelSU 设置内）"
ui_print "- 重启手机后强制停止微信，再打开"
ui_print "- 日志: adb logcat -s WCXZygisk"
ui_print "- 卸载: 在管理器里移除模块后重启"
