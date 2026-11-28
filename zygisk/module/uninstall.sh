#!/system/bin/sh
# Remove only module-owned state. WeChat data, its preferences and the module
# directory itself are handled by the root manager.

STATE_DIR=/data/adb/wcx_zygisk

rm -rf "$STATE_DIR/.lock"
rm -f "$STATE_DIR/injection-targets.tsv"
rm -f "$STATE_DIR/webui.log"
# Temporary files left by an interrupted refresh/set: config.sh names them
# .scan.$$ / .enabled.$$ / .merged.$$ and their targets-file variants.
rm -f "$STATE_DIR"/.scan.*
rm -f "$STATE_DIR"/.enabled.*
rm -f "$STATE_DIR"/.merged.*
rm -f "$STATE_DIR"/.injection-targets.*
rm -f "$STATE_DIR"/.users.*
rm -f "$STATE_DIR"/.packages.*
rmdir "$STATE_DIR" 2>/dev/null || true
