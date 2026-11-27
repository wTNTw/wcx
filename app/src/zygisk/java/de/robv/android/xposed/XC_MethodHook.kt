package de.robv.android.xposed

import de.robv.android.xposed.callbacks.IXUnhook
import de.robv.android.xposed.callbacks.XCallback
import java.lang.reflect.Member

/**
 * Runtime replacement for `de.robv.android.xposed.XC_MethodHook`.
 *
 * WCX overrides [beforeHookedMethod] / [afterHookedMethod]; the Zygisk bridge
 * adapter drives them through [runBefore] / [runAfter], which keeps the
 * original `protected` visibility intact for subclasses.
 */
abstract class XC_MethodHook : XCallback {

    constructor() : super()

    constructor(priority: Int) : super(priority)

    protected open fun beforeHookedMethod(param: MethodHookParam) {}

    protected open fun afterHookedMethod(param: MethodHookParam) {}

    internal fun runBefore(param: MethodHookParam) = beforeHookedMethod(param)

    internal fun runAfter(param: MethodHookParam) = afterHookedMethod(param)

    /**
     * Mirrors the Xposed parameter object: `args`, `method` and `thisObject`
     * are public fields in the original, so they are fields here too.
     */
    class MethodHookParam : XCallback.Param() {

        @JvmField
        var args: Array<Any?> = emptyArray()

        @JvmField
        var method: Member? = null

        @JvmField
        var thisObject: Any? = null

        private var resultValue: Any? = null
        private var throwableValue: Throwable? = null

        internal var resultSet: Boolean = false
            private set

        fun getResult(): Any? = resultValue

        fun setResult(result: Any?) {
            resultValue = result
            throwableValue = null
            resultSet = true
        }

        fun getThrowable(): Throwable? = throwableValue

        fun hasThrowable(): Boolean = throwableValue != null

        fun setThrowable(throwable: Throwable?) {
            throwableValue = throwable
            resultValue = null
            resultSet = false
        }

        fun getResultOrThrowable(): Any? {
            throwableValue?.let { throw it }
            return resultValue
        }

        /** Seeds the parameter with the state the host call produced. */
        internal fun loadState(result: Any?, throwable: Throwable?) {
            resultValue = result
            throwableValue = throwable
            resultSet = false
        }
    }

    class Unhook internal constructor(
        private val hookedMethod: Member,
        private val callback: XC_MethodHook,
        private val handle: com.Johnny.wcx.loader.abc.IHookBridge.MemberUnhookHandle,
    ) : IXUnhook<XC_MethodHook> {

        fun getHookedMethod(): Member = hookedMethod

        override fun getCallback(): XC_MethodHook = callback

        override fun unhook() {
            handle.unhook()
        }
    }
}
