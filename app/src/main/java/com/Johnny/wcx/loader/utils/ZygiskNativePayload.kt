package com.Johnny.wcx.loader.utils

import android.annotation.SuppressLint
import android.os.Build
import android.os.Process
import com.Johnny.wcx.utils.fs.copyFrom
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** Native artifacts from the copied APK; accessed under NativeLoader's load lock. */
class ZygiskNativePayload(val apk: File, private val dataDir: File) {

    /**
     * InMemoryDexClassLoader has no native-library directory on API 28. Match
     * FunBox's workaround: extract packaged libraries into app data, then use
     * absolute System.load paths from this module ClassLoader.
     */
    @SuppressLint("UnsafeDynamicallyLoadedCode")
    fun loadLibraries(): Map<String, File> = ZipFile(apk).use { archive ->
        val abi = currentProcessAbi(archive)
        val nativeRoot = File(dataDir, ".wcx-native")
        val currentDirName = apk.nameWithoutExtension
        val libraryDir = File(nativeRoot, "$currentDirName/$abi")
        if (!libraryDir.exists() && !libraryDir.mkdirs()) {
            error("cannot create Zygisk native-library directory: $libraryDir")
        }
        // 每次换 payload（模块升级）都会有一个新的解包目录，一个版本约 4.4MB。
        // 之前旧的从不清理，实测积累到 99MB/22 个目录，这里只保留当前版本。
        // 正在运行的旧进程的 .so 已 mmap 完成，删除目录不会影响它们。
        pruneNativeExtractionDirs(nativeRoot, currentDirName)
        require(libraryDir.isDirectory) { "Zygisk native-library path is not a directory: $libraryDir" }

        val libraries = mutableMapOf<String, File>()
        // 本仓库实际随 APK 打包的原生库（app/src/main/jniLibs/<abi>/）
        val names = listOf(
            "androidx.graphics.path",
            "dexkit",
            "mmkv",
            "silk_codec",
            "termux",
            "wekit_native",
        )
        for (name in names) {
            val fileName = "lib$name.so"
            val entry = archive.getEntry("lib/$abi/$fileName") ?: continue
            // 冷启动优化：目标已存在且大小一致时直接复用（目录名里带 APK 版本标记，
            // 升级模块后路径会变，自然重新解出）
            val cached = File(libraryDir, fileName)
            if (cached.isFile && cached.length() == entry.size) {
                libraries[name] = cached
                when (name) {
                    "androidx.graphics.path", "dexkit", "wekit_native", "silk_codec" ->
                        System.load(cached.absolutePath)
                }
                continue
            }
            val extracted = extractLibrary(archive, entry, libraryDir, fileName)
            libraries[name] = extracted
            // MMKV loads through its callback; executable artifacts must never be dlopen-ed.
            when (name) {
                "androidx.graphics.path", "dexkit", "wekit_native", "silk_codec" -> System.load(extracted.absolutePath)
            }
        }
        for (name in listOf("dexkit", "wekit_native")) {
            require(name in libraries) { "Zygisk payload is missing lib$name.so for $abi" }
        }
        libraries
    }

    /**
     * 只保留当前 payload 的解包目录，其它版本的目录 / 残留 .tmp 一并清掉（尽力而为）。
     *
     * 注意宽限期：同一个微信里主进程和子进程启动时间不同步，刚解出来的目录可能
     * 还在被别的进程使用（它的 .so 可能还没 dlopen），删早了那个进程会失败。
     */
    private fun pruneNativeExtractionDirs(root: File, keepName: String) {
        val now = System.currentTimeMillis()
        runCatching {
            root.listFiles()?.forEach { entry ->
                if (entry.name == keepName) return@forEach
                if (now - entry.lastModified() < EXTRACTION_GRACE_MS) return@forEach
                // 只处理我们自己生成的目录：wcx-payload-* / .wcx-bootstrap-*
                val managed = entry.name.startsWith("wcx-payload-") ||
                    entry.name.startsWith(".wcx-bootstrap-")
                if (!managed) return@forEach
                if (entry.isDirectory) entry.deleteRecursively() else entry.delete()
            }
        }
    }

    private companion object {
        const val EXTRACTION_GRACE_MS = 10 * 60 * 1000L
    }

    private fun currentProcessAbi(archive: ZipFile): String {
        val candidates = if (Process.is64Bit()) {
            Build.SUPPORTED_64_BIT_ABIS.asList()
        } else {
            Build.SUPPORTED_32_BIT_ABIS.asList()
        }
        return candidates.firstOrNull { abi ->
            archive.getEntry("lib/$abi/libwekit_native.so") != null
        } ?: error("Zygisk payload has no native library for this process ABI")
    }

    private fun extractLibrary(
        archive: ZipFile,
        entry: ZipEntry,
        destinationDir: File,
        fileName: String,
    ): File {
        val destination = File(destinationDir, fileName)
        val temporary = File(destinationDir, "$fileName.${Process.myPid()}.tmp")
        temporary.delete()
        archive.getInputStream(entry).use { input ->
            temporary.toPath().copyFrom(input)
        }
        check(temporary.setReadable(true, true) && temporary.setExecutable(true, true) &&
            temporary.setWritable(false, false)) { "cannot protect Zygisk native library: $temporary" }
        if (!temporary.renameTo(destination)) {
            temporary.delete()
            error("cannot publish Zygisk native library: $destination")
        }
        return destination
    }
}
