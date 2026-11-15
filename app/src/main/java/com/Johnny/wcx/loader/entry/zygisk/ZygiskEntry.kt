@file:Suppress("unused")

package com.Johnny.wcx.loader.entry.zygisk

import android.annotation.SuppressLint
import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import androidx.annotation.Keep
import dev.ujhhgtg.reflekt.utils.makeAccessible
import com.Johnny.wcx.BuildConfig
import com.Johnny.wcx.constants.PackageNames
import com.Johnny.wcx.loader.abc.IHookBridge
import com.Johnny.wcx.loader.entry.common.ModuleLoader
import com.Johnny.wcx.loader.startup.StartupAgent
import com.Johnny.wcx.loader.startup.UnifiedEntryPoint
import com.Johnny.wcx.loader.utils.NativeLoader
import com.Johnny.wcx.utils.WeLogger
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Zygisk JVM entry point.
 *
 * Called from native postAppSpecialize:
 *   ZygiskEntry.init(processName, dataDir, copiedApkPath)
 *
 * The native side has:
 *   1. Copied the active module's APK into this app's data directory.
 *   2. Read DEX directly from that APK through InMemoryDexClassLoader.
 *   3. 子进程：初始化 LSPlant 并 trust loader；主进程：把 LSPlant 初始化推迟到
 *      libaff_biz 加载之后（见 nativeDeferredInitialize）。
 *
 * 主进程走延迟启动：后台线程轮询 /proc/self/maps 里出现 libaff_biz.so 后，
 * 再初始化 LSPlant 并直接启动模块；子进程保持原有 createAppFactory 钩子链。
 */
@Keep
object ZygiskEntry {

    private const val TAG = "ZygiskEntry"
    private const val LIBAFF_BIZ_GRACE_MS = 1_000L
    private val entryLock = Any()
    private val moduleStarted = AtomicBoolean(false)
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
                WeLogger.i(TAG, "ZygiskEntry.init: process=$processName apk=$apkPath dataDir=$dataDir")
                check(nativeInitialize()) {
                    "LSPlant bootstrap or hook bridge registration failed"
                }
                NativeLoader.configureZygiskPayload(apkPath, dataDir)
                val service = ZygiskLoaderService(
                    modulePath = apkPath,
                    versionName = BuildConfig.VERSION_NAME,
                    versionCode = BuildConfig.VERSION_CODE,
                )
                val bridge = ArtHookBridge()
                hostDataDir = dataDir
                modulePath = apkPath
                loaderService = service
                hookBridge = bridge

                if (processName.contains(':')) {
                    // 子进程不加载 libaff_biz，原生侧已在 postAppSpecialize 早期初始化
                    // LSPlant，这里沿用原来的 createAppFactory 钩子链。
                    installEarlyStartupHooks(bridge, targetPackage)
                } else {
                    // 主进程：原生侧已推迟 LSPlant 初始化，等微信把 libaff_biz.so 加载完
                    // 再初始化 LSPlant 并直接启动模块（避开 libowl 与 LSPlant 的冲突）。
                    startDeferredStartup(service, bridge, apkPath)
                }
            } catch (t: Throwable) {
                // All fields are published before the hook can become reachable;
                // roll them back when installation itself fails so init can retry.
                loaderService = null
                hookBridge = null
                hostDataDir = ""
                modulePath = ""
                moduleStarted.set(false)
                finalClassLoaderHookInstalled.set(false)
                WeLogger.e(TAG, "ZygiskEntry.init failed", t)
            }
        }
    }

    @JvmStatic
    private external fun nativeInitialize(): Boolean

    @JvmStatic
    private external fun nativeDeferredInitialize(): Boolean

    /**
     * 子进程早期入口：原生侧已在 postAppSpecialize 初始化 LSPlant，这里直接挂
     * LoadedApk.createAppFactory → AppComponentFactory.instantiateClassLoader → 启动模块。
     */
    private fun installEarlyStartupHooks(bridge: ArtHookBridge, targetPackage: String) {
        // Waits at LoadedApk.createAppFactory. The ClassLoader
        // parameter here is only AOSP's default loader; the final
        // mClassLoader is selected by AppComponentFactory below.
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
        }, priority = 10000)
    }

    /**
     * 主进程延迟启动：等微信把 libaff_biz.so 加载完（它与 LSPlant/Dobby 的 ART hook
     * 冲突，见 docs/work-items/wechat-8.0.80-startup-abort.md），再初始化 LSPlant 并启动
     * 模块。失败只打日志，不影响微信。
     */
    private fun startDeferredStartup(
        service: ZygiskLoaderService,
        bridge: ArtHookBridge,
        apkPath: String,
    ) {
        Thread({
            try {
                val loaded = waitForLibaffBiz()
                WeLogger.i(
                    TAG,
                    "deferred startup: libaff_biz ${if (loaded) "loaded" else "timeout"} after polling",
                )
                if (!nativeDeferredInitialize()) {
                    WeLogger.e(TAG, "deferred startup: LSPlant init failed; module not started in main process")
                    return@Thread
                }
                WeLogger.i(TAG, "deferred startup: LSPlant initialized, acquiring Application")
                val app = acquireApplication()
                if (app == null) {
                    WeLogger.e(TAG, "deferred startup: could not acquire Application; module not started")
                    return@Thread
                }
                // 模块启动（ActivityProxy 替换 Instrumentation、HostInfo/MMKV 初始化、功能 hook）
                // 必须在主线程跑，与原来的 callApplicationOnCreate 时序保持一致，避免后台线程
                // 替换 Instrumentation 与主线程首帧渲染抢跑。
                WeLogger.i(TAG, "deferred startup: posting module start to main thread")
                Handler(Looper.getMainLooper()).post {
                    try {
                        UnifiedEntryPoint.prepareClassLoaderBridge()
                        StartupAgent.startup(service, bridge, apkPath, app)
                    } catch (t: Throwable) {
                        WeLogger.e(TAG, "deferred startup: module start on main thread failed", t)
                    }
                }
            } catch (t: Throwable) {
                WeLogger.e(TAG, "deferred startup failed", t)
            }
        }, "WCX-DeferredStartup").apply { isDaemon = true }.start()
    }

    /**
     * 轮询 /proc/self/maps 等 libaff_biz 映射稳定后才返回 true。
     *
     * 为什么不能「一命中就初始化」：maps 里出现 libaff_biz.so 只说明 ELF 被映射了，
     * 真正和 LSPlant 冲突的是它 JNI_OnLoad 里 libowl 的 GetMethodID 钩子，那是映射之后
     * 才执行的。若命中就立刻 art::init，会撞上「已映射、JNI_OnLoad 还没跑完」的窗口。
     * 这里首次命中后再要求它连续稳定存在 1s（每 100ms 复查一次）；JNI_OnLoad 是同步的、
     * 远在 1s 内返回，从而避开窗口。
     *
     * 超时（15s）仍拿不到稳定观测时按兜底返回，不因此不装功能。
     */
    private fun waitForLibaffBiz(timeoutMs: Long = 15_000L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        var stableSince = -1L
        while (System.currentTimeMillis() < deadline) {
            if (isLibaffBizLoaded()) {
                val now = System.currentTimeMillis()
                if (stableSince < 0) stableSince = now
                if (now - stableSince >= LIBAFF_BIZ_GRACE_MS) {
                    WeLogger.i(TAG, "等 libaff_biz 稳定后才初始化：映射已连续存在 ${now - stableSince}ms")
                    return true
                }
            } else {
                stableSince = -1L
            }
            Thread.sleep(100L)
        }
        WeLogger.w(TAG, "等 libaff_biz 稳定超时（${timeoutMs}ms），按兜底路径继续")
        return isLibaffBizLoaded()
    }

    private fun isLibaffBizLoaded(): Boolean = try {
        File("/proc/self/maps").useLines { lines ->
            lines.any { it.contains("libaff_biz.so") }
        }
    } catch (_: Throwable) {
        false
    }

    private fun acquireApplication(): Application? {
        var app = getHostApplication()
        // 万一还没走到 Application.onCreate，重试一小段时间。
        var attempts = 0
        while (app == null && attempts < 20) {
            Thread.sleep(250L)
            app = getHostApplication()
            attempts++
        }
        return app
    }

    private fun getHostApplication(): Application? = try {
        android.app.ActivityThread.currentApplication()
    } catch (_: Throwable) {
        null
    }

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
            }, priority = 10000)
            WeLogger.i(TAG, "hooked AppComponentFactory.instantiateClassLoader on ${appComponentFactory.javaClass.name}")
        } catch (t: Throwable) {
            finalClassLoaderHookInstalled.set(false)
            WeLogger.e(TAG, "failed to hook AppComponentFactory.instantiateClassLoader", t)
        }
    }

    private fun startModule(hostClassLoader: ClassLoader) {
        if (!moduleStarted.compareAndSet(false, true)) return

        val started: Boolean = try {
            val service = loaderService ?: error("Zygisk loader service not initialized")
            val bridge = hookBridge ?: error("Zygisk hook bridge not initialized")
            ModuleLoader.init(
                hostDataDir = hostDataDir,
                initialClassLoader = hostClassLoader,
                loaderService = service,
                hookBridge = bridge,
                modulePath = modulePath,
                allowDynamicLoad = false,
            )
        } catch (t: Throwable) {
            WeLogger.e(TAG, "failed to start WCX module", t)
            false
        }
        if (started) {
            WeLogger.i(TAG, "WCX module started with host ClassLoader=$hostClassLoader")
        } else {
            // ModuleLoader deliberately leaves its guard unset on failure.
            // Do the same here so a later app lifecycle entry can retry.
            moduleStarted.set(false)
            WeLogger.w(TAG, "WCX module startup failed; retry remains available")
        }
    }

}
