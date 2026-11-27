#!/system/bin/sh
# Module action button: print the current target summary and the tail of the
# log. It never changes configuration.

STATE_DIR=/data/adb/wcx_zygisk
TARGETS_FILE=$STATE_DIR/injection-targets.tsv

echo "WCX Zygisk targets"
if [ -f "$TARGETS_FILE" ]; then
    total=$(awk -F '\t' 'NF == 3 { count++ } END { print count + 0 }' "$TARGETS_FILE")
    enabled=$(awk -F '\t' 'NF == 3 && $3 == "1" { count++ } END { print count + 0 }' "$TARGETS_FILE")
    echo "enabled $enabled / $total"
    awk -F '\t' 'NF == 3 { printf "%s %s %s\n", $1, $2, ($3 == "1" ? "on" : "off") }' "$TARGETS_FILE"
else
    echo "no target list yet; open the module WebUI and refresh"
fi
echo
echo "recent log"
[ -r "$STATE_DIR/webui.log" ] && tail -n 20 "$STATE_DIR/webui.log" || echo "(none)"
