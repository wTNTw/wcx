# WCX 首 Activity 启动门竞态修复设计

日期：2026-11-19

目标基线：`zygisk-module` at `72d1fad1`

## 1. 背景与故障定义

微信主进程不能在 Zygisk `postAppSpecialize` 阶段立即初始化 LSPlant。Android 16 与微信 8.0.80 的 ART、`libaff_biz` 和 libowl 启动时序会导致隐藏 API 初始化失败、SIGABRT 或 SIGSEGV。因此现有实现保留两道门：

1. `/proc/self/maps` 中的 `libaff_biz.so` 连续稳定 1 秒；
2. 首个 Activity transaction 到达，但尚未进入 Activity 创建和 `onCreate`。

当前实现按以下顺序执行：

1. 后台线程等待 `libaff_biz` 稳定；
2. 获取宿主 `Application`；
3. 向主线程投递任务，替换 `ActivityThread.mH` callback 和 `mInstrumentation`；
4. 由代理在首 Activity 创建前初始化 LSPlant 和 WCX。

步骤 1–3 存在竞态。LauncherUI 可以在启动门安装前完成创建。此时后续 `am start` 只会向顶部 Activity 投递 Intent，不会再产生新的 `LaunchActivityItem`，启动门永久无法触发。现场表现为：

- `libaff_biz` 门成功；
- 日志出现“首个 Activity 启动门已安装”；
- 没有“首个 Activity 即将创建”或“LSPlant initialized”；
- LauncherUI 已是 `topResumedActivity`；
- 无 Java/native crash，但 WCX 主进程功能未启动。

## 2. 目标与非目标

### 2.1 目标

- 启动门必须在首 Activity transaction 可被分发前安装；
- LSPlant 仍只能在 `libaff_biz` 稳定后初始化；
- WCX 仍必须在首 Activity 创建前完成主进程启动；
- 纯后台启动且没有 Activity 时不得初始化 LSPlant；
- 保留现有 `libaff_biz` 宽限、LSPlant 重试、标准/legacy 入口和 `IHookBridge` 语义；
- 为启动门安装、Activity 到达、libaff 就绪、等待耗时和最终结果提供可机器判定的日志；
- 修复后的稳定 APK 通过严格真机矩阵后，才允许继续性能优化 APK 验收。

### 2.2 非目标

- 不移除或缩短 `libaff_biz` 稳定门；
- 不回到 Zygisk native 回调中的早期 LSPlant 初始化；
- 不在漏失后自动重启微信；
- 不改变 feature、DexKit、media capability 或包体积优化；
- 不修改 injector；设备部署只替换 `module.apk`。

## 3. 方案比较

### 3.1 采用：提前安装最小 Activity gate，延后满足初始化条件

在 `ZygiskEntry.init` 的主进程路径中同步安装最小 Activity 拦截器，然后再启动等待 `libaff_biz` 的后台线程。拦截器只负责捕获首个 Activity transaction；完整的 Activity 路由代理仍由 `WeLauncher` 初始化。

后台线程等待 `libaff_biz` 和宿主 `Application`，再发布启动准备结果。若 Activity 先到，ActivityThread 在进入 Activity 创建前等待该结果；若准备结果先到，则首 Activity 到达时立即执行 LSPlant 和 WCX 启动。

该方案同时满足“不能早于 libaff”与“不能晚于首 Activity”两项约束。

### 3.2 否决：晚装 gate 后检测漏失并重启进程

该方案会改变用户可见行为，污染启动性能数据，并可能触发恢复模式或启动失败计数，不适合作为基线修复。

### 3.3 否决：libaff 稳定后直接启动模块

该方案会在 BootReceiver 等纯后台拉起中初始化 LSPlant，恢复此前已证实的崩溃风险，与现有安全语义冲突。

## 4. 架构设计

### 4.1 ActivityProxy 分层

`ActivityProxy` 拆分为两个幂等层次：

1. **启动门拦截层**
   - 不需要 `Context`；
   - 在 `ZygiskEntry.init` 返回前同步替换 `ActivityThread.mH.mCallback`；
   - 注册一次性的 `beforeFirstActivity` action；
   - `mInstrumentation` 在早期可能尚未创建，因此不作为同步安装成功的前提；后台线程取得 `Application` 后、发布 `Ready` 前再补装 Instrumentation 后备；
   - 可以在完整模块尚未启动时独立工作。

2. **完整 Activity 路由层**
   - 由现有 `ActivityProxy.init(context)` 入口初始化；
   - 复用已安装的 Handler/Instrumentation 包装，不重复嵌套；
   - 继续安装 `IActivityManager`、`IActivityTaskManager` 和 PackageManager 代理；
   - 保持模块 Activity token、Intent 恢复和 Parcelable hooks 的现有语义。

启动门层和完整路由层分别维护初始化状态，避免当前单一 `initialized` 标志把两种职责耦合在一起。

### 4.2 Deferred startup 协调器

主进程延迟启动使用一个进程内、一次性的协调状态：

- `Pending`：启动门已安装，后台准备尚未完成；
- `Ready(application, libaffLoaded)`：允许首 Activity 执行 LSPlant/WCX 启动；
- `Failed(reason)`：准备失败，首 Activity 不启动模块并记录明确原因；
- `Consumed`：首 Activity action 已执行，不得重复启动。

协调器包含一个完成信号。所有后台路径，包括异常路径，都必须发布 `Ready` 或 `Failed`，避免 ActivityThread 无限等待。ActivityThread 最多等待 5 秒；超时后以 `prepare_failed` fail-open，并把协调器置为 `Consumed`，后台线程此后发布的迟到结果必须丢弃，不能在首 Activity 之后补启动模块。

### 4.3 主进程时序

默认路径按以下顺序执行：

1. `ZygiskEntry.init` 完成 loader service、bridge 和路径字段发布；
2. 同步安装最小 Activity gate；安装失败时记录错误并停止本进程模块启动；
3. 启动 `WCX-Defer-<pid>` 后台线程；
4. 后台线程等待 `libaff_biz` 连续稳定 1 秒；
5. 后台线程获取宿主 `Application`，并补装 Instrumentation 后备；
6. 发布 `Ready` 或 `Failed`；
7. 首 Activity transaction 到达：
   - 若仍为 `Pending`，在 ActivityThread 上等待后台准备结束，最多 5 秒；
   - 若为 `Ready`，在单独线程执行 `nativeDeferredInitialize()`，ActivityThread 同步等待结果；
   - LSPlant 成功后，在 Activity 创建前调用 `UnifiedEntryPoint.prepareClassLoaderBridge()` 和 `StartupAgent.startup()`；
   - 最后才允许原始 Activity transaction 继续执行。

如果主进程仅被后台组件拉起且没有 Activity，步骤 7 不发生，因此不会初始化 LSPlant。

### 4.4 A/B 开关与失败行为

`debug.wcx.early_lsplant` 保留原语义：显式启用时跳过首 Activity gate，继续走现有早初始化试验路径。正式验收时该属性必须为空。

失败行为保持 fail-open：启动门准备或 LSPlant 初始化失败时，记录错误并允许微信 Activity 继续创建，不因模块失败阻断宿主。失败不得自动重启微信，不得循环安装 gate。

## 5. 并发与死锁约束

- 启动门必须在启动后台等待线程前同步安装；
- `beforeFirstActivity` 仍使用原子 get-and-set，最多执行一次；
- 后台线程不得等待主线程任务才能发布准备结果；否则 ActivityThread 等待协调器时会形成死锁；
- ActivityThread 的准备等待上限固定为 5 秒，超时后必须 fail-open，后台迟到结果不得再启动模块；
- `Application` 获取只能使用不依赖主线程消息执行的 `ActivityThread.currentApplication()` 轮询；
- 协调器必须在 `finally` 等异常路径发布失败结果；
- LSPlant 初始化继续放在专用线程，ActivityThread 只等待完成信号；
- 完整 `ActivityProxy.init(context)` 必须识别并复用早期包装器。

## 6. 可观测性

新增结构化日志，至少包含：

- `startup_gate_installed`：最小 gate 安装完成；
- `startup_gate_activity_arrived`：首 Activity transaction 被捕获；
- `startup_gate_ready`：libaff/Application 准备完成；
- `startup_gate_wait_ms=<n>`：Activity 等待准备结果的时长；
- `startup_gate_result=started|prepare_failed|lsplant_failed|startup_failed`；
- 现有 `LSPlant initialized`、`WCX module started`、`ProxyInstrumentation` 和 startup performance 摘要继续保留。

探针以当前主进程 PID 和本次启动日志窗口为边界，不能把子进程的 `已启用 0` 当作主进程失败。

## 7. 分支与部署隔离

1. 从 `zygisk-module` 的 `72d1fad1` 创建独立修复分支和 worktree；
2. 将本设计文档提交带入修复分支；
3. 修复分支只包含启动竞态、日志、探针和测试，不包含性能优化提交；
4. 在 Debian VM `/root/wcx` 构建 arm64 稳定修复 APK；
5. 记录构建产物 SHA-256；确认 injector MD5 仍为 `93dcbeea7a5ea0075c36c315696be62f`；
6. 设备上只替换 `/data/adb/modules/zygisk-wcx/module.apk`，修复权限和 SELinux context，不重启框架或设备；
7. 严格矩阵通过后，把修复提交 cherry-pick 到 `perf/module-size-startup`；
8. 在性能分支重新构建并恢复 Task 16。

## 8. 测试与验收

### 8.1 静态契约测试

新增源码契约测试，验证：

- 主进程同步安装启动门发生在启动后台线程和 `waitForLibaffBiz()` 之前；
- 启动门安装 API 不需要 `Context`；
- Handler 与 Instrumentation 包装是幂等的，且早期 gate 不依赖尚未创建的 `mInstrumentation`；
- 完整 Activity 路由初始化复用早期包装；
- 后台准备的所有返回和异常路径都会发布完成结果；
- `debug.wcx.early_lsplant` 仍保留绕过 gate 的语义；
- Parcelable Intent hooks 仍只在模块 Activity 边界安装。

### 8.2 VM 验证

- 运行全部 Python 契约测试；
- 构建 standard arm64 release；
- 运行 minified ABI contracts；
- 确认 injector 未变化；
- 确认 APK 中标准/legacy 入口、静态 JNI 和动态脚本 ABI 未回归。

### 8.3 严格真机矩阵

在已清空 `debug.wcx.wekite_m2b`、`debug.wcx.wekite_selftest` 和 `debug.wcx.early_lsplant` 的设备上执行：

- 普通冷启动 10 次；
- 息屏冷启动 5 次；
- 后台拉起后再进入前台 3 次。

每次必须同时满足：

- `startup_gate_installed = 1`；
- `startup_gate_activity_arrived = 1`；
- `startup_gate_result=started`；
- `LSPlant initialized = 1`；
- 主进程 feature `enabled_max >= 10`；
- `ProxyInstrumentation: callActivityOnCreate` 命中；
- 无 `startup_gate_not_triggered`；
- 无 WCX `E/`、Java crash、native crash、SIGABRT、SIGSEGV；
- 无新增 `ApplicationExitInfo reason=5` 或 `status=6/11`；
- 首屏可见且模块 UI/功能存在。

后台拉起阶段在 Activity 到达前应只有 `startup_gate_installed`，不得出现 `LSPlant initialized`；进入前台后才允许完成启动。

任何一次失败都停止性能 APK 部署，保存完整日志、exit-info 和进程状态并回到诊断。

## 9. 完成条件

只有以下条件全部满足，稳定基线才视为可靠：

1. 修复分支 VM 验证全部通过；
2. 严格真机矩阵 18/18 通过；
3. injector identity 未变化；
4. 没有新增启动崩溃或异常退出；
5. 修复提交已移植到性能分支并通过同一组静态/构建验证。

之后才恢复性能优化 Task 16，部署优化 APK并比较启动 P50/P95、进程 maps 和按需能力首用行为。
