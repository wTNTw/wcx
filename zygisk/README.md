# WCX Zygisk 注入器（不装 APK 形态）

> 目标：刷一个 zip 就能用，**不需要 LSPosed，也不安装 WCX 的 APK**。
> 由模块自带的 `zygisk/arm64-v8a.so` 在微信进程里完成注入，自己带 ART hook 引擎（LSPlant）。

当前进度见文末「里程碑」，未完成的部分会在日志里明确报出来而不是静默失败。

---

## 1. 产物

`./x build --release`（或 `./gradlew assembleRelease`）跑完后额外产出：

```
app/build/outputs/zygisk/wcx-<版本>-zygisk-arm64-v8a.zip
```

zip 结构：

```
module.prop                      模块信息（版本号构建时注入）
customize.sh                     安装校验（架构 / API / 包完整性）+ 权限修正
service.sh                       开机后置（记录注入结果，后续放崩溃自愈）
uninstall.sh                     卸载清理
META-INF/com/google/android/     Magisk 官方安装器（update-binary 取自 Magisk 仓库）
zygisk/arm64-v8a.so              注入器（含 wrapper + LSPlant hook 桥）
zygisk/liblsplant.so             ART hook 引擎（运行时 dlopen）
payload/classes*.dex             模块代码（从 release APK 抽出，内存加载）
lib/arm64-v8a/*.so               模块原生库（dexkit / wekit_native，zygote 阶段预加载）
```

刷入：

```bash
adb push app/build/outputs/zygisk/wcx-*-zygisk-arm64-v8a.zip /data/local/tmp/
adb shell su -c "magisk --install-module /data/local/tmp/wcx-*-zygisk-arm64-v8a.zip"
# KernelSU: adb shell su -c "ksud module install /data/local/tmp/xxx.zip"
# APatch  : 管理器里直接选 zip
adb reboot
adb logcat -s WCXZygisk
```

卸载：管理器里移除模块 → 重启（或 `touch /data/adb/modules/wcx-zygisk/remove`）。

---

## 2. 运行时流程

| 阶段 | 进程上下文 | 做什么 |
|---|---|---|
| `preAppSpecialize` | 刚 fork，仍是 zygote 特权 | 只看 `com.tencent.mm` 主进程，其它进程 `DLCLOSE_MODULE_LIBRARY`；用 `Api::getModuleDir()` 把 `payload/classes*.dex` 读进内存；`dlopen` 模块目录里的 `liblsplant.so` 与 `lib/arm64-v8a/*.so` |
| `postAppSpecialize` | 已在 sandbox（`untrusted_app`） | 用 `InMemoryDexClassLoader` 从内存建 ClassLoader；`RegisterNatives` 注册 hook 桥；调用 payload 的 `ZygiskInjectEntry.entry(...)` |
| payload 内 | 微信进程 | `HiddenApiBypass` 豁免隐藏 API → LSPlant `Init` → hook `Instrumentation.callApplicationOnCreate` → `StartupAgent.startup(...)` |

为什么 dex 和 .so 都在 pre 阶段取：sandbox 内读不到 `/data/adb/**`（`0700 root`），
pre 阶段还是 zygote 特权，这是唯一能直读模块目录的窗口。dex 之后一直留在内存里，
不再落盘，也不需要 companion（省掉一个 root 进程和 SELinux 攻击面）。

## 3. 关键设计取舍

- **模块 .so 对 LSPlant 零链接期依赖**
  Zygisk 用 `android_dlopen_ext(fd)` 加载 `zygisk/<abi>.so`，运行时搜索路径里没有模块目录，
  带 `DT_NEEDED=liblsplant.so` 会直接加载失败。因此 `lsplant.hpp` 只是声明，
  `liblsplant.so` 在 pre 阶段 `dlopen(RTLD_GLOBAL)`，之后用 `dlsym` 拿 5 个入口函数指针
  （符号名可在升级 LSPlant 时用 `nm -D liblsplant.so | grep _ZN7lsplant2v2` 核对）。
- **libc++ 静态化（`ANDROID_STL=none`）**
  设备上没有 `libc++_shared.so`，模块 .so 必须自包含：`org.lsposed.libcxx:libcxx` 提供静态 libc++，
  `io.github.vvb2060.ndk:dobby` 提供静态 Dobby（LSPlant 的 inline hook 后端）。
- **hook 桥只需要一个 hooker 类**
  LSPlant 6.x 支持「分派方法签名与目标不一致」，统一用 `NativeHooker.callback(Object[])`，
  不必像老版本那样按「返回值 × 参数类型」生成几十个类。多回调按 priority 挂在 Kotlin 侧。
- **原生库绑定**
  不装 APK 时 `System.loadLibrary` 一定失败（库不在 `nativeLibraryDir` 里），
  所以 `ILoaderService.loadNativeLibrary()` 在 Zygisk 入口下改成「反射列出 native 方法 → 拼 JNI 符号名 →
  `RegisterNatives`」。

---

## 4. 里程碑

| 编号 | 内容 | 状态 |
|---|---|---|
| S1 | `zygisk/*.so` 只在目标进程加载，其它进程零影响 | ✅ 沿用 spike 已验证的结论 |
| S2 | 模块目录在 pre 阶段直读 dex | ✅ spike 已验证（`payload/classes*.dex` 读入内存） |
| S3 | 内存 dex 建 ClassLoader 并跑通 `StartupAgent` | ⏳ 本目录实现，待真机验证 |
| S4 | LSPlant hook 桥（hook/unhook/deoptimize/invokeOriginal） | ⏳ 本目录实现，待真机验证 |
| S5 | feature 层从 `de.robv.android.xposed` 迁移到 `IHookBridge` | ❌ 未开始（58 个文件，feature 目前在 Zygisk 模式下会各自失败但不影响微信） |
| S6 | 资源 / 设置 UI（不装 APK 没有 Activity 入口） | ❌ 未开始（WebUI 或 ActivityProxy 借壳，二选一评审） |
| S7 | 崩溃自愈（连续启动失败自动写 `disable`） | ❌ 未开始（落在 `service.sh`） |

> S5 之前的实际效果：注入链路、hook 引擎、模块启动日志都正常，日志里能看到
> `StartupAgent` 与 `FeaturesLoader` 的输出；但各 feature 调用的 `de.robv` API 不存在，
> 会逐个抛 `NoClassDefFoundError`（被 feature 自身的 runCatching 兜住，微信不会崩）。

## 5. 排错

| 现象 | 排查 |
|---|---|
| logcat 里没有 `WCXZygisk` | Zygisk 没开 / 模块没启用 / 进程名不是 `com.tencent.mm`（只注入主进程） |
| `liblsplant.so 未预加载` | `zygisk/liblsplant.so` 没随包下发，或模块目录 SELinux 上下文不对（重刷一次） |
| `libart.so 符号解析失败` | ROM 的 libart 被裁剪/混淆，需要更新 LSPlant 版本或改用符号前缀解析策略 |
| `lsplant::Init 失败` | ART 版本超出 LSPlant 支持范围，日志里带 Android 版本 |
| `RegisterNatives(NativeHookBridge) 失败` | payload dex 被 R8 改名了，检查 `app/proguard-rules.pro` 里的 keep 规则 |
