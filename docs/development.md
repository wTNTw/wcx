# 开发

## 1. 克隆仓库

```bash
git clone https://github.com/Johnny520/wcx.git --recursive
```

## 2. 安装系统依赖

### A. Arch Linux

```bash
# 确保已在 /etc/pacman.conf 中启用 multilib 软件源
yay -Syu lib32-glibc rustup
rustup toolchain install stable
rustup default stable
rustup target add x86_64-linux-android aarch64-linux-android armv7-linux-androideabi i686-linux-android
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager "ndk;$(grep '^ndk' ./gradle/libs.versions.toml | sed 's/.*= "\(.*\)"/\1/')"
```

### B. Debian 系

```bash
sudo apt update -y && sudo apt full-upgrade -y
sudo apt install gcc-multilib rustup
rustup toolchain install stable
rustup default stable
rustup target add x86_64-linux-android aarch64-linux-android armv7-linux-androideabi i686-linux-android
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager "ndk;$(grep '^ndk' ./gradle/libs.versions.toml | sed 's/.*= "\(.*\)"/\1/')"
```

### C. Windows

建议全文背诵 [停止用 Windows 工作!](https://zhuanlan.fxzhihu.com/p/2024527609388627701)

## 3. 构建

构建期间会自动编译 Rust 原生库, 无须手动编译

### 本地构建提速

日常改 Kotlin/Java 的循环用 `./x build` (等价 `cargo xtask build`) 或 `./x run`:

```bash
./x build            # debug + 只编 arm64-v8a + 跳过 Zygisk 注入器
./x run --skip-native # Rust 没改时, 直接 Gradle + 安装, 最快的一轮
```

debug 构建默认只做「本机测试需要的那一份」, 省掉一半的原生编译与打包/对齐/签名:

| 开关 | 作用 |
| --- | --- |
| `--abi arm64-v8a` / `--all-abis` | 只编/只打指定 ABI; release 与 `--native-only` 仍是全 ABI |
| `--skip-native` | 跳过 cargo, 只跑 Gradle (纯 Kotlin/Java/资源改动) |
| `--dev-native` | 用 cargo `dev` profile 编 .so (编得快, 运行时慢、体积大) |
| `--zygisk` | 额外编 Zygisk 注入器 (默认只在 release / `--native-only` 编; 它要跑 CMake 拉 LSplant/Dobby, 是最慢的原生步骤) |

直接调 Gradle 时可以自己传对应的属性:

```bash
./gradlew :app:assembleStandardDebug --parallel --build-cache -Pwekit.abi=arm64-v8a
```

- `-Pwekit.abi=arm64-v8a,armeabi-v7a` — 覆盖 ABI split (缺省两个都打)
- `-PwekitBuildTimestamp=<epochMillis>` — 覆盖 `BuildConfig.BUILD_TIMESTAMP` (缺省取最后一次提交时间;
  它必须稳定, 否则生成的 BuildConfig 每次都变, 增量编译和配置缓存全部失效)

仓库 `gradle.properties` 里放的是**本地快速**档 (`org.gradle.parallel=true`、Kotlin 编译走独立
Kotlin daemon 等); CI 内存紧张, 由 `.github/workflows/ci.yml` 写入 `$GRADLE_USER_HOME/gradle.properties`
覆盖回省内存配置 (Gradle 属性优先级: 命令行 > 用户目录 > 仓库), 两边互不影响.

还可以再开配置缓存 (省掉每次构建 10s+ 的配置阶段):

```properties
# ~/.gradle/gradle.properties 或仓库 gradle.properties
org.gradle.configuration-cache=true
```

> 开配置缓存后注意: release 签名是配置阶段读 keystore 文件判断的 (文件存在性不被缓存跟踪),
> 换过 keystore / 新下了 keystore 时先 `--no-configuration-cache` 跑一次, 否则可能拿到旧的签名结果.

> 排查构建变慢时先看 `--profile` 报告 (`app/build/reports/profile/`), 别先怀疑 Gradle 参数:
> 单文件上千行的 Compose UI (`h/Hchat/ui/miuix/MiuixSettingsPage*.kt`) 才是 Kotlin 增量的主要成本.

### 变体 (Flavor)

模块提供两个入口点变体, 通过 `entrypoint` flavor 维度区分:

- **standard**: 包含现代 libxposed api 入口点 (`entry/lxp/*` 与 `META-INF/xposed/*`), 框架会优先使用 libxposed 加载. 大多数用户应使用此变体.
- **legacy**: 移除了 libxposed 入口点与相关元数据, 使框架自动回退到传统 de.robv xposed api (`Xp51HookEntry` + `assets/xposed_init`). 供设备或框架对 libxposed 兼容性差的用户使用.

两个变体共用同一份 `applicationId` 与除入口点外的所有代码资源, 由 Gradle 的 flavor source set 机制自动分离, 无须手动删除文件.

```bash
# 单独构建某个变体
cargo xtask build --release --flavor standard
cargo xtask build --release --flavor legacy

# 一次性构建全部变体 (standard/legacy × debug/release)
cargo xtask build --release
```

产物按 `变体/构建类型` 分目录输出:

```none
app/build/outputs/apk/standard/release/app-standard-arm64-v8a-release.apk
app/build/outputs/apk/legacy/release/app-legacy-arm64-v8a-release.apk
```

## 4. 安装

```bash
# standard 变体
cargo xtask run --release --flavor standard

# legacy 变体
cargo xtask run --release --flavor legacy

# 可选: 应用基准配置 (Baseline Profile)
adb shell cmd package compile -m speed-profile com.Johnny.wcx
```
