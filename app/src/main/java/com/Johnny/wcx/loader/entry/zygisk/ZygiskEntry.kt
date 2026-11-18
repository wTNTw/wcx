@file:Suppress("unused")

package com.Johnny.wcx.loader.entry.zygisk

import android.annotation.SuppressLint
import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import androidx.annotation.Keep
import dev.ujhhgtg.reflekt.utils.makeAccessible
import com.Johnny.wcx.BuildConfig
import com.Johnny.wcx.constants.PackageNames
import com.Johnny.wcx.loader.abc.IHookBridge
import com.Johnny.wcx.loader.entry.common.ModuleLoader
import com.Johnny.wcx.loader.startup.StartupAgent
import com.Johnny.wcx.loader.startup.UnifiedEntryPoint
import com.Johnny.wcx.loader.utils.ActivityProxy
import com.Johnny.wcx.loader.utils.NativeLoader
import com.Johnny.wcx.utils.WeLogger
import java.io.FileDescriptor
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
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
    private const val LIBAFF_BIZ_POLL_INTERVAL_MS = 250L

    /** libaff_biz 已命中时，等 Application 的宽容时间。 */
    private const val APP_WAIT_MS = 5_000L

    /** libaff_biz 超时（兑底路径）时，等 Application 的上限；池进程/被冻结时会一直等到这里。 */
    private const val FALLBACK_APP_WAIT_MS = 60_000L

    /** art::init（LSPlant）失败时的重试次数与退避。 */
    private const val LSPLANT_INIT_ATTEMPTS = 3
    private const val LSPLANT_RETRY_DELAY_MS = 3_000L

    private val LIBAFF_BIZ_NEEDLE = "libaff_biz.so".toByteArray(StandardCharsets.US_ASCII)
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
            runDeferredStartup(service, bridge, apkPath)
        }, "WCX-Defer-${android.os.Process.myPid()}").apply { isDaemon = true }.start()
    }

    /** 读 `debug.wcx.early_lsplant`（A/B 试验用；默认关闭）。 */
    private fun isEarlyLsplantEnabled(): Boolean = runCatching {
        val cls = Class.forName("android.os.SystemProperties")
        val get = cls.getMethod("get", String::class.java, String::class.java)
        val value = get.invoke(null, "debug.wcx.early_lsplant", "") as? String
        value != null && value.isNotBlank() && value != "0"
    }.getOrDefault(false)

    /** 主进程的延迟启动：等 libaff_biz → 安装首 Activity 门 → 按需初始化 LSPlant/模块。 */
    private fun runDeferredStartup(
        service: ZygiskLoaderService,
        bridge: ArtHookBridge,
        apkPath: String,
    ) {
        try {
            // A/B 开关：debug.wcx.early_lsplant=1 时按上游 WeKit 的时序「立即」初始化 LSPlant
            // （不等 libaff_biz/libowl），用来复核「早期初始化必崩」是否只对本构建成立。
            val earlyLsplant = isEarlyLsplantEnabled()
            if (earlyLsplant) {
                WeLogger.w(TAG, "debug.wcx.early_lsplant=1：跳过 libaff_biz 等待，按上游时序初始化 LSPlant")
            }
            val loaded = if (earlyLsplant) true else waitForLibaffBiz()
            WeLogger.i(
                TAG,
                "deferred startup: libaff_biz ${if (loaded) "loaded" else "timeout"} after polling",
            )
            // 兜底路径（没观测到 libaff_biz）必须先确认宿主真的已经跑起来：进程可能还没被
            // 特化（USAP 池进程），或正被系统 frozen 冻着。
            val app = acquireApplication(if (loaded) APP_WAIT_MS else FALLBACK_APP_WAIT_MS)
            if (app == null) {
                if (loaded) {
                    WeLogger.e(TAG, "deferred startup: could not acquire Application; module not started")
                } else {
                    WeLogger.w(
                        TAG,
                        "deferred startup: 宿主 Application 未就绪（疑似池进程或被冻结），本进程不启动模块",
                    )
                }
                return
            }
            if (earlyLsplant) {
                // 保留 A/B 开关的原语义：显式开启时立即初始化，不经过首 Activity 门。
                if (initializeLsPlant()) {
                    postMainStartup(service, bridge, apkPath, app)
                } else {
                    reportLsPlantFailure(loaded)
                }
                return
            }

            // 背景 BootReceiver 可以先拉起主进程。此时直接装全部 hook 会触发微信 SIGABRT；
            // 等 importance 进入前台又会晚于 LauncherUI 的首个 onCreate。先只替换
            // Instrumentation/mH，在 ActivityThread 真正处理首个 Activity transaction 前，
            // 同步完成 LSPlant + 模块启动，既不碰纯后台启动窗口，也不漏首屏 hook。
            if (!installMainStartupGate(service, bridge, apkPath, app, loaded)) {
                WeLogger.e(TAG, "deferred startup: failed to install first-Activity startup gate")
                return
            }
            WeLogger.i(TAG, "deferred startup: 首个 Activity 启动门已安装")
        } catch (t: Throwable) {
            WeLogger.e(TAG, "deferred startup failed", t)
        }
    }

    private fun installMainStartupGate(
        service: ZygiskLoaderService,
        bridge: ArtHookBridge,
        apkPath: String,
        app: Application,
        libaffLoaded: Boolean,
    ): Boolean {
        val installed = CountDownLatch(1)
        var success = false
        Handler(Looper.getMainLooper()).post {
            success = ActivityProxy.init(app, startupGate = startupGate@{
                WeLogger.i(TAG, "deferred startup: 首个 Activity 即将创建，开始初始化 LSPlant")
                if (!initializeLsPlantOffMainThread()) {
                    reportLsPlantFailure(libaffLoaded)
                    return@startupGate
                }
                try {
                    UnifiedEntryPoint.prepareClassLoaderBridge()
                    StartupAgent.startup(service, bridge, apkPath, app)
                } catch (t: Throwable) {
                    WeLogger.e(TAG, "deferred startup: module start before first Activity failed", t)
                }
            })
            installed.countDown()
        }
        awaitUninterruptibly(installed)
        return success
    }

    /** LSPlant may suspend ART; keep that work off ActivityThread while its transaction waits. */
    private fun initializeLsPlantOffMainThread(): Boolean {
        val finished = CountDownLatch(1)
        var success = false
        Thread({
            try {
                success = initializeLsPlant()
            } finally {
                finished.countDown()
            }
        }, "WCX-LSPlant-${android.os.Process.myPid()}").apply { isDaemon = true }.start()
        awaitUninterruptibly(finished)
        return success
    }

    private fun postMainStartup(
        service: ZygiskLoaderService,
        bridge: ArtHookBridge,
        apkPath: String,
        app: Application,
    ) {
        WeLogger.i(TAG, "deferred startup: posting module start to main thread")
        Handler(Looper.getMainLooper()).post {
            try {
                UnifiedEntryPoint.prepareClassLoaderBridge()
                StartupAgent.startup(service, bridge, apkPath, app)
            } catch (t: Throwable) {
                WeLogger.e(TAG, "deferred startup: module start on main thread failed", t)
            }
        }
    }

    private fun reportLsPlantFailure(libaffLoaded: Boolean) {
        if (libaffLoaded) {
            WeLogger.e(TAG, "deferred startup: LSPlant init failed after retries; module not started in main process")
        } else {
            WeLogger.w(
                TAG,
                "deferred startup: 宿主环境异常（libaff_biz 未加载），LSPlant 未初始化，本进程不启动模块",
            )
        }
    }

    private fun awaitUninterruptibly(latch: CountDownLatch) {
        var interrupted = false
        try {
            while (true) {
                try {
                    latch.await()
                    return
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    /**
     * art::init + trust_class_loader，失败按退避重试。
     *
     * 单次失败就把本进程判死会导致“微信活着但模块不生效”，而重试代价极低；
     * 实际上只要 libaff_biz 的 JNI_OnLoad 跑完，后续重试就能成功。
     */
    private fun initializeLsPlant(): Boolean {
        for (attempt in 1..LSPLANT_INIT_ATTEMPTS) {
            if (nativeDeferredInitialize()) {
                WeLogger.i(TAG, "deferred startup: LSPlant initialized")
                return true
            }
            if (attempt < LSPLANT_INIT_ATTEMPTS) {
                WeLogger.w(
                    TAG,
                    "deferred startup: LSPlant init failed (attempt $attempt/$LSPLANT_INIT_ATTEMPTS)，" +
                        "${LSPLANT_RETRY_DELAY_MS}ms 后重试",
                )
                Thread.sleep(LSPLANT_RETRY_DELAY_MS)
            }        }
        return false
    }

    /**
     * 轮询 /proc/self/maps 等 libaff_biz 映射稳定后才返回 true。
     *
     * 为什么不能「一命中就初始化」：maps 里出现 libaff_biz.so 只说明 ELF 被映射了，
     * 真正和 LSPlant 冲突的是它 JNI_OnLoad 里 libowl 的 GetMethodID 钩子，那是映射之后
     * 才执行的。若命中就立刻 art::init，会撞上「已映射、JNI_OnLoad 还没跑完」的窗口。
     * 这里首次命中后再要求它连续稳定存在 1s（每 250ms 复查一次）；JNI_OnLoad 是同步的、
     * 远在 1s 内返回，从而避开窗口。
     *
     * 超时（15s）仍拿不到稳定观测时按兜底返回，不因此不装功能。
     */
    private fun waitForLibaffBiz(timeoutMs: Long = 15_000L): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var stableSince = -1L
        // 用 elapsedRealtime 而不是 currentTimeMillis：进程被厂商 freezer 冻住再解冻时
        // 时钟已经越过 deadline，循环会立刻退出，不会在解冻后接着读一次 maps（见
        // docs/work-items/wechat-8.0.80-wake-deferred-startup-crash.md 里的崩溃栈）。
        while (SystemClock.elapsedRealtime() < deadline) {
            if (isLibaffBizLoaded()) {
                val now = SystemClock.elapsedRealtime()
                if (stableSince < 0) stableSince = now
                if (now - stableSince >= LIBAFF_BIZ_GRACE_MS) {
                    WeLogger.i(TAG, "等 libaff_biz 稳定后才初始化：映射已连续存在 ${now - stableSince}ms")
                    return true
                }
            } else {
                stableSince = -1L
            }
            Thread.sleep(LIBAFF_BIZ_POLL_INTERVAL_MS)
        }
        WeLogger.w(TAG, "等 libaff_biz 稳定超时（${timeoutMs}ms），按兜底路径继续")
        return isLibaffBizLoaded()
    }

    /**
     * 直接用 `Os.open/read` 扫 `/proc/self/maps`。
     *
     * 不能用 `File(...).useLines`：构造 `FileInputStream` 会走 `CloseGuard.openWithCallSite`
     * → 构造 `Throwable` → `fillInStackTrace` → ART `StackVisitor::WalkStack`，在刚被解冻的
     * USAP 池进程里这一步会踩到空函数指针（SIGSEGV，pc=0），直接把微信主进程带走。
     * 轮询一次 maps 完全不需要异常栈。
     */
    private fun isLibaffBizLoaded(): Boolean = try {
        val fd = Os.open("/proc/self/maps", OsConstants.O_RDONLY, 0)
        try {
            containsNeedle(fd, LIBAFF_BIZ_NEEDLE)
        } finally {
            runCatching { Os.close(fd) }
        }
    } catch (_: Throwable) {
        false
    }

    /** 分块读 fd，命中 needle 返回 true；跨块边界用 needle.size-1 字节的重叠兜住。 */
    private fun containsNeedle(fd: FileDescriptor, needle: ByteArray): Boolean {
        val chunk = ByteArray(64 * 1024)
        val overlap = needle.size - 1
        var carry = 0
        while (true) {
            val read = Os.read(fd, chunk, carry, chunk.size - carry)
            if (read <= 0) return false
            val length = carry + read
            if (indexOfBytes(chunk, length, needle) >= 0) return true
            carry = minOf(overlap, length)
            System.arraycopy(chunk, length - carry, chunk, 0, carry)
        }
    }

    private fun indexOfBytes(haystack: ByteArray, length: Int, needle: ByteArray): Int {
        val limit = length - needle.size
        if (limit < 0) return -1
        outer@ for (start in 0..limit) {
            for (offset in needle.indices) {
                if (haystack[start + offset] != needle[offset]) continue@outer
            }
            return start
        }
        return -1
    }

    private fun acquireApplication(timeoutMs: Long = APP_WAIT_MS): Application? {
        var app = getHostApplication()
        if (app != null) return app
        // 万一还没走到 Application.onCreate，重试一小段时间。
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(LIBAFF_BIZ_POLL_INTERVAL_MS)
            app = getHostApplication()
            if (app != null) return app
        }
        return null
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
