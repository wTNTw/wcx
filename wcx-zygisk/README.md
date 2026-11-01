# wcx-zygisk（Zygisk 独立注入器）

刷一个 zip 就能让 WCX 注入微信，**不需要 LSPosed**。注入器移植自上游 **Ujhhgtg/WeKit**
（同为 GPL-3.0），并针对本仓库与 Android 16 做了修正。

## 产物

`./x build --release`（或 `./gradlew assembleRelease`）后额外产出：

```
app/build/outputs/zygisk/wcx-<版本>-arm64-v8a.apk   # 可安装 APK + 可刷模块包（同一份字节）
app/build/outputs/zygisk/wcx-<版本>-arm64-v8a.zip   # 上者副本，方便直接刷
```

刷入方式：把 `.apk` 改名成 `.zip`（或直接用产物里的 `.zip`）在 Magisk / KernelSU / APatch 刷入。
`customize.sh` 用 `SKIPUNZIP=1`，只做三件事：

1. 把**整包自身**复制成 `$MODPATH/module.apk`（注入器在目标进程里读它，dex + 资源都在里面）
2. 把 APK 内的 `lib/arm64-v8a/libwcx_zygisk.so` 搬到 `$MODPATH/zygisk/arm64-v8a.so`
3. 落地 `module.prop` / `uninstall.sh`

> 注意：`zygisk/` 目录里只能有 `<abi>.so` 一个文件。Zygisk(Next) 会把这个目录里的
> `.so` 都当候选模块库，多放一个没有 `zygisk_module_entry` 的库会导致整个模块被静默跳过。

## 运行时链路

```
preAppSpecialize（zygote 特权）
  ├─ 目标判定：默认 com.tencent.mm（可用 <模块目录>/target.list 覆盖，一行一个包名）
  ├─ get_module_dir() 拿到模块目录（不依赖 root companion）
postAppSpecialize（sandbox）
  ├─ openat(module.apk) → 读出 classes*.dex
  ├─ InMemoryDexClassLoader（parent = 系统 ClassLoader）
  ├─ 原生侧 lsplant::Init（Dobby 做 inline hook）+ MakeDexFileTrusted
  ├─ RegisterNatives（ZygiskEntry / ArtHookBridge）
  └─ ZygiskEntry.init(processName, dataDir, apkPath)
        ├─ LoadedApk.createAppFactory → AppComponentFactory.instantiateClassLoader
        ├─ 拿到**最终**宿主 ClassLoader
        └─ ModuleLoader.init → UnifiedEntryPoint → Application.attachBaseContext →
           Instrumentation.callApplicationOnCreate → StartupAgent → FeaturesLoader
```

## 与上游的两处关键差异（都已在真机验证）

| 问题 | 现象 | 处理 |
|---|---|---|
| 上游自研 Rust inline hook | Android 16 上对 `Class::SetStatus`、`VisiblyInitializedCallback::MarkVisiblyInitialized` 等 ART 函数重定位出错，进程启动即 SIGILL | 换成 **Dobby**（`third_party/dobby` 静态链入）作为 LSPlant 的 `inline_hooker`；并让符号解析跳过 `VisiblyInitializedCallback` 的两个可选 hook |
| 上游用 root companion + WebUI 授权 | 未配置时永远不注入，且需要 `config.sh`/`webroot` 一整套 | 改成读 `<模块目录>/target.list`，默认注入 `com.tencent.mm`（命中包名的所有进程，含子进程） |

## 依赖的固定版本

| 组件 | 版本 | 说明 |
|---|---|---|
| `third_party/lsplant` | `d8b5d1dbb664abc606644036822e4bb64547edf6` | 该提交不用 `import std;`、`find_package(cxx)` 仅在 `LSPLANT_STANDALONE=ON` 时触发 |
| LSPlant 内嵌 `dex_builder` | `ac7fb2230954ee311808bad469b0db501f31bfb8` | 需 `--init` 其 `external/parallel_hashmap` |
| `third_party/dobby` | LSPosed/Dobby master | inline hook 引擎（Android 16 可用） |
| `third_party/xz-embedded` | `ae63ae3a36ed01724674e8f3d750dc47bf125410` | module.apk 解压 |
| Android NDK | `30.0.14904198` | `ANDROID_STL=c++_static`，产物自包含 |

## 构建

```bash
git -c submodule.third_party/lsplant.update=checkout submodule update --init \
    third_party/lsplant third_party/xz-embedded third_party/dobby
git -C third_party/lsplant submodule update --init lsplant/src/main/jni/external/dex_builder
git -C third_party/lsplant/lsplant/src/main/jni/external/dex_builder \
    submodule update --init external/parallel_hashmap

export WCX_ANDROID_NDK=/path/to/ndk/30.0.14904198
export WCX_ANDROID_API=28
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER=\
"$WCX_ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android28-clang"

cargo build -p wcx-zygisk --target aarch64-linux-android --release
cp target/aarch64-linux-android/release/libwcx_zygisk.so \
   app/src/main/jniLibs/arm64-v8a/libwcx_zygisk.so
./gradlew packageZygiskApk        # 产物在 app/build/outputs/zygisk/
```

产物校验：`NEEDED` 只应有 `liblog/libz/libdl/libc`，`llvm-nm -D` 应导出 `zygisk_module_entry`。

## 真机验证记录（Android 16 / WeChat 8.0.79 / KernelSU + Zygisk Next）

```
Zygisk: com.tencent.mm 在注入名单内
Zygisk: preAppSpecialize OK for com.tencent.mm
Zygisk: LSPlant initialized
Zygisk: trusted 1 DexFile(s) for class loader 0x...
Zygisk: ZygiskEntry natives registered
ZygiskEntry: ZygiskEntry.init: process=com.tencent.mm apk=/data/user/0/com.tencent.mm/files/mmkv/.wcx-bootstrap-<hash>.apk
Zygisk: ArtHookBridge natives registered
ZygiskEntry: hooked AppComponentFactory.instantiateClassLoader on android.app.AppComponentFactory
ZygiskEntry: WCX module started with host ClassLoader=dalvik.system.PathClassLoader[...com.tencent.mm.../base.apk...]
FeaturesLoader: enabling all hook items took ~85ms
ProxyInstrumentation: callActivityOnCreate: com.tencent.mm.app.WeChatSplashActivity
HomeSidePanel: 左边缘右滑触摸条已挂载
```

## 已知问题 / 待办

- 个别 feature 与 WeChat 8.0.79 类名不匹配（例如 `com.tencent.mm.plugin.multitalk.ui.MultiTalkMainUI`
  已不存在），会持续刷 `NoClassDefFoundError`。属于 feature 维护问题，与注入链路无关。
- 设置 UI 仍依赖已安装的 WCX APK（Zygisk 模式下没有 Activity 入口），后续按需做 WebUI。
- 崩溃自愈（连续启动失败自动 `disable`）未实现。
