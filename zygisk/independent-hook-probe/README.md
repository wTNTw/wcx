# 自有 APK 的独立 Hook 探针（原型）

仅在 `dev.wcx.independenthook.probe` 的自有、可调试进程内运行。用 Android 公开的 ART TI / JVMTI 对内置 `Fixture` 验证运行时方法事件和参数变更。不使用 Zygisk、LSPosed、隐藏 API、系统配置更改或其他应用的类。这个目录不进入普通 WCX APK 或模块 ZIP。

## Debian 构建

```bash
export ANDROID_HOME=/opt/android-sdk
bash zygisk/independent-hook-probe/build.sh dist/independent-hook-probe
(cd dist/independent-hook-probe && sha256sum --check SHA256SUMS)
```

构建使用 JDK 21、Android SDK 37.0、NDK 30.0.14904198 及临时自签证书。取回 APK 后，在自有 ARM64 测试设备上运行：

```bash
adb install wcx-independent-hook-probe.apk
adb shell am start -W -n dev.wcx.independenthook.probe/.MainActivity
adb logcat -d -s 'WcxIndependentHook:I' '*:S'
adb uninstall dev.wcx.independenthook.probe
```

## 真机结果

ARM64 / Android 16，测试进程中没有 `XposedBridge`。启用时收到构造器和方法的前后事件；异常正常传播。在自有方法进入时把参数从 `3` 改为 `8`，返回值从 `10` 变为 `15`。关闭订阅后返回值恢复为 `10`，回调计数不再增长。

```text
xposed_bridge=absent
RESULT pass constructors=1/1 entries=2 returns=1 exceptions=1 baseline=10 hooked=15 restored=10 get_local_status=0 set_local_status=0 force_return_cap=1 input_slot=3
```

`force_return_cap` 只是 ART 报告的能力位，未验证强制返回。没有验证回调优先级、原方法调用或非调试版本。非调试 APK 的 attach 拒绝见[后端验证报告](../../docs/superpowers/reports/2026-11-27-wcx-zygisk-backends.md)，因此这个原型不能替代 WCX 发布版的 `IHookBridge`。验证后已卸载 APK，没有重启或改动手机现有模块。
