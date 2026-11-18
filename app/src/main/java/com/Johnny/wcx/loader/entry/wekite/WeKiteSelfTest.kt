package com.Johnny.wcx.loader.entry.wekite

import com.Johnny.wcx.loader.abc.IHookBridge
import com.Johnny.wcx.utils.WeLogger
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicInteger

/**
 * M2a 离线自测：在不接 WeKite native 后端（M2b）的前提下，验证 Java 侧的
 * DexMaker bridge/backup 生成器 + hookId 注册表 + before/after dispatch 闭环。
 *
 * 触发：`debug.wcx.wekite_selftest=1`，主进程启动后在 StartupAgent 里跑一次。
 * 测试目标是无害的静态 framework 方法 `Integer.parseInt(String)` / `Boolean.parseBoolean(String)`。
 * 选择静态方法是因为 M2a 尚未安装 native ArtMethod 替换，反射无法用目标类实例直接调用
 * 生成类的实例 bridge；实例 receiver ABI 留到 M2b native 闭环验证。
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
            val target: Method = Int::class.javaObjectType.getMethod(
                "parseInt",
                String::class.java,
            )

            // 1. DexMaker 生成 bridge/backup 对
            // M2a 尚未注册 WeKite JNI；这里只验证 DEX 生成/加载与 Java dispatch。
            val pair = bridge.generateBridgePair(target, trustDex = false)
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
            //    第 1 轮：不设 result，走 backup 占位（验证异常传递 + after 回调）
            var dispatchThrowable: Throwable? = null
            try {
                pair.bridgeMethod.invoke(null, "123")
            } catch (e: InvocationTargetException) {
                dispatchThrowable = e.targetException
            }
            WeLogger.i(
                TAG,
                "dispatch#1 done: before=$beforeHits after=$afterHits " +
                    "throwable=${dispatchThrowable?.javaClass?.simpleName}",
            )

            //    第 2 轮：在 before 里设置 param.result = 42（验证 primitive int 拆箱返回）
            val replaceReg = WeKiteHookBridgeRuntime.addCallback(
                hookId,
                object : IHookBridge.IMemberHookCallback {
                    override fun beforeHookedMember(param: IHookBridge.IMemberHookParam) {
                        param.result = 42
                    }
                    override fun afterHookedMember(param: IHookBridge.IMemberHookParam) = Unit
                },
                0,
            )
            val unboxedReturn = pair.bridgeMethod.invoke(null, "123")
            WeLogger.i(TAG, "dispatch#2 (early-return int unbox) result=$unboxedReturn")

            //    第 3 轮：单独生成 boolean 返回 bridge，覆盖此前 'Z' class_idx 失败路径。
            val boolTarget = Boolean::class.javaObjectType.getMethod(
                "parseBoolean",
                String::class.java,
            )
            val boolPair = bridge.generateBridgePair(boolTarget, trustDex = false)
            val boolHookId = WeKiteHookBridgeRuntime.register(
                boolTarget,
                boolPair.backupMethod,
                boolPair.dexFile,
            )
            boolPair.setHookId(boolHookId)
            val boolReg = WeKiteHookBridgeRuntime.addCallback(
                boolHookId,
                object : IHookBridge.IMemberHookCallback {
                    override fun beforeHookedMember(param: IHookBridge.IMemberHookParam) {
                        param.result = true
                    }
                    override fun afterHookedMember(param: IHookBridge.IMemberHookParam) = Unit
                },
                50,
            )
            val booleanReturn = boolPair.bridgeMethod.invoke(null, "false")
            WeLogger.i(TAG, "dispatch#3 (early-return boolean unbox) result=$booleanReturn")

            // 4. 清理：removeCallback + retire（unhook 的 Java 侧等价）
            WeKiteHookBridgeRuntime.removeCallback(boolHookId, boolReg)
            WeKiteHookBridgeRuntime.retire(boolHookId)
            WeKiteHookBridgeRuntime.removeCallback(hookId, replaceReg)
            WeKiteHookBridgeRuntime.removeCallback(hookId, registration)
            WeKiteHookBridgeRuntime.retire(hookId)

            val ok = beforeHits.get() == 2 && afterHits.get() == 2 &&
                dispatchThrowable is UnsupportedOperationException &&
                unboxedReturn == 42 && booleanReturn == true
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
