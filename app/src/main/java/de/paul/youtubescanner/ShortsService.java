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
    private int swipes = 0;

    private SharedPreferences prefs;
    private BroadcastReceiver screenOffReceiver;
    private final SimpleDateFormat diagFmt = new SimpleDateFormat("dd.MM. HH:mm:ss", Locale.GERMANY);

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        prefs = getSharedPreferences("pending", MODE_PRIVATE);
        recoverPendingSession();

        screenOffReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                endSession(System.currentTimeMillis());
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
        } else if (sessionStart > 0) {
            endSession(now);
        }

        if (now - lastDiag > DIAG_INTERVAL_MS) {
            lastDiag = now;
            writeDiagnostics(root, inShorts, now);
        }
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

        String line = diagFmt.format(new Date(now)) + " " + (inShorts ? "[SHORTS]" : "[-]")
                + " groß=" + big + " stichwort=" + keywords;
        SharedPreferences d = getSharedPreferences("diag", MODE_PRIVATE);
        String old = d.getString("log", "");
        // Gleiche Zeile wie zuletzt nicht doppelt speichern
        String lastBody = d.getString("lastBody", "");
        String body = (inShorts ? "S" : "-") + big + keywords;
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
        lastSwipe = now;
        persistPending();
    }

    private void endSession(long now) {
        if (sessionStart == 0) return;
        if (now - sessionStart >= MIN_SESSION_MS) {
            ShortsDb.get(this).insert(sessionStart, now, swipes + 1);
        }
        sessionStart = 0;
        swipes = 0;
        clearPending();
    }

    private void persistPending() {
        if (prefs == null) return;
        lastPersist = System.currentTimeMillis();
        prefs.edit()
                .putLong("start", sessionStart)
                .putLong("lastSeen", lastSeen)
                .putInt("swipes", swipes)
                .apply();
    }

    private void clearPending() {
        if (prefs != null) prefs.edit().remove("start").remove("lastSeen").remove("swipes").apply();
    }

    /** Falls Android den Dienst mitten in einer Session beendet hat, die Session bis zum letzten Lebenszeichen retten. */
    private void recoverPendingSession() {
        long start = prefs.getLong("start", 0);
        long seen = prefs.getLong("lastSeen", 0);
        int sw = prefs.getInt("swipes", 0);
        if (start > 0 && seen - start >= MIN_SESSION_MS) {
            ShortsDb.get(this).insert(start, seen, sw + 1);
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
        if (screenOffReceiver != null) {
            try {
                unregisterReceiver(screenOffReceiver);
            } catch (Exception ignored) {
            }
        }
        super.onDestroy();
    }
}
