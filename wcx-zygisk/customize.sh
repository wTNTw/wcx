# shellcheck disable=SC2034
SKIPUNZIP=1

# Zygisk - WCX 一体包安装脚本（APK 改名为 .zip 刷入）
#
# 设计要点：
#   * SKIPUNZIP=1：不整包解压，避免把 APK 的 res/assets/dex 铺满模块目录
#   * 把「整包本身」复制成 $MODPATH/module.apk —— 注入器在目标进程里读它
#     （PathClassLoader 直接 mmap，冷启动不必把 dex 读进内存）
#   * 只解出模块运行需要的文件；注入器 lib/arm64-v8a/libwcx_zygisk.so → zygisk/arm64-v8a.so
#   * 保留用户已有的 target.list / disable（WebUI 的作用域与总开关）

ACTIVE_DIR=/data/adb/modules/zygisk-wcx

if [ "$BOOTMODE" = true ]; then
  ui_print "- 通过 Root 管理器安装（KernelSU/APatch/Magisk）"
else
  abort "! 请通过 Root 管理器应用安装，不支持 Recovery"
fi

[ "$ARCH" = arm64 ] || abort "! 仅支持 arm64，当前: $ARCH"
unzip -t "$ZIPFILE" >/dev/null 2>&1 || abort "! WCX APK 已损坏"
unzip -l "$ZIPFILE" > "$TMPDIR/wcx-apk-entries" || abort "! 无法列出 APK 内容"

for entry in module.prop customize.sh uninstall.sh config.sh service.sh \
  webroot/index.html webroot/css/app.css webroot/js/bridge.js webroot/js/app.js \
  META-INF/com/google/android/update-binary META-INF/com/google/android/updater-script \
  AndroidManifest.xml classes.dex resources.arsc \
  lib/arm64-v8a/libwcx_zygisk.so lib/arm64-v8a/libwekit_native.so
do
  awk -v entry="$entry" '$NF == entry { count++ } END { exit count != 1 }' \
    "$TMPDIR/wcx-apk-entries" || abort "! APK 条目缺失或重复: $entry"
done
rm -f "$TMPDIR/wcx-apk-entries"

ui_print "- 解压模块文件"
for entry in module.prop uninstall.sh config.sh service.sh \
  webroot/index.html webroot/css/app.css webroot/js/bridge.js webroot/js/app.js
do
  unzip -o "$ZIPFILE" "$entry" -d "$MODPATH" >/dev/null || abort "! 无法解压 $entry"
done

# 保留上一版的作用域；disable 只在「自愈自动停用」时恢复，其余情况重装即视为重新启用
if [ -f "$ACTIVE_DIR/target.list" ]; then
  cp -f "$ACTIVE_DIR/target.list" "$MODPATH/target.list" || ui_print "! 恢复 target.list 失败"
  ui_print "- 已保留 target.list"
fi
if [ -f "$ACTIVE_DIR/self-heal.log" ]; then
  cp -f "$ACTIVE_DIR/self-heal.log" "$MODPATH/self-heal.log" 2>/dev/null
  ui_print "- 上次因连续启动失败被自动停用（见 self-heal.log）；本次重装已重新启用"
fi
if [ -f "$ACTIVE_DIR/disable" ] && [ ! -f "$ACTIVE_DIR/self-heal.log" ]; then
  cp -f "$ACTIVE_DIR/disable" "$MODPATH/disable" || ui_print "! 恢复 disable 失败"
  ui_print "- 已保留 disable（上一个版本是手动停用的）"
fi

ui_print "- 放置 Zygisk 注入器"
mkdir -p "$MODPATH/zygisk" || abort "! 无法创建 zygisk 目录"
unzip -p "$ZIPFILE" lib/arm64-v8a/libwcx_zygisk.so > "$TMPDIR/wcx-zygisk.so" ||
  abort "! 无法解压 Zygisk 原生库"
mv -f "$TMPDIR/wcx-zygisk.so" "$MODPATH/zygisk/arm64-v8a.so" ||
  abort "! 无法写入 zygisk/arm64-v8a.so"

ui_print "- 保存原始 APK 作为 payload"
cp "$ZIPFILE" "$MODPATH/module.apk.tmp" || abort "! 无法复制 APK"
mv -f "$MODPATH/module.apk.tmp" "$MODPATH/module.apk" || abort "! 无法写入 module.apk"

set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/zygisk/arm64-v8a.so" 0 0 0644
set_perm "$MODPATH/module.apk" 0 0 0644
set_perm "$MODPATH/module.prop" 0 0 0644
set_perm "$MODPATH/config.sh" 0 0 0755
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755

ui_print "**********************************************"
ui_print "- Zygisk - WCX 安装完成"
ui_print "- 在 Root 管理器里打开模块 WebUI 选择注入作用域"
ui_print "- 重启设备以加载注入器"
ui_print "- 日志: adb logcat -s WCX"
ui_print "**********************************************"
