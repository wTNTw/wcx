import java.io.File
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.google.devtools.ksp)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.aboutlibraries)
    alias(libs.plugins.aboutlibraries.android)
}

// Source ZIPs often do not include .git metadata. Do not make a clean export
// impossible to build just because the repository history is unavailable.
fun gitValue(vararg args: String): String? = runCatching {
    providers.exec {
        commandLine("git", *args)
        isIgnoreExitValue = true
    }.let { result ->
        if (result.result.get().exitValue == 0) result.standardOutput.asText.get().trim().takeIf { it.isNotEmpty() }
        else null
    }
}.getOrNull()

fun getCommitCount(): Int = gitValue("rev-list", "--count", "HEAD")?.toIntOrNull() ?: 0

fun getGitHash(): String = gitValue("rev-parse", "--short", "HEAD") ?: "source-zip"

android {
    namespace = libs.versions.namespace.get()
    compileSdk {
        version = release(libs.versions.compileSdk.get().toInt()) {
            minorApiLevel = libs.versions.compileSdkMinor.get().toInt()
        }
    }
    ndkVersion = libs.versions.ndk.get()

    val commitCount = getCommitCount()
    val gitHash = getGitHash()

    // BUILD_TIMESTAMP 必须是稳定的：以前用 System.currentTimeMillis()，导致生成的
    // BuildConfig 每次配置都变 → generate*BuildConfig 永不 up-to-date，Kotlin/Java 增量
    // 编译每次都要重编 BuildConfig 及其下游，配置缓存也无法复用。
    // 默认取最后一次提交时间（毫秒），可用 -PwekitBuildTimestamp=<epochMillis> 覆盖。
    val buildTimestamp: Long = providers.gradleProperty("wekitBuildTimestamp").orNull?.toLongOrNull()
        ?: gitValue("log", "-1", "--format=%ct")?.toLongOrNull()?.times(1000)
        ?: 0L

    defaultConfig {
        applicationId = libs.versions.namespace.get()
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        // 版本号单一数据源：CI（.github/workflows/ci.yml）直接读取此处，
        // 发版时只需在此递增 versionCode / versionName
        versionCode = 347
        versionName = "v347"

        buildConfigField("String", "COMMIT_HASH", "\"${gitHash}\"")
        buildConfigField("String", "TAG", "\"WCX\"")
        buildConfigField("long", "BUILD_TIMESTAMP", "${buildTimestamp}L")
        buildConfigField("boolean", "BEAUTIFY_ENABLED", "true")
    }

    // -Pwekit.abi=arm64-v8a[,armeabi-v7a]：只打指定 ABI 的包。
    // 本地调试只需要自己设备那一套 .so，砍掉多余 ABI 就省掉一整套 资源/dex/打包/对齐/签名，
    // 以及一半的 .so 拷贝与 strip。缺省仍是出齐两个 ABI（CI / 发版行为不变）。
    val abiSplits: List<String> = (providers.gradleProperty("wekit.abi").orNull ?: "")
        .split(',', ' ', ';')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .ifEmpty { listOf("arm64-v8a", "armeabi-v7a") }

    splits {
        abi {
            reset()
            isEnable = true
            include(*abiSplits.toTypedArray())
            isUniversalApk = false
        }
    }

    // Two entry-point variants:
    //  - standard: ships the modern libxposed entry point (entry/lxp/* sources +
    //              META-INF/xposed/*), placed in the `standard` flavor source set.
    //  - legacy:   omits both, so frameworks with poor libxposed compatibility fall
    //              back to the traditional de.robv entry (Xp51HookEntry via
    //              assets/xposed_init, which lives in `main` and is shared by both).
    flavorDimensions += "entrypoint"
    productFlavors {
        create("standard") {
            dimension = "entrypoint"
            // ships the libxposed entry point (entry/lxp/* + META-INF/xposed/*)
            buildConfigField("boolean", "HAS_LIBXPOSED_ENTRY", "true")
            buildConfigField("String", "FLAVOR_SLUG", "\"standard\"")
        }
        create("legacy") {
            dimension = "entrypoint"
            // no libxposed entry; framework falls back to the de.robv api
            buildConfigField("boolean", "HAS_LIBXPOSED_ENTRY", "false")
            buildConfigField("String", "FLAVOR_SLUG", "\"legacy\"")
        }
    }

    sourceSets["main"].jniLibs.directories += "src/main/jniLibs"

    var foundKeystore = false

    // ─── 签名配置 ──────────────────────────────────────────────────────────
    // 签名优先级（后续构建默认即本机 WCX 正式签名，无需每次手设环境变量）：
    //   1. 环境变量 WEKIT_KEYSTORE_FILE/WEKIT_KEYSTORE_PASSWORD/WEKIT_KEY_ALIAS/WEKIT_KEY_PASSWORD
    //      （CI / 显式指定时优先）
    //   2. gradle 属性同名 key
    //   3. 默认 keystore：~/wcx-keystore.jks → ~/.wcx/wcx-keystore.jks → <rootProject>/wcx-keystore.jks
    //      （alias=wcx，store/key 密码 wcx-store-pass，与历史交付 APK 一致）
    //   4. 都找不到 → release 不签名（已禁用 debug 兜底；用户要求永远用仓库固定签名，
    //      由 CI 的「Verify APK signatures」步骤校验产物签名）
    // ────────────────────────────────────────────────────────────────────────
    @Suppress("LocalVariableName")
    signingConfigs {
        fun resolveProp(name: String): String? =
            System.getenv(name) ?: runCatching { project.property(name) }.getOrNull() as? String?

        val home = System.getProperty("user.home") ?: ""
        val defaultStore = listOf("$home/wcx-keystore.jks", "$home/.wcx/wcx-keystore.jks")
            .firstOrNull { File(it).exists() }
            ?: rootProject.file("wcx-keystore.jks").takeIf { it.exists() }?.absolutePath

        val _storeFile = resolveProp("WEKIT_KEYSTORE_FILE") ?: defaultStore
        val _storePassword = resolveProp("WEKIT_KEYSTORE_PASSWORD") ?: "wcx-store-pass"
        val _keyAlias = resolveProp("WEKIT_KEY_ALIAS") ?: "wcx"
        val _keyPassword = resolveProp("WEKIT_KEY_PASSWORD") ?: "wcx-store-pass"

        val storeExists = _storeFile != null && File(_storeFile).exists()
        if (storeExists) {
            create("release") {
                foundKeystore = true
                storeFile = file(_storeFile)
                storePassword = _storePassword
                keyAlias = _keyAlias
                keyPassword = _keyPassword

                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName(if (foundKeystore) "release" else "debug")
        }

        release {
            isMinifyEnabled = !project.hasProperty("disableMinify")
            isShrinkResources = !project.hasProperty("disableMinify")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 用户要求：release 永远使用固定签名，禁止回退 debug keystore
            // （避免产出无法覆盖安装的包）。未找到固定签名时不设 signingConfig
            // （产物未签名），并由 CI 的「Verify APK signatures」步骤兜底拦下。
            if (foundKeystore) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                logger.warn("未找到固定签名 keystore：release 将不签名（已禁用 debug 兜底）。请检查 WEKIT_KEYSTORE_FILE 或项目根 wcx-keystore.jks。\n")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.jdk.get().toInt())
        targetCompatibility = JavaVersion.toVersion(libs.versions.jdk.get().toInt())
    }

    packaging {
        // 严禁开启 jniLibs.useLegacyPackaging：LSPosed 在微信进程内通过 System.loadLibrary
        // 从模块 APK 直接 mmap 加载 .so，该机制只对 STORED(未压缩) 生效。开启后 .so 变
        // DEFLATED，System.loadLibrary 抛 UnsatisfiedLinkError → NativeLoader 类初始化失败
        // → 整个模块在初始化阶段崩溃，微信里看不到任何模块痕迹，LSPosed 日志也无记录。
        resources.excludes += listOf(
            "kotlin/**",
            "**.bin",
            "kotlin-tooling-metadata.json",
            "META-INF/INDEX.LIST"
        )
        resources.merges += listOf(
            "META-INF/io.netty.versions.properties",
            "META-INF/xposed/*",
            "org/mozilla/javascript/**"
        )
    }

    @Suppress("UnstableApiUsage")
    androidResources {
        localeFilters += setOf("zh")
    }

    buildFeatures {
        resValues = false
        compose = true
        buildConfig = true
    }

    lint {
        // 默认 locale values/ 仅有少量 key，完整字符串位于 values-zh-rCN；
        // androidResources.localeFilters=zh 打包时只保留中文资源，ExtraTranslation 属误报。
        disable += "ExtraTranslation"
        // 本地快速构建：跳过 release 的 lint vital 分析（这一步实测要 8 分钟）。
        // CI 里不带 -Pfast，仍然照常检查。
        checkReleaseBuilds = !fastBuild
    }
}

/**
 * 本地快速构建开关：`./gradlew packageZygiskApk -Pfast`
 *
 * 只影响「出包速度」，不影响产物行为：
 * - 跳过 release 的 lint vital（实测 8m25s）；
 * - 跳过 Compose 编译映射（produce/mergeComposeMapping，实测约 1m40s）。
 * CI 与正式发布不带这个开关，检查项保持完整。
 */
val fastBuild = providers.gradleProperty("fast").isPresent
if (fastBuild) {
    // Compose 编译映射（约 1m40s）只在做 Compose 工具链分析时需要
    tasks.matching { it.name.contains("ComposeMapping") }.configureEach {
        enabled = false
    }
    // release 的 lint vital 分析（实测 9 分钟）：直接禁任务，比 lint.checkReleaseBuilds 可靠
    tasks.matching { it.name.startsWith("lintVital") || it.name.startsWith("lintAnalyze") }
        .configureEach { enabled = false }
}

tasks.withType<KotlinCompile> {
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(libs.versions.jdk.get()))
    }
}

val adbProvider = androidComponents.sdkComponents.adb
androidComponents {
    onVariants { variant ->
        val kotlinSources = variant.sources.kotlin ?: return@onVariants

        kotlinSources.addGeneratedSourceDirectory(
            generateMethodHashes,
            GenerateMethodHashesTask::getOutputDir
        )

        val variantName = variant.name.replaceFirstChar { it.uppercase() }
        val embedAboutLibraries = tasks.register<EmbedAboutLibrariesTask>("embedAboutLibraries$variantName") {
            group = "wekit"
            description = "Embed aboutlibraries.json as a String constant for $variantName"

            val aboutLibrariesJson = layout.buildDirectory.file("generated/aboutLibraries/${variant.name}/res/raw/aboutlibraries.json")
            inputFile.set(aboutLibrariesJson)
            outputDir.set(layout.buildDirectory.dir("generated/source/aboutlibraries/${variant.name}"))
            namespace.set(libs.versions.namespace.get())
        }

        embedAboutLibraries.configure {
            dependsOn(tasks.named("prepareLibraryDefinitions$variantName"))
        }

        kotlinSources.addGeneratedSourceDirectory(
            embedAboutLibraries,
            EmbedAboutLibrariesTask::getOutputDir
        )

        val embedEruda = tasks.register<EmbedErudaTask>("embedEruda$variantName") {
            group = "wekit"
            description = "Embed eruda.min.js as a String constant for $variantName"

            url.set("https://cdn.jsdelivr.net/npm/eruda@3.4.3/eruda.min.js")
            outputDir.set(layout.buildDirectory.dir("generated/source/eruda/${variant.name}"))
            namespace.set(libs.versions.namespace.get())
        }

        kotlinSources.addGeneratedSourceDirectory(
            embedEruda,
            EmbedErudaTask::getOutputDir
        )

//        val embedMonetAssets = tasks.register<EmbedMonetAssetsTask>("embedMonetAssets$variantName") {
//            group = "wekit"
//            description = "Embed Monet overlay templates/tables as byte-array constants for $variantName"
//
//            inputDir.set(layout.projectDirectory.dir("embedded/monet"))
//            outputDir.set(layout.buildDirectory.dir("generated/source/monet/${variant.name}"))
//            namespace.set(libs.versions.namespace.get())
//        }
//
//        kotlinSources.addGeneratedSourceDirectory(
//            embedMonetAssets,
//            EmbedMonetAssetsTask::outputDir
//        )
    }
}

// --- tasks ---

val generateMethodHashes = tasks.register<GenerateMethodHashesTask>("generateMethodHashes") {
    description = "Generate resolveDex() method hashes"
    group = "wekit"
    sourceDir.set(file("src/main/java"))
    outputDir.set(layout.buildDirectory.dir("generated/source/methodhashes"))
    namespace.set(libs.versions.namespace.get())
}

// ─── Zygisk 一体包（APK 兼作可刷入模块）────────────────────────────────────
// 注入模块条目（module.prop / customize.sh / uninstall.sh / META-INF 安装器）后重签。
// 注入器本体作为 lib/arm64-v8a/libwcx_zygisk.so 随 APK 打包（由 cargo 构建后放进 jniLibs），
// 安装时 customize.sh 把它搬到 /zygisk/arm64-v8a.so，并把整包复制成 module.apk。
val packageZygiskApk = tasks.register<PackageZygiskApkTask>("packageZygiskApk") {
    description = "把 release APK 变成「可安装 APK + 可刷模块包」一体包 (arm64-v8a)"
    group = "wekit"

    dependsOn("assembleStandardRelease")

    apkDir.set(layout.buildDirectory.dir("outputs/apk/standard/release"))
    templateDir.set(rootProject.layout.projectDirectory.dir("wcx-zygisk"))
    outputDir.set(layout.buildDirectory.dir("outputs/zygisk"))
    rootProjectDir.set(rootProject.projectDir.absolutePath)
    versionName.set(android.defaultConfig.versionName ?: "v0")
    versionCode.set(android.defaultConfig.versionCode ?: 0)
    moduleAuthor.set("wcx-dev")
    abi.set("arm64-v8a")
}

// assembleRelease 跑完后再串一步：finalizedBy 不会造成任务循环
tasks.matching { it.name == "assembleRelease" }.configureEach {
    finalizedBy(packageZygiskApk)
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.android.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.browser)
    implementation(libs.aboutlibraries.core)
    implementation(libs.aboutlibraries.compose.m3)
    implementation(libs.androidx.profileinstaller)
    // miuix 0.9.4 内置 aar（对齐 Hchat 上游 app/libs；maven 0.9.3 API 与上游代码不兼容）
    implementation(files("libs/miuix-ui-android-0.9.4.aar"))
    implementation(files("libs/miuix-core-android-0.9.4.aar"))
    implementation(files("libs/miuix-squircle-android-0.9.4.aar"))
    implementation(files("libs/miuix-preference-android-0.9.4.aar"))
    implementation(files("libs/miuix-icons-android-0.9.4.aar"))
    implementation(files("libs/miuix-nav-android-0.9.4.aar"))
    implementation(files("libs/miuix-blur-android-0.9.4.aar"))
    implementation(files("libs/miuix-shader-android-0.9.4.aar"))
    implementation(libs.materialkolor)
    implementation(libs.coil)
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.coil.network.okhttp)

    implementation(libs.composablehorizons.material.symbols.filled)
    implementation(libs.composablehorizons.material.symbols.outlined)
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)

    implementation(libs.google.protobuf.javalite)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.serialization.protobuf)
    implementation(libs.mmkv)
    // Hchat 脚本子系统依赖（迁移）
    // Hchat 设置浮层渲染依赖 lifecycle-viewmodel-compose（LocalViewModelStoreOwner），
    // 缺了会 NoClassDefFoundError 导致脚本页打不开（上游 build.gradle 里有这两项）
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.savedstate:savedstate:1.2.1")
    implementation("io.github.billywei01:fastkv:3.0.1")
    implementation("com.google.flatbuffers:flatbuffers-java:23.5.26")
    implementation("dev.rikka.ndk.thirdparty:cxx:1.2.0")
    // 内置 ARSCLib：JitPack 在该构件上经常 30s+ 才响应，CI 里会直接判成 Could not find。
    // 该库无传递依赖（POM 只有自身坐标），内置后构建可复现。升级时手动替换 jar 即可。
    implementation(files("libs/ARSCLib-V1.3.8.jar"))
    implementation("io.github.skylot:jadx-dex-input:1.5.5") {
        exclude(group = "com.google.guava", module = "guava")
    }
    implementation("com.google.guava:guava:33.5.0-android")
    implementation("com.android.tools.smali:smali-baksmali:3.0.9")
    implementation("com.highcapable.kavaref:kavaref-core:1.1.0")
    implementation("com.jakewharton.android.repackaged:dalvik-dx:16.0.1")
    implementation("com.alibaba.fastjson2:fastjson2:2.0.61.android8")
    implementation("androidx.navigationevent:navigationevent:1.1.2")
    implementation("androidx.navigationevent:navigationevent-compose:1.1.2")
    implementation("org.jetbrains.kotlinx:kotlinx-collections-immutable:0.5.1")

    // 内置 Xposed API 82：Maven Central / 腾讯镜像都没有该构件，只能从 api.xposed.info 拉，
    // 而该源在 CI 上偶发超时会导致整个 master 构建失败（缓存丢失时必现）。内置后构建可复现。
    compileOnly(files("libs/xposed-api-82.jar"))
    compileOnly(libs.libxposed.api)
    implementation(libs.libxposed.service)
    implementation(files("libs/dexkit-2.2.0-76551eb-idle.aar"))
    implementation(libs.hiddenapibypass)
    implementation(project(":libs:common:reflekt"))
    implementation(libs.libsu.core)
    implementation(libs.dexmaker)
//    implementation(libs.arsclib)
//    implementation(libs.apksig)
//    implementation(libs.bouncycastle.prov)
//    implementation(libs.bouncycastle.pkix)
    @Suppress("AvoidDuplicateDependencies")
    implementation(project(":libs:common:annotation-scanner"))
    @Suppress("AvoidDuplicateDependencies")
    ksp(project(":libs:common:annotation-scanner"))

    implementation(libs.okhttp3.okhttp)
    implementation(libs.jsoup)

    implementation(libs.fastjson2)

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)

    implementation(libs.markwon.core)
    implementation(libs.markwon.ext.strikethrough)
    implementation(libs.markwon.ext.tables)
    implementation(libs.markwon.ext.tasklist)
    implementation(libs.markwon.html)

    implementation(libs.mcp.server)
    implementation(libs.mcp.client)
    implementation(platform(libs.ktor.bom))
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.sse)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.websockets)
    implementation(libs.ktor.serialization.kotlinx.json)

    implementation(libs.osmdroid.android)

    compileOnly(project(":libs:common:stubs"))
}

// markwon conflict
configurations.all {
    exclude(group = "org.jetbrains", module = "annotations-java5")

//    resolutionStrategy {
//        force("androidx.compose.ui:ui:1.12.0-beta01")
//        force("androidx.compose.ui:ui-android:1.12.0-beta01")
//        force("androidx.compose.material3:material3:1.5.0-alpha21")
//        force("androidx.compose.material3:material3-android:1.5.0-alpha21")
//    }
}

tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.add("-opt-in=androidx.compose.material3.ExperimentalMaterial3Api")
        freeCompilerArgs.add("-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi")
    }
}
