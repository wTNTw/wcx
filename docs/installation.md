# 安装指南

## 下载

本项目不会发布稳定版本, 请从以下渠道下载最新 CI 构建产物 (每夜版):

- [GitHub Actions](https://github.com/Johnny520/wcx/actions/workflows/ci.yml)
- [Telegram 超级群组](https://t.me/+7j5dJ6g16B43OWVl)

[GitHub Releases](https://github.com/Johnny520/wcx/releases) 中会发布"稳定的 CI", 但不保证真的稳定, 且可能无法享受最新功能与修复, 故建议使用每夜版。

## 安装

### Root (以 [LSPosed](https://github.com/JingMatrix/Vector) 为例)

1. 下载模块安装包
2. 安装模块
3. 在 LSPosed 管理器中启用模块, 并勾选微信
   - 若 LSPosed 提示「此模块是为较新的 Xposed 版本设计的, 因此某些功能可能无法使用」, 忽略即可, 不影响模块使用
4. 重启微信

### 免 Root (以 [NPatch](https://github.com/7723mod/NPatch) 为例)

1. 下载模块与 NPatch 管理器安装包
2. 安装模块与 NPatch 管理器
3. 修补微信, 并根据你的需求选择「本地模式」或「集成模式」。若使用「集成模式」, 需在「嵌入模块」界面勾选「WCX」。修补时, 包名必须为 `com.tencent.mm` 或以 `com.tencent.mm` 开头, 且建议启用「注入文件提供器」以方便管理模块 KV 数据。
4. 安装修补后的微信。由于未知原因, 即使修补的包名与已安装应用的包名不一致, NPatch 也会请求卸载已安装应用, 请注意不要误操作导致丢失微信数据。
5. 若使用「本地模式」, 需在修补的微信作用域中启用 WCX。

### Zygisk 模块 zip（不安装 APK, 不需要 LSPosed, 实验性）

> 该形态由模块自带的注入器完成注入, 仍在开发中（见仓库 `zygisk/README.md` 的里程碑）。
> 当前能跑通注入与 hook 桥, 但 feature 层尚未迁移完, 可能看不到实际功能。

1. 确认已启用 Zygisk（Magisk 设置, 或 KernelSU/APatch 的 Zygisk 开关, 或装了 ZygiskNext）
2. 下载 `wcx-*-zygisk-arm64-v8a.zip`（Releases / CI 产物）, 在 Magisk / KernelSU / APatch 里刷入
3. 重启手机, 强制停止微信后重新打开
4. 抓日志确认：`adb logcat -s WCXZygisk`（应看到 `LSPlant 初始化完成` 与 `注入流程结束`）
5. 卸载：在管理器里移除模块后重启

注意：它**不安装 APK**, 因此不会覆盖你已安装的 WCX；两种形态可以同时存在, 但建议二选一以免重复 hook。

## 修复微信热更新导致的模块不加载

如果模块不加载且日志没有报错, 请尝试采取以下步骤。

### Root

1. 授予模块 Root 权限
2. 打开模块应用
3. 右上角三个点菜单 -> 「修复模块加载」 -> 确定
4. 重启微信

### 免 Root (需「注入文件提供器」; 以 MT 管理器为例)

1. 启动 MT 管理器
2. 左上角菜单 -> 右上角三个点菜单 -> 添加本地存储
3. 左上角菜单 -> 微信 -> 使用此文件夹 -> 允许
4. 打开添加的微信, 打开 `./data/`, 删除所有 `tinker` 开头文件夹里的内容
5. 对上述每个文件夹, 依次执行: 长按文件夹 -> 属性 -> 权限 -> 修改 -> 取消打钩全部, 勾选两个「同时应用到所有...」 -> 确定
    - 若打开添加的微信显示的目录为空, 启动微信并重试

## 下一步

- [配置指南](configuration.md) — 了解如何使用和配置功能
