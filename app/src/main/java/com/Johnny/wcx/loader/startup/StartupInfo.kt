package com.Johnny.wcx.loader.startup

import com.Johnny.wcx.loader.abc.IHookBridge
import com.Johnny.wcx.loader.abc.ILoaderService
import com.Johnny.wcx.utils.HostInfo

object StartupInfo {

    lateinit var loaderService: ILoaderService
    var hookBridge: IHookBridge? = null

    @Volatile
    private var loadedModulePath: String? = null

    internal fun recordModulePath(path: String) {
        loadedModulePath = path.takeIf { it.isNotBlank() }
    }

    /** The loader supplies the module APK. Only the standalone module app can use its own sourceDir. */
    val modulePath: String?
        get() = loadedModulePath ?: runCatching {
            if (HostInfo.isModule) HostInfo.appInfo.sourceDir.takeIf { it.isNotBlank() } else null
        }.getOrNull()
}
