package de.robv.android.xposed;

/** Binary-compatible stand-in for the zygote-init hook interface. */
public interface IXposedHookZygoteInit {

    void initZygote(StartupParam startupParam) throws Throwable;

    class StartupParam {
        public String modulePath;
        public boolean startsSystemServer;
    }
}
