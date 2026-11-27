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
