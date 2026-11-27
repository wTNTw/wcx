package com.Johnny.wcx.loader.utils

import java.io.File

/**
 * Supplies the module's native libraries when they are not available through
 * the host process's normal library path.
 *
 * Loaders that inject a payload APK (Zygisk) provide an implementation; the
 * Xposed entry points install none and keep using `System.loadLibrary`.
 */
interface NativePayloadSource {

    /** Publishes and loads the module libraries, returning them by logical name. */
    fun loadLibraries(): Map<String, File>

    /** Directory the libraries were extracted to, once [loadLibraries] ran. */
    val libraryDirectory: File
}
