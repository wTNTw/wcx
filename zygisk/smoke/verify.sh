#!/usr/bin/env bash
# Host-side artifact check only; passing this does not prove Zygisk ran on a device.
set -euo pipefail

out=${1:?Usage: bash zygisk/smoke/verify.sh OUTPUT_DIR}
sdk=${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK}
bin="$sdk/build-tools/37.0.0"
ndk=${ANDROID_NDK_HOME:-"$sdk/ndk/30.0.14904198"}
package=dev.wcx.zygisk.probe
apk="$out/wcx-zygisk-smoke.apk"
module="$out/wcx-zygisk-smoke-module.zip"
for file in "$apk" "$module" "$out/SHA256SUMS"; do
    [[ -f "$file" ]] || { printf 'Missing smoke artifact: %s\n' "$file" >&2; exit 1; }
done

(cd "$out" && sha256sum --check SHA256SUMS)
badging=$("$bin/aapt2" dump badging "$apk")
grep -F "package: name='$package'" <<< "$badging"
grep -F "launchable-activity: name='$package.MainActivity'" <<< "$badging"
"$bin/apksigner" verify "$apk"

unzip -tqq "$module"
expected=$'module.prop\nzygisk/arm64-v8a.so'
[[ $(unzip -Z1 "$module" | LC_ALL=C sort) == "$expected" ]]
properties=$(unzip -p "$module" module.prop)
grep -Fx 'id=wcx_zygisk_probe' <<< "$properties"

work=$(mktemp -d)
trap 'rm -r -- "$work"' EXIT
unzip -p "$module" zygisk/arm64-v8a.so > "$work/probe.so"
readelf="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
elf=$("$readelf" -h "$work/probe.so")
grep -E 'Class:[[:space:]]+ELF64' <<< "$elf"
grep -E 'Machine:[[:space:]]+AArch64' <<< "$elf"
symbols=$("$readelf" --dyn-syms --wide "$work/probe.so")
grep -E 'GLOBAL +DEFAULT +[0-9]+ +zygisk_module_entry$' <<< "$symbols"
printf 'Smoke artifacts verified (device execution not tested).\n'
