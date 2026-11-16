# 微信 8.0.80：唤醒后模块不生效、微信自己重启的定性与修复

排查时间：2026-11-15。设备：OnePlus PJZ110（ColorOS / Android 16，KernelSU），微信 8.0.80
（versionCode 3220），模块走 **Zygisk 注入器**（`zygisk-wcx`，非 LSPosed）。

现象（用户描述）：微信长时间后台睡眠后唤醒，「模块不生效」，过几秒微信自己重启，然后模块恢复
生效；与更早那种「长时间卡屏」不同，这次没有长冻屏。

结论：**不是内存不足、也不是第一次抓到的那个 `bg anr`，而是模块自己的延迟启动线程在 ART
走栈时踩空（SIGSEGV, pc=0），直接把微信主进程带走。**

## 一、直接证据：tombstone

`/data/tombstones/tombstone_00`（2026-11-15 23:12:39）：

```
Cmdline: usap64            uid: 10514        Process uptime: 975s       not key_process
signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0
Cause: null pointer dereference
pc 0000000000000000        ← 调到空函数指针
tid 11922, name: WCX-DeferredSta

backtrace:
  #00 pc 0x0                       <unknown>
  #01 art::StackVisitor::WalkStack
  #02 art::Thread::CreateInternalStackTrace
  #03 art::Throwable_nativeFillInStackTrace
  #05 java.lang.Throwable.fillInStackTrace
  #06 java.lang.Throwable.<init>
  #07 dalvik.system.CloseGuard.openWithCallSite
  #08 java.io.FileInputStream.<init>
  #10 com.Johnny.wcx.loader.entry.zygisk.ZygiskEntry.isLibaffBizLoaded
  #12 ZygiskEntry.waitForLibaffBiz
  #16 ZygiskEntry.startDeferredStartup$lambda$0
  #21 java.lang.Thread.run
```

对应系统侧同刻：

```
23:12:38.519 am_proc_start [0,31829,10514,com.tencent.mm,top-activity,
               {com.tencent.mm/com.tencent.mm.app.WeChatSplashActivity}]
23:12:38.933 F/libc (31829): Fatal signal 11 (SIGSEGV) in tid 11922 (WCX-DeferredSta)
23:12:39.400 ApplicationExitInfo: process=com.tencent.mm reason=5 (APP CRASH(NATIVE)) status=11
```

紧接着模块在新的进程里重新初始化（这就是用户看到的「微信自己重启、模块又好了」）：

```
23:12:52.324 ZygiskEntry: 等 libaff_biz 稳定后才初始化：映射已连续存在 1037ms
23:12:52.667 ExitReasonRecorder: last exit: reason=5(CRASH_NATIVE 原生崩溃) importance=1000
```

`fault addr 0x0` + `pc 0x0` 说明 ART 在走栈时调用了一个空指针；栈顶那段
`FileInputStream → CloseGuard → Throwable → fillInStackTrace` 是本模块 `isLibaffBizLoaded()`
里 `File("/proc/self/maps").useLines { … }` 的固有副作用 —— **每轮询一次 maps 就构造一次异常栈**。

## 二、为什么会走到这条路径（池进程 + 冻结）

- tombstone 的 `Cmdline` 仍是 `usap64`、`Process uptime: 975s`，而 `am_proc_start` 在
  23:12:38 —— 说明这个进程是 **USAP 池进程**（22:56:24 预孵化，之后一直闲置），
  在 23:12 被交给微信冷启动，特化尚未完成就崩了。
- 池进程闲置期间被 ColorOS 的 cgroup v2 freezer 冻结（实测 `cgroup.events: frozen 1`）。
  延迟启动线程正好被冻在 `isLibaffBizLoaded()` 这一轮里，解冻瞬间继续执行
  `FileInputStream` 构造 → `fillInStackTrace` → ART 走栈 → 空指针。
- 也就是说：**冻结越久 → 越容易在解冻瞬间踩到这条路径**，与「长时间后台睡眠后唤醒」的现象吻合。

## 三、已排除的两个假设

### 1）不是「内存不够被 OOM 杀」

- 微信今天的 `ApplicationExitInfo` 里 reason 分布：`SIGNALED ×8 / EXIT_SELF ×6 / ANR ×3`，
  **一条 `LOW_MEMORY` 都没有**；`dmesg` 里也没有 `lowmemorykiller` / OOM 记录。
- 17:58–21:53 挂机采样（微信 swap 合计 **峰值 2091MB**，被冻/解冻几十轮）：
  `bg anr` 0 次、`ANR in com.tencent` 0 次、`am_kill …10514` 0 次。swap 比出事时（958MB）
  高出一倍多也没被杀。

### 2）第一次抓到的 `bg anr` 是另一条线（未闭环）

17:53:14 那次确实是系统杀的，但 reason 是 ANR 而不是 OOM：

```
am_kill: [0,25808,com.tencent.mm,0,bg anr,244688]
description=bg anr: Broadcast of Intent { act=android.intent.action.SCREEN_ON
            cmp=com.tencent.mm/.app.o }
anr trace: Foreground: No / uptime 414s / RssKb 244688 / VmSwapKb 981440 / 全线程 do_signal_stop
```

即「亮屏广播派发后 60s 内没处理完」。三轮针对性复现（息屏 3 / 20 / 10 分钟 + 亮屏 + 进微信）
都没能再触发，因此这条只是**已证实存在、尚未闭环**：怀疑是冻结 churn 与广播处理竞态，
等这个 SIGSEGV 修完后再看是否还残留。

## 四、修复（本次改动）

`app/src/main/java/com/Johnny/wcx/loader/entry/zygisk/ZygiskEntry.kt`

1. **`isLibaffBizLoaded()` 改用 `Os.open/Os.read/Os.close` 扫 maps**，不再构造
   `FileInputStream` / `Throwable`，从根上掐掉「轮询 maps 却顺手走一次 ART 走栈」这条路径。
   分块 64KB 读，跨块用 `needle.size-1` 字节重叠兜住边界。
2. **deadline 改用 `SystemClock.elapsedRealtime()`**，并在每轮循环开头判超时：进程被冻结
   再解冻时时钟早已越过 deadline，循环立刻退出，不会再「解冻后又读一次 maps」。
3. 轮询间隔 100ms → 250ms（`LIBAFF_BIZ_POLL_INTERVAL_MS`），减少无效唤醒与 syscall。
4. 线程名由 `WCX-DeferredStartup` 改为 `WCX-Defer-<pid>`（tombstone 只留 15 字符，
   带上 pid 便于后续定位到底哪个进程崩的）。

未改动 `LIBAFF_BIZ_GRACE_MS`（1s 稳定宽限）与 15s 兜底逻辑 —— 它们对应的 libowl 竞态
（见 `wechat-8.0.80-startup-abort.md`）与本次崩溃互不影响。

## 五、验证方式

- 冷启动 / 唤醒后进微信，确认不再出现 `F/libc … (WCX-DeferredSta)` 的 SIGSEGV；
- `ls /data/tombstones/` 不应新增 tombstone；
- 模块日志仍应出现 `等 libaff_biz 稳定后才初始化` → `deferred startup: posting module start`；
- 若要复现旧行为，把 `isLibaffBizLoaded` 换回 `File(...).useLines` 并把进程冻 10 分钟以上即可。

抓现场的采样脚本（本次实际用的）思路：每 2–5s 读
`/sys/fs/cgroup/apps/uid_<wechat_uid>/cgroup.freeze`（`1` = 被 ColorOS 冻结，
注意 **cgroup v2 freezer 不会让 `/proc/<pid>/stat` 变成 `T`**，用 state 判断是无效的）、
微信各进程 `VmSwap`/`VmRSS` 合计、`/proc/meminfo`、`/proc/swaps`，同时把过滤后的
logcat（`am_kill|ANR in|bg anr|SCREEN_ON|Start proc|HansManager|lmkd`）落盘，
并在主进程 pid 变化时自动抓一份 `dumpsys activity exit-info` 快照。

## 六、部署注意

本次只改了 Java 侧（module.apk 里的 `ZygiskEntry`），**注入器 `libwcx_zygisk.so` 没变**：

- 只需替换 `/data/adb/modules/zygisk-wcx/module.apk` 后杀掉微信进程即可，不需要重启手机；
- 若同时换了注入器 `.so`，则必须重启手机（Zygisk 开机缓存注入器）；
- **用 `adb push` 替换后必须修 SELinux 上下文**：push 上来的文件会带
  `u:object_r:adb_data_file:s0`，注入器随后会在 logcat 报
  `Zygisk: cannot open module.apk: Permission denied (os error 13)`，模块完全不注入：

  ```sh
  adb push wcx-vXXX-arm64-v8a.apk /data/adb/modules/zygisk-wcx/module.apk
  adb shell 'chmod 644 /data/adb/modules/zygisk-wcx/module.apk; \
             chcon u:object_r:system_file:s0 /data/adb/modules/zygisk-wcx/module.apk'
  ```

  校验方式：`ls -lZ` 应与目录内其它文件一致（本机为 `u:object_r:system_file:s0`）。
  替换后用 `md5sum` 对一下构建产物，并确认
  `/data/user/0/com.tencent.mm/files/mmkv/wcx-payload-<hash>.apk` 的大小/hash 也跟着变了
  （payload 是从 module.apk 复制过去的，名字带 hash）。

## 七、本次部署验证（2026-11-15 23:37）

- 新 `module.apk` md5 `84e2f1d4f050eabfbff2cb72292ea082`（v347），`chcon` 修上下文后注入恢复正常；
- 新 payload：`wcx-payload-3f4628-2a39dc9-6af9d18a-0.apk`（44277193 bytes，与 module.apk 同尺寸）；
- 主进程路径正常：`deferred startup: LSPlant initialized` → `posting module start to main thread`
  → `WCX module started`，`ProxyInstrumentation: callActivityOnCreate LauncherUI`、
  `Hchat 脚本子系统引导完成`、`QuickRead 已捕获原生已读 storage 实例` 均正常；
- `/data/tombstones/` 无新增（仍是 23:12 那份旧崩溃）。

### 修复后观察（2026-11-15 23:40 → 11-16 09:37）

| 观察项 | 结果 |
|---|---|
| 夜间 8h 采样（23:40→07:40，微信被冻/解冻几十轮） | `MAIN PROCESS RESTART` **0 次**、`ANR in com.tencent` **0 次**、主进程 pid 全程不变 |
| 同期 swap 峰值 | **2278MB**（比出事时 958MB 高一倍多，仍未被杀） |
| 次日早上解锁进微信（用户确认） | 模块正常，无重启/无卡屏 |
| 主进程存活 | `12931` 从 23:38 一直到次日 09:35（约 10 小时）未重启；`exit-info` 无任何主进程退出记录 |
| 定向冷启动 3 轮（`am force-stop` + `am start`） | 三轮均正常：`ZygiskEntry.init: process=com.tencent.mm` → `等 libaff_biz 稳定` → `posting module start`；tombstone 0；`exit-info` 只有 `reason=10 USER REQUESTED / FORCE STOP`（我们自己停的） |

注意：崩溃需要「USAP 池进程闲置并被冻结较久后再度特化」这个组合，强制冷启动拿到的是
新鲜池进程，条件不完全等价 —— 因此这轮验证只能说明「修复没有引入回归、常规路径不再触发
该崩溃」，长期仍需观察自然唤醒场景。若再次出现，拿 `WCX-Defer-<pid>` 线程名去
`/data/tombstones/` 对一下就知是否同一路径。

## 八、第二个失败模式：libaff_biz 超时后的兜底路径会「永久判死」（2026-11-16）

现象（同一台机器，v347 首版修复后）：微信进程活着、界面正常，但模块完全不生效。日志：

```
09:37:38.329 W 等 libaff_biz 稳定超时（15000ms），按兜底路径继续
09:37:38.352 I deferred startup: libaff_biz timeout after polling
09:37:38.457 E deferred startup: LSPlant init failed; module not started in main process
```

`tombstones/` 为空（不是崩溃）。复现条件：**息屏状态下冷启动微信**（本次是自动化 `am start`
拉起的，屏幕处于熄灭）——微信被厂商冻结，启动阶段走不到加载 `libaff_biz` 的那一步，
15s 超时后立即调 `nativeDeferredInitialize()`（`art::init`）必然失败，而代码直接 `return`，
这个进程就再也没有第二次机会。

### 修复（两处）

1. **兜底前先确认宿主真的在启动**（`ZygiskEntry.kt`）：
   - `libaff_biz` 超时后，先 `acquireApplication(FALLBACK_APP_WAIT_MS = 60s)` 等宿主 Application；
     等不到就判定为「池进程 / 被冻结」（`W 宿主 Application 未就绪…本进程不启动模块`），
     而不是盲目 `art::init`；
   - `art::init` 改为 `initializeLsPlant()`：最多 3 次、间隔 3s 重试（单次失败不再永久放弃）；
   - `acquireApplication()` 改为接受超时参数，内部用 `elapsedRealtime` 计时。
2. **启动失败计数排除系统行为**（`StartupCrashGuard` + `ExitReasonRecorder`）：
   - 新增 `ExitReasonRecorder.lastMainProcessExitReason()`（直接问 AMS，不读 SharedPreferences ——
     `recordOnStartup` 跑在后台线程，与守卫并发，读盘会拿到上一轮的旧值）；
   - 新增 `ExitReasonRecorder.isSelfCausedExit(reason)`：只有 `CRASH / CRASH_NATIVE /
     INITIALIZATION_FAILURE / UNKNOWN` 算启动失败；`ANR / LOW_MEMORY / SIGNALED / OTHER /
     FREEZER / USER_REQUESTED / USER_STOPPED / 包更新` 等一律不计。
   - 背景：修复前连续测试把计数推到了 `attempt #3`（阀值就是 3），再失败一次下次开机
     `service.sh` 就会写 `disable` 把模块自动停用。

### 验证（2026-11-16 09:57，v347 第二版，md5 `95e5545…`）

```
09:57:22 ZygiskEntry.init: process=com.tencent.mm   （新 payload wcx-payload-3f2770-…）
09:57:33 deferred startup: libaff_biz loaded after polling
09:57:33 deferred startup: LSPlant initialized → posting module start to main thread
09:57:34 StartupCrashGuard: 上次退出 reason=10(USER_REQUESTED 被强行停止)，属系统/用户行为，不计入启动失败计数
09:57:35 FeaturesLoader: enabling all hook items took 480ms
```

两条修复都生效；息屏冷启动再测一次（09:58）也正常走完 `libaff_biz loaded` 路径。
**未观测到兜底路径本身**（需“启动瞬间被冻结且 libaff_biz 始终不出现”），该分支待自然场景覆盖。

## 九、遗留

- 池进程/未完成特化的进程里，延迟启动 15s 兜底后仍会走 `nativeDeferredInitialize()`
  （提前 `art::init`）。虽然本次崩溃与之无关，但在池进程里提前初始化 LSPlant 仍有
  与后续 `libaff_biz` 的 `JNI_OnLoad` 相撞的理论风险，需要确认 Zygisk 在 USAP 池进程上的
  回调语义后再决定是否「池进程不启动延迟启动」。
- `StartupCrashGuard` 目前把「被系统/崩溃带走的进程」也算一次启动失败（阈值 3，`service.sh`
  在开机时检查）。本次崩溃后计数为 1，暂未触发停用；建议后续按
  `ExitReasonRecorder` 的 reason 过滤（`reason=6 ANR`、`reason=5 CRASH_NATIVE` 不计入
  由模块自身 hook 导致的启动失败），避免用户遇到「模块被自动停用」。
