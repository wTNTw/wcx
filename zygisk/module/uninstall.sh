#!/system/bin/sh
# Remove only module-owned state. WeChat data, its preferences and the module
# directory itself are handled by the root manager.

rm -f /data/adb/wcx_zygisk/injection-targets.tsv
rm -f /data/adb/wcx_zygisk/webui.log
rm -f /data/adb/wcx_zygisk/.injection-targets.scan.*
rm -f /data/adb/wcx_zygisk/.injection-targets.packages.*
rm -f /data/adb/wcx_zygisk/.injection-targets.unsorted.*
rm -f /data/adb/wcx_zygisk/.injection-targets.tmp.*
rmdir /data/adb/wcx_zygisk 2>/dev/null || true
