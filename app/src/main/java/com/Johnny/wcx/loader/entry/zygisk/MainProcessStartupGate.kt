package com.Johnny.wcx.loader.entry.zygisk

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
        if (!completion.await(timeoutMs, TimeUnit.MILLISECONDS) &&
            state.compareAndSet(State.Pending, State.Consumed)
        ) {
            return null
        }
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
}
