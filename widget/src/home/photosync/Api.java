package home.photosync;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/** Minimal client for the Syncthing REST API running on this phone. Plain Java, no Android APIs. */
final class Api {
    /** {@link #ping()} result when nothing answers: Syncthing is not running. */
    static final int DOWN = 0;

    // Syncthing-Fork serves its GUI over HTTPS with a self-signed certificate by default.
    // Overridable for tests.
    static String host = "127.0.0.1:8384";
    private static volatile String scheme = "https";
    private static SSLSocketFactory trustSelfSigned;

    private final String key;

    Api(String key) {
        this.key = key;
    }

    /** HTTP status of /rest/system/ping (200 ok, 401/403 bad key), or {@link #DOWN}. */
    int ping() {
        try {
            return request("/rest/system/ping", 3000).getResponseCode();
        } catch (IOException e) {
            return DOWN;
        }
    }

    /** GET a JSON endpoint; throws unless the answer is 200. */
    String get(String path) throws IOException {
        HttpURLConnection c = request(path, 8000);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code + " for " + path);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            c.disconnect();
        }
    }

    /** Opens a connection, falling back to plain HTTP if the GUI doesn't speak TLS. */
    private HttpURLConnection request(String path, int timeoutMs) throws IOException {
        try {
            return connect(scheme, path, timeoutMs);
        } catch (SSLException e) {
            if (!scheme.equals("https")) throw e;
            HttpURLConnection c = connect("http", path, timeoutMs);
            scheme = "http";
            return c;
        }
    }

    private HttpURLConnection connect(String sch, String path, int timeoutMs) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(sch + "://" + host + path).openConnection();
        if (c instanceof HttpsURLConnection) {
            HttpsURLConnection s = (HttpsURLConnection) c;
            s.setSSLSocketFactory(trustSelfSigned());
            // Only ever talks to this phone, so Syncthing's own self-signed certificate is fine.
            s.setHostnameVerifier((hostname, session) -> "127.0.0.1".equals(hostname));
        }
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs);
        c.setUseCaches(false);
        c.setRequestProperty("X-API-Key", key);
        c.getResponseCode(); // connect now, so TLS problems surface here
        return c;
    }

    private static synchronized SSLSocketFactory trustSelfSigned() throws IOException {
        if (trustSelfSigned == null) {
            try {
                TrustManager[] all = {new X509TrustManager() {
                    @Override public void checkClientTrusted(X509Certificate[] chain, String type) {}
                    @Override public void checkServerTrusted(X509Certificate[] chain, String type) {}
                    @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                }};
                SSLContext ctx = SSLContext.getInstance("TLS");
                ctx.init(null, all, null);
                trustSelfSigned = ctx.getSocketFactory();
            } catch (GeneralSecurityException e) {
                throw new IOException(e);
            }
        }
        return trustSelfSigned;
    }
}
