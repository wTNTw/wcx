# WCX Zygisk 打包与验收

目前只有自有 APK 探针和 ZIP 预检工具，没有可用于微信的独立 Hook 后端或正式 WCX Zygisk ZIP。普通 `standard` / `legacy` 的构建、签名和入口不变。

## 在 Debian 验证打包

```bash
export ANDROID_HOME=/opt/android-sdk
bash zygisk/smoke/build.sh dist/zygisk-probe
python3 zygisk/package_zip.py pack-probe \
  --apk dist/zygisk-probe/wcx-zygisk-smoke.apk \
  --module dist/zygisk-probe/wcx-zygisk-smoke-module.zip \
  --output dist/zygisk-probe/wcx-zygisk-packaging-probe.zip
python3 zygisk/package_zip.py verify --profile probe \
  dist/zygisk-probe/wcx-zygisk-packaging-probe.zip
```

`pack-probe` 将探针 APK 原样放入 `payload/probe.apk`，保留独立模块 ID `wcx_zygisk_probe`；无论成功与否都不调用 ADB，不安装任何东西。探针 loader 只记录自有测试 APK 的回调，不加载嵌入的 APK，也不包含 WCX 或微信功能。写入前会校验临时 ZIP；失败不覆盖已有产物。

## 正式 ZIP 的前置检查

独立后端通过验收并产出正式 ZIP 后，可以执行：

```bash
python3 zygisk/package_zip.py verify --profile release dist/wcx-zygisk-arm64.zip
```

此命令只读取产物。它检查模块 ID、归档布局和完整性、原生 loader 的 ARM64 ELF 与 Zygisk 入口，以及 payload 的包名、签名、版本、所需 ARM64 库条目和 Xposed 声明。该预检不验证发行者身份、安装脚本行为、后端安全性或真机运行效果，也不产生正式 ZIP。

在构建或安装正式模块之前，须先在自有非调试 APK 上验证独立后端不会绕过安全检查。正式包才进入 KernelSU 安装与回退验收；不以探针或 LSPosed APK 替代。参见[后端验证报告](../docs/superpowers/reports/2026-11-27-wcx-zygisk-backends.md)。
