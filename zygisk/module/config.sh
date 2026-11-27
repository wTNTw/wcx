#!/system/bin/sh
# Target configuration backend for the KernelSU/APatch WebUI.
#
# Commands:
#   list                 print "userId<TAB>package<TAB>enabled" rows
#   refresh              rescan installed WeChat packages; new rows start disabled
#   set <user> <pkg> <0|1>
#   log                  print the WebUI log
#
# Only packages starting with $TARGET_PACKAGE_PREFIX are accepted. All writes
# go through a temporary file plus rename, so a killed command cannot leave a
# half-written target list.

STATE_DIR=/data/adb/wcx_zygisk
TARGETS_FILE=$STATE_DIR/injection-targets.tsv
LOCK_DIR=$STATE_DIR/.lock
LOG_FILE=$STATE_DIR/webui.log
LOG_MAX_BYTES=65536
TARGET_PACKAGE_PREFIX=com.tencent.mm
umask 077
export PATH=/system/bin:/system/xbin:/vendor/bin:$PATH

log_event() {
    mkdir -p "$STATE_DIR" 2>/dev/null || return 0
    if [ -f "$LOG_FILE" ]; then
        size=$(wc -c < "$LOG_FILE" 2>/dev/null)
        case "$size" in
            ''|*[!0-9]*) size=0 ;;
        esac
        [ "$size" -le "$LOG_MAX_BYTES" ] || : > "$LOG_FILE"
    fi
    printf '%s %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*" >> "$LOG_FILE" 2>/dev/null
    chmod 600 "$LOG_FILE" 2>/dev/null || true
}

fail() {
    log_event "ERROR $*"
    echo "$*" >&2
    exit 1
}

acquire_lock() {
    mkdir -p "$STATE_DIR" || fail "cannot create $STATE_DIR"
    chmod 700 "$STATE_DIR" 2>/dev/null || true
    attempt=0
    while ! mkdir "$LOCK_DIR" 2>/dev/null; do
        if [ -r "$LOCK_DIR/pid" ]; then
            read -r owner < "$LOCK_DIR/pid" || owner=
            case "$owner" in
                ''|*[!0-9]*) ;;
                *) kill -0 "$owner" 2>/dev/null || { rm -rf "$LOCK_DIR"; continue; } ;;
            esac
        fi
        attempt=$((attempt + 1))
        [ "$attempt" -lt 10 ] || fail "timed out waiting for $LOCK_DIR"
        sleep 1
    done
    printf '%s\n' "$$" > "$LOCK_DIR/pid"
}

release_lock() {
    rm -rf "$LOCK_DIR"
}

is_valid_user() {
    case "$1" in
        ''|*[!0-9]*) return 1 ;;
        *) return 0 ;;
    esac
}

is_valid_package() {
    case "$1" in
        "$TARGET_PACKAGE_PREFIX"|"$TARGET_PACKAGE_PREFIX".*) return 0 ;;
        *) return 1 ;;
    esac
}

write_header() {
    printf '%s\n' '# WCX Zygisk injection targets v1'
    printf '%s\n' '# userId<TAB>packageName<TAB>enabled'
}

publish() {
    source_file=$1
    temp_file=$TARGETS_FILE.tmp.$$
    if ! { write_header; cat "$source_file"; } > "$temp_file"; then
        rm -f "$temp_file"
        fail "cannot write $temp_file"
    fi
    chmod 600 "$temp_file" || { rm -f "$temp_file"; fail "cannot chmod $temp_file"; }
    mv -f "$temp_file" "$TARGETS_FILE" || { rm -f "$temp_file"; fail "cannot publish targets"; }
    log_event "INFO published $(wc -l < "$TARGETS_FILE") line(s)"
}

list_user_ids() {
    if cmd user list > /dev/null 2>&1; then
        cmd user list 2>/dev/null | sed -n 's/.*UserInfo{\([0-9][0-9]*\):.*/\1/p'
    else
        find /data/system/users -maxdepth 1 -type d 2>/dev/null |
            sed -n 's#^.*/\([0-9][0-9]*\)$#\1#p'
    fi
}

scan_packages() {
    user_id=$1
    if cmd package list packages --user "$user_id" > /dev/null 2>&1; then
        cmd package list packages --user "$user_id" 2>/dev/null
    else
        pm list packages --user "$user_id" 2>/dev/null
    fi | sed -n 's/^package://p'
}

existing_state() {
    [ -f "$TARGETS_FILE" ] || return 0
    awk -F '\t' 'NF == 3 && $3 == "1" { print $1 "\t" $2 }' "$TARGETS_FILE"
}

cmd_list() {
    [ -f "$TARGETS_FILE" ] || return 0
    awk -F '\t' -v prefix="$TARGET_PACKAGE_PREFIX" '
        NF == 3 && $1 ~ /^[0-9]+$/ && index($2, prefix) == 1 && ($3 == "0" || $3 == "1") {
            print $1 "\t" $2 "\t" $3
        }' "$TARGETS_FILE"
}

cmd_refresh() {
    scan=$STATE_DIR/.scan.$$
    enabled=$STATE_DIR/.enabled.$$
    : > "$scan" || fail "cannot create $scan"
    existing_state > "$enabled" || fail "cannot read existing state"
    users=0
    for user_id in $(list_user_ids); do
        is_valid_user "$user_id" || continue
        users=$((users + 1))
        scan_packages "$user_id" | while read -r package_name; do
            is_valid_package "$package_name" || continue
            printf '%s\t%s\n' "$user_id" "$package_name" >> "$scan"
        done
    done
    [ "$users" -gt 0 ] || { rm -f "$scan" "$enabled"; fail "no Android user could be scanned"; }

    merged=$STATE_DIR/.merged.$$
    sort -u -k1,1n -k2,2 "$scan" | awk -F '\t' -v OFS='\t' '
        NR == FNR { on[$0] = 1; next }
        { print $1, $2, (($1 "\t" $2) in on ? "1" : "0") }' "$enabled" - > "$merged" ||
        { rm -f "$scan" "$enabled" "$merged"; fail "cannot merge scan results"; }
    publish "$merged"
    rm -f "$scan" "$enabled" "$merged"
}

cmd_set() {
    user_id=$1
    package_name=$2
    enabled=$3
    is_valid_user "$user_id" || fail "invalid Android user id"
    is_valid_package "$package_name" || fail "only $TARGET_PACKAGE_PREFIX packages are supported"
    case "$enabled" in
        0|1) ;;
        *) fail "invalid enabled value" ;;
    esac
    [ -f "$TARGETS_FILE" ] || fail "target list does not exist; refresh first"
    temp_file=$TARGETS_FILE.tmp.$$
    awk -F '\t' -v OFS='\t' -v user="$user_id" -v package="$package_name" -v enabled="$enabled" '
        $1 == user && $2 == package { print $1, $2, enabled; found = 1; next }
        { print }' "$TARGETS_FILE" > "$temp_file"
    status=$?
    [ "$status" -eq 0 ] || { rm -f "$temp_file"; fail "cannot update target"; }
    chmod 600 "$temp_file" || { rm -f "$temp_file"; fail "cannot chmod $temp_file"; }
    mv -f "$temp_file" "$TARGETS_FILE" || { rm -f "$temp_file"; fail "cannot publish targets"; }
    log_event "INFO set user=$user_id package=$package_name enabled=$enabled"
}

cmd_log() {
    if [ -r "$LOG_FILE" ]; then tail -n 200 "$LOG_FILE"; else echo "no log yet: $LOG_FILE"; fi
}

case "$1" in
    list) cmd_list ;;
    refresh) acquire_lock; cmd_refresh; release_lock ;;
    set) cmd_set "$2" "$3" "$4" ;;
    log) cmd_log ;;
    *)
        echo "usage: $0 {list|refresh|set <user> <package> <0|1>|log}" >&2
        exit 64
        ;;
esac
