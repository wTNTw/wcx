package de.robv.android.xposed

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * Runtime replacement for `de.robv.android.xposed.XposedHelpers`.
 *
 * Only the helpers WCX calls are implemented; anything else would silently
 * return wrong results, so unknown lookups fail loudly instead.
 */
object XposedHelpers {

    @JvmStatic
    fun findClass(className: String, classLoader: ClassLoader?): Class<*> {
        val loader = classLoader ?: XposedBridge.BOOTCLASSLOADER
        return try {
            Class.forName(className, false, loader)
        } catch (e: ClassNotFoundException) {
            if (loader === XposedBridge.BOOTCLASSLOADER) throw e
            Class.forName(className, false, XposedBridge.BOOTCLASSLOADER)
        }
    }

    @JvmStatic
    fun findClassIfExists(className: String, classLoader: ClassLoader?): Class<*>? =
        runCatching { findClass(className, classLoader) }.getOrNull()

    @JvmStatic
    fun findAndHookMethod(
        clazz: Class<*>,
        methodName: String,
        vararg parameterTypesAndCallback: Any?,
    ): XC_MethodHook.Unhook {
        val callback = parameterTypesAndCallback.lastOrNull() as? XC_MethodHook
            ?: throw IllegalArgumentException("last argument must be an XC_MethodHook")
        val types = parameterTypesAndCallback.dropLast(1).toTypedArray()
        return findAndHookMethod(clazz, methodName, callback, types)
    }

    @JvmStatic
    fun findAndHookMethod(
        className: String,
        classLoader: ClassLoader?,
        methodName: String,
        vararg parameterTypesAndCallback: Any?,
    ): XC_MethodHook.Unhook = findAndHookMethod(
        findClass(className, classLoader),
        methodName,
        *parameterTypesAndCallback,
    )

    private fun findAndHookMethod(
        clazz: Class<*>,
        methodName: String,
        callback: XC_MethodHook,
        parameterTypes: Array<Any?>,
    ): XC_MethodHook.Unhook {
        val types = getParameterTypes(*parameterTypes)
        val method = runCatching { clazz.getDeclaredMethod(methodName, *types) }
            .getOrElse { findMethodBestMatch(clazz, methodName, types) }
        method.isAccessible = true
        return XposedBridge.hookMethod(method, callback)
    }

    @JvmStatic
    fun callMethod(instance: Any, methodName: String, vararg args: Any?): Any? {
        val method = findMethodBestMatch(instance.javaClass, methodName, *args)
        method.isAccessible = true
        return try {
            method.invoke(instance, *args)
        } catch (e: InvocationTargetException) {
            throw e.targetException ?: e
        }
    }

    @JvmStatic
    fun callMethod(
        instance: Any,
        methodName: String,
        parameterTypes: Array<Class<*>>,
        vararg args: Any?,
    ): Any? {
        val method = instance.javaClass.getDeclaredMethod(methodName, *parameterTypes)
        method.isAccessible = true
        return try {
            method.invoke(instance, *args)
        } catch (e: InvocationTargetException) {
            throw e.targetException ?: e
        }
    }

    @JvmStatic
    fun callStaticMethod(clazz: Class<*>, methodName: String, vararg args: Any?): Any? {
        val method = findMethodBestMatch(clazz, methodName, *args)
        method.isAccessible = true
        return try {
            method.invoke(null, *args)
        } catch (e: InvocationTargetException) {
            throw e.targetException ?: e
        }
    }

    @JvmStatic
    fun getParameterTypes(vararg parameterTypes: Any?): Array<Class<*>> =
        parameterTypes.map { type ->
            when (type) {
                is Class<*> -> type
                is String -> findClass(type, null)
                else -> throw IllegalArgumentException("unsupported parameter type: $type")
            }
        }.toTypedArray()

    @JvmStatic
    fun getClassesAsArray(vararg classes: Class<*>): Array<Class<*>> = classes

    /**
     * Picks the method whose parameter types can accept [args]; exact matches win.
     */
    private fun findMethodBestMatch(
        clazz: Class<*>,
        methodName: String,
        vararg args: Any?,
    ): Method {
        val candidates = clazz.declaredMethods.filter { it.name == methodName }
        candidates.firstOrNull { method ->
            method.parameterCount == args.size &&
                method.parameterTypes.zip(args).all { (type, arg) -> arg == null || boxed(type).isInstance(arg) }
        }?.let { return it }
        // Fall back to a name-only match when the argument types are unknown to us
        // (for example a lazily initialised host object).
        return candidates.singleOrNull()
            ?: clazz.getDeclaredMethod(methodName, *args.map { it?.javaClass ?: Any::class.java }.toTypedArray())
    }

    private fun boxed(type: Class<*>): Class<*> = when (type) {
        java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
        java.lang.Byte.TYPE -> java.lang.Byte::class.java
        java.lang.Character.TYPE -> java.lang.Character::class.java
        java.lang.Short.TYPE -> java.lang.Short::class.java
        java.lang.Integer.TYPE -> java.lang.Integer::class.java
        java.lang.Long.TYPE -> java.lang.Long::class.java
        java.lang.Float.TYPE -> java.lang.Float::class.java
        java.lang.Double.TYPE -> java.lang.Double::class.java
        else -> type
    }
}
