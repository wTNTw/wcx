package com.Johnny.wcx.features.items.chat

import com.Johnny.wcx.utils.HookCallback

import android.view.View

import dev.ujhhgtg.reflekt.reflekt
import com.Johnny.wcx.features.api.core.WeMessageApi
import com.Johnny.wcx.features.api.core.WeServiceApi
import com.Johnny.wcx.features.api.core.models.MessageType
import com.Johnny.wcx.features.api.ui.WeChatMessageViewApi
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.utils.collections.LruCache
import java.lang.reflect.InvocationTargetException
import com.Johnny.wcx.utils.HookParam

@Feature(name = "自动语音转文字", categories = ["聊天"], description = "自动将语音消息转为文字")
object AutoSpeechToText : SwitchFeature(),
    WeChatMessageViewApi.ICreateViewListener {

    private val processedMessages = LruCache<Long, Boolean>()

    override fun onEnable() {
        WeChatMessageViewApi.addListener(this)
    }

    override fun onDisable() {
        WeChatMessageViewApi.removeListener(this)
    }

    private const val TAG = "AutoSpeechToText"

    override fun onCreateView(
        param: HookParam,
        view: View
    ) {
        // 这里全是微信内部结构（apiManager 字段、transform 方法签名、MsgInfo 实体），
        // 任一漂移都会抛异常。原来没有兜底：一条消息失败就等于整个功能「没反应」，
        // 日志里也看不到。现在失败要留痕，自检页「最近错误」也能直接看到。
        runCatching { handleCreateView(param, view) }
            .onFailure { com.Johnny.wcx.utils.WeLogger.e(TAG, "自动语音转文字失败（本条消息跳过）：${it.message}", it) }
    }

    /**
     * 取「消息 → 视图项」的 transform 结果。
     *
     * 8.0.80 起这个方法的返回类型不再落在 `com.tencent.mm.ui.chatting.viewitems` 包里，
     * 严格匹配会抛 `No method matching conditions in com.tencent.mm.ui.chatting.component.gp`
     * （日志里的「自动语音转文字失败」就是这么来的）。这里保留严格匹配做快路径，
     * 失败再退化为「一个 long 参数 + 非 void 返回」并按名字偏好挑选。
     */
    private fun transformItem(api: Any, id: Long): Any {
        val clazz = api.javaClass
        val strict = clazz.methods.firstOrNull { method ->
            method.parameterCount == 1 &&
                method.parameterTypes[0] == java.lang.Long.TYPE &&
                method.returnType.name.startsWith("com.tencent.mm.ui.chatting.viewitems")
        }
        if (strict != null) return strict.invoke(api, id)

        val candidates = clazz.methods.filter { method ->
            method.parameterCount == 1 &&
                method.parameterTypes[0] == java.lang.Long.TYPE &&
                method.returnType != Void.TYPE
        }
        com.Johnny.wcx.utils.WeLogger.i(
            TAG,
            "transform 严格匹配未命中，放宽候选 ${candidates.size} 个：" +
                candidates.joinToString { it.name + "():" + it.returnType.simpleName },
        )
        val picked = candidates.firstOrNull { it.name.contains("transform", ignoreCase = true) }
            ?: candidates.firstOrNull()
            ?: error("no transform candidate in ${clazz.name}")
        return picked.invoke(api, id)
    }

    /**
     * 找「标记为已转文字」的方法：优先严格签名
     * `(MsgInfo, boolean, int, int) -> void`，其次 `(MsgInfo, boolean...) -> void`，
     * 最后退化为「首参是 MsgInfo 且返回 void」。失败打一次候选列表，便于对齐新版。
     */
    private fun findMarkTransformed(api: Any): java.lang.reflect.Method? {
        val msgInfoClass = WeMessageApi.classMsgInfo.clazz
        val methods = api.javaClass.methods.filter { it.parameterCount >= 2 && it.parameterTypes[0] == msgInfoClass }
        methods.firstOrNull { method ->
            method.returnType == Void.TYPE &&
                method.parameterTypes[1] == java.lang.Boolean.TYPE &&
                method.parameterTypes.drop(2).all { it == java.lang.Integer.TYPE }
        }?.let { return it }
        methods.firstOrNull { method ->
            method.returnType == Void.TYPE && method.parameterTypes[1] == java.lang.Boolean.TYPE
        }?.let { return it }
        methods.firstOrNull { it.returnType == Void.TYPE }?.let { return it }
        com.Johnny.wcx.utils.WeLogger.w(
            TAG,
            "标记已转文字候选 " + methods.size + " 个：" +
                methods.take(8).joinToString { it.name + "(" + it.parameterTypes.joinToString { p -> p.simpleName } + ")" },
        )
        return null
    }

    /** 按方法声明补齐参数：boolean 传 false，int 传 -1/0，其它传 null。 */
    private fun invokeMarkTransformed(
        method: java.lang.reflect.Method,
        api: Any,
        msgInfo: Any,
    ) {
        val args = method.parameterTypes.mapIndexed { index, type ->
            when {
                index == 0 -> msgInfo
                type == java.lang.Boolean.TYPE -> false
                type == java.lang.Integer.TYPE -> if (index == 2) -1 else 0
                else -> null
            }
        }.toTypedArray()
        method.invoke(api, *args)
    }

    private fun handleCreateView(
        param: HookParam,
        view: View
    ) {
        val msgInfo = WeChatMessageViewApi.getMsgInfoFromParam(param)
        if (msgInfo.typeCode != MessageType.VOICE.code) return

        val id = msgInfo.id
        if (processedMessages[id] == true) {
            return
        }

        val chattingContext = WeChatMessageViewApi.getChattingContextFromParam(param)
        val apiMan = chattingContext.reflekt()
            .firstField {
                type = WeServiceApi.apiManagerClass
            }
            .get()!!
        val api = WeServiceApi.getApiByClass(apiMan, WeMessageApi.classTransformChattingComponent.clazz)
        val chatViewItem = transformItem(api, id)

        if (chatViewItem.toString() != "NoTransform") return

        processedMessages[id] = true

        // Clear the unplayed red dot the same way WeChat does when the voice is listened to,
        // since we consume the message via transform instead of playback.
        runCatching { WeMessageApi.markVoicePlayed(msgInfo) }

        if (WeMessageApi.methodGetIsTransformed.method.invoke(msgInfo.instance) as Boolean) return
        // 8.0.80 起这个「标记为已转文字」的方法签名变了，严格匹配会抛
        // `No method matching conditions in com.tencent.mm.ui.chatting.component.gp`，
        // 于是整条消息的处理被外层 runCatching 打断 → 功能表现为完全失效。
        // 这里改成宽度匹配（首参是 MsgInfo），并且失败只记一条日志、不打断后续。
        try {
            val markTransformed = findMarkTransformed(api)
            if (markTransformed == null) {
                com.Johnny.wcx.utils.WeLogger.w(TAG, "标记已转文字的方法未找到，本条消息跳过标记")
            } else {
                invokeMarkTransformed(markTransformed, api, msgInfo.instance)
            }
        } catch (_: InvocationTargetException) {
            // WeChat throws `java.lang.NullPointerException: getImgPath(...) must not be null`,
            // but that's not what we should care about and doesn't affect functionality
        }
    }
}
