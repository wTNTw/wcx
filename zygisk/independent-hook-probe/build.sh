#!/usr/bin/env bash
# Debian build of a debuggable, self-contained ART TI diagnostic. Not a WCX APK.
set -euo pipefail

src=$(cd -- "$(dirname -- "$0")" && pwd)
out=${1:?Usage: bash zygisk/independent-hook-probe/build.sh OUTPUT_DIR}
sdk=${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK}
bin="$sdk/build-tools/37.0.0"
android="$sdk/platforms/android-37.0/android.jar"
ndk=${ANDROID_NDK_HOME:-"$sdk/ndk/30.0.14904198"}
llvm="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin"
jdk=$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")

mkdir -p -- "$out"
out=$(cd -- "$out" && pwd)
work=$(mktemp -d "$out/.hook-probe.XXXXXX")
trap 'rm -r -- "$work"' EXIT
mkdir -p "$work/include" "$work/classes" "$work/stage/lib/arm64-v8a"
# Use the OpenJDK JVMTI declaration but keep the NDK's JNI and platform headers.
cp "$jdk/include/jvmti.h" "$work/include/"
so="$work/stage/lib/arm64-v8a/libwcx_independent_hook_probe.so"
"$llvm/aarch64-linux-android28-clang++" -shared -fPIC -O2 -std=c++17 -fno-exceptions -fno-rtti \
    -nostdlib++ -Werror -Wall -Wextra -fvisibility=hidden -I "$work/include" "$src/agent.cpp" -o "$so"
elf=$("$llvm/llvm-readelf" -h "$so")
grep -E 'Machine:[[:space:]]+AArch64' <<< "$elf"
symbols=$("$llvm/llvm-readelf" --dyn-syms --wide "$so")
grep -E 'GLOBAL +DEFAULT +[0-9]+ +Agent_OnAttach$' <<< "$symbols"

javac -g --release 17 -cp "$android" -d "$work/classes" "$src/MainActivity.java"
"$bin/d8" --min-api 28 --lib "$android" --output "$work/stage" \
    "$work"/classes/dev/wcx/independenthook/probe/MainActivity*.class
"$bin/aapt2" link --manifest "$src/AndroidManifest.xml" -I "$android" \
    --min-sdk-version 28 --target-sdk-version 36 -o "$work/base.apk"
(cd "$work/stage" && zip -0q "$work/base.apk" classes.dex lib/arm64-v8a/libwcx_independent_hook_probe.so)
"$bin/zipalign" -P 16 -f 4 "$work/base.apk" "$work/aligned.apk"
keytool -genkeypair -alias probe -keyalg RSA -keysize 2048 -validity 30 \
    -dname 'CN=WCX independent hook diagnostic' -keystore "$work/probe.p12" -storepass probe-only -noprompt
name=wcx-independent-hook-probe.apk
"$bin/apksigner" sign --ks "$work/probe.p12" --ks-pass pass:probe-only --out "$work/$name" "$work/aligned.apk"
"$bin/apksigner" verify "$work/$name"
badging=$("$bin/aapt2" dump badging "$work/$name")
grep -F "package: name='dev.wcx.independenthook.probe'" <<< "$badging"
grep -Fx 'application-debuggable' <<< "$badging"
(cd "$work" && sha256sum "$name" > SHA256SUMS)
mv -- "$work/$name" "$work/SHA256SUMS" "$out/"
printf 'Output: %s/%s\n' "$out" "$name"
