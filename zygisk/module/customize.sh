#!/system/bin/sh
# WCX Zygisk installer. Runs inside the root manager's installation shell with
# the archive already extracted to $MODPATH.

ui_print() { echo "$1"; }

[ "$ARCH" = "arm64" ] || abort "! WCX Zygisk supports arm64 only (device reports: ${ARCH:-unknown})"

[ -f "$MODPATH/zygisk/arm64-v8a.so" ] || abort "! Missing Zygisk loader (zygisk/arm64-v8a.so)"
[ -f "$MODPATH/module.apk" ] || abort "! Missing payload (module.apk)"

payload_size=$(wc -c < "$MODPATH/module.apk" 2>/dev/null)
case "$payload_size" in
    ''|*[!0-9]*) abort "! Cannot read payload size" ;;
esac
[ "$payload_size" -gt 1048576 ] || abort "! Payload looks truncated ($payload_size bytes)"

# Zygisk availability is reported, not enforced: managers enable it under
# different names, and a wrong guess must not block a valid installation.
zygisk_state=unknown
if [ -d /data/adb/modules/zygisksu ] || [ -d /data/adb/zygisksu ]; then
    zygisk_state=present
elif command -v magisk >/dev/null 2>&1; then
    case "$(magisk --sqlite "SELECT value FROM settings WHERE key='zygisk'" 2>/dev/null)" in
        1) zygisk_state=enabled ;;
        0) zygisk_state=disabled ;;
    esac
fi
case "$zygisk_state" in
    enabled|present) ui_print "- Zygisk: $zygisk_state" ;;
    disabled) ui_print "! Zygisk is reported as disabled; enable it and reboot." ;;
    *) ui_print "! Could not determine Zygisk state; make sure it is enabled." ;;
esac

set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/customize.sh" 0 0 0755
[ -f "$MODPATH/uninstall.sh" ] && set_perm "$MODPATH/uninstall.sh" 0 0 0755
[ -f "$MODPATH/config.sh" ] && set_perm "$MODPATH/config.sh" 0 0 0755
[ -f "$MODPATH/action.sh" ] && set_perm "$MODPATH/action.sh" 0 0 0755
set_perm "$MODPATH/module.apk" 0 0 0644

ui_print "- Installed. Reboot to load the Zygisk loader."
ui_print "- KernelSU/APatch: open the module page to select WeChat instances."
ui_print "- Magisk: targets com.tencent.mm automatically."
ui_print "- Disable the WCX LSPosed scope for the same instance to avoid double injection."
