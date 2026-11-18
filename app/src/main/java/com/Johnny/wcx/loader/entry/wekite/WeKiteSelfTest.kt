package com.Johnny.wcx.loader.entry.wekite

import com.Johnny.wcx.loader.abc.IHookBridge
import com.Johnny.wcx.utils.WeLogger
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.ArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * M2a 离线自测：在不接 WeKite native 后端（M2b）的前提下，验证 Java 侧的
 * DexMaker bridge/backup 生成器 + hookId 注册表 + before/after dispatch 闭环。
 *
 * 触发：`debug.wcx.wekite_selftest=1`，主进程启动后在 StartupAgent 里跑一次。
 * 测试目标是一个无害的 framework 方法 `java.util.ArrayList.add(Object)`。
 *
 * 说明：此时 backup 的 ArtMethod 还没被 native hook 覆盖（M2b 才做），所以
 * invokeOriginal 会抛占位异常（UnsupportedOperationException）——这是预期，
 * 只要 before/after 回调与参数装箱/拆箱链路正常就算 PASS。
 */
object WeKiteSelfTest {

    private const val TAG = "WeKiteSelfTest"

    fun run() {
        try {
            val bridge = WeKiteHookBridge()
            val target: Method = ArrayList::class.java.getMethod("size")

            // 1. DexMaker 生成 bridge/backup 对
            val pair = bridge.generateBridgePair(target)
            WeLogger.i(TAG, "generated bridge=${pair.bridgeMethod} backup=${pair.backupMethod}")

            // 2. 注册 + 写入 hookId
            val hookId = WeKiteHookBridgeRuntime.register(target, pair.backupMethod, pair.dexFile)
            pair.setHookId(hookId)

            val beforeHits = AtomicInteger(0)
            val afterHits = AtomicInteger(0)
            val registration = WeKiteHookBridgeRuntime.addCallback(
                hookId,
                object : IHookBridge.IMemberHookCallback {
                    override fun beforeHookedMember(param: IHookBridge.IMemberHookParam) {
                        beforeHits.incrementAndGet()
                        WeLogger.i(TAG, "before: member=${param.member.name} arg0=${param.args.getOrNull(0)}")
                    }

                    override fun afterHookedMember(param: IHookBridge.IMemberHookParam) {
                        afterHits.incrementAndGet()
                        WeLogger.i(TAG, "after: member=${param.member.name} throwable=${param.throwable?.javaClass?.simpleName}")
                    }
                },
                50,
            )

            // 3. 直接调用生成的 bridge（等价于 ART 调 target 入口 -> bridge）
            val list = ArrayList<Any>()
            list.add("x")
            var dispatchThrowable: Throwable? = null
            try {
                pair.bridgeMethod.invoke(list)
            } catch (e: InvocationTargetException) {
                dispatchThrowable = e.targetException
            }
            WeLogger.i(
                TAG,
                "dispatch done: before=$beforeHits after=$afterHits " +
                    "throwable=${dispatchThrowable?.javaClass?.simpleName}",
            )

            // 4. 清理：removeCallback + retire（unhook 的 Java 侧等价）
            WeKiteHookBridgeRuntime.removeCallback(hookId, registration)
            WeKiteHookBridgeRuntime.retire(hookId)

            val ok = beforeHits.get() == 1 && afterHits.get() == 1 &&
                dispatchThrowable is UnsupportedOperationException
            if (ok) {
                WeLogger.i(TAG, "SELFTEST PASS: bridge 生成 + 注册表 + before/after dispatch 闭环正常")
            } else {
                WeLogger.e(
                    TAG,
                    "SELFTEST FAIL: before=$beforeHits after=$afterHits throwable=$dispatchThrowable",
                )
            }
        } catch (t: Throwable) {
            WeLogger.e(TAG, "SELFTEST FAIL: unexpected error", t)
        }
    }
}
