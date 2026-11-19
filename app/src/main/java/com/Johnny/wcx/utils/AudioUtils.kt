package com.Johnny.wcx.utils

import com.Johnny.wcx.loader.utils.NativeLoader

object AudioUtils {

    fun anyToSilk(inputPath: String, silkPath: String): Boolean =
        anyToSilkAtRate(inputPath, silkPath, 24_000)

    fun anyToSilkAtRate(inputPath: String, silkPath: String, sampleRate: Int): Boolean {
        NativeLoader.ensureMediaLoaded()
        return anyToSilkNative(inputPath, silkPath, sampleRate)
    }

    fun pcmToSilk(
        pcmPath: String,
        silkPath: String,
        silkSampleRate: Int,
        pcmSampleRate: Int,
        channels: Int,
    ): Boolean {
        NativeLoader.ensureMediaLoaded()
        return pcmToSilkNative(pcmPath, silkPath, silkSampleRate, pcmSampleRate, channels)
    }

    fun silkToPcm(silkPath: String, pcmPath: String): Boolean =
        silkToPcmAtRate(silkPath, pcmPath, 24_000)

    fun silkToPcmAtRate(silkPath: String, pcmPath: String, sampleRate: Int): Boolean {
        NativeLoader.ensureMediaLoaded()
        return silkToPcmNative(silkPath, pcmPath, sampleRate)
    }

    fun pcmToMp3(pcmPath: String, mp3Path: String): Boolean =
        pcmToMp3AtRate(pcmPath, mp3Path, 24_000)

    fun pcmToMp3AtRate(pcmPath: String, mp3Path: String, sampleRate: Int): Boolean {
        NativeLoader.ensureMediaLoaded()
        return pcmToMp3Native(pcmPath, mp3Path, sampleRate)
    }

    /** Decode a supported non-Silk audio file to mono signed 16-bit little-endian PCM. */
    fun anyToPcm(inputPath: String, pcmPath: String): Int {
        NativeLoader.ensureMediaLoaded()
        return anyToPcmNative(inputPath, pcmPath)
    }

    fun getFileType(path: String): Int {
        NativeLoader.ensureMediaLoaded()
        return getFileTypeNative(path)
    }

    fun getDurationMs(path: String): Long {
        NativeLoader.ensureMediaLoaded()
        return getDurationMsNative(path)
    }

    private external fun anyToSilkNative(
        inputPath: String,
        silkPath: String,
        sampleRate: Int,
    ): Boolean

    private external fun pcmToSilkNative(
        pcmPath: String,
        silkPath: String,
        silkSampleRate: Int,
        pcmSampleRate: Int,
        channels: Int,
    ): Boolean

    private external fun silkToPcmNative(
        silkPath: String,
        pcmPath: String,
        sampleRate: Int,
    ): Boolean

    private external fun pcmToMp3Native(
        pcmPath: String,
        mp3Path: String,
        sampleRate: Int,
    ): Boolean

    private external fun anyToPcmNative(inputPath: String, pcmPath: String): Int

    private external fun getFileTypeNative(path: String): Int

    private external fun getDurationMsNative(path: String): Long
}
