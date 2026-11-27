# WCX Zygisk 冒烟探针

本工具不构建、不加载 WCX，不针对微信或其他应用。测试 APK `dev.wcx.zygisk.probe` 报告其类加载器能否解析 `XposedBridge`；独立同名探针模块仅对该 APK 的主进程记录公开 Zygisk 生命周期回调。不要把任一结果当成 WCX 运行通过。

## 构建（Debian x86_64）

```bash
export ANDROID_HOME=/opt/android-sdk
bash zygisk/smoke/build.sh dist/zygisk-smoke
bash zygisk/smoke/verify.sh dist/zygisk-smoke
```

需要 JDK 21、SDK 37.0 / Build Tools 37.0.0 和 NDK 30.0.14904198。脚本使用 [Magisk 官方示例 0BSD API 头文件](https://github.com/topjohnwu/zygisk-module-sample/blob/8ce26128f81baaed0b969aaf7f52f886b61af4ab/module/jni/zygisk.hpp)，固定提交并检查 SHA-256；也可通过 `ZYGISK_API_HEADER` 提供同一份文件。测试 APK 使用临时签名，与普通 WCX APK 完全独立。不同构建的临时签名不同，已安装旧探针时先卸载它，勿卸载其他应用。

## 设备验证

1. 安装 `wcx-zygisk-smoke.apk`，启动 `dev.wcx.zygisk.probe/.MainActivity`，查看 `WcxZygiskAppProbe` 是否报告 `probe_app_started xposed_bridge=absent`。这是该测试进程的可见性结果，不代表设备没有启用 LSPosed，也不代表微信进程的状态。
2. 如获设备操作者同意，在支持公共 Zygisk API v5 的管理器里安装 `wcx-zygisk-smoke-module.zip`，按提示重启。重新启动测试 APK，按其当前 PID 过滤 `adb logcat`；预期 `WcxZygiskNativeProbe` 记录 `probe_post_app_specialize`，同一进程的应用探针仍报告 `xposed_bridge=absent`。
3. 用管理器卸载 ID 为 `wcx_zygisk_probe` 的独立测试模块并重启。确认重新启动测试 APK 时不再出现原生回调记录；不改其他模块或作用域。卸载测试 APK 后结束。

KernelSU + Zygisk 的日志可作为补充证据，不能替代 Magisk 上的首版验收。这个探针不提供 Java hook 后端、WCX payload 或发布 ZIP。
