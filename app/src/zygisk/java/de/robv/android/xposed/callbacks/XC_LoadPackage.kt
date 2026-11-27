package de.robv.android.xposed.callbacks

import android.content.pm.ApplicationInfo
import de.robv.android.xposed.IXposedHookLoadPackage

/**
 * Runtime stand-in for `XC_LoadPackage`. WCX's Hchat module entry implements
 * [IXposedHookLoadPackage]; the Zygisk deployment never dispatches through this
 * callback, but the types must exist so the entry class can be loaded.
 */
abstract class XC_LoadPackage protected constructor() : XCallback(), IXposedHookLoadPackage {

    class LoadPackageParam : XCallback.Param() {
        @JvmField
        var appInfo: ApplicationInfo? = null

        @JvmField
        var classLoader: ClassLoader? = null

        @JvmField
        var isFirstApplication: Boolean = false

        @JvmField
        var packageName: String? = null

        @JvmField
        var processName: String? = null
    }
}
