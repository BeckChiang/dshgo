package com.dsh.remote;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Deep-integrated Tailscale engine wrapper (libtailscale). One process, one
 * engine: the tunnel lives inside this app, no separate VPN app is involved.
 * Everything is driven through Tailscale's localapi so login, status, connect
 * and discovery all stay inside our own UI.
 */
public final class Tunnel {

    private static final String TAG = "DSHTunnel";
    private static Tunnel instance;

    private libtailscale.Application app;
    private final Context ctx;
    private libtailscale.NotificationManager watch;
    private String lastNotify = "";
    private String browseToURL = "";

    public interface Listener {
        void onStateChanged(String backendState, String detail);
    }

    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    public static synchronized Tunnel get(Context c) {
        if (instance == null) instance = new Tunnel(c.getApplicationContext());
        return instance;
    }

    private Tunnel(Context c) {
        this.ctx = c;
    }

    /** Boot the in-process engine exactly once. */
    public synchronized void ensureEngine() {
        if (app != null) return;
        try {
            app = LibtailscaleHolder.start(ctx);
            com.dshgo.app.data.EventLog.event(ctx, "engine", "started");
        } catch (Throwable t) {
            com.dshgo.app.data.EventLog.event(ctx, "engine", "start threw "
                + t.getClass().getSimpleName() + ": " + (t.getMessage() == null ? "" : t.getMessage()));
            throw t;
        }
        startWatching();
        // Tell the backend the frontend is here; harmless if already started.
        try {
            call("POST", "start", "{\"FrontendLogID\":\"dsh-remote\"}");
        } catch (Exception e) {
            Log.w(TAG, "start: " + e.getMessage());
        }
    }

    public boolean isEngineUp() {
        return app != null;
    }

    // ------------------------------------------------------- localapi

    public String call(String method, String path, String body) throws Exception {
        return call(method, path, body, 15000);
    }

    public String call(String method, String path, String body, int timeoutMs) throws Exception {
        ensureEngine();
        libtailscale.InputStream in = null;
        if (body != null) {
            final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            in = new libtailscale.InputStream() {
                private boolean done;
                @Override public byte[] read() throws Exception {
                    if (done) return null;
                    done = true;
                    return bytes;
                }
                @Override public void close() {}
            };
        }
        libtailscale.LocalAPIResponse resp = app.callLocalAPI(timeoutMs, method, "/localapi/v0/" + path, in);
        byte[] b = resp.bodyBytes();
        String s = b == null ? "" : new String(b, StandardCharsets.UTF_8);
        long code = resp.statusCode();
        if (code >= 400) throw new Exception("HTTP " + code + ": " + s);
        return s;
    }

    // ---------------------------------------------------------- state

    // Cached status: LocalAPI round-trips can stall for seconds on a weak
    // network, and poll() runs on the UI thread — never block it. A background
    // refresh keeps the cache warm; readers use the last snapshot and ask for
    // a fresh one only when the snapshot is stale.
    private volatile JSONObject statusCache = new JSONObject();
    private volatile long statusAt = 0;
    private volatile boolean statusBusy = false;
    private volatile boolean statusEverOk = false;

    public JSONObject status() {
        long now = System.currentTimeMillis();
        if (now - statusAt > 4000 && !statusBusy) {
            statusBusy = true;
            final Tunnel self = this;
            new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        JSONObject o = new JSONObject(self.call("GET", "status", null, 6000));
                        self.statusCache = o;
                        self.statusEverOk = true;
                    } catch (Exception e) {
                        // keep the last snapshot; a stale picture beats a blank one
                    }
                    self.statusAt = System.currentTimeMillis();
                    self.statusBusy = false;
                }
            }, "ts-status").start();
        }
        return statusCache;
    }

    /** Kick a refresh now (e.g. right after login) so the next read is fresh. */
    public void refreshStatus() {
        statusAt = 0;
        status();
    }

    public String backendState() {
        return optString(status(), "BackendState");
    }

    public boolean isRunning() {
        return "Running".equals(backendState());
    }

    /**
     * True only when the engine positively says we have a session. A failed or
     * not-yet-completed status query must NOT read as "logged out" — on weak
     * networks that mislabelled every stall as "待登录".
     */
    public boolean isLoggedIn() {
        JSONObject st = status();
        String state = optString(st, "BackendState");
        if ("Running".equals(state) || "Starting".equals(state)) return true;
        JSONObject self = st.optJSONObject("Self");
        if (self != null) {
            String user = optString(self, "UserID");
            return user != null && !user.isEmpty() && !"0".equals(user);
        }
        return false;
    }

    /** True when the engine explicitly reports no profile (real login need). */
    private volatile long noStateSince = 0;

    public boolean needsLogin() {
        if (!statusEverOk) return false; // never judged from a blank snapshot
        String state = optString(status(), "BackendState");
        if ("NeedsLogin".equals(state)) return true;
        if ("NoState".equals(state)) {
            // "NoState" flashes on cold start before the stored profile loads;
            // only trust it after it has persisted for a while.
            long now = System.currentTimeMillis();
            if (noStateSince == 0) noStateSince = now;
            return now - noStateSince > 8000;
        }
        noStateSince = 0;
        return false;
    }

    public boolean statusEverOk() {
        return statusEverOk;
    }

    public void setWantRunning(boolean want) {
        try {
            call("PATCH", "prefs",
                "{\"WantRunningSet\":true,\"WantRunning\":" + want
                + ",\"CorpDNSSet\":true,\"CorpDNS\":true}");
        } catch (Exception e) {
            Log.w(TAG, "setWantRunning: " + e.getMessage());
        }
        fire();
    }

    /** Kick interactive login; the auth URL arrives via notifications. */
    public String startLogin() {
        browseToURL = "";
        try {
            String r = call("POST", "login-interactive", "");
            if (r != null && r.contains("http")) {
                int i = r.indexOf("https://");
                if (i < 0) i = r.indexOf("http://");
                if (i >= 0) {
                    int j = i;
                    while (j < r.length() && "\"\\ \n\r\t".indexOf(r.charAt(j)) < 0) j++;
                    browseToURL = r.substring(i, j);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "login-interactive: " + e.getMessage());
        }
        return browseToURL;
    }

    public String browseURL() {
        return browseToURL;
    }

    /** Fallback login URL source: the status payload carries AuthURL too. */
    public String authURL() {
        String u = optString(status(), "AuthURL");
        return u == null ? "" : u;
    }

    public void logout() {
        try {
            call("POST", "logout", "");
        } catch (Exception e) {
            Log.w(TAG, "logout: " + e.getMessage());
        }
        fire();
    }

    // -------------------------------------------------- notifications

    private void startWatching() {
        try {
            watch = app.watchNotifications(0, new libtailscale.NotificationCallback() {
                @Override public void onNotify(byte[] json) throws Exception {
                    if (json != null) {
                        String s = new String(json, StandardCharsets.UTF_8);
                        lastNotify = s;
                        Log.i(TAG, "notify: " + (s.length() > 160 ? s.substring(0, 160) : s));
                        try {
                            JSONObject n = new JSONObject(s);
                            String url = n.optString("BrowseToURL", "");
                            if (!url.isEmpty()) browseToURL = url;
                        } catch (Exception ignored) {}
                        fire();
                    }
                }
            });
        } catch (Exception e) {
            Log.w(TAG, "watch: " + e.getMessage());
        }
    }

    public String lastNotify() {
        return lastNotify;
    }

    public void addListener(Listener l) {
        listeners.add(l);
    }

    private void fire() {
        final String state = backendState();
        for (final Listener l : listeners) {
            try {
                l.onStateChanged(state, browseToURL);
            } catch (Exception ignored) {}
        }
    }

    // ------------------------------------------------------- helpers

    public static String optString(JSONObject o, String k) {
        try {
            String v = o.optString(k, "");
            return "null".equals(v) ? "" : v;
        } catch (Exception e) {
            return "";
        }
    }

    /** Peer list for universal endpoint discovery. */
    public JSONArray peers() {
        JSONArray out = new JSONArray();
        try {
            JSONObject st = status();
            JSONObject peers = st.optJSONObject("Peer");
            if (peers != null) {
                JSONArray names = peers.names();
                if (names != null) {
                    for (int i = 0; i < names.length(); i++) {
                        JSONObject p = peers.optJSONObject(names.optString(i));
                        if (p != null) out.put(p);
                    }
                }
            }
        } catch (Exception ignored) {}
        return out;
    }

    /** gomobile binding glue: AppContext implementation lives in SecureStore. */
    static final class LibtailscaleHolder {
        static libtailscale.Application start(Context ctx) {
            String dataDir = ctx.getFilesDir().getAbsolutePath();
            // 状态目录：以前这里传空串，升级/重装后登录态容易丢（引擎没有稳定的
            // 落盘位置）。显式给一个应用私有子目录，卸载才会清。
            java.io.File stateDir = new java.io.File(ctx.getFilesDir(), "ts-state");
            if (!stateDir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                stateDir.mkdirs();
            }
            com.dshgo.app.data.EventLog.event(ctx, "engine",
                "start dataDir=" + dataDir + " stateDir=" + stateDir.getAbsolutePath());
            return libtailscale.Libtailscale.start(
                dataDir, stateDir.getAbsolutePath(), false, new SecureStore(ctx));
        }
    }
}
