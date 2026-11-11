package com.Johnny.wcx.features.items.chat

import android.os.Message
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.utils.reflection.ClassLoaders
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.hookBeforeDirectly
import com.Johnny.wcx.utils.invokeOriginal

/**
 * 聊天页「附近直播」浮窗崩溃防御。
 *
 * 实测崩溃（微信 8.0.80 / Android 16，小米 shennong）：
 * ```
 * java.lang.NullPointerException: ... View.getResources()' on a null object reference
 *   at cn2.n0.a
 *   at pa6.n.getValue
 *   at com.tencent.mm.plugin.finder.feed.eg.<init>
 *   at com.tencent.mm.plugin.finder.nearby.live.view.NearbyLivePreviewView.m
 *   at com.tencent.mm.ui.chatting.component.c4.t0
 *   at com.tencent.mm.sdk.platformtools.i3.dispatchMessage
 *   at android.os.Looper.loopOnce
 * ```
 * 这是微信自身缺陷（栈内无任何模块代码），无法用常规 hookBefore/After 拦截——
 * 异常发生在微信自己的方法体内，构造器与 hookAfter 都接不住。
 *
 * 防御挂点：崩溃链最外层的**普通方法**——聊天页创建这个浮窗的延迟任务
 * `iq0.o.run`（8.0.80 的混淆名，Runnable；跨版本找不到就降级为只记日志）。
 * 在它上面套「替换 + invokeOriginal + try-catch」：仅当异常栈命中已知的微信自身
 * 崩溃（含 finder 直播预览、不含任何模块帧）时才吞掉——消息循环继续，浮窗不显示，
 * 进程不闪退；其它一切异常照常 rethrow，交给崩溃处理器记录。
 *
 * 注意：绝不能挂在 i3.dispatchMessage 上——那是主线程所有消息的入口，
 * replacement + invokeOriginal 每条消息都要走一次反射，实测会拖慢整个微信。
 */
@Feature(
    name = "聊天直播浮窗崩溃防御",
    categories = ["聊天"],
    description = "修复聊天页「附近直播」预览浮窗初始化时的微信自身 NPE 闪退（仅豁免该已知崩溃，其它异常照常上报）"
)
object ChatLivePreviewCrashGuard : SwitchFeature() {

    private const val TAG = "ChatLivePreviewCrashGuard"

    /** 模块自身的包名前缀：异常栈里只要出现模块帧，就说明是模块引起的，绝不能吞。 */
    private val MODULE_PACKAGES = listOf(
        "com.Johnny.wcx",
        "h.Hchat",
        "me.hd.wauxv",
        "bsh.",
        "org.luckypray.dexkit",
    )

    /** 模块自己的 hook 桥接包：这些帧出现在异常栈里不代表模块业务代码出错。 */
    private const val BRIDGE_PACKAGE = "com.Johnny.wcx.loader.entry.zygisk"

    /** 已知微信自身崩溃的特征（聊天页「附近直播」预览浮窗初始化 NPE）。 */
    private const val VICTIM_PACKAGE = "com.tencent.mm.plugin.finder"
    private const val VICTIM_HINT = "nearby"

    override val defaultEnabled: Boolean = true

    override fun onEnable() {
        val taskClass = runCatching {
            Class.forName("iq0.o", false, ClassLoaders.HOST)
        }.getOrNull() ?: run {
            WeLogger.w(TAG, "未找到 iq0.o（聊天页直播浮窗创建任务），防御不可用（微信版本变化）")
            return
        }
        if (!Runnable::class.java.isAssignableFrom(taskClass)) {
            WeLogger.w(TAG, "iq0.o 不再是 Runnable，防御不可用")
            return
        }
        val runMethod = runCatching {
            taskClass.getDeclaredMethod("run").apply { isAccessible = true }
        }.getOrNull() ?: run {
            WeLogger.w(TAG, "iq0.run 未找到，防御不可用")
            return
        }

        // 上游用 XposedBridge.hookMethod(…, XC_MethodReplacement)；
        // Zygisk 形态统一走模块自己的 hook 桥：hookBefore 里调用原方法并只吞掉已知崩溃。
        runMethod.hookBeforeDirectly {
            try {
                invokeOriginal()
            } catch (t: Throwable) {
                if (isKnownWeChatCrash(t)) {
                    WeLogger.e(
                        TAG,
                        "已吞掉微信自身崩溃（聊天页「附近直播」浮窗 NPE，本次浮窗不显示）: ${t.message}",
                    )
                    result = null
                } else {
                    throw t
                }
            }
        }

        WeLogger.i(TAG, "聊天直播浮窗崩溃防御已安装（iq0.run，仅豁免该已知崩溃）")
    }

    /**
     * 判定是否「已知的微信自身崩溃」。
     *
     * 关键点：异常是在我们自己的 hook 里 `invokeOriginal()` 抛出来的，所以**异常栈里必然
     * 包含模块的桥接帧**（`loader.entry.zygisk.ArtHookBridgeRuntime*`、`LSPHooker_*`、
     * `java.lang.reflect.Method.invoke`）。不能简单地说「栈里有模块帧就不是微信的锅」——
     * 要只看「比桥接帧更深（更靠栈顶）」的那部分调用链，那才是崩溃真正发生的现场。
     */
    private fun isKnownWeChatCrash(t: Throwable): Boolean {
        var foundVictim = false
        var foundModuleCode = false
        var hasAudioCallbackFrame = false
        var hasAudioManagerFrame = false

        var cause: Throwable? = t
        while (cause != null && !foundModuleCode) {
            var crossedBridge = false
            for (frame in cause.stackTrace) {
                val cls = frame.className
                if (cls.startsWith(BRIDGE_PACKAGE) || cls.startsWith("LSPHooker_") ||
                    (cls == "java.lang.reflect.Method" && frame.methodName == "invoke")
                ) {
                    crossedBridge = true
                    continue
                }
                if (!crossedBridge) {
                    // 比桥接帧更深：这里出现的模块帧才是「模块代码引起的崩溃」，绝不能吞
                    if (MODULE_PACKAGES.any { cls.startsWith(it) } && !cls.startsWith(BRIDGE_PACKAGE)) {
                        foundModuleCode = true
                        break
                    }
                    if (cls.startsWith(VICTIM_PACKAGE) && cls.contains(VICTIM_HINT)) {
                        foundVictim = true
                    }
                    if (cls == "android.media.CallbackUtil") hasAudioCallbackFrame = true
                    if (cls == "android.media.AudioManager") hasAudioManagerFrame = true
                }
            }
            cause = cause.cause
        }

        if (foundModuleCode) return false
        if (foundVictim) return true

        // 第二个已知崩溃：iq0.o.run 被重复执行时 AudioManager 回调二次注册，
        // 框架抛 IllegalArgumentException（栈里只有 android.media，无模块代码）。
        val duplicateListener = t is IllegalArgumentException &&
            t.message?.contains("previously registered listener") == true &&
            hasAudioCallbackFrame && hasAudioManagerFrame
        if (duplicateListener) {
            WeLogger.i(TAG, "命中第二个已知的微信自身崩溃（AudioManager 回调重复注册），本次任务跳过")
            return true
        }
        return false
    }

}
