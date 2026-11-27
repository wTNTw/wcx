package de.robv.android.xposed

import de.robv.android.xposed.callbacks.XC_LoadPackage

/** Binary-compatible stand-in; see [XC_LoadPackage]. */
interface IXposedHookLoadPackage {
    @Throws(Throwable::class)
    fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam)
}
