package com.Johnny.wcx.loader.startup

import android.annotation.SuppressLint
import android.app.Application
import android.os.Build
import com.Johnny.wcx.loader.abc.IHookBridge
import com.Johnny.wcx.loader.abc.IModuleLibraryHider
import com.Johnny.wcx.loader.abc.ILoaderService
import com.Johnny.wcx.utils.WeLogger
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.reflect.Field
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively

/** Compatibility startup for the existing Xposed entry points only. */
internal object XposedStartup {

    private const val TAG = "StartupAgent"

    @OptIn(ExperimentalPathApi::class)
    fun startup(
        loaderService: ILoaderService,
        hookBridge: IHookBridge?,
        modulePath: String,
        application: Application
    ) {
        if (!StartupAgent.prepare(loaderService, hookBridge, modulePath, application)) return

        ensureHiddenApiAccess()
        StartupAgent.initializeHost(modulePath, application)
        hideModuleLibraries(hookBridge)
        WeLauncher.init(application)

        runCatching {
            application.dataDir.toPath().resolve("app_qqprotect").deleteRecursively()
        }.onFailure { WeLogger.e(TAG, "failed to delete app_qqprotect", it) }
    }

    private fun hideModuleLibraries(hookBridge: IHookBridge?) {
        val hider = hookBridge as? IModuleLibraryHider ?: return
        runCatching { hider.hideLoadedModuleLibraries() }
            .onSuccess { hidden ->
                if (hidden) WeLogger.i(TAG, "hid loaded module libraries")
                else WeLogger.w(TAG, "module native-library hiding was incomplete")
            }
            .onFailure { WeLogger.e(TAG, "failed to hide module libraries", it) }
    }

    private fun ensureHiddenApiAccess() {
        if (!isHiddenApiAccessible()) {
            WeLogger.w(
                TAG,
                "hidden api is not accessible, SDK_INT is ${Build.VERSION.SDK_INT}"
            )
            HiddenApiBypass.setHiddenApiExemptions("L")
        }
    }

    @SuppressLint("BlockedPrivateApi", "PrivateApi")
    private fun isHiddenApiAccessible(): Boolean {
        val kContextImpl = runCatching {
            Class.forName("android.app.ContextImpl")
        }.getOrElse { return false }

        var mActivityToken: Field? = null
        var mToken: Field? = null

        try {
            mActivityToken = kContextImpl.getDeclaredField("mActivityToken")
        } catch (_: NoSuchFieldException) {
        }
        try {
            mToken = kContextImpl.getDeclaredField("mToken")
        } catch (_: NoSuchFieldException) {
        }

        return mActivityToken != null || mToken != null
    }
}
