package com.Johnny.wcx.features.core

import com.tencent.mm.ui.LauncherUI
import com.Johnny.wcx.constants.Preferences
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.cache.DexCacheManager
import com.Johnny.wcx.features.api.ui.WeSettingsInjector
import com.Johnny.wcx.ui.content.DexResolver
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.HostInfo
import com.Johnny.wcx.utils.HookEnumCollector
import com.Johnny.wcx.utils.TargetProcesses
import com.Johnny.wcx.utils.VersionCompat
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.showToast
import com.Johnny.wcx.utils.perf.StartupPerformance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

object FeaturesLoader {

    /** 并发读 feature 缓存的阈值与线程数。 */
    private const val PARALLEL_CACHE_THRESHOLD = 8
    private const val PARALLEL_CACHE_THREADS = 4

    private const val TAG = "FeaturesLoader"

    fun loadFeatures() {
        val allFeatures = FeaturesProvider.ALL_HOOK_ITEMS
        val allDexItems = allFeatures.filterIsInstance<IResolveDex>()

        val t0 = android.os.SystemClock.elapsedRealtime()
        val outdatedItems = DexCacheManager.getOutdatedItems(allDexItems)
        val t1 = android.os.SystemClock.elapsedRealtime()
        val validItems = allDexItems - outdatedItems.toSet()

        if (outdatedItems.isNotEmpty())
            WeLogger.i(TAG, "found ${validItems.size} valid items, ${outdatedItems.size} outdated items")

        // 只从缓存加载「完整」的条目；不完整的条目绝不启动 —— 缺的委托是 placeholder，
        // 带着它启动会让 feature 里的 hook 拿到假方法/空数组（8.0.80 实测会 ArrayIndexOutOfBounds
        // 甚至把微信界面搞白）。跳过只是该功能不生效，安全得多。
        val (cacheFailedItems, partialItems) = loadDescriptorsFromCache(validItems)
        val t2 = android.os.SystemClock.elapsedRealtime()
        val brokenFromCache = (cacheFailedItems + partialItems).distinct()

        // 当前微信版本已经确认「凑不齐」的功能：跳过但不再弹窗。
        // 记录按 版本号+APK 大小/mtime 指纹保存，换微信（升级）后自动失效重试。
        val knownUnavailableNames = runCatching {
            com.Johnny.wcx.dexkit.cache.DexUnavailableStore.items(HostInfo.application)
        }.getOrDefault(emptySet())
        val allBrokenItems = (outdatedItems + brokenFromCache).distinct()
        val (knownBroken, actionableBroken) = allBrokenItems.partition { item ->
            (item as? BaseFeature)?.displayName in knownUnavailableNames
        }
        if (knownBroken.isNotEmpty()) {
            WeLogger.i(
                TAG,
                "跳过 ${knownBroken.size} 个当前微信版本不可用的功能（不再弹窗）: " +
                    knownBroken.joinToString { (it as? BaseFeature)?.displayName ?: it.javaClass.simpleName },
            )
        }
        if (actionableBroken.isNotEmpty())
            handleBrokenItems(actionableBroken)

        // 拆分两批：只保留少量「首屏必需」的现在装，其余全部等首帧渲染完后装。
        // 实测：每个 feature 的 startup 只要 ~1.6ms，没有单点热点（208 个共 340ms），
        // 所以能省的只能靠「少装一点」，靠不了「优化某个慢 feature」。
        val (earlyFeatures, deferredFeatures) = allFeatures.partition { feature ->
            val categories = (feature as? BaseFeature)?.categories.orEmpty()
            // 实测最贵的几个（设置模块入口 101ms / 莫奈引擎 33ms / 视频号分享菜单 12ms）
            // 都不影响首屏渲染，按名字单独推到首帧后。
            val deferByName = (feature as? BaseFeature)?.name in DEFER_BY_NAME
            // 没标注分类的按「必需」处理，宁可慢一点也不要漏装。
            !deferByName && (categories.isEmpty() || categories.any { it in EARLY_CATEGORIES })
        }

        val earlyStartedAt = android.os.SystemClock.elapsedRealtime()
        startFeatureBatchWhenPrefsReady(earlyFeatures, allBrokenItems) {
            val elapsed = android.os.SystemClock.elapsedRealtime() - earlyStartedAt
            WeLogger.i(TAG, "enabling all hook items took ${elapsed}ms（首屏必需 ${earlyFeatures.size} 个）")
            StartupPerformance.mark("early_features_ready")
            StartupPerformance.emitOnce(TargetProcesses.currentName)
        }

        if (deferredFeatures.isNotEmpty()) {
            runAfterFirstFrame("延后批次 ${deferredFeatures.size} 个") {
                val deferredStartedAt = android.os.SystemClock.elapsedRealtime()
                startFeatureBatchWhenPrefsReady(deferredFeatures, allBrokenItems) {
                    val elapsed = android.os.SystemClock.elapsedRealtime() - deferredStartedAt
                    WeLogger.i(TAG, "首帧后延后批次完成: ${deferredFeatures.size} 个, 耗时 ${elapsed}ms")
                    StartupPerformance.mark("deferred_features_ready")
                }
            }
        }

        WeLogger.i(
            TAG,
            "loadFeatures 耗时拆解: outdated=${t1 - t0}ms cache=${t2 - t1}ms " +
                "startupDispatch=${android.os.SystemClock.elapsedRealtime() - t2}ms",
        )

        // 功能全部加载完 → 30 秒后若进程仍存活就清零「连续启动失败」计数，
        // 避免 StartupCrashGuard 把偶发的一次崩溃也算成隐患。
        runCatching {
            if (TargetProcesses.isInMain) {
                com.Johnny.wcx.loader.startup.StartupCrashGuard.scheduleHealthyReset()
            }
        }

        if (TargetProcesses.isInMain && Preferences.showStartupToast) {
            showToast("WCX 加载成功!")
        }
    }

    // ---------------------------------------------------------------------------

    /**
     * 逐委托从缓存恢复状态。
     *
     * - 某个委托的 key 缺失 → 其他委托不受影响，仍正常加载。
     * - 有任意 key 缺失的 item 加入返回列表，等待 DexKit 重新扫描。
     * - 缓存文件整体读取失败 → 删除损坏文件，整个 item 加入返回列表。
     */
    /**
     * 从缓存加载描述符。
     *
     * 返回 (完全没有可用缓存的, 只加载了一部分的)。
     *
     * 两者要区别对待：**部分加载**的 item 里已经解析出来的委托是好的，功能可以先按这些委托
     * 启动；以前把这种情况也算作「损坏」，于是每次启动都把它们丢进 DEX 适配弹窗 —— 8.0.80 上
     * 「朋友圈服务」就卡在这个循环里（它的某些委托在这个版本匹配不到，缓存永远不完整）。
     */
    private fun loadDescriptorsFromCache(items: List<IResolveDex>): Pair<List<IResolveDex>, List<IResolveDex>> {
        // 逐条读缓存文件 + 反序列化，条目数上百；各条互不依赖，多线程分摊能省下可观的主线程等待。
        if (items.size >= PARALLEL_CACHE_THRESHOLD) {
            return loadDescriptorsFromCacheParallel(items)
        }
        val failedItems = mutableListOf<IResolveDex>()
        val partialItems = mutableListOf<IResolveDex>()

        for (item in items) {
            loadSingleItemCache(item, failedItems, partialItems)
        }
        return failedItems to partialItems
    }

    /** 并发版：[loadDescriptorsFromCache] 的同构实现，结果与串行版一致。 */
    private fun loadDescriptorsFromCacheParallel(items: List<IResolveDex>): Pair<List<IResolveDex>, List<IResolveDex>> {
        val failedItems = java.util.Collections.synchronizedList(mutableListOf<IResolveDex>())
        val partialItems = java.util.Collections.synchronizedList(mutableListOf<IResolveDex>())
        val threadCount = minOf(PARALLEL_CACHE_THREADS, items.size)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(threadCount) { r ->
            Thread(r, "WCX-FeatureCache").apply { isDaemon = true }
        }
        try {
            val futures = items.map { item -> pool.submit { loadSingleItemCache(item, failedItems, partialItems) } }
            futures.forEach { future ->
                runCatching { future.get() }
                    .onFailure { WeLogger.w(TAG, "并发加载 feature 缓存失败: ${it.message}") }
            }
        } finally {
            pool.shutdown()
        }
        return failedItems.toList() to partialItems.toList()
    }

    /** 单条：从缓存恢复委托，失败/不完整时记入对应列表。 */
    private fun loadSingleItemCache(
        item: IResolveDex,
        failedItems: MutableList<IResolveDex>,
        partialItems: MutableList<IResolveDex>,
    ) {
        val path = (item as BaseFeature).displayName
        try {
            val cache = DexCacheManager.loadItemCache(item)
            if (cache == null) {
                WeLogger.w(TAG, "cache missing for $path")
                failedItems += item
                return
            }

            // loadFromCache 逐委托加载；返回未命中的 key 集合
            val missingKeys = item.loadFromCache(cache)
            if (missingKeys.isNotEmpty()) {
                val total = item.dexDelegates.size
                val loaded = total - missingKeys.size
                WeLogger.w(TAG, "$path: loaded $loaded/$total delegates from cache, missing: $missingKeys")
                if (loaded > 0) {
                    // 部分可用：按已解析的委托正常启动，不再反复弹 DEX 适配窗。
                    partialItems += item
                } else {
                    failedItems += item
                }
            }
        } catch (e: Exception) {
            WeLogger.e(TAG, "cache load failed for $path", e)
            runCatching { DexCacheManager.deleteCache(path) }
            failedItems += item
        }
    }

    /**
     * 宿主配置（SharedPreferences/MMKV）是否已经可读。
     *
     * 用微信自己的 key 探测：重启后首次启动时，宿主的 prefs 可能尚未加载完，
     * 此时读开关会全部返回默认值 false。
     */
    private fun hostPrefsReady(): Boolean = runCatching {
        // 探测模块自己的开关存储（WePrefs 用的是 MMKV，不是微信的 SharedPreferences）：
        // 写一个探针 key 再读回来，能读到才算真的可用。MMKV 未初始化/文件异常时这里会抛或读回 false。
        com.Johnny.wcx.preferences.WePrefs.putBool(PREFS_READY_PROBE_KEY, true)
        com.Johnny.wcx.preferences.WePrefs.getBoolOrFalse(PREFS_READY_PROBE_KEY)
    }.getOrDefault(false)

    /**
     * 等宿主配置就绪后再启动这一批；未就绪则短延迟重试，**绝不**把 feature 当成「用户关闭」。
     * 重试到上限仍未就绪时按默认值启动，并打 W 说明可能有一批功能未生效。
     */
    private fun startFeatureBatchWhenPrefsReady(
        features: List<BaseFeature>,
        allBrokenItems: List<IResolveDex>,
        attempt: Int = 1,
        onComplete: () -> Unit = {},
    ) {
        if (features.isEmpty()) {
            onComplete()
            return
        }
        if (hostPrefsReady()) {
            if (attempt > 1) {
                WeLogger.i(TAG, "开关配置已就绪（第 $attempt 次尝试），开始启动本批 ${features.size} 个")
            }
            try {
                startFeatureBatch(features, allBrokenItems)
            } finally {
                onComplete()
            }
            return
        }
        if (attempt >= PREFS_READY_MAX_ATTEMPTS) {
            WeLogger.w(
                TAG,
                "开关配置 ${attempt - 1} 次重试仍不可读，按默认值启动本批 ${features.size} 个" +
                    "（其中被读成关闭的功能本次不会生效）",
            )
            try {
                startFeatureBatch(features, allBrokenItems)
            } finally {
                onComplete()
            }
            return
        }
        WeLogger.i(
            TAG,
            "开关配置尚未就绪（第 $attempt 次），${PREFS_READY_RETRY_DELAY_MS}ms 后重试本批 ${features.size} 个",
        )
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            startFeatureBatchWhenPrefsReady(features, allBrokenItems, attempt + 1, onComplete)
        }, PREFS_READY_RETRY_DELAY_MS)
    }

    /**
     * 启动一批 feature（含跳过判定与逐个隔离）。
     * 单个 feature 启动失败绝不能中断整批：任一 feature 在 startup() 里抛异常，
     * 排在它后面的功能（含各类 API 服务）就全都不会启动，表现成「开关是开的但功能没反应」。
     */
    private fun startFeatureBatch(features: List<BaseFeature>, allBrokenItems: List<IResolveDex>) {
        HookEnumCollector.reset()
        val costs = ArrayList<Pair<String, Long>>(features.size)
        features.forEach { feature ->
            val isBroken = feature is IResolveDex && allBrokenItems.contains(feature)

            // 当前微信版本已不支持的功能（微信把这部分实现删掉了）：完全不启动，
            // 即便旧配置里开关仍是开着的。unavailableReason 必须是稳定判定（版本号），
            // 不能用 DexKit 解析状态——解析未完成时同样为 true，会误跳过。
            if (feature is SwitchFeature && feature.unavailableReason != null) {
                WeLogger.i(TAG, "skipping ${feature.name} — 当前微信版本不支持：${feature.unavailableReason}")
                return@forEach
            }

            if (isBroken && feature !is WeSettingsInjector) {
                WeLogger.w(TAG, "skipping ${feature.name} — incomplete cache, awaiting re-resolution")
                return@forEach
            }

            val started = android.os.SystemClock.elapsedRealtime()
            HookEnumCollector.currentFeatureName.set(feature.displayName)
            try {
                runCatching { feature.startup() }
                    .onFailure { WeLogger.e(TAG, "startup failed for ${feature.displayName}", it) }
            } finally {
                HookEnumCollector.currentFeatureName.remove()
                costs += feature.displayName to (android.os.SystemClock.elapsedRealtime() - started)
            }
        }
        HookEnumCollector.logSummary()
        logSlowFeatures(costs)
        // 统计真正装上 hook 的数量：与总数量差距过大说明大部分 feature 的开关被读成关闭
        // （典型就是宿主配置未就绪），这条日志是排查「白屏/不生效」的第一手证据。
        val active = features.count { it.isActive }
        WeLogger.i(TAG, "本批 ${features.size} 个：已启用 $active 个")
        // 只有在「确实读到过真实开关」时才写快照：否则会把「全读成 false」也固化下来。
        if (active > 0 && hostPrefsReady()) {
            FeatureSwitchSnapshot.save(features.map { it.name }, features.filter { it.isActive }.map { it.name })
        }
    }

    /** 把本批最慢的 feature 打出来（只看 >= [SLOW_FEATURE_MS] 的，最多 15 条）。 */
    private fun logSlowFeatures(costs: List<Pair<String, Long>>) {
        val slow = costs.filter { it.second >= SLOW_FEATURE_MS }.sortedByDescending { it.second }
        if (slow.isEmpty()) return
        val top = slow.take(15)
        WeLogger.i(
            TAG,
            "慢 feature（>=${SLOW_FEATURE_MS}ms，共 ${slow.size} 个）: " +
                top.joinToString { "${it.first}=${it.second}ms" } +
                if (slow.size > top.size) " …" else "",
        )
    }

    /**
     * 在「首帧已渲染」后执行（主线程）。
     *
     * 双保险：主线程消息队列空闲（IdleHandler）就立即跑；若一直不空闲，
     * [FIRST_FRAME_FALLBACK_MS] 后强制跑，保证延后批次不会因为持续绘制而饿死。
     */
    private fun runAfterFirstFrame(reason: String, block: () -> Unit) {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        val runOnce = {
            if (done.compareAndSet(false, true)) {
                runCatching { block() }
                    .onFailure { WeLogger.e(TAG, "$reason 执行失败", it) }
            }
        }
        handler.postDelayed({ runOnce() }, FIRST_FRAME_FALLBACK_MS)
        handler.post {
            runCatching {
                android.os.Looper.myQueue().addIdleHandler { runOnce(); false }
            }.onFailure { runOnce() }
        }
    }

    /**
     * 首帧前就要装上的分类白名单（其余全部延后到首帧后）。
     *
     * 只保留直接决定「首页长什么样」的：
     * - `API`：各类服务基元，其它 feature 依赖，必须先生效；
     * - `聊天`：用户点开微信很可能马上进会话，输入栏/消息菜单/防撤回依赖它；
     * - `首页右上角菜单` / `界面美化` / `密友功能`：首页可见（菜单项、负一屏、会话列表过滤）。
     *
     * 剩下的（朋友圈/视频号/小程序/支付/通话/系统与隐私/联系人与群组…）都是要进到对应页面
     * 才会用到，推到首帧后装（IdleHandler 命中约在首帧后 0–2s，且带 1.5s 兜底）。
     */
    private val EARLY_CATEGORIES = setOf(
        "API",
        "聊天",
        "首页右上角菜单",
        "界面美化",
        "密友功能",
    )

    /**
     * 即使分类属于「首屏必需」，也可以安全推到首帧后的单个 feature（按实测耗时挑选）。
     * 这些都是模块自己的设置/配色/分享菜单钩子，不影响微信首页首帧渲染。
     */
    private val DEFER_BY_NAME = setOf(
        "设置模块入口",
        "莫奈引擎",
        "视频号分享菜单扩展",
    )

    /** 延后批次的兜底延迟：首帧一直不空闲时也不能拖太久。 */
    private const val FIRST_FRAME_FALLBACK_MS = 1_500L

    /** 单个 feature 超过这个耗时才进「慢 feature」名单。 */
    private const val SLOW_FEATURE_MS = 8L

    /**
     * 开关配置未就绪时的重试参数。
     *
     * 重启后第一次启动微信时，微信自己的 SharedPreferences/MMKV 可能还没加载完，
     * 这时读开关会全部拿到默认值 false —— 于是 169 个 feature 集体空转（实测 5.59ms，
     * 正常是 ~185ms），首屏直接白屏。所以「读不到配置」绝不能当成「用户关了」，
     * 而是短暂重试等宿主把配置加载出来。
     */
    private const val PREFS_READY_MAX_ATTEMPTS = 8
    private const val PREFS_READY_RETRY_DELAY_MS = 400L

    /** 用来探测模块开关存储是否可用的探针 key（写后立即读回校验）。 */
    private const val PREFS_READY_PROBE_KEY = "__wcx_prefs_ready_probe"

    private fun handleBrokenItems(brokenItems: List<IResolveDex>) {
        if (Preferences.noDexResolve) return
        if (!TargetProcesses.isInMain) return

        // 版本兼容性检测：大量Hook失效时输出警告日志并弹窗提示用户
        try {
            val (compatible, message) = VersionCompat.checkCompatibility(
                brokenItems.size,
                FeaturesProvider.ALL_HOOK_ITEMS.filterIsInstance<IResolveDex>().size
            )
            if (!compatible) {
                WeLogger.w(TAG, "版本兼容性警告: $message")
                // 弹窗提示用户当前微信版本部分功能暂未适配
                showToast("当前微信版本部分功能暂未适配，等待模块更新")
            } else {
                WeLogger.i(TAG, "版本兼容性: $message")
            }
        } catch (e: Throwable) {
            WeLogger.e(TAG, "版本兼容性检测异常", e)
        }

        WeLogger.i(TAG, "launching background coroutine to repair ${brokenItems.size} items")

        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            var activity = LauncherUI.getInstance()
            var waited = 0L
            while (activity == null && waited < 30_000L) {
                delay(1_000.milliseconds)
                waited += 1_000
                activity = LauncherUI.getInstance()
            }

            if (activity == null) {
                WeLogger.w(TAG, "no LauncherUI available for dex-repair dialog; skipping")
                return@launch
            }

            val boundActivity = activity
            withContext(Dispatchers.Main) {
                showComposeDialog(boundActivity, directlyDismissable = false) {
                    DexResolver(
                        boundActivity,
                        brokenItems,
                        MainScope(),
                        onDismiss
                    )
                }
            }
        }
    }
}
