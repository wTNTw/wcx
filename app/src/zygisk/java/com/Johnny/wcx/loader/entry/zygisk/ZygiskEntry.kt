@file:Suppress("unused")

package com.Johnny.wcx.loader.entry.zygisk

import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import androidx.annotation.Keep
import com.Johnny.wcx.constants.PackageNames
import com.Johnny.wcx.loader.abc.IHookBridge
import com.Johnny.wcx.loader.entry.common.ModuleLoader
import com.Johnny.wcx.loader.utils.NativeLoader
import com.Johnny.wcx.loader.utils.ZygiskNativePayload
import com.Johnny.wcx.utils.WeLogger
import dev.ujhhgtg.reflekt.utils.makeAccessible
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * JVM entry point of the Zygisk deployment.
 *
 * Called from native `postAppSpecialize` as
 * `ZygiskEntry.init(processName, dataDir, copiedApkPath)` after the native side
 * has published the payload APK, built its in-memory DEX loader, initialised
 * LSPlant and trusted that loader.
 *
 * At this point the host application class loader does not exist yet, so the
 * module waits for `LoadedApk.createAppFactory` and
 * `AppComponentFactory.instantiateClassLoader`, then hands the final host
 * loader to [ModuleLoader]. Later lifecycle hooks reuse the same bridge.
 */
@Keep
object ZygiskEntry {

    private const val TAG = "ZygiskEntry"
    private val entryLock = Any()
    private val finalClassLoaderHookInstalled = AtomicBoolean(false)
    private var loaderService: ZygiskLoaderService? = null
    private var hookBridge: ArtHookBridge? = null
    private var hostDataDir: String = ""
    private var modulePath: String = ""

    @SuppressLint("PrivateApi", "SoonBlockedPrivateApi")
    @Keep
    @JvmStatic
    fun init(
        processName: String,
        dataDir: String,
        apkPath: String,
    ) {
        val targetPackage = processName.substringBefore(':')
        if (!PackageNames.isWeChat(targetPackage)) {
            WeLogger.w(TAG, "ignoring unsupported Zygisk target: $targetPackage")
            return
        }
        synchronized(entryLock) {
            if (hookBridge != null) return

            try {
                WeLogger.i(TAG, "init: process=$processName apk=$apkPath")
                check(nativeInitialize()) { "LSPlant bootstrap or hook bridge registration failed" }
                NativeLoader.configurePayload(ZygiskNativePayload(File(apkPath), File(dataDir)))
                val service = ZygiskLoaderService(modulePath = apkPath)
                val bridge = ArtHookBridge()
                hostDataDir = dataDir
                modulePath = apkPath
                loaderService = service
                hookBridge = bridge

                // Wait at the point where the host's ApplicationInfo and the
                // default loader are available but the final loader is not yet
                // chosen; AppComponentFactory supplies the real one below.
                val loadedApk = Class.forName("android.app.LoadedApk")
                val createAppFactory = loadedApk.getDeclaredMethod(
                    "createAppFactory",
                    ApplicationInfo::class.java,
                    ClassLoader::class.java,
                ).makeAccessible()

                bridge.hookMethod(createAppFactory, object : IHookBridge.IMemberHookCallback {
                    override fun beforeHookedMember(param: IHookBridge.IMemberHookParam) = Unit

                    override fun afterHookedMember(param: IHookBridge.IMemberHookParam) {
                        if (param.throwable != null) return
                        val appInfo = param.args.getOrNull(0) as? ApplicationInfo ?: return
                        if (appInfo.packageName != targetPackage) return
                        val factory = param.result ?: return
                        installFinalClassLoaderHook(bridge, factory, targetPackage)
                    }
                }, priority = HOOK_PRIORITY)
            } catch (t: Throwable) {
                // Nothing is reachable before the hook is installed, so clear
                // the published fields and allow a later attempt to retry.
                loaderService = null
                hookBridge = null
                hostDataDir = ""
                modulePath = ""
                finalClassLoaderHookInstalled.set(false)
                WeLogger.e(TAG, "init failed", t)
            }
        }
    }

    /** Registers the bridge natives; the payload classes are already loaded. */
    @JvmStatic
    private external fun nativeInitialize(): Boolean

    private fun installFinalClassLoaderHook(
        bridge: ArtHookBridge,
        appComponentFactory: Any,
        targetPackage: String,
    ) {
        if (!finalClassLoaderHookInstalled.compareAndSet(false, true)) return
        try {
            val instantiateClassLoader = appComponentFactory.javaClass.getMethod(
                "instantiateClassLoader",
                ClassLoader::class.java,
                ApplicationInfo::class.java,
            ).makeAccessible()

            bridge.hookMethod(instantiateClassLoader, object : IHookBridge.IMemberHookCallback {
                override fun beforeHookedMember(param: IHookBridge.IMemberHookParam) = Unit

                override fun afterHookedMember(param: IHookBridge.IMemberHookParam) {
                    if (param.throwable != null) return
                    val appInfo = param.args.getOrNull(1) as? ApplicationInfo ?: return
                    if (appInfo.packageName != targetPackage) return
                    val finalClassLoader = param.result as? ClassLoader ?: return
                    startModule(finalClassLoader)
                }
            }, priority = HOOK_PRIORITY)
            WeLogger.i(
                TAG,
                "hooked AppComponentFactory.instantiateClassLoader on ${appComponentFactory.javaClass.name}",
            )
        } catch (t: Throwable) {
            finalClassLoaderHookInstalled.set(false)
            WeLogger.e(TAG, "failed to hook AppComponentFactory.instantiateClassLoader", t)
        }
    }

    private fun startModule(hostClassLoader: ClassLoader) {
        val service = loaderService ?: return
        val bridge = hookBridge ?: return
        try {
            ModuleLoader.init(
                hostDataDir = hostDataDir,
                initialClassLoader = hostClassLoader,
                loaderService = service,
                hookBridge = bridge,
                modulePath = modulePath,
                allowDynamicLoad = false,
            )
            WeLogger.i(TAG, "module startup handed to ModuleLoader (host=$hostClassLoader)")
        } catch (t: Throwable) {
            WeLogger.e(TAG, "failed to start WCX module", t)
        }
    }

    private const val HOOK_PRIORITY = 10000
}
