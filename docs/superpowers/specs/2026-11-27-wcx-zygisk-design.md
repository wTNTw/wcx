# WCX Zygisk 多管理器设计

## 目标

为当前 WCX 分支增加独立的 Zygisk 部署形式：ARM64 payload、native 注入器和 Root 管理器模块包。首版面向 Magisk、KernelSU、APatch。KernelSU/APatch 提供按 Android 用户和微信实例配置目标的 WebUI，Magisk 首版固定目标为 `com.tencent.mm` 主进程。所有管理器对启用的微信包实例注入主进程及其 `:...` 子进程。未在相应设备上验证的管理器不标记为已支持。

Zygisk 部署复用 WCX 统一启动逻辑和 `ILoaderService` / `IHookBridge` 合约，不改变普通 APK 的入口、包名、签名、ABI 或发布流程。Zygisk ZIP、payload 和普通 APK 是独立产物。用户不得对同一微信实例同时启用 WCX 的 LSPosed 和 Zygisk 部署。

## 参考实现与采用范围

参考 WeKit `Ujhhgtg/WeKit` 固定提交 `b51532bf3427348af824d7d704e55081fac9ca55` 的 Zygisk 子项目，并参考 WeKite `SymonChu/WeKite` 的模块布局和安装模板。移植固定上游来源及许可证，保留对 WCX 必需的 lifecycle、companion allow-list IPC、payload/DEX bootstrap、LSPlant ART hook backend、Kotlin bridge 和 WebUI 适配，并明确列出 WCX 补丁。

按用户确认，本分支也移植 WeKit 的 `so_hider`：它读取当前进程 `/proc/self/maps` 中匹配的映射，使用 memfd、mprotect 和 MAP_FIXED 重映射段，意图隐藏 native 模块路径。这是改变本进程内存映射、可能影响诊断和兼容性的隐蔽行为；必须单独审计其匹配范围、映射权限恢复和失败路径，并以开关隔离、默认行为及验证结果记录在支持文档中。不得扩大匹配到 WCX 以外的映射。

不移植 WeKit Telegram root companion 及 Telegram 包扫描、`cache4.db`/WAL/SHM 读取能力。模块不增加宽松 SELinux 规则（参考提交的 `sepolicy.rule` 为空）。WebUI 只管理本模块的微信目标和状态，不加载任意远程代码。

## 构建与模块包

- `standard` / `legacy` 构建和普通 APK 行为保持不变。新增显式启用的 Zygisk ARM64 payload 变体，只收录 Zygisk 入口和必需 native/runtime 库；不将既有签名 APK 解包、改写或重签后用作 payload。
- Zygisk 产物由同一提交的 WCX 代码、资源和固定版本的后端依赖构建。ZIP 生成命令只接收显式产物路径，验证包名、版本、签名状态、ABI、native ELF、导出入口、Xposed 元数据排除和归档路径；验证失败不能覆盖原有 ZIP。
- 提供 Magisk、KernelSU、APatch 可安装模块布局。针对三者的安装脚本、模块元数据和卸载/停用语义做显式适配；不以一个管理器上的安装成功推断其他管理器兼容。
- 每个 Zygisk native loader 在 specialize 生命周期读取目标规则，只继续处理已启用的微信包。目标进程名必须等于包名或以 `${package}:` 开始；对非目标应用、系统进程和未启用的微信实例不初始化 WCX。

## 目标配置

- KernelSU/APatch WebUI 扫描 Android 用户下已安装的微信包实例，新增目标默认关闭；刷新时保留既有开关，移除已卸载实例。目标身份包含 Android user 与 package，防止不同用户/多开配置串用。启用目标影响该实例的主进程及全部 `:...` 子进程。
- WebUI 仅提供目标刷新、启停和状态查看。存储格式有版本号，更新时兼容已有数据；卸载仅删除模块自身配置，不触碰微信数据。
- Magisk 首版使用固定目标 `com.tencent.mm` 主进程及子进程，不依赖通用 WebUI API。其行为与 KSU/APatch 按用户实例选择不同，应在文档明确说明。
- 只匹配合法的微信包名和进程名前缀，不扫描或加载其他应用。未来扩大包名前缀规则须另行设计和验证。

## Runtime 与风险边界

- native loader 使用公开 Zygisk API，按上游生命周期持有 module directory FD，在 app specialize 后安全读取模块 APK、发布 payload 并创建 `InMemoryDexClassLoader`。初始化 ART hook 后建立 Zygisk app bridge，再进入 WCX 共享启动流程。
- LSPlant 是 ART method hook backend；适配维持 WCX bridge 已有的回调语义，包括优先级、before/after、参数与返回值修改、调用原方法、异常传递、构造器支持和回调解绑。上游初始化可能依赖 ART 私有符号解析、代码改写及模块 DEX trust。移植须记录其精确行为与源代码，不把探针结果当作验证。
- 按本次范围，允许迁移上游 hook 初始化所需的 ART/DEX 机制及 `so_hider`；不以修改微信 APK、系统镜像或全局 SELinux 策略作为后备方案。对 ART 初始化失败、DEX trust 失败、映射重映射失败应阶段化记录并停止该进程的 WCX 初始化，不能假报成功。
- `so_hider` 改写目标进程映射，是本设计明确纳入的隐藏行为。实现需以精确的模块库路径为匹配目标，保留原保护属性，失败时不破坏映射；对开启/关闭效果、maps 观察、原生库加载和崩溃/卸载回归做单独验证。不得将其扩展成通用模块隐藏器或隐藏无关库。
- companion IPC 只承载注入目标 allow-list 判定，不提供任意文件读取、任意命令执行或其他应用数据访问。卸载后移除本模块 companion 与配置。
- 管理器停用/卸载后按各自流程重启恢复未注入状态。文档说明需要关闭微信作用域下 WCX LSPosed 部署以防双重注入。

## 验收

1. **构建隔离**：无 Zygisk 开关时，原 `standard` / `legacy` 构建任务、签名证书、ABI 和 Xposed 入口与基线一致；普通 APK 不含 Zygisk 专用入口。显式目标只生成 ARM64 payload 和 loader。
2. **包结构**：对 Magisk、KernelSU、APatch 的模块格式分别验证元数据、安装/卸载脚本、目标 WebUI 文件、ARM64 loader、payload 版本及 archive path；负例覆盖缺文件、错误架构、版本不一致、无效 ELF、路径遍历和无效目标配置。
3. **Hook contract**：在自有测试 APK 上验证 LSPlant bridge 的方法/构造器、before/after 顺序、参数/结果变更、异常传递、原方法调用、优先级、并发与解绑行为；验证 ART/DEX 初始化行为和失败路径。仅源码编译和 desktop 测试不构成 ART 运行时证据。
4. **so_hider**：在自有测试进程比较开启/关闭状态的精确映射，验证只处理指定 loader/payload 映射、非目标映射不变、权限属性保持、重复调用与失败路径安全，并验证 hook 初始化、日志诊断与进程正常退出。
5. **设备矩阵**：在各自真实管理器设备上记录 Android、管理器、WCX 和微信版本；逐一验证安装、目标启停、启用实例主/子进程均加载、未启用实例与其他应用不加载、设置页、停用/卸载及重启回退。缺少设备测试的管理器明确标为未验证。
6. **限制**：首版 ARM64；Magisk 固定 `com.tencent.mm`，KSU/APatch 按 Android 用户及包配置；不注入非微信目标，不含 Telegram root companion/数据库读取，不自动刷机、不自动发布。
