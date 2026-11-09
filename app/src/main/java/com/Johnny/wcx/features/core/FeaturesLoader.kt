package com.Johnny.wcx.features.core

import com.tencent.mm.ui.LauncherUI
import com.Johnny.wcx.constants.Preferences
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.cache.DexCacheManager
import com.Johnny.wcx.features.api.ui.WeSettingsInjector
import com.Johnny.wcx.ui.content.DexResolver
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.HostInfo
import com.Johnny.wcx.utils.TargetProcesses
import com.Johnny.wcx.utils.VersionCompat
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.showToast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.measureTime

object FeaturesLoader {

    private const val TAG = "FeaturesLoader"

    fun loadFeatures() {
        val allFeatures = FeaturesProvider.ALL_HOOK_ITEMS
        val allDexItems = allFeatures.filterIsInstance<IResolveDex>()

        val outdatedItems = DexCacheManager.getOutdatedItems(allDexItems)
        val validItems = allDexItems - outdatedItems.toSet()

        if (outdatedItems.isNotEmpty())
            WeLogger.i(TAG, "found ${validItems.size} valid items, ${outdatedItems.size} outdated items")

        // 只从缓存加载「完整」的条目；不完整的条目绝不启动 —— 缺的委托是 placeholder，
        // 带着它启动会让 feature 里的 hook 拿到假方法/空数组（8.0.80 实测会 ArrayIndexOutOfBounds
        // 甚至把微信界面搞白）。跳过只是该功能不生效，安全得多。
        val (cacheFailedItems, partialItems) = loadDescriptorsFromCache(validItems)
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

        val elapsed = measureTime {
            allFeatures.forEach { feature ->
                val isBroken = feature is IResolveDex && allBrokenItems.contains(feature)

                if (isBroken && feature !is WeSettingsInjector) {
                    WeLogger.w(TAG, "skipping ${feature.name} — incomplete cache, awaiting re-resolution")
                    return@forEach
                }

                // 单个 feature 启动失败绝不能中断整轮加载：之前没有隔离，任何一个 feature
                // 在 startup() 里抛异常，排在它后面的功能（含各类 API 服务）就全都不会启动，
                // 表现成「开关是开的但功能没反应」。
                runCatching { feature.startup() }
                    .onFailure { WeLogger.e(TAG, "startup failed for ${feature.displayName}", it) }
            }
        }
        WeLogger.i(TAG, "enabling all hook items took $elapsed")

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
        val failedItems = mutableListOf<IResolveDex>()
        val partialItems = mutableListOf<IResolveDex>()

        for (item in items) {
            val path = (item as BaseFeature).displayName
            try {
                val cache = DexCacheManager.loadItemCache(item)
                if (cache == null) {
                    WeLogger.w(TAG, "cache missing for $path")
                    failedItems += item
                    continue
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

        return failedItems to partialItems
    }

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
