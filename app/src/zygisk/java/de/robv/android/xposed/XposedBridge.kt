package de.robv.android.xposed

import com.Johnny.wcx.loader.abc.IHookBridge
import com.Johnny.wcx.loader.abc.IHookBridge.IMemberHookCallback
import com.Johnny.wcx.loader.abc.IHookBridge.IMemberHookParam
import com.Johnny.wcx.loader.startup.StartupInfo
import com.Johnny.wcx.utils.WeLogger
import java.lang.reflect.Constructor
import java.lang.reflect.Member
import java.lang.reflect.Method

/**
 * Runtime replacement for `de.robv.android.xposed.XposedBridge`, routing every
 * operation to the Zygisk [IHookBridge].
 *
 * WCX is compiled against the compileOnly Xposed API jar; in the Zygisk payload
 * that framework does not exist, so these entry points must behave the same way
 * the Xposed implementation does for the features WCX relies on.
 */
object XposedBridge {

    private const val TAG = "XposedBridgeShim"

    /** The Xposed API level WCX was compiled against. */
    @JvmField
    var XPOSED_BRIDGE_VERSION: Int = 82

    @JvmField
    val BOOTCLASSLOADER: ClassLoader = ClassLoader.getSystemClassLoader()

    private val bridge: IHookBridge
        get() = StartupInfo.hookBridge
            ?: error("Xposed compatibility layer used before the Zygisk hook bridge is ready")

    /** member -> callbacks, so [unhookMethod] can resolve the handle. */
    private val handles = HashMap<Pair<Member, XC_MethodHook>, XC_MethodHook.Unhook>()

    @JvmStatic
    fun getXposedVersion(): Int = XPOSED_BRIDGE_VERSION

    @JvmStatic
    fun log(text: String) {
        WeLogger.i(TAG, text)
    }

    @JvmStatic
    fun log(t: Throwable) {
        WeLogger.e(TAG, "module log", t)
    }

    @JvmStatic
    fun hookMethod(member: Member, hook: XC_MethodHook): XC_MethodHook.Unhook {
        require(member is Method || member is Constructor<*>) {
            "hookMethod expects a Method or Constructor: $member"
        }
        val handle = bridge.hookMethod(member, Adapter(hook), hook.priority)
        val unhook = XC_MethodHook.Unhook(member, hook, handle)
        synchronized(handles) { handles[member to hook] = unhook }
        return unhook
    }

    @JvmStatic
    fun unhookMethod(member: Member, hook: XC_MethodHook) {
        val unhook = synchronized(handles) { handles.remove(member to hook) }
        unhook?.unhook()
    }

    @JvmStatic
    fun hookAllMethods(
        hookClass: Class<*>,
        methodName: String,
        callback: XC_MethodHook,
    ): Set<XC_MethodHook.Unhook> {
        val result = linkedSetOf<XC_MethodHook.Unhook>()
        for (method in hookClass.declaredMethods) {
            if (method.name != methodName) continue
            method.isAccessible = true
            result += hookMethod(method, callback)
        }
        return result
    }

    @JvmStatic
    fun hookAllConstructors(
        hookClass: Class<*>,
        callback: XC_MethodHook,
    ): Set<XC_MethodHook.Unhook> {
        val result = linkedSetOf<XC_MethodHook.Unhook>()
        for (ctor in hookClass.declaredConstructors) {
            ctor.isAccessible = true
            result += hookMethod(ctor, callback)
        }
        return result
    }

    @JvmStatic
    fun invokeOriginalMethod(member: Member, thisObject: Any?, args: Array<Any?>?): Any? {
        val arguments = args ?: emptyArray()
        return when (member) {
            is Method -> bridge.invokeOriginalMethod(member, thisObject, arguments)
            is Constructor<*> -> {
                @Suppress("UNCHECKED_CAST")
                bridge.invokeOriginalConstructor(
                    member as Constructor<Any>,
                    thisObject ?: error("constructor invocation needs an instance"),
                    arguments,
                )
            }
            else -> throw IllegalArgumentException("unsupported member: $member")
        }
    }

    /** Adapts an [XC_MethodHook] to the Zygisk bridge callback contract. */
    private class Adapter(private val hook: XC_MethodHook) : IMemberHookCallback {

        override fun beforeHookedMember(param: IMemberHookParam) {
            val shim = param.toShim()
            if (hook is XC_MethodReplacement) {
                val replacement = hook.runReplacement(shim)
                shim.setResult(replacement)
            } else {
                hook.runBefore(shim)
            }
            shim.applyTo(param)
        }

        override fun afterHookedMember(param: IMemberHookParam) {
            val shim = param.toShim()
            hook.runAfter(shim)
            shim.applyTo(param)
        }

        private fun IMemberHookParam.toShim(): XC_MethodHook.MethodHookParam =
            XC_MethodHook.MethodHookParam().apply {
                args = this@toShim.args
                method = this@toShim.member
                thisObject = this@toShim.thisObject
                loadState(this@toShim.result, this@toShim.throwable)
            }

        private fun XC_MethodHook.MethodHookParam.applyTo(param: IMemberHookParam) {
            // A callback that explicitly set a result (or throwable) short-circuits
            // the host call, matching Xposed semantics.
            if (resultSet) param.result = getResult()
            getThrowable()?.let { param.throwable = it }
        }
    }
}
