# WCX Zygisk 多管理器设计

## 目标

为当前 WCX 分支增加独立的 Zygisk 部署形式：ARM64 payload、native 注入器和 Root 管理器模块包。首版面向 Magisk、KernelSU、APatch，支持微信主进程；KernelSU/APatch 提供按 Android 用户和微信实例配置目标的 WebUI，Magisk 首版固定目标为 `com.tencent.mm` 主进程。未在相应设备上验证的管理器不标记为已支持。

Zygisk 部署应复用 WCX 统一启动逻辑和 `ILoaderService` / `IHookBridge` 合约，不改变普通 APK 的入口、包名、签名、ABI 或发布流程。Zygisk ZIP、payload 和普通 APK 是独立产物。用户不得对同一微信实例同时启用 WCX 的 LSPosed 和 Zygisk 部署。

## 参考实现与采用范围

- 参考 WeKit `Ujhhgtg/WeKit` 的公开 Zygisk lifecycle、LSPlant ART hook backend、Kotlin hook bridge 接合和按用户区分目标的管理器 UI。移植时固定上游来源和提交，保留相关许可证，并仅带入所需组件及明确的 WCX 适配补丁。
- 参考 WeKite `SymonChu/WeKite` 的模块目录、安装模板和生命周期设计，不直接复制与 WCX 不匹配的业务逻辑。
- 不带入隐藏、反检测、安全检查绕过、权限放宽或与功能无关的远程执行逻辑。安装包不加入宽松 SELinux 规则。管理器 WebUI 只管理本模块的目标开关，不加载任意远程代码。

## 构建与模块包

- `standard` / `legacy` 构建和普通 APK 行为保持不变。新增显式启用的 Zygisk ARM64 payload 变体，只收录 Zygisk 入口和必需 native/runtime 库；不将既有签名 APK 解包、改写或重签后用作 payload。
- Zygisk 产物由同一提交的 WCX 代码、资源和固定版本的后端依赖构建。ZIP 生成命令只接收显式产物路径，验证包名、版本、签名状态、ABI、native ELF、导出入口、Xposed 元数据排除和归档路径；验证失败不能覆盖原有 ZIP。
- 提供 Magisk、KernelSU、APatch 可安装模块布局。针对三者的安装脚本、模块元数据和卸载/停用语义做显式适配；不以一个管理器上的安装成功推断其他管理器兼容。
- 每个 Zygisk native loader 在进程 specialize 回调中读取目标规则，只继续处理已启用的微信主进程/配置实例；对非目标应用和进程不初始化 WCX。进程启动、目标过滤、payload 读取及 hook 后端初始化均应记录可诊断的阶段结果，不记录敏感用户数据。

## 目标配置

- KernelSU/APatch WebUI 扫描 Android 用户下已安装的微信包实例，新增目标默认关闭；刷新时保留既有开关，移除已卸载实例。目标身份应包含 Android user 与 package，防止不同用户/多开配置串用。
- WebUI 仅提供目标刷新、启停和状态查看。存储格式有版本号，更新时兼容已有数据；卸载仅删除模块自身配置，不触碰微信数据。
- Magisk 首版使用固定目标 `com.tencent.mm` 主进程，不依赖并不存在的通用 WebUI API。其行为与 KSU/APatch 按用户实例选择不同，应在安装文档和 UI 状态中明确说明。
- 所有管理器只匹配微信主进程；不注入 `:push` 等子进程，不扫描或加载其他应用。未来扩大进程范围须另行设计和设备验证。

## Runtime 与安全边界

- native loader 使用公开 Zygisk API，初始化后通过 WCX 专用 app 入口建立 loader service 与 hook bridge，再进入共享启动流程。普通 APK 不添加 Zygisk 分支或依赖。
- LSPlant 是 ART method hook backend；适配应维持 WCX bridge 已有的回调语义，包括优先级、before/after、参数与返回值修改、调用原方法、异常传递、构造器支持和回调解绑。无法满足的边界须写入支持矩阵，不可静默退化。
- Zygisk 路径跳过依赖 Xposed 框架的操作，以及隐藏 API 豁免、删除宿主保护目录等与安全边界冲突的逻辑。无法在此边界下运行的初始化步骤或功能应作为明确阻塞/不支持项，而非引入绕过措施。
- payload、目标配置或 hook backend 不可用时停止该目标进程的 WCX 初始化并记录原因；不影响非目标进程，不自动重试、不安装常驻服务。
- 管理器停用/卸载后按各自流程重启恢复未注入状态。文档说明需要关闭微信作用域下 WCX LSPosed 部署以防双重注入。

## 验收

1. **构建隔离**：无 Zygisk 开关时，原 `standard` / `legacy` 构建任务、签名证书、ABI 和 Xposed 入口与基线一致；普通 APK 不含 Zygisk 专用入口。显式目标只生成 ARM64 payload 和 loader。
2. **包结构**：对 Magisk、KernelSU、APatch 的模块格式分别验证元数据、安装/卸载脚本、目标 WebUI 文件、ARM64 loader、payload 版本及 archive path；对缺文件、错误架构、版本不一致、无效 ELF、路径遍历和无效目标配置等负例验证明确失败。
3. **hook contract**：在自有测试 APK 上验证 LSPlant bridge 的方法/构造器、before/after 顺序、参数/结果变更、异常传递、原方法调用、优先级、并发与解绑行为。仅源码编译和 desktop 测试不构成 ART 运行时证据。
4. **设备矩阵**：在各自真实管理器设备上记录 Android、管理器、WCX 和微信版本；逐一验证安装、目标启停、微信主进程启动和设置页、非目标进程不加载、停用/卸载及重启回退。缺少设备测试的管理器明确标为未验证。
5. **限制**：首版 ARM64；Magisk 固定主进程目标；KSU/APatch 提供按 Android 用户和实例配置，不注入子进程；不自动刷机、不自动发布，不迁移上游 WebUI 以外的管理功能。
