# WCX Zygisk 上游运行时导入清单

本文件记录 `zygisk` 分支从 WeKit Zygisk 运行时选择导入的组件、它们的实际行为、WCX 需要的改动以及明确排除的部分。它只描述代码审计结果，不构成任何设备验证结论。

## 1. 固定来源

| 项 | 值 |
| --- | --- |
| 上游仓库 | `https://github.com/Ujhhgtg/WeKit` |
| 固定提交 | `b51532bf3427348af824d7d704e55081fac9ca55` |
| 子项目路径 | `wekit-zygisk/`（native + template）、`app/src/main/java/dev/ujhhgtg/wekit/loader/entry/zygisk/` |
| 审计方式 | 克隆该提交后本地静态阅读，未执行上游二进制 |

审计提交处 `wekit-zygisk/` 不含 `Cargo.lock`；依赖版本由 `native/Cargo.toml` 的宽松声明决定（`libc = "0.2"`、`jni = "0.22"`、`anyhow = "1"`、`sha2 = "0.11"`、`zip = "2.4.2"`、构建期 `cmake = "0.1"`）。导入时必须补一份锁定文件，否则同一提交两次构建可能得到不同 native 产物。

### 许可证

| 组件 | 许可证 | 来源 |
| --- | --- | --- |
| LSPlant | LGPL-3.0 | `third_party/lsplant` 子模块，`LSPosed/LSPlant` |
| XZ Embedded | 0BSD | `third_party/xz-embedded` 子模块，`tukaani-project/xz-embedded` |
| Rust crates | Apache-2.0 / MIT（按 crate） | crates.io，需随锁定文件逐项登记 |
| 上游 Rust 代码与模板 | 跟随 WeKit 仓库许可证 | 导入时需确认并保留原始声明 |

LGPL-3.0 的静态链接会影响 WCX 的分发义务（提供对应源码/可重链接对象）。这一点在导入前必须由项目负责人确认，不应默默带过。

## 2. 原生模块清单

| 上游文件 | 行数 | 职责 | WCX 处理 |
| --- | --- | --- | --- |
| `native/src/lib.rs` | 65 | 导出 `zygisk_module_entry` / `zygisk_companion_entry`，注册 `ModuleAbi`（`api_version: 4`） | 保留；改为 WCX 模块入口与状态对象 |
| `native/src/zygisk.rs` | 119 | 官方 Zygisk API 的 `#[repr(C)]` 重声明（`ApiTable` 10 字段、`ModuleAbi`、`AppSpecializeArgs`） | 保留；与锁定头文件逐字段对照 |
| `native/src/lifecycle.rs` | 312 | `preAppSpecialize` 目标判定 + 模块目录 FD；`postAppSpecialize` 发布 APK、建 `InMemoryDexClassLoader`、初始化 ART、trust DEX、回调 Java 入口；`preServerSpecialize` 退出 | 保留结构；目标判定与进程范围按 WCX 规则 |
| `native/src/companion.rs` | 488 | root companion：注入 allow-list 判定 + (上游) Telegram 会话/数据库 | 仅保留 allow-list 判定路径；Telegram 全部移除 |
| `native/src/protocol.rs` | 172 | companion 二进制协议常量与 fd 读写 | 保留 allow-list 所需子集 |
| `native/src/payload.rs` | 198 | 按内容哈希发布 APK 到 app 私有目录（0600 临时 → 0400 最终、原子 rename），并创建 `InMemoryDexClassLoader` | 保留语义，路径改为 WCX 子目录 |
| `native/src/apk.rs` | 222 | 从 APK 读 DEX；上限 `MAX_APK_BYTES=256MiB`、`MAX_DEX_BYTES=64MiB`、`MAX_TOTAL_DEX_BYTES=256MiB` | 保留 |
| `native/src/art/mod.rs` | 301 | ART 初始化、符号解析回调、`MakeDexFileTrusted`、`hook_method`/`deoptimize`/`allocate_instance` | 保留；LSPlant 版本固定 |
| `native/src/art/elf.rs` | 632 | 解析 `libart.so` ELF，按符号名解析 ART 内部符号 | 保留 |
| `native/src/art/inline_hook/{mod,aarch64,memory}.rs` | — | ARM64 入口 inline hook：备份、near veneer、指令重定位、页权限切换 | 保留（这是 LSPlant 的必要前提） |
| `native/src/natives.rs` | 467 | `RegisterNatives` 注册 bridge 与 ZygiskEntry native 方法；含 `nativeHideLoadedModuleLibraries` 与 Telegram JNI | 保留 bridge/entry 注册；删除 Telegram JNI |
| `native/src/so_hider.rs` | 223 | `/proc/self/maps` 解析 + memfd/mprotect/MAP_FIXED 段重映射 | 保留但收窄匹配（见第 4 节） |
| `native/src/logging.rs` | 60 | logcat 输出 | 保留，标签改为 WCX |
| `native/cpp/lsplant_bridge.cpp` | — | LSPlant 的 C ABI 桥（`wekit_lsplant_init/hook/deoptimize/make_dex_file_trusted`） | 保留，改名 |
| `native/CMakeLists.txt`、`build.rs` | — | 静态链接 LSPlant/DexBuilder/libc++/compiler-rt；仅 `arm64-v8a` | 保留，路径与命名调整 |
| `native/tests/` | — | 宿主机重定位测试（Unicorn） | 保留 |

## 3. Kotlin 侧清单

| 上游文件 | 行数 | 职责 | WCX 处理 |
| --- | --- | --- | --- |
| `loader/entry/zygisk/ArtHookBridge.kt` | 140 | `IHookBridge` 实现，转调 `ArtHookBridgeRuntime` | 重写为 WCX 包名下的等价实现 |
| `loader/entry/zygisk/ArtHookBridgeRuntime.kt` | 282 | 回调注册表、优先级、原方法调用、备份等待 | 同上 |
| `loader/entry/zygisk/ZygiskEntry.kt` | 203 | `init(processName, dataDir, apkPath)`：校验包名、`nativeInitialize`、注册 `LoadedApk.createAppFactory` 与 `AppComponentFactory.instantiateClassLoader` hook、启动模块 | 同上，改调 WCX `ModuleLoader` |
| `loader/entry/zygisk/ZygiskLoaderService.kt` | 42 | `ILoaderService` 实现 | 同上 |
| `loader/entry/zygisk/ZygiskTelegramRootClient.kt` | 79 | Telegram root companion 客户端 | **排除** |
| `loader/utils/NativeLoader.kt`（`configureZygiskPayload` 等） | — | 从 Zygisk 发布的 APK 加载 native 库（`check(!nativeLibrariesLoaded)`） | WCX 需等价改造，否则 `System.loadLibrary` 找不到库 |

## 4. 与安全/诊断相关的实际行为

以下是审计中逐条确认的行为，导入后仍然生效，必须在支持文档中如实披露：

1. **隐藏 API 豁免**：`StartupAgent.ensureHiddenApiAccess()`（上游第 54 行调用）在检测不到 `ContextImpl.mActivityToken`/`mToken` 时执行 `HiddenApiBypass.setHiddenApiExemptions("L")`。上游把它放在共享启动路径，Zygisk 也会执行。
2. **宿主保护目录删除**：同一启动流程第 67 行 `deleteRecursively()` 删除 `app_qqprotect`。
3. **模块 DEX trust**：`art::trust_class_loader` 反射读取 `BaseDexClassLoader.pathList` → `DexPathList.dexElements` → `DexPathList$Element.dexFile` → `DexFile.mCookie`，再调 `lsplant::MakeDexFileTrusted`。任一 DexFile 失败即整体失败。
4. **ART 私有符号解析**：`art::elf::ArtSymbolResolver::load()` 解析 `libart.so`，供 LSPlant 解析内部符号；`ART_SYMBOLS` 与 `INITIALIZED` 都是 `OnceLock`，**初始化失败会被缓存，本进程不会重试**。
5. **代码改写**：`art/inline_hook/*` 会改写 ART 函数入口，分配可执行代码，切换页权限，保留备份不释放；不使用信号，不暂停其他线程。
6. **库路径隐藏（`so_hider`）**：`natives.rs` 第 73–76 行对 `libdexkit.so`、`libwekit_native.so`、`libmmkv.so` 三个名字调用 `hide_path`；`so_hider.rs` 第 69 行用 `e.path.contains(needle)` 做**子串匹配**，第 127/137 行用 `MAP_PRIVATE | MAP_FIXED` **无条件覆盖**原映射。`inline_hook/memory.rs` 则刻意只用 `MAP_FIXED_NOREPLACE` 且注释明确"绝不用 MAP_FIXED"——两套内存子系统策略不一致。
7. **companion 目标判定**：`companion.rs` 第 49 行 `process_name == pkg || process_name.starts_with(pkg + ":")`，并额外要求 `enabled == "1"`、`pkg.starts_with("com.tencent.mm")`、`uid / 100000 == 行内 user`。目标文件为固定路径 `/data/adb/wekit_zygisk/injection-targets.tsv`。
8. **payload 发布**：内容哈希命名、`O_NOFOLLOW`、0600/0400 权限、先解码 DEX 再原子 rename。

## 5. WCX 必须修正的三处

| 问题 | 上游现状 | WCX 要求 |
| --- | --- | --- |
| `so_hider` 匹配过宽 | 子串匹配裸库名 | 改为规范化绝对路径精确匹配，只覆盖 WCX 自身的 loader/payload 库；同名但来自其他模块/应用的库不得命中 |
| `so_hider` 覆盖语义 | `MAP_FIXED` 无条件替换 | 保留原段的起止与保护属性（含 BTI/MTE），失败时安全放弃该段并记录；重复调用不得破坏映射 |
| 依赖版本不锁 | 无 `Cargo.lock` | 提交锁定文件并记录每个 crate 版本与校验和 |

## 6. 明确排除

- `companion.rs` 的 Telegram 会话协商、包发现、`cache4.db`/WAL/SHM 读取。
- `natives.rs` 中 `nativeHasTelegramRootCompanion` / `nativeListTelegramInstances` / `nativeCopyTelegramDatabaseSnapshot`。
- `ZygiskTelegramRootClient.kt`。
- 上游 `template/sepolicy.rule`（该提交为空文件）：WCX 不新增 SELinux 规则。
- WebUI 的日志导出以外不新增命令入口；WebUI 不做任意命令执行。

## 7. 待决问题

上游共享启动路径（第 54、67 行）把隐藏 API 豁免与 `app_qqprotect` 删除放在 Zygisk 也会走到的位置。这与实现计划 Task 3 Step 4「这两步只留在 Xposed 路径」直接冲突，需要明确取舍后才能写启动代码。

## 8. 未验证事项

本文件仅基于源码阅读。以下均未执行，不能视为通过：

- 未编译 native runtime（本机无 Rust/NDK/CMake）。
- 未运行宿主机重定位测试。
- 未在真机验证 ART 初始化、DEX trust、hook 语义或 `so_hider` 效果。
- 未验证任何管理器的安装脚本。
