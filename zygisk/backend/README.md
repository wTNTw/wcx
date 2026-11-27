# WCX Zygisk 上游运行时导入清单

本文件记录 `zygisk` 分支从 WeKit Zygisk 运行时选择导入的组件、它们的实际行为、WCX 需要的改动以及明确排除的部分。它只描述代码审计与构建结果，不构成任何设备验证结论。

## 1. 固定来源

| 项 | 值 |
| --- | --- |
| 上游仓库 | `https://github.com/Ujhhgtg/WeKit` |
| 固定提交 | `b51532bf3427348af824d7d704e55081fac9ca55` |
| 导入目标 | `zygisk/native/`（crate `wcx-zygisk`，产物 `libwcx_zygisk.so`） |
| 子模块 | `third_party/lsplant` @ `d8b5d1dbb664abc606644036822e4bb64547edf6`、`third_party/xz-embedded` @ `ae63ae3a36ed01724674e8f3d750dc47bf125410` |

依赖版本由工作区 `Cargo.lock` 固定（`zygisk/native` 是 workspace member）。上游该提交没有锁定文件，这一项是 WCX 增补。

### 许可证

| 组件 | 许可证 |
| --- | --- |
| LSPlant | LGPL-3.0（静态链接进 loader） |
| XZ Embedded | 0BSD |
| Rust crates（`libc`/`jni`/`anyhow`/`sha2`/`zip`/`cmake`） | Apache-2.0 或 MIT，逐项见 `Cargo.lock` |

LSPlant 为 LGPL-3.0 且静态链接，分发时必须提供对应源码与可重链接材料。本项目按 LGPL 合规处理（用户已确认）。

## 2. 原生模块清单

| 文件 | 职责 | WCX 改动 |
| --- | --- | --- |
| `src/lib.rs` | 导出 `zygisk_module_entry` / `zygisk_companion_entry`，注册 `ModuleAbi`（`api_version: 4`） | 类型改名 `WcxModule`；删除 Telegram socket 全局 |
| `src/zygisk.rs` | 官方 Zygisk API 的 `#[repr(C)]` 重声明 | 未改动 |
| `src/lifecycle.rs` | `preAppSpecialize` 目标判定 + 模块目录 FD；`postAppSpecialize` 发布 APK、建 `InMemoryDexClassLoader`、初始化 ART、trust DEX、回调 Java 入口；`preServerSpecialize` dlclose | 删除 Telegram 协商；入口类改为 `com.Johnny.wcx...ZygiskEntry` |
| `src/companion.rs` | root companion：注入 allow-list 判定 | **重写**：只保留 allow-list 查询；新增严格包名/进程名判定与单元测试 |
| `src/protocol.rs` | companion 二进制协议 | 删除 Telegram 常量与未使用助手 |
| `src/payload.rs` | 按内容哈希发布 APK（0600 临时 → 0400 最终、原子 rename） | 临时文件前缀 `.wcx-bootstrap-` |
| `src/apk.rs` | 从 APK 读 DEX（APK ≤256MiB，单 DEX ≤64MiB，合计 ≤256MiB） | 未改动 |
| `src/art/mod.rs` | ART 初始化、符号解析回调、`MakeDexFileTrusted`、hook/deoptimize/allocate | 未改动 |
| `src/art/elf.rs` | 解析 `libart.so` 取 ART 内部符号 | 未改动 |
| `src/art/inline_hook/*` | ARM64 入口 inline hook（备份、near veneer、重定位、页权限） | memfd 名称改为 `wcx-inline-hook` |
| `src/natives.rs` | `RegisterNatives`：bridge 与 ZygiskEntry | 删除 Telegram JNI 与相关注册；库隐藏改为接收目录参数 |
| `src/so_hider.rs` | `/proc/self/maps` 解析 + 段重映射 | **重写**：精确目录 + 文件名白名单、重映射前复核、失败报告 |
| `src/logging.rs` | logcat 输出 | 日志前缀 `WcxZygisk:` |
| `cpp/lsplant_bridge.cpp` | LSPlant 的 C ABI 桥 | 符号改为 `wcx_lsplant_*` |
| `CMakeLists.txt`、`build.rs` | 静态链接 LSPlant/DexBuilder/libc++/compiler-rt；仅 `arm64-v8a` | 目标与变量改名 |

## 3. Kotlin 侧清单

| 文件 | 职责 |
| --- | --- |
| `app/src/zygisk/java/.../ArtHookBridge.kt` | `IHookBridge` 实现（转调 runtime），并实现 `IModuleLibraryHider` |
| `app/src/zygisk/java/.../ArtHookBridgeRuntime.kt` | 回调注册表、优先级、原方法调用、备份等待 |
| `app/src/zygisk/java/.../ZygiskEntry.kt` | native 调用的入口：校验包名、注册 bridge native、`configurePayload`、hook `LoadedApk.createAppFactory` 与 `AppComponentFactory.instantiateClassLoader`、交给 `ModuleLoader` |
| `app/src/zygisk/java/.../ZygiskLoaderService.kt` | `ILoaderService` 实现 |
| `app/src/zygisk/java/.../ZygiskNativePayload.kt` | 从 payload APK 解出 `.so` 到 app 私有目录并 `System.load` |
| `app/src/main/java/.../NativePayloadSource.kt` | main 侧的中性接口，使普通 APK 不引用 Zygisk 代码 |
| `app/src/main/java/.../IModuleLibraryHider.kt` | 同上：库隐藏能力的可选接口 |

`NativeLoader` 由「类初始化即 `System.loadLibrary`」改为按需加载，并支持 payload 来源；`HybridClassLoader` 增加 `moduleClassLoader` 以处理 `InMemoryDexClassLoader`；`UnifiedEntryPoint` 在检测到该 loader 时登记它。Xposed 路径行为保持不变。

## 4. 与安全/诊断相关的实际行为

以下行为在 WCX 中仍然生效，必须在支持文档中如实披露（用户已确认按上游保留）：

1. **隐藏 API 豁免**：`XposedStartup.ensureHiddenApiAccess()` 在检测不到 `ContextImpl.mActivityToken`/`mToken` 时执行 `HiddenApiBypass.setHiddenApiExemptions("L")`。Zygisk 路径同样执行。
2. **宿主保护目录删除**：同一启动流程删除 `app_qqprotect`。
3. **模块 DEX trust**：`art::trust_class_loader` 反射读取 `DexFile.mCookie` 后调用 `lsplant::MakeDexFileTrusted`；任一 DexFile 失败即整体失败。
4. **ART 私有符号解析**：解析 `libart.so` 供 LSPlant 使用；`INITIALIZED`/`ART_SYMBOLS` 为 `OnceLock`，**初始化失败在本进程内不会重试**。
5. **代码改写**：`art/inline_hook/*` 改写 ART 函数入口并保留备份，不安装信号、不暂停其他线程；`memory.rs` 只用 `MAP_FIXED_NOREPLACE`。
6. **库路径隐藏**：`so_hider` 以「payload 目录 + 白名单文件名」精确匹配（`libwekit_native.so`、`libdexkit.so`、`libmmkv.so`），重映射前复核段未变化，失败只影响该段并返回 `-1`。上游按裸库名子串匹配且无条件 `MAP_FIXED`，WCX 收窄了这两点。
7. **companion 判定**：只回答「该 UID + 进程名是否启用」。包名必须是 `com.tencent.mm` 或以 `com.tencent.mm.` 开头（上游仅 `starts_with`，会匹配 `com.tencent.mmx`，WCX 已修）；进程名必须是包名本身或以 `<package>:` 开头。

## 5. 明确排除

- Telegram 会话协商、包发现、`cache4.db`/WAL/SHM 读取，及对应 JNI 与 Kotlin 客户端。
- 宽松 SELinux 规则：`sepolicy.rule` 未导入，模块不含该文件。
- 任意文件复制、命令执行或其他应用数据访问的 companion 命令。

## 6. 实测结果（构建机，非真机）

在 Debian 13 构建机（JDK 21、SDK platform 37.0、build-tools 37.0.0、NDK 30.0.14904198、Rust 1.102 nightly、CMake 3.31.6、Ninja 1.12.1）上：

- `sh zygisk/native/prepare-deps.sh`：拉取并检出两个子模块及 DexBuilder/parallel-hashmap。
- `sh zygisk/native/build.sh`：编译通过；`zygisk/arm64-v8a.so` 为 ELF64/AArch64/DYN，导出 `zygisk_module_entry` 与 `zygisk_companion_entry`。
- payload APK（`-PwcxZygisk=true`、legacy flavor、arm64-v8a）构建通过，含 Zygisk 入口类、无 `assets/xposed_init`、无 `META-INF/xposed/`、manifest 无 Xposed 声明与 libxposed provider。
- 三种管理器 ZIP 由 `package_zip.py pack` 生成并通过 `verify`（模块 ID/版本、归档路径、安装脚本前提、loader ELF 与导出、payload 包名/签名/版本/ARM64 库集合）。
- 宿主机单元测试：归档规则与打包原子性共 17 项通过。

**未验证**：真机安装、Zygisk 生命周期回调、LSPlant 初始化与 DEX trust、hook 语义、`so_hider` 实际映射效果、目标启停、子进程注入、停用/卸载回退。构建成功不等于运行时可用。

## 7. 已知限制

- payload 由测试密钥签名时仅用于构建校验；正式发布必须使用项目固定签名 keystore。
- 目前只编译 `arm64-v8a`；payload APK 的 `armeabi-v7a` 分片不参与打包。
