package dev.wcx.independenthook.probe;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import android.os.Debug;
import android.util.Log;
import android.widget.TextView;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;

public final class MainActivity extends Activity {
    private static final String TAG = "WcxIndependentHook";
    private static final String LIBRARY = "wcx_independent_hook_probe";

    private static native int start(Constructor<?> constructor, Method method);
    private static native int[] snapshot();
    private static native int stop();

    private static final class Fixture {
        private final int seed;

        Fixture(int seed) {
            this.seed = seed;
        }

        int evaluate(int input) {
            if (input < 0) throw new IllegalArgumentException("negative input");
            return seed + input;
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        String outcome;
        try {
            outcome = runProbe();
            Log.i(TAG, outcome);
        } catch (Exception | AssertionError | LinkageError failure) {
            outcome = "RESULT fail: " + failure;
            Log.e(TAG, outcome, failure);
        }
        TextView view = new TextView(this);
        view.setText(outcome);
        setContentView(view);
    }

    private String runProbe() throws Exception {
        require((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0, "probe must be debuggable");
        try {
            Class.forName("de.robv.android.xposed.XposedBridge", false, getClassLoader());
            throw new AssertionError("XposedBridge is visible to the test APK");
        } catch (ClassNotFoundException expected) {
            Log.i(TAG, "xposed_bridge=absent");
        }

        int baseline = new Fixture(7).evaluate(3);
        require(baseline == 10, "wrong baseline result");
        System.loadLibrary(LIBRARY);
        Debug.attachJvmtiAgent("lib" + LIBRARY + ".so", null, getClassLoader());

        int code = start(Fixture.class.getDeclaredConstructor(int.class), Fixture.class.getDeclaredMethod("evaluate", int.class));
        require(code == 0, "could not enable method callbacks: " + code);
        int observed;
        int[] events;
        try {
            Fixture fixture = new Fixture(7);
            observed = fixture.evaluate(3);
            try {
                fixture.evaluate(-1);
                throw new AssertionError("fixture exception did not propagate");
            } catch (IllegalArgumentException expected) {
                // An exceptional exit must still reach the callback without altering Java behavior.
            }
            events = snapshot();
        } finally {
            int stopped = stop();
            require(stopped == 0, "could not disable method callbacks: " + stopped);
        }
        require(events[0] == 1 && events[1] == 1 && events[2] == 2 && events[3] == 1 && events[4] == 1,
                "incorrect event counts: " + Arrays.toString(events));
        boolean argumentChanged = events[7] == 0;
        require(observed == (argumentChanged ? 15 : 10) && events[5] == observed, "incorrect return value");
        int restored = new Fixture(7).evaluate(3);
        require(restored == baseline && Arrays.equals(events, snapshot()), "callbacks survived stop");
        return "RESULT pass constructors=" + events[0] + "/" + events[1] + " entries=" + events[2]
                + " returns=" + events[3] + " exceptions=" + events[4] + " baseline=" + baseline
                + " hooked=" + observed + " restored=" + restored + " get_local_status=" + events[6]
                + " set_local_status=" + events[7] + " force_return_cap=" + events[8] + " input_slot=" + events[9];
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }
}
