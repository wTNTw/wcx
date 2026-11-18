package com.Johnny.wcx.loader.entry.wekite

import com.Johnny.wcx.loader.abc.IHookBridge
import com.Johnny.wcx.utils.WeLogger
import java.lang.reflect.Constructor
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Member

internal object WeKiteNativeSelfTest {
    private const val TAG = "WeKiteNativeSelfTest"
    data class Result(val passed: Boolean, val checks: List<String>, val failure: Throwable? = null)

    // Identity checks constrain expected fault logging to these two deliberate throws.
    private val beforeFault = IllegalArgumentException("M2b deliberate before callback fault")
    private val afterFault = IllegalArgumentException("M2b deliberate after callback fault")
    internal fun isExpectedCallbackFault(member: Member, failure: Throwable): Boolean =
        member.declaringClass == WeKiteNativeFixture::class.java &&
            (failure === beforeFault || failure === afterFault)

    fun run(bridge: WeKiteHookBridge, processName: String): Result {
        val checks = mutableListOf<String>()
        val handles = mutableListOf<IHookBridge.MemberUnhookHandle>()
        val initialMembers = bridge.hookedMethods
        return try {
            try {
                testStatic(bridge, checks, handles)
                testInstance(bridge, checks, handles)
                testExceptions(bridge, checks, handles)
                testConstructor(bridge, checks, handles)
            } finally {
                // Also covers a failure during installation of a later callback.
                var cleanupFailure: Throwable? = null
                for (handle in handles.asReversed()) {
                    try {
                        handle.unhook()
                        check(!handle.isHookActive) { "fixture native unhook failed: ${handle.member}" }
                    } catch (t: Throwable) {
                        if (cleanupFailure == null) cleanupFailure = t else cleanupFailure.addSuppressed(t)
                    }
                }
                cleanupFailure?.let { throw it }
            }
            check(bridge.hookedMethods == initialMembers) { "fixture hook leaked" }
            checks += "all-hooks-retired"
            Result(true, checks).also {
                WeLogger.i(TAG, "SELFTEST PASS process=$processName checks=${checks.joinToString()}")
            }
        } catch (t: Throwable) {
            Result(false, checks, t).also {
                WeLogger.e(TAG, "SELFTEST FAIL process=$processName after=${checks.joinToString()}", t)
            }
        }
    }

    private fun callback(
        before: (IHookBridge.IMemberHookParam) -> Unit = {},
        after: (IHookBridge.IMemberHookParam) -> Unit = {},
    ) = object : IHookBridge.IMemberHookCallback {
        override fun beforeHookedMember(param: IHookBridge.IMemberHookParam) = before(param)
        override fun afterHookedMember(param: IHookBridge.IMemberHookParam) = after(param)
    }

    private fun install(
        bridge: WeKiteHookBridge, handles: MutableList<IHookBridge.MemberUnhookHandle>,
        member: Member, priority: Int = 0,
        before: (IHookBridge.IMemberHookParam) -> Unit = {},
        after: (IHookBridge.IMemberHookParam) -> Unit = {},
    ): IHookBridge.MemberUnhookHandle = bridge.hookMethod(member, callback(before, after), priority)
        .also { handles += it }

    private fun retire(vararg handles: IHookBridge.MemberUnhookHandle) {
        for (handle in handles) {
            handle.unhook()
            check(!handle.isHookActive) { "final unhook did not restore target: ${handle.member}" }
        }
    }

    private fun testStatic(
        bridge: WeKiteHookBridge, checks: MutableList<String>, handles: MutableList<IHookBridge.MemberUnhookHandle>,
    ) {
        val target = WeKiteNativeFixture::class.java.getDeclaredMethod("multiply", Integer.TYPE, Integer.TYPE)
        val order = mutableListOf<String>()
        val counter = bridge.hookCounter
        val high = install(bridge, handles, target, 100,
            before = { check(it.thisObject == null); order += "before-high" },
            after = { order += "after-high" })
        val low = install(bridge, handles, target,
            before = { order += "before-low"; it.args[0] = 4 },
            after = { order += "after-low" })
        check(bridge.hookCounter == counter + 1) { "duplicate native hook" }
        check(target.invoke(null, 3, 5) == 20) { "static primitive original/argument mutation" }
        check(bridge.invokeOriginalMethod(target, null, arrayOf(6, 7)) == 42)
        check(order == listOf("before-high", "before-low", "after-low", "after-high"))
        retire(low, high)
        val count = order.size
        check(target.invoke(null, 3, 5) == 15)
        check(order.size == count && bridge.hookCounter == counter)
        checks += "static-receiver-primitive-original-order-repeat-final-unhook"
    }

    private fun testInstance(
        bridge: WeKiteHookBridge, checks: MutableList<String>, handles: MutableList<IHookBridge.MemberUnhookHandle>,
    ) {
        val fixture = WeKiteNativeFixture(10)
        val add = WeKiteNativeFixture::class.java.getDeclaredMethod("add", Integer.TYPE)
        val normal = install(bridge, handles, add, before = {
            check(it.thisObject === fixture)
            it.args[0] = 5
        })
        check(add.invoke(fixture, 2) == 15) { "instance receiver/arguments" }
        check(bridge.invokeOriginalMethod(add, fixture, arrayOf(7)) == 17)
        val early = install(bridge, handles, add, 100, before = { it.result = 99 })
        check(add.invoke(fixture, 2) == 99)
        retire(early, normal)
        check(add.invoke(fixture, 2) == 12)
        val echo = WeKiteNativeFixture::class.java.getDeclaredMethod("echo", String::class.java)
        val echoHandle = install(bridge, handles, echo, before = { it.args[0] = "y" })
        check(echo.invoke(fixture, "x") == "origin:y")
        check(bridge.invokeOriginalMethod(echo, fixture, arrayOf("z")) == "origin:z")
        retire(echoHandle)
        check(echo.invoke(fixture, "x") == "origin:x")
        checks += "instance-receiver-args-reference-early-return-original-unhook"
    }

    private fun testExceptions(
        bridge: WeKiteHookBridge, checks: MutableList<String>, handles: MutableList<IHookBridge.MemberUnhookHandle>,
    ) {
        val fixture = WeKiteNativeFixture(1)
        val fail = WeKiteNativeFixture::class.java.getDeclaredMethod("fail", String::class.java)
        var observed: Throwable? = null
        val observer = install(bridge, handles, fail, 100, after = { observed = it.throwable })
        val faulty = install(bridge, handles, fail,
            before = { it.result = "not-an-early-return"; throw beforeFault },
            after = { it.result = "temporary"; throw afterFault })
        val thrown = runCatching { fail.invoke(fixture, "boom") }.exceptionOrNull()
        val cause = (thrown as? InvocationTargetException)?.targetException ?: thrown
        check(cause is WeKiteNativeFixture.FixtureException && cause.message == "boom")
        check(observed === cause) { "callback failure did not restore original throwable" }
        val original = runCatching { bridge.invokeOriginalMethod(fail, fixture, arrayOf("origin")) }.exceptionOrNull()
        check(original is WeKiteNativeFixture.FixtureException && original.message == "origin")
        val recovery = install(bridge, handles, fail, 200, after = { it.result = "recovered" })
        check(fail.invoke(fixture, "again") == "recovered")
        retire(recovery, faulty, observer)
        checks += "exception-propagation-before-after-restoration-recovery"
    }

    private fun testConstructor(
        bridge: WeKiteHookBridge, checks: MutableList<String>, handles: MutableList<IHookBridge.MemberUnhookHandle>,
    ) {
        val raw = WeKiteNativeFixture::class.java.getDeclaredConstructor(Integer.TYPE)
        @Suppress("UNCHECKED_CAST")
        val ctor = raw as Constructor<WeKiteNativeFixture?>
        var hits = 0
        val uninitialized = WeKiteNativeFixture(0)
        val handle = install(bridge, handles, ctor, before = { hits++; it.args[0] = 8 })
        check(ctor.newInstance(5)!!.seed == 8 && hits == 1)
        bridge.invokeOriginalConstructor(ctor, uninitialized, arrayOf(13))
        check(uninitialized.seed == 13 && hits == 1)
        check(bridge.newInstanceOrigin(ctor, 11).seed == 11 && hits == 1)
        retire(handle)
        check(ctor.newInstance(5)!!.seed == 5 && hits == 1)
        checks += "constructor-invokeOriginalConstructor-newInstanceOrigin-final-unhook"
    }
}
