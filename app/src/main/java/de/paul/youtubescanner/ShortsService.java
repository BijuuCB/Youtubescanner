package de.paul.youtubescanner;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.util.DisplayMetrics;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * Erkennt über die Bedienungshilfen, ob in der YouTube-App gerade der
 * Shorts-Player auf dem Bildschirm ist. Gespeichert werden nur Start- und
 * Endzeit und die Zahl der Wischer – keine Inhalte.
 *
 * Version 2: Nur noch eindeutige Shorts-Player-IDs zählen (kein "reel"-Rückfall
 * mehr, der normale Videos fälschlich erkannt hat). Wischer zählen nur noch,
 * wenn der Shorts-Feed selbst scrollt. Dazu ein Diagnose-Protokoll mit den
 * View-IDs (nur Namen, kein Text), um die Erkennung anpassen zu können.
 */
public class ShortsService extends AccessibilityService {

    static final String YT = "com.google.android.youtube";

    /** Der vertikale Shorts-Feed. Nur diese IDs gelten als "Shorts offen". */
    private static final String[] SHORTS_IDS = {
            "reel_recycler",
            "reel_player_page_container",
            "reel_watch_player"
    };

    private static final long CHECK_INTERVAL_MS = 700;
    private static final long MIN_SESSION_MS = 3000;
    private static final long SWIPE_DEBOUNCE_MS = 1200;
    private static final long DIAG_INTERVAL_MS = 4000;
    private static final int DIAG_MAX_LINES = 25;

    private long sessionStart = 0;
    private long lastSeen = 0;
    private long lastCheck = 0;
    private long lastPersist = 0;
    private long lastSwipe = 0;
    private long lastDiag = 0;
    private int swipes = 0;          // Rückfall: Scroll-Signale des Shorts-Feeds
    private int fpVideos = 0;        // Hauptweg: Zahl verschiedener Shorts (über Kanal/Titel erkannt)
    private String fpCurrent = "";
    private String fpCandidate = "";
    private long fpCandidateSince = 0;
    private static final long FP_STABLE_MS = 800;

    private SharedPreferences prefs;
    private BroadcastReceiver screenOffReceiver;
    private ContentWatcher content;
    private final SimpleDateFormat diagFmt = new SimpleDateFormat("dd.MM. HH:mm:ss", Locale.GERMANY);

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        prefs = getSharedPreferences("pending", MODE_PRIVATE);
        recoverPendingSession();
        content = new ContentWatcher(this);
        try {
            ContentDb.get(this).purgeOld();
        } catch (Exception ignored) {
        }

        screenOffReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                endSession(System.currentTimeMillis());
                if (content != null) content.commitAll(null);
            }
        };
        registerReceiver(screenOffReceiver, new IntentFilter(Intent.ACTION_SCREEN_OFF));
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        CharSequence pkgCs = e.getPackageName();
        if (pkgCs == null) return;
        String pkg = pkgCs.toString();
        long now = System.currentTimeMillis();
        int type = e.getEventType();

        try {
            if (content != null) content.onEvent(e, pkg, this::getRootInActiveWindow);
        } catch (Exception ignored) {
            // Mitlesen darf die Shorts-Erkennung nie stören.
        }

        if (!YT.equals(pkg)) {
            // Eine andere App (oder der Startbildschirm) ist in den Vordergrund gekommen.
            // Benachrichtigungsleiste und Tastatur beenden die Session nicht.
            if (sessionStart > 0
                    && type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    && !pkg.equals("com.android.systemui")
                    && !pkg.contains("inputmethod")
                    && !pkg.contains("keyboard")
                    && !pkg.equals(getPackageName())) {
                endSession(now);
            }
            return;
        }

        // Wischer nur zählen, wenn der Shorts-Feed selbst scrollt
        // (nicht Kommentare, Startseite oder Videobeschreibung).
        if (sessionStart > 0 && type == AccessibilityEvent.TYPE_VIEW_SCROLLED
                && isFromShortsFeed(e) && now - lastSwipe > SWIPE_DEBOUNCE_MS) {
            swipes++;
            lastSwipe = now;
        }
        if (sessionStart > 0 && now - lastPersist > 5000) persistPending();

        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && now - lastCheck < CHECK_INTERVAL_MS) {
            return;
        }
        lastCheck = now;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        if (root.getPackageName() == null || !YT.contentEquals(root.getPackageName())) return;

        boolean inShorts = isShortsVisible(root);
        if (inShorts) {
            if (sessionStart == 0) startSession(now);
            lastSeen = now;
            trackVideoChange(root, now);
        } else if (sessionStart > 0) {
            endSession(now);
        }

        if (now - lastDiag > DIAG_INTERVAL_MS) {
            lastDiag = now;
            writeDiagnostics(root, inShorts, now);
        }
    }

    /**
     * Erkennt ein neues Short daran, dass sich Kanalname/Titel des gerade
     * angezeigten Shorts ändern. Gezählt wird erst, wenn das neue Short
     * mindestens 0,8 s stabil zu sehen ist – halbe Wischer zählen nicht.
     * Die Texte selbst werden nicht gespeichert, nur verglichen.
     */
    private void trackVideoChange(AccessibilityNodeInfo root, long now) {
        String fp = fingerprint(root);
        if (fp.isEmpty()) return;
        if (fp.equals(fpCurrent)) {
            fpCandidate = "";
            return;
        }
        if (!fp.equals(fpCandidate)) {
            fpCandidate = fp;
            fpCandidateSince = now;
            if (fpCurrent.isEmpty()) { // allererstes Short der Session sofort zählen
                fpCurrent = fp;
                fpVideos = 1;
                fpCandidate = "";
            }
            return;
        }
        if (now - fpCandidateSince >= FP_STABLE_MS) {
            fpCurrent = fp;
            fpVideos++;
            fpCandidate = "";
        }
    }

    private static final java.util.regex.Pattern COUNTER =
            java.util.regex.Pattern.compile("(?s).*\\d.*");

    /** Texte des Shorts, das gerade (fast) den ganzen Bildschirm füllt. */
    private String fingerprint(AccessibilityNodeInfo root) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        AccessibilityNodeInfo page = null;
        long best = 0;
        Rect r = new Rect();
        for (String id : new String[]{"reel_player_page_container", "reel_watch_player"}) {
            List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByViewId(YT + ":id/" + id);
            if (found == null) continue;
            for (AccessibilityNodeInfo n : found) {
                if (n == null || !n.isVisibleToUser()) continue;
                n.getBoundsInScreen(r);
                int top = Math.max(r.top, 0), bottom = Math.min(r.bottom, dm.heightPixels);
                long visible = (long) Math.max(0, bottom - top) * r.width();
                if (visible > best) { best = visible; page = n; }
            }
            if (page != null) break;
        }
        if (page == null) page = root;

        TreeSet<String> texts = new TreeSet<>();
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(page);
        int visited = 0;
        while (!q.isEmpty() && visited < 400) {
            AccessibilityNodeInfo n = q.poll();
            visited++;
            if (n == null) continue;
            if (n.isVisibleToUser()) {
                // Nur sichtbarer Text (Kanal, Titel), keine Button-Beschreibungen wie
                // "Pausieren", die sich beim Antippen ändern.
                CharSequence cs = n.getText();
                if (cs != null) {
                    String t = cs.toString().trim();
                    // Alles mit Ziffern (Likes, Kommentare, Abspielzeit) ändert sich – nicht verwenden.
                    if (t.length() >= 4 && !COUNTER.matcher(t).matches()) {
                        texts.add(t.length() > 80 ? t.substring(0, 80) : t);
                    }
                }
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) q.add(c);
            }
        }
        lastFpTextCount = texts.size();
        if (texts.isEmpty()) return "";
        return Integer.toHexString(texts.toString().hashCode());
    }

    private int lastFpTextCount = 0;

    /** Beste Schätzung der Shorts in der laufenden Session. */
    private int videoCount() {
        return fpVideos > 0 ? fpVideos : swipes + 1;
    }

    private boolean isFromShortsFeed(AccessibilityEvent e) {
        AccessibilityNodeInfo src = e.getSource();
        if (src == null) return false;
        String id = src.getViewIdResourceName();
        return id != null && id.endsWith(":id/reel_recycler");
    }

    private boolean isShortsVisible(AccessibilityNodeInfo root) {
        for (String id : SHORTS_IDS) {
            List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByViewId(YT + ":id/" + id);
            if (found != null) {
                for (AccessibilityNodeInfo n : found) {
                    if (n != null && n.isVisibleToUser() && isLarge(n)) return true;
                }
            }
        }
        return false;
    }

    /** Ein Shorts-Player füllt fast den ganzen Bildschirm – kleine Vorschaukacheln im Feed zählen nicht. */
    private boolean isLarge(AccessibilityNodeInfo n) {
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        return r.height() > dm.heightPixels * 0.6 && r.width() > dm.widthPixels * 0.8;
    }

    /**
     * Schreibt die Namen der sichtbaren View-IDs auf (nur IDs, kein Text),
     * damit sich die Erkennung an Pauls YouTube-Version anpassen lässt.
     */
    private void writeDiagnostics(AccessibilityNodeInfo root, boolean inShorts, long now) {
        TreeSet<String> big = new TreeSet<>();
        TreeSet<String> keywords = new TreeSet<>();
        DisplayMetrics dm = getResources().getDisplayMetrics();
        long screenArea = (long) dm.widthPixels * dm.heightPixels;
        Rect r = new Rect();

        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;
        while (!queue.isEmpty() && visited < 800) {
            AccessibilityNodeInfo n = queue.poll();
            visited++;
            if (n == null) continue;
            String vid = n.getViewIdResourceName();
            if (vid != null && n.isVisibleToUser()) {
                String shortId = vid.substring(vid.indexOf('/') + 1);
                n.getBoundsInScreen(r);
                long area = (long) r.width() * r.height();
                if (area > screenArea * 0.4) big.add(shortId);
                String low = shortId.toLowerCase(Locale.ROOT);
                if (low.contains("reel") || low.contains("short") || low.contains("player") || low.contains("watch")) {
                    keywords.add(shortId);
                }
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo child = n.getChild(i);
                if (child != null) queue.add(child);
            }
        }

        String line = diagFmt.format(new Date(now)) + " " + (inShorts ? "[SHORTS videos=" + fpVideos + " scroll=" + swipes + " texte=" + lastFpTextCount + "]" : "[-]")
                + " groß=" + big + " stichwort=" + keywords;
        SharedPreferences d = getSharedPreferences("diag", MODE_PRIVATE);
        String old = d.getString("log", "");
        // Gleiche Zeile wie zuletzt nicht doppelt speichern
        String lastBody = d.getString("lastBody", "");
        String body = (inShorts ? "S" + fpVideos + "/" + swipes : "-") + big + keywords;
        if (body.equals(lastBody)) return;
        String[] lines = old.isEmpty() ? new String[0] : old.split("\n");
        StringBuilder sb = new StringBuilder(line);
        for (int i = 0; i < Math.min(lines.length, DIAG_MAX_LINES - 1); i++) sb.append('\n').append(lines[i]);
        d.edit().putString("log", sb.toString()).putString("lastBody", body).apply();
    }

    private void startSession(long now) {
        sessionStart = now;
        lastSeen = now;
        swipes = 0;
        fpVideos = 0;
        fpCurrent = "";
        fpCandidate = "";
        lastSwipe = now;
        persistPending();
    }

    private void endSession(long now) {
        if (sessionStart == 0) return;
        if (now - sessionStart >= MIN_SESSION_MS) {
            ShortsDb.get(this).insert(sessionStart, now, videoCount());
        }
        sessionStart = 0;
        swipes = 0;
        fpVideos = 0;
        fpCurrent = "";
        clearPending();
    }

    private void persistPending() {
        if (prefs == null) return;
        lastPersist = System.currentTimeMillis();
        prefs.edit()
                .putLong("start", sessionStart)
                .putLong("lastSeen", lastSeen)
                .putInt("videos", videoCount())
                .apply();
    }

    private void clearPending() {
        if (prefs != null) prefs.edit().remove("start").remove("lastSeen").remove("videos").apply();
    }

    /** Falls Android den Dienst mitten in einer Session beendet hat, die Session bis zum letzten Lebenszeichen retten. */
    private void recoverPendingSession() {
        long start = prefs.getLong("start", 0);
        long seen = prefs.getLong("lastSeen", 0);
        int videos = prefs.getInt("videos", 1);
        if (start > 0 && seen - start >= MIN_SESSION_MS) {
            ShortsDb.get(this).insert(start, seen, Math.max(1, videos));
        }
        clearPending();
    }

    @Override
    public void onInterrupt() {
        endSession(System.currentTimeMillis());
    }

    @Override
    public void onDestroy() {
        endSession(System.currentTimeMillis());
        if (content != null) content.commitAll(null);
        if (screenOffReceiver != null) {
            try {
                unregisterReceiver(screenOffReceiver);
            } catch (Exception ignored) {
            }
        }
        super.onDestroy();
    }
}
