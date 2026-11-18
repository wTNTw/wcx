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

| 能力 | WeKite 行为 | 我们是否用到 |
|---|---|---|
| native 方法 hook | `require(!Modifier.isNative)` 拒绝 | 待确认（见 1.3） |
| 接口方法 hook | `require(!Modifier.isInterface)` 拒绝 | 待确认 |
| proxy 类方法 hook | `require(!Proxy.isProxyClass)` 拒绝 | 1 处（`WePacketDispatcher.kt` 引用 Proxy/isInterface） |
| deoptimize | `deoptimize()` 恒返回 false | 0 处 feature 直接调用（仅 `Lsp10xHookImpl`/`Xp51HookImpl` 适配层暴露） |

### 1.3 我们的实际用量（按调用点统计）

- DSL 层（`hookBefore`/`hookAfter`/`.hook`）最终都汇到 `IHookBridge.hookMethod`：
  **1156 处**（features + hooks 目录）。
- 直接 `hookMethod`/`findAndHook` 调用点：约 **254 处**。
- feature 文件数：**419 个**。
- `deoptimize`：feature 里 **0 处**直接调用；只有 Xposed API 适配层
  （`Lsp10xHookImpl.kt:52`、`Xp51HookImpl.java:100`）暴露 `deoptimize(member)`。
- `invokeOriginal` / `newInstanceOrigin`：经 `HookUtils` 的 `OriginalMethodInvoker` /
  `invokeOriginal` 使用，WeKite 同语义支持。

**缺口量化**：native/接口/proxy 是运行时属性（取决于被 hook 的 `Member` 是否为 native、
其 declaring class 是否为接口/代理），静态 grep 无法精确统计。WeKite 的 `ArtHookBridge`
对这些会在 hook 时**抛异常**（不是静默失败），所以 M2 前需要在真机上跑一遍全量 hook
安装，把抛出的成员逐个列出并评估兜底（见风险清单 R3）。

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

- `ArtHookBridge.kt` / `ArtHookBridgeRuntime.kt`：以 WeKite 版本为基准改写（DexMaker
  生成 bridge/backup、`hookId` 索引、`hookLock` 同步安装、`dispatch(hookId, self, args)`）。
- `IHookBridge.kt`：接口微调（WeKite 用 `hookBridgeName` 而非 `apiLevel`，`deoptimize` 参数
  是 `Executable`）。
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
- **R3（native/接口/proxy hook 缺口）**：WeKite 显式拒绝这三类成员，hook 时会抛异常。
  若我们的 1156 处 hook 里有这类目标，M2 会直接崩/抛。需 M2 前用全量 hook 安装枚举出
  所有被拒成员，逐个决定「改 hook 点 / 单独保留 LSPlant / 特殊兜底」。
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
- **M2（待拍板 + 授权重启）**：子进程路径（`:push`/`:appbrand`）切到 WeKite 引擎验证——
  先跑全量 hook 安装枚举被拒成员（R3），再验证子进程不崩、hook 生效。
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
