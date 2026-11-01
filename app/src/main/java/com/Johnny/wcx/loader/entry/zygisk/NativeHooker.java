package com.Johnny.wcx.loader.entry.zygisk;

import java.lang.reflect.Executable;
import java.lang.reflect.Modifier;

/**
 * LSPlant 的 hooker 对象：所有被 hook 的方法共用这一个类。
 *
 * <p>LSPlant 6.x 的 {@code Hook(env, target, hookerObject, callbackMethod)} 允许分派方法签名
 * 与目标方法不一致，只要求 {@code Object callback(Object[] args)}。因此不需要像老版本那样
 * 按「返回值 × 参数类型」组合生成几十个 Hooker 子类。
 *
 * <p>拿到的 {@code target}（反射 Executable）用于在 {@link ZygiskHookBridge} 里找回该成员上
 * 注册的所有回调。
 */
public final class NativeHooker {

    private final Executable target;
    private final boolean isStatic;

    @SuppressWarnings("unused") // 由 native 侧 NewObject(Class, "<init>(Executable)", target) 调用
    public NativeHooker(Executable target) {
        this.target = target;
        this.isStatic = Modifier.isStatic(target.getModifiers());
    }

    /**
     * 由 LSPlant 在目标方法被调用时进入。
     *
     * <p>约定：静态方法 args 就是全部参数；实例方法 args[0] 是 this，其余是参数。
     *
     * @return 装箱后的返回值（void 返回 null）
     */
    @SuppressWarnings("unused") // 由 native 侧 ToReflectedMethod 之后交给 LSPlant 调用
    public Object callback(Object[] args) {
        Object thisObject = null;
        Object[] realArgs;
        if (isStatic || args.length == 0) {
            realArgs = args;
        } else {
            thisObject = args[0];
            realArgs = new Object[args.length - 1];
            System.arraycopy(args, 1, realArgs, 0, args.length - 1);
        }
        return ZygiskHookBridge.dispatch(target, thisObject, realArgs);
    }
}
