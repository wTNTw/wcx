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

    /** 当前快照的不可变副本；用于实例化 feature 前筛选启动候选。 */
    fun enabledNames(): Set<String> {
        ensureLoaded()
        return snapshot.toSet()
    }

    /** 快照里该功能是否启用（快照为空/读失败时一律 false）。 */
    fun isEnabled(featureName: String): Boolean = featureName in enabledNames()

    /**
     * 按批次增量更新快照。仅在开关存储可读时调用。
     *
     * 必须**按批合并**而不是整体覆盖：feature 是分两批（首屏 / 首帧后）启动的，
     * 每批只知道自己那部分，整体覆盖会把另一批的结果冲掉（实测只剩延后批的 2 个）。
     * 语义：本批的开关状态就是真值 → 先移除本批所有名字，再把本批启用的加回去。
     */
    fun save(batchNames: Collection<String>, enabledNames: Collection<String>) {
        ensureLoaded()
        val updated = (snapshot - batchNames.toSet() + enabledNames.toSet())
        write(updated)
    }

    private fun write(enabledNames: Collection<String>) {
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
