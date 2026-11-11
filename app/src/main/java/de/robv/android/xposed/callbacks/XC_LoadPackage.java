package de.robv.android.xposed.callbacks;

import androidx.annotation.Keep;

import android.content.pm.ApplicationInfo;

/** 经典 Xposed {@code XC_LoadPackage} 的兼容实现（脚本子系统加载链需要这个类型）。 */
@Keep
public abstract class XC_LoadPackage {

    public static final String[] ALL_PROCESSES = {};

    @Keep
    public static final class LoadPackageParam {
        public String packageName;
        public String processName;
        public ClassLoader classLoader;
        public ApplicationInfo appInfo;
        public boolean isFirstApplication;
        public Object lpparam;
    }

    public abstract void handleLoadPackage(LoadPackageParam lpparam) throws Throwable;
}
