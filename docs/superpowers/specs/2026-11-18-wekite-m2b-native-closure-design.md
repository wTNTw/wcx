# WeKite M2b Native ArtMethod 闭环设计

日期：2026-11-18  
分支：`feat/wekite-hook-engine`

## 1. 目标

在不改变 feature-facing `IHookBridge` 契约、不重启手机或 zygote、且不改变当前稳定 Zygisk 注入器的前提下，完成 WeKite Java bridge 到 native ArtMethod 替换的真实闭环，并仅在微信 `:push` 与 `:appbrand*` 子进程进行受控验证。

本阶段必须验证：

- `nativeGetArtMethod`
- `nativeHookMethod`
- `nativeUnhookMethod`
- `nativeTrustDexFile`
- `nativeAllocateInstance`
- 静态方法与实例方法
- 参数修改与 primitive/reference 返回值
- 真实 `invokeOriginal`
- 构造器与 `newInstanceOrigin`
- 原方法异常传播
- callback 优先级、before/after 顺序及异常恢复
- 最后一个 callback 移除后的 native unhook

主进程继续使用 LSPlant。本阶段不宣称目标子进程完全没有初始化 LSPlant；稳定注入器仍按现有逻辑完成 LSPlant 初始化，但 WCX 的方法 hook 将由 WeKite 执行。

## 2. 安全边界

- 先整合已验收的冷启动首 Activity 门提交 `2d84b5cd`。
- 不修改 `IHookBridge` 对 feature 暴露的语义。
- 不替换 `/data/adb/modules/zygisk-wcx/zygisk/arm64-v8a.so`。
- 不执行 `adb reboot`，也不软重启 zygote/framework。
- 真机只替换 `/data/adb/modules/zygisk-wcx/module.apk`。
- 稳定注入器 MD5 必须始终为 `93dcbeea7a5ea0075c36c315696be62f`。
- 测试结束后删除所有测试属性，并恢复稳定 APK SHA-256 `24de905db1df639df5d43889a2866578a3025e50a24e4dadb4f1a5b43eec1a32`。
- 任一部署步骤失败、设备状态不明确或出现新增崩溃时，优先恢复稳定 APK，再继续诊断。

## 3. 方案选择

### 3.1 采用：共享 Rust 引擎 + APK 内测试后端

把当前 `wcx-zygisk/native/src/wekite/` 的 ART 引擎提取成独立共享 Rust crate。两个消费者分别是：

1. `wcx-zygisk/native`：保留未来 M3 的 resident Zygisk 集成路径。
2. `app/src/main/rust/wekit-native`：本阶段由 module APK 动态加载，提供无需重启 zygote 的 JNI 测试后端。

共享 crate 负责 ArtMethod 布局探测、符号解析、trampoline pool、hook/unhook、DexFile trust 与对象分配。消费者只负责日志适配、JNI 导出和生命周期接线。

### 3.2 不采用的方案

- **复制一份引擎到 `wekit-native`**：实现较快，但两份高风险 ART 代码会漂移，后续修复难以证明一致。
- **替换 resident 注入器并重启 zygote**：最接近最终 M3 架构，但超出本阶段“不重启 Android Runtime”的安全边界。

## 4. 组件设计

### 4.1 共享 WeKite ART crate

新增一个仅包含 ART hook 原语的 Rust library crate，承接现有：

- `elf.rs`
- `layout.rs`
- `trampoline.rs`
- `mod.rs` 中的初始化与 hook API

公共 API 保持窄接口：

- `init(JNIEnv*) -> bool`
- `is_initialized() -> bool`
- `get_art_method(JNIEnv*, Executable) -> usize`
- `hook_method(JNIEnv*, target, backup, bridge) -> i32`
- `unhook_method(JNIEnv*, target, backup) -> i32`
- `trust_dex_file(JNIEnv*, DexFile) -> bool`
- `trust_class_loader(JNIEnv*, ClassLoader) -> bool`
- `allocate_instance(JNIEnv*, Class) -> jobject`

日志通过共享 crate 自己的轻量 Android log 适配或注入式宏完成，不反向依赖任一消费者。

### 4.2 APK native JNI

`wekit-native` 增加与 `WeKiteHookBridge` 声明完全匹配的 JNI 导出，并新增一次性初始化入口。`hookId` 继续由 Java runtime 管理，native 只用于诊断日志，不作为 native 生命周期索引；native hook 表仍以 target ArtMethod 为键。

JNI 调用必须：

- 对空指针和未初始化状态返回明确错误码。
- 保留 Java pending exception，不把错误静默转成成功。
- hook/unhook 仅在共享引擎完成初始化后执行。
- 避免 JNI local reference 泄漏。

### 4.3 早期 payload native 加载

`NativeLoader` 增加幂等的早期加载入口，使目标子进程可以在安装 `LoadedApk.createAppFactory` hook 前加载 APK 中的 native 库。后续 `NativeLoader.init()` 必须复用已加载状态，不能重复初始化或丢失 MMKV 的 library map。

早期加载只在测试开关开启且进程命中 allowlist 时发生。默认主进程与普通子进程时序不变。

### 4.4 后端选择

新增测试属性：

```text
debug.wcx.wekite_m2b=1
```

启用条件必须同时满足：

- 属性值为 `1`；
- 进程名为 `com.tencent.mm:push` 或以 `com.tencent.mm:appbrand` 开头；
- APK native 引擎初始化成功；
- M2b native self-test 全部通过。

满足时实例化 `WeKiteHookBridge` 并用于早期启动 hook 和后续 feature hook。否则记录原因并回退 `ArtHookBridge`。主进程无条件继续 LSPlant。

`ZygiskEntry` 内部持有类型从具体 `ArtHookBridge` 泛化为 `IHookBridge`，但主进程延迟初始化和首 Activity 门仍只走现有 LSPlant 路径。

### 4.5 冷启动修复整合

在任何 M2b 代码前整合 `2d84b5cd`：

- 主进程安装首 Activity 启动门；
- 首个 Activity transaction 前同步完成 LSPlant 与模块启动；
- PackageManager 代理不得在 `HostInfo` 初始化前读取其状态；
- 保留自动注入探针。

M2b 不重新设计主进程启动时序，只解决分支落后问题。

## 5. 数据流

目标子进程启动时：

1. 稳定 resident 注入器完成现有 payload ClassLoader 和 LSPlant 初始化。
2. `ZygiskEntry.init(processName, dataDir, apkPath)` 调用现有 `nativeInitialize()`，保留 LSPlant 回退能力。
3. Java 检查 M2b 属性和进程 allowlist。
4. 命中时由 `NativeLoader` 从当前 module APK 解包并加载 `libwekit_native.so`。
5. 调用 WeKite native 初始化，探测当前 Android 16 ART 布局并建立 trampoline pool。
6. 运行模块私有 fixture 的 native self-test。
7. self-test 成功后，使用 `WeKiteHookBridge` 安装 `createAppFactory` / `instantiateClassLoader` 启动 hook。
8. `ModuleLoader` 将同一 bridge 发布给所有 feature。
9. feature 调用保持原有 `IHookBridge` 语义，Java runtime 负责 callback dispatch，native 负责 target/backup/bridge ArtMethod 替换。

## 6. Native self-test

新增模块私有 fixture，不 hook 系统全局常用方法，避免测试影响宿主线程。覆盖：

1. **静态方法**：参数修改、primitive 返回、真实 original。
2. **实例方法**：验证 receiver 传递、引用参数和引用返回。
3. **early return**：跳过 original 并正确拆箱。
4. **异常路径**：原方法抛出确定异常，after callback 可观察并替换或继续传播。
5. **callback 顺序**：高优先级 before 先执行，after 逆序执行。
6. **callback 自身异常**：恢复进入 callback 前状态并继续执行剩余链路。
7. **构造器**：hook 构造器、`invokeOriginalConstructor`、`newInstanceOrigin`。
8. **unhook**：移除最后 callback 后恢复 target；再次直接调用不得进入 dispatch。
9. **重复 hook**：同一 member 多 callback 只安装一次 native hook。

self-test 任一断言失败都禁止该进程切换到 WeKite，并记录单一明确的 `SELFTEST FAIL` 原因。

## 7. 错误处理与回退

- WeKite native 初始化失败：回退 LSPlant。
- Dex trust 失败：回退 LSPlant，不继续生成真实 feature bridge。
- native hook 返回非零：注销尚未发布的 Java hook entry；调用者收到安装失败。
- final unhook 失败：恢复 callback registration，保持 hook 可重试。
- self-test 安装后清理失败：将该进程视为失败并回退；不能带着未知 fixture hook 进入 feature 加载。
- 真机出现 Java crash、SIGABRT、SIGSEGV、status=11/reason=5 或异常重启：立即恢复稳定 APK，保留日志，再诊断修复。

## 8. 自动验证矩阵

### 8.1 VM

- 共享 Rust crate host 可执行测试或最小静态测试。
- `wekit-native` arm64 release 构建。
- `wcx-zygisk` 默认 LSPlant 构建。
- `wcx-zygisk` 启用 `wcx_hook_wekite` 构建，证明共享引擎仍可用于未来 resident 集成。
- Android debug/release APK 构建。
- 检查产物 provenance、工作区状态和哈希。

### 8.2 真机

1. 备份并校验稳定 APK 与注入器 MD5。
2. 部署测试 APK，不修改注入器。
3. 设置 `debug.wcx.wekite_m2b=1`。
4. 重启微信相关进程并拉起 `:push`、`:appbrand0`、`:appbrand1`。
5. 验证每个目标子进程：
   - native engine init PASS；
   - self-test PASS；
   - 日志明确显示 backend=WeKite；
   - feature 加载和真实 hook 生效。
6. 主进程继续验证 102 功能和 LauncherUI `ProxyInstrumentation`。
7. 执行连续冷启动与 BootReceiver/direct 探针。
8. 至少 10 分钟存活观察，检查进程 PID 是否异常变化。
9. 扫描 WCX E、Java crash、SIGABRT、SIGSEGV、tombstone、status=11/reason=5、recovery。
10. 删除 `debug.wcx.wekite_m2b`，恢复稳定 APK，再做一次健康检查。

## 9. 完成标准

只有同时满足以下条件才可宣布 M2b 完成：

- 所有 VM 构建与测试通过。
- 所有 M2b fixture 断言在目标子进程通过。
- `:push` 与至少一个 `:appbrand*` 进程明确使用 WeKite 完成真实 feature hook。
- `invokeOriginal`、实例 receiver、构造器、异常传播、unhook 均有真机证据。
- 主进程仍为 LSPlant，102 功能和首屏代理正常。
- 连续启动和 10 分钟观察无新增崩溃、异常退出或 WCX E。
- 测试属性已删除，稳定 APK 已恢复，注入器 MD5 未改变。
- 工作区干净，每个阶段已独立 commit 并 push 到 `feat/wekite-hook-engine`。

若测试发现问题，修复后必须从受影响层级开始，并在最终完成前重新跑完整 VM 与真机矩阵，不能只验证单个失败用例。

## 10. 非目标

- 本阶段不把主进程切换到 WeKite。
- 不证明完全移除 LSPlant 后目标子进程仍可工作。
- 不补齐 native 方法 hook、接口方法 hook、proxy 方法 hook或 deoptimize。
- 不修改 Dobby 注入基线。
- 不进行 zygote/framework 重启。
