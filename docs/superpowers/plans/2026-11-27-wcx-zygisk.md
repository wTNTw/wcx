# WCX Zygisk Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 `zygisk` 分支产出可手动安装的 ARM64 Magisk ZIP，保留 WCX 普通 APK 的上游构建、签名与发布方式。

**Architecture:** `zygisk/` 维护原生 loader、安装模板和打包命令；`app` 仅在显式请求时新增 ZIP 专用 payload。先验证与 LSPosed 无关的启动和桥接，再复用 WCX 启动逻辑。所有 ZIP/APK 编译均在指定 Debian 主机上完成。

**Tech Stack:** Magisk 公共 Zygisk API、Android NDK/CMake、AGP/Kotlin、既有 Rust `xtask`、Python 3 标准库。

**Design:** [WCX Zygisk 首版设计](../specs/2026-11-27-wcx-zygisk-design.md)。首版只验证 ARM64、Magisk 和微信主进程；不开发隐藏、反检测、安全检查绕过、自动刷机或 WebUI。

---

## 文件边界

| 文件 | 用途 |
|---|---|
| `zygisk/README.md` | 远程构建、安装、验证和回退说明；不保存主机地址或凭据 |
| `zygisk/build.py` | 构建、校验和生成 ZIP；支持从显式输入产物打包 |
| `zygisk/module/module.prop.in` | Magisk 元数据模板 |
| `zygisk/module/customize.sh` | 安装前检查 ARM64、Zygisk 和文件完整性；拒绝不支持的安装 |
| `zygisk/native/CMakeLists.txt`、`zygisk/native/src/module.cpp` | 独立编译、公开 Zygisk API 入口和启动诊断 |
| `zygisk/native/vendor/README.md` | API 头文件及所选兼容依赖的固定版本、许可证和来源 |
| `app/build.gradle.kts`、`app/proguard-rules.pro` | 显式 `zygisk` payload 构建目标与入口保留规则 |
| `app/src/main/assets/xposed_init`、`app/src/xposed/assets/xposed_init` | 若需从 payload 剥离声明，将原文件移至两个普通变体共用的资源目录；内容逐字节保留 |
| `app/src/zygisk/AndroidManifest.xml` | ZIP 专用 manifest 叠加；不进普通 APK |
| `app/src/zygisk/java/com/Johnny/wcx/loader/entry/zygisk/ZygiskEntry.kt`、`ZygiskLoaderService.kt`、`ZygiskHookBridge.kt` | ZIP 专用入口及 `ILoaderService` / `IHookBridge` 适配；不进普通 APK |
| `app/src/main/java/com/Johnny/wcx/loader/startup/UnifiedEntryPoint.kt`、`StartupAgent.kt`、`WeLauncher.kt` | 只有在需要共享内部启动逻辑时才引入最小接缝；普通入口原有行为不变 |

## 1. 建立 Debian 构建基线

- [ ] 在已有 Debian 主机上使用独立构建目录；不要复用已清理的 `/root/wcx`，也不改系统级配置。预检远端 SDK、NDK 和 Rust。路径与签名参数由当地构建环境提供，不写入 Git 或日志。

  ```bash
  export ANDROID_HOME=/opt/android-sdk
  export PATH="$HOME/.cargo/bin:$ANDROID_HOME/platform-tools:$PATH"
  java -version
  test -r "$ANDROID_HOME/platforms/android-37.0/android.jar"
  test -x "$ANDROID_HOME/ndk/30.0.14904198/ndk-build"
  rustup target list --installed | grep -Fx aarch64-linux-android
  git --version; cmake --version; python3 --version; df -h .
  ```

- [ ] 远端从 WCX `origin` 取得 `53dd2c8` 后建立基线检出。后续用 `git bundle create "$BUNDLE" zygisk ^53dd2c8` 将已提交的分支差异传到远端；在同一远端仓库用 `git bundle verify "$BUNDLE"`、`git fetch "$BUNDLE" refs/heads/zygisk` 导入，按提交新建工作目录，不推送分支。基线与候选均运行 `git submodule update --init --recursive`，记录 `git rev-parse HEAD` 和 `git submodule status --recursive`。若远端拿不到基线或子模块锁定提交，停在同步步骤，不使用目录副本或猜测版本替代。
- [ ] 以现有签名方式运行基线构建。固定签名材料不在源码中；先确认远端已有合法可用的签名配置，不用调试签名冒充正式签名。构建失败先记录基线失败，不更改普通 APK 或签名设置以掩盖问题。

  ```bash
  ./x build --native-only
  ./gradlew :app:assembleRelease --no-daemon --max-workers=2 --stacktrace
  find app/build/outputs/apk -type f -name '*.apk' -print | sort
  ```

- [ ] 对基线 APK 保存 `aapt dump badging`、`apksigner verify --print-certs`、`unzip -Z1` 和 SHA-256。预期普通变体是 `standard` / `legacy`，各保留 `arm64-v8a` / `armeabi-v7a`，两者都有 `assets/xposed_init`，只有 `standard` 有 `META-INF/xposed` 入口。用实测清单做后续比较，不要求不同提交的 APK 字节相同。

## 2. 验证 loader 与桥接可行性（发布阻断点）

**Debian 预检（源码 `2917cce`）：** x86_64 主机可用 JDK 21、SDK 37.0、NDK 30 和 Rust ARM64 target；`adb devices -l` 无设备，未安装 Android 模拟器和系统镜像。该提交还没有 Zygisk loader。将当前源码和 API JAR 从 Git 树传到 Debian 后，无 Xposed 类路径的 JVM 找不到 `XposedBridge`，而 `javap -c` 确认编译期 JAR 的 `hookMethod` 只会抛出 `RuntimeException("Stub!")`。因此直接复用当前入口或把 stub 打入 payload 都不能提供运行时桥接。这是依赖验证，不是 Android 上的启动测试。Windows ADB 已识别一台 ARM64 / Android 16 手机，但设备当前使用 KernelSU + Zygisk 实现，LSPosed 与 WeKite Zygisk 模块均启用，不能作为本计划的 Magisk / 无 LSPosed 验收环境。现有模块和微信作用域保持原样。

- [x] 盘点现有依赖：`UnifiedEntryPoint.entry()` 的启动 hook 走 `HookUtils.kt`，其中 `hookAfterDirectly` 仍直接调用 `XposedBridge.hookMethod`；`app/libs/xposed-api-82.jar` 只是 `compileOnly`。下列命令列出直接依赖，不能用一个空壳 `IHookBridge` 或打包编译期 stub 来通过构建。

  ```bash
  git grep -l -E 'XposedBridge|XposedHelpers|XC_MethodHook' -- app/src/main/java
  git grep -n -E 'compileOnly.*xposed|ensureHiddenApiAccess|app_qqprotect' -- app/build.gradle.kts app/src/main/java
  ```

- [x] 在 [`zygisk/smoke/`](../../../zygisk/smoke/README.md) 维护独立的日志探针，锁定官方公共 Zygisk API v5 头文件和 SHA-256。已在 Debian 构建并校验 APK 签名、ZIP 结构、ARM64 ELF 及 `zygisk_module_entry`。在 Android 16 / ARM64 / KernelSU 手机的自有测试进程中，同一 PID 先记录 `probe_post_app_specialize`，后记录 `probe_app_started xposed_bridge=absent`；卸载探针模块并重启后只剩应用侧日志，随后也卸载了探针 APK。这不是 WCX 启动或 Magisk 兼容性的证据。
- [ ] 在启用 Magisk/Zygisk 的 ARM64 设备上重复日志探针与卸载回归，单独记录 Magisk 版本和非目标进程检查结果。今后实际 loader 原生库输出路径为 `zygisk/native/build/libwcx_zygisk.so`，不复用日志探针模块 ID 或产物。
- [ ] 在测试进程验证候选 Java hook 后端的最低能力：方法/构造器，优先级与 before/after，修改参数和结果，异常传递，调用原方法与解除 hook。实现代码必须有明确许可证和固定来源。确认 APK 内运行时依赖来自实现库，不是 Xposed 编译期 stub；如果后端不可用或必须依靠安全检查绕过，记录阻塞，不把冒烟模块标为可用的 WCX ZIP。
  已完成候选筛查与 ART TI 真机对照，详见[独立后端验证报告](../reports/2026-11-27-wcx-zygisk-backends.md)。公共 Zygisk API 缺少通用 Java hook，公开 JVMTI 接口拒绝非 debuggable APK；尚无后端通过上述接口验证，此项保持未完成。
- [ ] 梳理进入设置页前必经的直接 Xposed 调用、`NativeLoader` 的 DexKit/MMKV/`wekit_native` 查找、模块资源以及 `WeLauncher` 的功能初始化。只选择能由兼容层真实满足的调用；不支持的可选功能从 payload 的可用范围中剔除并列清单。启动路径若仍强依赖尚未实现的调用，停在独立 ZIP/基线验证，不标记 Zygisk 版可用。

## 3. 增加独立 ARM64 payload

- [ ] 在 `app/build.gradle.kts` 使用 `providers.gradleProperty("wcxZygisk").map(String::toBoolean).orElse(false)` 作为显式开关。仅为 `true` 时添加 `zygisk` flavor，限制该次调用到 `arm64-v8a`。普通构建依然只有现有两种 flavor、两个 ABI，未传属性时 `assembleZygiskRelease` 不应存在。
- [ ] 把传统 Xposed 入口资源隔离到 `standard` / `legacy` 共同使用的 `app/src/xposed/assets`，如主 manifest 声明 Xposed 元数据，使用 `app/src/zygisk/AndroidManifest.xml` 定向移除；不改普通变体入口、签名或 R8 默认行为。payload 专用入口规则只关联新变体。
- [ ] 在 `app/src/zygisk/java/com/Johnny/wcx/loader/entry/zygisk/` 实现 `ZygiskEntry`、`ZygiskLoaderService`、`ZygiskHookBridge`，依赖任务 2 实测的后端。共享启动按实际能力拆为不含 Xposed 专属处理的公共路径；保留既有 APK 行为。桥接未就绪则不启动，不进入隐藏 API 豁免、删除宿主保护目录或反检测功能，不改变微信或其他应用的权限设置。仅在桥接和资源验证成功后记录 `WCX Zygisk: ready`。
- [ ] 远端构建并检查唯一的 ARM64 APK。检查失败要给出缺失项，不回退选择同目录下的历史 APK。

  ```bash
  ./x build --native-only --abi arm64-v8a
  ./gradlew -PwcxZygisk=true :app:assembleZygiskRelease --no-daemon --max-workers=2 --stacktrace
  find app/build/outputs/apk/zygisk/release -type f -name '*.apk' -print
  ```

  预期：包含 `classes.dex`、`resources.arsc`、ARM64 的 `libwekit_native.so`、DexKit 和 MMKV 库及 Zygisk 适配入口；无其他 ABI，且无 `assets/xposed_init` 或 `META-INF/xposed/` 入口。重跑无属性的 `:app:assembleRelease`，对比任务 1 的产物清单和签名证书，正常 APK 不得含 Zygisk 适配入口。

## 4. 装配 ZIP 并验证失败行为

已在 Debian 完成[探针嵌包及正式包静态预检](../../../zygisk/README.md)：签名探针 APK 内嵌后字节不变，坏 ELF、版本不一致、两类 Xposed 声明、非法归档路径、探针误用正式预检和缺失输入均被拒绝。探针没有安装本次嵌包产物；以下正式构建步骤仍依赖独立后端。

- [ ] 在 `zygisk/build.py` 实现 `build --output <zip>` 与 `pack --apk <apk> --loader <so> --output <zip>`。`build` 显式调用任务 3 的构建目标，并用 SDK 声明的 NDK CMake 工具链编译任务 2 的 loader。`pack` 不编译，不遍历历史产物；从 APK 的 `aapt dump badging` 取版本，确保有一个 ARM64 payload；用 NDK `llvm-readelf` 检查 SO 为 ELF64/AArch64 且导出公共模块入口。检查 APK/ELF 失败则退出非零，原有输出不变。
- [ ] 用 `wcx_zygisk` 模块 ID 渲染 `module.prop`，版本从 payload 派生。按 WeKite 参考契约打包 `module.prop`、`customize.sh`、`zygisk/arm64-v8a.so`、`payload/wcx.apk`；参考 Magisk 官方模板确认安装入口。APK 内容原样写进 ZIP；写同目录临时 ZIP，经 CRC 和条目校验后原子重命名到 `--output`。不加入设备安装命令、自动更新地址、SELinux 放宽配置或常驻服务。
- [ ] 在远端用真实输入及坏输入验证打包命令。以下负例使用临时路径，不污染有效产物：

  ```bash
  temp=$(mktemp -d)
  printf 'not an ELF\n' > "$temp/bad.so"
  ! python3 zygisk/build.py pack --apk "$PAYLOAD_APK" --loader "$temp/bad.so" --output "$temp/bad.zip"
  test ! -e "$temp/bad.zip"
  ! python3 zygisk/build.py pack --apk "$temp/missing.apk" --loader "$LOADER_SO" --output "$temp/missing.zip"
  test ! -e "$temp/missing.zip"
  python3 zygisk/build.py pack --apk "$PAYLOAD_APK" --loader "$LOADER_SO" --output "$temp/wcx-zygisk.zip"
  unzip -t "$temp/wcx-zygisk.zip"
  unzip -Z1 "$temp/wcx-zygisk.zip" | sort
  unzip -p "$temp/wcx-zygisk.zip" module.prop
  cmp "$PAYLOAD_APK" <(unzip -p "$temp/wcx-zygisk.zip" payload/wcx.apk)
  ```

  另外以任务 1 的 ARMv7 APK 作为负例，预期输出不存在。检查 ZIP 无根路径、父路径跳转、重复文件名或未渲染的模板变量。安装脚本以 Magisk 官方模板接口报告失败，拒绝缺文件、非 ARM64 或 Zygisk 未启用的安装。

## 5. 远程整包与真机验收

- [ ] 从待验证提交的独立远端检出执行 `python3 zygisk/build.py build --output zygisk/dist/wcx-zygisk-arm64.zip`。保存构建和签名校验日志，和 `git rev-parse HEAD`、子模块状态、APK/ZIP 的 SHA-256 一起取回。本机只验证哈希和 ZIP 条目，不重新构建。普通 APK CI 不改，ZIP 不发布为正式版本。
- [ ] 在用户自有的 ARM64 设备上记录 Android / Magisk / 微信版本。手动安装 ZIP，关闭 LSPosed 下对微信的 WCX 作用域，按 Magisk 提示重启。检查微信主进程正常启动、设置页能打开、非目标应用和微信子进程没有就绪记录。通过 Magisk 禁用或卸载、重启后验证 WCX 不再加载且微信仍可正常启动。
- [ ] 更新 `zygisk/README.md`：列出命令、支持矩阵、实测版本、未验证的功能、已知依赖和回退方式。没有真机证据时仅报告远程构建通过；桥接阻塞时只报告已验证的阶段，不称完整支持。实现按任务独立提交，仅暂存相关文件；不把产物、日志、私钥或主机连接信息提交入库。
