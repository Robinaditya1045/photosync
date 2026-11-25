package home.photosync;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import java.util.regex.Pattern;

/** One-screen setup: allow switching, paste the API key, add the widget. */
public class SetupActivity extends Activity {
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9_-]{16,}");

    private EditText key;
    private TextView result;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(R.layout.activity_setup);
        key = findViewById(R.id.key);
        result = findViewById(R.id.key_result);
        key.setText(Store.key(this));

        findViewById(R.id.open_syncthing).setOnClickListener(v -> {
            Intent i = Store.openSyncthing(this);
            if (i != null) startActivity(i);
            else show("Syncthing-Fork is not installed on this phone.", R.color.red);
        });
        findViewById(R.id.paste).setOnClickListener(v -> {
            String clip = clipboard();
            if (clip != null) key.setText(clip);
            else show("The clipboard has no API key. Copy it in Syncthing first.", R.color.amber);
        });
        findViewById(R.id.save).setOnClickListener(v -> save());

        View add = findViewById(R.id.add_widget);
        AppWidgetManager m = getSystemService(AppWidgetManager.class);
        if (Build.VERSION.SDK_INT >= 26 && m != null && m.isRequestPinAppWidgetSupported()) {
            add.setOnClickListener(v -> m.requestPinAppWidget(new ComponentName(this, SyncWidget.class), null, null));
        } else {
            add.setVisibility(View.GONE);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        showSteps();
    }

    private void showSteps() {
        boolean installed = Store.syncthingInstalled(this);
        state(R.id.step1_state, installed ? "" : "Not installed", R.color.red);
        state(R.id.step2_state, Store.key(this).isEmpty() ? "" : "Saved", R.color.green);
        AppWidgetManager m = AppWidgetManager.getInstance(this);
        boolean placed = m.getAppWidgetIds(new ComponentName(this, SyncWidget.class)).length > 0;
        state(R.id.step3_state, placed ? "Added" : "", R.color.green);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // Coming back from Syncthing with the key copied: fill it in. (Android only lets us read
        // the clipboard while this screen has focus.)
        if (hasFocus && key.getText().toString().trim().isEmpty()) {
            String clip = clipboard();
            if (clip != null) {
                key.setText(clip);
                show("Pasted the API key from the clipboard. Tap Save & test.", R.color.text2);
            }
        }
    }

    private void save() {
        String k = key.getText().toString().trim();
        if (!KEY.matcher(k).matches()) {
            show("That doesn't look like a Syncthing API key.", R.color.red);
            return;
        }
        Store.setKey(this, k);
        show("Checking…", R.color.text2);
        Context app = getApplicationContext();
        new Thread(() -> {
            int code = new Api(k).ping();
            runOnUiThread(() -> {
                if (code == 200) show("Connected to Syncthing ✓", R.color.green);
                else if (code == Api.DOWN) show("Saved. Syncthing isn't running right now, so the key will be checked later.", R.color.amber);
                else show("Syncthing rejected this key. Copy it again and paste it here.", R.color.red);
                showSteps();
            });
            SyncWidget.requestRefresh(app);
        }, "photosync-test").start();
    }

    private String clipboard() {
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        ClipData clip = cm == null ? null : cm.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) return null;
        CharSequence t = clip.getItemAt(0).coerceToText(this);
        String s = t == null ? "" : t.toString().replaceAll("\\s", "");
        return KEY.matcher(s).matches() ? s : null;
    }

    private void show(String msg, int color) {
        result.setVisibility(View.VISIBLE);
        result.setText(msg);
        result.setTextColor(getColor(color));
    }

    private void state(int id, String text, int color) {
        TextView t = findViewById(id);
        t.setText(text);
        t.setTextColor(getColor(color));
    }
}
