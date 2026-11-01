package com.Johnny.wcx.loader.abc

import androidx.annotation.Keep

@Keep
interface ILoaderService {
    val entryPointName: String

    val loaderVersionName: String

    val loaderVersionCode: Int

    val mainModulePath: String

    fun log(msg: String)

    fun log(tr: Throwable)

    fun queryExtension(key: String, vararg args: Any?): Any?

    var classLoaderHelper: IClassLoaderHelper?

    /**
     * 加载模块自带的原生库。
     *
     * Xposed / Frida 入口下 APK 是装好的，沿用 [System.loadLibrary] 即可；
     * Zygisk 入口不安装 APK，原生库由注入器在 zygote 阶段 dlopen，
     * 由对应实现覆写成「只做 JNI 符号绑定」。
     */
    fun loadNativeLibrary(name: String) {
        System.loadLibrary(name)
    }
}
