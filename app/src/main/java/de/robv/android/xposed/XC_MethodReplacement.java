package de.robv.android.xposed;

import androidx.annotation.Keep;

/**
 * 经典 Xposed {@code XC_MethodReplacement}：用返回值完全替换原方法。
 * 兼容层里把它实现成「before 回调里算出结果并 setResult」。
 */
@Keep
public abstract class XC_MethodReplacement extends XC_MethodHook {

    public static final XC_MethodReplacement DO_NOTHING = new XC_MethodReplacement(PRIORITY_HIGHEST * 2) {
        @Override
        protected Object replaceHookedMethod(MethodHookParam param) {
            return null;
        }
    };

    public XC_MethodReplacement() {
        super();
    }

    public XC_MethodReplacement(int priority) {
        super(priority);
    }

    @Override
    protected final void beforeHookedMethod(MethodHookParam param) throws Throwable {
        Object result = replaceHookedMethod(param);
        param.setResult(result);
    }

    @Override
    protected final void afterHookedMethod(MethodHookParam param) {
    }

    protected abstract Object replaceHookedMethod(MethodHookParam param) throws Throwable;
}
