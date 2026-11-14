package com.Johnny.wcx.utils

/**
 * 排错用的调试开关（都通过 `adb shell setprop debug.wcx.*` 打开，默认全关）。
 *
 * 存在的意义：适配新版微信时需要「一次性把所有功能都打开」才能收集到各功能的运行期错误，
 * 否则只能靠用户逐个手动开。走系统属性而不是配置项，避免污染用户设置。
 */
object DebugFlags {

    private const val TAG = "DebugFlags"

    private fun prop(key: String): String? = runCatching {
        val clazz = Class.forName("android.os.SystemProperties")
        val get = clazz.getMethod("get", String::class.java)
        (get.invoke(null, key) as? String)?.trim()
    }.getOrNull()

    /** `debug.wcx.enable_all=1`：忽略功能开关，全部启用（排错用）。 */
    val enableAllFeatures: Boolean by lazy {
        val on = prop("debug.wcx.enable_all") == "1"
        if (on) WeLogger.w(TAG, "debug.wcx.enable_all=1 → 忽略开关，全部功能强制启用（排错模式）")
        on
    }
}
