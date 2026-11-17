package com.Johnny.wcx.features.core

import android.content.Context
import com.Johnny.wcx.constants.Preferences
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.utils.TargetProcesses
import com.Johnny.wcx.utils.WeLogger

/** 开关复核时间点：覆盖「MMKV 数据在进程启动后 1~3s 内才载入」这个窗口。 */
private val RECHECK_DELAYS_MS = longArrayOf(2_000L, 5_000L)
private const val RECHECK_TAG = "SwitchFeature"

abstract class SwitchFeature : BaseFeature() {

    companion object {
        /** 带分类总开关的分类名（见 [Preferences.hiddenContactsMasterEnabled]）。 */
        const val CATEGORY_WITH_MASTER_SWITCH = "密友功能"
    }

    /** Default state when the user has never toggled this feature. */
    protected open val defaultEnabled: Boolean = false

    /** Whether this feature should load in the current process. Defaults to the main process only. */
    protected open val shouldLoadInCurrentProcess: Boolean
        get() = TargetProcesses.isInMain

    /** Whether the feature should be active at startup, given the cached preference. */
    protected open val shouldEnableOnStartup: Boolean
        get() = _isEnabled

    /** 延迟复核只安排一次。 */
    private var prefRecheckScheduled = false

    final override fun startup() {
        if (!shouldLoadInCurrentProcess) return
        // 调试开关：`adb shell setprop debug.wcx.enable_all 1` + 重启微信 = 忽略开关全开，
        // 用来一次性收集所有功能在新版微信上的运行期错误（排错/适配用，默认不生效）。
        if (com.Johnny.wcx.utils.DebugFlags.enableAllFeatures) _isEnabled = true
        // 「密友功能」分类有总开关：关掉总开关时整个分类都不加载（各功能自身开关状态保留）。
        if (CATEGORY_WITH_MASTER_SWITCH in categories && !Preferences.hiddenContactsMasterEnabled) return
        if (!com.Johnny.wcx.utils.DebugFlags.enableAllFeatures) {
            _isEnabled = WePrefs.getBoolOrDef(name, defaultEnabled)
            if (!_isEnabled && FeatureSwitchSnapshot.isEnabled(name)) {
                // 重启后首次启动时 MMKV 还没载入数据，开关会读成默认 false；
                // 用上次「确实读到过」的纯文本快照兜底，避免整轮 feature 空转（首屏白屏）。
                WeLogger.i(RECHECK_TAG, "MMKV 读到关闭但快照显示启用，按快照启用：" + name)
                _isEnabled = true
            }
        }
        if (shouldEnableOnStartup) {
            enable()
        } else {
            schedulePrefRecheck()
        }
    }

    /**
     * 读到「关闭」时安排延迟复核。
     *
     * 重启后**首次**启动微信时，模块的开关存储（WePrefs/MMKV 多进程模式）还没把已有数据
     * 载入内存，于是所有开关都读成默认值 —— 实测 169 个 feature 里只有 1 个（默认开的）
     * 能启用，整轮空转 → 首屏白屏；而同一进程稍后打开设置页又能看到开关是开着的。
     *
     * 所以「读成关闭」不能一锤定音：2s / 5s 各复核一次，读到开着就补 enable()。
     * 真正被用户关掉的功能依旧保持关闭（只是多读两次 MMKV）。
     */
    private fun schedulePrefRecheck() {
        if (prefRecheckScheduled) return
        if (!com.Johnny.wcx.utils.TargetProcesses.isInMain) return
        prefRecheckScheduled = true
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        for (delay in RECHECK_DELAYS_MS) {
            handler.postDelayed({
                runCatching {
                    if (isActive || !shouldLoadInCurrentProcess) return@runCatching
                    // 当前微信版本已不支持的功能不要复活
                    if (unavailableReason != null) return@runCatching
                    val nowEnabled = WePrefs.getBoolOrDef(name, defaultEnabled)
                    if (nowEnabled) {
                        WeLogger.i(RECHECK_TAG, "开关延迟复核命中：${displayName} 在 ${delay}ms 后读到开启，补装 hook")
                        _isEnabled = true
                        enable()
                    }
                }.onFailure { WeLogger.w(RECHECK_TAG, "开关延迟复核失败: ${displayName}", it) }
            }, delay)
        }
    }



    /** Cached user preference (desired state). Distinct from [isActive], the runtime truth. */
    @Suppress("PropertyName")
    protected var _isEnabled = false

    var isEnabled
        get() = _isEnabled
        set(value) {
            if (_isEnabled == value) return
            _isEnabled = value
            if (value) {
                WeLogger.i("SwitchFeature", "enabling $displayName...")
                enable()
            } else {
                WeLogger.i("SwitchFeature", "disabling $displayName...")
                disable()
            }
        }

    private var toggleCompletionCallback: Runnable? = null

    open fun onBeforeToggle(newState: Boolean, context: Context): Boolean = true

    fun setToggleCompletionCallback(callback: Runnable) {
        toggleCompletionCallback = callback
    }

    fun applyToggle(newState: Boolean) {
        WePrefs.putBool(name, newState)
        isEnabled = newState
        toggleCompletionCallback?.run()
    }
}
