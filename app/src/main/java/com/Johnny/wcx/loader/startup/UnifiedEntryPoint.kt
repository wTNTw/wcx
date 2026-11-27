package com.Johnny.wcx.loader.startup

import android.app.Application
import android.content.Context
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClass
import com.Johnny.wcx.loader.abc.IHookBridge
import com.Johnny.wcx.loader.abc.IHookBridge.IMemberHookCallback
import com.Johnny.wcx.loader.abc.IHookBridge.IMemberHookParam
import com.Johnny.wcx.loader.utils.HybridClassLoader
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.reflection.ClassLoaders
import java.lang.reflect.Member

object UnifiedEntryPoint {

    private const val TAG = "UnifiedEntryPoint"
    private const val STARTUP_HOOK_PRIORITY = 50

    fun entry(
        initialClassLoader: ClassLoader,
        hookBridge: IHookBridge,
        onApplicationCreated: (Application) -> Unit
    ) {
        val self = ClassLoaders.MODULE
        val selfParent = self.parent
        HybridClassLoader.moduleParentClassLoader = selfParent
        self.reflekt()
            .firstField { name = "parent"; superclass() }
            .set(HybridClassLoader)

        WeLogger.d(TAG, "hooking Application.attachBaseContext")

        val attachBaseContext = "com.tencent.mm.app.Application".toClass(initialClassLoader).reflekt()
            .firstMethod { name = "attachBaseContext" }.self
        hookBridge.afterStartupMethod(attachBaseContext) { param ->
            WeLogger.d(TAG, "Application.attachBaseContext invoked, hooking Instrumentation.callApplicationOnCreate")
            val currentClassLoader = (param.thisObject as Context).classLoader
            val callApplicationOnCreate = "android.app.Instrumentation".toClass(currentClassLoader).reflekt()
                .firstMethod("callApplicationOnCreate").self
            hookBridge.afterStartupMethod(callApplicationOnCreate) {
                WeLogger.d(TAG, "Instrumentation.callApplicationOnCreate invoked")
                onApplicationCreated(it.args[0] as Application)
            }
        }
    }

    private fun IHookBridge.afterStartupMethod(member: Member, action: (IMemberHookParam) -> Unit) {
        hookMethod(member, object : IMemberHookCallback {
            override fun beforeHookedMember(param: IMemberHookParam) = Unit
            override fun afterHookedMember(param: IMemberHookParam) = action(param)
        }, STARTUP_HOOK_PRIORITY)
    }
}
