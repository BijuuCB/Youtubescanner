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

import java.util.ArrayDeque;
import java.util.List;

/**
 * Erkennt über die Bedienungshilfen, ob in der YouTube-App gerade der
 * Shorts-Player auf dem Bildschirm ist. Es werden nur Start- und Endzeit
 * gespeichert, keine Inhalte.
 */
public class ShortsService extends AccessibilityService {

    static final String YT = "com.google.android.youtube";

    /** Bekannte View-IDs des Shorts-Players (können sich mit YouTube-Updates ändern). */
    private static final String[] SHORTS_IDS = {
            "reel_recycler",
            "reel_player_page_container",
            "reel_watch_player",
            "reel_player_overlay_v2_scrims",
            "reel_time_bar",
            "shorts_container"
    };

    private static final long CHECK_INTERVAL_MS = 700;
    private static final long MIN_SESSION_MS = 3000;
    private static final long SWIPE_DEBOUNCE_MS = 1200;

    private long sessionStart = 0;
    private long lastSeen = 0;
    private long lastCheck = 0;
    private long lastPersist = 0;
    private long lastSwipe = 0;
    private int swipes = 0;

    private SharedPreferences prefs;
    private BroadcastReceiver screenOffReceiver;

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

        if (sessionStart > 0) {
            lastSeen = now;
            if (type == AccessibilityEvent.TYPE_VIEW_SCROLLED && now - lastSwipe > SWIPE_DEBOUNCE_MS) {
                swipes++;
                lastSwipe = now;
            }
            if (now - lastPersist > 5000) persistPending();
        }

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
    }

    private boolean isShortsVisible(AccessibilityNodeInfo root) {
        // 1. Schneller Weg: bekannte IDs direkt abfragen.
        for (String id : SHORTS_IDS) {
            List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByViewId(YT + ":id/" + id);
            if (found != null) {
                for (AccessibilityNodeInfo n : found) {
                    if (n != null && n.isVisibleToUser() && isLarge(n)) return true;
                }
            }
        }
        // 2. Rückfall: Baum nach großen sichtbaren "reel"-Elementen durchsuchen.
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;
        while (!queue.isEmpty() && visited < 600) {
            AccessibilityNodeInfo n = queue.poll();
            visited++;
            if (n == null) continue;
            String vid = n.getViewIdResourceName();
            if (vid != null && vid.startsWith(YT) && vid.contains("reel") && n.isVisibleToUser() && isLarge(n)) {
                return true;
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo child = n.getChild(i);
                if (child != null) queue.add(child);
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

    private void startSession(long now) {
        sessionStart = now;
        lastSeen = now;
        swipes = 0;
        lastSwipe = now;
        persistPending();
    }

    private void endSession(long now) {
        if (sessionStart == 0) return;
        long end = now;
        if (end - sessionStart >= MIN_SESSION_MS) {
            ShortsDb.get(this).insert(sessionStart, end, Math.max(1, swipes + 1));
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
        if (prefs != null) prefs.edit().clear().apply();
    }

    /** Falls Android den Dienst mitten in einer Session beendet hat, die Session bis zum letzten Lebenszeichen retten. */
    private void recoverPendingSession() {
        long start = prefs.getLong("start", 0);
        long seen = prefs.getLong("lastSeen", 0);
        int sw = prefs.getInt("swipes", 0);
        if (start > 0 && seen - start >= MIN_SESSION_MS) {
            ShortsDb.get(this).insert(start, seen, Math.max(1, sw + 1));
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
