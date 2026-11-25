package home.photosync;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** What the widget shows: one snapshot of "what is left to send to the Pixel". Plain Java. */
final class Report {
    enum Kind {
        SETUP,     // no API key yet
        NO_APP,    // Syncthing-Fork is not installed
        BAD_KEY,   // Syncthing rejected the API key
        STOPPED,   // Syncthing is not running (Off, or Auto away from home Wi-Fi)
        SENDING,   // files left, Pixel connected
        WAITING,   // files left, Pixel not connected
        DONE,      // nothing left to send
        BLOCKED,   // a folder is paused or not accepted on the Pixel
        NO_SHARE,  // nothing is shared with another device
        ERROR      // anything else; see errTitle / errDetail
    }

    static final class Row {
        final String name, value, tone; // tone: ok | busy | warn | muted

        Row(String name, String value, String tone) {
            this.name = name;
            this.value = value;
            this.tone = tone;
        }
    }

    Kind kind = Kind.SETUP;
    String peer = "Pixel";
    boolean connected;
    long lastSeen;        // epoch ms the Pixel was last connected, 0 = unknown
    long leftItems, leftBytes, totalItems, totalBytes, deletes;
    int percent;          // 0..100, by bytes
    boolean stale;        // numbers are from the last time Syncthing answered
    long at;              // when this was checked, epoch ms
    String errTitle = "", errDetail = "";
    final List<Row> rows = new ArrayList<>();

    static Report of(Kind kind) {
        Report r = new Report();
        r.kind = kind;
        r.at = System.currentTimeMillis();
        return r;
    }

    static Report error(String title, String detail) {
        Report r = of(Kind.ERROR);
        r.errTitle = title;
        r.errDetail = detail;
        return r;
    }

    boolean hasNumbers() {
        return totalItems > 0 || leftItems > 0;
    }

    /** Keeps the last known numbers visible while Syncthing is not running. */
    void carryNumbersFrom(Report prev) {
        if (prev == null || !prev.hasNumbers()) return;
        peer = prev.peer;
        lastSeen = prev.lastSeen;
        leftItems = prev.leftItems;
        leftBytes = prev.leftBytes;
        totalItems = prev.totalItems;
        totalBytes = prev.totalBytes;
        deletes = prev.deletes;
        percent = prev.percent;
        rows.clear();
        rows.addAll(prev.rows);
        stale = true;
    }

    // --- formatting -------------------------------------------------------------

    static String num(long n) {
        return String.format(Locale.US, "%,d", n);
    }

    static String files(long n) {
        return num(n) + (n == 1 ? " file" : " files");
    }

    static String bytes(long b) {
        String[] u = {"B", "KB", "MB", "GB", "TB"};
        double v = b;
        int i = 0;
        while (v >= 1024 && i < u.length - 1) {
            v /= 1024;
            i++;
        }
        return i == 0 ? String.format(Locale.US, "%d %s", b, u[0]) : String.format(Locale.US, "%.1f %s", v, u[i]);
    }

    static String ago(long epochMs, long now) {
        if (epochMs <= 86_400_000L) return "never";
        long d = Math.max(0, (now - epochMs) / 1000);
        if (d < 90) return "just now";
        if (d < 5400) return (d / 60) + " min ago";
        if (d < 172_800) return (d / 3600) + " h ago";
        return (d / 86400) + " days ago";
    }

    /** Syncthing timestamps: 2026-11-24T10:12:13.123456789+05:30 (or Z). Returns 0 if unparseable. */
    static long parseTime(String s) {
        if (s == null || s.isEmpty()) return 0;
        String t = s.replaceFirst("\\.\\d+", "").replaceFirst("Z$", "+0000").replaceFirst("([+-]\\d\\d):(\\d\\d)$", "$1$2");
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US);
            return f.parse(t).getTime();
        } catch (ParseException e) {
            return 0;
        }
    }

    // --- persistence (last report is cached so the widget can redraw instantly) ----

    String toJson() {
        try {
            JSONObject o = new JSONObject()
                    .put("kind", kind.name()).put("peer", peer).put("connected", connected)
                    .put("lastSeen", lastSeen).put("leftItems", leftItems).put("leftBytes", leftBytes)
                    .put("totalItems", totalItems).put("totalBytes", totalBytes).put("deletes", deletes)
                    .put("percent", percent).put("stale", stale).put("at", at)
                    .put("errTitle", errTitle).put("errDetail", errDetail);
            JSONArray a = new JSONArray();
            for (Row row : rows) a.put(new JSONArray().put(row.name).put(row.value).put(row.tone));
            return o.put("rows", a).toString();
        } catch (JSONException e) {
            return "{}";
        }
    }

    static Report fromJson(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            JSONObject o = new JSONObject(s);
            Report r = new Report();
            r.kind = Kind.valueOf(o.getString("kind"));
            r.peer = o.optString("peer", "Pixel");
            r.connected = o.optBoolean("connected");
            r.lastSeen = o.optLong("lastSeen");
            r.leftItems = o.optLong("leftItems");
            r.leftBytes = o.optLong("leftBytes");
            r.totalItems = o.optLong("totalItems");
            r.totalBytes = o.optLong("totalBytes");
            r.deletes = o.optLong("deletes");
            r.percent = o.optInt("percent");
            r.stale = o.optBoolean("stale");
            r.at = o.optLong("at");
            r.errTitle = o.optString("errTitle");
            r.errDetail = o.optString("errDetail");
            JSONArray a = o.optJSONArray("rows");
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONArray x = a.getJSONArray(i);
                r.rows.add(new Row(x.getString(0), x.getString(1), x.getString(2)));
            }
            return r;
        } catch (JSONException | IllegalArgumentException e) {
            return null;
        }
    }
}
