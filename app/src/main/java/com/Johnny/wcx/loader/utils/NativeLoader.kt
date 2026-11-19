package com.Johnny.wcx.loader.utils

import android.annotation.SuppressLint
import android.content.Context
import android.os.Process
import com.Johnny.wcx.loader.startup.StartupInfo
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.utils.fs.createDirsSafe
import com.tencent.mmkv.MMKV
import java.io.File
import java.util.EnumSet
import kotlin.io.path.div
import kotlin.io.path.exists

/** Initializes bundled native libraries and resolves the APK's executable artifacts. */
object NativeLoader {

    private enum class Capability {
        CORE,
        DEXKIT,
        MEDIA,
        GRAPHICS_PATH,
    }

    private val nativeLoadLock = Any()
    private val loadedCapabilities = EnumSet.noneOf(Capability::class.java)
    private var zygiskPayload: ZygiskNativePayload? = null
    private var zygiskNativeLibraries: Map<String, File> = emptyMap()
    private var installedNativeLibraryDir: File? = null
    private var nativeLibrariesPrepared = false

    /** Configures the copied Zygisk APK before module startup reaches [init]. */
    @JvmStatic
    fun configureZygiskPayload(apkPath: String, dataDir: String) = synchronized(nativeLoadLock) {
        check(!nativeLibrariesPrepared && loadedCapabilities.isEmpty()) {
            "native libraries were already prepared"
        }
        val apk = File(apkPath)
        require(apk.isFile && apk.canRead()) { "Zygisk payload APK is unreadable: $apkPath" }
        val appDataDir = File(dataDir)
        require(appDataDir.isDirectory) { "Zygisk app data directory is unavailable: $dataDir" }
        zygiskPayload = ZygiskNativePayload(apk, appDataDir)
    }

    /** The module APK used as the class path for standalone child processes. */
    fun bootstrapApk(): File = synchronized(nativeLoadLock) {
        zygiskPayload?.apk ?: File(StartupInfo.loaderService.mainModulePath)
    }

    /** Absolute path of an extracted Zygisk native artifact for legacy Hchat callers. */
    fun loadedLibraryPath(soName: String): File? = synchronized(nativeLoadLock) {
        val key = soName.removePrefix("lib").removeSuffix(".so")
        zygiskNativeLibraries[key]?.takeIf { it.isFile }
    }

    /** Load the configured APK's startup core before opt-in child-process hooks. */
    fun loadZygiskNativeLibrariesEarly() = synchronized(nativeLoadLock) {
        check(zygiskPayload != null) { "Zygisk payload is not configured" }
        ensureCoreLoadedLocked()
    }

    fun init(hostCtx: Context) {
        val libLoader = synchronized(nativeLoadLock) {
            ensureCoreLoadedLocked()
            mmkvLibLoader()
        }
        val mmkvDir = hostCtx.filesDir.toPath() / "mmkv"
        if (!mmkvDir.exists()) {
            mmkvDir.createDirsSafe()
        }
        MMKV.initialize(hostCtx, mmkvDir.toString(), libLoader)
        MMKV.mmkvWithID(WePrefs.PREFS_NAME, MMKV.MULTI_PROCESS_MODE)
    }

    fun ensureCoreLoaded() = synchronized(nativeLoadLock) {
        ensureCoreLoadedLocked()
    }

    fun ensureDexKitLoaded() = synchronized(nativeLoadLock) {
        ensureCoreLoadedLocked()
        ensureCapabilityLoaded(Capability.DEXKIT, "dexkit")
    }

    fun ensureMediaLoaded() = synchronized(nativeLoadLock) {
        ensureCoreLoadedLocked()
        ensureCapabilityLoaded(Capability.MEDIA, "wekit_media")
    }

    fun ensureGraphicsPathLoaded() = synchronized(nativeLoadLock) {
        ensureCoreLoadedLocked()
        ensureCapabilityLoaded(Capability.GRAPHICS_PATH, "androidx.graphics.path")
    }

    private fun ensureCoreLoadedLocked() {
        ensureNativeLibrariesPrepared()
        if (Capability.CORE in loadedCapabilities) return
        ensureCapabilityLoaded(Capability.CORE, "wekit_native")
    }

    /** Prepare paths/extractions without loading deferred capabilities. */
    private fun ensureNativeLibrariesPrepared() {
        if (nativeLibrariesPrepared) return

        val payload = zygiskPayload
        if (payload == null) {
            val instructionSet = if (Process.is64Bit()) "arm64" else "arm"
            installedNativeLibraryDir = File(
                requireNotNull(File(StartupInfo.loaderService.mainModulePath).parentFile),
                "lib/$instructionSet",
            ).also {
                require(it.isDirectory) { "installed WCX native-library directory is unavailable: $it" }
            }
        } else {
            // ZygiskNativePayload loads only the startup core while extracting all capabilities.
            zygiskNativeLibraries = payload.loadLibraries()
            loadedCapabilities.add(Capability.CORE)
        }
        nativeLibrariesPrepared = true
    }

    @SuppressLint("UnsafeDynamicallyLoadedCode")
    private fun ensureCapabilityLoaded(capability: Capability, libraryName: String) {
        if (capability in loadedCapabilities) return
        try {
            loadPackagedLibrary(libraryName)
            loadedCapabilities.add(capability)
        } catch (error: Throwable) {
            loadedCapabilities.remove(capability)
            throw error
        }
    }

    @SuppressLint("UnsafeDynamicallyLoadedCode")
    private fun loadPackagedLibrary(name: String) {
        val library = if (zygiskPayload == null) {
            installedNativeLibrary(name)
        } else {
            requireNotNull(zygiskNativeLibraries[name]) {
                "Zygisk payload is missing lib$name.so"
            }
        }
        System.load(library.absolutePath)
    }

    @SuppressLint("UnsafeDynamicallyLoadedCode")
    private fun mmkvLibLoader(): MMKV.LibLoader = if (zygiskPayload == null) {
        MMKV.LibLoader { name -> System.load(installedNativeLibrary(name).absolutePath) }
    } else {
        MMKV.LibLoader { name ->
            val library = zygiskNativeLibraries[name]
            if (library != null) {
                System.load(library.absolutePath)
            } else {
                System.loadLibrary(name)
            }
        }
    }

    fun invokeToolExecutable(): File = bundledExecutable("invoke_tool")

    // PRoot requires the installed APK's native directory; the Zygisk payload does not provide it.
    fun prootExecutable(): File = synchronized(nativeLoadLock) {
        installedNativeArtifact("proot").requireExecutable("proot")
    }

    fun prootLoaderExecutable(): File = synchronized(nativeLoadLock) {
        installedNativeArtifact("proot_loader").requireExecutable("proot_loader")
    }

    private fun bundledExecutable(name: String): File = synchronized(nativeLoadLock) {
        ensureNativeLibrariesPrepared()
        (zygiskNativeLibraries[name] ?: installedNativeArtifact(name)).requireExecutable(name)
    }

    private fun File.requireExecutable(name: String): File = also {
        require(isFile && canExecute()) { "$name is not executable: $this" }
    }

    private fun installedNativeLibrary(name: String): File = installedNativeArtifact(name).also {
        require(it.isFile && it.canRead()) { "$name is not readable: $it" }
    }

    private fun installedNativeArtifact(name: String): File {
        val directory = installedNativeLibraryDir
            ?: error("packaged $name requires an installed WCX APK")
        return File(directory, "lib$name.so")
    }
}
