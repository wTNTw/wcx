package de.robv.android.xposed;

import androidx.annotation.Keep;

import com.Johnny.wcx.loader.abc.IHookBridge;
import com.Johnny.wcx.loader.startup.StartupInfo;
import com.Johnny.wcx.utils.WeLogger;

import java.lang.reflect.Constructor;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * {@code de.robv.android.xposed.XposedBridge} 的兼容实现（Zygisk 独立模式用）。
 *
 * 背景：模块里的 {@code h/Hchat} 脚本子系统是从上游 Hchat 移植过来的老代码，全部按经典
 * Xposed API 写（{@code XposedBridge.hookAllMethods} / {@code XC_MethodHook} / …）。
 * 在 Zygisk 独立模式下没有 Xposed 框架，这些类不存在 → 引导阶段直接
 * {@code NoClassDefFoundError: de/robv/android/xposed/XposedBridge}，脚本总开关、脚本插件卡片
 * 全都用不了（点卡片还会把微信卡死重启）。
 *
 * 这个文件（以及同包的 {@link XC_MethodHook} / {@link XposedHelpers} 等）把经典 Xposed API
 * 转发到本模块自己的 hook 桥（{@link IHookBridge}），于是老代码不用改一行就能跑起来。
 *
 * 注意：LSPosed 模式下框架自带真正的 XposedBridge（boot classpath），本类不会被用到。
 */
@Keep
public final class XposedBridge {

    /** 与经典 Xposed 对齐的默认优先级。 */
    public static final int PRIORITY_DEFAULT = 50;

    private static final String TAG = "XposedBridgeCompat";

    private XposedBridge() {
    }

    /**
     * 兼容字段：老代码里有直接读 {@code XposedBridge.BOOTCLASSLOADER} 的写法。
     * 这里给当前线程的上下文 ClassLoader（通常是宿主微信的）。
     */
    public static volatile ClassLoader BOOTCLASSLOADER =
            Thread.currentThread().getContextClassLoader();

    public static int getXposedVersion() {
        return 82;
    }

    public static void log(String text) {
        WeLogger.INSTANCE.i(TAG, String.valueOf(text));
    }

    public static void log(Throwable t) {
        WeLogger.INSTANCE.e(TAG, "XposedBridge.log(Throwable)", t);
    }

    public static XC_MethodHook.Unhook hookMethod(Member hookMethod, XC_MethodHook callback) {
        if (hookMethod == null) {
            log("hookMethod: member is null");
            return null;
        }
        if (callback == null) {
            log("hookMethod: callback is null");
            return null;
        }
        IHookBridge bridge = requireBridge();
        IHookBridge.MemberUnhookHandle handle = bridge.hookMethod(
                hookMethod,
                new CallbackAdapter(callback),
                callback.getPriority()
        );
        return new UnhookImpl(handle);
    }

    public static Set<XC_MethodHook.Unhook> hookAllMethods(
            Class<?> hookClass, String methodName, XC_MethodHook callback) {
        if (hookClass == null) return Collections.emptySet();
        Set<XC_MethodHook.Unhook> result = new LinkedHashSet<>();
        for (Method method : hookClass.getDeclaredMethods()) {
            if (!method.getName().equals(methodName)) continue;
            try {
                method.setAccessible(true);
            } catch (Throwable ignored) {
            }
            XC_MethodHook.Unhook unhook = hookMethod(method, callback);
            if (unhook != null) result.add(unhook);
        }
        return result;
    }

    public static Set<XC_MethodHook.Unhook> hookAllConstructors(
            Class<?> hookClass, XC_MethodHook callback) {
        if (hookClass == null) return Collections.emptySet();
        Set<XC_MethodHook.Unhook> result = new LinkedHashSet<>();
        for (Constructor<?> ctor : hookClass.getDeclaredConstructors()) {
            try {
                ctor.setAccessible(true);
            } catch (Throwable ignored) {
            }
            XC_MethodHook.Unhook unhook = hookMethod(ctor, callback);
            if (unhook != null) result.add(unhook);
        }
        return result;
    }

    /** 经典 Xposed 的静态工具：按原方法执行（会绕过当前 hook 链）。 */
    public static Object invokeOriginalMethod(Member method, Object thisObject, Object[] args)
            throws Throwable {
        if (!(method instanceof Method)) {
            throw new IllegalArgumentException("invokeOriginalMethod 只支持普通方法: " + method);
        }
        return requireBridge().invokeOriginalMethod(
                (Method) method,
                thisObject,
                args == null ? new Object[0] : args
        );
    }

    private static IHookBridge requireBridge() {
        IHookBridge bridge = StartupInfo.INSTANCE.getHookBridge();
        if (bridge == null) {
            throw new IllegalStateException(
                    "XposedBridge 兼容层：当前加载链没有 hook 桥（StartupInfo.hookBridge == null）");
        }
        return bridge;
    }

    /** 把 Xposed 回调适配到模块自己的 hook 桥。 */
    private static final class CallbackAdapter implements IHookBridge.IMemberHookCallback {
        private final XC_MethodHook callback;

        CallbackAdapter(XC_MethodHook callback) {
            this.callback = callback;
        }

        @Override
        public void beforeHookedMember(IHookBridge.IMemberHookParam param) {
            callback.handleBefore(new XC_MethodHook.MethodHookParam(param));
        }

        @Override
        public void afterHookedMember(IHookBridge.IMemberHookParam param) {
            callback.handleAfter(new XC_MethodHook.MethodHookParam(param));
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof CallbackAdapter && ((CallbackAdapter) o).callback == callback;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(callback);
        }
    }

    /** {@link XC_MethodHook.Unhook} 的转发实现。 */
    private static final class UnhookImpl implements XC_MethodHook.Unhook {
        private final IHookBridge.MemberUnhookHandle handle;

        UnhookImpl(IHookBridge.MemberUnhookHandle handle) {
            this.handle = handle;
        }

        @Override
        public XC_MethodHook getCallback() {
            return null;
        }

        @Override
        public Member getHookedMethod() {
            return handle.getMember();
        }

        @Override
        public void unhook() {
            try {
                handle.unhook();
            } catch (Throwable t) {
                log(t);
            }
        }
    }
}
