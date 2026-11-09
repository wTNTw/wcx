@file:Suppress("NOTHING_TO_INLINE")

package com.Johnny.wcx.features.core

import com.Johnny.wcx.utils.HookCallback

import androidx.compose.runtime.Composable


import dev.ujhhgtg.reflekt.reflected.BaseReflectedMethod
import dev.ujhhgtg.reflekt.reflected.ReflectedConstructor
import dev.ujhhgtg.reflekt.reflekt
import com.Johnny.wcx.dexkit.dsl.BaseDexDelegate
import com.Johnny.wcx.dexkit.dsl.DexConstructorDelegate
import com.Johnny.wcx.dexkit.dsl.DexMethodDelegate
import com.Johnny.wcx.utils.HookAction
import com.Johnny.wcx.utils.hookDirectly
import com.Johnny.wcx.utils.WeLogger
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Executable
import kotlin.reflect.KClass
import com.Johnny.wcx.utils.HookParam

abstract class BaseFeature {

    var name: String = ""
    var categories: List<String> = emptyList()

    val displayName: String
        get() = "${categories.joinToString(",")}/$name"

    var description: String = ""

    /**
     * 当前微信版本是否支持本功能；返回非空字符串表示不支持（开关会拒绝开启并提示该文案）。
     *
     * 用于「微信把某个模块整体删掉」这类情况（例如 8.0.77 起多人通话 MultiTalk 被移除），
     * 避免用户开了开关却毫无反应、误以为模块坏了。
     */
    open val unavailableReason: String?
        get() = null

    /** WeKit 兼容: 功能稳定标识 (FeaturesLoader 注入, 本模块保留以兼容连带代码)。 */
    var technicalId: String = ""

    open fun startup() {
        error("You shouldn't inherit BaseFeature")
    }

    /** Whether this feature's hooks are currently installed (runtime truth). */
    var isActive: Boolean = false
        private set

    fun enable() {
        if (isActive) return

        runCatching {
            isActive = true
            onEnable()
        }.onFailure { e ->
            WeLogger.e(TAG, "failed to enable feature $displayName", e)
            // ensure transaction is fully discarded
            unhookAll()
            isActive = false
        }
    }

    fun disable() {
        if (!isActive) return

        runCatching {
            isActive = false
            unhookAll()
            onDisable()
        }.onFailure { e ->
            WeLogger.e(TAG, "failed to disable feature $displayName", e)
            isActive = true
        }
    }

    open fun onEnable() {}

    open fun onDisable() {}

    @Composable
    open fun Ui() {
    }

    private val _dexDelegates = mutableListOf<BaseDexDelegate>()
    val dexDelegates: List<BaseDexDelegate> get() = _dexDelegates
    internal fun registerDexDelegate(d: BaseDexDelegate) {
        _dexDelegates += d
    }

    internal fun resolveInlineDex(dexKit: DexKitBridge) {
        dexDelegates.forEach { it.findInline(dexKit) }
    }

    internal val unhooks = mutableListOf<com.Johnny.wcx.utils.HookHandle>()
    internal fun registerUnhook(u: com.Johnny.wcx.utils.HookHandle) {
        unhooks += u
    }

    internal fun unhookAll() {
        unhooks.forEach { it.unhook() }
        unhooks.clear()
    }

    // --- hookBefore ---

    internal fun Executable.hookBefore(
        priority: Int = 50,
        action: HookAction
    ) = registerUnhook(
        this.hookDirectly(
            object :
                HookCallback(priority) {
                override fun beforeHookedMethod(param: HookParam) {
                    executeHookAction(param, action)
                }
            }
        ))

    @JvmName("hookBefore2")
    internal fun BaseReflectedMethod.hookBefore(
        priority: Int = 50,
        action: HookAction
    ) = self.hookBefore(priority, action)

    @JvmName("hookBefore3")
    internal fun ReflectedConstructor<*>.hookBefore(
        priority: Int = 50,
        action: HookAction
    ) = this.self.hookBefore(priority, action)

    internal fun Class<*>.hookBeforeOnCreate(
        action: HookAction
    ) = this.reflekt().firstMethod { name = "onCreate" }.hookBefore(50, action)

    internal fun Class<*>.hookAfterOnCreate(
        action: HookAction
    ) = this.reflekt().firstMethod { name = "onCreate" }.hookAfter(50, action)

    internal fun KClass<*>.hookBeforeOnCreate(
        action: HookAction
    ) = this.reflekt().firstMethod { name = "onCreate" }.hookBefore(50, action)

    internal fun KClass<*>.hookAfterOnCreate(
        action: HookAction
    ) = this.reflekt().firstMethod { name = "onCreate" }.hookAfter(50, action)

    // --- end hookBefore ---

    // --- hookAfter ---

    internal fun Executable.hookAfter(
        priority: Int = 50,
        action: HookAction
    ) = registerUnhook(
        this.hookDirectly(
            object :
                HookCallback(priority) {
                override fun afterHookedMethod(param: HookParam) {
                    executeHookAction(param, action)
                }
            }
        ))

    @JvmName("hookAfter2")
    internal fun BaseReflectedMethod.hookAfter(
        priority: Int = 50,
        action: HookAction
    ) = self.hookAfter(priority, action)

    @JvmName("hookAfter3")
    internal fun ReflectedConstructor<*>.hookAfter(
        priority: Int = 50,
        action: HookAction
    ) = this.self.hookAfter(priority, action)

    // --- end hookAfter ---

    // --- dex delegate ---

    internal fun DexMethodDelegate.hookBefore(
        priority: Int = 50,
        action: HookAction
    ) = method.hookBefore(priority, action)

    internal fun DexMethodDelegate.hookAfter(
        priority: Int = 50,
        action: HookAction
    ) = method.hookAfter(priority, action)

    internal fun DexConstructorDelegate.hookBefore(
        priority: Int = 50,
        action: HookAction
    ) = constructor.hookBefore(priority, action)

    internal fun DexConstructorDelegate.hookAfter(
        priority: Int = 50,
        action: HookAction
    ) = constructor.hookAfter(priority, action)

    // --- end dex delegate ---

    /**
     * 每个功能自己的 hook 体执行入口：出错只记日志，不中断宿主。
     *
     * 8.0.80 适配经验：微信改签名后，某个功能的 hook 可能**每次事件都抛**（实测某个功能
     * 一次启动就刷 250 条 `ArrayIndexOutOfBoundsException`）。除了刷日志，更危险的是
     * 「改了一半再抛」——例如先 removeAllViews 再失败，界面直接白掉。
     * 所以这里加了一道熔断：同一个功能连续失败 [MAX_HOOK_FAILURES] 次后本轮进程内直接停用它
     * （日志只打一次），等我们按功能适配完再恢复。
     */
    internal fun executeHookAction(param: HookParam, action: HookAction) {
        if (runtimeDisabled) return
        runCatching {
            action(param)
        }.onFailure { e ->
            val count = hookFailureCount.incrementAndGet()
            if (count == 1) {
                WeLogger.e("executeHookAction", "failed to execute hook of $name", e)
            }
            if (count == MAX_HOOK_FAILURES) {
                runtimeDisabled = true
                WeLogger.w(
                    "executeHookAction",
                    "$name 连续失败 $count 次，本进程内停止该功能的 hook（多半是当前微信版本签名变了，等适配）",
                )
            }
        }
    }

    @Volatile
    private var runtimeDisabled = false

    private val hookFailureCount = java.util.concurrent.atomic.AtomicInteger(0)

    companion object {
        private const val TAG = "BaseFeature"
        /** 同一功能连续失败多少次后在当前进程内熔断。 */
        private const val MAX_HOOK_FAILURES = 5
    }
}
