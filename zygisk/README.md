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

# 3. 三种管理器模块包
for manager in magisk kernelsu apatch; do
  python3 zygisk/package_zip.py pack \
    --apk app/build/outputs/apk/legacy/release/app-legacy-arm64-v8a-release.apk \
    --loader dist/zygisk/zygisk/arm64-v8a.so \
    --manager "$manager" \
    --output "dist/wcx-zygisk-$manager.zip"
done
```

`pack` 只接受显式路径，不会在 `app/build/outputs` 里挑选历史产物。校验失败时不会覆盖已有 ZIP。

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

| 项目 | 状态 |
| --- | --- |
| loader 编译（AArch64 ELF、导出符号） | 已在构建机通过 |
| payload APK 构建（无 Xposed 元数据） | 已在构建机通过 |
| 归档规则单元测试 | 通过 |
| 三份 ZIP 静态校验 | 见本轮构建记录 |
| 真机安装、目标启停、子进程注入、回退 | **未验证** |
