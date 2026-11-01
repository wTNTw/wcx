package com.Johnny.wcx.loader.entry.zygisk;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;

/**
 * Zygisk 注入器内置的 native 桥（LSPlant）。
 *
 * <p>这些方法由 {@code zygisk/src/main/cpp/main.cpp} 在创建完 payload ClassLoader 之后用
 * {@code RegisterNatives} 显式注册 —— 模块自身的 .so 不是通过 {@code System.loadLibrary}
 * 加载的，ART 不会替它做符号解析，所以不能依赖 {@code Java_xxx} 命名约定。
 *
 * <p>必须写成 Java 的 {@code public static native}：Kotlin 的 {@code external fun} 在 object
 * 里是实例方法，JNI 函数签名会多一个 receiver，注册时容易搞错。
 */
public final class NativeHookBridge {

    private NativeHookBridge() {
    }

    /**
     * 初始化 LSPlant。必须在任何 hook 之前调用，重复调用无副作用。
     *
     * @return 是否初始化成功（失败时不能继续 hook）
     */
    public static native boolean nativeInit();

    /**
     * 把 {@code target} 替换为 {@code hookerClass} 的分派方法。
     *
     * <p>{@code hookerClass} 必须满足：
     * <pre>
     *   public Hooker(java.lang.reflect.Executable target)
     *   public Object callback(Object[] args)
     * </pre>
     *
     * @return 备份方法（反射 Method，可直接 invoke 调用原实现），失败返回 null
     */
    public static native Method nativeHook(Executable target, Class<?> hookerClass);

    public static native boolean nativeUnhook(Executable target);

    public static native boolean nativeIsHooked(Executable target);

    /** 反优化调用者，避免内联导致 hook 不生效。 */
    public static native boolean nativeDeoptimize(Executable target);

    /**
     * 把已预加载的 payload 原生库里的符号注册给 {@code clazz}。
     *
     * <p>不装 APK 时 payload 的 .so 是注入器在 zygote 阶段 dlopen 的，
     * 不经过 ART 的 nativeLoad，因此 {@code System.loadLibrary} /
     * 默认符号查找都不可用，只能显式 RegisterNatives。
     *
     * @param symbols 每项形如 {@code "nativeFoo|(ILjava/lang/String;)V"}
     */
    public static native boolean nativeBindNatives(Class<?> clazz, String[] symbols);
}
