# WCX Zygisk 部署

本目录包含 WCX 的 Zygisk 部署：ARM64 native loader、上游运行时导入、Root 管理器模块模板、打包与校验工具。

普通 `standard` / `legacy` APK 的构建、签名、入口和发布流程不变。Zygisk payload 是独立产物，不能与原 APK 混用。

## 目录

| 路径 | 作用 |
| --- | --- |
| `native/` | ARM64 loader：Zygisk 生命周期、目标 allow-list companion、payload/DEX bootstrap、LSPlant 接入、库映射隐藏 |
| `backend/README.md` | 上游来源、许可证、逐文件导入清单、排除项与风险 |
| `module/` | 三种管理器的模块模板（`module.prop.in`、`customize.sh`、`uninstall.sh`、KernelSU/APatch 的 `config.sh`/`action.sh`/`webroot/`） |
| `package_zip.py` | 显式输入的打包与校验命令 |
| `tests/` | 归档结构与打包原子的单元测试（不需要 Android SDK） |
| `smoke/`、`independent-hook-probe/` | 既有的日志探针与可调试 APK Hook 探针，不参与正式产物 |

## 构建

需要：JDK 21、Android SDK（platform 37.0、build-tools 37.0.0、NDK 30.0.14904198）、Rust + `aarch64-linux-android` target、CMake ≥ 3.28、Ninja、Python 3。

```bash
export ANDROID_HOME=/opt/android-sdk
export PATH="$HOME/.cargo/bin:$PATH"

# 一次性：拉取固定版本的 LSPlant / XZ Embedded（含 DexBuilder 依赖）
sh zygisk/native/prepare-deps.sh

# 1. loader（输出 zygisk/arm64-v8a.so）
sh zygisk/native/build.sh dist/zygisk

# 2. payload APK（只取 arm64-v8a 的 legacy 变体）
bash gradlew :app:assembleLegacyRelease -PwcxZygisk=true
# -> app/build/outputs/apk/legacy/release/app-legacy-arm64-v8a-release*.apk
#
# 立刻取出 payload：不带属性的普通 legacy release 构建会写到同一路径，把它覆盖成
# 不含 Zygisk 入口的普通 APK。打包器会拒绝这种 APK，但别让构建顺序成为唯一防线。
cp app/build/outputs/apk/legacy/release/app-legacy-arm64-v8a-release.apk dist/zygisk/payload.apk

# 3. 三种管理器模块包
for manager in magisk kernelsu apatch; do
  python3 zygisk/package_zip.py pack \
    --apk dist/zygisk/payload.apk \
    --loader dist/zygisk/zygisk/arm64-v8a.so \
    --manager "$manager" \
    --output "dist/wcx-zygisk-$manager.zip"
done
```

`pack` 只接受显式路径，不会在 `app/build/outputs` 里挑选历史产物。它会拒绝不含 Zygisk 入口类的 APK（即未带 `-PwcxZygisk=true` 构建的普通包），校验失败时不会覆盖已有 ZIP。

`-PwcxZygisk=true` 只在该次调用中把 `app/src/zygisk/java` 加入 `main`、并从资源中排除 `assets/xposed_init`。不带该属性时构建输入与任务图与之前一致。

## 校验

```bash
python3 -m unittest discover -s zygisk/tests -v          # 归档规则与打包原子性
python3 zygisk/package_zip.py verify <zip> --profile magisk
```

`verify` 检查模块 ID/版本、归档路径（拒绝重复、穿越、符号链接）、安装与卸载脚本的安全前提、loader 的 ELF64/AArch64 与 `zygisk_module_entry` 导出，以及 payload 的包名、签名、版本、ARM64 库集合与 Xposed 元数据缺失。它不验证安装脚本在真机上的行为，也不构成运行时证明。

## 目标选择

- **KernelSU / APatch**：模块页面刷新后列出各 Android 用户下的微信实例，新发现的实例默认关闭；启用后该实例的主进程与 `:...` 子进程都会被注入。列表保存在 `/data/adb/wcx_zygisk/injection-targets.tsv`，卸载时只删除该模块自己的状态。
- **Magisk**：固定 `com.tencent.mm` 主进程及其 `:...` 子进程，不提供 WebUI。

三种管理器都只接受 `com.tencent.mm` / `com.tencent.mm.*`，其他包、系统进程和未启用实例一律不初始化。

## 已知边界

- 仅 ARM64、仅微信。
- Zygisk 路径会执行上游的 hidden-API 豁免与 `app_qqprotect` 删除，并对 WCX 自己的 payload 库做映射重映射以隐藏路径；这些行为已记录在 `backend/README.md`，不是可选装饰。
- 不要对同一微信实例同时启用 WCX 的 LSPosed 作用域与 Zygisk 模块。
- 停用/卸载后按管理器提示重启以恢复未注入状态。

## 验证状态

在 Debian 13 构建机（JDK 21、SDK platform 37.0、build-tools 37.0.0、NDK 30.0.14904198、Rust 1.102 nightly、CMake 3.31.6、Ninja 1.12.1）上实测：

| 项目 | 结果 |
| --- | --- |
| `prepare-deps.sh`（LSPlant/XZ/DexBuilder） | 通过 |
| loader 编译：ELF64 / AArch64 / DYN，导出 `zygisk_module_entry` + `zygisk_companion_entry` | 通过 |
| payload APK（`-PwcxZygisk=true`，legacy，arm64-v8a） | 通过；含 Zygisk 入口类，无 `assets/xposed_init`、无 `META-INF/xposed/`、manifest 无 Xposed 声明与 libxposed provider |
| 三份管理器 ZIP（pack + verify） | 通过 |
| 归档规则与打包原子性单元测试（17 项） | 通过 |
| 普通 `standard` / `legacy` 回归 | 通过：standard 仍含 `assets/xposed_init` + 3 个 `META-INF/xposed/*`，legacy 仍含 `assets/xposed_init`；manifest 仍声明 Xposed 模块 |

参考哈希（本轮构建；payload 由**测试密钥**签名，非发布签名）：

```text
570a117a…9bbf08  payload.apk（legacy arm64-v8a，含 Zygisk 入口）
1bc59684…65b0b4  zygisk/arm64-v8a.so
24ad2527…959c82  wcx-zygisk-magisk.zip
b2ba7374…b186402 wcx-zygisk-kernelsu.zip
bc73a465…e30a680 wcx-zygisk-apatch.zip
```

隔离性证据（同一轮构建）：payload APK 含 7 处 `ZygiskEntry` 引用；不带属性的普通 legacy / standard APK 均为 0 处；普通 legacy 仍有 `assets/xposed_init`，standard 仍有 3 个 `META-INF/xposed/*`。

### 真机结果（KernelSU / Android 16 / 微信 8.0.80）：失败

- 安装、模块激活、默认不注入、`config.sh` 扫描与启停：通过。禁用目标后微信立即恢复。
- 启用目标后：微信进程在 attach 阶段死亡，`SIGILL` 命中 `libart.so art::ClassLinker::VisiblyInitializedCallback::MarkVisiblyInitialized+20`，栈上是 LSPlant 桥与 inline-hook 重定位的匿名可执行段（详见 `backend/README.md` 与后端报告）。
- 因此**当前产物不可用**：hook 后端与该 ART 构建不兼容。未验证项（hook 语义、`so_hider` 实际映射、子进程注入稳定性、卸载回退）在修复前不必再测。
