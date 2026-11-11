package de.paul.youtubescanner;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Baut aus den Android-Nutzungsereignissen eine Zeitleiste: welche App wann im Vordergrund war. */
public class UsageReader {

    public static class AppSession {
        public final String pkg;
        public final String label;
        public long start;
        public long end;

        AppSession(String pkg, String label, long start, long end) {
            this.pkg = pkg;
            this.label = label;
            this.start = start;
            this.end = end;
        }

        public long duration() {
            return end - start;
        }
    }

    private static final int ACTIVITY_RESUMED = 1;
    private static final int ACTIVITY_PAUSED = 2;
    private static final int SCREEN_NON_INTERACTIVE = 16;
    private static final int ACTIVITY_STOPPED = 23;
    private static final int KEYGUARD_SHOWN = 17;

    private final Context ctx;
    private final PackageManager pm;
    private final Map<String, String> labelCache = new HashMap<>();
    private final Set<String> ignored = new HashSet<>();

    public UsageReader(Context ctx) {
        this.ctx = ctx;
        this.pm = ctx.getPackageManager();
        ignored.add("com.android.systemui");
        ignored.add("android");
        // Startbildschirm(e) ausblenden
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        for (ResolveInfo ri : pm.queryIntentActivities(home, 0)) {
            if (ri.activityInfo != null) ignored.add(ri.activityInfo.packageName);
        }
    }

    public List<AppSession> sessions(long dayStart, long dayEnd) {
        long now = System.currentTimeMillis();
        long queryEnd = Math.min(dayEnd, now);
        List<AppSession> raw = new ArrayList<>();
        if (queryEnd <= dayStart) return raw;

        UsageStatsManager usm = (UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);
        // Etwas früher anfangen, um Sessions über Mitternacht korrekt zu erfassen.
        UsageEvents events = usm.queryEvents(dayStart - 3 * 3600_000L, queryEnd);
        UsageEvents.Event ev = new UsageEvents.Event();

        String curPkg = null;
        long curStart = 0;

        while (events.hasNextEvent()) {
            events.getNextEvent(ev);
            int type = ev.getEventType();
            String pkg = ev.getPackageName();
            long ts = ev.getTimeStamp();

            if (type == ACTIVITY_RESUMED) {
                if (curPkg != null && !curPkg.equals(pkg)) {
                    add(raw, curPkg, curStart, ts);
                    curPkg = null;
                }
                if (curPkg == null) {
                    curPkg = pkg;
                    curStart = ts;
                }
            } else if ((type == ACTIVITY_PAUSED || type == ACTIVITY_STOPPED) && pkg.equals(curPkg)) {
                add(raw, curPkg, curStart, ts);
                curPkg = null;
            } else if ((type == SCREEN_NON_INTERACTIVE || type == KEYGUARD_SHOWN) && curPkg != null) {
                add(raw, curPkg, curStart, ts);
                curPkg = null;
            }
        }
        if (curPkg != null && dayEnd > now) {
            add(raw, curPkg, curStart, now);
        }

        // Auf den Tag zuschneiden, Filter anwenden, Lücken < 3 s bei gleicher App zusammenführen.
        List<AppSession> out = new ArrayList<>();
        for (AppSession s : raw) {
            if (s.end <= dayStart || s.start >= dayEnd) continue;
            s.start = Math.max(s.start, dayStart);
            s.end = Math.min(s.end, dayEnd);
            if (ignored.contains(s.pkg)) continue;
            AppSession last = out.isEmpty() ? null : out.get(out.size() - 1);
            if (last != null && last.pkg.equals(s.pkg) && s.start - last.end < 3000) {
                last.end = Math.max(last.end, s.end);
            } else {
                out.add(s);
            }
        }
        List<AppSession> filtered = new ArrayList<>();
        for (AppSession s : out) if (s.duration() >= 2000) filtered.add(s);
        return filtered;
    }

    private void add(List<AppSession> list, String pkg, long start, long end) {
        if (end > start) list.add(new AppSession(pkg, label(pkg), start, end));
    }

    public String label(String pkg) {
        String cached = labelCache.get(pkg);
        if (cached != null) return cached;
        String label = pkg;
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            label = pm.getApplicationLabel(ai).toString();
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        labelCache.put(pkg, label);
        return label;
    }
}
