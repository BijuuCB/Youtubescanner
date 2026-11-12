package de.paul.youtubescanner;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.net.Uri;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Liest in freigegebenen Apps mit, was Paul tippt und was er öffnet:
 * - Claude, Notion, YouTube-Suche, Chrome-Suchfeld: getippter Text, sobald er
 *   abgeschickt/fertig ist (Feld geleert, App gewechselt oder 25 s Ruhe).
 * - Chrome: Google-Suchbegriffe und besuchte Seiten aus der Adresszeile.
 * - YouTube: Titel des gerade geöffneten Videos (Versuch, hängt vom YouTube-Layout ab).
 * Passwortfelder und lange Zahlenfolgen (Karten-/Kontonummern) werden nie gespeichert.
 */
public class ContentWatcher {

    public static final String CLAUDE = "com.anthropic.claude";
    public static final String CHROME = "com.android.chrome";
    public static final String NOTION = "notion.id";
    public static final String YT = ShortsService.YT;

    public static final String[][] APPS = {
            {CLAUDE, "Claude", "Deine Fragen an Claude"},
            {CHROME, "Chrome / Google", "Suchbegriffe und geöffnete Seiten"},
            {YT, "YouTube", "Videotitel und YouTube-Suchen"},
            {NOTION, "Notion", "Was du in Notion schreibst"},
    };

    private static final Pattern DIGITS = Pattern.compile("(?:\\d[ -]?){12,}");
    private static final long IDLE_COMMIT_MS = 25_000;
    private static final int MAX_LEN = 400;

    private static class Draft {
        String pkg;
        String text;
        long updated;
    }

    private final Context ctx;
    private final SharedPreferences prefs;
    private final Map<String, Draft> drafts = new HashMap<>();
    private String lastChromeUrl = "";
    private long lastChromeCheck = 0;
    private String lastYtTitle = "";
    private long lastYtCheck = 0;
    private String lastPkg = "";

    public ContentWatcher(Context ctx) {
        this.ctx = ctx;
        this.prefs = ctx.getSharedPreferences("content_settings", Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(Context ctx, String pkg) {
        SharedPreferences p = ctx.getSharedPreferences("content_settings", Context.MODE_PRIVATE);
        return p.getBoolean("on", true) && p.getBoolean("app_" + pkg, true);
    }

    private boolean watched(String pkg) {
        if (!prefs.getBoolean("on", true)) return false;
        for (String[] a : APPS) if (a[0].equals(pkg)) return prefs.getBoolean("app_" + pkg, true);
        return false;
    }

    /** Wird für jedes Bedienungshilfen-Ereignis aufgerufen. */
    public void onEvent(AccessibilityEvent e, String pkg, AccessibilityNodeInfoProvider rootProvider) {
        long now = System.currentTimeMillis();

        // App gewechselt → offene Entwürfe der vorherigen App festschreiben.
        if (e.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && !pkg.equals(lastPkg)
                && !pkg.equals("com.android.systemui") && !pkg.contains("inputmethod") && !pkg.contains("keyboard")) {
            commitAll(null);
            lastPkg = pkg;
        }
        commitIdle(now);

        if (!watched(pkg)) return;

        if (e.getEventType() == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            onTextChanged(e, pkg, now);
        }

        if (CHROME.equals(pkg) && now - lastChromeCheck > 1500) {
            lastChromeCheck = now;
            AccessibilityNodeInfo root = rootProvider.root();
            if (root != null) checkChrome(root);
        }
        if (YT.equals(pkg) && now - lastYtCheck > 2000
                && e.getEventType() != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            lastYtCheck = now;
            AccessibilityNodeInfo root = rootProvider.root();
            if (root != null) checkYouTubeTitle(root);
        }
    }

    public interface AccessibilityNodeInfoProvider {
        AccessibilityNodeInfo root();
    }

    // ------------------------------------------------------------ Tippen

    private void onTextChanged(AccessibilityEvent e, String pkg, long now) {
        if (e.isPassword()) return;
        AccessibilityNodeInfo src = e.getSource();
        if (src != null && src.isPassword()) return;
        String vid = src != null ? src.getViewIdResourceName() : null;

        StringBuilder sb = new StringBuilder();
        for (CharSequence cs : e.getText()) if (cs != null) sb.append(cs);
        String text = sb.toString().trim();

        // In der Chrome-Adresszeile zählt nur, was nach Suchbegriff aussieht.
        // Sobald dort nach Enter die URL erscheint, bleibt der Suchbegriff als Entwurf stehen.
        if (CHROME.equals(pkg) && vid != null && vid.endsWith("url_bar")
                && !text.isEmpty() && text.contains(".") && !text.contains(" ")) {
            return;
        }

        String key = pkg + "|" + (vid != null ? vid : "?");
        Draft d = drafts.get(key);
        if (d != null && d.text != null && !d.text.isEmpty()) {
            // Feld geleert oder stark gekürzt → vorheriger Text wurde abgeschickt.
            if (text.isEmpty() || text.length() < d.text.length() / 3) {
                commit(d);
                drafts.remove(key);
                d = null;
            }
        }
        if (text.isEmpty()) return;
        if (d == null) {
            d = new Draft();
            d.pkg = pkg;
            drafts.put(key, d);
        }
        d.text = text;
        d.updated = now;
    }

    private void commitIdle(long now) {
        Iterator<Map.Entry<String, Draft>> it = drafts.entrySet().iterator();
        while (it.hasNext()) {
            Draft d = it.next().getValue();
            if (now - d.updated > IDLE_COMMIT_MS) {
                commit(d);
                it.remove();
            }
        }
    }

    public void commitAll(String onlyPkg) {
        Iterator<Map.Entry<String, Draft>> it = drafts.entrySet().iterator();
        while (it.hasNext()) {
            Draft d = it.next().getValue();
            if (onlyPkg == null || onlyPkg.equals(d.pkg)) {
                commit(d);
                it.remove();
            }
        }
    }

    private void commit(Draft d) {
        if (d.text == null) return;
        String kind;
        switch (d.pkg) {
            case CLAUDE: kind = "Frage"; break;
            case CHROME: kind = "Suche"; break;
            case YT: kind = "YouTube-Suche"; break;
            case NOTION: kind = "Notiz"; break;
            default: kind = "Text";
        }
        save(d.pkg, kind, d.text);
    }

    private void save(String pkg, String kind, String text) {
        if (text == null) return;
        text = text.replaceAll("\\s+", " ").trim();
        if (text.length() < 3) return;
        if (DIGITS.matcher(text).find()) return; // mögliche Karten-/Kontonummer
        if (text.length() > MAX_LEN) text = text.substring(0, MAX_LEN) + " …";
        ContentDb.get(ctx).add(pkg, kind, text);
    }

    // ------------------------------------------------------------ Chrome

    private void checkChrome(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> bars = root.findAccessibilityNodeInfosByViewId(CHROME + ":id/url_bar");
        if (bars == null || bars.isEmpty()) return;
        AccessibilityNodeInfo bar = bars.get(0);
        if (bar.isFocused()) return; // Paul tippt gerade – erst die fertige Seite zählt
        CharSequence cs = bar.getText();
        if (cs == null) return;
        String url = cs.toString().trim();
        if (url.isEmpty() || url.equals(lastChromeUrl)) return;
        lastChromeUrl = url;

        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.startsWith("chrome://") || lower.contains("bank") || lower.contains("paypal")
                || lower.contains("login") || lower.contains("signin") || lower.contains("account")) {
            return;
        }
        // Chrome zeigt bei Google-Suchen oft direkt den Suchbegriff statt der URL.
        if (!url.contains(".") || url.contains(" ")) {
            if (!url.contains("/")) {
                save(CHROME, "Suche", url);
                return;
            }
        }
        try {
            Uri u = Uri.parse(url.startsWith("http") ? url : "https://" + url);
            String host = u.getHost() == null ? "" : u.getHost().replaceFirst("^www\\.", "");
            if (host.startsWith("google.") || host.contains(".google.")) {
                String q = u.getQueryParameter("q");
                if (q != null && !q.isEmpty()) save(CHROME, "Suche", q);
                return;
            }
            String path = u.getPath() == null ? "" : u.getPath();
            if (path.length() > 80) path = path.substring(0, 80) + "…";
            save(CHROME, "Seite", host + path);
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------ YouTube

    private static final String[] YT_WATCH_IDS = {"watch_player", "watch_list", "watch_while_layout"};
    private static final String[] YT_VIDEO_TITLE_IDS = {"title", "video_title", "watch_title"};
    private static final String[] YT_SHORT_TITLE_IDS = {"reel_main_title", "reel_multi_format_title"};

    private boolean anyVisible(AccessibilityNodeInfo root, String[] ids) {
        for (String id : ids) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(YT + ":id/" + id);
            if (nodes == null) continue;
            for (AccessibilityNodeInfo n : nodes) if (n != null && n.isVisibleToUser()) return true;
        }
        return false;
    }

    private void checkYouTubeTitle(AccessibilityNodeInfo root) {
        // Nur Titel speichern, wenn wirklich ein Video bzw. Short geöffnet ist –
        // nicht beim Durchscrollen der Startseite.
        String[] ids;
        if (anyVisible(root, new String[]{"reel_recycler", "reel_player_page_container"})) {
            ids = YT_SHORT_TITLE_IDS;
        } else if (anyVisible(root, YT_WATCH_IDS)) {
            ids = YT_VIDEO_TITLE_IDS;
        } else {
            return;
        }
        for (String id : ids) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(YT + ":id/" + id);
            if (nodes == null) continue;
            for (AccessibilityNodeInfo n : nodes) {
                if (n == null || !n.isVisibleToUser()) continue;
                CharSequence cs = n.getText() != null ? n.getText() : n.getContentDescription();
                if (cs == null) continue;
                String t = cs.toString().trim();
                if (t.length() < 8) continue;
                // Nur ein Titel im oberen Bereich unter dem Player, nicht Vorschläge im Feed.
                Rect r = new Rect();
                n.getBoundsInScreen(r);
                int h = ctx.getResources().getDisplayMetrics().heightPixels;
                if (r.top > h * 0.7) continue;
                if (!t.equals(lastYtTitle)) {
                    lastYtTitle = t;
                    save(YT, id.startsWith("reel") ? "Short" : "Video", t);
                }
                return;
            }
        }
    }
}
