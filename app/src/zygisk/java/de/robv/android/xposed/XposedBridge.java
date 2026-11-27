package de.robv.android.xposed;

import java.lang.reflect.Constructor;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import com.Johnny.wcx.loader.abc.IHookBridge;
import com.Johnny.wcx.loader.startup.StartupInfo;
import com.Johnny.wcx.utils.WeLogger;

/**
 * Runtime replacement for {@code de.robv.android.xposed.XposedBridge}.
 *
 * Every operation is routed to the Zygisk {@link IHookBridge}; WCX is compiled
 * against the compileOnly Xposed API jar, so these entry points must behave the
 * way the Xposed implementation does for the calls WCX makes.
 */
public final class XposedBridge {

    private static final String TAG = "XposedBridgeShim";

    /** The Xposed API level WCX was compiled against. */
    public static int XPOSED_BRIDGE_VERSION = 82;

    public static final ClassLoader BOOTCLASSLOADER = ClassLoader.getSystemClassLoader();

    private static final Map<String, XC_MethodHook.Unhook> HANDLES = new HashMap<>();

    private XposedBridge() {
    }

    private static IHookBridge bridge() {
        IHookBridge bridge = StartupInfo.INSTANCE.getHookBridge();
        if (bridge == null) {
            throw new IllegalStateException(
                    "Xposed compatibility layer used before the Zygisk hook bridge is ready");
        }
        return bridge;
    }

    public static int getXposedVersion() {
        return XPOSED_BRIDGE_VERSION;
    }

    public static synchronized void log(String text) {
        WeLogger.INSTANCE.i(TAG, text);
    }

    public static synchronized void log(Throwable throwable) {
        WeLogger.INSTANCE.e(TAG, "module log", throwable);
    }

    public static XC_MethodHook.Unhook hookMethod(Member member, XC_MethodHook callback) {
        if (!(member instanceof Method) && !(member instanceof Constructor<?>)) {
            throw new IllegalArgumentException("hookMethod expects a Method or Constructor: " + member);
        }
        IHookBridge.MemberUnhookHandle handle =
                bridge().hookMethod(member, new Adapter(callback), callback.priority);
        XC_MethodHook.Unhook unhook = new XC_MethodHook.Unhook(member, callback, handle);
        synchronized (HANDLES) {
            HANDLES.put(key(member, callback), unhook);
        }
        return unhook;
    }

    public static void unhookMethod(Member member, XC_MethodHook callback) {
        XC_MethodHook.Unhook unhook;
        synchronized (HANDLES) {
            unhook = HANDLES.remove(key(member, callback));
        }
        if (unhook != null) {
            unhook.unhook();
        }
    }

    public static Set<XC_MethodHook.Unhook> hookAllMethods(
            Class<?> hookClass, String methodName, XC_MethodHook callback) {
        Set<XC_MethodHook.Unhook> result = new LinkedHashSet<>();
        for (Method method : hookClass.getDeclaredMethods()) {
            if (!method.getName().equals(methodName)) {
                continue;
            }
            method.setAccessible(true);
            result.add(hookMethod(method, callback));
        }
        return result;
    }

    public static Set<XC_MethodHook.Unhook> hookAllConstructors(
            Class<?> hookClass, XC_MethodHook callback) {
        Set<XC_MethodHook.Unhook> result = new LinkedHashSet<>();
        for (Constructor<?> constructor : hookClass.getDeclaredConstructors()) {
            constructor.setAccessible(true);
            result.add(hookMethod(constructor, callback));
        }
        return result;
    }

    public static Object invokeOriginalMethod(Member member, Object thisObject, Object[] args)
            throws Throwable {
        Object[] arguments = args == null ? new Object[0] : args;
        if (member instanceof Method) {
            return bridge().invokeOriginalMethod((Method) member, thisObject, arguments);
        }
        if (member instanceof Constructor<?>) {
            if (thisObject == null) {
                throw new IllegalArgumentException("constructor invocation needs an instance");
            }
            @SuppressWarnings("unchecked")
            Constructor<Object> constructor = (Constructor<Object>) member;
            bridge().invokeOriginalConstructor(constructor, thisObject, arguments);
            return null;
        }
        throw new IllegalArgumentException("unsupported member: " + member);
    }

    private static String key(Member member, XC_MethodHook callback) {
        return System.identityHashCode(member) + ":" + System.identityHashCode(callback);
    }

    /** Adapts an {@link XC_MethodHook} to the Zygisk bridge callback contract. */
    private static final class Adapter implements IHookBridge.IMemberHookCallback {

        private final XC_MethodHook callback;

        Adapter(XC_MethodHook callback) {
            this.callback = callback;
        }

        @Override
        public void beforeHookedMember(IHookBridge.IMemberHookParam param) {
            XC_MethodHook.MethodHookParam shim = toShim(param);
            try {
                if (callback instanceof XC_MethodReplacement) {
                    shim.setResult(((XC_MethodReplacement) callback).replaceHookedMethod(shim));
                } else {
                    callback.beforeHookedMethod(shim);
                }
            } catch (Throwable failure) {
                // A failing callback must not corrupt the host call; Xposed logs
                // and continues with the original method.
                WeLogger.INSTANCE.e(TAG, "before callback failed for " + param.getMember(), failure);
                return;
            }
            applyTo(shim, param);
        }

        @Override
        public void afterHookedMember(IHookBridge.IMemberHookParam param) {
            XC_MethodHook.MethodHookParam shim = toShim(param);
            try {
                callback.afterHookedMethod(shim);
            } catch (Throwable failure) {
                WeLogger.INSTANCE.e(TAG, "after callback failed for " + param.getMember(), failure);
                return;
            }
            applyTo(shim, param);
        }

        private XC_MethodHook.MethodHookParam toShim(IHookBridge.IMemberHookParam param) {
            XC_MethodHook.MethodHookParam shim = new XC_MethodHook.MethodHookParam();
            shim.args = param.getArgs();
            shim.method = param.getMember();
            shim.thisObject = param.getThisObject();
            shim.loadState(param.getResult(), param.getThrowable());
            return shim;
        }

        private void applyTo(XC_MethodHook.MethodHookParam shim, IHookBridge.IMemberHookParam param) {
            if (shim.hasResult()) {
                param.setResult(shim.getResult());
            }
            if (shim.getThrowable() != null) {
                param.setThrowable(shim.getThrowable());
            }
        }
    }
}
