package com.Johnny.wcx.loader.utils

import android.annotation.SuppressLint
import android.os.Build
import android.os.Process
import com.Johnny.wcx.loader.utils.NativePayloadSource
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Native artifacts of the Zygisk payload, extracted from the copied APK.
 *
 * `InMemoryDexClassLoader` has no native-library directory, so the payload's
 * `.so` files are extracted into app-private storage and loaded by absolute
 * path from the module ClassLoader. Instances are created and used only under
 * NativeLoader's load lock.
 */
class ZygiskNativePayload(val apk: File, private val dataDir: File) : NativePayloadSource {

    /** Directory the payload libraries are extracted to; used for mapping hygiene. */
    override lateinit var libraryDirectory: File
        private set

    @SuppressLint("UnsafeDynamicallyLoadedCode")
    override fun loadLibraries(): Map<String, File> = ZipFile(apk).use { archive ->
        val abi = currentProcessAbi(archive)
        val directory = File(dataDir, ".wcx-native/${apk.nameWithoutExtension}/$abi")
        if (!directory.exists() && !directory.mkdirs()) {
            error("cannot create Zygisk native-library directory: $directory")
        }
        require(directory.isDirectory) { "Zygisk native-library path is not a directory: $directory" }
        libraryDirectory = directory

        val libraries = mutableMapOf<String, File>()
        for (entry in archive.entries().asSequence().toList()) {
            val name = entry.name
            if (entry.isDirectory || !name.startsWith("lib/$abi/") || !name.endsWith(".so")) continue
            val fileName = name.substringAfterLast('/')
            require(fileName.isNotEmpty() && fileName.indexOf('/') < 0) {
                "invalid payload library entry: $name"
            }
            libraries[fileName.removePrefix("lib").removeSuffix(".so")] =
                extractLibrary(archive, entry, directory, fileName)
        }
        for (required in REQUIRED) {
            require(required in libraries) {
                "Zygisk payload is missing lib$required.so for $abi"
            }
        }
        for (name in REQUIRED) {
            System.load(libraries.getValue(name).absolutePath)
        }
        libraries
    }

    private fun currentProcessAbi(archive: ZipFile): String {
        val candidates = if (Process.is64Bit()) {
            Build.SUPPORTED_64_BIT_ABIS.asList()
        } else {
            Build.SUPPORTED_32_BIT_ABIS.asList()
        }
        return candidates.firstOrNull { abi ->
            archive.getEntry("lib/$abi/libwekit_native.so") != null
        } ?: error("Zygisk payload has no native library for this process ABI")
    }

    private fun extractLibrary(
        archive: ZipFile,
        entry: ZipEntry,
        destinationDir: File,
        fileName: String,
    ): File {
        val destination = File(destinationDir, fileName)
        val temporary = File(destinationDir, "$fileName.${Process.myPid()}.tmp")
        temporary.delete()
        archive.getInputStream(entry).use { input ->
            temporary.outputStream().use { output -> input.copyTo(output) }
        }
        check(
            temporary.setReadable(true, true) &&
                temporary.setExecutable(true, true) &&
                temporary.setWritable(false, false)
        ) { "cannot protect Zygisk native library: $temporary" }
        if (!temporary.renameTo(destination)) {
            temporary.delete()
            error("cannot publish Zygisk native library: $destination")
        }
        return destination
    }

    private companion object {
        /** Libraries the module itself loads; everything else is extracted only. */
        val REQUIRED = listOf("dexkit", "wekit_native")
    }
}
