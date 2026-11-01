package com.Johnny.wcx.loader.utils

import com.tencent.mmkv.MMKV
import com.Johnny.wcx.loader.startup.StartupInfo
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.utils.fs.createDirsSafe
import kotlin.io.path.div
import kotlin.io.path.exists

object NativeLoader {

    init {
        loadLibrary("dexkit")
        loadLibrary("wekit_native")
    }

    fun init(hostCtx: android.content.Context) {
        val mmkvDir = hostCtx.filesDir.toPath() / "mmkv"
        if (!mmkvDir.exists()) {
            mmkvDir.createDirsSafe()
        }

        MMKV.initialize(hostCtx, mmkvDir.toString())

        MMKV.mmkvWithID(WePrefs.PREFS_NAME, MMKV.MULTI_PROCESS_MODE)
    }

    /**
     * 原生库加载交给入口实现：
     * - Xposed / Frida：APK 已安装，走 System.loadLibrary
     * - Zygisk：不装 APK，库由注入器在 zygote 阶段 dlopen，入口实现只做 JNI 符号绑定
     */
    private fun loadLibrary(name: String) {
        val service = runCatching { StartupInfo.loaderService }.getOrNull()
        if (service == null) {
            System.loadLibrary(name)
        } else {
            service.loadNativeLibrary(name)
        }
    }
}
