cmake_minimum_required(VERSION 3.28)
project(wcx_zygisk_native LANGUAGES C)

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_C_VISIBILITY_PRESET hidden)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_VISIBILITY_INLINES_HIDDEN ON)

get_filename_component(WCX_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../.." ABSOLUTE)
set(XZ_SOURCE "${WCX_ROOT}/third_party/xz-embedded")
if(NOT EXISTS "${XZ_SOURCE}/linux/lib/xz/xz_dec_stream.c")
    message(FATAL_ERROR "Missing XZ sources; run git submodule update --init third_party/xz-embedded")
endif()

# Dobby：inline hook 引擎。上游自研的 Rust inline hook 在 Android 16 的 ART 函数上
# 重定位会出错（SetStatus/MarkVisiblyInitialized 处 SIGILL），改用 LSPosed 生产环境用的 Dobby。
set(DOBBY_GENERATE_SHARED OFF CACHE BOOL "" FORCE)
set(Plugin.SymbolResolver OFF CACHE BOOL "" FORCE)
set(BUILD_EXAMPLE OFF CACHE BOOL "" FORCE)
set(BUILD_TEST OFF CACHE BOOL "" FORCE)
add_subdirectory("${WCX_ROOT}/third_party/dobby" dobby)

# Compile XZ into the loader; Android liblzma does not provide XZ Utils APIs.
add_library(wcx_xz STATIC
    "${XZ_SOURCE}/linux/lib/xz/xz_crc32.c"
    "${XZ_SOURCE}/linux/lib/xz/xz_crc64.c"
    "${XZ_SOURCE}/linux/lib/xz/xz_sha256.c"
    "${XZ_SOURCE}/linux/lib/xz/xz_dec_lzma2.c"
    "${XZ_SOURCE}/linux/lib/xz/xz_dec_stream.c"
    "${XZ_SOURCE}/linux/lib/xz/xz_dec_bcj.c")
target_include_directories(wcx_xz PRIVATE
    "${XZ_SOURCE}/linux/include/linux" "${XZ_SOURCE}/userspace")
target_compile_definitions(wcx_xz PRIVATE
    XZ_DEC_SINGLE XZ_USE_CRC64 XZ_USE_SHA256 XZ_DEC_ARM64)
target_compile_options(wcx_xz PRIVATE -Wall -Wextra -Werror)
set_target_properties(wcx_xz PROPERTIES C_STANDARD 11 C_STANDARD_REQUIRED ON)
install(FILES $<TARGET_FILE:wcx_xz> DESTINATION lib)
install(FILES $<TARGET_FILE:dobby> DESTINATION lib)

# Host tests need XZ, but neither Android nor LSPlant.
if(NOT ANDROID)
    return()
endif()
if(NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(FATAL_ERROR "The WCX Zygisk bridge requires Android arm64-v8a")
endif()
enable_language(CXX)

set(LSPLANT_SOURCE "${WCX_ROOT}/third_party/lsplant/lsplant/src/main/jni")
if(NOT EXISTS "${LSPLANT_SOURCE}/external/dex_builder/external/parallel_hashmap/CMakeLists.txt")
    message(FATAL_ERROR "Missing LSPlant sources; prepare them with ./x build --native-only")
endif()

set(LSPLANT_BUILD_SHARED OFF CACHE BOOL "" FORCE)
set(LSPLANT_STANDALONE OFF CACHE BOOL "" FORCE)
add_subdirectory("${LSPLANT_SOURCE}" lsplant EXCLUDE_FROM_ALL)

add_library(wcx_lsplant_bridge STATIC cpp/lsplant_bridge.cpp)
target_link_libraries(wcx_lsplant_bridge PRIVATE lsplant_static)
target_compile_options(wcx_lsplant_bridge PRIVATE -Wall -Wextra -Werror -fexceptions)

# Cargo supplies Rust's builtins but LSPlant also emits Clang's __clear_cache.
# Link the NDK's matching compiler-rt archive; Dobby previously supplied that
# symbol incidentally. Copy the archive rather than adding a generic NDK -L path.
execute_process(
    COMMAND "${CMAKE_CXX_COMPILER}" --target=aarch64-linux-android --print-libgcc-file-name
    OUTPUT_VARIABLE WCX_COMPILER_RT
    OUTPUT_STRIP_TRAILING_WHITESPACE
    COMMAND_ERROR_IS_FATAL ANY)
if(NOT EXISTS "${WCX_COMPILER_RT}")
    message(FATAL_ERROR "Android compiler runtime not found: ${WCX_COMPILER_RT}")
endif()

# Cargo performs the final cdylib link. Export just the required archives;
# upstream's shared libraries, tests and documentation are not built or packaged.
install(FILES
    $<TARGET_FILE:wcx_lsplant_bridge>
    $<TARGET_FILE:lsplant_static>
    $<TARGET_FILE:dex_builder_static>
    "${CMAKE_SYSROOT}/usr/lib/aarch64-linux-android/libc++_static.a"
    "${CMAKE_SYSROOT}/usr/lib/aarch64-linux-android/libc++abi.a"
    DESTINATION lib)
install(FILES "${WCX_COMPILER_RT}" DESTINATION lib RENAME libwcx_compiler_rt.a)
