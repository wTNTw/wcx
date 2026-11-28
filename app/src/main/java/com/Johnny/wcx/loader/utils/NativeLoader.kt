package com.Johnny.wcx.loader.utils

import android.content.Context
import com.tencent.mmkv.MMKV
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.utils.fs.createDirsSafe
import kotlin.io.path.div
import kotlin.io.path.exists

object NativeLoader {

    private val loadLock = Any()
    private var payload: NativePayloadSource? = null
    private var payloadLibraries: Map<String, java.io.File> = emptyMap()
    private var librariesLoaded = false

    /**
     * Installs the payload source before startup reaches [init].
     *
     * Used by the Zygisk deployment, whose libraries live in the copied payload
     * APK instead of the host's native library path.
     */
    fun configurePayload(source: NativePayloadSource) = synchronized(loadLock) {
        check(!librariesLoaded) { "native libraries were already loaded" }
        payload = source
    }

    /**
     * A native library shipped in the payload APK, by logical name (`dexkit`,
     * `silk_codec`, ...), or null on the Xposed path where libraries come from
     * the module APK's own native-library directory.
     *
     * Subsystems that resolve libraries from the module APK (Hchat) must ask
     * here first: a Zygisk payload is loaded by an InMemoryDexClassLoader, which
     * has no native-library directory at all.
     */
    @JvmStatic
    fun bundledLibrary(name: String): java.io.File? = synchronized(loadLock) {
        val hasPayload = payload != null
        if (hasPayload) ensureLibrariesLoaded()
        val file = payloadLibraries[name]?.takeIf { it.isFile }
        WeLogger.i(
            "NativeLoader",
            "bundledLibrary($name) payload=$hasPayload loaded=$librariesLoaded " +
                "keys=${payloadLibraries.keys} file=${file?.absolutePath}",
        )
        file
    }

    /** Directory of the loaded payload libraries, or null on the Xposed path. */
    fun payloadLibraryDirectory(): java.io.File? = synchronized(loadLock) {
        payload?.takeIf { librariesLoaded }?.libraryDirectory
    }

    private fun ensureLibrariesLoaded() {
        if (librariesLoaded) return
        val source = payload
        if (source == null) {
            System.loadLibrary("dexkit")
            System.loadLibrary("wekit_native")
        } else {
            payloadLibraries = source.loadLibraries()
        }
        librariesLoaded = true
    }

    fun init(hostCtx: Context) {
        val libLoader = synchronized(loadLock) {
            ensureLibrariesLoaded()
            mmkvLibLoader()
        }
        val mmkvDir = hostCtx.filesDir.toPath() / "mmkv"
        if (!mmkvDir.exists()) {
            mmkvDir.createDirsSafe()
        }

        MMKV.initialize(hostCtx, mmkvDir.toString(), libLoader)

        MMKV.mmkvWithID(WePrefs.PREFS_NAME, MMKV.MULTI_PROCESS_MODE)
    }

    private fun mmkvLibLoader(): MMKV.LibLoader {
        if (payload == null) {
            return MMKV.LibLoader { name -> System.loadLibrary(name) }
        }
        val libraries = payloadLibraries
        return MMKV.LibLoader { name ->
            val library = libraries[name]
            if (library != null) System.load(library.absolutePath) else System.loadLibrary(name)
        }
    }
}
