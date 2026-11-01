plugins {
    alias(libs.plugins.android.library)
}

/**
 * Zygisk 注入器模块（只出 arm64-v8a）。
 *
 * 产物：
 *   libwcxzygisk.so（AGP 中间产物）→ 打包成模块内的 zygisk/arm64-v8a.so
 *   liblsplant.so（从 AAR 取出）  → 打包成模块内的 zygisk/liblsplant.so
 *
 * 依赖说明见 src/main/cpp/CMakeLists.txt：LSPlant 只取头文件（运行时 dlopen），
 * Dobby 与 libc++ 静态链进模块 .so，保证模块 .so 没有 DT_NEEDED 外部依赖。
 */

android {
    namespace = "com.Johnny.wcx.zygisk"

    compileSdk {
        version = release(libs.versions.compileSdk.get().toInt()) {
            minorApiLevel = libs.versions.compileSdkMinor.get().toInt()
        }
    }
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()

        ndk {
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    // 模块 .so 必须自包含：设备上既没有 libc++_shared.so，
                    // 也不能从 /data/adb 里 dlopen 依赖（sandbox 读不到）
                    "-DANDROID_STL=none",
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
                )
            }
        }
    }

    buildFeatures {
        prefab = true
        buildConfig = false
        androidResources = false
        resValues = false
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    packaging {
        jniLibs {
            // AAR 的 prefab 产物不要打进 jniLibs，packaging 任务会自己取
            excludes += "**/liblsplant.so"
        }
    }
}

dependencies {
    // prefab 原生依赖：Dobby（静态 inline hook）+ libc++（静态、STL=none）
    implementation(libs.ndk.dobby)
    implementation(libs.lsposed.libcxx)
    // 注意：LSPlant 不在这里做 build 期依赖（头文件自带、运行期 dlopen），
    // 只有下面的 lsplantRuntime 配置用于从 AAR 里取出 liblsplant.so 随模块下发。
}

// 单独解析 lsplant-standalone 的 AAR，把 arm64 的 liblsplant.so 取出来随模块下发
val lsplantRuntime: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    lsplantRuntime(libs.lsplant.standalone)
}

val extractLsplantSo = tasks.register<Copy>("extractLsplantSo") {
    group = "wekit"
    description = "从 lsplant-standalone AAR 里取出 arm64 的 liblsplant.so"

    val lsplantAar = lsplantRuntime.elements.map { it.first().asFile }
    from(lsplantAar.map { aar ->
        zipTree(aar).matching {
            include("prefab/modules/lsplant/libs/android.arm64-v8a/liblsplant.so")
        }
    }) {
        eachFile { path = "liblsplant.so" }
        includeEmptyDirs = false
    }
    into(layout.buildDirectory.dir("lsplant-runtime"))
}

/**
 * 打包任务需要的「模块 .so」路径在 AGP 内部是中间产物路径，若未来 AGP 改名，
 * 这里给一个显式的兜底查找，避免打包任务直接失败在路径上。
 */
val exportZygiskSo = tasks.register("exportZygiskSo") {
    group = "wekit"
    description = "导出 Zygisk 模块 .so（找不到时给出明确错误）"
    dependsOn("assembleRelease")

    val buildDir = layout.buildDirectory
    val outDir = buildDir.dir("zygisk-runtime")
    outputs.dir(outDir)
    // 没有声明的输入（.so 由 assembleRelease 产出），禁止 up-to-date 跳过
    outputs.upToDateWhen { false }

    doLast {
        val root = buildDir.get().asFile
        val candidates = root.walkTopDown()
            .filter { it.isFile && it.name == "libwcxzygisk.so" }
            .toList()
        val preferred = candidates.firstOrNull { it.path.contains("stripped_native_libs") }
            ?: candidates.firstOrNull()
            ?: error("未找到 libwcxzygisk.so，请确认 CMake 构建成功（build/ 下应有 libwcxzygisk.so）")

        val target = outDir.get().asFile.resolve("libwcxzygisk.so")
        target.parentFile.mkdirs()
        preferred.copyTo(target, overwrite = true)
        logger.lifecycle("Zygisk 模块 .so: ${preferred.absolutePath} → ${target.absolutePath}")
    }
}
