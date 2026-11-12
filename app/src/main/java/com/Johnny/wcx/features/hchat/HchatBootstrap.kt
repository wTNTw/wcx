package com.Johnny.wcx.features.hchat

import android.content.Context
import com.Johnny.wcx.constants.PackageNames
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.reflection.ClassLoaders
import h.Hchat.dexkit.DexBridgeHolder
import h.Hchat.dexkit.DexFinder
import h.Hchat.event.EventBus
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.Feature
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.api.core.WechatApiFeature
import h.Hchat.hooks.items.script.ScriptPluginFeature
import h.Hchat.loader.utils.NativeLibraryLoader
import h.Hchat.preferences.ConfigStore
import h.Hchat.ui.UIRegistry
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.luckypray.dexkit.DexKitBridge

/**
 * WCX 侧的 Hchat 脚本子系统引导。
 *
 * 设计原则（可插拔）：
 * - 只安装脚本链路的最小集合：[WechatApiFeature]（微信公共 API）+ [ScriptPluginFeature]（脚本插件）。
 *   Hchat 其余 Feature（会话分组/防撤回/自动回复等）一律不装，避免与 WCX 现有同名功能双 Hook 冲突。
 * - 引导只新增、不修改 WCX 既有初始化：拿掉本文件与 [com.Johnny.wcx.loader.startup.WeLauncher] 里的
 *   一行调用，脚本子系统即完全下线，其余功能零影响。
 * - 32 位进程直接跳过（Hchat 的 Proot/silk 等原生库只有 arm64-v8a）。
 *
 * 初始化顺序对齐上游 FeatureManager.installAll 语义：
 * 1) Native 库（dexkit/silk）；2) DexKit 后台任务内构建 DexFinder/DexBridgeHolder/FeatureContext；
 * 3) 全部 Feature 先 onInit（注册 settings provider）再 install；4) markDexBridgeReady 放行 BRIDGE 阶段任务。
 */
object HchatBootstrap {

    private const val TAG = "[WCX:HchatBootstrap]"

    /** 已完成引导标记（供诊断页查询，只读）。 */
    @Volatile
    var bootstrapped: Boolean = false
        private set

    private val pendingWrapperRetries = java.util.concurrent.atomic.AtomicInteger(0)
    private const val MAX_WRAPPER_RETRIES = 10
    private const val WRAPPER_RETRY_DELAY_MS = 3_000L

    /** 引导完成后的上下文（给「数据库 wrapper 补救」用）。 */
    @Volatile
    private var featureContext: FeatureContext? = null

    /**
     * 用主模块（WeDatabaseApi）解析出来的 SqliteDB wrapper 类补装 Hchat 的数据库监听。
     *
     * 背景：8.0.80 起 Hchat 的 DexFinder 靠 "MicroMsg.SqliteDB" / "sql is null " 找不到 wrapper，
     * 于是 `WeChatDatabaseListenerApi.isOperational()` 永远 false →「消息变更监听未就绪」。
     * 主模块用自己的形状匹配能找到同一个类，这里把结果塞进去并重新 install 一次。
     */
    fun applyHostSqliteDbWrapper(wrapperClass: Class<*>?) {
        if (wrapperClass == null) return
        val ctx = featureContext
        val apiReady = runCatching {
            h.Hchat.hooks.api.core.WeChatApis.databaseChanges() != null &&
                h.Hchat.hooks.api.core.WeChatApis.messageChanges() != null
        }.getOrDefault(false)
        if (ctx == null || !apiReady) {
            // 两边顺序不固定：主模块解析出 wrapper 时，Hchat 引导可能还没跑完。
            // 这里延后重试，最多 ~30s（引导正常几秒内完成）。
            if (pendingWrapperRetries.addAndGet(1) > MAX_WRAPPER_RETRIES) return
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                applyHostSqliteDbWrapper(wrapperClass)
            }, WRAPPER_RETRY_DELAY_MS)
            return
        }
        runCatching {
            val finder = ctx.dexFinder()
            // 注意：wrapper 类可能已经被 DexFinder 自己填上了（我们上一次推的），
            // 但 Hchat 的监听是在「还没拿到 wrapper」时 install 过一次、失败后不会再自己重来。
            // 所以这里无条件再 install 一次（install() 内部是幂等的：operational 就直接返回）。
            if (finder.sqliteDbWrapperClass != wrapperClass) {
                finder.sqliteDbWrapperClass = wrapperClass
                WeLogger.i(TAG, "补装 Hchat 数据库监听：wrapper=${wrapperClass.name}")
            }
            runCatching { h.Hchat.hooks.api.core.WeChatApis.databaseChanges()?.install() }
            runCatching { h.Hchat.hooks.api.core.WeChatApis.messageChanges()?.install() }
        }.onFailure { WeLogger.w(TAG, "补装 Hchat 数据库监听失败", it) }
    }

    fun install(hostContext: Context) {
        if (!android.os.Process.is64Bit()) {
            WeLogger.i(TAG, "32 位进程，跳过 Hchat 脚本子系统引导")
            return
        }
        Thread({
            try {
                val moduleClassLoader: ClassLoader = HchatBootstrap::class.java.classLoader
                    ?: ClassLoaders.MODULE

                // 1. Native 库：dexkit 必需；silk 可选（语音编码，失败只降级不影响脚本）。
                val nll = NativeLibraryLoader()
                nll.loadDexKit(hostContext, moduleClassLoader)
                runCatching { nll.loadSilkCodec(hostContext, moduleClassLoader) }

                // 2. 模块自身 Context（上游用固定包名 h.Hchat；WCX 模块包名不同，用 PackageNames.MODULE）。
                val moduleContext: Context = runCatching {
                    hostContext.createPackageContext(
                        PackageNames.MODULE,
                        Context.CONTEXT_IGNORE_SECURITY or Context.CONTEXT_INCLUDE_CODE
                    )
                }.getOrNull() ?: hostContext

                val configStore = ConfigStore(hostContext)

                // 3. DexKit 解析在后台任务中完成（DexInstallScheduler 统一生成代保护）。
                //    宿主微信的 apk 路径与 ClassLoader 直接从 hostContext 获取
                //    （WCX 的 StartupAgent 加载链没有 XC_LoadPackage.LoadPackageParam，
                //    这里按上游 FeatureContext 的要求合成一个等价的 LoadPackageParam）。
                DexInstallScheduler.runDexKitTask {
                    try {
                        val apkPath = hostContext.applicationInfo.sourceDir
                        val hostClassLoader = hostContext.classLoader
                        // WCX 的 libxposed 加载链没有 LoadPackageParam；api-82 jar 里该类构造器均不可用
                        // （无参与 (String) 都不存在）。优先取 legacy 入口缓存的真实 lpparam，
                        // 取不到就传 null（hchat 内仅 LegacyChatInputFeature 用到，且不安装）。
                        val lpparam = runCatching {
                            com.Johnny.wcx.loader.entry.xp51.Xp51HookEntry.getLoadPackageParam()
                        }.getOrNull()
                        val dexKit = DexKitBridge.create(apkPath)
                        val finder = DexFinder(dexKit, hostClassLoader, hostContext)
                        val bridgeHolder = DexBridgeHolder(dexKit, finder, hostClassLoader, apkPath)
                        val context = FeatureContext(
                            hostContext,
                            moduleContext,
                            hostClassLoader,
                            lpparam,
                            dexKit,
                            finder,
                            EventBus.get(),
                            configStore,
                            bridgeHolder,
                            UIRegistry.get()
                        )

                        featureContext = context

                        // 4. 只装脚本最小集：先 onInit（注册设置 provider），再 install。
                        val features = listOf(WechatApiFeature(), ScriptPluginFeature())
                        val initialized = ArrayList<Feature>(features.size)
                        for (feature in features) {
                            try {
                                feature.onInit(context)
                                initialized.add(feature)
                            } catch (t: Throwable) {
                                WeLogger.e(TAG, "onInit 失败: ${feature.featureId()}", t)
                            }
                        }
                        for (feature in initialized) {
                            try {
                                val enabled = runCatching { feature.isEnabled(context) }.getOrDefault(true)
                                if (!enabled) {
                                    WeLogger.i(TAG, "feature 未启用，跳过: ${feature.featureId()}")
                                    continue
                                }
                                feature.install(context)
                                WeLogger.i(TAG, "feature 已安装: ${feature.featureId()}")
                            } catch (t: Throwable) {
                                WeLogger.e(TAG, "install 失败: ${feature.featureId()}", t)
                            }
                        }

                        // 5. 放行 BRIDGE 阶段的延迟任务（微信 API resolveAll / 数据库解析）。
                        DexInstallScheduler.markDexBridgeReady()
                        bootstrapped = true
                        WeLogger.i(TAG, "Hchat 脚本子系统引导完成")
                    } catch (t: Throwable) {
                        WeLogger.e(TAG, "DexKit 阶段引导失败: ${t.message}", t)
                    }
                }
            } catch (t: Throwable) {
                WeLogger.e(TAG, "引导失败: ${t.message}", t)
            }
        }, "WCX-HchatInit").apply { isDaemon = true }.start()
    }
}
