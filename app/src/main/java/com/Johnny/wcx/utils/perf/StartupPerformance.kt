package com.Johnny.wcx.utils.perf

import android.os.SystemClock
import com.Johnny.wcx.utils.WeLogger
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Low-overhead monotonic marks for the WCX startup critical path. */
object StartupPerformance {
    private const val TAG = "StartupPerf"

    private val lock = Any()
    private val emitted = AtomicBoolean(false)
    private var origin = 0L
    private val marks = LinkedHashMap<String, Long>()

    fun begin() = synchronized(lock) {
        if (origin == 0L) origin = SystemClock.elapsedRealtime()
    }

    fun mark(name: String) = synchronized(lock) {
        if (origin == 0L) origin = SystemClock.elapsedRealtime()
        marks.putIfAbsent(name, SystemClock.elapsedRealtime() - origin)
    }

    fun emitOnce(processName: String) {
        if (!emitted.compareAndSet(false, true)) return
        val snapshot = synchronized(lock) { marks.toMap() }
        val total = snapshot.values.maxOrNull() ?: 0L
        WeLogger.i(TAG, buildString {
            append("startup process=").append(processName)
            append(" total=").append(total).append("ms")
            snapshot.forEach { (name, value) ->
                append(' ').append(name).append('=').append(value).append("ms")
            }
        })
    }
}
