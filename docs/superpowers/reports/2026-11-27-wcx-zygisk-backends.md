# WCX Zygisk：独立 Java hook 后端验证

**结论：未找到符合首版约束、且可替代当前 Xposed 运行时的后端。** 公共 Zygisk 回调已在 KernelSU 测试机跑通，但这一结果不覆盖 Java 方法拦截、WCX 启动或 Magisk 验收。

## 候选对比

| 候选 | 已核实 | 决定 |
| --- | --- | --- |
| [公共 Zygisk API v5](https://github.com/topjohnwu/zygisk-module-sample/blob/8ce26128f81baaed0b969aaf7f52f886b61af4ab/module/jni/zygisk.hpp) | 提供生命周期回调及 JNI/PLT 拦截，没有任意 Java 方法与构造器的 hook 接口。 | 保留为 loader 的模块入口，不当作 `IHookBridge` 后端。 |
| [Android ART TI / JVMTI](https://source.android.com/docs/core/runtime/art-ti) | 官方仅支持附加到应用开发者启用调试的进程。ARM64 / Android 16 实机对照验证相符。 | 只用于自有可调试应用，不能作为 WCX 正式版的后端。 |
| [LSPlant](https://github.com/LSPosed/LSPlant/blob/3a240637e621677457c202150100d3ebcab924c6/README.md) | LGPL-3.0；公开接口记录了方法 hook、解除和去优化，初始化仍需调用方提供原生 inline hook 和 ART 内部符号解析。 | 未集成、未实测。它不是现成的 `IHookBridge`，也没有在本项目的兼容性及不绕过安全检查的约束下通过验证。 |

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

1. `UnifiedEntryPoint` 在发布 `StartupInfo.hookBridge` 之前就通过 `HookUtils` 调用 `XposedBridge`；仅提供一个 `IHookBridge` 实例不够。`WeLauncher` 后续也直接使用 Xposed 原始调用。仅有 `compileOnly` 的 API JAR 不提供运行时实现。
2. `NativeLoader`、资源访问和设置页入口都要独立验证。Zygisk 路径不得运行现有 Xposed 专属的隐藏 API 豁免、保护目录删除或反检测逻辑，不改普通 APK 行为。
3. 后端须先在自有测试应用验证方法与构造器、before/after 及优先级、参数和结果变更、异常、原始调用与解除。未满足前不生成或标记可用于 WCX 的 payload/ZIP。
4. Windows ADB 当前仅有 ARM64 / Android 16 的 KernelSU 设备，未发现 Magisk CLI。Magisk 验收保持待办；不能把 KernelSU 结果作为 Magisk 的通过证据。
