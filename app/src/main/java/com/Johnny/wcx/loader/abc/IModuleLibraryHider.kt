package com.Johnny.wcx.loader.abc

import androidx.annotation.Keep

/**
 * Implemented by hook bridges that can also hide the module's own native
 * libraries from the host process memory map.
 *
 * The Xposed entry points do not implement this; the Zygisk bridge does.
 */
@Keep
interface IModuleLibraryHider {

    /**
     * Remaps the module's payload libraries. Returns true when every mapped
     * module library was handled, false when hiding was incomplete.
     */
    fun hideLoadedModuleLibraries(): Boolean
}
