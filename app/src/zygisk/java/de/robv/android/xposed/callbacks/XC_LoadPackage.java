package de.robv.android.xposed.callbacks;

import android.content.pm.ApplicationInfo;

import de.robv.android.xposed.IXposedHookLoadPackage;

/**
 * Runtime stand-in for {@code XC_LoadPackage}. The Zygisk deployment does not
 * dispatch through this callback, but the types must exist so classes that
 * implement {@link IXposedHookLoadPackage} can be loaded.
 */
public abstract class XC_LoadPackage extends XCallback implements IXposedHookLoadPackage {

    protected XC_LoadPackage() {
    }

    public static final class LoadPackageParam extends XCallback.Param {

        public ApplicationInfo appInfo;
        public ClassLoader classLoader;
        public boolean isFirstApplication;
        public String packageName;
        public String processName;

        public LoadPackageParam() {
        }
    }
}
