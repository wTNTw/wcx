package com.Johnny.wcx.utils.android

import android.content.Context
import android.util.AttributeSet
import com.tencent.mm.pluginsdk.ui.chat.ChatFooter
import dev.ujhhgtg.reflekt.reflected.ReflectedConstructor
import dev.ujhhgtg.reflekt.reflekt
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.hookAfterDirectly
import com.Johnny.wcx.utils.reflection.BInt
import com.Johnny.wcx.utils.reflection.int
import kotlin.reflect.KClass

/**
 * 老接口：按 `(Context, AttributeSet, int)` 找 ChatFooter 的构造器。
 *
 * 微信 8.0.79 起这个签名不一定存在（实测直接抛
 * `NoSuchElementException: No constructor matching conditions in ...ChatFooter`，
 * 连带「悬浮输入框」「聊天工具栏」两个功能整块 enable 失败），所以现在逐级放宽：
 *   1. 老签名（含 superclass）；
 *   2. 两参数 `(Context, AttributeSet)`；
 *   3. 任意「第一个参数是 Context」的构造器；
 *   4. 都没有才抛异常。
 * 新代码请优先用 [hookAllConstructors]，不要依赖具体签名。
 */
val KClass<ChatFooter>.constructor: ReflectedConstructor<ChatFooter>
    get() = reflekt().run {
        firstConstructorOrNull { parameters(Context::class, AttributeSet::class, int) }
            ?: firstConstructorOrNull { parameters(Context::class, AttributeSet::class, BInt) }
            ?: firstConstructorOrNull {
                superclass(true)
                parameters(Context::class, AttributeSet::class, int)
            }
            ?: firstConstructorOrNull { parameters(Context::class, AttributeSet::class) }
            ?: firstConstructorOrNull {
                parameters { params: List<Class<*>> -> params.firstOrNull() == Context::class.java }
            }
            ?: firstConstructor()
    }

/**
 * 给 ChatFooter 的**所有**构造器挂 after hook，返回挂上的数量。
 *
 * 不写死签名：微信各版本 ChatFooter 的构造器参数不同（8.0.79 连
 * `(Context, AttributeSet, int)` 都没有），逐个挂上去最稳。
 */
fun KClass<ChatFooter>.hookAllConstructors(onConstructed: (ChatFooter) -> Unit): Int {
    val constructors = runCatching { reflekt().constructors() }.getOrElse {
        WeLogger.w("ChatFooterUtils", "读取 ChatFooter 构造器失败", it)
        return 0
    }
    if (constructors.isEmpty()) {
        WeLogger.w("ChatFooterUtils", "ChatFooter 没有任何构造器？")
        return 0
    }
    var hooked = 0
    for (ctor in constructors) {
        runCatching {
            ctor.hookAfterDirectly {
                (thisObject as? ChatFooter)?.let(onConstructed)
            }
            hooked++
        }.onFailure { WeLogger.w("ChatFooterUtils", "挂 ChatFooter 构造器失败: $ctor", it) }
    }
    WeLogger.i(
        "ChatFooterUtils",
        "ChatFooter 构造器已挂 $hooked/${constructors.size} 个: " +
            constructors.joinToString { it.parameterTypes.joinToString { t -> t.simpleName } },
    )
    return hooked
}
