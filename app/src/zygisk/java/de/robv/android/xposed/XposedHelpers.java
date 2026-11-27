package de.robv.android.xposed;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Runtime replacement for {@code de.robv.android.xposed.XposedHelpers}.
 *
 * Only the helpers WCX calls are implemented. Unknown lookups fail loudly
 * instead of returning a plausible but wrong member.
 */
public final class XposedHelpers {

    private XposedHelpers() {
    }

    public static Class<?> findClass(String className, ClassLoader classLoader) {
        ClassLoader loader = classLoader == null ? XposedBridge.BOOTCLASSLOADER : classLoader;
        try {
            return Class.forName(className, false, loader);
        } catch (ClassNotFoundException notFound) {
            if (loader == XposedBridge.BOOTCLASSLOADER) {
                throw new RuntimeException(notFound);
            }
            try {
                return Class.forName(className, false, XposedBridge.BOOTCLASSLOADER);
            } catch (ClassNotFoundException alsoMissing) {
                throw new RuntimeException("cannot find class " + className, alsoMissing);
            }
        }
    }

    public static Class<?> findClassIfExists(String className, ClassLoader classLoader) {
        try {
            return findClass(className, classLoader);
        } catch (RuntimeException missing) {
            return null;
        }
    }

    public static XC_MethodHook.Unhook findAndHookMethod(
            Class<?> clazz, String methodName, Object... parameterTypesAndCallback) {
        if (parameterTypesAndCallback == null || parameterTypesAndCallback.length == 0) {
            throw new IllegalArgumentException("missing XC_MethodHook for " + methodName);
        }
        Object last = parameterTypesAndCallback[parameterTypesAndCallback.length - 1];
        if (!(last instanceof XC_MethodHook)) {
            throw new IllegalArgumentException("last argument must be an XC_MethodHook");
        }
        XC_MethodHook callback = (XC_MethodHook) last;
        int typeCount = parameterTypesAndCallback.length - 1;
        Object[] rawTypes = new Object[typeCount];
        System.arraycopy(parameterTypesAndCallback, 0, rawTypes, 0, typeCount);
        Class<?>[] types = getParameterTypes(rawTypes);
        Method method = findMethodBestMatch(clazz, methodName, types);
        method.setAccessible(true);
        return XposedBridge.hookMethod(method, callback);
    }

    public static XC_MethodHook.Unhook findAndHookMethod(
            String className, ClassLoader classLoader, String methodName,
            Object... parameterTypesAndCallback) {
        return findAndHookMethod(
                findClass(className, classLoader), methodName, parameterTypesAndCallback);
    }

    public static Object callMethod(Object instance, String methodName, Object... args) {
        Method method = findMethodBestMatch(instance.getClass(), methodName, args);
        method.setAccessible(true);
        try {
            return method.invoke(instance, args);
        } catch (InvocationTargetException e) {
            throw new RuntimeException(e.getTargetException() == null ? e : e.getTargetException());
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    public static Object callStaticMethod(Class<?> clazz, String methodName, Object... args) {
        Method method = findMethodBestMatch(clazz, methodName, args);
        method.setAccessible(true);
        try {
            return method.invoke(null, args);
        } catch (InvocationTargetException e) {
            throw new RuntimeException(e.getTargetException() == null ? e : e.getTargetException());
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    public static Class<?>[] getParameterTypes(Object... parameterTypes) {
        Class<?>[] types = new Class<?>[parameterTypes.length];
        for (int i = 0; i < parameterTypes.length; i++) {
            Object type = parameterTypes[i];
            if (type instanceof Class<?>) {
                types[i] = (Class<?>) type;
            } else if (type instanceof String) {
                types[i] = findClass((String) type, null);
            } else {
                throw new IllegalArgumentException("unsupported parameter type: " + type);
            }
        }
        return types;
    }

    public static Class<?>[] getClassesAsArray(Class<?>... classes) {
        return classes;
    }

    private static Method findMethodBestMatch(Class<?> clazz, String methodName, Class<?>[] types) {
        try {
            return clazz.getDeclaredMethod(methodName, types);
        } catch (NoSuchMethodException ignored) {
            // Fall through to a best-effort search below.
        }
        Method best = null;
        for (Method method : clazz.getDeclaredMethods()) {
            if (!method.getName().equals(methodName) || method.getParameterCount() != types.length) {
                continue;
            }
            boolean compatible = true;
            Class<?>[] declared = method.getParameterTypes();
            for (int i = 0; i < declared.length; i++) {
                if (types[i] != null && !box(declared[i]).isAssignableFrom(box(types[i]))) {
                    compatible = false;
                    break;
                }
            }
            if (compatible) {
                if (best != null) {
                    throw new IllegalArgumentException(
                            "ambiguous method " + methodName + " on " + clazz.getName());
                }
                best = method;
            }
        }
        if (best == null) {
            throw new IllegalArgumentException(
                    "no method " + methodName + " on " + clazz.getName() + " for " + types.length + " args");
        }
        return best;
    }

    private static Method findMethodBestMatch(Class<?> clazz, String methodName, Object[] args) {
        Class<?>[] types = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            types[i] = args[i] == null ? null : args[i].getClass();
        }
        Method best = null;
        for (Method method : clazz.getDeclaredMethods()) {
            if (!method.getName().equals(methodName) || method.getParameterCount() != args.length) {
                continue;
            }
            Class<?>[] declared = method.getParameterTypes();
            boolean compatible = true;
            for (int i = 0; i < declared.length; i++) {
                if (types[i] != null && !box(declared[i]).isAssignableFrom(box(types[i]))) {
                    compatible = false;
                    break;
                }
            }
            if (!compatible) {
                continue;
            }
            if (best == null) {
                best = method;
                continue;
            }
            // Prefer the more specific parameter types.
            Class<?>[] bestTypes = best.getParameterTypes();
            boolean moreSpecific = true;
            for (int i = 0; i < declared.length; i++) {
                if (!box(bestTypes[i]).isAssignableFrom(box(declared[i]))) {
                    moreSpecific = false;
                    break;
                }
            }
            if (moreSpecific) {
                best = method;
            }
        }
        if (best == null) {
            throw new IllegalArgumentException(
                    "no method " + methodName + " on " + clazz.getName() + " for " + args.length + " args");
        }
        return best;
    }

    private static Class<?> box(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == boolean.class) return Boolean.class;
        if (type == byte.class) return Byte.class;
        if (type == char.class) return Character.class;
        if (type == short.class) return Short.class;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        return type;
    }
}
