# wcx-zygisk（移植自上游 WeKit 的 Zygisk 注入器）

本目录是从 **Ujhhgtg/WeKit**（同为 GPL-3.0）移植过来的 Zygisk 独立注入器：
纯 Rust + 少量 C++（LSPlant 桥），自带 ARM64 inline hook 与 ELF 符号解析，
**不依赖 Xposed/LSPosed**，刷 zip（= APK 改后缀）即可注入微信。

## 为什么不用自己从零写

上一轮从零实现的注入器在真机上卡在 LSPlant：Android 16 的 libart 移除了
`ClassLinker::SetEntryPointsToInterpreter` 等符号（LSPlant 6.4 直接 `Init` 失败），
而 LSPlant master 又要求 C++23 `import std` 工具链（NDK 的 CMake 探测不过）。
上游这套实现已经在你那台 Android 16 设备上跑通，直接移植风险最低。

## 结构

```
wcx-zygisk/
├── native/                 Rust crate（cargo 编出 libwcx_zygisk.so）
│   ├── build.rs            用 cmake crate 驱动 CMake：编 xz + LSPlant + lsplant_bridge
│   ├── CMakeLists.txt      静态编 third_party/xz-embedded 与 third_party/lsplant
│   ├── cpp/lsplant_bridge.cpp  C 导出：wekit_lsplant_init/hook/unhook/deoptimize
│   └── src/
│       ├── lib.rs          zygisk_module_entry / 生命周期回调
│       ├── lifecycle.rs    pre/post specialize：读 module.apk、建 InMemoryDexClassLoader、
│       │                   原生里 init LSPlant + trust class loader、调 Kotlin ZygiskEntry.init
│       ├── art/            自研 ARM64 inline hook + libart ELF 符号解析（Android 16 可用）
│       ├── apk.rs          module.apk 里取 dex / 解压原生库
│       ├── payload.rs      InMemoryDexClassLoader 构造
│       ├── natives.rs      RegisterNatives（ArtHookBridge / ZygiskEntry）
│       └── so_hider.rs     抹掉自身在 /proc/self/maps 的痕迹
├── module.prop             模块信息
├── customize.sh            SKIPUNZIP=1：把整包自身复制成 module.apk + 只解出脚本与 zygisk/*.so
├── uninstall.sh / sepolicy.rule
└── README.md
```

Kotlin 侧（`app/src/main/java/com/Johnny/wcx/loader/`）：

- `entry/zygisk/ZygiskEntry.kt` — 原生调用入口 `init(processName, dataDir, apkPath)`，
  hook `LoadedApk.createAppFactory` → `AppComponentFactory.instantiateClassLoader` 拿到**最终**宿主 ClassLoader，
  然后 `ModuleLoader.init(...)`
- `entry/zygisk/ArtHookBridge.kt` + `ArtHookBridgeRuntime.kt` — `IHookBridge` 的 LSPlant 实现（含 backup 等待）
- `entry/zygisk/ZygiskLoaderService.kt` — `ILoaderService` 实现
- `utils/ZygiskNativePayload.kt` / `loader/utils/NativeLoader.kt` — 从 `module.apk` 解出并 `System.load` 原生库

## 依赖的固定版本（已验证能编过 Android 16）

| 组件 | 版本 | 说明 |
|---|---|---|
| `third_party/lsplant` | `d8b5d1dbb664abc606644036822e4bb64547edf6` | 该提交的 CMake 只在 `LSPLANT_STANDALONE=ON` 时才 `find_package(cxx)`，且源码不用 `import std;` |
| `third_party/xz-embedded` | `ae63ae3a36ed01724674e8f3d750dc47bf125410` | module.apk 解压（deflate/xz） |
| LSPlant 内嵌 `dex_builder` | `ac7fb2230954ee311808bad469b0db501f31bfb8` | 需要 `--init` 其 `external/parallel_hashmap` |
| Android NDK | `30.0.14904198` | `ANDROID_STL=c++_static`（产物自包含，不依赖 libc++_shared） |

## 构建

```bash
git submodule update --init --recursive third_party/lsplant third_party/xz-embedded
cd third_party/lsplant && git submodule update --init --recursive \
  lsplant/src/main/jni/external/dex_builder \
  lsplant/src/main/jni/external/dex_builder/external/parallel_hashmap

export WCX_ANDROID_NDK=/path/to/ndk/30.0.14904198
export WCX_ANDROID_API=28
cargo build -p wcx-zygisk --target aarch64-linux-android --release
# 产物：target/aarch64-linux-android/release/libwcx_zygisk.so
```

产物校验（应当只有系统库依赖）：

```
NEEDED: liblog.so libz.so libdl.so libc.so
$ llvm-nm -D --defined-only libwcx_zygisk.so | grep zygisk_module_entry   # T zygisk_module_entry
```

## 运行时流程

1. 刷入后模块目录：`module.apk`（整包副本）+ `zygisk/arm64-v8a.so` + 脚本
2. `preAppSpecialize`：只放行目标进程（默认 `com.tencent.mm`），拿 module dir fd、读 `module.apk`
3. `postAppSpecialize`：从 `module.apk` 读 dex → `InMemoryDexClassLoader`；
   **原生里** `lsplant::Init` + `MakeDexFileTrusted`（避开 Java 层 HiddenApiBypass 崩溃）；
   `RegisterNatives` 后调 `ZygiskEntry.init(processName, dataDir, apkPath)`
4. Kotlin：hook `createAppFactory`/`instantiateClassLoader` 拿最终 CL → `ModuleLoader.init(...)`

## 待办

- [ ] `app/src/main/java/.../features/**` 剩余 `.method` / `invokeOriginal` 迁移收尾（当前编译未通过）
- [ ] 打包接线：让 `PackageZygiskApkTask` 用本目录模板、把 `libwcx_zygisk.so` 打进 APK 的 `lib/arm64-v8a/`
- [ ] 移除旧的 `zygisk/`（CMake + LSPlant 6.4）模块
- [ ] CI：`cargo xtask`/workflow 里准备 LSPlant 子模块并设置 `WCX_ANDROID_NDK` / `WCX_ANDROID_API`
