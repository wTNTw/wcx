package com.Johnny.wcx.loader.entry.wekite

import androidx.annotation.Keep

/** Private reflection targets: never hook a shared framework method for acceptance tests. */
@Keep
internal class WeKiteNativeFixture(var seed: Int) {
    fun add(delta: Int): Int = seed + delta
    fun echo(value: String): String = "origin:$value"
    fun fail(message: String): String = throw FixtureException(message)

    @Keep
    internal class FixtureException(message: String) : IllegalStateException(message)

    companion object {
        @JvmStatic fun multiply(left: Int, right: Int): Int = left * right
    }
}
