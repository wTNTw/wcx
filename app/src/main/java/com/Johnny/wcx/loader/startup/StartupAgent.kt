package com.Johnny.wcx.loader.startup

import android.app.Application
import com.Johnny.wcx.loader.abc.IHookBridge
import com.Johnny.wcx.loader.abc.ILoaderService
import com.Johnny.wcx.loader.utils.HybridClassLoader
import com.Johnny.wcx.loader.utils.NativeLoader
import com.Johnny.wcx.utils.HostInfo
import com.Johnny.wcx.utils.WeLogger
import dev.ujhhgtg.reflekt.utils.ReflectionClassLoader
import java.io.File

/** Host setup shared by loaders. Does not install framework-specific features. */
internal object StartupAgent {
    private const val TAG = "StartupAgent"
    private var initialized = false

    fun prepare(
        loaderService: ILoaderService,
        hookBridge: IHookBridge?,
        modulePath: String,
        application: Application,
    ): Boolean {
        if (initialized) return false
        initialized = true

        val realClassLoader = application.baseContext.classLoader
        HybridClassLoader.hostClassLoader = realClassLoader
        ReflectionClassLoader.value = realClassLoader
        StartupInfo.loaderService = loaderService
        StartupInfo.hookBridge = hookBridge
        StartupInfo.recordModulePath(modulePath)
        return true
    }

    fun initializeHost(modulePath: String, application: Application) {
        if (File(modulePath).canWrite()) {
            WeLogger.w(TAG, "module path is writable: $modulePath\nThis may cause issues on Android 15+, please check your module loader")
        }
        HostInfo.init(application)
        NativeLoader.init(application)
    }
}
