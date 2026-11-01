# WCX Zygisk 注入器（APK 一体包形态）

> 目标：刷一个 zip 就能用，**不需要 LSPosed**；而且这个 zip 就是 APK 本身 ——
> `wcx-<版本>-arm64-v8a.apk` 改名成 `.zip` 即可在 Magisk / KernelSU / APatch 刷入。

---

## 1. 产物

`./x build --release`（或 `./gradlew assembleRelease`）跑完后额外产出：

```
app/build/outputs/zygisk/wcx-<版本>-arm64-v8a.apk   # 可安装 APK + 可刷模块包（同一份字节）
app/build/outputs/zygisk/wcx-<版本>-arm64-v8a.zip   # 上者的副本，纯为方便直接刷
```

为什么能一个文件两用：Magisk/KernelSU 的安装器只解压 zip 里 **除 `META-INF/` 之外**的内容，
于是 APK 自带的 `classes*.dex`、`lib/<abi>/*.so` 会原样落到模块目录 —— 正好是注入器需要的 payload。
再额外注入几个模块条目就够了：

```
module.prop                      模块信息（版本号构建时注入）
customize.sh                     安装校验（架构 / API / 包完整性）+ 权限修正
service.sh                       开机后置（记录注入结果，后续放崩溃自愈）
uninstall.sh                     卸载清理
zygisk/arm64-v8a.so              注入器（含 LSPlant hook 桥）
zygisk/liblsplant.so             ART hook 引擎（运行时 dlopen）
META-INF/com/google/android/     Magisk 官方安装器（recovery 刷入用）
classes*.dex                     ← APK 原有
lib/arm64-v8a/*.so               ← APK 原有（dexkit / wekit_native …）
res/ assets/ resources.arsc …    ← APK 原有（注入器不用，但跟着落到模块目录，无副作用）
```

## 2. 构建流程（顺序不能变）

APK 的 v2/v3 签名覆盖整个 zip（条目 + 中央目录），所以模块文件必须**在签名之前**写进去：

1. AGP 产出 release APK
2. `PackageZygiskApkTask` 重打包：复制全部条目（保留 STORED/DEFLATED 方式）+ 注入模块条目，
   丢弃旧签名（`MANIFEST.MF` / `*.SF` / `*.RSA`）
3. `zipalign -P 16 -f 4`：`extractNativeLibs=false` 要求 `.so` 未压缩且按页对齐
   （`resources.arsc` 也必须保持未压缩，重打包时原样保留 STORED）
4. `apksigner` 用仓库固定密钥重签（v1+v2+v3）
   - 没找到 keystore 时产物为 **unsigned**，只能本机测试，无法覆盖安装

## 3. 刷入

```bash
# 方式一：直接用 zip 副本
adb push app/build/outputs/zygisk/wcx-<ver>-arm64-v8a.zip /data/local/tmp/
adb shell su -c "magisk --install-module /data/local/tmp/wcx-<ver>-arm64-v8a.zip"
# KernelSU: adb shell su -c "ksud module install /data/local/tmp/xxx.zip"
# APatch  : 管理器里选 zip

# 方式二：把 APK 改名成 .zip（字节完全相同）
cp wcx-<ver>-arm64-v8a.apk wcx-<ver>-arm64-v8a.zip   # 再刷

adb reboot
adb logcat -s WCXZygisk
```

卸载：管理器里移除模块 → 重启（或 `touch /data/adb/modules/wcx-zygisk/remove`）。

> 同一个 APK 直接安装时仍然是普通的 WCX（走 LSPosed/libxposed）；刷成模块时则不安装 APK，
> 由模块自带注入器注入微信。两种形态建议二选一，避免重复 hook。

---

## 4. 运行时流程

| 阶段 | 进程上下文 | 做什么 |
|---|---|---|
| `preAppSpecialize` | 刚 fork，仍是 zygote 特权 | 只看 `com.tencent.mm` 主进程，其它进程 `DLCLOSE_MODULE_LIBRARY`；用 `Api::getModuleDir()` 的 fd 读 `payload/classes*.dex` **或模块根目录的 `classes*.dex`**（一体包布局）；`openat` + `android_dlopen_ext(USE_LIBRARY_FD)` 加载 `zygisk/liblsplant.so` 与 `lib/<abi>/*.so` |
| `postAppSpecialize` | 已在 sandbox（`untrusted_app`） | `InMemoryDexClassLoader` 从内存建 ClassLoader；`RegisterNatives` 注册 hook 桥；调用 payload 的 `ZygiskInjectEntry.entry(...)` |
| payload 内 | 微信进程 | `HiddenApiBypass` 豁免隐藏 API → LSPlant `Init` → hook `Instrumentation.callApplicationOnCreate` → `StartupAgent.startup(...)` |

为什么 dex/.so 都在 pre 阶段取：sandbox 内读不到 `/data/adb/**`（`0700 root`），
pre 阶段还是 zygote 特权，这是唯一能直读模块目录的窗口。dex 之后一直留在内存里，不再落盘。

## 5. 关键设计取舍

- **模块 .so 对 LSPlant 零链接期依赖**
  Zygisk 用 `android_dlopen_ext(fd)` 加载 `zygisk/<abi>.so`，运行时搜索路径里没有模块目录，
  带 `DT_NEEDED=liblsplant.so` 会直接加载失败。因此 `lsplant.hpp` 只是声明，
  `liblsplant.so` 在 pre 阶段 `dlopen(RTLD_GLOBAL)`，之后用 `dlsym` 拿 5 个入口函数指针
  （符号名可用 `nm -D liblsplant.so | grep _ZN7lsplant2v2` 核对）。
- **libc++ 静态化（`ANDROID_STL=none`）**
  设备上没有 `libc++_shared.so`，模块 .so 必须自包含：`org.lsposed.libcxx:libcxx` 提供静态 libc++，
  `io.github.vvb2060.ndk:dobby` 提供静态 Dobby（LSPlant 的 inline hook 后端）。
- **hook 桥只需要一个 hooker 类**
  LSPlant 6.x 支持「分派方法签名与目标不一致」，统一用 `NativeHooker.callback(Object[])`，
  不必按「返回值 × 参数类型」生成几十个类；多回调按 priority 挂在 Kotlin 侧。
- **原生库绑定**
  不装 APK 时 `System.loadLibrary` 一定失败（库不在 `nativeLibraryDir` 里），
  所以 `ILoaderService.loadNativeLibrary()` 在 Zygisk 入口下改成「反射列出 native 方法 → 拼 JNI 符号名 →
  `RegisterNatives`」。
- **入口符号可见性**
  模块整体编译带 `-fvisibility=hidden`，`zygisk_module_entry` 必须显式
  `__attribute__((visibility("default")))`，否则 Zygisk 的 `dlsym` 找不到它（早期 spike 就栽在这）。

---

## 6. 里程碑

| 编号 | 内容 | 状态 |
|---|---|---|
| S1 | `zygisk/*.so` 只在目标进程加载，其它进程零影响 | ✅ |
| S2 | 模块目录在 pre 阶段直读 dex / 原生库 | ✅ |
| S3 | 内存 dex 建 ClassLoader 并跑通 `StartupAgent` | 🧪 待真机验证 |
| S4 | LSPlant hook 桥（hook/unhook/deoptimize/invokeOriginal） | 🧪 待真机验证 |
| S5 | feature 层从 `de.robv.android.xposed` 迁移到 `IHookBridge` | ❌ 未开始（58 个文件；当前 Zygisk 模式下各 feature 会自己失败，不影响微信） |
| S6 | 资源 / 设置入口（不装 APK 没有 Activity 入口） | ❌ 未开始（WebUI 或 ActivityProxy 借壳） |
| S7 | 崩溃自愈（连续启动失败自动写 `disable`） | ❌ 未开始（落在 `service.sh`） |

> S5 之前的实际效果：注入链路、hook 引擎、模块启动日志都正常；
> 但各 feature 调用的 `de.robv` API 不存在，会逐个抛 `NoClassDefFoundError`
> （被 `FeaturesLoader` 的 runCatching 兜住，微信不会崩）。

## 7. 排错

| 现象 | 排查 |
|---|---|
| logcat 里没有 `WCXZygisk` | Zygisk 没开 / 模块没启用 / 进程名不是 `com.tencent.mm`（只注入主进程） |
| 刷入后 APK 装不上（一体包） | 重打包后签名丢了：检查构建日志里 `已用 ... 重签` 是否存在（CI 需要 keystore secrets） |
| `liblsplant.so 未预加载` | `zygisk/liblsplant.so` 没随包下发，或模块目录 SELinux 上下文不对（重刷一次） |
| `libart.so 符号解析失败` | ROM 的 libart 被裁剪/混淆，需要更新 LSPlant 版本或改符号前缀解析策略 |
| `lsplant::Init 失败` | ART 版本超出 LSPlant 支持范围，日志里带 Android 版本 |
| `RegisterNatives(NativeHookBridge) 失败` | payload dex 被 R8 改名了，检查 `app/proguard-rules.pro` 里的 keep 规则 |
| 微信加载原生库失败（一体包） | `zipalign` 没跑或没用 `-P 16`，`.so` 未页对齐 |
