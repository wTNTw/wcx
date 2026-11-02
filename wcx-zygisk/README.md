# Zygisk - WCX（独立注入器）

刷一个 zip 就能让 WCX 注入微信，**不需要 LSPosed**。注入器移植自上游 **Ujhhgtg/WeKit**
（同为 GPL-3.0），并针对本仓库与 Android 16 做了修正。

模块 id `zygisk-wcx`，显示名 `Zygisk - WCX`；安装后位于 `/data/adb/modules/zygisk-wcx`。

## 产物

`./x build --release`（或 `./gradlew assembleRelease`）后额外产出：

```
app/build/outputs/zygisk/wcx-<版本>-arm64-v8a.apk   # 可安装 APK + 可刷模块包（同一份字节）
```

只产出这**一个** APK：它本身就是模块包，不再额外生成 `.zip` 副本。

刷入方式：把 `.apk` 改名成 `.zip`，在 Magisk / KernelSU / APatch 刷入。
`customize.sh` 用 `SKIPUNZIP=1`，只做三件事：

1. 把**整包自身**复制成 `$MODPATH/module.apk`（注入器在目标进程里读它，dex + 资源都在里面）
2. 把 APK 内的 `lib/arm64-v8a/libwcx_zygisk.so` 搬到 `$MODPATH/zygisk/arm64-v8a.so`
3. 落地 `module.prop` / `uninstall.sh`

> 注意：`zygisk/` 目录里只能有 `<abi>.so` 一个文件。Zygisk(Next) 会把这个目录里的
> `.so` 都当候选模块库，多放一个没有 `zygisk_module_entry` 的库会导致整个模块被静默跳过。

## 运行时链路

```
preAppSpecialize（zygote 特权）
  ├─ 目标判定：默认 com.tencent.mm；可用 <模块目录>/target.list 按 (userId, 包名) 精确控制（双开/分身）
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
| 上游用 root companion + WebUI 授权 | 未配置时永远不注入，判定发生在 native 与 root 进程之间 | 作用域/总开关都落到模块目录里的文件（`target.list` / `disable`），注入器自己在 pre-specialize 读，改完**立即生效**、不依赖 companion；WebUI 仍然保留（见下） |

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

## WebUI：只选注入作用域

模块自带 `webroot/`（KernelSU / KernelSU Next / APatch 的 WebUI 会自动识别），
所有配置都在 WebUI 里完成。本模块只注入微信，因此**没有总开关**：
不想用时在 Root 管理器里把模块停用即可（会写 `disable`，注入器同样识别）。

状态都在模块目录里，注入器在 `preAppSpecialize` 直接读，改完对**下一个启动的微信进程**立即生效，无需重启设备：

| 文件 | 含义 |
|---|---|
| `target.list` | 每行 `<userId>\t<package>`（双开/分身靠 userId 区分）；兼容旧格式「只有包名」= 该包所有用户；**文件不存在** = 默认注入 `com.tencent.mm`（所有用户）；**文件存在但为空** = 一个都不注入 |
| `disable` | 存在 = 模块停用（Root 管理器写入，注入器直接识别） |

`config.sh` 用法（终端调试用，和 WebUI 等价）：

```bash
sh /data/adb/modules/zygisk-wcx/config.sh targets                    # <userId>\t<package>\t<0|1>
sh /data/adb/modules/zygisk-wcx/config.sh list                       # 当前作用域
sh /data/adb/modules/zygisk-wcx/config.sh set 0 com.tencent.mm 1     # 只开主用户
sh /data/adb/modules/zygisk-wcx/config.sh set 999 com.tencent.mm 0   # 关掉分身（userId=999）
sh /data/adb/modules/zygisk-wcx/config.sh set all com.tencent.mm 1   # 所有用户一起开
sh /data/adb/modules/zygisk-wcx/config.sh reset                      # 恢复默认（微信，所有用户）
```

> 模块升级时 `customize.sh` 会把现有的 `target.list` / `disable` 复制到新版本，作用域不会丢。

## 冷启动优化

| 优化 | 之前 | 现在 |
|---|---|---|
| APK 复用 | 每个进程都复制一次 35MB `module.apk` 并全量读 3 个 dex（≈33MB 堆） | 目标文件按 `(inode,size,mtime)` 命名，命中即**零拷贝复用**；顺带清理旧版本副本 |
| 类加载方式 | `InMemoryDexClassLoader`（dex 全量进内存 + DirectByteBuffer） | 优先 `DexClassLoader(路径, optimizedDirectory)`（ART 自己 mmap）；本 ROM 的**主进程**首次打开应用私有 APK 会拿到空 dexElements，此时自动回退 `InMemoryDexClassLoader` |
| 原生库解包 | 每个进程都从 APK 解出 ~4.5MB `.so` 再 `System.load` | 目标已存在且大小一致就跳过（目录名带 APK 版本标记） |

真机实测（Android 16 / 微信 8.0.79 / KernelSU + Zygisk Next，冷启动单进程）：

```
# 子进程：文件式加载
Zygisk: 冷启动耗时 publish=1ms loader=46ms(file) art=27ms total=75ms (com.tencent.mm:recovery3)
# 主进程：ART 首次打开私有 APK 失败 → 自动回退内存式
Zygisk: 文件式 dex 加载仍为空（试了 3 次），回退 InMemoryDexClassLoader
Zygisk: 冷启动耗时 publish=1ms loader=139ms(memory) art=25ms total=165ms (com.tencent.mm)
```

## 与 LSPosed / Xposed 共存

**两者不能同时注入同一个进程**：LSPlant 都要 hook ART 内部函数，第二套 hook 会踩坏第一套的
trampoline，表现为微信一启动就 SIGILL（实测崩在 `Class::SetStatus+20` /
`MarkVisiblyInitialized+236`）。

所以本模块在 `preAppSpecialize` 会检查 `/data/adb/modules/zygisk_lsposed`（以及 `riru_lsposed`）
是否处于启用状态（没有 `disable` / `remove`）：

```
Zygisk: 检测到 LSPosed (Zygisk) 已启用，跳过本模块以免双 hook 冲突（想强制启用：在模块目录放 force 文件）
```

- 想用本模块（脱离 LSPosed）：在 Root 管理器里**停用 LSPosed 模块** → 重启设备
- 想继续用 LSPosed 里的 WCX：保持 LSPosed 启用即可，本模块会自动让路
- 只想用 zygisk 形态、要绕过这个检查：`touch /data/adb/modules/zygisk-wcx/force`
  （⚠️ 与 LSPosed 同时存在时大概率崩微信，只用于排障）

## 已知问题 / 待办

- 个别 feature 与 WeChat 8.0.79 类名不匹配（例如 `com.tencent.mm.plugin.multitalk.ui.MultiTalkMainUI`
  已不存在），会持续刷 `NoClassDefFoundError`。属于 feature 维护问题，与注入链路无关。
- 功能设置 UI 仍依赖已安装的 WCX APK（Zygisk 模式下没有 Activity 入口）；模块级作用域/开关已由 WebUI 提供。
- 崩溃自愈（连续启动失败自动 `disable`）未实现。

## 与 master 分支的兼容性约定

Zygisk 形态**不依赖** master 分支里的具体 feature 代码：注入器只认下面这些「契约」，
只要它们不变，master 上怎么改功能都不会导致模块加载失败。

### 加载链路依赖的契约（改了才会「无法加载」）

| 契约 | 位置 | 说明 |
|---|---|---|
| 模块 zip 内有 `module.prop` / `customize.sh` / `uninstall.sh` | `wcx-zygisk/` | 安装器要用 |
| APK 内有 `lib/arm64-v8a/libwcx_zygisk.so` | `app/src/main/jniLibs/` + `xtask`/CI | 注入器本体，`customize.sh` 会搬到 `zygisk/<abi>.so` |
| 模块目录里有 `module.apk` | `customize.sh` 生成 | 注入器从它读 dex / 资源 |
| `module.apk` 里有 `classes*.dex` 和 `resources.arsc` | AGP 正常产物 | 缺了就是「payload 为空」 |
| 类名 `…loader.entry.zygisk.ZygiskEntry` 且 `static init(String,String,String)` | `ZygiskEntry.kt` | 原生按 FQCN + 签名反射调用；`app/proguard-rules.pro` 里已显式 keep |
| 类名 `…loader.entry.zygisk.ArtHookBridge` 的 4 个 native 方法名 | `ArtHookBridge.kt` + `natives.rs` | `RegisterNatives` 按名字注册 |
| 默认注入目标 `com.tencent.mm`（或 `target.list`） | `lifecycle.rs` | 只影响注入哪些进程 |

**维护提示**：上面这些改名字时，必须同步改
`wcx-zygisk/native/src/{natives,lifecycle}.rs` 里的字符串、`app/proguard-rules.pro` 的 keep 规则。

### 不会导致加载失败、但会影响功能的改动

| 改动 | 后果 |
|---|---|
| master 新增/删除 feature | 随 `module.apk` 一起生效，无需改注入器 |
| 新增 `jniLibs/*.so` | 需要在 `ZygiskNativePayload.kt` 的 `names` 列表里登记，否则该库不会被解出/加载 |
| 新增 feature 用 `de.robv.android.xposed.*`（`XposedBridge` / `XC_MethodHook`） | 编译能过（compileOnly），但 Zygisk 模式运行时会 `NoClassDefFoundError`（被 feature 隔离兜住，微信不崩）。**新代码请统一用 `IHookBridge`**（`hookBefore/hookAfter/hookDirectly`、`HookCallback`/`HookParam`） |
| WeChat 换版本、`com.tencent.mm.app.Application` / `MultiTalkMainUI` 之类类名变化 | 属于 WeChat 适配问题，LSPosed 形态同样会挂 |
| 模块 id / 显示名改动 | 只需改 `wcx-zygisk/module.prop`；运行期路径都由 `getModuleDir()` 取得，没有硬编码 |

### 验证方式

```bash
adb shell su -c 'cat /data/adb/modules/zygisk-wcx/module.prop'   # id/版本
adb shell su -c 'grep zygisk-wcx /data/adb/zygisksu/modules_info' # Zygisk 是否收录
adb logcat -s WCX | grep -E 'LSPlant initialized|module started|FeaturesLoader'
```
