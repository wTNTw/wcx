#!/bin/sh
# Build the WCX Zygisk loader for arm64-v8a.
#
# Usage: zygisk/native/build.sh [output-dir]
#
# Requires ANDROID_HOME (with the NDK pinned in gradle/libs.versions.toml),
# a Rust toolchain with the aarch64-linux-android target, CMake >= 3.28 and
# Ninja. Run zygisk/native/prepare-deps.sh once first.
set -eu

root=$(cd "$(dirname "$0")/../.." && pwd)
out=${1:-$root/zygisk/dist}

sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
[ -n "$sdk" ] || { echo "set ANDROID_HOME" >&2; exit 1; }

ndk_version=$(sed -n 's/^ndk *= *"\(.*\)"$/\1/p' "$root/gradle/libs.versions.toml")
[ -n "$ndk_version" ] || { echo "cannot read ndk version" >&2; exit 1; }
min_sdk=$(sed -n 's/^minSdk *= *"\(.*\)"$/\1/p' "$root/gradle/libs.versions.toml")

ndk_dir="$sdk/ndk/$ndk_version"
[ -d "$ndk_dir" ] || { echo "NDK not found: $ndk_dir" >&2; exit 1; }

export WCX_ANDROID_NDK="$ndk_dir"
export WCX_ANDROID_API="${min_sdk:-28}"

case $(uname -s) in
    Linux) host=linux-x86_64 ;;
    Darwin) host=darwin-x64 ;;
    *) echo "unsupported build host: $(uname -s)" >&2; exit 1 ;;
esac
bin="$ndk_dir/toolchains/llvm/prebuilt/$host/bin"
export CC_aarch64_linux_android="$bin/aarch64-linux-android$WCX_ANDROID_API-clang"
export CXX_aarch64_linux_android="$bin/aarch64-linux-android$WCX_ANDROID_API-clang++"
export AR_aarch64_linux_android="$bin/llvm-ar"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$CC_aarch64_linux_android"

cd "$root/zygisk/native"
cargo build --release --target aarch64-linux-android

"$bin/llvm-strip" --strip-unneeded \
    "$root/target/aarch64-linux-android/release/libwcx_zygisk.so"

mkdir -p "$out/zygisk"
cp "$root/target/aarch64-linux-android/release/libwcx_zygisk.so" "$out/zygisk/arm64-v8a.so"
echo "$out/zygisk/arm64-v8a.so"
