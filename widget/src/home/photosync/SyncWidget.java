package home.photosync;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.View;
import android.widget.RemoteViews;
import java.util.Date;

/** The home-screen widget: progress ring, status, per-folder rows and an Auto / On / Off switch. */
public class SyncWidget extends AppWidgetProvider {
    private static final String ACT_REFRESH = "home.photosync.REFRESH"; // tap: refresh, show "Updating…"
    private static final String ACT_TICK = "home.photosync.TICK";       // timer while sending: silent
    private static final String ACT_MODE = "home.photosync.MODE";
    private static final String EXTRA_MODE = "mode";
    private static final long TICK_MS = 60_000;

    private static final int[][] ROWS = {
            {R.id.row1, R.id.row1_name, R.id.row1_value},
            {R.id.row2, R.id.row2_name, R.id.row2_value},
            {R.id.row3, R.id.row3_name, R.id.row3_value},
            {R.id.row4, R.id.row4_name, R.id.row4_value},
    };

    // --- events -------------------------------------------------------------------

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        refresh(c, null);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context c, AppWidgetManager m, int id, Bundle options) {
        render(c, Store.last(c), null); // resized: show or hide the folder rows
    }

    @Override
    public void onDisabled(Context c) {
        alarms(c).cancel(broadcast(c, ACT_TICK, null, 5));
    }

    @Override
    public void onReceive(Context c, Intent intent) {
        super.onReceive(c, intent);
        String a = intent.getAction();
        if (ACT_REFRESH.equals(a)) refresh(c, "Updating…");
        else if (ACT_TICK.equals(a)) refresh(c, null);
        else if (ACT_MODE.equals(a)) switchMode(c, intent.getStringExtra(EXTRA_MODE));
    }

    /** Ask any widget to refresh (used by the setup screen after saving the key). */
    static void requestRefresh(Context c) {
        c.sendBroadcast(new Intent(c, SyncWidget.class).setAction(ACT_TICK));
    }

    // --- work ---------------------------------------------------------------------

    private void refresh(Context c, String busy) {
        Context app = c.getApplicationContext();
        if (busy != null) render(app, Store.last(app), busy);
        PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                finish(app, Store.check(app));
            } finally {
                pending.finish();
            }
        }, "photosync-refresh").start();
    }

    private void switchMode(Context c, String mode) {
        Context app = c.getApplicationContext();
        if (mode == null) return;
        if (!Store.syncthingInstalled(app)) {
            finish(app, Report.of(Report.Kind.NO_APP));
            return;
        }
        Store.setMode(app, mode);
        Store.sendMode(app, mode);
        render(app, Store.last(app), Store.ON.equals(mode) ? "Turning on…" : Store.OFF.equals(mode) ? "Turning off…" : "Switching to Auto…");

        PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                String key = Store.key(app);
                boolean ok = true;
                if (Store.ON.equals(mode)) {
                    ok = waitFor(key, true, 30_000);
                    sleep(3000); // give it a moment to reach the Pixel
                } else if (Store.OFF.equals(mode)) {
                    ok = waitFor(key, false, 20_000);
                } else {
                    sleep(6000);
                }
                Report r = Store.check(app);
                if (!ok && !key.isEmpty()) {
                    r = Report.error(Store.ON.equals(mode) ? "Syncthing didn't start" : "Syncthing is still running",
                            "Turn on “Service Control by Broadcast” in Syncthing");
                }
                finish(app, r);
            } finally {
                pending.finish();
            }
        }, "photosync-switch").start();
    }

    private static boolean waitFor(String key, boolean up, long maxMs) {
        if (key.isEmpty()) return true;
        Api api = new Api(key);
        long end = SystemClock.elapsedRealtime() + maxMs;
        while (SystemClock.elapsedRealtime() < end) {
            if ((api.ping() != Api.DOWN) == up) return true;
            sleep(2000);
        }
        return false;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static void finish(Context c, Report r) {
        if (r.kind != Report.Kind.ERROR) Store.saveLast(c, r);
        render(c, r, null);
        // Refresh every minute while photos are going out; otherwise the 30-minute system update.
        PendingIntent tick = broadcast(c, ACT_TICK, null, 5);
        if (r.kind == Report.Kind.SENDING) {
            alarms(c).set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + TICK_MS, tick);
        } else {
            alarms(c).cancel(tick);
        }
    }

    // --- drawing ------------------------------------------------------------------

    static void render(Context c, Report r, String busy) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        int[] ids = m.getAppWidgetIds(new ComponentName(c, SyncWidget.class));
        if (ids.length == 0) return;
        if (r == null) r = Report.of(Store.key(c).isEmpty() ? Report.Kind.SETUP : Report.Kind.STOPPED);
        for (int id : ids) m.updateAppWidget(id, build(c, r, busy, m.getAppWidgetOptions(id)));
    }

    private static RemoteViews build(Context c, Report r, String busy, Bundle options) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget);
        String mode = Store.mode(c);
        String pct = r.percent + "%";
        String left = Report.files(r.leftItems) + " · " + Report.bytes(r.leftBytes);
        String seen = Report.ago(r.lastSeen, System.currentTimeMillis());

        int ring;            // ring colour
        float fill;          // 0..1
        String center, title, sub, meta;
        int dot;
        boolean opensSetup = false;

        switch (r.kind) {
            case SENDING:
                ring = col(c, R.color.accent); fill = r.percent / 100f; center = pct;
                title = "Sending to " + r.peer;
                sub = left + " left";
                dot = col(c, R.color.green); meta = r.peer + " connected";
                break;
            case WAITING:
                ring = col(c, R.color.amber); fill = r.percent / 100f; center = pct;
                title = "Waiting for " + r.peer;
                sub = left + " to send";
                dot = col(c, R.color.muted); meta = r.peer + " offline · seen " + seen;
                break;
            case DONE:
                ring = col(c, R.color.green); fill = 1f; center = "✓";
                title = "All photos on " + r.peer;
                sub = r.deletes > 0
                        ? Report.num(r.deletes) + " deleted here, kept on " + r.peer
                        : Report.files(r.totalItems) + " synced";
                if (r.connected) {
                    dot = col(c, R.color.green); meta = r.peer + " connected";
                } else {
                    dot = col(c, R.color.muted); meta = "As of " + seen + " · " + r.peer + " offline";
                }
                break;
            case STOPPED: {
                ring = col(c, R.color.muted); fill = r.hasNumbers() ? r.percent / 100f : 0f;
                center = Store.OFF.equals(mode) ? "Off" : r.hasNumbers() ? pct : "–";
                String waiting = r.leftItems > 0 ? Report.files(r.leftItems) + " waiting" : null;
                if (Store.OFF.equals(mode)) {
                    title = "Sync is off";
                    sub = waiting != null ? waiting + " · switch on to send" : "Choose Auto or On to resume";
                } else if (Store.ON.equals(mode)) {
                    title = "Syncthing isn't running";
                    sub = "Open Syncthing, or choose On again";
                } else {
                    title = "Paused until home Wi-Fi";
                    sub = waiting != null ? waiting : r.hasNumbers() ? "All sent at last check" : "Syncs on home Wi-Fi";
                }
                dot = col(c, R.color.muted);
                meta = "Syncthing stopped";
                break;
            }
            case BLOCKED:
                ring = col(c, R.color.red); fill = r.percent / 100f; center = "!";
                title = r.peer + " isn't taking every folder";
                sub = "A folder is paused or not accepted there";
                dot = col(c, r.connected ? R.color.green : R.color.muted);
                meta = r.peer + (r.connected ? " connected" : " offline");
                break;
            case NO_SHARE:
                ring = col(c, R.color.amber); fill = 0f; center = "!";
                title = "Nothing shared yet";
                sub = "Share Camera with the Pixel in Syncthing";
                dot = col(c, R.color.muted); meta = "No folders shared";
                break;
            case BAD_KEY:
                ring = col(c, R.color.red); fill = 0f; center = "!";
                title = "API key rejected";
                sub = "Tap to paste it again";
                dot = col(c, R.color.red); meta = "Not connected";
                opensSetup = true;
                break;
            case NO_APP:
                ring = col(c, R.color.red); fill = 0f; center = "!";
                title = "Syncthing-Fork is missing";
                sub = "Install it, then tap here";
                dot = col(c, R.color.red); meta = "Not installed";
                opensSetup = true;
                break;
            case ERROR:
                ring = col(c, R.color.red); fill = 0f; center = "!";
                title = r.errTitle;
                sub = r.errDetail;
                dot = col(c, R.color.red); meta = "Tap to retry";
                break;
            case SETUP:
            default:
                ring = col(c, R.color.accent); fill = 0f; center = "→";
                title = "Set up Photo Sync";
                sub = "Tap to connect to Syncthing";
                dot = col(c, R.color.muted); meta = "Not set up";
                opensSetup = true;
        }

        if (busy != null) {
            meta = busy;
            dot = col(c, R.color.accent);
        } else if (r.at > 0 && !opensSetup) {
            meta += " · " + DateFormat.getTimeFormat(c).format(new Date(r.at));
        }

        float density = c.getResources().getDisplayMetrics().density;
        v.setImageViewBitmap(R.id.ring, ring(c.getResources().getDimension(R.dimen.ring), 4.5f * density,
                fill, ring, col(c, R.color.track)));
        v.setTextViewText(R.id.ring_text, center);
        v.setTextColor(R.id.ring_text, r.kind == Report.Kind.STOPPED ? col(c, R.color.text2) : ring);
        v.setTextViewTextSize(R.id.ring_text, TypedValue.COMPLEX_UNIT_SP, center.length() > 3 ? 12f : 14f);
        v.setTextViewText(R.id.title, title);
        v.setTextViewText(R.id.subtitle, sub);
        v.setTextViewText(R.id.meta, meta);
        v.setInt(R.id.dot, "setColorFilter", dot);

        // Folder rows when the widget is tall enough (height in dp; portrait uses MAX_HEIGHT)
        int h = options == null ? 0 : options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT);
        int room = h >= 250 ? 4 : h >= 200 ? 3 : h >= 170 ? 2 : 0;
        boolean showRows = room > 0 && !r.rows.isEmpty() && !opensSetup;
        v.setViewVisibility(R.id.rows, showRows ? View.VISIBLE : View.GONE);
        for (int i = 0; i < ROWS.length; i++) {
            boolean on = showRows && i < Math.min(room, r.rows.size());
            v.setViewVisibility(ROWS[i][0], on ? View.VISIBLE : View.GONE);
            if (!on) continue;
            Report.Row row = r.rows.get(i);
            boolean more = i == room - 1 && r.rows.size() > room;
            v.setTextViewText(ROWS[i][1], more ? "+" + (r.rows.size() - i) + " more folders" : row.name);
            v.setTextViewText(ROWS[i][2], more ? "" : ("ok".equals(row.tone) ? "✓ " : "") + row.value);
            v.setTextColor(ROWS[i][2], col(c, "ok".equals(row.tone) ? R.color.green
                    : "busy".equals(row.tone) ? R.color.accent
                    : "warn".equals(row.tone) ? R.color.amber : R.color.text3));
        }

        // Switch
        int[][] segs = {{R.id.seg_auto, 2}, {R.id.seg_on, 3}, {R.id.seg_off, 4}};
        String[] modes = {Store.AUTO, Store.ON, Store.OFF};
        for (int i = 0; i < segs.length; i++) {
            boolean sel = modes[i].equals(mode);
            v.setInt(segs[i][0], "setBackgroundResource", sel ? R.drawable.seg_selected : 0);
            v.setTextColor(segs[i][0], col(c, sel ? R.color.text1 : R.color.text2));
            v.setOnClickPendingIntent(segs[i][0], broadcast(c, ACT_MODE, modes[i], segs[i][1]));
        }

        PendingIntent setup = PendingIntent.getActivity(c, 10, new Intent(c, SetupActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        v.setOnClickPendingIntent(R.id.header, opensSetup ? setup : broadcast(c, ACT_REFRESH, null, 1));
        v.setOnClickPendingIntent(R.id.more, setup);
        return v;
    }

    private static Bitmap ring(float sizePx, float stroke, float fill, int color, int track) {
        int px = Math.max(1, Math.round(sizePx));
        Bitmap b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(stroke);
        p.setStrokeCap(Paint.Cap.ROUND);
        RectF o = new RectF(stroke / 2, stroke / 2, px - stroke / 2, px - stroke / 2);
        p.setColor(track);
        cv.drawOval(o, p);
        if (fill > 0) {
            p.setColor(color);
            float sweep = fill >= 1f ? 360f : Math.max(6f, 360f * fill);
            cv.drawArc(o, -90f, sweep, false, p);
        }
        return b;
    }

    // --- helpers ------------------------------------------------------------------

    private static int col(Context c, int res) {
        return c.getColor(res);
    }

    private static AlarmManager alarms(Context c) {
        return (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
    }

    private static PendingIntent broadcast(Context c, String action, String mode, int requestCode) {
        Intent i = new Intent(c, SyncWidget.class).setAction(action);
        if (mode != null) i.putExtra(EXTRA_MODE, mode);
        return PendingIntent.getBroadcast(c, requestCode, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
