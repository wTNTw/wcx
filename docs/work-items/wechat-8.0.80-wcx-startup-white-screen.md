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

## 排查手法备忘

- **一定要有「每批已启用 N 个」这种量化日志**：否则「feature 被跳过」和「feature 装了但没生效」
  在日志里长得一样；
- **判据必须带进程维度**：微信主进程 + `:push`/`:appbrand`/`:patch` 都会跑 `loadFeatures`，
  子进程合法地 `已启用 0`，用 `tail -1` 会把正常的子进程当成主进程失败；
- **验证「界面是否起来」不要用 importance**，它在 top-activity 绑定瞬间就是 FOREGROUND；
- 反编译参考实现（FunBox 的 `.so`）比读源码更快看清「它到底在什么时机碰 ART」：
  `strings` 找符号 → `nm -D` 取地址 → `llvm-objdump -d --start-address/--stop-address` 看调用。
