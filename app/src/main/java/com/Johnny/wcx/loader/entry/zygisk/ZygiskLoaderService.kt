package com.Johnny.wcx.loader.entry.zygisk

import android.util.Log
import androidx.annotation.Keep
import com.Johnny.wcx.loader.abc.IClassLoaderHelper
import com.Johnny.wcx.loader.abc.ILoaderService

@Keep
class ZygiskLoaderService(
    private val modulePath: String,
    private val versionName: String,
    private val versionCode: Int,
) : ILoaderService {

    override var classLoaderHelper: IClassLoaderHelper? = null

    override val entryPointName: String = "com.Johnny.wcx.loader.entry.zygisk.ZygiskEntry"

    override val loaderVersionName: String get() = versionName

    override val loaderVersionCode: Int get() = versionCode

    override val mainModulePath: String get() = modulePath

    override fun log(msg: String) {
        Log.i(TAG, msg)
    }

    override fun log(tr: Throwable) {
        Log.e(TAG, tr.toString(), tr)
    }

    override fun queryExtension(key: String, vararg args: Any?): Any? = null

    /**
     * Zygisk 模式下 payload 的原生库已经由 [com.Johnny.wcx.loader.utils.NativeLoader]
     * 从 module.apk 里解出来并 System.load 过，这里不需要再加载一次。
     */
    override fun loadNativeLibrary(name: String) {
        Log.i(TAG, "loadNativeLibrary($name): 已由 Zygisk payload 预加载")
    }

    companion object {
        private const val TAG = "ZygiskLoaderService"
    }
}
