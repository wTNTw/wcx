package de.robv.android.xposed.callbacks;

/** Binary-compatible stand-in for the Xposed unhook handle. */
public interface IXUnhook<T> {
    T getCallback();

    void unhook();
}
