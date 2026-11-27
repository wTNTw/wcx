package de.robv.android.xposed.callbacks

import android.os.Bundle

/**
 * Runtime replacement for the Xposed callback base class.
 *
 * The module is compiled against `app/libs/xposed-api-82.jar` (compileOnly), so
 * this class must stay binary compatible with that jar: same class name, same
 * field for [priority], same nested `Param` members. Only what WCX actually
 * uses is implemented.
 */
abstract class XCallback protected constructor(
    @JvmField val priority: Int,
) {

    protected constructor() : this(PRIORITY_DEFAULT)

    abstract class Param protected constructor() {

        private var extras: MutableMap<String, Any?>? = null

        /** Xposed stores a Bundle; nothing in WCX reads bundle-typed extras. */
        @Synchronized
        fun getExtra(): Bundle = Bundle()

        @Synchronized
        fun getObjectExtra(key: String): Any? = extras?.get(key)

        @Synchronized
        fun setObjectExtra(key: String, value: Any?) {
            val map = extras ?: HashMap<String, Any?>().also { extras = it }
            if (value == null) map.remove(key) else map[key] = value
        }
    }

    companion object {
        const val PRIORITY_DEFAULT: Int = 50
        const val PRIORITY_HIGHEST: Int = 10000
        const val PRIORITY_LOWEST: Int = -10000
    }
}
