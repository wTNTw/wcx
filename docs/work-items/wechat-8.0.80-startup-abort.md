# 微信 8.0.80：启动期 SIGABRT / 卡启动页 的定性结论

排查时间：2026-11-14。设备：Android 16 / arm64-v8a / 微信 8.0.80（versionCode 3200）。
现象：微信**卡在启动页**（系统判 ANR）、后台切回前台偶发冻住、每天 4-5 次进程消失。

## 崩溃签名（反复出现，11-11 起每天 4-8 次）

```
bionic abort
 ← art::Runtime::Abort
 ← libbase LogMessage
 ← art::Thread::AssertNoPendingExceptionForNewException
 ← art::Thread::ThrowNewException
 ← art::JNI::GetMethodID
 ← libowl.so   owl::v9::HookGetMethodID          ← 微信自己的 JNI hook 库
 ← libaff_biz.so  JNI_OnLoad                      ← 微信自己的业务库
 ← art::JavaVMExt::LoadNativeLibrary  (System.load → Runtime.load0)
```

机制：`System.load` 持有 `Runtime.class` 监视器；这条线程在 `libaff_biz` 的 `JNI_OnLoad`
里被 `libowl` 的 `GetMethodID` 包装器触发 ART 断言后进入 abort 流程（要 dump 全部线程），
主线程等 `Runtime.class` 锁 → 界面冻住 → ANR → 进程被杀。**所以「卡启动页」和「SIGABRT」
是同一件事的两个阶段。**

## A/B 结论（同一台设备、同一版本）

| 配置 | 结果 |
|---|---|
| 停用模块（不注入） | 不卡（用户确认） |
| 注入 + 全部功能 hook | 卡死 / 每天 4-8 次 SIGABRT |
| 注入 + 零功能 hook（`debug.wcx.no_hooks=1`） | 仍卡死 |
| 注入 + **不初始化 LSPlant/Dobby**（编译期 `WCX_NO_LSPLANT_BUILD=true`） | 连续 1 小时不卡、不自重启 |

⇒ 与**功能 hook 无关**，问题出在 **LSPlant/Dobby 这一层 ART inline hook**
（它改 `ArtMethod`/`DexFile` 等底层结构，与微信 `libowl` 的 JNI hook 互相踩）。

## 探针开关（临时，验证完要删）

- `debug.wcx.no_hooks=1`（`DebugFlags.noHooks` + `WeLauncher` 提前返回）：只加载 payload，
  不装任何功能 hook、不引导 Hchat。
- `WCX_NO_LSPLANT_BUILD`（`wcx-zygisk/native/src/lifecycle.rs` 的编译期常量）：跳过
  `art::init` + `trust_class_loader`，因此 Java 入口会以
  `LSPlant bootstrap did not complete` 失败、功能整体不可用 —— 这是探针形态，不是正常构建。

> 注意：运行期判断不可行 —— `postAppSpecialize` 阶段仍在 **zygisk SELinux 域**，
> 读不到 `debug.*` 系统属性，也读不到 app 私有目录里的标记文件。只能编译期决定。

## 真修复方向（未实施）

把 LSPlant 初始化**推迟到微信加载完 `libaff_biz` 之后**（例如首个 Activity 起来后），
再回头初始化 LSPlant 并装功能 hook。已知风险：

1. LSPlant 的初始化契约要求「在 native Zygisk 回调里、成为 Java 帧调用者之前」执行，
   延迟到 Java 帧里可能被 hidden API 限制挡住；
2. 启动期相关的 hook（`attachBaseContext` 级）会晚到，需要在推迟的前提下单独处理；
3. 每个进程都要重新判断「`libaff_biz` 是否已加载」，避免在子进程里卡住启动。

备选（若推迟不可行）：主进程推迟、子进程仍早期初始化；或评估其它 hook 引擎。
