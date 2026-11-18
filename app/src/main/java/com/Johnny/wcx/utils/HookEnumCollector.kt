package com.Johnny.wcx.utils

import java.lang.reflect.Constructor
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap

/**
 * WeKite hook 引擎移植前的「全量 hook 安装枚举」统计器。
 *
 * 目的：在不动注入器、不重启手机的前提下，统计当前 419 个 feature 里有多少 hook 目标
 * 会被 WeKite 引擎（ArtHookBridge.hookMethod 的 require）拒绝 —— native 方法、接口方法、
 * proxy 类方法。结果用于决定 M2 时哪些功能要改 hook 点 / 保留 LSPlant / 放弃。
 *
 * 开关：`debug.wcx.hook_enum=1`（默认关）。关闭时 record() 只做一次 volatile 读后立即
 * 返回，零统计开销；打开时才累计计数与打日志。
 */
object HookEnumCollector {

    private const val TAG = "HookEnum"

    /** 关闭时零开销：enabled 只在首次访问时读一次系统属性并缓存。 */
    val enabled: Boolean by lazy {
        val on = runCatching {
            val clazz = Class.forName("android.os.SystemProperties")
            val get = clazz.getMethod("get", String::class.java, String::class.java)
            (get.invoke(null, "debug.wcx.hook_enum", "0") as? String) == "1"
        }.getOrDefault(false)
        if (on) WeLogger.i(TAG, "debug.wcx.hook_enum=1 → 开始 hook 安装枚举（仅统计，不改行为）")
        on
    }

    /** 当前正在启动的 feature 名（startFeatureBatch 里 startup() 前后设置/清除）。 */
    val currentFeatureName: ThreadLocal<String?> = ThreadLocal()

    private var total = 0
    private var nativeCount = 0
    private var interfaceCount = 0
    private var proxyCount = 0

    /** 去重：同一个成员可能被多个 feature / 多次 hook，只算一次。 */
    private val uniqueMembers = ConcurrentHashMap.newKeySet<String>()

    /** 每批开始前重置（startFeatureBatch 调用）。 */
    @Synchronized
    fun reset() {
        if (!enabled) return
        total = 0
        nativeCount = 0
        interfaceCount = 0
        proxyCount = 0
        uniqueMembers.clear()
    }

    /**
     * 在 hook 安装唯一收口（ArtHookBridge.hookMethod）调用。
     * 只统计「WeKite 会拒绝」的三类目标，不改变任何 hook 行为。
     * 只统计主进程（子进程 feature 大多不启动，会污染数据）。
     */
    @Synchronized
    fun record(member: Member) {
        if (!enabled || !TargetProcesses.isInMain) return
        total++

        val kind = when {
            member is Method && Modifier.isNative(member.modifiers) -> "native"
            member.declaringClass.isInterface -> "interface"
            Proxy.isProxyClass(member.declaringClass) ||
                member.declaringClass.name.startsWith("com.sun.proxy") ||
                member.declaringClass.name.contains("\$Proxy") -> "proxy"
            else -> null
        } ?: return

        when (kind) {
            "native" -> nativeCount++
            "interface" -> interfaceCount++
            "proxy" -> proxyCount++
        }

        val feature = currentFeatureName.get() ?: "<unknown>"
        val signature = signatureOf(member)
        uniqueMembers.add("$kind|$signature")
        WeLogger.i(TAG, "feature=$feature kind=$kind member=$signature")
    }

    /** 每批结束时打印汇总（startFeatureBatch 调用）。 */
    @Synchronized
    fun logSummary() {
        if (!enabled) return
        WeLogger.i(
            TAG,
            "hook 枚举：total=$total native=$nativeCount interface=$interfaceCount " +
                "proxy=$proxyCount （去重后 unique=${uniqueMembers.size}）",
        )
    }

    private fun signatureOf(member: Member): String {
        val owner = member.declaringClass.name
        val params = when (member) {
            is Method -> member.parameterTypes.joinToString(",") { it.name }
            is Constructor<*> -> member.parameterTypes.joinToString(",") { it.name }
            else -> ""
        }
        val ret = when (member) {
            is Method -> member.returnType.name
            is Constructor<*> -> "void"
            else -> "?"
        }
        return "$owner#${member.name}($params)$ret"
    }
}
