package com.Johnny.wcx.loader.entry.zygisk

import com.Johnny.wcx.BuildConfig
import com.Johnny.wcx.loader.abc.IClassLoaderHelper
import com.Johnny.wcx.loader.abc.ILoaderService
import com.Johnny.wcx.utils.WeLogger
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Zygisk 入口的 [ILoaderService] 实现。
 *
 * 与 Frida 入口的区别只在于：
 * - 模块路径是 `/data/adb/modules/<id>`（不是已安装 APK）
 * - 原生库已经在注入器的 pre-specialize 阶段 dlopen 好，这里只负责把 JNI 符号绑定回去
 */
object ZygiskStartupImpl : ILoaderService {

    private const val TAG = "ZygiskStartupImpl"

    private var modulePath: String = ""
    private var hostDataDir: String = ""
    private var moduleClassLoader: ClassLoader? = null

    override var classLoaderHelper: IClassLoaderHelper? = null

    fun init(moduleDir: String, hostDataDir: String, classLoader: ClassLoader) {
        this.modulePath = moduleDir
        this.hostDataDir = hostDataDir
        this.moduleClassLoader = classLoader
    }

    override val entryPointName: String
        get() = "ZygiskInjectEntry"

    override val loaderVersionName: String
        get() = BuildConfig.VERSION_NAME

    override val loaderVersionCode: Int
        get() = BuildConfig.VERSION_CODE

    override val mainModulePath: String
        get() = modulePath

    override fun log(msg: String) = WeLogger.i(TAG, msg)

    override fun log(tr: Throwable) = WeLogger.e(TAG, "loader error", tr)

    override fun queryExtension(key: String, vararg args: Any?): Any? = null

    /**
     * 不装 APK 时 `System.loadLibrary` 一定失败（APK 没装，nativeLibraryDir 里没有模块的 .so），
     * 因此这里只做「把已 dlopen 的符号注册给声明 native 方法的类」。
     */
    override fun loadNativeLibrary(name: String) {
        val classes = NATIVE_CLASSES[name]
        if (classes == null) {
            WeLogger.w(TAG, "未登记的 zygisk 原生库: $name")
            return
        }
        classes.forEach { bindNatives(it) }
    }

    private fun bindNatives(className: String) {
        val cl = moduleClassLoader ?: return
        val clazz = runCatching { Class.forName(className, false, cl) }.getOrElse {
            WeLogger.e(TAG, "bindNatives: 找不到类 $className", it)
            return
        }
        val symbols = clazz.declaredMethods
            .filter { Modifier.isNative(it.modifiers) }
            .map { "${it.name}|${descriptorOf(it)}" }
        if (symbols.isEmpty()) return

        val ok = runCatching {
            NativeHookBridge.nativeBindNatives(clazz, symbols.toTypedArray())
        }.getOrElse {
            WeLogger.e(TAG, "nativeBindNatives 抛异常 $className", it)
            false
        }
        if (!ok) {
            WeLogger.w(TAG, "符号绑定失败 $className (${symbols.size} 个方法)")
        } else {
            WeLogger.i(TAG, "符号绑定成功 $className (${symbols.size} 个方法)")
        }
    }

    /** 计算 JVM 描述符，例如 (ILjava/lang/String;)V */
    private fun descriptorOf(method: Method): String {
        val sb = StringBuilder("(")
        method.parameterTypes.forEach { sb.append(descriptorOf(it)) }
        sb.append(')').append(descriptorOf(method.returnType))
        return sb.toString()
    }

    private fun descriptorOf(type: Class<*>): String = when (type.name) {
        "void" -> "V"
        "boolean" -> "Z"
        "byte" -> "B"
        "char" -> "C"
        "short" -> "S"
        "int" -> "I"
        "long" -> "J"
        "float" -> "F"
        "double" -> "D"
        else -> if (type.isArray) {
            "[" + descriptorOf(type.componentType!!)
        } else {
            "L" + type.name.replace('.', '/') + ";"
        }
    }

    private val NATIVE_CLASSES: Map<String, List<String>> = mapOf(
        "dexkit" to listOf(
            "org.luckypray.dexkit.DexKitBridge"
        ),
        "wekit_native" to listOf(
            "com.Johnny.wcx.utils.AudioUtils",
            "com.Johnny.wcx.utils.SignatureVerifier",
            "com.Johnny.wcx.utils.TelegramStickerConverter",
            "com.Johnny.wcx.utils.crash.NativeCrashHandler",
            "com.Johnny.wcx.features.items.chat.MarkdownRendering"
        )
    )
}
