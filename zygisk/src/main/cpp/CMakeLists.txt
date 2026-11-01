cmake_minimum_required(VERSION 3.22)
project(wcxzygisk CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# libc++：随 prefab 提供的静态实现。ANDROID_STL=none（见 build.gradle.kts），
# 模块 .so 必须自包含 —— 设备上既没有 libc++_shared.so，
# sandbox 内也不能从 /data/adb 里 dlopen 依赖。
find_package(cxx REQUIRED CONFIG)

# Dobby：LSPlant 要求宿主提供 inline hook 实现（静态链入）
find_package(dobby REQUIRED CONFIG)

# LSPlant：头文件由仓库自带（lsplant.hpp，来源 org.lsposed.lsplant:lsplant-standalone:6.4）。
# 运行时的 liblsplant.so 由 :zygisk 从 AAR 取出并随 zip 下发，
# 注入器在 zygote 阶段 dlopen(RTLD_GLOBAL) 后用 dlsym 调用，
# 因此模块 .so 对 LSPlant **零链接期依赖**（没有 DT_NEEDED）。

add_library(wcxzygisk SHARED
        main.cpp
        hook_bridge.cpp
        elf_util.cpp
)

target_compile_options(wcxzygisk PRIVATE
        -fvisibility=hidden
        -fvisibility-inlines-hidden
        -fno-exceptions
        -fno-rtti
        -Wall
        -Wextra
        -Wno-unused-parameter
)

target_link_options(wcxzygisk PRIVATE
        -Wl,--build-id=none
        -Wl,-z,max-page-size=16384
        -Wl,-z,common-page-size=4096
)

target_link_libraries(wcxzygisk PRIVATE
        cxx::cxx
        dobby::dobby
        log
        dl
)
