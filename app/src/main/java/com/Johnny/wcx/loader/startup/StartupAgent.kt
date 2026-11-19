package com.Johnny.wcx.loader.startup

import android.annotation.SuppressLint
import android.app.Application
import android.os.Build
import dev.ujhhgtg.reflekt.utils.ReflectionClassLoader
import com.Johnny.wcx.loader.abc.IHookBridge
import com.Johnny.wcx.loader.abc.ILoaderService
import com.Johnny.wcx.loader.utils.HybridClassLoader
import com.Johnny.wcx.loader.utils.NativeLoader
import com.Johnny.wcx.utils.HostInfo
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.perf.StartupPerformance
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.File
import java.lang.reflect.Field
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively

object StartupAgent {

    private const val TAG = "StartupAgent"

    private var initialized = false


    @OptIn(ExperimentalPathApi::class)
    fun startup(
        loaderService: ILoaderService,
        hookBridge: IHookBridge?,
        modulePath: String,
        application: Application
    ) {
        if (initialized) return
        initialized = true

        val realClassLoader = application.baseContext.classLoader
        HybridClassLoader.hostClassLoader = realClassLoader
        ReflectionClassLoader.value = realClassLoader
        StartupInfo.loaderService = loaderService
        StartupInfo.hookBridge = hookBridge

        ensureHiddenApiAccess()
        checkWriteXorExecuteForModulePath(modulePath)

        HostInfo.init(application)
        NativeLoader.init(application)
        StartupPerformance.mark("native_ready")

        // 把模块加载过的原生库从 /proc/self/maps 里隐藏掉（memfd 重映射 + 路径隐藏）。
        // 移植上游 wekit-zygisk 时只搬了 native 侧的 so_hider，Java 侧的调用漏了，
        // 所以之前一直是死代码。必须在 NativeLoader.init 之后调用（要隐藏的库此时才全部映射好）。
        (hookBridge as? com.Johnny.wcx.loader.entry.zygisk.ArtHookBridge)?.let { bridge ->
            runCatching { bridge.hideLoadedModuleLibraries() }
                .onSuccess { ok -> WeLogger.i(TAG, "hideLoadedModuleLibraries -> " + ok) }
                .onFailure { WeLogger.w(TAG, "hideLoadedModuleLibraries failed: " + (it.message ?: it.javaClass.simpleName)) }
        }
        // FIXME: some people have hiding on, which causes false positives in signature verifier
//        SignatureVerifier.verify(application)
        WeLauncher.init(application)

        runCatching {
            application.dataDir.toPath().resolve("app_qqprotect").deleteRecursively()
        }.onFailure { WeLogger.e(TAG, "failed to delete app_qqprotect", it) }
    }

    private fun checkWriteXorExecuteForModulePath(modulePath: String) {
        val moduleFile = File(modulePath)
        if (moduleFile.canWrite()) {
            WeLogger.w(TAG, "module path is writable: $modulePath\nThis may cause issues on Android 15+, please check your Xposed framework")
        }
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
    fun isHiddenApiAccessible(): Boolean {
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
