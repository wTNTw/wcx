package de.robv.android.xposed

/** Binary-compatible stand-in for the zygote-init hook interface. */
interface IXposedHookZygoteInit {

    @Throws(Throwable::class)
    fun initZygote(startupParam: StartupParam)

    class StartupParam {
        @JvmField
        var modulePath: String? = null

        @JvmField
        var startsSystemServer: Boolean = false
    }
}
