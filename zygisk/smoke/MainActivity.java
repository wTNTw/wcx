package dev.wcx.zygisk.probe;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

public final class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String visibility;
        try {
            Class.forName("de.robv.android.xposed.XposedBridge", false, getClassLoader());
            visibility = "present";
        } catch (ClassNotFoundException ignored) {
            visibility = "absent";
        } catch (LinkageError error) {
            visibility = "linkage-error";
        }
        String result = "probe_app_started xposed_bridge=" + visibility;
        Log.i("WcxZygiskAppProbe", result);
        TextView view = new TextView(this);
        view.setText(result);
        setContentView(view);
    }
}
