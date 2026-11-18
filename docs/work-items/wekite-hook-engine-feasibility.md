# WeKite Hook 引擎移植可行性评估（不用 LSPlant）

评估时间：2026-11-18。分支：`feat/wekite-hook-engine`。前文保留 M1/M2a 历史；M2b 实施与验收状态见文末。
参考实现：`/tmp/wekite`（`wekite-zygisk/native/src/art/`：elf.rs 508 行、layout.rs 97 行、
mod.rs 1051 行、trampoline.rs 215 行，共 1871 行）。

## 0. 一句话结论

WeKite 的 hook 引擎是「ArtMethod entry_point 替换」式，**完全不 hook 任何 native 函数**，
因此天然避开了我们 Dobby/自研 trampoline 在 Android 16 PAC ABI 下踩的 `paciasp/autiasp`
坑。它不需要 `inline_hooker`，只做 LSPlant `DoHook` 那类 Java 方法 hook（该机制在我们
默认路径下已验证可用）。方向可行，但有三个能力缺口需要在 M2 前确认：**native 方法 /
接口方法 / proxy 方法不能 hook，deoptimize 不支持**。

---

## 1. 能力对照

### 1.1 WeKite 引擎提供（native 侧 `art/hook_method`）

- Java 方法替换：`hook_method(target_art, backup_art, bridge_art)` —— 把 target 的
  `entry_point` 指向蹦床，蹦床 `ldr x0, #12; ldur x16,[x0,#ep]; br x16` 跳转到 bridge 的
  entry_point；backup 是 target 的 ArtMethod 快照（memcpy），后续经反射调用即跑原始代码。
- `unhook_method(target_art, backup_art)` —— 从 backup 恢复 target。
- before/after 回调：不是 native 原语，而是 Java 侧 DexMaker 生成的 bridge 调
  `ArtHookBridgeRuntime.dispatch(hookId, self, args)`，在 Java 里跑 before/after。
- 构造器：支持（bridge/backup 按构造器签名生成，返回 void）。
- 静态/实例方法：支持（生成的 bridge 保留 static 位）。
- `invokeOriginal` / `newInstanceOrigin`：经 backup ArtMethod + `AllocObject` 实现。
- `trust_dex_file` / `trust_class_loader`：`DexFile_setTrusted` + Runtime debug state 开关。
- `allocate_instance`：`AllocObject`。

### 1.2 WeKite 引擎**没有**的能力（显式 `require` 拒绝，会直接抛异常）

| 能力 | WeKite 行为 | 全量安装枚举结果 |
|---|---|---|
| native 方法 hook | `require(!Modifier.isNative)` 拒绝 | **1 个目标**：`Camera#setPreviewTexture(SurfaceTexture)` |
| 接口方法 hook | `require(!Modifier.isInterface)` 拒绝 | **0 个目标** |
| proxy 类方法 hook | `require(!Proxy.isProxyClass)` 拒绝 | **0 个目标** |
| deoptimize | `deoptimize()` 恒返回 false | feature 直接调用 **0 处**（仅 Xposed API 适配层暴露） |

### 1.3 我们的实际用量（运行时全量枚举）

M2a-1 已在真机完成全量 hook 安装枚举：共记录 **542 个 hook 目标**。其中：

- native：**1 个**，`android.hardware.Camera#setPreviewTexture(SurfaceTexture)`，来自
  `聊天,音视频通话/虚拟视频通话`；已由 `supportsNativeMethodHook` 能力位在 feature 侧跳过。
- 接口声明方法：**0 个**。
- 生成 proxy 类方法：**0 个**。
- `deoptimize`：feature 直接调用 **0 处**；只有 Xposed API 适配层暴露。
- `invokeOriginal` / `newInstanceOrigin`：经 `HookUtils` 使用，WeKite 有同语义实现。

因此当前已知硬缺口只有 1 个 native 目标，且已有能力位兜底；它不要求修改
`IHookBridge` 的 feature-facing hook 契约。

---

## 2. 语义映射表

| 我们的调用 | 现在（LSPlant） | WeKite 对应 | 1:1？ |
|---|---|---|---|
| `hookMethod(member, callback, priority)` | `nativeHookMethod(target, hooker, callback)` → LSPlant `Hook` 生成 bridge/backup，返回 backup `Method` | `nativeGetArtMethod(member)` ×3 + `nativeHookMethod(targetArt, backupArt, bridgeArt, hookId)`；bridge/backup 由 DexMaker 生成 | 语义等价，实现不同（bridge 生成从 C++ `BuildDex` 移到 Java DexMaker） |
| `ArtHookBridgeRuntime.HookEntry.callback(Object[])` | LSPlant 生成 bridge 的 `callback(Object[])` 签名 | DexMaker 生成 bridge 的 `bridge(params)` → `dispatch(hookId, self, args)` | 等价，boxing/unboxing 从 LSPlant C++ 移到 DexMaker |
| `invokeOriginal` / `backup` | backup `Method` 反射调用（LSPlant `BackupTo` 复制原始 ArtMethod） | backup `Method` 反射调用（`hook_method` 把 target 快照 memcpy 进 backup 的 ArtMethod） | 等价 |
| `awaitBackup`（安装中的 CountDownLatch） | 有（LSPlant Hook 可能先于返回被调用） | **无**（WeKite 在 `hookLock` 内同步注册 + 安装，注册先于安装，`dispatch` 靠 `hookId` 查表） | 需按 WeKite 的 `hookLock` 语义重写 |
| `deoptimize(member)` | LSPlant `Deoptimize` | 不支持（返回 false） | **缺口**（feature 不直接用，但 Xposed API 面会退化） |
| `nativeAllocateInstance` | JNI `AllocObject` | 同 | 等价 |
| `nativeTrustDexFile` | LSPlant `MakeDexFileTrusted(cookie)` | WeKite `DexFile_setTrusted`（接受 `DexFile` 对象） | 签名不同，语义等价 |
| native/接口/proxy 方法 | LSPlant 支持 | 拒绝 | **缺口** |

---

## 3. 移植清单

### 3.1 native（`wcx-zygisk/native/`）

搬入（复制 WeKite `art/`，改 crate 路径 `crate::art::` → `crate::wekite::`）：
- `src/wekite/mod.rs`（1051 行）：`init/hook_method/unhook_method/trust_*/allocate_instance/get_art_method`。
- `src/wekite/trampoline.rs`（215）、`layout.rs`（97）、`elf.rs`（508）。

native 导出改动（`natives.rs`）：
- 新增 `nativeGetArtMethod(Executable) -> jlong`、`nativeUnhookMethod(targetArt, backupArt) -> jint`。
- `nativeHookMethod` 签名从 `(target, hooker, callback) -> jobject` 改为
  `(targetArt, backupArt, bridgeArt, hookId) -> jint`。
- `nativeTrustDexFile(DexFile)` 改接受 `DexFile` 对象。
- 删除/停用 `cpp/lsplant_bridge.cpp`（`wcx_lsplant_*` 系列）——或按 feature toggle 保留。

`CMakeLists.txt` / `build.rs`：
- WeKite 后端是纯 Rust（无 C/C++ 依赖），不需要 LSPlant 的 C++/dex_builder。
- 编译期 feature toggle：`wcx_hook_lsplant`（默认）/ `wcx_hook_wekite`，两套
  `natives.rs` 分支 + 链接目标二选一。

### 3.2 Java（`app/`）

- 保留现有 `ArtHookBridge.kt` / `ArtHookBridgeRuntime.kt` 作为 LSPlant 默认后端。
- 新增独立的 `WeKiteHookBridge.kt` / `WeKiteHookBridgeRuntime.kt`：DexMaker 生成
  bridge/backup、`hookId` 索引、`hookLock` 同步安装、`dispatch(hookId, self, args)`。
- **不修改 `IHookBridge.kt` 对 feature 暴露的接口或语义。**
- DexMaker 依赖已存在（`com.linkedin.dexmaker:dexmaker:2.28.6`，见 `gradle/libs.versions.toml`）。

### 3.3 是否保留 LSPlant 作为可选后端

**建议保留**，编译期 feature toggle 二选一（默认 `wcx_hook_lsplant`）。理由：
- M2/M3 验证需要 A/B 对照，同一条线可回退；
- LSPlant 支持 native/接口/proxy/deoptimize，能力更全，作为兜底。

---

## 4. 风险清单

- **R1（版本边界）**：WeKite 支持微信 8.0.65~8.0.80，我们目标正是 8.0.80，处于支持区间
  **上边缘**（「更高版本未经测试」）。8.0.80 内部小版本差异（如 3220）需实测锚点。
- **R2（Android 16 / PAC / BTI）**：WeKite 的蹦床**不 patch native 代码**，没有
  `paciasp/autiasp` 问题；它做的 ArtMethod entry_point 替换与 LSPlant `DoHook` 同机制
  （我们默认路径已验证可用）。唯一要关注的是蹦床 `br x16` 是间接跳转，Android 16 BTI
  下目标（bridge entry_point）必须 BTI 兼容——M2 在子进程路径上优先验证即可。
- **R3（native hook 缺口）**：全量 542 个目标中仅 1 个 native 方法：
  `Camera#setPreviewTexture(SurfaceTexture)`。M2a-1 已增加 `supportsNativeMethodHook` 能力位，
  WeKite 后端跳过该 feature；接口/proxy 目标均为 0。
- **R4（deoptimize 缺失）**：Xposed API 面会退化（`XposedBridge.deoptimizeMethod` 恒 false）。
  feature 不直接用，但第三方脚本/兼容层可能用。
- **R5（DexMaker 生成 bridge 的可靠性）**：DexMaker 运行时生成 + `DexFile(ByteBuffer[],
  ClassLoader, Element[])` 反射构造 + `nativeTrustDexFile`，链路过长；需注意首次生成耗时
  与并发安装（WeKite 用 `hookLock` 串行化）。
- **R6（ArtMethod 布局探测）**：WeKite `layout.rs` 假设 ArtMethod 40 字节、entry_point
  在尾部（LP64 offset 32）、access_flags offset 4。Android 16 上需用 `detect()` 实际探测
  （它已做 `kAccPreCompiled` 符号读取 + 反射探测）。

---

## 5. 里程碑拆分

- **M1（本轮）**：编译期引入 WeKite 引擎（复制 `art/` 为 `src/wekite/`，加 cargo feature
  `wcx_hook_wekite` 默认关，`#[cfg(feature=...)]` 挂载，`cargo build --features
  wcx_hook_wekite` 编译通过）。**默认后端仍是 LSPlant，线上零变化，不部署、不重启。**
- **M2a（已完成并通过真机自测）**：全量 hook 枚举、native 能力位、WeKite Java
  bridge/runtime 移植；primitive descriptor 失败已定位为移植别名错误，不是 DexMaker 缺陷，
  Android 16 真机上的 int/boolean dispatch 闭环已通过（见下文）。
- **M2b（待实现）**：接通 WeKite JNI，再仅在 `:push`/`:appbrand` 切换后端，验证
  native ArtMethod 替换、`invokeOriginal`、构造器和异常路径。
- **M3（待拍板 + 授权重启）**：主进程切 WeKite + `native_early_init`（`postAppSpecialize`
  同步 `art::init`）验证，目标「启动早期安全完成 LSPlant 等价初始化 + 102 feature」。

---

## 附：WeKite trampoline 与 PAC 的对照结论（R2 依据）

WeKite `art/trampoline.rs` 的蹦床只有 3 条指令：`ldr x0, #12`（载 bridge_art_method）、
`ldur x16, [x0, #ep]`（载 bridge entry_point）、`br x16`（间接跳转）。它**从不重写任何
native 函数的序言**，因此没有「重放/跳过 `paciasp`」的问题——我们此前 trampoline 的
SIGSEGV/SIGILL 都发生在 hook `Class::SetStatus`（native 函数）的序言上。WeKite 对
`Class::SetStatus` 这类 ART 函数是**直接调用**（`ScopedSuspendAll`/`SetNotIntrinsic`/
`DexFile_setTrusted` 只 resolve + call），不做 inline hook，天然规避 PAC。

---

## M2a spike：LSPlant `BuildDex` 复用评估与 primitive descriptor 根因（2026-11-18）

### 结论

1. **语义上可行，红线不冲突。** 若用 LSPlant 的 `BuildDex` 产出 bridge/backup，现有
   `ArtHookBridgeRuntime.HookEntry.callback(Object[])` 可原样承担 dispatch；feature 侧
   `IHookBridge` 契约无需修改。
2. **工程上不能直接复用。** `BuildDex` 位于 `lsplant.cc` 的匿名 namespace，LSPlant 公共
   API 只暴露会直接执行 `DoHook` 的 `lsplant::Hook`，没有“只生成 bridge/backup”的入口。
   在不修改 `third_party/*` 的前提下，只能在 WCX 自己的 C++ 中复制/重实现生成与加载逻辑。
3. **当前不应转向 BuildDex。** M2a-2 的 `Invalid descriptor for class_idx: 'I'/'Z'` 已定位为
   移植错误：原 WeKite 的 `BInt`/`BBool` 是 `javaObjectType`（包装类），而 WCX 同名别名是
   primitive `Class`。错误代码把 `TypeId.INT/BOOLEAN` 用作 check-cast 与 `valueOf` 的类 owner，
   因此 ART 正确拒绝了 dex；这不是 DexMaker 2.28.6 的 primitive 返回类型缺陷。

### bridge / backup ABI 对照

| 项目 | LSPlant `BuildDex` | 当前 WeKite DexMaker bridge | BuildDex 混合方案影响 |
|---|---|---|---|
| bridge 签名 | 生成 static 方法；实例 receiver 作为第 1 个显式 `Object` 参数；引用参数擦除为 `Object` | 保留目标 static/instance 形态；receiver 单独传给 `dispatch`；引用参数擦除为 `Object` | native 入口 ABI 均兼容目标 shorty，但 Java dispatch 入口不同 |
| callback | hooker 实例的 `Object callback(Object[] rawArgs)` | static `dispatch(long hookId, Object self, Object[] args)` | 复用 `ArtHookBridgeRuntime.HookEntry.callback` 即可，不需要 hookId |
| 参数语义 | `rawArgs[0]` 仅实例/构造器为 receiver；其余为可变 args | receiver 与 args 分开 | `HookEntry.callback` 已完成拆分；feature 无感 |
| 返回值 | callback 返回 `Object`，生成代码按目标类型 cast/unbox | 相同 | `result`/`throwable`/primitive unbox 语义不变 |
| backup | 同 shorty 的 static 占位方法，安装时 `BackupTo` 覆盖 ArtMethod | 同调用形态的占位方法，WeKite `memcpy` target ArtMethod | 均可由反射调用原方法 |
| 安装竞态 | bridge 可能在 native `Hook` 返回 backup 前执行，靠 `awaitBackup` 等待 | Java 先 register + 写 hookId，再安装；不需要 latch | BuildDex 路线必须保留 `awaitBackup` |
| 构造器 | shorty 返回 `V`，显式 receiver，backup 反射调用 | 返回 `void`，receiver 由实例 bridge 承载 | `newInstanceOrigin` 仍可 allocate + backup |
| unhook | 当前 WCX 只移除 callback，native hook 保留到进程结束 | WeKite runtime 支持最后 callback 时恢复 ArtMethod | 为保持现状与 in-flight 安全，BuildDex 混合方案应继续保留 native hook |

### 必须改与可复用

**可原样复用（0 行 feature 改动）：**

- `IHookBridge.hookMethod/hookBefore/hookAfter/backup/invokeOriginal/replace` 的公开语义。
- `ArtHookBridgeRuntime` 的优先级、before/after 逆序、`result`、`throwable`、`extra`、异常恢复。
- `HookEntry.callback(Object[])`、`awaitBackup`、`invokeOriginal`、构造器 allocate + backup 路径。

**BuildDex 混合方案必须新增：**

- WCX 自有 C++ bridge/backup generator：复制 `BuildDex`、DexFile 内存加载和反射取 Method 的
  必要逻辑；不能调用现有匿名 namespace 函数。
- C++/Rust 边界：把生成出的 bridge/backup ArtMethod 交给
  `wekite::hook_method(target, backup, bridge)`，并把 backup `Method` 返回 Java。
- 编译期后端分流；默认 LSPlant 路径保持不变。

**预计改动量（BuildDex 路线）：** C++ 约 250–350 行，Rust/JNI 约 80–130 行，Java 分流
约 30–60 行，构建配置约 20–40 行，总计约 **380–580 行**。这明显高于修正现有 DexMaker
路线（本次根因修复约 20 行；M2b 剩余主要是 WeKite JNI 导出与接线）。

### 红线判断

- **PASS：** 两条实现路线都不要求修改 feature-facing `IHookBridge` 语义。
- **STOP BuildDex 实施：** 当前没有契约红线，但存在不必要的 C++ 复制与维护成本；既然
  DexMaker 阻塞来自移植别名错误，M2b 应继续现有 WeKite runtime/JNI 路线。
- 真机自测仅替换 module APK，未修改注入器；测试结束后已恢复当前稳定 APK 与 Dobby/LSPlant
  基线。结果见下一节。

### M2a-3 / M2b-1 Android 16 真机验收（2026-11-18）

- 验证提交：`7cf14aed`；VM clean release 产物大小 `44,326,361` 字节，SHA-256
  `187c5bb837ebdc84f83474f7a39c8322a24e0781852cbf670b4c894a1a127995`。
- 部署范围：只替换 `/data/adb/modules/zygisk-wcx/module.apk`；注入器始终保持 Dobby 基线
  MD5 `93dcbeea7a5ea0075c36c315696be62f`。
- `debug.wcx.wekite_selftest=1` 下，主进程及当时启动的 `:push`/`:appbrand0`/`:appbrand1`
  均输出 `SELFTEST PASS`：
  - `Integer.parseInt(String)` bridge：before/after 均命中 2 次；backup 占位异常为预期的
    `UnsupportedOperationException`；early-return primitive int 正确拆箱为 `42`。
  - `Boolean.parseBoolean(String)` bridge：early-return primitive boolean 正确拆箱为 `true`。
- 未出现 `Invalid descriptor for class_idx: 'I'/'Z'`、`UnsatisfiedLinkError`、WCX E 日志、
  Java crash、SIGABRT 或 SIGSEGV；主进程同时正常载入 102 个功能并命中 LauncherUI
  `ProxyInstrumentation`。
- 测试后已用 `resetprop --delete debug.wcx.wekite_selftest` 删除属性，并恢复稳定 APK
  SHA-256 `24de905db1df639df5d43889a2866578a3025e50a24e4dadb4f1a5b43eec1a32`。

**结论：M2a Java bridge/runtime 与 primitive boxing/unboxing 红线 PASS。** 下一阶段可直接进入
M2b-0 JNI 导出与 ArtMethod 闭环；无需转向 LSPlant `BuildDex` 复制方案。

### 推荐的 M2b 分步提交计划

1. **M2a-3（当前）**：包装类改用 `javaObjectType`；离线自测覆盖 int/boolean 生成、
   before/after、early return 与 primitive unbox；默认 LSPlant 不变。
2. **M2b-0（编译期）**：为 `nativeGetArtMethod/nativeHookMethod/nativeUnhookMethod/
   nativeTrustDexFile/nativeAllocateInstance` 接通 WeKite Rust JNI；两种 cargo feature 分别构建。
3. **M2b-1（已完成）**：仍用 LSPlant 默认后端，仅运行 `debug.wcx.wekite_selftest=1`，
   已证明 Android 16 上生成类可加载、int/boolean dispatch 闭环通过。
4. **M2b-2（历史提案；已由 APK 内后端方案替代，不需重启）**：仅 `:push`/`:appbrand` 选择 WeKite，主进程继续 LSPlant；验证
   进程存活、hook 计数、`invokeOriginal`、构造器和异常路径。
5. **M2b-3（稳定性门槛）**：连续冷启动与 10 分钟存活，无新增 status=11/reason=5、
   tombstone 或 WCX E 日志；不合格立即恢复 Dobby/LSPlant 基线。

## M2b APK native 闭环实施与证据（2026-11-18）

**状态：实现与 19:08 的完整矩阵通过；本次窄化重试在 23:14 的稳定健康检查失败后已停止，
不宣称全部验收完成。** 19:00 的主进程 LSPlant/宿主 `LauncherUI.onResume` 事件为单次、
后续无法复现、因果未建立的未闭环残余观察，不称已修复或 flaky。没有添加吞异常补丁，
也没有修改主进程启动时序来掩盖问题。最新失败与停止状态见本节末尾。

### 实施提交

所有提交均已推送 `origin/feat/wekite-hook-engine`，未推送 master：

| 提交 | 内容 |
|---|---|
| `9bcee322e10302d806643e9aa6165f0d4fcb1238` | cherry-pick 已验收 `2d84b5cd` 冷启动首 Activity 门 |
| `d19e23b17930d30f032a7aad9a9f6b3be94e24f5` | 引擎移动至共享 `libs/common/wekite-art`，无第二份引擎副本 |
| `39f325b4d9b3fa9e7a8952499051c908227e5bb3` | APK JNI 导出、可选 Cargo feature、显式 `--wekite-hook` |
| `a16343b9ec7e5be826ae1b0f64c07a6cc538c491` | 幂等 early payload load 与多进程提取发布复用 |
| `f04e20b8a7327bd3aacc4ffacca119a632140425` | 真实 fixture、自清理、初始化入口；预期故障仅按异常对象身份及私有 fixture 类记 INFO，其余 callback 异常仍记 E |
| `9e07082781269750bbaf14f54772c92a85f2c06a` | 属性 + 子进程 allowlist + init/self-test gate；失败回退 LSPlant |
| `4d7c9edd589157555eb4a4ac2e1af39e4d139364` | 启动前 baseline、逐进程核验、完整时长 PID 观察探针 |
| `a1d737c079935061d1f1938178d586b1843c7941` | 修复真机探针日志来源，随后重跑完整 VM/设备矩阵 |

验证的代码提交及当时远端 SHA 均为 `a1d737c079935061d1f1938178d586b1843c7941`。
本节另作独立文档提交，不把文档提交误写成 APK provenance。

实现不改 feature-facing `IHookBridge`：主进程始终为 `ArtHookBridge`/LSPlant，只有
`debug.wcx.wekite_m2b=1` 且进程为 `:push`/`:appbrand*` 时才尝试 APK 后端。
基线 `wekit-native` feature 默认空；resident 默认仍为 LSPlant。

### VM 矩阵与产物

全部编译仅在 Debian VM `/root/wcx` 执行，通过指定 `wcx-remote.sh` 同步
`/mnt/d/Github/wcx`。最终 provenance：

```text
a1d737c079935061d1f1938178d586b1843c7941 dirty=0 synced=2026-11-18T18:55:51+08:00
```

- `cargo test -p wekite-art`：1 test PASS（arm64 trampoline 寄存器编码），doc tests PASS。
- `wekit-native` arm64 `cargo check` baseline / `--features wekite_hook`：均 PASS。
- `wcx-zygisk` arm64 `cargo check` baseline / `--features wcx_hook_wekite`：均 PASS。
- `wcx-remote.sh release --abi arm64-v8a`：两 flavor release + module APK PASS，20s。
- 同命令加 `--wekite-hook`：PASS，1m4s。
- baseline/test native-only release 及导出检查亦通过；最终 APK 内的
  `libwekit_native.so`：baseline 不含 `WeKiteHookBridge_nativeInitialize`，test 包含。
- 首次从 workspace 根直接 check APK native 时，依赖构建找不到无 API 后缀的
  `aarch64-linux-android-clang`；实际 NDK 配置位于该 crate 的 `.cargo/config.toml`。
  改为从 `app/src/main/rust/wekit-native` 执行，未改源码/工具链，完整矩阵重跑通过。

产物根目录：`C:/Users/kwu89/AppData/Roaming/PiDeck/chat-workspace/wcx-m2b-dist/`。

| 产物（相对根目录） | 字节数 | SHA-256 |
|---|---:|---|
| `baseline-a1d737c0/outputs/zygisk/wcx-v347-arm64-v8a.apk` | 44,309,977 | `d511f0d1ba7cea0f54c4848809a33801c2d3422647e9f0f621b66193f5d9e3e7` |
| `test-a1d737c0/outputs/zygisk/wcx-v347-arm64-v8a.apk` | 44,333,705 | `11e67039d58edfebda0380b2922a0bc432c6e9e27a3b399f800d0b583358f4cf` |

VM 无固定 release keystore，module APK 为本机测试用 unsigned 产物；只作 Zygisk
module payload，不用于覆盖安装 Android 应用。

### 真机最终完整矩阵

设备：PJZ110 / Android 16 / 微信 8.0.80(3220)。没有重启手机、zygote 或 framework，
没有替换稳定注入器。部署仅临时替换 module APK；root shell 在替换前安装 EXIT/信号
rollback trap，每次恢复先删属性并原子发布稳定 APK，再收集诊断。

最终完整运行：`wcx-m2b-full-20261118-190842`。

- boot **6/6**、direct **4/4**：每轮主进程 `enabled_max=102`，LauncherUI
  `ProxyInstrumentation` 命中、LSPlant initialized，WCX E=0，无新增 Java crash、
  status=6/11 或 recovery；boot 前后主进程 PID 相同。
- `:push`、`:appbrand0`、`:appbrand1` 均有 `native init PASS`、
  `WeKiteNativeSelfTest SELFTEST PASS`、`backend=WeKite`；有真实
  `AppComponentFactory.instantiateClassLoader` hook 与 `WCX module started` 证据。
  push 的 NotificationsEvolved 四个方法安装成功，两个 appbrand 进程各加载 2 个延后功能。
- fixture 所有检查通过：静态 null receiver、实例 receiver 身份、primitive/reference、
  修改参数、early return、真实 `invokeOriginalMethod`、异常原样传播/after 恢复、
  before/after 故障状态恢复、优先级/逆序、重复 callback 只装一次 native hook、
  `invokeOriginalConstructor`、`newInstanceOrigin`、最终 unhook 后直接调用及零遗留 hook。
- 最后一轮观察 **604 秒**、118 个采样：主进程 **18683**、push **21368**、
  appbrand1 **22258** 始终不变；appbrand0 按宿主逻辑自然退出，不计为受观察 PID。
- 观察窗口 `crash.txt` 为空，WCX E=0，无 SELFTEST FAIL/fallback，无新增
  reason=4/5、status=6/11 或 recovery。没有 native crash/tombstone 通知。

### 失败与尚未关闭的风险

1. 第一轮 smoke 的所有真实 native 自测通过，但探针只在 logcat 找 WCX，误判失败。
   实际捕获中，logcat 的 push SELFTEST 记录为 **0**，同窗口 WeLogger 文件为 **1**；
   该 ROM 会抑制应用 logcat，早期文件队列却保留完整记录。先恢复稳定 APK，再以
   `a1d737c0` 改用文件记录做后端判定，logcat 仍保留系统/崩溃诊断；完整矩阵重新运行。
2. `19:00:35` 首次 full boot 第 1 轮主进程 PID **13185** 发生：

   ```text
   Unable to resume activity com.tencent.mm.ui.LauncherUI
   NullPointerException: void androidx.appcompat.app.b.L() on null
   LauncherUI.onResume -> ArtHookBridgeRuntime -> LSPHooker_.onResume
   ```

   当轮首屏门、LSPlant、102 功能和 ProxyInstrumentation 均已命中，随后出现
   reason=4 Java crash。立即恢复稳定 APK 后再诊断；stable direct 健康检查、stable
   boot 4 轮及 appbrand→boot 对照 1 轮均通过。相同 test APK 的后续完整矩阵通过，
   **但这不能证明原崩溃已修复或与 M2b 无关**。保留完整堆栈、exit-info 与窗口；
   不把此问题标为 flaky，不以吞 NPE 或改变主进程 hook 契约消除失败。

因此 native 功能闭环已有证据；第 2 项列为**未闭环残余观察**，不能将后续成功
当成原事件已修复或与 M2b 无关的证据。同类 Java crash 在探针中仍是硬失败。
未经定位，不安全扩大主进程时序或 UI 行为修改。子进程仍由稳定 resident 注入器初始化过 LSPlant，
本轮不证明“完全移除 LSPlant”的运行模式，也不覆盖 native/interface/proxy hook 或 deoptimize。

### 回滚与最终健康检查

初始、每次 trap 恢复及最终健康检查都精确匹配：

```text
stable module.apk SHA-256=24de905db1df639df5d43889a2866578a3025e50a24e4dadb4f1a5b43eec1a32
stable injector MD5=93dcbeea7a5ea0075c36c315696be62f
```

`debug.wcx.wekite_m2b` 已 `resetprop --delete`；旧 `debug.wcx.wekite_selftest` 也确认
不存在。恢复后再次只重启微信，direct 健康检查 PASS：主进程 **20139**、push **1765**，
102 功能、LauncherUI proxy、E=0，无新增 Java/native crash、recovery 或 self-test/backend
日志。设备最终运行稳定 APK，不是实验 APK。

证据根目录：`C:/Users/kwu89/AppData/Roaming/PiDeck/chat-workspace/wcx-m2b-evidence/`：

- `matrix-native-a1d737c0.log`、`matrix-baseline-a1d737c0.log`、`matrix-test-a1d737c0.log`、
  `provenance-a1d737c0.txt`。
- `device-smoke-4d7c9edd.txt`、`wcx-m2b-smoke-20261118-185006/`。
- `device-full-a1d737c0.txt`、`wcx-m2b-full-20261118-190005/`：保留失败，不替换为重跑输出。
- `device-full-a1d737c0-repro.txt`、`wcx-m2b-full-20261118-190842/`：最终 6/4/604 秒完整证据。
- `stable-boot-repro.txt`、`stable-boot-controls.txt`、`stable-appbrand-boot-repro.txt`、
  `stable-final-health.txt`、`wcx-m2b-stable-final-window.txt`。

### 最后一次窄化重试与停止状态（23:14）

按父会话转达的“只重试一次，失败即停止”要求，本次只完成 property=0 对照、真实
8.0.80 字节码和 ART method sharing 取证，没有再部署 property=1 或盲修主进程。
重试前本地/远端均为 `7b42093ce490602ac483827ff2688f96ccb32e48`，工作区干净，
设备 APK/注入器是上述稳定哈希，两个测试属性不存在。

`7b42093c` 只修改探针的精确计数约束，没有改 APK 后端或推翻原构建/设备矩阵。
对已保存的最终完整窗口重新核验：3 次 SELFTEST PASS，唯一预期 before 故障恢复
**6** 次、after **6** 次，总计 **12**，unexpected WCX E **0**。
新探针要求 before/after 各等于 PASS 数的两倍，任何 WCX E 仍直接失败，不用宽泛
排除模式。该计数已在原始窗口核验，未把它描述成一次新的 property=1 真机运行。

**对照结果：**

- 稳定 APK 额外 boot **10/10** PASS（`stable-boot-controls-10.txt`）。
- 同一已验证 test APK（SHA-256 `11e67039d58edfebda0380b2922a0bc432c6e9e27a3b399f800d0b583358f4cf`）
  配 `debug.wcx.wekite_m2b=0`，boot **10/10** PASS；每轮 102 功能、LSPlant 与
  LauncherUI proxy 均命中，无新增 Java/status6/status11/recovery，WCX E=0。
- 对照全窗口 **2035** 行，无 WeKite/self-test 或 WCX E。期间主进程 PID 依次为
  `21491,10486,12984,15497,18178,20267,22559,24782,29156,29295`；每轮 boot
  前后 PID 相同，完整时间线保留，未把子进程“已启用 1 个”误当成主进程重入。
- 对照结束时 trap 已恢复稳定 APK、删除属性，注入器 MD5 不变。

**实际宿主字节码：** 从设备当前安装的 `base.apk` 取证，仅在 VM 用 SDK
`apkanalyzer dex code` 反汇编，APK SHA-256：
`024c53bfc192c463d56b66a676d9133f6d5b1cdcd435601d02d4f6632a333152`。

- 空字段定位为 `com.tencent.mm.ui.HomeUI.c:Landroidx/appcompat/app/b;`。
- `LauncherUI.onCreate` 将 `getSupportActionBar()` 返回值写入 `HomeUI.c`；该路径允许
  返回 null，并捕获 getter 的 Exception。
- `LauncherUI.onResume` 调 `HomeUI.o()`，其开头对 `c==null` 有日志与 return；返回后
  LauncherUI 紧接着无条件调用 `HomeUI.c.L()`，与原堆栈的空 ActionBar 解引用一致。
- `MMFragmentActivity.getSupportActionBar` 调 AppCompat delegate 的 `s()` 后读
  `g0.i`，再交 `iu5.q0.N` 包装；`N(null)` 仍返回 null。delegate 是否建立 ActionBar
  受自身状态/主题分支影响。本轮没有复现 getter 为 null 的现场，**尚不知其产生原因**。

**ART method sharing：** 原 smoke 中两个 appbrand PID 的 native hook 日志含相同 target
虚址，例如 `0xa9eec8d0`；相同虚址本身不说明写入跨进程传播。本次在主进程 **21077**
与 push **30978** 的 `/proc/maps` 中，该地址所在区间均为：

```text
a9e58000-a9ff0000 rw-p [anon:dalvik-/system/framework/boot-core-icu4j.art]
VmFlags: rd wr mr mw me ac
```

两份 smaps 均有 Shared_Dirty=1604kB，但 Private_Dirty 分别为 12kB/8kB；这是
fork 后物理页共享与 MAP_PRIVATE/COW，并非 `rw-s` 可跨进程写共享。引擎在当前进程
mprotect 后复制/写 target ArtMethod；新建 trampoline memfd 的双映射 MAP_SHARED 是
本进程新建对象的 RW/RX alias，不能仅据此推断兄弟进程共享了该新对象。
这支持“不应把同虚址当污染证据”，**并不证明 19:00 事件已排除全部间接因果**；
未采得当时崩溃页的跨进程 PFN，也未据此作无因果结论。

**重试收尾出现另一项硬失败，已停止：**

```text
adb.exe shell su -c 'sh /data/local/tmp/wcx-m2b-stable-health.sh'
exit=1
main=31778 push=10523 gate=1 trigger=0 lsplant=0 enabled_max=1 proxy=0
E=0 new_java_crash=0 new_status6=0 new_status11=0
stage=startup_gate_not_triggered
ARTIFACT=/data/local/tmp/wcx-injection-2026-11-18_23-14-47-direct.log
```

该检查运行在已恢复的稳定 APK 上，不是 19:00 NPE 的重现；没有将“未触发首 Activity
门”当作通过。失败后没有再拉起/杀进程、重新部署、自动修复或重试，只读核实哈希、
属性并保存失败窗口。停止时稳定 APK SHA-256 仍为 `24de905d…ec1a32`，注入器 MD5
仍为 `93dcbeea7a5ea0075c36c315696be62f`，两项测试属性均不存在。

**后续复活条件：** 获得新的明确授权后，若同一 NPE/门未触发再次出现，保留该次
PID 对齐的 onCreate/onPostCreate/onStart/onResume、ActionBar 字段状态、宿主主题与
完整 exit-info，再作单变量 baseline/test/属性 A/B；不以本轮未复现宣称修复。

补充证据位于同一证据根目录：`device-property0-control.txt`、
`wcx-m2b-control-20261118-230501/control-full-wcx.txt`、`control-capture.txt`、
`launcher-8080.smali`、`home-o-8080.smali`、`getactionbar-8080.smali`、
`appcompat-bar-8080.smali`、`delegate-s-8080.smali`、`q0-N-8080.smali`、
`wcx-m2b-art-sharing/`、`callback-count-verification.txt`、
`stable-final-health-retry.txt`、`retry-failed-stable-direct-window.txt`、
`retry-stopped-device-state.txt`。
