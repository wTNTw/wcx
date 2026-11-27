package de.robv.android.xposed

/**
 * Runtime replacement for `XC_MethodReplacement`: the original method body is
 * replaced entirely by [replaceHookedMethod].
 */
abstract class XC_MethodReplacement : XC_MethodHook {

    constructor() : super()

    constructor(priority: Int) : super(priority)

    protected abstract fun replaceHookedMethod(param: MethodHookParam): Any?

    internal fun runReplacement(param: MethodHookParam): Any? = replaceHookedMethod(param)

    companion object {
        @JvmField
        val DO_NOTHING: XC_MethodReplacement = object : XC_MethodReplacement() {
            override fun replaceHookedMethod(param: MethodHookParam): Any? = null
        }

        @JvmStatic
        fun returnConstant(result: Any?): XC_MethodReplacement =
            object : XC_MethodReplacement() {
                override fun replaceHookedMethod(param: MethodHookParam): Any? = result
            }

        @JvmStatic
        fun returnConstant(priority: Int, result: Any?): XC_MethodReplacement =
            object : XC_MethodReplacement(priority) {
                override fun replaceHookedMethod(param: MethodHookParam): Any? = result
            }
    }
}
