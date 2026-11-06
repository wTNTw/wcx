package com.Johnny.wcx.loader.utils

import com.Johnny.wcx.utils.WeLogger
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * 读取「模块 APK 自身」的 assets。
 *
 * 为什么需要这个：Zygisk 独立模式下模块 APK 只是被当作 payload 加载，
 * **并没有作为应用安装**（`pm list packages` 里查不到 com.Johnny.wcx），所以
 * `createPackageContext(modulePackage)` 必然失败、`appContext.assets` 又是微信自己的。
 * 之前城市库、内置主题素材都是这么读的，结果全部读不到。
 *
 * 读取顺序：
 *  1. [NativeLoader.bootstrapApk]（Zygisk payload / LSPosed 下的模块 APK）；
 *  2. 已安装模块 APK 的 sourceDir（装了 WCX APK 的情况）；
 *  3. 已安装模块的 `createPackageContext(...).assets`（老路子，兼容 LSPosed 注入）。
 *
 * 注意 ZipFile 的 entry 名要带 `assets/` 前缀，而 AssetManager 的路径不带。
 */
object ModuleApkAssets {

    private const val TAG = "ModuleApkAssets"

    private const val MODULE_PACKAGE = "com.Johnny.wcx"

    /** 依次尝试的模块 APK（去重、只保留可读的）。 */
    private fun apkCandidates(): List<File> {
        val candidates = mutableListOf<File>()
        runCatching { candidates += NativeLoader.bootstrapApk() }
            .onFailure { WeLogger.w(TAG, "bootstrapApk 不可用: ${it.message}") }
        runCatching {
            val app = com.Johnny.wcx.utils.HostInfo.application
            val info = app.packageManager.getApplicationInfo(MODULE_PACKAGE, 0)
            info.sourceDir?.let { candidates += File(it) }
        }.onFailure { WeLogger.d(TAG, "模块未安装（Zygisk 独立模式属正常）: ${it.message}") }
        return candidates.distinctBy { it.absolutePath }.filter { it.isFile && it.canRead() }
    }

    /** 打开模块 assets 里的文件；找不到返回 null。调用方负责 close。 */
    fun open(assetPath: String): InputStream? {
        val entryName = if (assetPath.startsWith("assets/")) assetPath else "assets/$assetPath"

        for (apk in apkCandidates()) {
            val stream = runCatching {
                val zip = ZipFile(apk)
                val entry = zip.getEntry(entryName)
                if (entry == null) {
                    zip.close()
                    null
                } else {
                    // 跟着 ZipFile 关：用一个包装流保证 zip 也被关闭
                    object : InputStream() {
                        private val inner = zip.getInputStream(entry)
                        override fun read(): Int = inner.read()
                        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len)
                        override fun available(): Int = inner.available()
                        override fun close() {
                            runCatching { inner.close() }
                            runCatching { zip.close() }
                        }
                    }
                }
            }.getOrElse {
                WeLogger.w(TAG, "打开 ${apk.absolutePath}!$entryName 失败: ${it.message}")
                null
            }
            if (stream != null) return stream
        }

        // 兼容「模块 APK 已安装」的场景
        return runCatching {
            com.Johnny.wcx.utils.HostInfo.application
                .createPackageContext(MODULE_PACKAGE, 0)
                .assets.open(assetPath)
        }.getOrElse {
            WeLogger.w(TAG, "读不到模块 assets: $assetPath（尝试过 ${apkCandidates().map { it.name }}）")
            null
        }
    }

    fun readBytes(assetPath: String): ByteArray? =
        runCatching { open(assetPath)?.use { it.readBytes() } }.getOrNull()

    /** 把 assets 解到 [target]；成功且长度 > 0 才算真成功。 */
    fun extractTo(assetPath: String, target: File): Boolean {
        val bytes = readBytes(assetPath) ?: return false
        if (bytes.isEmpty()) return false
        return runCatching {
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
            target.length() > 0L
        }.getOrElse {
            WeLogger.w(TAG, "写入 $target 失败: ${it.message}")
            false
        }
    }
}
