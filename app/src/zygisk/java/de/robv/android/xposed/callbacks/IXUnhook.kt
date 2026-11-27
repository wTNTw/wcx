package de.robv.android.xposed.callbacks

/** Binary-compatible stand-in for the Xposed unhook handle. */
interface IXUnhook<T> {
    fun getCallback(): T

    fun unhook()
}
