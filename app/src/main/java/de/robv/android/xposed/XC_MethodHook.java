package de.robv.android.xposed;

import androidx.annotation.Keep;

import com.Johnny.wcx.loader.abc.IHookBridge;

import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * 经典 Xposed {@code XC_MethodHook} 的兼容实现。
 *
 * 关键点：真正的 Xposed 把参数暴露成**公有字段**（{@code param.method} / {@code param.thisObject} /
 * {@code param.args} / {@code param.result}），仓库里的老 Java 代码（h/Hchat、xp51 包装）都是按字段写的，
 * 所以这里也必须是字段而不是 getter。
 *
 * 字段与模块 hook 桥之间的同步在 {@link #handleBefore}/{@link #handleAfter} 里做：
 * before 前把桥状态灌进字段，用户回调跑完再把字段改动（result/throwable）推回桥。
 */
@Keep
public abstract class XC_MethodHook implements Comparable<XC_MethodHook> {

    public static final int PRIORITY_HIGHEST = 10000;
    public static final int PRIORITY_DEFAULT = 50;
    public static final int PRIORITY_LOWEST = -10000;

    private int priority = PRIORITY_DEFAULT;

    public XC_MethodHook() {
    }

    public XC_MethodHook(int priority) {
        this.priority = priority;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    @Override
    public int compareTo(XC_MethodHook other) {
        return Integer.compare(other.priority, this.priority);
    }

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
    }

    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
    }

    public final void handleBefore(MethodHookParam param) {
        param.pullFromBridge();
        try {
            beforeHookedMethod(param);
        } catch (Throwable t) {
            XposedBridge.log(t);
        } finally {
            param.pushToBridge();
        }
    }

    public final void handleAfter(MethodHookParam param) {
        param.pullFromBridge();
        try {
            afterHookedMethod(param);
        } catch (Throwable t) {
            XposedBridge.log(t);
        } finally {
            param.pushToBridge();
        }
    }

    /** 经典 Xposed 的 hook 参数（公有字段 + 桥转发）。 */
    @Keep
    public static final class MethodHookParam {
        public Member method;
        public Object thisObject;
        public Object[] args;
        public Object result;
        public Throwable throwable;

        /** 用户是否显式设置过 result（包括 null） */
        public boolean resultSet;

        private final IHookBridge.IMemberHookParam delegate;

        MethodHookParam(IHookBridge.IMemberHookParam delegate) {
            this.delegate = delegate;
            pullFromBridge();
        }

        void pullFromBridge() {
            method = delegate.getMember();
            thisObject = delegate.getThisObject();
            args = delegate.getArgs();
        }

        void pushToBridge() {
            if (resultSet || result != null) {
                delegate.setResult(result);
            }
            if (throwable != null) {
                delegate.setThrowable(throwable);
            }
        }

        public Object getResult() {
            return result;
        }

        public void setResult(Object value) {
            this.result = value;
            this.resultSet = true;
            delegate.setResult(value);
        }

        public Throwable getThrowable() {
            return throwable;
        }

        public void setThrowable(Throwable value) {
            this.throwable = value;
            delegate.setThrowable(value);
        }

        public boolean hasThrowable() {
            return throwable != null;
        }

        public Object getObjectExtra(String key) {
            return delegate.getExtra();
        }

        public void setObjectExtra(String key, Object value) {
            delegate.setExtra(value);
        }

        public Object invokeOriginalMethod() throws Throwable {
            if (!(method instanceof Method)) {
                throw new IllegalStateException("invokeOriginalMethod 只支持普通方法: " + method);
            }
            IHookBridge bridge = com.Johnny.wcx.loader.startup.StartupInfo.INSTANCE.getHookBridge();
            if (bridge == null) {
                throw new IllegalStateException("hook 桥不可用");
            }
            return bridge.invokeOriginalMethod((Method) method, thisObject, args);
        }

        @Override
        public String toString() {
            return "MethodHookParam{" + method + ", args=" + Arrays.toString(args) + '}';
        }
    }

    /** 卸载句柄，与经典 Xposed 同名。 */
    public interface Unhook {
        XC_MethodHook getCallback();

        Member getHookedMethod();

        void unhook();
    }
}
