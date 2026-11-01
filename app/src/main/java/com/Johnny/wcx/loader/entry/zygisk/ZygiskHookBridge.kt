package com.Johnny.wcx.loader.entry.zygisk

import android.os.Build
import com.Johnny.wcx.loader.abc.IHookBridge
import com.Johnny.wcx.utils.WeLogger
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 基于 LSPlant 的 [IHookBridge] 实现（Zygisk 注入器专用）。
 *
 * 结构：
 * - 每个被 hook 的成员对应一个 [Record]，成员只被 LSPlant 替换一次，
 *   多个回调按 priority 挂在 Record 上（与 Xposed 语义一致）
 * - 真正进入 hook 的是 [NativeHooker.callback]，它把调用转回 [dispatch]
 * - 原始方法通过 LSPlant 返回的 backup 反射调用
 *
 * 注意：本类会被 feature 代码高频调用，实现里刻意避免 hook 框架自身的类
 * （只用 java.* / 已加载的 payload 类）。
 */
object ZygiskHookBridge : IHookBridge {

    private const val TAG = "ZygiskHookBridge"

    private val records = ConcurrentHashMap<Member, Record>()

    override val apiLevel: Int = Build.VERSION.SDK_INT

    override val frameworkName: String = "Zygisk (LSPlant)"

    override val frameworkVersion: String = Build.VERSION.RELEASE

    override val frameworkVersionCode: Long = Build.VERSION.SDK_INT.toLong()

    override val isDeoptimizationSupported: Boolean = true

    override val hookCounter: Long
        get() = records.values.sumOf { it.callbackCount.toLong() }

    override val hookedMethods: Set<Member?>
        get() = records.keys

    // ── IHookBridge ─────────────────────────────────────────────────────────

    override fun hookMethod(
        member: Member,
        callback: IHookBridge.IMemberHookCallback,
        priority: Int
    ): IHookBridge.MemberUnhookHandle {
        val executable = member as? Executable
            ?: throw IllegalArgumentException("only methods/constructors can be hooked: $member")

        val record = records.computeIfAbsent(member) { Record(member) }
        if (!record.install(executable)) {
            throw IllegalStateException("LSPlant hook failed for $member")
        }
        val entry = Entry(callback, priority)
        record.addCallback(entry)
        return Handle(record, entry)
    }

    override fun deoptimize(member: Member): Boolean {
        val executable = member as? Executable ?: return false
        return NativeHookBridge.nativeDeoptimize(executable)
    }

    override fun invokeOriginalMethod(
        method: Method,
        thisObject: Any?,
        args: Array<Any?>
    ): Any? {
        val backup = records[method]?.backup
        if (backup == null) {
            method.isAccessible = true
            return method.invoke(thisObject, *args)
        }
        backup.isAccessible = true
        return backup.invoke(thisObject, *args)
    }

    override fun <T> invokeOriginalConstructor(
        ctor: Constructor<T?>,
        thisObject: T,
        args: Array<Any?>
    ) {
        // LSPlant 目前不会为构造器返回可用的 backup（备份是 Method 而非 Constructor），
        // 这一条留给后续里程碑：需要用 InvokeSpecial 语义重放构造体。
        WeLogger.w(TAG, "invokeOriginalConstructor 暂不支持（zygisk 模式）: $ctor")
    }

    override fun <T> newInstanceOrigin(constructor: Constructor<T?>, vararg args: Any): T {
        constructor.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return constructor.newInstance(*args) as T
    }

    // ── 分派（由 NativeHooker 调用）───────────────────────────────────────────

    @JvmStatic
    fun dispatch(member: Member, thisObject: Any?, args: Array<Any?>): Any? {
        val record = records[member]
        val callbacks = record?.callbackSnapshot()
        if (record == null || callbacks.isNullOrEmpty()) {
            return record?.invokeBackup(thisObject, args)
                ?: throw IllegalStateException("no hook record for $member")
        }

        val param = Param(member, thisObject, args)

        for (entry in callbacks) {
            try {
                entry.callback.beforeHookedMember(param)
            } catch (t: Throwable) {
                // 与 Xposed 一致：回调自身抛异常只记日志，不中断后续回调/原方法
                WeLogger.e(TAG, "beforeHookedMember failed: $member", t)
                param.throwable = null
            }
        }

        if (param.throwable == null && !param.resultWasSet) {
            try {
                param.result = record.invokeBackup(thisObject, args)
            } catch (ite: InvocationTargetException) {
                param.throwable = ite.targetException ?: ite
            } catch (t: Throwable) {
                param.throwable = t
            }
        }

        // after 回调按 priority 升序（与 Xposed 一致：before 降序、after 升序）
        for (i in callbacks.indices.reversed()) {
            try {
                callbacks[i].callback.afterHookedMember(param)
            } catch (t: Throwable) {
                WeLogger.e(TAG, "afterHookedMember failed: $member", t)
            }
        }

        param.throwable?.let { throw it }
        return param.result
    }

    // ── 内部结构 ────────────────────────────────────────────────────────────

    private class Entry(
        val callback: IHookBridge.IMemberHookCallback,
        val priority: Int
    )

    private class Param(
        override val member: Member,
        override val thisObject: Any?,
        override val args: Array<Any?>,
        override var throwable: Throwable? = null,
        override var extra: Any? = null
    ) : IHookBridge.IMemberHookParam {

        /**
         * 与 Xposed 语义对齐：只要回调设置了 result（哪怕设为 null），就跳过原方法。
         * 否则「返回 null 让微信不发生该行为」这类功能会失效。
         */
        var resultWasSet = false
            private set

        override var result: Any? = null
            set(value) {
                field = value
                resultWasSet = true
            }
    }

    private class Handle(
        private val record: Record,
        val entry: Entry
    ) : IHookBridge.MemberUnhookHandle {

        @Volatile
        private var active = true

        override val member: Member
            get() = record.member

        override val callback: IHookBridge.IMemberHookCallback
            get() = entry.callback

        override val isHookActive: Boolean
            get() = active

        override fun unhook() {
            if (!active) return
            active = false
            record.removeCallback(entry)
        }
    }

    private class Record(val member: Member) {

        @Volatile
        var backup: Method? = null
            private set

        private val callbacks = CopyOnWriteArrayList<Entry>()

        val callbackCount: Int
            get() = callbacks.size

        /** 首次 hook 时用 LSPlant 替换目标方法。 */
        fun install(executable: Executable): Boolean {
            if (backup != null) return true
            synchronized(this) {
                if (backup != null) return true
                val result = try {
                    NativeHookBridge.nativeHook(executable, NativeHooker::class.java)
                } catch (t: Throwable) {
                    WeLogger.e(TAG, "nativeHook threw for $executable", t)
                    null
                }
                if (result == null) return false
                // 构造器 backup 可能是 Constructor，统一按 Method 处理
                backup = result as Method
                return true
            }
        }

        fun addCallback(entry: Entry) {
            callbacks.add(entry)
            // 保持 priority 降序（大者先执行，与 Xposed 语义一致）
            callbacks.sortByDescending { it.priority }
        }

        fun removeCallback(entry: Entry) {
            callbacks.remove(entry)
            if (callbacks.isNotEmpty()) return
            // 没有回调了：把方法还原，避免残留分派开销
            synchronized(this) {
                if (callbacks.isNotEmpty()) return
                val executable = member as? Executable
                if (executable != null) {
                    runCatching { NativeHookBridge.nativeUnhook(executable) }
                        .onFailure { WeLogger.e(TAG, "nativeUnhook failed: $executable", it) }
                }
                backup = null
                records.remove(member)
            }
        }

        fun callbackSnapshot(): List<Entry>? =
            if (callbacks.isEmpty()) null else callbacks.toList()

        fun invokeBackup(thisObject: Any?, args: Array<Any?>): Any? {
            val method = backup
                ?: throw IllegalStateException("hook record has no backup: $member")
            method.isAccessible = true
            return if (Modifier.isStatic(method.modifiers)) {
                method.invoke(null, *args)
            } else {
                method.invoke(thisObject, *args)
            }
        }
    }
}
