# 微信 8.0.80：冷启动后 wcx 不加载 / 首页白屏 的完整排查与修复

排查时间：2026-11-17（承接 `wechat-8.0.80-wake-deferred-startup-crash.md` 与
`wechat-8.0.80-startup-abort.md`）。
设备：OnePlus PJZ110（ColorOS / Android 16，KernelSU），微信 8.0.80(3220)，
Zygisk 注入（`zygisk-wcx`）。

## 现象

1. **冷启动偶发「模块不加载」**：微信界面正常但 WCX 全无（设置入口/侧边栏/功能都不在），
   重启微信后恢复；
2. **手机重启后第一次打开微信必现首页白屏**：白屏、模块 UI 全无；微信自己重启一次后正常。

## 结论（三条独立的根因，按发现顺序）

### 根因 1：`art::init`(LSPlant) 撞 8.0.80 的 libowl/libaff_biz 窗口 → SIGABRT

`exit-info` 实测：

```
timestamp=2026-11-17 10:14:31.802 pid=3865 process=com.tencent.mm
  reason=2 (SIGNALED) status=6        ← status 6 = SIGABRT
```

主进程被 SIGABRT 带走后，微信进恢复模式（`:recovery` 起来），新进程里 `libaff_biz`
几十秒才加载 → 模块放弃初始化：

```
10:15:32  LSPlant init failed (attempt 1/3)
10:18:01  宿主环境异常（libaff_biz 未加载）→ 本进程不启动模块
10:19:41  enabling all hook items took 572µs     ← feature 一个都没装
```

### 根因 2：**首屏窗口只有约 1 秒余量**，任何「多等一会儿」都会白屏

为了让 `art::init` 离开冲突窗口，试过三种「等更晚」的判据，全部被微信自己的早期信号骗过：

| 判据 | 实测行为 | 结果 |
|---|---|---|
| `ActivityManager` importance ≤ FOREGROUND | 进程刚绑 top-activity 就是 FOREGROUND（`libaff_biz` 后 **2ms** 就"可见"） | 等于没挡 |
| `WindowManagerGlobal.mViews` 有已 attach 且有尺寸的窗口 | 微信先显示的 **splash 窗口**已经满足 | 等于没挡 |
| `ActivityThread.mActivities` 里出现 `LauncherUI` | 真正等到首屏 Activity 创建 | **副作用致命** |
| `libaff_biz` + `libowl` 双库稳定 **2s** | 初始化被推后约 1s | **白屏** |
| `libaff_biz` 单库稳定 **1s**（当前） | 初始化紧跟 `libaff_biz` | ✅ 正常 |

「等 LauncherUI」那次部署后用户实测：**首页白屏 + 切走再回首页直接崩溃退出**，崩溃签名：

```
IllegalStateException: ViewTreeLifecycleOwner not found from
  com.tencent.mm.blink.FirstScreenFrameLayout
```

这正是 `wechat-8.0.80-startup-abort.md`「残余观察」里记过的那条：模块的
`ActivityProxy`（替换 Instrumentation）与 Compose 相关注入**必须早于首屏 onCreate**。
所以：**`art::init` 不能晚于首屏窗口，晚 1 秒就白屏，晚到 LauncherUI 之后直接崩。**

### 根因 3：重启后首次启动，模块自己的开关存储（MMKV）读成空 → feature 全被当成「关闭」

这是「重启后必现白屏」的真因。加统计日志后一眼可见：

```
15:43:27  enabling all hook items took 5.59ms（169 个）      ← 正常应为 ~185ms
         本批 169 个：已启用 1 个                            ← 只有默认开启的那 1 个
```

而用户在同一时刻打开 WCX 设置页，**开关显示的却都是开着的** —— 即「早期读是 false、稍后读是 true」。
`wekit_prefs`（MMKV，`MMKV.MULTI_PROCESS_MODE`）文件在、刚被写过（32KB），
说明是**数据尚未载入内存**，不是文件丢失。

`SwitchFeature.startup()` 读不到开关就默认 `false` → 169 个 feature 集体空转 → 首屏没有任何注入 → 白屏。

## 已实现的修复（按提交顺序）

| 提交 | 内容 |
|---|---|
| `8879e8da` | `isLibaffBizLoaded` 改用 `Os.open/read` 扫 maps（原来 `File.useLines` 会在解冻的 USAP 池进程里踩空，SIGSEGV pc=0） |
| `0101e69b` | `StartupCrashGuard` 只把 CRASH/CRASH_NATIVE/INITIALIZATION_FAILURE 计为启动失败，系统回收/ANR/强停不算 |
| `be2dc841`…`9b5478cf` | 弹窗配色跟随明暗（与本问题无关，同期修） |
| `cc7a8d00` | `FeaturesLoader`：开关存储未就绪时**延迟重试**（400ms × 8），不把「读不到」当成「用户关了」；每批打印 `本批 N 个：已启用 M 个` |
| `e76f2eee` | ① 回退到只等 `libaff_biz` 稳定 1s；② `SwitchFeature` 读到关闭时 **2s / 5s 各复核一次**，读到开着就补 `enable()` |

## 被否决的方案（免得再走一遍）

1. **「迟到重试」（失败后 30s 再跑一轮）**：30s 之后微信早崩了/被杀了，兜底只会在异常或纯后台进程里
   白跑一次 `art::init`，已删除。
2. **等 importance / 等窗口 / 等 LauncherUI**：见根因 2，前者被骗、后者白屏甚至崩溃。
3. **照搬上游 WeKit 的 zygisk 注入**：上游在 `postAppSpecialize` 里**同步** `art::init`
   （`wekit-zygisk/native/src/lifecycle.rs:224`），所以没有「不加载」的中间态；但上游
   `docs/getting-started.md` 的支持矩阵**最高只到 8.0.78**，那种做法在 8.0.80 上必触发
   libowl/libaff_biz 的 SIGABRT（本仓库的 A/B 实测：不初始化 LSPlant 连续 1 小时不崩，
   早期初始化必崩）。
4. **参考 FunBox 的注入**（反编译 `/data/adb/modules/funbox/zygisk/arm64-v8a.so`）：
   它的原生回调只做「拷 APK + 建 `BaseDexClassLoader` + 调 Java 入口」，**完全不在 zygote
   回调里碰 ART**（`postAppSpecialize` 只有 strcmp + JNI 调用），自研 `shooker` 引擎全是
   JNI 可调用函数，由 Java 在真正要装 hook 时才初始化 —— 所以它天然没有这个窗口问题。
   但 LSPlant 的 `art::init` 是装 hook 的前提，无法同样推迟，因此只能「学它的时序、
   保留 LSPlant 引擎」。

## 当前配置（最终形态）

```kotlin
// ZygiskEntry
private const val LIBAFF_BIZ_GRACE_MS = 1_000L   // 只等 libaff_biz 稳定 1s，不额外等 libowl
// SwitchFeature
private val RECHECK_DELAYS_MS = longArrayOf(2_000L, 5_000L)  // 读成关闭 → 2s/5s 复核补装
// FeaturesLoader
private const val PREFS_READY_MAX_ATTEMPTS = 8
private const val PREFS_READY_RETRY_DELAY_MS = 400L
```

## 验收（自动化）

手机上留了冷启动验收脚本 `/data/local/tmp/wcx-coldstart-test.sh`：

```sh
sh /data/local/tmp/wcx-coldstart-test.sh 45
```

它自己杀微信 → 拉起 → 等 45s → 按本次启动窗口内的日志判定：

- 判据：`本批 169 个：已启用 ≥ 10`（取窗口内**最大值**，子进程会打 `已启用 0`，不能用最后一行）
  且 `ProxyInstrumentation: callActivityOnCreate` 命中 且 `E == 0`；
- 输出 `RESULT=PASS/FAIL`，FAIL 时打印现场关键行。

实测结果：

| 场景 | 结果 |
|---|---|
| 普通冷启动 ×3 | `enabled_max=102`、`proxy` 命中、`E=0` → 全部 PASS |
| **手机重启后首次打开微信** | `18:28:34 本批 169 个：已启用 102 个`、`18:28:34 ProxyInstrumentation: callActivityOnCreate LauncherUI` → PASS，截图确认首屏正常（自定义顶栏 + 底部悬浮胶囊栏） |

`开关延迟复核命中` 在这两次里计数为 0，说明当前主要靠「只等 libaff_biz + 开关重试」就够了，
复核是额外保险。

## 借鉴 WX-Augment / WeKit-bootstrap（2026-11-17 晚）

反编译了 `WX-Augment-v310.apk` 与它配套的 `bootstrap-release.apk`
（`libwekit_zygisk.so` + `libwekit_loader.so`）：

- **三层架构**：Zygisk 注入器（原生，LSPlant + dex 加载）→ bootstrap（libxposed API 实现层）
  → 模块本体（只写 `XposedInterface` / `XposedModuleInterface`，不碰 ART）；
- **作用域配置在 root 侧**：`/data/adb/wekit_zygisk/injection-targets.tsv`，注入前就确定，
  不依赖 app 内存储时序；
- **反检测**：`SoHider: remap`（memfd 重映射）、`nativeHideLoadedModuleLibraries`、
  `hide_path(...)`，让模块 .so 不出现在 `/proc/self/maps`；
- 它的注入器**同样没有 libaff_biz/libowl 处理**（strings 里 0 命中），即上游那种「同步早初始化」。

### 落地与 A/B 结论

1. **开关快照**（已实现）：`FeatureSwitchSnapshot` 把「确实读到的启用集合」按批增量落到
   `filesDir/wcx-enabled-features.txt`，MMKV 读成关闭而快照是开着的就按快照启用 ——
   直接治本文根因 3。实测：清空快照后冷启动，文件恢复 103 行；冷启动验收 PASS。
   （注意必须**按批合并**，整体覆盖会被后一批冲掉，实测只剩 2 行。）
2. **早初始化 A/B**（已实现，开关 `debug.wcx.early_lsplant`）：置 1 后实测
   `LSPlant init failed (attempt 1/3 → 3/3)` ×3 → 该进程模块不启动
   （`E ZygiskEntry: LSPlant init failed after retries`）；
   置回 0 后同一台机器立刻恢复（`本批 169 个：已启用 102 个` + 首屏 hook 命中）。
   **结论：上游「同步早初始化」在我们这份构建上不可行**，我们的延迟等待必须保留；
   差异来源当初猜测是「wcx 额外用了 Dobby、hook 面更大」，**后经排除 Dobby 验证已证伪**
   （根因是 `Failed to find DexFile.<init>`，见下方「排除 Dobby 的验证」）。
3. **so 隐藏**（已实现并验证）：`so_hider.rs`（memfd 重映射 + MAP_FIXED）在移植上游时
   就搬过来了，但 **Java 侧从没调用过** `ArtHookBridge.hideLoadedModuleLibraries()`，
   一直是死代码；而且隐藏列表写的是 `libwcx_native.so`，payload 里的库实际叫
   `libwekit_native.so`，等于没隐藏。现在：
   - `StartupAgent.startup()` 在 `NativeLoader.init` 之后调用它（与上游同位置）；
   - 隐藏主路径改为按目录片段 `/.wcx-native/` 匹配（一次覆盖 libwekit_native /
     libdexkit / libsilk_codec / libandroidx.graphics.path / libmmkv），再按 soname 兜底。

   实测（重启手机让新注入器生效后）：`hideLoadedModuleLibraries -> true`；
   主进程 `/proc/<pid>/maps` 里 `wcx-native|wekit_native|libdexkit|silk_codec` 命中数
   降为 **0**，并出现 36 个 memfd 映射；模块功能不受影响（`本批 169 个：已启用 102 个`、
   冷启动验收 PASS、`E: 0`）。

### 排除 Dobby 的验证（inline hook 后端替换，2026-11-17 晚）

为了验证「早初始化失败是否源于 Dobby 与 libowl 冲突」，把注入器的 inline hooker 从
Dobby 换成上游 WeKit 自研的 Rust inline hook（`wcx-zygisk/native/src/art/inline_hook/`，
从 `wekit-zygisk` 移植，含 ARM64 指令重定位 + memfd 双映射 trampoline；`resolve_symbol`
继续跳过 `VisiblyInitializedCallback` 两个可选 hook）。Dobby 从 `CMakeLists.txt` /
`build.rs` 移除，不再链接。

实测（Android 16 / 微信 8.0.80，重启手机换新注入器）：

| 路径 | 结果 |
|---|---|
| 默认延迟路径（等 libaff_biz 1s） | `LSPlant initialized` + `本批 169 个：已启用 102` + `E=0`，冷启动验收连续 3 次 PASS，无 SIGABRT |
| 早初始化 A/B（`debug.wcx.early_lsplant=1`） | 仍失败：`E/LSPlant: Failed to find DexFile.<init>` → `LSPlant init failed (1/3→3/3)`，进程不崩 |

关键发现：早初始化失败发生在 LSPlant 的 `InitJNI` 阶段（`JNI_GetMethodID` 找 `DexFile.<init>`
隐藏构造器返回 null），**早于任何 inline hook 安装**（`InitNative` 在 `InitJNI` 之后）。
因此早初始化失败与 hook 后端无关——既不是 Dobby 也不是自研 trampoline 造成的，而是
「从 Java 帧里、在 libaff_biz 加载完成前调用 `art::init`」时拿不到隐藏 API 访问权。
上文第 2 点猜测的「差异来源可能是 wcx 额外用了 Dobby」被证伪。

结论：**排除 Dobby 后早初始化仍失败**（败在 `DexFile.<init>`，而非 hook 冲突）。
Dobby→自研 trampoline 的替换本身是干净的（默认路径 3× PASS、无崩溃、去掉了 Dobby 依赖），
但它不能单独达成「启动早期 `art::init`」目标——那需要先解决 `InitJNI` 的隐藏 API 访问
（例如回到 native Zygisk 回调里同步 `art::init`，或预解析 `DexFile.<init>` 等方法 ID），
或整体换成不用 LSPlant 的 WeKite 那套。

### native 早初始化 A/B（`debug.wcx.native_early_init`，2026-11-17 深夜）

为了验证「回到 native Zygisk 回调里同步 `art::init` 是否可行」（此时无 Java 帧、
`DexFile.<init>` 不再受限），新增开关 `debug.wcx.native_early_init`（默认 0）：置 1 时
主进程在 `postAppSpecialize` 里、ClassLoader 之后、`ZygiskEntry.init` 之前同步
`art::init` + `trust_class_loader`。

实测结果（重启手机换新注入器）：

| 路径 | 结果 |
|---|---|
| 默认延迟路径（native_early_init=0） | `LSPlant initialized` + `已启用 102`，主进程正常 |
| 主进程 native 早初始化（native_early_init=1） | **SIGSEGV（status=11）崩溃**：`Class::SetStatus+16` ← `MarkVisiblyInitialized+236` ← `MakeInitializedClassesVisiblyInitialized+148`（tombstone_21） |
| 子进程（`:push`/`:appbrand`，本就一直早期初始化） | **同样 SIGSEGV** 反复崩溃（`dumpsys activity exit-info` 里多条 `status=11`，tombstone_19/20） |

关键发现：
1. 崩溃不是 SIGABRT（status=6），而是 **SIGSEGV（status=11）**，fault addr
   `0x808080808080813f`、`lr` 带 PAC 签名——是 `Class::SetStatus` 被 hook 后、在 ART
   启动早期 `MakeInitializedClassesVisiblyInitialized → MarkVisiblyInitialized` 路径里
   崩溃。与 Dobby 的 libowl `GetMethodID`→abort 是**不同的崩溃**。
2. **子进程也崩，但 Dobby 同样崩**：回退到 Dobby 后端后（`af6b4a15`），`:push` 子进程
   在 23:13 仍以同样 `Class::SetStatus+16 ← MarkVisiblyInitialized+236` 栈 SIGSEGV
   （tombstone_16）。因此这不是自研 trampoline 引入的回归，而是 **LSPlant 在 Android 16
   上早期初始化（无论 Dobby 还是自研 hook）的通病**。
3. 自研 trampoline 与 Dobby 的 relocator 都会把 `paciasp` 原样搬进备份蹦床，两者崩溃
   路径一致，说明根因不在 hook 后端，而在 LSPlant 的 `Class::SetStatus` hook 本身（或其
   replacement 在 `new_status==kVisiblyInitialized` 时跑的 `BackupClassMethods`）与 ART
   早期 `MakeInitializedClassesVisiblyInitialized` 的相互作用。

结论：**延迟逻辑不能移除**。native 早初始化（无论自研 trampoline 还是 Dobby）都会把
主/子进程打崩；自研 trampoline 相对 Dobby 不引入额外回归，且避免了 Dobby 与 libowl 的
SIGABRT（换成 SIGSEGV），但「启动早期 `art::init`」目标仍未达成——需要先解决
`Class::SetStatus` hook 与 `MarkVisiblyInitialized` 的冲突（属 LSPlant 层问题，非
hook 后端问题），或改用 WeKite 那套不碰 `Class::SetStatus` 的方案。

## 排查手法备忘

- **一定要有「每批已启用 N 个」这种量化日志**：否则「feature 被跳过」和「feature 装了但没生效」
  在日志里长得一样；
- **判据必须带进程维度**：微信主进程 + `:push`/`:appbrand`/`:patch` 都会跑 `loadFeatures`，
  子进程合法地 `已启用 0`，用 `tail -1` 会把正常的子进程当成主进程失败；
- **验证「界面是否起来」不要用 importance**，它在 top-activity 绑定瞬间就是 FOREGROUND；
- 反编译参考实现（FunBox 的 `.so`）比读源码更快看清「它到底在什么时机碰 ART」：
  `strings` 找符号 → `nm -D` 取地址 → `llvm-objdump -d --start-address/--stop-address` 看调用。
