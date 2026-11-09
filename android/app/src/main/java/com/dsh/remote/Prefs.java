package com.dsh.remote;

import android.content.Context;
import android.content.SharedPreferences;

/** All user-facing options live here; SettingsActivity edits them, MainActivity applies them. */
public final class Prefs {

    public static final String SERVER_URL = "server_url";
    public static final String FONT_PCT = "font_pct";
    public static final String FULLSCREEN = "fullscreen";
    public static final String KEEP_SCREEN = "keep_screen";
    public static final String AUTO_SIDEBAR = "auto_sidebar";
    public static final String DESKTOP_UA = "desktop_ua";
    public static final String BACK_SUSPEND = "back_suspend";
    public static final String EXT_LINKS = "ext_links";
    public static final String AUTO_RECONNECT = "auto_reconnect";
    public static final String AUTO_LOAD = "auto_load";
    public static final String AUTO_DISCOVER = "auto_discover";
    public static final String DISCOVERED_URL = "discovered_url";
    public static final String TARGET_MODE = "target_mode";
    public static final String THEME = "theme";
    public static final String ORIENTATION = "orientation"; // smart|portrait|landscape|system
    public static final String SITES = "sites_json";        // [{name,url}...] multi-site aggregation
    public static final String ACTIVE_SITE = "active_site"; // index into sites; 0 = DSH workbench
    public static final String ZCODE_URL = "zcode_url";     // ZCode remote link (direct https, no tunnel)
    public static final String MODE = "app_mode";           // dsh | zcode
    public static final String SITE_POPUP = "site_popup";   // site mini-window switch (off by default)

    // Empty by default: rely on 万能抓取 (auto-discovery) after login, or set
    // your own workbench URL in 设置 → 目标服务器.
    public static final String DEFAULT_URL = "";

    private Prefs() {}

    public static SharedPreferences get(Context c) {
        return c.getSharedPreferences("dsh_prefs", Context.MODE_PRIVATE);
    }

    public static String serverUrl(Context c) {
        String v = get(c).getString(SERVER_URL, DEFAULT_URL);
        return (v == null || v.trim().isEmpty()) ? DEFAULT_URL : v.trim();
    }

    public static int fontPct(Context c) {
        return get(c).getInt(FONT_PCT, 100);
    }

    public static boolean get(Context c, String key, boolean def) {
        return get(c).getBoolean(key, def);
    }

    public static String hostOf(String url) {
        try {
            String host = new java.net.URI(url).getHost();
            return host == null ? "" : host;
        } catch (Exception e) {
            return url;
        }
    }

    /** auto mode: the discovered endpoint wins; manual mode: the stored URL. */
    public static String effectiveUrl(Context c) {
        SharedPreferences p = get(c);
        String mode = p.getString(TARGET_MODE, "auto");
        if ("manual".equals(mode)) return serverUrl(c);
        String found = p.getString(DISCOVERED_URL, "");
        return found.isEmpty() ? serverUrl(c) : found;
    }

    public static org.json.JSONArray getSites(Context c) {
        try {
            return new org.json.JSONArray(get(c).getString(SITES, "[]"));
        } catch (Exception e) {
            return new org.json.JSONArray();
        }
    }

    public static void saveSites(Context c, org.json.JSONArray arr) {
        get(c).edit().putString(SITES, arr.toString()).apply();
    }
}
