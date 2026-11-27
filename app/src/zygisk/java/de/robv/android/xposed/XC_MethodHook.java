package de.robv.android.xposed;

import java.lang.reflect.Member;

import de.robv.android.xposed.callbacks.IXUnhook;
import de.robv.android.xposed.callbacks.XCallback;

/**
 * Runtime replacement for {@code de.robv.android.xposed.XC_MethodHook}.
 *
 * WCX subclasses override the protected callbacks; the Zygisk bridge adapter in
 * {@link XposedBridge} drives them, which keeps the original visibility.
 */
public abstract class XC_MethodHook extends XCallback {

    public XC_MethodHook() {
        super();
    }

    public XC_MethodHook(int priority) {
        super(priority);
    }

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
    }

    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
    }

    /** {@code args}, {@code method} and {@code thisObject} are fields in Xposed. */
    public static final class MethodHookParam extends XCallback.Param {

        public Object[] args = new Object[0];
        public Member method;
        public Object thisObject;

        private Object result;
        private Throwable throwable;
        private boolean resultSet;

        public MethodHookParam() {
        }

        public Object getResult() {
            return result;
        }

        public void setResult(Object result) {
            this.result = result;
            this.throwable = null;
            this.resultSet = true;
        }

        public Throwable getThrowable() {
            return throwable;
        }

        public boolean hasThrowable() {
            return throwable != null;
        }

        public void setThrowable(Throwable throwable) {
            this.throwable = throwable;
            this.result = null;
            this.resultSet = false;
        }

        public Object getResultOrThrowable() throws Throwable {
            if (throwable != null) {
                throw throwable;
            }
            return result;
        }

        boolean hasResult() {
            return resultSet;
        }

        void loadState(Object result, Throwable throwable) {
            this.result = result;
            this.throwable = throwable;
            this.resultSet = false;
        }
    }

    public static class Unhook implements IXUnhook<XC_MethodHook> {

        private final Member hookedMethod;
        private final XC_MethodHook callback;
        private final com.Johnny.wcx.loader.abc.IHookBridge.MemberUnhookHandle handle;

        Unhook(Member hookedMethod, XC_MethodHook callback,
               com.Johnny.wcx.loader.abc.IHookBridge.MemberUnhookHandle handle) {
            this.hookedMethod = hookedMethod;
            this.callback = callback;
            this.handle = handle;
        }

        public Member getHookedMethod() {
            return hookedMethod;
        }

        @Override
        public XC_MethodHook getCallback() {
            return callback;
        }

        @Override
        public void unhook() {
            handle.unhook();
        }
    }
}
