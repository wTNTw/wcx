#!/usr/bin/env bash
# Build only the diagnostic APK and log-only module. Run on Debian x86_64.
set -euo pipefail

src=$(cd -- "$(dirname -- "$0")" && pwd)
out=${1:?Usage: bash zygisk/smoke/build.sh OUTPUT_DIR}
sdk=${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK}
bin="$sdk/build-tools/37.0.0"
android_jar="$sdk/platforms/android-37.0/android.jar"
ndk=${ANDROID_NDK_HOME:-"$sdk/ndk/30.0.14904198"}
compiler="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android28-clang++"
api_rev=8ce26128f81baaed0b969aaf7f52f886b61af4ab
api_sha=f8d55e8b4f89d418c5941afe62ce6a09ddec1f4afd9a1b0a01eb40a93310dd28

mkdir -p -- "$out"
out=$(cd -- "$out" && pwd)
work=$(mktemp -d "$out/.wcx-smoke.XXXXXX")
trap 'rm -r -- "$work"' EXIT
mkdir -p -- "$work/stage/zygisk" "$work/classes" "$work/dex"
if [[ -n ${ZYGISK_API_HEADER:-} ]]; then
    cp -- "$ZYGISK_API_HEADER" "$work/zygisk.hpp"
else
    curl --fail --silent --show-error --location --retry 2 \
        "https://raw.githubusercontent.com/topjohnwu/zygisk-module-sample/$api_rev/module/jni/zygisk.hpp" \
        -o "$work/zygisk.hpp"
fi
printf '%s  %s\n' "$api_sha" "$work/zygisk.hpp" | sha256sum --check

"$compiler" -O2 -shared -fPIC -std=c++17 -fno-exceptions -fno-rtti \
    -fvisibility=hidden -fvisibility-inlines-hidden -nostdlib++ \
    -I "$work" "$src/module.cpp" -llog -lstdc++ -Wl,--no-undefined \
    -o "$work/stage/zygisk/arm64-v8a.so"
cp -- "$src/module.prop" "$work/stage/module.prop"
(cd "$work/stage" && zip -q "$work/wcx-zygisk-smoke-module.zip" module.prop zygisk/arm64-v8a.so)

javac --release 17 -cp "$android_jar" -d "$work/classes" "$src/MainActivity.java"
"$bin/d8" --min-api 28 --lib "$android_jar" --output "$work/dex" \
    "$work/classes/dev/wcx/zygisk/probe/MainActivity.class"
"$bin/aapt2" link --manifest "$src/AndroidManifest.xml" -I "$android_jar" \
    --min-sdk-version 28 --target-sdk-version 36 -o "$work/base.apk"
(cd "$work/dex" && zip -q "$work/base.apk" classes.dex)
"$bin/zipalign" -f 4 "$work/base.apk" "$work/aligned.apk"

# Throwaway diagnostic key only. Never use the WCX release key for this APK.
keytool -genkeypair -alias smoke -keyalg RSA -keysize 2048 -validity 30 \
    -dname 'CN=WCX local smoke test' -keystore "$work/smoke.p12" -storepass probe-only -noprompt
"$bin/apksigner" sign --ks "$work/smoke.p12" --ks-pass pass:probe-only --ks-key-alias smoke \
    --out "$work/wcx-zygisk-smoke.apk" "$work/aligned.apk"
(cd "$work" && sha256sum wcx-zygisk-smoke.apk wcx-zygisk-smoke-module.zip > SHA256SUMS)
bash "$src/verify.sh" "$work"
for name in wcx-zygisk-smoke.apk wcx-zygisk-smoke-module.zip SHA256SUMS; do
    mv -- "$work/$name" "$out/$name"
done
printf 'Output: %s\n' "$out"
