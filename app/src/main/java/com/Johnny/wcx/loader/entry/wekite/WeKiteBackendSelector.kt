package com.Johnny.wcx.loader.entry.wekite

import com.Johnny.wcx.loader.utils.NativeLoader
import com.Johnny.wcx.utils.WeLogger

internal object WeKiteBackendSelector {
    private const val TAG = "WeKiteBackendSelector"
    private const val PROPERTY = "debug.wcx.wekite_m2b"

    fun select(processName: String): WeKiteHookBridge? {
        if (!isTargetProcess(processName) || systemProperty(PROPERTY) != "1") return null
        return try {
            NativeLoader.loadZygiskNativeLibrariesEarly()
            val bridge = WeKiteHookBridge()
            check(WeKiteHookBridge.initializeNativeBackend()) { "native ART engine initialization failed" }
            WeLogger.i(TAG, "native init PASS process=$processName")
            val result = WeKiteNativeSelfTest.run(bridge, processName)
            check(result.passed) { "native self-test failed: ${result.failure}" }
            WeLogger.i(TAG, "backend=WeKite process=$processName checks=${result.checks.size}")
            bridge
        } catch (t: Throwable) {
            WeLogger.e(TAG, "backend=LSPlant fallback process=$processName", t)
            null
        }
    }

    internal fun isTargetProcess(processName: String): Boolean =
        processName == "com.tencent.mm:push" || processName.startsWith("com.tencent.mm:appbrand")

    private fun systemProperty(name: String): String = runCatching {
        val cls = Class.forName("android.os.SystemProperties")
        cls.getMethod("get", String::class.java, String::class.java).invoke(null, name, "") as String
    }.getOrDefault("")
}
