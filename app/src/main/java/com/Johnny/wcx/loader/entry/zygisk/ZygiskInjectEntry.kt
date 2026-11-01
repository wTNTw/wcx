package com.Johnny.wcx.loader.entry.zygisk

import android.app.Application
import android.app.Instrumentation
import com.Johnny.wcx.loader.abc.IHookBridge
import com.Johnny.wcx.loader.startup.StartupAgent
import com.Johnny.wcx.loader.startup.StartupInfo
import com.Johnny.wcx.loader.utils.HybridClassLoader
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.reflection.ClassLoaders
import dev.ujhhgtg.reflekt.reflekt
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * Zygisk 注入器交给 payload 的唯一入口。
 *
 * 由 `zygisk/src/main/cpp/main.cpp` 在 post-specialize 阶段通过 JNI 调用：
 * ```java
 * entry(String appDataDir, String moduleDir, String processName, ClassLoader moduleLoader)
 * ```
 *
 * 这里做的事情等价于 Xposed 入口的 `ModuleLoader.init` → `UnifiedEntryPoint.entry`，
 * 但完全走 [IHookBridge]（不依赖 de.robv.android.xposed，因为不装 APK 时没有 Xposed 框架）。
 */
object ZygiskInjectEntry {

    private const val TAG = "ZygiskInjectEntry"

    private var started = false

    @JvmStatic
    fun entry(
        appDataDir: String,
        moduleDir: String,
        processName: String,
        moduleLoader: ClassLoader
    ) {
        if (started) return
        started = true

        WeLogger.i(
            TAG,
            "注入入口 pid=${android.os.Process.myPid()} uid=${android.os.Process.myUid()} " +
                "process=$processName module=$moduleDir data=$appDataDir"
        )

        runCatching {
            ZygiskStartupImpl.init(moduleDir, appDataDir, moduleLoader)
            StartupInfo.loaderService = ZygiskStartupImpl
            StartupInfo.hookBridge = ZygiskHookBridge

            // 让模块 ClassLoader 的委派链与 Xposed 入口一致：
            // 模块 CL -> HybridClassLoader -> (BOOT / HOST / 其它)
            HybridClassLoader.moduleParentClassLoader = moduleLoader.parent ?: ClassLoaders.BOOT
            runCatching {
                ClassLoaders.MODULE.reflekt()
                    .firstField { name = "parent"; superclass() }
                    .set(HybridClassLoader)
            }.onFailure { WeLogger.w(TAG, "改写模块 ClassLoader 的 parent 失败: $it") }

            // LSPlant 需要访问隐藏 API；payload 的 dex 是内存加载的，需要显式豁免
            runCatching { HiddenApiBypass.setHiddenApiExemptions("L") }
                .onFailure { WeLogger.w(TAG, "hidden api 豁免失败: $it") }

            check(NativeHookBridge.nativeInit()) { "LSPlant 初始化失败" }

            hookApplicationCreate()
            WeLogger.i(TAG, "hook 已就位，等待微信 Application 创建")
        }.onFailure {
            WeLogger.e(TAG, "Zygisk 注入初始化失败", it)
        }
    }

    /**
     * 与 Xposed 入口保持一致：在 `Instrumentation.callApplicationOnCreate` 之后启动模块。
     * 此时 Application 已创建完成，宿主 ClassLoader 可用。
     */
    private fun hookApplicationCreate() {
        val method = Instrumentation::class.java
            .getDeclaredMethod("callApplicationOnCreate", Application::class.java)
        method.isAccessible = true

        ZygiskHookBridge.hookMethod(method, object : IHookBridge.IMemberHookCallback {
            override fun beforeHookedMember(param: IHookBridge.IMemberHookParam) = Unit

            override fun afterHookedMember(param: IHookBridge.IMemberHookParam) {
                val application = param.args.getOrNull(0) as? Application ?: return
                runCatching {
                    WeLogger.i(TAG, "Application 已创建，启动 StartupAgent")
                    StartupAgent.startup(
                        StartupInfo.loaderService,
                        StartupInfo.hookBridge,
                        ZygiskStartupImpl.mainModulePath,
                        application
                    )
                }.onFailure { WeLogger.e(TAG, "StartupAgent 启动失败", it) }
            }
        }, 50)

        WeLogger.d(TAG, "已 hook Instrumentation.callApplicationOnCreate")
    }
}
