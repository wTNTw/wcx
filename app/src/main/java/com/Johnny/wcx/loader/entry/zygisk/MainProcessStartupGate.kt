package com.Johnny.wcx.loader.entry.zygisk

import android.os.SystemClock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal class MainProcessStartupGate<T> {
    private sealed interface State<out T> {
        data object Pending : State<Nothing>
        data class Published<T>(val value: T) : State<T>
        data object Consumed : State<Nothing>
    }

    private val state = AtomicReference<State<T>>(State.Pending)
    private val completion = CountDownLatch(1)

    fun publish(value: T): Boolean {
        if (!state.compareAndSet(State.Pending, State.Published(value))) return false
        completion.countDown()
        return true
    }

    fun awaitAndConsume(timeoutMs: Long): T? {
        awaitBounded(timeoutMs)
        while (true) {
            when (val current = state.get()) {
                is State.Published -> {
                    if (state.compareAndSet(current, State.Consumed)) return current.value
                }
                State.Pending -> {
                    if (state.compareAndSet(State.Pending, State.Consumed)) return null
                }
                State.Consumed -> return null
            }
        }
    }

    private fun awaitBounded(timeoutMs: Long) {
        val sliceMs = 100L
        var activeWaitedMs = 0L
        while (activeWaitedMs < timeoutMs) {
            val remainingMs = timeoutMs - activeWaitedMs
            val waitSliceMs = minOf(sliceMs, remainingMs)
            val sliceStart = SystemClock.elapsedRealtime()
            if (completion.await(waitSliceMs, TimeUnit.MILLISECONDS)) return
            val elapsed = (SystemClock.elapsedRealtime() - sliceStart).coerceAtLeast(1L)
            // Cap each slice so cgroup freezer time during screen-off does not
            // falsely exhaust the active wait budget while threads were unscheduled.
            activeWaitedMs += elapsed.coerceAtMost(waitSliceMs * 2)
        }
    }
}
