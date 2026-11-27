package com.Johnny.wcx.loader.utils

import com.Johnny.wcx.utils.reflection.ClassLoaders

object HybridClassLoader : ClassLoader(ClassLoaders.BOOT) {

    private val bootClassLoader = ClassLoaders.BOOT
    lateinit var moduleParentClassLoader: ClassLoader
    /**
     * The module's own loader when it cannot be reached through
     * [moduleParentClassLoader] (the Zygisk payload is loaded by an
     * `InMemoryDexClassLoader` whose parent is the system loader).
     */
    var moduleClassLoader: ClassLoader? = null
    lateinit var hostClassLoader: ClassLoader
    val additionalLoaders = mutableListOf<ClassLoader>()

    /**
     * Spring-loading the payload loader must not go through its parent:
     * `UnifiedEntryPoint` points that loader's parent at this class, so
     * `loadClass` here would re-enter this `findClass` for the same name and
     * recurse until the process is wedged. `ClassLoader.findClass` (protected)
     * is invoked reflectively so only the payload's own DEX is searched.
     */
    private val findClassWithoutDelegation: java.lang.reflect.Method? = runCatching {
        ClassLoader::class.java.getDeclaredMethod("findClass", String::class.java)
            .apply { isAccessible = true }
    }.getOrNull()

    /** Guards against any remaining self/cyclic delegation. */
    private val inFlight = ThreadLocal.withInitial { HashSet<String>() }

    private fun searchModuleLoader(name: String): Class<*>? {
        val loader = moduleClassLoader ?: return null
        return runCatching {
            when (val method = findClassWithoutDelegation) {
                null -> loader.loadClass(name)
                else -> method.invoke(loader, name) as Class<*>
            }
        }.getOrNull()
    }

    private const val PREFIX_BOOT = "BOOT."
    private const val PREFIX_MODULE = "MODULE."
    private const val PREFIX_HOST = "HOST."

    override fun findClass(name: String): Class<*> {
        val active = inFlight.get()
        if (!active.add(name)) {
            // Another frame in this thread is already resolving the same name
            // through this loader: report failure instead of recursing.
            throw ClassNotFoundException("recursive resolution of $name through HybridClassLoader")
        }
        try {
            return findClassInternal(name)
        } finally {
            active.remove(name)
        }
    }

    private fun findClassInternal(name: String): Class<*> {
        when {
            name.startsWith(PREFIX_BOOT) -> {
                return bootClassLoader.loadClass(name.removePrefix(PREFIX_BOOT))
            }
            name.startsWith(PREFIX_MODULE) -> {
                val moduleName = name.removePrefix(PREFIX_MODULE)
                searchModuleLoader(moduleName)?.let { return it }
                if (::moduleParentClassLoader.isInitialized) {
                    return moduleParentClassLoader.loadClass(moduleName)
                }
                throw ClassNotFoundException("Forced MODULE route failed: moduleParentClassLoader is not initialized. Class: $name")
            }
            name.startsWith(PREFIX_HOST) -> {
                if (::hostClassLoader.isInitialized) {
                    return hostClassLoader.loadClass(name.removePrefix(PREFIX_HOST))
                }
                throw ClassNotFoundException("Forced HOST route failed: hostClassLoader is not initialized. Class: $name")
            }
        }

        try {
            return bootClassLoader.loadClass(name)
        } catch (_: ClassNotFoundException) {}

        searchModuleLoader(name)?.let { return it }

        if (::moduleParentClassLoader.isInitialized) {
            try {
                return moduleParentClassLoader.loadClass(name)
            } catch (_: ClassNotFoundException) {}
        }

        if (::hostClassLoader.isInitialized) {
            try {
                return hostClassLoader.loadClass(name)
            } catch (_: ClassNotFoundException) {}
        }

        additionalLoaders.forEach {
            try {
                return it.loadClass(name)
            } catch (_: ClassNotFoundException) {}
        }

        throw ClassNotFoundException(name)
    }
}
