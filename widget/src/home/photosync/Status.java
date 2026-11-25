package home.photosync;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Works out what is left to send, from Syncthing's view of what each peer (the Pixel) still needs.
 *
 * "Done" means nothing is left to transfer. Deletions the Pixel deliberately ignores
 * (ignoreDelete) are counted separately: Syncthing itself would show them as "95 %" forever.
 * Plain Java, no Android APIs.
 */
final class Status {
    private Status() {}

    static Report check(String key) {
        if (key == null || key.isEmpty()) return Report.of(Report.Kind.SETUP);
        Api api = new Api(key);
        int code = api.ping();
        if (code == Api.DOWN) return Report.of(Report.Kind.STOPPED);
        if (code == 401 || code == 403) return Report.of(Report.Kind.BAD_KEY);
        if (code != 200) return Report.error("Unexpected reply from Syncthing", "HTTP " + code + " · tap to retry");
        try {
            return compute(api);
        } catch (IOException | JSONException e) {
            return Report.error("Couldn't read the status", "Syncthing is busy · tap to retry");
        }
    }

    static Report compute(Api api) throws IOException, JSONException {
        Report r = Report.of(Report.Kind.DONE);
        String me = new JSONObject(api.get("/rest/system/status")).optString("myID", "");
        JSONArray devices = new JSONArray(api.get("/rest/config/devices"));
        JSONObject conns = new JSONObject(api.get("/rest/system/connections")).optJSONObject("connections");
        JSONObject stats = new JSONObject(api.get("/rest/stats/device"));
        JSONArray folders = new JSONArray(api.get("/rest/config/folders"));
        if (me.isEmpty()) throw new IOException("no device ID");

        Map<String, String> names = new HashMap<>();
        for (int i = 0; i < devices.length(); i++) {
            JSONObject d = devices.getJSONObject(i);
            String id = d.optString("deviceID");
            String name = d.optString("name", "");
            names.put(id, name.isEmpty() ? id.substring(0, Math.min(7, id.length())) : name);
        }

        Set<String> peers = new LinkedHashSet<>();
        boolean shared = false, blocked = false;

        for (int i = 0; i < folders.length(); i++) {
            JSONObject f = folders.getJSONObject(i);
            String id = f.getString("id");
            String label = f.optString("label", "");
            if (label.isEmpty()) label = id;
            if (f.optBoolean("paused")) {
                r.rows.add(new Report.Row(label, "Paused here", "muted"));
                continue;
            }
            List<String> others = new ArrayList<>();
            JSONArray fd = f.optJSONArray("devices");
            for (int j = 0; fd != null && j < fd.length(); j++) {
                String did = fd.getJSONObject(j).optString("deviceID");
                if (!did.equals(me)) others.add(did);
            }
            if (others.isEmpty()) {
                r.rows.add(new Report.Row(label, "Not shared", "warn"));
                continue;
            }
            for (String did : others) {
                shared = true;
                peers.add(did);
                String peerName = names.containsKey(did) ? names.get(did) : did;
                String tag = others.size() > 1 ? label + " → " + peerName : label;
                JSONObject c;
                try {
                    c = new JSONObject(api.get("/rest/db/completion?folder=" + enc(id) + "&device=" + enc(did)));
                } catch (IOException | JSONException e) {
                    r.rows.add(new Report.Row(tag, "Unavailable", "muted"));
                    continue;
                }
                long g = c.optLong("globalItems"), gb = c.optLong("globalBytes");
                long ni = c.optLong("needItems"), nb = c.optLong("needBytes"), nd = c.optLong("needDeletes");
                r.deletes += nd;
                switch (c.optString("remoteState", "unknown")) {
                    case "paused":
                        r.rows.add(new Report.Row(tag, "Paused on " + peerName, "warn"));
                        blocked = true;
                        break;
                    case "notSharing":
                        r.rows.add(new Report.Row(tag, "Not accepted on " + peerName, "warn"));
                        blocked = true;
                        break;
                    default:
                        r.totalItems += g;
                        r.totalBytes += gb;
                        if (ni > 0 || nb > 0) {
                            r.leftItems += ni;
                            r.leftBytes += nb;
                            r.rows.add(new Report.Row(tag, Report.num(ni) + " left", "busy"));
                        } else {
                            r.rows.add(new Report.Row(tag, Report.num(g), "ok"));
                        }
                }
            }
        }

        for (String did : peers) {
            JSONObject cn = conns == null ? null : conns.optJSONObject(did);
            if (cn != null && cn.optBoolean("connected")) r.connected = true;
            JSONObject st = stats.optJSONObject(did);
            if (st != null) r.lastSeen = Math.max(r.lastSeen, Report.parseTime(st.optString("lastSeen")));
        }
        if (peers.size() == 1) r.peer = names.getOrDefault(peers.iterator().next(), "Pixel");
        else if (peers.size() > 1) r.peer = peers.size() + " devices";
        if (r.connected) r.lastSeen = r.at;

        if (r.totalBytes > 0) {
            long sent = Math.max(0, r.totalBytes - r.leftBytes);
            r.percent = (int) Math.floor(100.0 * sent / r.totalBytes);
        } else if (r.totalItems > 0) {
            r.percent = (int) Math.floor(100.0 * Math.max(0, r.totalItems - r.leftItems) / r.totalItems);
        }

        if (!shared) {
            r.kind = Report.Kind.NO_SHARE;
        } else if (r.leftItems > 0 || r.leftBytes > 0) {
            r.kind = r.connected ? Report.Kind.SENDING : Report.Kind.WAITING;
            r.percent = Math.min(r.percent, 99);
        } else if (blocked) {
            r.kind = Report.Kind.BLOCKED;
        } else {
            r.kind = Report.Kind.DONE;
            r.percent = 100;
        }
        return r;
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new AssertionError(e);
        }
    }
}
