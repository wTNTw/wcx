# WCX Zygisk 首版设计

## 目标与分支

- `zygisk` 从 WCX `master`（`53dd2c8`）分出。后续从 WCX `master` 同步功能代码，普通 APK 继续沿现有上游同步和发布流程维护。
- 首版目标是可由启用了 Zygisk 的 Magisk 手动安装的 ARM64 ZIP。仅验证当前 Android 版本范围内的一套 ARM64 真机与受支持的微信版本；不承诺所有版本或功能已兼容。
- `standard` / `legacy` 的入口、包名、签名、分包、构建命令和发布规则不变。Zygisk 的 loader 与 ZIP 独立维护，不让普通 APK 依赖 Zygisk。

## 结构与构建

- `zygisk/` 保存原生 loader、Magisk 安装模板、构建入口与说明。参考 WeKite `19bffe4c98c8ccd966de47a4ebd059dc2a6286d8` 的布局：`module.prop`、安装模板、`zygisk/arm64-v8a.so` 和 `payload/wcx.apk`。参考打包契约，不移植其运行时代码、WebUI、隐藏逻辑或 SELinux 放宽规则。
- ZIP payload 是独立的 ARM64 APK 构建目标，使用同一提交的 WCX 代码、资源和所需的 ARM64 原生库。Zygisk 适配代码只进该目标；既有发布 APK 不作为 ZIP 输入，不解包重签。现有 Gradle 配置只做隔离该目标所需的改动，并验证普通构建不会顺带产出它。
- 独立命令显式构建 loader 和 payload，再校验并生成 ZIP。`module.prop` 的版本号从 WCX 的构建版本派生，ZIP 使用独立模块 ID；不隐式选择旧产物。缺少任一输入、架构不符或版本校验失败时退出，不留下半成品。首版不改普通 APK 工作流，不自动刷机或发布。

## 远程构建

- ZIP、payload APK 和普通 APK 回归构建都在用户指定的 Debian 13 构建机上执行；本机只编辑、同步源码以及检查产物。构建机连接信息与签名凭据不入库。
- 将待验证提交和锁定的子模块同步到远端独立检出，不覆盖已有构建目录，也不要求推送分支。使用远端 JDK 21、Android SDK/NDK 和 Rust 工具链，按仓库声明的 SDK 与 NDK 版本做预检。
- 构建记录包含 Git 提交与子模块版本，取回 ZIP、所需 APK、日志和 SHA-256 校验值。本地校验下载的产物；真机验证与远程构建分开记录。

## 运行时边界

- 通过 Magisk 的公开 Zygisk 模块 API 接入。用户手动安装并启用模块；首版只匹配微信主进程，不处理其他应用、系统进程、微信子进程。不得与微信作用域内的 LSPosed 版同时启用。
- loader 的 app 侧适配实现 `ILoaderService` / `IHookBridge`。初始化桥接后复用 WCX 的统一启动逻辑，不复制业务 hooks，也不在普通 APK 入口中增加 Zygisk 分支。保留原本的 APK 启动行为。
- Zygisk 路径不加入隐藏、反检测或安全检查绕过。共享启动代码中已有的 Xposed 兼容或反检测步骤（包括删除宿主保护目录）不在 Zygisk 路径中运行；如果所需的启动步骤无法在此约束下工作，记录为首版阻塞项，不以扩大权限作为兜底。
- payload 或桥接不可用时停止初始化并记录阶段和原因，不重试、不影响非目标进程。禁用或卸载走 Magisk 原生流程；按管理器提示重启后应恢复到未加载 WCX 的状态。

## 验收

1. **构建与回归**：干净检出可使用文档命令构建 ZIP；校验模块元数据、目录结构、版本、ARM64 ELF 和 payload 内容。缺失或错误的输入明确失败。原有 `standard` / `legacy` 仍能按原命令独立构建，签名配置、产物 ABI 和 Xposed 入口不变，普通 APK 产物不包含 Zygisk 适配代码。
2. **真机冒烟**：在用户自有 ARM64 设备上，记录 Android、Magisk 和微信的实测版本。手动安装 ZIP，关闭对应 LSPosed 作用域后验证微信主进程启动、WCX 设置可打开、非目标应用未加载。验证禁用或卸载、重启后的回退；未做真机验证时只报告构建结果，不称运行时已验证。
3. **范围外**：ARMv7、KernelSU、APatch、WebUI、自动更新、容器或多开、微信所有子进程、APK/ZIP 同时启用、逐项功能验证。
