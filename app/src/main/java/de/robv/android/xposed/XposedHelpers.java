package de.robv.android.xposed;

import androidx.annotation.Keep;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;

/**
 * 经典 Xposed {@code XposedHelpers} 的兼容实现（只实现脚本子系统实际用到的部分）：
 * {@code findClass} / {@code findAndHookMethod} / {@code callMethod} / 常用字段读写。
 */
@Keep
public final class XposedHelpers {

    private XposedHelpers() {
    }

    public static Class<?> findClass(String className, ClassLoader classLoader) {
        ClassLoader loader = classLoader != null
                ? classLoader
                : Thread.currentThread().getContextClassLoader();
        try {
            return Class.forName(className, false, loader);
        } catch (ClassNotFoundException e) {
            throw new ClassNotFoundError(e);
        }
    }

    public static XC_MethodHook.Unhook findAndHookMethod(
            Class<?> clazz, String methodName, Object... parameterTypesAndCallback) {
        if (parameterTypesAndCallback.length == 0
                || !(parameterTypesAndCallback[parameterTypesAndCallback.length - 1]
                instanceof XC_MethodHook)) {
            throw new IllegalArgumentException("no callback defined");
        }
        XC_MethodHook callback = (XC_MethodHook)
                parameterTypesAndCallback[parameterTypesAndCallback.length - 1];
        Class<?>[] parameterTypes = resolveParameterTypes(
                parameterTypesAndCallback, parameterTypesAndCallback.length - 1);
        Method method = findMethodExact(clazz, methodName, parameterTypes);
        return XposedBridge.hookMethod(method, callback);
    }

    public static XC_MethodHook.Unhook findAndHookMethod(
            String className, ClassLoader classLoader, String methodName,
            Object... parameterTypesAndCallback) {
        return findAndHookMethod(findClass(className, classLoader), methodName,
                parameterTypesAndCallback);
    }

    public static Method findMethodExact(Class<?> clazz, String methodName,
                                         Class<?>... parameterTypes) {
        Class<?> current = clazz;
        while (current != null) {
            try {
                Method method = current.getDeclaredMethod(methodName, parameterTypes);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchMethodError(clazz.getName() + "#" + methodName
                + Arrays.toString(parameterTypes));
    }

    public static Object callMethod(Object obj, String methodName, Object... args) {
        if (obj == null) {
            throw new NullPointerException("callMethod: object is null");
        }
        Method method = findMethodByArgs(obj.getClass(), methodName, args);
        try {
            return method.invoke(obj, args);
        } catch (ReflectiveOperationException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new IllegalStateException("callMethod failed: " + methodName, cause);
        }
    }

    public static Object callStaticMethod(Class<?> clazz, String methodName, Object... args) {
        Method method = findMethodByArgs(clazz, methodName, args);
        try {
            return method.invoke(null, args);
        } catch (ReflectiveOperationException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new IllegalStateException("callStaticMethod failed: " + methodName, cause);
        }
    }

    public static Object getObjectField(Object obj, String fieldName) {
        return getFieldValue(obj, fieldName);
    }

    public static void setObjectField(Object obj, String fieldName, Object value) {
        Field field = findFieldRecursive(obj.getClass(), fieldName);
        try {
            field.set(obj, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("setObjectField failed: " + fieldName, e);
        }
    }

    public static Object getStaticObjectField(Class<?> clazz, String fieldName) {
        return getFieldValue(clazz, fieldName);
    }

    public static Field findField(Class<?> clazz, String fieldName) {
        return findFieldRecursive(clazz, fieldName);
    }

    private static Object getFieldValue(Object target, String fieldName) {
        Class<?> clazz = target instanceof Class ? (Class<?>) target : target.getClass();
        Field field = findFieldRecursive(clazz, fieldName);
        try {
            return field.get(target instanceof Class ? null : target);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("getObjectField failed: " + fieldName, e);
        }
    }

    private static Field findFieldRecursive(Class<?> clazz, String name) {
        Class<?> current = clazz;
        while (current != null) {
            for (Field field : current.getDeclaredFields()) {
                if (!field.getName().equals(name)) continue;
                field.setAccessible(true);
                return field;
            }
            current = current.getSuperclass();
        }
        throw new NoSuchFieldError(clazz.getName() + "#" + name);
    }

    private static Method findMethodByArgs(Class<?> clazz, String name, Object[] args) {
        int argCount = args == null ? 0 : args.length;
        Class<?> current = clazz;
        while (current != null) {
            for (Method method : current.getDeclaredMethods()) {
                if (!method.getName().equals(name)) continue;
                if (method.getParameterTypes().length != argCount) continue;
                method.setAccessible(true);
                return method;
            }
            current = current.getSuperclass();
        }
        throw new NoSuchMethodError(clazz.getName() + "#" + name + "/" + argCount);
    }

    private static Class<?>[] resolveParameterTypes(Object[] values, int count) {
        Class<?>[] types = new Class<?>[count];
        for (int i = 0; i < count; i++) {
            Object value = values[i];
            if (value instanceof Class) {
                types[i] = (Class<?>) value;
            } else if (value instanceof String) {
                types[i] = findClass((String) value, null);
            } else {
                throw new IllegalArgumentException("参数类型只能是 Class 或类名字符串: " + value);
            }
        }
        return types;
    }

    /** 与经典 Xposed 同名的错误类型。 */
    public static final class ClassNotFoundError extends Error {
        ClassNotFoundError(Throwable cause) {
            super(cause);
        }
    }

    /** 与经典 Xposed 同名的错误类型（NoSuchMethod 场景）。 */
    public static final class InvocationTargetError extends Error {
        InvocationTargetError(Throwable cause) {
            super(cause);
        }
    }
}
