package com.Johnny.wcx.dexkit.cache

import android.content.Context
import com.Johnny.wcx.utils.HostInfo
import com.Johnny.wcx.utils.WeLogger
import org.json.JSONArray
import java.io.File

/**
 * 「当前微信版本确实适配不了」的功能清单。
 *
 * 为什么需要：某个功能的 DexKit 匹配规则在新微信版本上找不到目标时，`resolveAllDex` 会失败、
 * 缓存也不会写。之前的实现里：
 *   - `LocalAdaptationEngine` 因为有失败项而**不保存版本号** → 每次启动都重新适配；
 *   - 失败项永远「缓存无效」→ 每次启动都弹一次 DEX 适配弹窗。
 * 用户看到的就是「适配一次 → 提示异常 → 重启又要完整适配一遍」的死循环。
 *
 * 这里把失败项按 (微信版本 + APK 大小/mtime) 记下来：同一个 APK 上不再反复扫描、不再弹窗，
 * 只在微信再次更新（版本或 APK 变化）时才重新尝试。
 */
object DexUnavailableStore {

    private const val TAG = "DexUnavailableStore"
    private const val PREFS = "dex_unavailable"
    private const val KEY_ITEMS = "items"
    private const val KEY_FINGERPRINT = "fingerprint"

    /** 当前微信 APK 的指纹：版本号 + APK 大小 + mtime，任一变化都视为「换了微信」。 */
    fun fingerprint(): String {
        val apk = runCatching { File(HostInfo.appInfo.sourceDir) }.getOrNull()
        val size = if (apk?.exists() == true) apk.length() else 0L
        val mtime = if (apk?.exists() == true) apk.lastModified() else 0L
        return "${HostInfo.versionName}${HostInfo.versionCode}-$size-$mtime"
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 当前微信版本下已知不可用的功能名（指纹不匹配时返回空，并顺手清掉旧记录）。 */
    fun items(context: Context): Set<String> = runCatching {
        val p = prefs(context)
        val stored = p.getString(KEY_FINGERPRINT, null)
        val current = fingerprint()
        if (stored != current) {
            if (stored != null) {
                WeLogger.i(TAG, "微信已变化（$stored → $current），清空不可用功能记录，允许重新尝试适配")
                p.edit().clear().apply()
            }
            return emptySet()
        }
        val raw = p.getString(KEY_ITEMS, null) ?: return emptySet()
        val array = JSONArray(raw)
        buildSet { for (i in 0 until array.length()) add(array.optString(i)) }
    }.getOrElse {
        WeLogger.w(TAG, "读取不可用功能记录失败: ${it.message}")
        emptySet()
    }

    /** 记录（覆盖）当前微信版本下不可用的功能。 */
    fun mark(context: Context, names: Collection<String>) = runCatching {
        val p = prefs(context)
        val array = JSONArray()
        names.distinct().sorted().forEach(array::put)
        p.edit()
            .putString(KEY_FINGERPRINT, fingerprint())
            .putString(KEY_ITEMS, array.toString())
            .apply()
        WeLogger.i(TAG, "记录 ${names.size} 个当前版本无法适配的功能: ${names.joinToString()}")
    }.getOrElse {
        WeLogger.w(TAG, "写入不可用功能记录失败: ${it.message}")
    }

    /** 手动「重新适配」时清空，让所有功能再试一次。 */
    fun clear(context: Context) = runCatching {
        prefs(context).edit().clear().apply()
        WeLogger.i(TAG, "已清空不可用功能记录（下次启动会重新尝试适配）")
    }.getOrElse {
        WeLogger.w(TAG, "清空不可用功能记录失败: ${it.message}")
    }
}
