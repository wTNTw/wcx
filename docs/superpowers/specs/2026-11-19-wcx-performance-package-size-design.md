# WCX 模块性能与安装包体积优化设计

日期：2026-11-19

目标分支：`zygisk-module`

## 1. 背景

WCX 当前同时承载 Xposed/Zygisk 入口、约 281 个 feature、Hchat 与脚本能力、Compose/Miuix 设置界面、DexKit 适配、网络服务器、媒体转换及诊断工具。完整 arm64 Zygisk APK 已增长到约 44.3 MB，模块在微信主进程中的初始化通常需要约 1–2 秒。

本次优化保留单一完整 APK，不删除现有功能，不拆分精简版或在线功能包。方案同时处理包体积和运行性能，但优先保证微信启动稳定性。

## 2. 当前基线

### 2.1 APK 构成

基线产物：`app/build/outputs/zygisk/wcx-v347-arm64-v8a.apk`

| 项目 | 当前值 |
| --- | ---: |
| APK 文件大小 | 44,274,873 B（约 42.2 MiB / 44.3 MB） |
| ZIP 内未压缩总量 | 48,026,624 B |
| `classes*.dex` | 32,380,616 B |
| arm64 native 库 | 5,453,192 B |
| `libwekit_native.so` | 3,214,480 B |
| `libwcx_zygisk.so` | 745,400 B |

DEX 中较大的代码域包括：

- `h.Hchat`：约 5.59 MB bytecode；
- `com.Johnny.wcx`：约 5.00 MB bytecode；
- Compose：约 1.92 MB bytecode；
- Fastjson2：约 1.47 MB bytecode；
- Kotlin reflection：约 1.37 MB bytecode；
- REAndroid/ARSCLib：约 1.37 MB bytecode。

较大的非 DEX 内容包括：

- `clst/core.jcst`：约 2.29 MB；
- ARSCLib/JADX 相关 Android framework、attribute 和 resource-map 数据；
- 主题壁纸资源；
- 未压缩 native 库。

DEX 与 native 库保持 `STORED` 是有意设计：Zygisk payload 需要让 ART/动态加载器直接 mmap。不得通过压缩 DEX 或 `.so` 制造表面上的体积下降，否则会增加启动解压成本，甚至破坏 LSPosed/Zygisk 加载。

### 2.2 运行性能

已有真机日志显示：

- `WeLauncher` 主线程初始化约 1,023–2,048 ms；
- `ParcelableFixer` 约 92–291 ms；
- `FeaturesLoader` 外层阶段约 1,091–1,839 ms；
- Dex 缓存检查与读取通常只占数十毫秒。

`FeaturesProvider.ALL_HOOK_ITEMS` 当前由 KSP 生成，并在首次访问时直接引用和配置所有 feature 单例。外层耗时显著高于缓存与 feature startup 的内部统计，说明大量类加载、单例初始化和静态初始化发生在内部计时之前。

`NativeLoader` 当前在模块启动阶段加载 `androidx.graphics.path`、DexKit、WeKit native 和 Silk 等库；而其中多项只在进入特定功能后才使用。

## 3. 目标与硬约束

### 3.1 目标

以 arm64 完整 Zygisk release APK 为主验收对象：

1. APK 不超过 36 MB（十进制）；
2. `classes*.dex` 总量不超过 25.5 MB；
3. 主进程 WCX 初始化 P50 相对基线降低至少 25%；
4. P95 不得高于基线；
5. 首屏前不加载媒体转换 native 库；
6. 主进程启动后的 Java heap、native heap 和 PSS 不得回归，期望因懒实例化和按需加载有所下降。

### 3.2 硬约束

- 保留单一完整 APK及全部现有功能；
- 不改变 `IHookBridge` 对 feature 暴露的语义；
- 不把 `hookBefore` / `hookAfter` 包进 `try-catch` 或 `runCatching`；
- 不改变 `libaff_biz` 稳定门、首 Activity gate、LSPlant/Dobby 分层及现有 fallback；
- 主进程继续使用 LSPlant，WeKite 仍只限已批准的目标子进程；
- 不通过压缩 DEX/native 库或移除诊断能力换取虚假收益；
- 标准/legacy 入口和 arm64/armeabi-v7a 构建均须继续可用。

## 4. 总体架构

优化分四个阶段执行：

1. **建立可重复的体积和性能基线**；
2. **收紧 R8 与清理依赖/资源**，先获得低风险体积收益；
3. **改造 feature 注册与启动调度**，减少首屏前类加载和对象初始化；
4. **拆分并按需加载 native 能力**，降低启动 I/O、链接和常驻内存。

每阶段独立提交、独立验收。后一阶段不能依赖未通过验收的前一阶段。

## 5. 阶段一：自动化基线和预算守卫

### 5.1 APK 体积报告

新增构建分析脚本，针对最终 Zygisk APK输出：

- APK 文件大小；
- DEX、native、assets、resources、META-INF 分类大小；
- 每个 `classes*.dex` 大小；
- 每个 native 库大小及主要 ELF section；
- 使用 mapping 的包级 bytecode 排名；
- 与基线提交或上一次 CI 产物的差值。

CI 设置软警告和硬预算：

- 单次提交增长超过 300 KB 时输出警告；
- APK 超过 36 MB 或 DEX 超过 25.5 MB 时，性能优化工作流失败；
- 普通开发构建先只报告，不立即阻断所有 PR，避免历史基线导致无关修改无法合并。

### 5.2 启动阶段指标

统一记录以下单调时钟阶段：

- native `preAppSpecialize`；
- payload publication；
- DexClassLoader 创建；
- `ZygiskEntry.init`；
- libaff 等待完成；
- 首 Activity gate 触发；
- LSPlant 初始化；
- `StartupAgent`；
- `NativeLoader`；
- feature 元数据加载；
- early feature 启动完成；
- 首帧后 deferred feature 完成。

每次进程启动只输出一条结构化摘要，不为每个阶段新增高频日志。探针脚本从持久化 WCX 日志读取摘要，统计 P50/P95 和异常次数。

## 6. 阶段二：R8、依赖与资源瘦身

### 6.1 精确替换全量 keep

当前以下规则会阻止 R8 删除和优化大量代码：

- `-keep class com.Johnny.wcx.features.** { *; }`
- `-keep class h.Hchat.** { *; }`
- `-keep class kotlin.reflect.** { *; }`

处理方式：

1. KSP 为 feature 入口生成精确 keep/keepnames 清单；
2. JNI 入口继续显式保留类名和 native 方法签名；
3. 脚本 ABI 只保留脚本可见类型、构造器、字段和方法；
4. 运行时按类名解析的目标集中登记，禁止散落新的裸 `Class.forName("...")`；
5. 普通 feature 内部实现允许优化、成员重命名和不可达代码删除；
6. `kotlin.reflect` 改为按实际入口保留，优先将 `FastJavaMethod` 的 `KFunction.javaMethod` 路径替换为 Java reflection，评估完全移除 kotlin-reflect runtime。

### 6.2 minified APK 契约测试

不能只依赖编译通过。构建 release 后自动检查：

- Zygisk JNI 注册使用的 FQCN 和方法签名存在；
- Xposed/libxposed 入口存在；
- KSP feature 工厂能解析全部 feature；
- BeanShell/Hchat 脚本 ABI 类名和成员存在；
- 动态加载的 serializer、callback、数据模型存在；
- ProGuard mapping 中未出现被错误重命名的固定 ABI。

测试输入由代码生成或统一 registry 提供，避免人工维护两份名单。

### 6.3 依赖归属审计

为每个重依赖记录唯一 owner 和运行入口：

- ARSCLib；
- JADX dex input；
- smali/baksmali；
- Guava；
- Fastjson2；
- Kotlin reflection；
- MCP/Ktor；
- osmdroid；
- Markwon；
- DexMaker/dalvik-dx；
- Room/Protobuf/FlatBuffers。

没有 owner、没有运行入口或仅用于开发工具的依赖不得进入 release。通过字符串反射或脚本引用的依赖必须进入显式动态依赖 registry，并由契约测试覆盖，不能仅靠全量 keep。

重点调查：

- ARSCLib/JADX 附带的 `core.jcst`、framework APK、attrs 和 resource map 是否全部为运行时必需；
- `fastjson2` 的两处声明是否可统一；
- Markwon 与 Rust `markdown` 是否能共享解析层或减少重复依赖；必须保留现有可选渲染模式，无法等价时继续保留两套实现；
- 两套 Silk 实现是否重复；
- Material Symbols 是否因 keep 规则而保留了未使用图标；
- `profileinstaller` 在当前非标准安装/注入场景中是否产生实际收益。

只有在功能回归测试覆盖后才能删除或替换依赖。

### 6.4 资源优化

- 将无透明需求的 JPG/PNG 重新编码为质量可接受的 WebP；
- 删除重复主题预览和可由代码生成的静态资源；
- 保持中文 locale 筛选；
- 为 assets 建立来源和消费者清单；
- 禁止把开发期文档、映射数据库或 SDK 数据无条件打入 release，除非运行时入口确实使用。

阶段二的主要目标是把 DEX 降至 25.5 MB 附近，并清除不必要的大型 assets。若仅靠精确 keep 已达到总包预算，不继续做高风险依赖替换。

## 7. 阶段三：feature 懒实例化与启动调度

### 7.1 新的生成结构

KSP 不再生成立即构造全部单例的 `List<BaseFeature>`，改为生成轻量 `FeatureDescriptor`：

```kotlin
data class FeatureDescriptor(
    val id: String,
    val name: String,
    val categories: List<String>,
    val isApi: Boolean,
    val defaultEnabled: Boolean,
    val hasDexResolver: Boolean,
    val factory: () -> BaseFeature,
)
```

registry 保存 metadata 和按需 factory。`@Feature` 增加 `defaultEnabled` 元数据，现有默认开启项迁移到 annotation，避免为了读取默认值而提前实例化。生成的 factory 在首次创建对象时同步写入 annotation 中的 `name`、`categories`、`description` 和默认状态，与当前初始化语义一致；feature 实例按 `id` 缓存，保证对象身份与当前单例列表一致。

### 7.2 启动策略

首屏前只实例化：

1. API 基元；
2. 根据可用 WePrefs 或 `FeatureSwitchSnapshot` 判断为已启用的 early feature；
3. ActivityProxy、设置入口等启动链必需项。

其余 feature：

- 首帧后批量实例化并启动已启用项；
- 打开设置页面时按需创建对应 UI model；
- Dex 修复只创建需要扫描的 feature；
- 用户在运行期启用尚未创建的 feature 时，factory 创建实例、恢复缓存并启动。

初期不引入新的进程声明体系，继续使用现有 `startup()` / `TargetProcesses` 语义，避免一次重构两个维度。等懒实例化稳定后，再单独评估把进程范围加入 annotation metadata。

### 7.3 ParcelableFixer 延迟安装

将 `ParcelableFixer` 拆为：

- 低成本的 `HybridClassLoader` 创建；
- 8 个 Intent getter hook 的安装。

启动阶段只准备 classloader。`ActivityProxy` 在确认某个 `startActivity` 目标是模块 Activity、生成 token wrapper 之前，同步、幂等地安装 Intent hook，然后继续现有代理流程；`ProxyInstrumentation` 保留一次兜底检查。这样避免每次微信启动都支付 92–291 ms，而不改变模块 Activity 的 Parcelable 行为。

### 7.4 Hchat 与重型后台系统

Hchat、脚本环境、MCP/Ktor server、地图和媒体工具不得因类被打包就自动初始化。它们只在以下条件之一满足时启动：

- 对应功能已启用；
- 用户进入相关设置页面；
- 对外 API 明确请求该服务。

已有功能开关和持久化状态继续作为唯一真相，不引入第二套配置系统。

## 8. 阶段四：native 拆分与按需加载

### 8.1 库边界

保持 `libwcx_zygisk.so` 不变，将 `libwekit_native.so` 拆为：

- `libwekit_core.so`：崩溃处理、必要 JNI、签名与启动期能力；
- `libwekit_media.so`：音频解码/编码、Silk、Markdown（若仍保留 native 实现）、Lottie、GIF、WebM、Telegram sticker 转换。

`NativeLoader.init()` 只加载 core 和 MMKV。以下库改为消费者首次使用时加载：

- DexKit；
- Silk/media；
- `androidx.graphics.path`；
- 其它仅属于脚本或媒体功能的 native artifact。

所有按需加载入口使用同一幂等状态机，避免多个线程重复 `System.load`。

### 8.2 体积处理

拆库本身主要改善启动和常驻内存，不把它计作包体积收益。体积收益来自：

- 合并重复 Silk 实现；
- 在保留现有 Markwon/native 可选模式的前提下减少 Markdown 重复实现；若无法等价则不合并；
- 对 media crate 按真实功能关闭未使用 codec feature；
- 对 Rust 依赖做 `cargo bloat`/ELF section 分析后移除未用格式；
- 保持 release 的 `opt-level="z"`、LTO、单 codegen unit、strip 和 panic abort。

## 9. 错误处理和回退

- R8 契约测试失败：阻止产物发布；
- 懒 factory 创建失败：只禁用对应 feature，记录精确错误，不影响其它 feature；
- deferred native 加载失败：仅对应功能不可用，不让微信进程退出；
- 启动链所需 core native 加载失败：沿用现有明确失败日志和安全退出路径；
- 不用宽泛 `catch(Throwable)` 隐藏 hook 安装错误；
- 每个阶段保留独立提交，可单独 revert。

## 10. 验证矩阵

### 10.1 构建与静态检查

- standard/legacy release；
- arm64-v8a/armeabi-v7a；
- baseline 与 `--wekite-hook` 实验构建；
- APK/DEX/native 预算；
- R8 mapping、seeds、usage 和动态 ABI 契约；
- Rust workspace tests 与 Android target build；
- APK 内 JNI/native/feature registry 完整性。

### 10.2 真机性能

在稳定 APK 基线可连续通过后执行：

- boot 6 次；
- direct 6 次；
- 统计 native publication、classloader、gate、LSPlant、StartupAgent、early feature、首帧后 feature 的 P50/P95；
- 记录主进程首帧时间、Java/native heap、PSS 和线程数；
- 对比日志数量与 dropped record。

### 10.3 功能回归

至少覆盖：

- 设置入口和模块 Activity Intent/Parcelable；
- 主进程常用 early feature；
- DexKit 缓存命中、失效和重新适配；
- Hchat/BeanShell 脚本加载与 snapshot；
- MCP/Ktor 服务；
- 地图；
- Markdown；
- 音频/Silk；
- Telegram sticker/Lottie/GIF/WebM；
- standard/legacy Xposed 入口；
- Zygisk 主进程、`:push`、`:appbrand*`。

验收要求：unexpected WCX E=0，无新增 Java/native crash、reason=5、status=6/11 或 recovery。

## 11. 预期收益与决策门

| 阶段 | 预期收益 | 决策门 |
| --- | --- | --- |
| 基线与预算 | 无直接收益 | 报告可重复、同一 APK 结果稳定 |
| 精确 R8/依赖/资源 | 主要体积收益，附带类加载改善 | APK/DEX 明显下降，动态 ABI 全通过 |
| feature 懒实例化 | 主线程初始化和 Java heap 下降 | P50 至少改善 15%，功能行为一致 |
| native 按需加载 | 启动 I/O、链接耗时和 PSS 下降 | 首屏前未映射 media 库，媒体功能首次使用正常 |

若阶段二已把 APK 降至 36 MB 以下，后续不为追求更小数字删除功能。若阶段三或四未达到可测收益，则回退该阶段，不保留只增加复杂度的架构。

## 12. 非目标

本轮不做：

- 全面切换主进程到 WeKite；
- 修改 libaff 等待时间或首 Activity gate 语义；
- 拆成精简版/完整版；
- 在线下载功能包；
- 删除现有功能；
- 用压缩 DEX/native 库换取表面 APK 体积；
- 顺手重写无关 feature。
