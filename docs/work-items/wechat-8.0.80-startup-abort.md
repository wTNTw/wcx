# 微信 8.0.80：启动期 SIGABRT / 卡启动页 的定性结论与修复

排查时间：2026-11-14。设备：Android 16 / arm64-v8a / 微信 8.0.80（versionCode 3220）。
现象：微信**卡在启动页**（系统判 ANR）、后台切回前台偶发冻住、每天 4-5 次进程消失。

## 崩溃签名（反复出现，11-11 起每天 4-8 次）

```
bionic abort
 ← art::Runtime::Abort
 ← libbase LogMessage
 ← art::Thread::AssertNoPendingExceptionForNewException
 ← art::Thread::ThrowNewException
 ← art::JNI::GetMethodID
 ← libowl.so   owl::v9::HookGetMethodID          ← 微信自己的 JNI hook 库
 ← libaff_biz.so  JNI_OnLoad                      ← 微信自己的业务库
 ← art::JavaVMExt::LoadNativeLibrary  (System.load → Runtime.load0)
```

机制：`System.load` 持有 `Runtime.class` 监视器；这条线程在 `libaff_biz` 的 `JNI_OnLoad`
里被 `libowl` 的 `GetMethodID` 包装器触发 ART 断言后进入 abort 流程（要 dump 全部线程），
主线程等 `Runtime.class` 锁 → 界面冻住 → ANR → 进程被杀。**所以「卡启动页」和「SIGABRT」
是同一件事的两个阶段。**

## A/B 结论（同一台设备、同一版本）

| 配置 | 结果 |
|---|---|
| 停用模块（不注入） | 不卡（用户确认） |
| 注入 + 全部功能 hook | 卡死 / 每天 4-8 次 SIGABRT |
| 注入 + 零功能 hook（`debug.wcx.no_hooks=1`） | 仍卡死 |
| 注入 + **不初始化 LSPlant/Dobby**（编译期 `WCX_NO_LSPLANT_BUILD=true`） | 连续 1 小时不卡、不自重启 |

⇒ 与**功能 hook 无关**，问题出在 **LSPlant/Dobby 这一层 ART inline hook**
（它改 `ArtMethod`/`DexFile` 等底层结构，与微信 `libowl` 的 JNI hook 互相踩）。

## 已实施的修复：把 LSPlant 初始化推迟到 libaff_biz 加载之后

核心思路：`libaff_biz` 只在微信主进程的启动期加载一次，冲突窗口就在那一瞬间。
既然「不初始化 LSPlant」不崩、而「早期初始化」必崩，那就**等微信自己把 libaff_biz
加载完，再从 Java 帧里补初始化 LSPlant**。

实现（分支 `zygisk-module`，提交见 git log）：

1. **原生侧（`wcx-zygisk/native/src/lifecycle.rs`）**
   - 删掉实验残留 `debug_prop()` / `preload_aff_biz()` / `WCX_RUN_JAVA_ENTRY`。
   - `do_post_app_specialize` 里按进程类型分流：
     - **主进程**（`process_name` 无 `:`）：只做 payload + DexClassLoader，**不** `art::init`，
       打日志 `Zygisk: 主进程推迟 LSPlant 初始化，等待 libaff_biz 加载完成`。
     - **子进程**（`com.tencent.mm:push` 等，不加载 libaff_biz）：保持原样早期 `art::init`
       + `trust_class_loader`。

2. **原生侧（`wcx-zygisk/native/src/natives.rs`）**
   - `jni_native_initialize` 改为「延迟容忍」：只负责注册 ArtHookBridge 的 natives，
     不再要求 `art::is_initialized()`。
   - 新增 JNI 导出 `nativeDeferredInitialize()`：`art::init` + `trust_class_loader`
     （从 ZygiskEntry 的 classloader 取 module classloader）。

3. **Java 侧（`ZygiskEntry.kt`）**
   - 主进程走「延迟启动」路径：起后台线程 `WCX-DeferredStartup`，轮询 `/proc/self/maps`
     里是否出现 `libaff_biz.so`（每 100ms，超时 15s 兜底），然后调 `nativeDeferredInitialize()`。
   - 成功后用 `ActivityThread.currentApplication()` 拿 Application，**post 回主线程**执行
     `UnifiedEntryPoint.prepareClassLoaderBridge()` + `StartupAgent.startup()`（即 `WeLauncher.init`）。
     —— 必须回主线程：`ActivityProxy` 要在主线程替换 `Instrumentation`，与原来
     `callApplicationOnCreate` 的时序保持一致，避免后台线程替换 Instrumentation 与首帧渲染抢跑。
   - 子进程保留原 `LoadedApk.createAppFactory` 钩子链（LSPlant 已早期初始化）。

4. **`UnifiedEntryPoint.kt`**：把模块 classloader 桥接（`HybridClassLoader` parent 交换）抽成
   `prepareClassLoaderBridge()`，供早期入口和延迟入口共用。

## 关键未知的实测结论（2026-11-14）

LSPlant 文档要求「在 native Zygisk 回调里、成为 Java 帧调用者之前」初始化，担心从 Java 帧里
调用会被 hidden API 限制挡住。**实测没有被挡住** —— 从后台线程/主线程 Java 帧里调
`art::init` + `trust_class_loader` 都成功。日志顺序：

```
Zygisk: 主进程推迟 LSPlant 初始化，等待 libaff_biz 加载完成      ← postAppSpecialize
ZygiskEntry: deferred startup: libaff_biz loaded after polling    ← 后台线程轮询命中
Zygisk: LSPlant initialized                                       ← 此时才初始化，晚于 libaff_biz
Zygisk: trusted 1 DexFile(s) for class loader ...
ZygiskEntry: deferred startup: posting module start to main thread
WeLauncher: loading in process name=com.tencent.mm, type=1        ← 主线程
```

`LSPlant initialized` 不再早于 libaff_biz 出现；功能（侧边栏、WCX 设置入口、Hchat 脚本子系统
引导）照常安装，`Hchat 脚本子系统引导完成` 正常出现。

## 残余观察（与本修复无直接因果关系，待继续跟踪）

- 早期一版把模块启动放在后台线程时，观察到一次 `ViewTreeLifecycleOwner not found from
  com.tencent.mm.blink.FirstScreenFrameLayout` 的 **Java** 崩溃（`*-CrashTmp.zip`）。这是微信
  自己 blink 闪屏代码里的 AndroidX lifecycle 崩溃，**不是** libowl/libaff_biz 的 SIGABRT；
  把启动改回主线程后未再复现（`StartupCrashGuard` 计数清零，进程稳定）。
- 之前的构建里也出现过一次 `Resources.newTheme() NPE`（`*-CrashTmp.zip`），与本次 LSPlant
  时序无关，属另一条线，未在本任务范围内处理。

## 验收对照

1. ✅ 出现「主进程推迟 LSPlant 初始化」标记，主进程不再出现早于 libaff_biz 的 `LSPlant initialized`；
2. ✅ 侧边栏 / WCX 设置入口 / Hchat 脚本子系统引导均正常（`Hchat 脚本子系统引导完成`）；
3. ✅ 部署后冷启动无 SIGABRT、无新增 `*-CrashTmp.zip`（主线程版）、`StartupCrashGuard` 计数清零；
4. ✅ 无新增 `E/WCX`（仅网络相关的 `W/WCX`，与启动无关）。

## 部署注意（换注入器 .so 要重启手机）

- 产物 `app/build/outputs/zygisk/wcx-v347-arm64-v8a.apk`（改名 `.zip` 即 Magisk 模块）。
- 部署必须同时更新两处：
  1. `module.apk` → `/data/adb/modules/zygisk-wcx/module.apk`（0644）；
  2. APK 里的 `lib/arm64-v8a/libwcx_zygisk.so` → `/data/adb/modules/zygisk-wcx/zygisk/arm64-v8a.so`。
- 换注入器 .so 后必须**重启手机**（Zygisk 开机缓存注入器）；只换 module.apk 杀进程即可。
