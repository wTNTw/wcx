package com.Johnny.wcx.dynamic

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import com.Johnny.wcx.constants.PackageNames
import com.Johnny.wcx.dexkit.cache.DexCacheManager
import com.Johnny.wcx.dexkit.cache.DexUnavailableStore
import com.Johnny.wcx.dexkit.resolution.resolveAllDex
import com.Johnny.wcx.features.core.FeaturesLoader
import com.Johnny.wcx.features.core.FeaturesProvider
import com.Johnny.wcx.utils.HostInfo
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.reflection.DexKit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.luckypray.dexkit.DexKitBridge
import java.io.File

/**
 * 纯本地自适应引擎 — 无云端依赖。
 *
 * 核心职责：
 * 1. 使用 DexKit 进行本地字节码扫描
 * 2. 自动检测微信版本变更
 * 3. 自动扫描和匹配 Hook，无需云端服务器
 * 4. 基于 SharedPreferences 的简单缓存机制
 * 5. 检测到变更后，通过回调通知UI，等待用户确认后才执行适配
 * 6. 不后台静默自动适配
 */
object LocalAdaptationEngine {

    private const val TAG = "LocalAdaptationEngine"
    private const val PREFS_ENGINE = "local_adaptation_engine"
    private const val KEY_LAST_VERSION = "last_wechat_version"
    private const val KEY_LAST_APK_SIZE = "last_apk_size"
    private const val KEY_LAST_APK_MTIME = "last_apk_mtime"
    private const val KEY_ADAPTATION_COUNT = "adaptation_count"
    private const val KEY_LAST_ADAPTATION_TIME = "last_adaptation_time"
    private const val KEY_RETRY_ATTEMPTS = "quiet_retry_attempts"
    /** 静默重试上限，避免每次启动都跑 DexKit。 */
    private const val MAX_QUIET_RETRIES = 2

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var isInitialized = false

    @Volatile
    private var isAdapting = false

    private var prefs: SharedPreferences? = null

    // 适配完成回调
    private val onCompleteCallbacks = mutableListOf<() -> Unit>()

    // 适配确认回调 — 当检测到微信版本变更时，通过此回调通知UI
    private var adaptationConfirmCallback: ((String) -> Unit)? = null

    enum class EngineState {
        IDLE, ADAPTING, COMPLETED, FAILED, PENDING_CONFIRMATION
    }

    @Volatile
    var state: EngineState = EngineState.IDLE
        private set

    /**
     * 初始化引擎。
     * 在 WeLauncher 中调用，主进程启动时触发。
     */
    fun init(context: Context) {
        if (isInitialized) return
        isInitialized = true

        prefs = context.getSharedPreferences(PREFS_ENGINE, Context.MODE_PRIVATE)

        WeLogger.i(TAG, "initializing local adaptation engine")
        WeLogger.i(TAG, "wechat version: ${HostInfo.versionName}, code: ${HostInfo.versionCode}")

        checkAndAdapt()
    }

    /**
     * 注册适配确认回调。
     * 当检测到微信版本变更时，会调用此回调，传入变更描述信息。
     * UI层需要在此回调中弹出确认对话框，用户确认后调用 [confirmAdaptation]。
     */
    fun onAdaptationConfirmationRequired(callback: (String) -> Unit) {
        adaptationConfirmCallback = callback
        // 如果当前状态是等待确认，立即触发回调
        if (state == EngineState.PENDING_CONFIRMATION) {
            val message = buildChangeMessage()
            mainHandler.post { callback(message) }
        }
    }

    /**
     * 用户确认后执行适配。
     */
    fun confirmAdaptation() {
        if (state != EngineState.PENDING_CONFIRMATION) {
            WeLogger.w(TAG, "confirmAdaptation called but state is $state")
            return
        }
        WeLogger.i(TAG, "user confirmed adaptation, starting...")
        startAdaptation()
    }

    /**
     * 用户拒绝适配。
     */
    fun cancelAdaptation() {
        if (state != EngineState.PENDING_CONFIRMATION) {
            WeLogger.w(TAG, "cancelAdaptation called but state is $state")
            return
        }
        WeLogger.i(TAG, "user cancelled adaptation, keeping previous state")
        state = EngineState.COMPLETED
    }

    /**
     * 注册适配完成回调。
     */
    fun onAdaptationComplete(callback: () -> Unit) {
        onCompleteCallbacks.add(callback)
        if (state == EngineState.COMPLETED) {
            callback()
        }
    }

    /**
     * 获取适配状态摘要。
     */
    fun getStatus(): Map<String, Any> {
        val p = prefs
        return mapOf(
            "state" to state.name,
            "initialized" to isInitialized,
            "isAdapting" to isAdapting,
            "lastVersion" to (p?.getString(KEY_LAST_VERSION, "") ?: ""),
            "currentVersion" to HostInfo.versionName,
            "adaptationCount" to (p?.getInt(KEY_ADAPTATION_COUNT, 0) ?: 0),
            "lastAdaptationTime" to (p?.getLong(KEY_LAST_ADAPTATION_TIME, 0L) ?: 0L)
        )
    }

    /**
     * 强制重新适配（调试用）。
     */
    fun forceReadapt() {
        WeLogger.i(TAG, "force re-adaptation requested")
        prefs?.edit()?.clear()?.apply()
        DexCacheManager.clearAllCache()
        state = EngineState.IDLE
        checkAndAdapt()
    }

    // ==================== 内部实现 ====================

    /**
     * 检查是否需要适配。
     * 适配条件：
     * 1. 首次运行（无缓存记录）
     * 2. 微信版本变更
     * 3. 微信 APK 文件变更（大小或修改时间变化）
     *
     * 检测到变更后，不自动适配，而是通过回调通知UI等待用户确认。
     */
    private fun checkAndAdapt() {
        val p = prefs ?: return

        val currentVersion = "${HostInfo.versionName}${HostInfo.versionCode}"
        val lastVersion = p.getString(KEY_LAST_VERSION, "")

        val apkFile = File(HostInfo.appInfo.sourceDir)
        val currentApkSize = if (apkFile.exists()) apkFile.length() else 0L
        val currentApkMtime = if (apkFile.exists()) apkFile.lastModified() else 0L
        val lastApkSize = p.getLong(KEY_LAST_APK_SIZE, 0L)
        val lastApkMtime = p.getLong(KEY_LAST_APK_MTIME, 0L)

        val versionChanged = currentVersion != lastVersion
        val apkChanged = currentApkSize != lastApkSize || currentApkMtime != lastApkMtime

        if (!versionChanged && !apkChanged && !lastVersion.isNullOrEmpty()) {
            WeLogger.i(TAG, "no changes detected, skipping adaptation")
            state = EngineState.COMPLETED
            // 先把「扫过但仍凑不齐委托」的功能补进不可用清单（只读缓存，很快），
            // 再对它们做一次静默重试。这样 FeaturesLoader 不会把它们当成需要弹窗的损坏项，
            // 也就不会出现「适配 → 报异常 → 重启又要适配」的循环。
            refreshIncompleteFromCache()
            retryUnavailableQuietly()
            return
        }

        WeLogger.i(TAG, "changes detected: versionChanged=$versionChanged, apkChanged=$apkChanged")
        state = EngineState.PENDING_CONFIRMATION

        // APK 变了但版本号没变（微信热更新 / 同版本重打包）时，缓存里的 DexKit 描述符可能指向
        // 已经不存在的方法 —— 之前就是这里没处理，导致 features 用旧签名 hook 时抛
        // NoSuchMethodException（例如 禁用「转发截图」提示 的 y0->Pj(...)）。
        // 清空缓存让本次启动重新解析；解析是后台进行的，失败也不会卡住微信。
        if (apkChanged) {
            runCatching {
                DexCacheManager.clearAllCache()
                WeLogger.w(TAG, "APK 已变更（版本号未变）→ 已清空 DexKit 缓存，本次启动重新解析")
            }.onFailure { WeLogger.e(TAG, "清空 DexKit 缓存失败", it) }
        }

        // 通知UI层需要用户确认
        val callback = adaptationConfirmCallback
        if (callback != null) {
            val message = buildChangeMessage()
            mainHandler.post { callback(message) }
        } else {
            // Zygisk 独立模式没有设置界面可确认（不装 APK），等下去就永远不会适配，
            // 所以这里直接自动适配。
            WeLogger.w(TAG, "no confirmation callback registered → 自动开始适配")
            startAdaptation()
        }
    }

    /**
     * 构建变更提示消息。
     */
    private fun buildChangeMessage(): String {
        val p = prefs ?: return "微信已更新，需要重新适配Dex"
        val lastVersion = p.getString(KEY_LAST_VERSION, "")
        val currentVersion = "${HostInfo.versionName}${HostInfo.versionCode}"
        return if (!lastVersion.isNullOrEmpty()) {
            "检测到微信已更新\n\n上次版本: $lastVersion\n当前版本: $currentVersion\n\n需要重新适配Dex以确保功能正常，是否立即适配？"
        } else {
            "首次检测到微信版本: $currentVersion\n\n需要进行Dex适配，是否立即适配？"
        }
    }

    private fun startAdaptation() {
        if (isAdapting) {
            WeLogger.w(TAG, "adaptation already in progress")
            return
        }

        isAdapting = true
        state = EngineState.ADAPTING

        scope.launch {
            try {
                performAdaptation()
                state = EngineState.COMPLETED
                WeLogger.i(TAG, "adaptation completed successfully")
                notifyComplete()
            } catch (e: Exception) {
                WeLogger.e(TAG, "adaptation failed", e)
                state = EngineState.FAILED
            } finally {
                isAdapting = false
            }
        }
    }

    /**
     * 执行适配流程：
     * 1. 获取 DexKitBridge
     * 2. 扫描所有 Hook 特征
     * 3. 保存缓存
     * 4. 更新版本记录
     */
    private suspend fun performAdaptation() {
        val p = prefs ?: return

        // 步骤 1: 获取 DexKitBridge
        val dexKit = withContext(Dispatchers.IO) {
            try {
                DexKit
            } catch (e: Exception) {
                WeLogger.e(TAG, "failed to acquire DexKitBridge", e)
                throw e
            }
        }

        // 步骤 2: 扫描所有 Hook 特征。Do not advance the stored version when any
        // resolver failed; otherwise the next launch would treat a partial run as complete
        // and never retry the broken descriptors.
        val scanSummary = withContext(Dispatchers.IO) {
            scanAllFeatures(dexKit)
        }
        // 有的功能在新微信版本上确实匹配不到（类/方法改了名），还有一种情况是解析没抛错、
        // 但某些委托始终匹配不到，缓存永远不完整（8.0.80 的「朋友圈服务」）。
        // 这两种都属于「该功能不可用」，**不是适配失败**。
        // 以前这里直接抛异常，版本指纹不保存 → 每次启动都重新完整适配、每次都弹适配窗。
        val incomplete = withContext(Dispatchers.IO) {
            FeaturesProvider.ALL_HOOK_ITEMS
                .filterIsInstance<com.Johnny.wcx.dexkit.abc.IResolveDex>()
                .filter { item ->
                    val name = (item as? com.Johnny.wcx.features.core.BaseFeature)?.displayName
                        ?: item.javaClass.simpleName
                    name !in scanSummary.failedItems && !DexCacheManager.isItemCacheValid(item)
                }
                .map { item ->
                    (item as? com.Johnny.wcx.features.core.BaseFeature)?.displayName
                        ?: item.javaClass.simpleName
                }
        }
        val unavailable = (scanSummary.failedItems + incomplete).distinct()
        if (unavailable.isNotEmpty()) {
            WeLogger.w(
                TAG,
                "本轮适配有 ${unavailable.size}/${scanSummary.attemptedItems} 项在当前微信版本不可用" +
                    "（已记录，不再重复弹窗）: " + unavailable.joinToString(),
            )
            runCatching { DexUnavailableStore.mark(HostInfo.application, unavailable) }
        } else {
            runCatching { DexUnavailableStore.clear(HostInfo.application) }
        }

        // 步骤 3: 保存版本信息（即使有不可用功能也保存，避免每次启动重复适配）。
        val currentVersion = "${HostInfo.versionName}${HostInfo.versionCode}"
        val apkFile = File(HostInfo.appInfo.sourceDir)
        val apkSize = if (apkFile.exists()) apkFile.length() else 0L
        val apkMtime = if (apkFile.exists()) apkFile.lastModified() else 0L

        p.edit()
            .putString(KEY_LAST_VERSION, currentVersion)
            .putLong(KEY_LAST_APK_SIZE, apkSize)
            .putLong(KEY_LAST_APK_MTIME, apkMtime)
            .putInt(KEY_ADAPTATION_COUNT, p.getInt(KEY_ADAPTATION_COUNT, 0) + 1)
            .putLong(KEY_LAST_ADAPTATION_TIME, System.currentTimeMillis())
            .putInt(KEY_RETRY_ATTEMPTS, 0)
            .apply()

        WeLogger.i(TAG, "version info saved: $currentVersion")
    }

    /** 把「已经扫过、但当前微信版本凑不齐委托」的功能补进不可用清单（只读缓存元信息，很便宜）。 */
    private fun refreshIncompleteFromCache() {
        runCatching {
            if (!com.Johnny.wcx.utils.TargetProcesses.isInMain) return
            val context = HostInfo.application
            val incomplete = FeaturesProvider.ALL_HOOK_ITEMS
                .filterIsInstance<com.Johnny.wcx.dexkit.abc.IResolveDex>()
                .filter { DexCacheManager.hasCacheFile(it) && !DexCacheManager.isItemCacheValid(it) }
                .map { (it as com.Johnny.wcx.features.core.BaseFeature).displayName }
            val existing = DexUnavailableStore.items(context)
            val union = existing + incomplete
            if (union != existing) {
                DexUnavailableStore.mark(context, union)
                WeLogger.i(TAG, "补齐不可用清单: ${(union - existing).joinToString()}（共 ${union.size} 项）")
            }
        }.onFailure { WeLogger.w(TAG, "刷新不可用清单失败", it) }
    }

    /**
     * 对 DexUnavailableStore 里的功能静默重试（不弹窗）。
     * 最多 [MAX_QUIET_RETRIES] 轮；成功后写缓存并移出清单，仍不完整的继续留着。
     */
    private fun retryUnavailableQuietly() {
        if (!com.Johnny.wcx.utils.TargetProcesses.isInMain) return
        val context = runCatching { HostInfo.application }.getOrNull() ?: return
        val names = runCatching { DexUnavailableStore.items(context) }.getOrDefault(emptySet())
        if (names.isEmpty()) return

        val p = prefs ?: return
        val attempts = p.getInt(KEY_RETRY_ATTEMPTS, 0)
        if (attempts >= MAX_QUIET_RETRIES) {
            WeLogger.i(TAG, "已静默重试 $attempts 次，跳过（可在设置里「重新适配」强制再来）")
            return
        }
        p.edit().putInt(KEY_RETRY_ATTEMPTS, attempts + 1).apply()

        scope.launch {
            runCatching {
                val dexKit = DexKit
                val stillFailed = mutableSetOf<String>()
                var resolved = 0
                for (item in FeaturesProvider.ALL_HOOK_ITEMS
                    .filterIsInstance<com.Johnny.wcx.dexkit.abc.IResolveDex>()) {
                    val name = (item as? com.Johnny.wcx.features.core.BaseFeature)?.displayName
                        ?: item.javaClass.simpleName
                    if (name !in names) continue
                    runCatching {
                        item.resolveAllDex(dexKit)
                        DexCacheManager.saveItemCache(item)
                        if (DexCacheManager.isItemCacheValid(item)) {
                            resolved++
                            WeLogger.i(TAG, "静默重试成功: $name")
                        } else {
                            stillFailed += name
                            WeLogger.w(TAG, "静默重试后缓存仍不完整: $name")
                        }
                    }.onFailure {
                        stillFailed += name
                        WeLogger.w(TAG, "静默重试仍失败: $name (${it.message})")
                    }
                }
                DexUnavailableStore.mark(context, stillFailed)
                WeLogger.i(
                    TAG,
                    "静默重试完成（第 ${attempts + 1}/$MAX_QUIET_RETRIES 轮）: " +
                        "成功 $resolved，仍不可用 ${stillFailed.size}",
                )
            }.onFailure { WeLogger.w(TAG, "静默重试异常", it) }
        }
    }

    /**
     * 扫描所有已注册的 Feature 的 Dex 特征。
     * 触发 DexKit 解析和缓存保存。
     */
    private data class ScanSummary(
        val attemptedItems: Int,
        val succeededItems: Int,
        val failedItems: List<String>
    )

    private fun scanAllFeatures(dexKit: DexKitBridge): ScanSummary {
        val features = FeaturesProvider.ALL_HOOK_ITEMS.filterIsInstance<com.Johnny.wcx.dexkit.abc.IResolveDex>()
        WeLogger.i(TAG, "scanning ${features.size} Dex-resolvable features...")

        var successCount = 0
        val failedItems = mutableListOf<String>()

        for (item in features) {
            val feature = item as? com.Johnny.wcx.features.core.BaseFeature
            val displayName = feature?.displayName ?: item.javaClass.simpleName
            try {
                // resolveAllDex keeps inline resolution and normal resolution in one active
                // DexKit context; splitting these calls can cause a missing-context exception.
                if (!DexCacheManager.isItemCacheValid(item)) {
                    item.resolveAllDex(dexKit)
                    DexCacheManager.saveItemCache(item)
                    WeLogger.d(TAG, "resolved: $displayName")
                } else {
                    WeLogger.d(TAG, "cache hit: $displayName")
                }
                successCount++
            } catch (e: Exception) {
                failedItems += displayName
                WeLogger.e(TAG, "failed to resolve $displayName", e)
            }
        }

        WeLogger.i(TAG, "scan complete: $successCount success, ${failedItems.size} failed")
        return ScanSummary(features.size, successCount, failedItems)
    }

    private fun notifyComplete() {
        mainHandler.post {
            onCompleteCallbacks.forEach { callback ->
                try { callback() } catch (e: Exception) {
                    WeLogger.e(TAG, "adaptation callback failed", e)
                }
            }
        }
    }
}