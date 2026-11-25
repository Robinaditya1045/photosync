package home.photosync;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

/** Settings, the last report, and talking to the Syncthing-Fork app. */
final class Store {
    static final String SYNCTHING = "com.github.catfriend1.syncthingfork";
    private static final String RECEIVER = "com.nutomic.syncthingandroid.receiver.AppConfigReceiver";

    static final String AUTO = "AUTO", ON = "ON", OFF = "OFF";

    private Store() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("photosync", Context.MODE_PRIVATE);
    }

    static String key(Context c) {
        return prefs(c).getString("apikey", "");
    }

    static void setKey(Context c, String key) {
        prefs(c).edit().putString("apikey", key).apply();
    }

    /** The switch position last chosen in the widget (Syncthing's own tile is not visible to us). */
    static String mode(Context c) {
        return prefs(c).getString("mode", AUTO);
    }

    static void setMode(Context c, String mode) {
        prefs(c).edit().putString("mode", mode).apply();
    }

    static Report last(Context c) {
        return Report.fromJson(prefs(c).getString("last", null));
    }

    static void saveLast(Context c, Report r) {
        prefs(c).edit().putString("last", r.toJson()).apply();
    }

    static boolean syncthingInstalled(Context c) {
        try {
            c.getPackageManager().getPackageInfo(SYNCTHING, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /**
     * Same switch as Syncthing's own quick-settings tile. Needs Syncthing > Settings >
     * Behaviour > "Service Control by Broadcast".
     */
    static void sendMode(Context c, String mode) {
        String action = ON.equals(mode) ? "START" : OFF.equals(mode) ? "STOP" : "FOLLOW";
        Intent i = new Intent(SYNCTHING + ".action." + action)
                .setComponent(new ComponentName(SYNCTHING, RECEIVER));
        c.sendBroadcast(i);
    }

    static Intent openSyncthing(Context c) {
        return c.getPackageManager().getLaunchIntentForPackage(SYNCTHING);
    }

    /** Full check, including the parts that need Android. Runs on a background thread. */
    static Report check(Context c) {
        if (!syncthingInstalled(c)) return Report.of(Report.Kind.NO_APP);
        Report r = Status.check(key(c));
        if (r.kind == Report.Kind.STOPPED) r.carryNumbersFrom(last(c));
        return r;
    }
}
