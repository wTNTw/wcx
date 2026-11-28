# WCX Zygisk：独立 Java hook 后端验证

**结论：未找到符合首版约束、且可替代当前 Xposed 运行时的后端。** 公共 Zygisk 回调已在 KernelSU 测试机跑通，但这一结果不覆盖 Java 方法拦截、WCX 启动或 Magisk 验收。

## 候选对比

| 候选 | 已核实 | 决定 |
| --- | --- | --- |
| [公共 Zygisk API v5](https://github.com/topjohnwu/zygisk-module-sample/blob/8ce26128f81baaed0b969aaf7f52f886b61af4ab/module/jni/zygisk.hpp) | 提供生命周期回调及 JNI/PLT 拦截，没有任意 Java 方法与构造器的 hook 接口。 | 保留为 loader 的模块入口，不当作 `IHookBridge` 后端。 |
| [Android ART TI / JVMTI](https://source.android.com/docs/core/runtime/art-ti) | 官方仅支持附加到应用开发者启用调试的进程。ARM64 / Android 16 实机对照验证相符。 | 只用于自有可调试应用，不能作为 WCX 正式版的后端。 |
| [LSPlant](https://github.com/LSPosed/LSPlant/blob/3a240637e621677457c202150100d3ebcab924c6/README.md) | LGPL-3.0；公开接口记录了方法 hook、解除和去优化，初始化仍需调用方提供原生 inline hook 和 ART 内部符号解析。 | 未集成。已在自有非调试 APK 实测 6.4 的初始化边界；在不提供私有符号解析和代码改写的情况下，未通过启动前提检查（见下）。 |

## ART TI 真机对照

两个临时自有 APK 在 Debian 上用同一 Java 源码、包名和临时证书构建，先装 `android:debuggable=false` 版，再覆盖安装 `android:debuggable=true` 版。测试在各自进程中查找 `XposedBridge`，然后用公开的 `Debug.attachJvmtiAgent` 尝试加载自身私有目录中一个**刻意不存在**的文件。不附加到其他应用，不提供真实 agent。

```text
debuggable=false xposed_bridge=absent attach_result=security_exception
debuggable=true  xposed_bridge=absent attach_result=io_exception
```

前者被策略拒绝；后者没有触发同一策略错误，但仍因 `IOException` 未能加载 agent。这不是 Java hook 成功的证据。两个 APK 的签名和 SHA-256 已校验，测试结束后应用已卸载。没有重启、改动 root 模块或调整微信作用域。

## 自有可调试 APK 的正向验证

[`zygisk/independent-hook-probe/`](../../../zygisk/independent-hook-probe/README.md) 在 Debian 构建，在同一 ARM64 / Android 16 设备上验证了真实 ART TI 回调：测试进程不可见 `XposedBridge`，构造器和方法进入/退出可观测，异常不被吞掉；修改自有方法的参数后结果从 `10` 变为 `15`，关闭订阅后恢复 `10` 且回调计数不再增加。不使用 Zygisk 或私有 ART 接口；没有改动现有模块，测试 APK 已卸载。正向结果只适用于明确启用调试的自有 APK，不改变上面的正式版限制。

## 仍需满足的 WCX 启动条件

1. `8421818` 后，`UnifiedEntryPoint` 的最早两个生命周期回调已改用显式传入的 `IHookBridge`。`WeLauncher` 的资源 Hook 和资源缺失兜底仍依赖 Xposed，不能仅靠替换入口就启动全部功能。仅有 `compileOnly` 的 API JAR 不提供运行时实现。
2. `NativeLoader`、资源访问和设置页入口都要独立验证。Zygisk 路径不得运行现有 Xposed 专属的隐藏 API 豁免、保护目录删除或反检测逻辑，不改普通 APK 行为。
3. 后端须先在自有测试应用验证方法与构造器、before/after 及优先级、参数和结果变更、异常、原始调用与解除。未满足前不生成或标记可用于 WCX 的 payload/ZIP。
4. Windows ADB 当前仅有 ARM64 / Android 16 的 KernelSU 设备，未发现 Magisk CLI。Magisk 验收保持待办；不能把 KernelSU 结果作为 Magisk 的通过证据。

## 补充筛查：发布态的替代方案

| 候选 | 结果 |
| --- | --- |
| [Android `profileable`](https://developer.android.com/guide/topics/manifest/profileable-element) | 允许发布包开放本地性能分析，不等于允许附加 JVMTI agent。用自有 APK 实测：Manifest 同时为 `android:debuggable=false` 和 `<profileable android:shell="true" />`，Android 16 上公开附加接口仍返回 `SecurityException`。APK 在 Debian 构建并核验 Manifest、签名与 SHA-256，没有打包 agent；测试结束已卸载。 |
| [Pine](https://github.com/canyie/pine/blob/216d910f18b18430a5d21c510affb221a9833a55/README.md) | 文档所列支持上限为 Android 15 Beta 4，且默认关闭隐藏 API 限制。未找到其在保留安全检查的情况下支持该 Android 16 发布环境的验证，因此未引入。 |
| [SandHook](https://github.com/asLody/SandHook/tree/16a59fa0eef011ca202814311b362a8a3bcda412) / [Epic](https://github.com/tiann/epic/blob/c67d0d3e3769c19119465784a579c659dd88c4ce/README.md) | 已公布的支持均只到 Android 11，不能作为当前 Android 16 验收的后端。 |
| [AGP 构建期插桩](https://developer.android.com/reference/tools/gradle-api/8.13/com/android/build/api/variant/Instrumentation) 或宿主显式插件接口 | 适合自有、可重新构建或主动开放接口的宿主；不能直接拦截未修改的微信发布 APK 的任意 Java 方法。 |

本轮没有确认新的正式版候选。没有为获取 Hook 能力修改微信 APK、系统策略或现有模块，不以测试探针作为正式发布 ZIP。

## LSPlant 6.4：自有非调试 APK 实机诊断

- Debian 使用 Maven Central 的 `org.lsposed.lsplant:lsplant-standalone:6.4`，AAR SHA-256 为 `5ffc352d937968397db86cabf074039c305014863a130015eaa70b056b080406`，对应[上游源码标签](https://github.com/LSPosed/LSPlant/tree/v6.4)。构建并核验了签名、哈希和 Manifest `android:debuggable=false`。
- 在 ARM64 / Android 16 / KernelSU 手机上以普通应用 UID 运行，`XposedBridge` 不可见，SELinux 保持 `Enforcing`。只用普通应用动态加载接口检查 `libart.so` 可见性，没有扫描或跨越加载命名空间。调用 `lsplant::Init` 时显式拒绝原生符号解析和代码改写；没有授予隐藏 API 豁免、标记受信任 DEX、装载 root 模块或编辑系统设置。
- 普通动态加载返回 `library "libart.so" not found`（指该进程的正常加载路径，不是设备不存在 ART）。LSPlant 到达了原生解析阶段，返回 `Failed to find GetMethodShorty`、`Failed to init art method`；探针记录 `init=false stopped_by_probe=true resolver_requested=true code_change_requests=0`。这**不是**隐藏字段访问被系统拒绝，也不证明 LSPlant 在提供完整解析与安装器时必然失败；它证明本次限定路径没能完成初始化，没有执行 Hook、原方法调用或解除测试。
- 临时 APK 已卸载；未改动微信或现有模块。该结果不构成 LSPlant 后端通过或 WCX 正式版验收。

## WeKit 桥接的无绕过接入检查

- 本地 `bootstrap-release.apk` 显示版本 `git+454e8fd6`（SHA-256 `41d6ed2234d74a2284c70a481a50a178cc06b42e7131cef1130f9b61170208a5`）。在 WeKit、WeKite、WCX 的公开仓库未找到与它匹配的源码提交；搜索到的同前缀提交属于不相关仓库。不能用其他版本源码替代该二进制的审计。
- [WeKit 公开源码 `b51532b`](https://github.com/Ujhhgtg/WeKit/blob/b51532bf3427348af824d7d704e55081fac9ca55/wekit-zygisk/native/src/lifecycle.rs#L223-L251) 的原生启动要求 LSPlant 初始化及模块 DEX 信任两项都成功，否则在调用 Java `ZygiskEntry.init` 前直接退出。只关库隐藏不会改变这个前置条件。该版本调用的 LSPlant 也明确记录了隐藏 API 访问前提。
- [WeKite `19bffe4`](https://github.com/SymonChu/WeKite/blob/19bffe4c98c8ccd966de47a4ebd059dc2a6286d8/app/src/main/java/com/github/wekite/loader/entry/zygisk/ArtHookBridge.kt#L461) 则在生成 Java Hook 桥接时把 DEX 信任成功作为断言。这两个可审查版本并不是关闭相关路径后仍能直接工作的候选。
- 本轮为静态前置检查。未修改候选二进制、未构建正式 WCX Zygisk ZIP，也未在手机安装引导包、探针或 LSPosed 版 APK。只有可审查候选先满足无绕过条件，才能进行仅 Zygisk 的 WCX 安装验收。

## KernelSU 正式版验收状态

- **设备就绪，正式版阻塞。** 只读复核为 ARM64、Android 16（API 36）、KernelSU，微信版本为 8.0.80。Zygisk 实现已启用，临时探针 APK 和临时 Zygisk 模块均已卸载。未安装正式产物，未改动手机配置。当前微信 8.0.80 也在仓库文档所列的 8.0.69–8.0.79 验收范围之外，需单独验证适配，不能直接计为已支持版本。
- **已有证据**：日志探针验证了 KernelSU 环境中的公共 Zygisk 回调与卸载回退；ART TI 探针验证了自有可调试 APK 的独立 Hook。两项都不能证明正式 WCX 启动。
- **验收前提**：后端须在非调试发布环境下按既定安全边界工作，并与 WCX 的完整启动路径兼容。当前没有通过这个前提的后端或正式 ZIP，不进入正式版安装、设置页启动和卸载回归测试。

## 附：上游 WeKit 运行时源码审计（`b51532b`）

范围由用户确认扩大为「完整移植 WeKit Zygisk 运行时 + 全部微信子进程 + `so_hider`」，排除 Telegram root companion 与数据库访问。逐文件清单、许可证、行为与排除项见 [`zygisk/backend/README.md`](../../../zygisk/backend/README.md)。

**审计事实（仅源码阅读）：**

- 上游 `native/Cargo.toml` 使用宽松版本声明且该提交无 `Cargo.lock`；LSPlant 为 LGPL-3.0 并静态链接。导入前需锁定依赖并确认分发义务。
- `art::init` 解析 `libart.so` 私有符号后调用 LSPlant；`INITIALIZED`/`ART_SYMBOLS` 为 `OnceLock`，初始化失败在本进程内不可重试。
- `art::trust_class_loader` 通过反射读取 `DexFile.mCookie` 后调用 `MakeDexFileTrusted`，任一 DexFile 失败即整体失败。
- `so_hider` 用裸库名子串匹配（`libdexkit.so`、`libwekit_native.so`、`libmmkv.so`）并以 `MAP_FIXED` 覆盖映射；同提交的 `art/inline_hook/memory.rs` 明确只用 `MAP_FIXED_NOREPLACE`，两者策略不一致。
- 上游共享启动路径仍调用 `HiddenApiBypass.setHiddenApiExemptions("L")` 并删除 `app_qqprotect`，Zygisk 路径同样执行。

**状态：** 上述均为静态结论。本机无 Rust/NDK/CMake，未编译 runtime，未运行重定位测试，未做任何真机验证。此前的「未找到符合首版无绕过约束的后端」结论因范围调整而失效，但**新的验收结论仍未产生**。

## 附：KernelSU 真机运行结果（2026-11-27，失败）

**环境**：OnePlus PJZ110，Android 16（API 36），`arm64-v8a`，KernelSU + Zygisksu，微信 `com.tencent.mm` **8.0.80**（超出仓库文档的 8.0.69–8.0.79 支持范围）。已装 `wekite_zygisk` 但目标列表为空；WCX 未以 APK 形式安装，故 LSPosed 侧不存在双重注入。

**安装与默认行为（通过）**

- `ksud module install wcx-zygisk-kernelsu.zip` 成功：模块暂存到 `modules_update`，重启后 `zygisk/arm64-v8a.so`（766 976 B）与 `module.apk`（44 892 894 B）就位。
- 目标为空时启动微信：主进程、`:push`、`:appbrand0/1` 正常运行，logcat 中无任何 `WcxZygisk` 记录 → 未注入，fail-closed 生效。

**目标启用与注入（失败）**

`config.sh refresh` → 发现 `0 com.tencent.mm 0`；`set 0 com.tencent.mm 1` 后强停并重启微信，进程在 attach 阶段死亡：

```text
W ActivityManager: Process ProcessRecord{... 25330:com.tencent.mm/u0a514} failed to attach
I ActivityManager: Killing 25330:com.tencent.mm/u0a514 (adj -10000): start timeout
```

`/data/tombstones/tombstone_02`、`tombstone_03`（同一时刻两次，均为 `usap64`，uid 10514）：

```text
signal 4 (SIGILL), code 1 (ILL_ILLOPC), fault addr 0x000000737fa01064
#00 pc 0x401064  libart.so (art::ClassLinker::VisiblyInitializedCallback::MarkVisiblyInitialized(art::Thread*)+20)
#01 pc 0x0903e4  <anonymous:2ef0373000>
#02 pc 0x15d63c  <anonymous:2ef05ed000>
#03 pc 0x7b9f58  libart.so (art::ClassLinker::MakeInitializedClassesVisiblyInitialized(art::Thread*, bool)+148)
```

`#01`/`#02` 是匿名可执行段，即 LSPlant 生成的桥与 inline-hook 重定位代码。崩溃发生在 app zygote（`usap64`）执行 ART 类初始化路径时，属于导入的 ARM64 inline-hook 后端与该设备 ART 构建不兼容，而不是伴随脚本或目标判定问题。

**回退（通过）**：`config.sh set 0 com.tencent.mm 0` 后强停并重启微信，主进程与 `:push`、`:appbrand*` 全部恢复，无需重启设备。模块仍处于安装但目标关闭状态。

**结论**：构建与静态校验通过，但**真机运行不可用**。在修复 ART inline-hook 与该 ART 构建的兼容性（或换用其他 LSPlant/重定位实现）并复测之前，本分支不应产出可发布 ZIP。证据文件保存在设备外的构建工作区（`tombstone_02`、`tombstone_03`）。

## 附：真机联调记录（2026-11-27 晚，KernelSU）

环境：OnePlus PJZ110、Android 16（API 36）、KernelSU + Zygisksu、微信 `com.tencent.mm` 8.0.80。

### 已打通的链路

1. `ksud module install` → 暂存 → 重启后 loader 与 payload 就位。
2. 默认（无目标）不注入：微信主进程与子进程正常运行，无任何 loader 日志。
3. `config.sh refresh/list/set` 正常；新目标默认关闭。
4. 启用目标后：`preAppSpecialize OK for com.tencent.mm`，以及 `:push`、`:appbrand0`、`:appbrand1` 均被选中 —— 目标匹配与子进程覆盖符合设计。
5. payload 按内容哈希发布到 app 私有目录，`InMemoryDexClassLoader` 建立成功。
6. LSPlant 初始化成功（6 个 ART inline hook，`patch_len=8`，入口原本干净），模块 DEX trust 成功。
7. `ZygiskEntry.init` 运行，hook 住 `AppComponentFactory.instantiateClassLoader`，拿到微信真实 `PathClassLoader`，交给 `ModuleLoader`。

### 本轮修掉的真实缺陷

| 缺陷 | 现象 | 修复 |
| --- | --- | --- |
| `config.sh` 用 `awk 'NR==FNR'` 区分两个输入 | 全新安装时状态文件为空，扫描行被当成第一个文件，目标列表永远为空 | 改用 `FILENAME` 判断 |
| `adb push` 覆盖模块文件后 SELinux 标签变成 `adb_data_file` | 微信进程 `cannot open module.apk: Permission denied` | `chcon u:object_r:system_file:s0` + 0644 |
| `HybridClassLoader` 用 `loadClass` 回查 payload loader，而该 loader 的 parent 已被指向 Hybrid | 主线程无限递归类加载，进程 114% CPU 挂死 | 反射调用 `ClassLoader.findClass`（不经父委派）+ 线程内重入保护 |
| `so_hider` 只比对 `/data/user/0/<pkg>` | 实际映射路径是 `/data/data/<pkg>`，一个段都没命中 | 同时接受规范路径别名 |
| 外来 entry trampoline 被当作普通序言搬迁 | `SIGILL` 落在对方跳板的字面量里 | 检测 `ldr xN,#imm; br xN` 跳板并拒绝 hook |

### 仍未通过的一步

启动推进到 `WeLauncher` 之前即失败：

```text
E WCX: ModuleLoader: StartupAgent failed
E WCX: java.lang.NoClassDefFoundError: Failed resolution of: Lde/robv/android/xposed/XC_MethodHook;
```

WCX 的启动与功能代码仍直接引用 `de.robv.android.xposed.*`（`app/libs/xposed-api-82.jar` 只是 `compileOnly`）。Zygisk 进程里没有该框架，因此需要一层运行期兼容实现（`XposedBridge` / `XC_MethodHook` / `XposedHelpers` 等映射到 `IHookBridge`），或把这些调用点改为走 `IHookBridge`。这是下一步的工作量主体。

### 兼容性约束（实测）

微信进程里同时存在其他 ART hooker 时，WCX 的 entry 补丁会被后来者按自己的跳板长度覆盖，重入点落进对方字面量 → `SIGILL`。本轮复现过的注入者：

- LSPosed：两个启用且作用域含微信的模块（取消微信作用域后消失）
- `FunBox For Zygisk`（`/data/adb/funbox/enablewx` 存在即注入微信）
- `zygisk-module-xfingerprint-pay-all`（`scope.applied` 中 `wechat=keep`）

因此 WCX Zygisk 要求**同一微信实例上只有一个 ART 注入器**；这与设计里「不要同时开启 LSPosed 版」一致，但范围更大：其他模块的注入同样会触发冲突。

### 环境状态备注

联调期间出现过两次与 WCX 无关的 `zygote64` 崩溃（`selinux_android_setcontext(... "oplusgame_app" ...)` 失败，`com.oplus.cosa`）。zygote 重启后 KernelSU 不再重新注入，表现为「本 boot 所有模块的 loader 均无日志」。排查注入类问题时需先确认 zygote 是否发生过重启。

## 附：Xposed 兼容层落地后的真机结果（成功）

上一节记录的 `NoClassDefFoundError: Lde/robv/android/xposed/XC_MethodHook` 已通过**运行期兼容层**解决：`app/src/zygisk/java/de/robv/android/xposed/` 提供与 `xposed-api-82.jar` 二进制兼容的 `XposedBridge` / `XC_MethodHook` / `XC_MethodReplacement` / `XposedHelpers` / `XCallback` / `XC_LoadPackage` 等，全部转发到 `IHookBridge`。该目录只在 `-PwcxZygisk=true` 时进入 payload，普通 APK 不受影响。

兼容层用 **Java** 编写，而不是 Kotlin：Kotlin 源码会遮蔽 compileOnly jar 里的同名 Java 类，导致 `param.result` 这类属性语法失效（`Unresolved reference 'result'`），而 Java 影子类保持了与 jar 一致的互操作规则。

### 实测（OnePlus PJZ110 / Android 16 / KernelSU / 微信 8.0.80）

```text
WcxZygisk: preAppSpecialize OK for com.tencent.mm[:push|:appbrand0|:appbrand1]
WcxZygisk: LSPlant initialized
WcxZygisk: trusted 1 DexFile(s) for class loader ...
ZygiskEntry: hooked AppComponentFactory.instantiateClassLoader on android.app.AppComponentFactory
UnifiedEntryPoint: hooking Application.attachBaseContext
UnifiedEntryPoint: Application.attachBaseContext invoked, hooking Instrumentation.callApplicationOnCreate
StartupAgent: hid loaded module libraries
FeaturesLoader: enabling all hook items took 68.663802ms
```

- 微信主进程与 `:push`、`:appbrand0`、`:appbrand1` 四个进程同时注入并正常运行（`do_epoll_wait`，无死转）。
- WCX 功能生效：`FeaturesLoader` 完成启用；例如 `SkipRewardedAds` 实际改写 `WAGameAd.js` / `WAAppAd.js` 并收到 bridge 事件。
- `so_hider` 生效：`remapped 9 segment(s)`，随后 `/proc/<pid>/maps` 中 `wcx-native` 路径计数为 **0**。
- 无 `E WCX` 级错误；微信界面正常（截图确认会话列表与「我」页面）。
- 普通变体回归：`standard` 仍含 `assets/xposed_init` + 3 个 `META-INF/xposed/*`，`legacy` 仍含 `assets/xposed_init`，两者都不含 Zygisk 代码。

### 仍未观察到的部分

- WCX 在微信内的**设置入口**没有出现在「我」或「设置」页面（截图核对）。启动链路与功能 hook 均正常，因此问题在 UI 入口注入这一段，需要单独排查（可能与 `HostInfo.isModule` 判定有关：payload 未以 APK 形式安装）。
- 未验证：hook 解绑/热重载、更新 payload 后的行为、子进程内的功能完整性、停用/卸载后的回退。

### 结论

Zygisk 部署在当前设备上**可以运行**，前提是同一微信实例只有一个 ART 注入器。剩余待办是设置入口与更细的功能回归，不再是「无法启动」。

## 附：设置入口（Zygisk 专用常驻入口）

排查结论：WCX 在微信内的设置入口来自 `features/items/home_screen_menu/ModuleSettings`（首页右上角「+」菜单里的「WCX」项），它是 `SwitchFeature`，`defaultEnabled = false`。正常（APK）部署下用户可以从应用图标打开设置再开启它；**Zygisk payload 没有应用图标**，于是形成死锁：进不去设置 → 无法开启入口 → 更进不去设置。

处理：新增 `app/src/zygisk/java/com/Johnny/wcx/loader/entry/zygisk/ZygiskMenuEntry.kt`，作为 `ApiFeature`（始终启用）向 `WeHomeScreenPopupMenuApi` 注册一个常驻「WCX」菜单项，点击调用 `WeSettingsInjector.openSettingsDialog`。该文件只在 `-PwcxZygisk=true` 时进入 payload；当用户以后手动开启原 `ModuleSettings` 时，新入口让位（`isActive` 时返回空列表），不会出现两个同名项。

状态：代码完成、payload 构建通过（payload 内含新入口标记）。**端到端验证未完成** —— 需要一次 zygote 未崩溃的干净启动（见上节），当时设备进入异常状态，微信的 MAIN/LAUNCHER intent 无法解析（`am start` 返回 `Error type 3`），故未能确认「+」菜单里出现该项。

设备侧排查记录：在 `touch /data/adb/modules/wcx_zygisk/disable` 停用本模块后重启，微信依旧 `No activity found`，说明该现象与本模块无关；同期 `logcat -b crash` 有 `init` 的 `SIGABRT` 记录，`screencap` 返回全黑帧（屏幕状态报告为 ON）。这些问题超出本模块范围，需要单独恢复设备。

## 附：设置入口结论修正（实测通过）

上节「设置入口未出现」的判断是**错误的**：当时截图时 `WeSettingsInjector` 的 DEX 委托尚未解析完成（该 feature 是 `ApiFeature` + `IResolveDex`，`FeaturesLoader` 允许它在缓存不完整时先启动）。缓存解析完成后入口正常注入。

实测（WCX 注入状态、微信 8.0.80）：

```text
微信「我」→ 设置 → 顶部新增分组「插件」→「WCX 设置」
点击后打开 WCX 自身设置页：模块已激活 v348 (348)、101 已启用 / 309 全部功能 / 22 分类、
模块自检、脚本插件，底部 主页/功能/日志/设置 四个 Tab 均可渲染
```

入口来自 `features/api/ui/WeSettingsInjector`（`@Feature(name = "设置模块入口")`），它同时提供三条路径：hook `LauncherUI`、legacy `SettingsUI.initView`、modern `SettingGroup*`，另有在聊天输入框键入 `#wcx` 直接打开的快捷通道。

因此**不需要额外入口**：先前为「payload 无应用图标」新增的 `ZygiskMenuEntry`（首页「+」菜单项）已删除，`WeHomeScreenPopupMenuApi` 里的临时诊断日志也已移除。Zygisk 部署与普通 APK 部署在入口行为上保持一致。

## 附：收尾构建与回归（清理后）

删除多余的首页「+」菜单入口与诊断日志后重新构建验证：

| 项目 | 结果 |
| --- | --- |
| payload（`-PwcxZygisk=true`，legacy，arm64-v8a） | 构建通过，SHA-256 `2cd65e19…eed672`，不含 `ZygiskMenuEntry` |
| 真机设置入口 | 微信「设置 → 插件 → WCX 设置」正常显示并可打开 WCX 设置页（模块已激活 v348、101 已启用） |
| 普通 `standard` 回归 | 含 `assets/xposed_init` + 3 个 `META-INF/xposed/*`，Zygisk 代码引用 0 |
| 普通 `legacy` 回归 | 含 `assets/xposed_init`，Zygisk 代码引用 0 |
| 三份管理器 ZIP（重新打包 + 校验） | magisk `92c1fcaf…`、kernelsu `1b49d990…`、apatch `cd3271bf…`；loader `1bc59684…` |

结论：Zygisk 部署在真机上可用（注入、功能、so_hider、设置入口均已实测），入口行为与普通 APK 部署一致，不需要额外代码。仍待完成：hook 解绑/热重载与卸载回退的真机回归、正式签名出包。

## 附：设备侧回归结论（停用 / 卸载 / 重装）

在 OnePlus PJZ110（Android 16 / KernelSU / 微信 8.0.80）上逐项验证：

| 场景 | 操作 | 结果 |
| --- | --- | --- |
| 停用 | `touch /data/adb/modules/wcx_zygisk/disable` + 重启 | 微信 5 个进程正常；`WcxZygisk` 日志 0 条、`WCX` 日志 0 条 → 完全不注入 |
| 卸载 | `ksud module uninstall wcx_zygisk` + 重启 | 模块目录移除；微信 4 个进程正常；`WcxZygisk` 0 条；`memfd:wcx` 映射 0 → 回到未加载状态 |
| 全新安装 | `ksud module install wcx-zygisk-kernelsu.zip` + 重启 | loader/payload 就位；`config.sh refresh` 扫出 `0 com.tencent.mm 0`（默认关闭）；`set … 1` 后 6 个进程全部注入 |

回归中发现并修复的两个缺陷：

1. **`uninstall.sh` 清理不干净**：临时文件实际叫 `.scan.$$` / `.enabled.$$` / `.merged.$$`，脚本里写的是 `.injection-targets.*`，导致中断残留的文件留在 `/data/adb/wcx_zygisk` 里、`rmdir` 失败。修正后再次卸载，状态目录被完整删除。
2. **`so_hider` 遇到 `" (deleted)"` 标记失效**：被 unlink 后仍映射的文件，内核会在 maps 路径后追加 `" (deleted)"`，精确文件名匹配因而失效，该次启动 9 个段一个都没隐藏。修正为比对前剥离该后缀，复测 `remapped 9 segment(s)`、主进程 maps 中 `wcx-native` 路径数为 0。个别段仍可能重映射失败（本轮出现一次 `libdexkit.so`），此时只记录该段并返回不完整结果，不影响其余段。

未覆盖：`hook` 解绑/热重载。Zygisk 部署没有 libxposed 的 API102 热重载通道，`ArtHookBridge` 的解绑语义由 `ArtHookBridgeRuntime` 保证（保留 LSPlant hook、移除回调后直接走备份），没有对应的外部触发入口可做黑盒验证。

## 附录：产物签名状态

本轮所有 payload 与三份管理器 ZIP 均使用**测试密钥**签名（`CN=WCX Test Build, OU=Not a release key`，SHA-256 `a2e07858e00376fb6454f08408b4bc699a751da2d3e31044fc2a1a5a37efee84`）。构建机上没有任何项目签名材料，因此未做正式签名。

发布前必须用项目固定 keystore 重新构建 payload 并重打包，命令与环境变量见 `zygisk/README.md` 的「签名」一节；打包器的签名校验只验证可解析性，不验证签名者身份，需另行比对证书指纹。
