# 上游同步指引

本分支与上游 WCX `master` 的差异分三类。同步时按类处理，冲突面可控。

## A. 全新文件（不会冲突）

- `zygisk/`（native loader、backend 记录、模块模板、打包器、测试、文档）
- `app/src/zygisk/`（Zygisk 专用 manifest / 入口 / 桥接 / Xposed 兼容层 / 库隐藏实现）
- `app/src/xposed/assets/xposed_init`（从 `app/src/main/assets/` 迁出）
- `app/src/main/java/com/Johnny/wcx/loader/utils/NativePayloadSource.kt`、`loader/abc/IModuleLibraryHider.kt`、`loader/abc/ModuleLibraryHiding.kt`

上游若也在这些路径新增文件才会冲突；概率极低。

## B. 就地修改的上游文件（同步时需手工合并）

按「冲突可能性」排序，并给出合并要点。

| 文件 | 我们的改动 | 合并要点 |
| --- | --- | --- |
| `app/build.gradle.kts` | 约 10 行：`wcxZygisk` 属性开关；`if` 应用 `zygisk/payload.gradle`，否则把 `src/xposed/assets` 加进两个 flavor 的 assets | 上游若改 `sourceSets`/flavor 定义，保留我们的 `if/else` 分支即可；其余逻辑都在 `zygisk/payload.gradle`（Groovy，动态类型） |
| `app/src/main/java/com/Johnny/wcx/loader/utils/NativeLoader.kt` | 改为按需加载 + 支持 payload 库来源（`NativePayloadSource`） | 上游若改库列表或 MMKV 初始化，把新增库名同步进 `ensureLibrariesLoaded` 与 `mmkvLibLoader` |
| `app/src/main/java/com/Johnny/wcx/loader/utils/HybridClassLoader.kt` | `moduleClassLoader` 字段 + 反射调 `ClassLoader.findClass`（避免父委派递归）+ 线程内重入保护 | 上游若重写 `findClass`，务必保留「不经父委派查 payload loader」和重入保护 |
| `app/src/main/java/com/Johnny/wcx/loader/startup/UnifiedEntryPoint.kt` | 检测 `InMemoryDexClassLoader` 时登记 `HybridClassLoader.moduleClassLoader`（约 6 行） | 上游若改入口签名，保留该分支 |
| `app/src/main/java/com/Johnny/wcx/loader/startup/XposedStartup.kt` | 1 行：`ModuleLibraryHiding.hideIfSupported(hookBridge)` | 上游若重构启动顺序，把这一行放在 `StartupAgent.initializeHost` 之后、`WeLauncher.init` 之前 |
| `app/proguard-rules.pro` | 末尾 8 行：保留 Zygisk 入口/桥接/兼容层类 | 纯追加，冲突时保留追加块 |
| `Cargo.toml` | `zygisk/native` 加入 workspace members | 一行 |
| `.gitmodules` | 新增 `third_party/lsplant`、`third_party/xz-embedded` | 追加即可 |
| `.gitignore` | `dist/`、`.worktrees/`、`__pycache__/`、`/zygisk/dist/` | 追加即可 |
| `Cargo.lock` | workspace 成员变化导致重解 | 直接以构建机生成的为准 |

## C. 上一轮（zygisk 分支既有）对启动链的改造

以下改动早于 Zygisk 运行时移植，属于该分支既定设计，同步时同样需要留意：

- `StartupAgent.kt`：拆出框架无关的 `prepare` / `initializeHost`
- `UnifiedEntryPoint.kt`：改为通过 `IHookBridge` 注册生命周期回调（不再直连 `XposedBridge`）
- `ModuleLoader.kt`：向两个入口注入 `hookBridge`
- `ModuleLoadStatus.kt`、`StartupInfo.kt`、`ModuleHealthScreen.kt`：启动状态诊断
- `WeLauncher.kt`：1 行（hchat 引导）

## 同步流程建议

```bash
git fetch upstream master
git merge upstream/master      # 只在 B/C 类文件上可能冲突
sh zygisk/native/prepare-deps.sh
sh zygisk/native/build.sh dist/zygisk
bash gradlew :app:assembleLegacyRelease -PwcxZygisk=true
cp app/build/outputs/apk/legacy/release/app-legacy-arm64-v8a-release.apk dist/zygisk/payload.apk
bash gradlew :app:assembleStandardRelease :app:assembleLegacyRelease   # 普通变体回归
python3 -m unittest discover -s zygisk/tests
python3 zygisk/package_zip.py pack --apk dist/zygisk/payload.apk \
  --loader dist/zygisk/zygisk/arm64-v8a.so --manager kernelsu \
  --output dist/wcx-zygisk-kernelsu.zip
```

合并后先跑 `-PwcxZygisk=true` 的 payload 构建：它能同时验证 A 类新增文件与 B 类的 loader 改动；再跑不带属性的普通变体，确认 `assets/xposed_init` 与 `META-INF/xposed/*` 仍在。
