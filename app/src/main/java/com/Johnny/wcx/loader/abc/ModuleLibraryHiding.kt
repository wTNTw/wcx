package com.Johnny.wcx.loader.abc

import com.Johnny.wcx.utils.WeLogger

/**
 * Runs the optional native-library hiding step of a loader.
 *
 * Kept in its own file so the shared startup path only needs a single call:
 * the Zygisk bridge implements [IModuleLibraryHider], the Xposed entry points
 * do not, and nothing else in the startup sequence has to know about it.
 */
internal object ModuleLibraryHiding {

    private const val TAG = "ModuleLibraryHiding"

    fun hideIfSupported(hookBridge: IHookBridge?) {
        val hider = hookBridge as? IModuleLibraryHider ?: return
        runCatching { hider.hideLoadedModuleLibraries() }
            .onSuccess { hidden ->
                if (hidden) WeLogger.i(TAG, "hid loaded module libraries")
                else WeLogger.w(TAG, "module native-library hiding was incomplete")
            }
            .onFailure { WeLogger.e(TAG, "failed to hide module libraries", it) }
    }
}
