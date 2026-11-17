package com.Johnny.wcx.features.core

import com.Johnny.wcx.utils.HostInfo
import com.Johnny.wcx.utils.WeLogger
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 开关快照（纯文本文件，不依赖 MMKV 时序）。
 *
 * 背景：手机重启后**第一次**启动微信时，模块的开关存储（WePrefs/MMKV 多进程模式）还没把
 * 已有数据载入内存，于是 169 个 feature 的开关全被读成默认 false，整轮空转 → 首页白屏；
 * 而同一进程稍后再读（打开设置页）又是对的。
 *
 * 参考另一个 WeKit 系模块（WX-Augment）：它的作用域/开关放在 root 侧文件里，注入前就确定，
 * 天然不受 app 内存储时序影响。
 *
 * 这里做等价的事：每次成功读到开关后，把「当时处于启用状态的 feature 名」落到微信自己
 * filesDir 下的纯文本快照（内部存储，进程一起来就可读，不需要 MMKV 初始化）。
 * 启动时若 MMKV 把所有开关读成关闭，而快照里该功能是开着的，就按快照启用。
 */
object FeatureSwitchSnapshot {

    private const val TAG = "FeatureSwitchSnapshot"
    private const val FILE_NAME = "wcx-enabled-features.txt"

    private val loaded = AtomicBoolean(false)

    @Volatile
    private var snapshot: Set<String> = emptySet()

    private val file: File?
        get() = runCatching { File(HostInfo.application.filesDir, FILE_NAME) }.getOrNull()

    /** 快照里该功能是否启用（快照为空/读失败时一律 false）。 */
    fun isEnabled(featureName: String): Boolean {
        ensureLoaded()
        return snapshot.contains(featureName)
    }

    /** 用「本次真实读到的启用集合」覆盖快照。仅在开关存储可读时调用。 */
    fun save(enabledNames: Collection<String>) {
        val target = file ?: return
        runCatching {
            val text = enabledNames.filter { it.isNotBlank() }.sorted().joinToString("\n")
            target.writeText(text)
            snapshot = enabledNames.toSet()
            loaded.set(true)
        }.onFailure { WeLogger.w(TAG, "写入开关快照失败: ${it.message}") }
    }

    private fun ensureLoaded() {
        if (!loaded.compareAndSet(false, true)) return
        val target = file ?: return
        runCatching {
            if (!target.isFile) return
            snapshot = target.readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSet()
            WeLogger.i(TAG, "已载入开关快照：${snapshot.size} 个功能")
        }.onFailure { WeLogger.w(TAG, "读取开关快照失败: ${it.message}") }
    }
}
