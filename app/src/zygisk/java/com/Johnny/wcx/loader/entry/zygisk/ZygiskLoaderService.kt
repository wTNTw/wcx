package com.Johnny.wcx.loader.entry.zygisk

import androidx.annotation.Keep
import com.Johnny.wcx.BuildConfig
import com.Johnny.wcx.loader.abc.IClassLoaderHelper
import com.Johnny.wcx.loader.abc.ILoaderService
import com.Johnny.wcx.utils.WeLogger

/**
 * [ILoaderService] for the Zygisk deployment.
 *
 * The loader version is the payload's own build version, so the settings page
 * and diagnostics report the actual module build rather than the manager's.
 */
@Keep
class ZygiskLoaderService(
    private val modulePath: String,
) : ILoaderService {

    private companion object {
        const val TAG = "ZygiskLoader"
    }

    override val entryPointName: String = "com.Johnny.wcx.loader.entry.zygisk.ZygiskEntry"

    override val loaderVersionName: String = BuildConfig.VERSION_NAME

    override val loaderVersionCode: Int = BuildConfig.VERSION_CODE

    override val mainModulePath: String get() = modulePath

    override var classLoaderHelper: IClassLoaderHelper? = null

    override fun log(msg: String) {
        WeLogger.i(TAG, msg)
    }

    override fun log(tr: Throwable) {
        WeLogger.e(TAG, "loader error", tr)
    }

    /** Zygisk exposes no extension protocol; keep the query surface inert. */
    override fun queryExtension(key: String, vararg args: Any?): Any? = null
}
