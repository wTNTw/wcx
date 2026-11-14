package com.Johnny.wcx.utils

/**
 * 排错用的调试开关（都通过 `adb shell setprop debug.wcx.*` 打开，默认全关）。
 *
 * 存在的意义：适配新版微信时需要「一次性把所有功能都打开」才能收集到各功能的运行期错误，
 * 否则只能靠用户逐个手动开。走系统属性而不是配置项，避免污染用户设置。
 */
object DebugFlags {

    private const val TAG = "DebugFlags"
    private const val KEY_NO_HOOKS = "debug.wcx.no_hooks"

    private fun prop(key: String): String? = runCatching {
        val clazz = Class.forName("android.os.SystemProperties")
        val get = clazz.getMethod("get", String::class.java)
        (get.invoke(null, key) as? String)?.trim()
    }.getOrNull()

    /**
     * `debug.wcx.no_hooks=1`：**临时排错开关**，只加载 payload（dex / 原生库），
     * 不装任何 hook、不跑 DexKit、不引导 Hchat。用于区分「模块是否参与」某个问题
     * （例如微信自身 libowl / libaff_biz 的 SIGABRT 崩在启动页）。
     *
     * 用完要连同 WeLauncher 里的调用一起删掉。
     */
    val noHooks: Boolean by lazy {
        val on = prop(KEY_NO_HOOKS) == "1"
        if (on) WeLogger.w(TAG, "no_hooks=1 → 只加载 payload，不装任何 hook（排错模式）")
        on
    }

    /** `debug.wcx.enable_all=1`：忽略功能开关，全部启用（排错用）。 */
    val enableAllFeatures: Boolean by lazy {
        val on = prop("debug.wcx.enable_all") == "1"
        if (on) WeLogger.w(TAG, "debug.wcx.enable_all=1 → 忽略开关，全部功能强制启用（排错模式）")
        on
    }
}
