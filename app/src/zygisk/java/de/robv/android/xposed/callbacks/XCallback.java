package de.robv.android.xposed.callbacks;

import android.os.Bundle;

import java.util.HashMap;
import java.util.Map;

/**
 * Runtime replacement for the Xposed callback base class.
 *
 * WCX is compiled against {@code app/libs/xposed-api-82.jar} (compileOnly), so
 * this class must stay binary compatible with it. Members nothing calls are
 * deliberately omitted rather than silently mis-implemented.
 */
public abstract class XCallback {

    public static final int PRIORITY_DEFAULT = 50;
    public static final int PRIORITY_HIGHEST = 10000;
    public static final int PRIORITY_LOWEST = -10000;

    public final int priority;

    protected XCallback() {
        this(PRIORITY_DEFAULT);
    }

    protected XCallback(int priority) {
        this.priority = priority;
    }

    public abstract static class Param {

        private Map<String, Object> objectExtras;

        protected Param() {
        }

        public synchronized Bundle getExtra() {
            return new Bundle();
        }

        public synchronized Object getObjectExtra(String key) {
            return objectExtras == null ? null : objectExtras.get(key);
        }

        public synchronized void setObjectExtra(String key, Object value) {
            if (value == null) {
                if (objectExtras != null) {
                    objectExtras.remove(key);
                }
                return;
            }
            if (objectExtras == null) {
                objectExtras = new HashMap<>();
            }
            objectExtras.put(key, value);
        }
    }
}
