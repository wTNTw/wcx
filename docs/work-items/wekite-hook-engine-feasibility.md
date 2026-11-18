# WeKite Hook 引擎移植可行性评估（不用 LSPlant）

评估时间：2026-11-18。分支：`feat/wekite-hook-engine`（本轮只做 M1 + 本文档）。
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
4. **M2b-2（需授权重启）**：仅 `:push`/`:appbrand` 选择 WeKite，主进程继续 LSPlant；验证
   进程存活、hook 计数、`invokeOriginal`、构造器和异常路径。
5. **M2b-3（稳定性门槛）**：连续冷启动与 10 分钟存活，无新增 status=11/reason=5、
   tombstone 或 WCX E 日志；不合格立即恢复 Dobby/LSPlant 基线。
