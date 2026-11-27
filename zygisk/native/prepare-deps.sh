#!/bin/sh
# Initialise the pinned native dependencies for the WCX Zygisk loader.
#
# LSPlant's own test/documentation submodules use SSH and are not needed to
# build the runtime, so `update = none` is overridden only for the gitlink
# checkout and DexBuilder's dependencies are initialised explicitly.
set -eu

root=$(cd "$(dirname "$0")/../.." && pwd)
cd "$root"

git -c submodule.third_party/lsplant.update=checkout submodule update --init -- \
    third_party/lsplant third_party/xz-embedded

cd "$root/third_party/lsplant"
git submodule update --init --recursive -- lsplant/src/main/jni/external/dex_builder

test -f "$root/third_party/xz-embedded/linux/lib/xz/xz_dec_stream.c"
test -f "$root/third_party/lsplant/lsplant/src/main/jni/external/dex_builder/external/parallel_hashmap/CMakeLists.txt"
echo "zygisk native dependencies ready"
