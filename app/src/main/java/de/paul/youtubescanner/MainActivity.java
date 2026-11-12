package de.paul.youtubescanner;

import android.app.Activity;
import android.app.AppOpsManager;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.Process;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {

    private static final int BG = Color.parseColor("#F5F3EE");
    private static final int CARD = Color.WHITE;
    private static final int TEXT = Color.parseColor("#1D1B18");
    private static final int MUTED = Color.parseColor("#77726A");
    private static final int SHORTS = Color.parseColor("#E0352B");
    private static final int ACCENT = Color.parseColor("#2F5D50");

    private LinearLayout content;
    private int dayOffset = 0; // 0 = heute, -1 = gestern …

    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm", Locale.GERMANY);
    private final SimpleDateFormat dayFmt = new SimpleDateFormat("EEEE, d. MMMM", Locale.GERMANY);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFitsSystemWindows(true);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        content.setPadding(p, p, p, dp(40));
        scroll.addView(content);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    // ------------------------------------------------------------------ UI

    private void render() {
        content.removeAllViews();

        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        cal.add(Calendar.DAY_OF_YEAR, dayOffset);
        long dayStart = cal.getTimeInMillis();
        cal.add(Calendar.DAY_OF_YEAR, 1);
        long dayEnd = cal.getTimeInMillis();

        // Kopfzeile mit Tagesnavigation
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        Button prev = smallButton("◀");
        prev.setOnClickListener(v -> { dayOffset--; render(); });
        Button next = smallButton("▶");
        next.setEnabled(dayOffset < 0);
        next.setAlpha(dayOffset < 0 ? 1f : 0.3f);
        next.setOnClickListener(v -> { if (dayOffset < 0) { dayOffset++; render(); } });
        TextView title = text(dayOffset == 0 ? "Heute" : dayOffset == -1 ? "Gestern" : dayFmt.format(dayStart), 22, TEXT, true);
        title.setGravity(Gravity.CENTER);
        nav.addView(prev);
        nav.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        nav.addView(next);
        content.addView(nav);
        if (dayOffset == 0 || dayOffset == -1) {
            TextView sub = text(dayFmt.format(dayStart), 13, MUTED, false);
            sub.setGravity(Gravity.CENTER);
            content.addView(sub);
        }
        space(12);

        boolean usageOk = hasUsageAccess();
        boolean a11yOk = isAccessibilityEnabled();
        if (!usageOk || !a11yOk) renderSetup(usageOk, a11yOk);

        List<UsageReader.AppSession> apps = usageOk
                ? new UsageReader(this).sessions(dayStart, dayEnd)
                : new ArrayList<>();
        List<ShortsDb.Session> shorts = ShortsDb.get(this).between(dayStart, dayEnd);

        renderSummary(apps, shorts, dayStart, dayEnd);
        renderTopApps(apps);
        renderTimeline(apps, shorts, dayStart, dayEnd);
        renderXiaomiHint();
        renderTools();
    }

    private void renderTools() {
        space(12);
        LinearLayout card = card();
        card.addView(text("Shorts-Erkennung prüfen", 17, TEXT, true));
        card.addView(text("Das Protokoll enthält nur die Namen der Bildschirm-Elemente in YouTube "
                + "(z. B. „reel_recycler“), keinen Text und keine Videotitel. "
                + "Kopieren und an Claude schicken, wenn die Erkennung danebenliegt.", 13, MUTED, false));

        Button copy = bigButton("Diagnose kopieren");
        copy.setOnClickListener(v -> {
            String log = getSharedPreferences("diag", MODE_PRIVATE).getString("log", "");
            if (log.isEmpty()) {
                android.widget.Toast.makeText(this, "Noch leer – öffne kurz YouTube und Shorts.", android.widget.Toast.LENGTH_LONG).show();
                return;
            }
            String header = "YouTube Scanner " + versionName() + " · YouTube " + ytVersion() + "\n";
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("Diagnose", header + log));
            android.widget.Toast.makeText(this, "Kopiert", android.widget.Toast.LENGTH_SHORT).show();
        });
        card.addView(copy);

        Button wipe = bigButton("Alle Shorts-Daten löschen");
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.parseColor("#B23A30"));
        d.setCornerRadius(dp(10));
        wipe.setBackground(d);
        wipe.setOnClickListener(v -> new android.app.AlertDialog.Builder(this)
                .setTitle("Shorts-Daten löschen?")
                .setMessage("Alle bisher erkannten Shorts-Sessions werden entfernt. Die App-Zeitleiste bleibt.")
                .setPositiveButton("Löschen", (dlg, w) -> {
                    ShortsDb.get(this).deleteAll();
                    getSharedPreferences("diag", MODE_PRIVATE).edit().clear().apply();
                    render();
                })
                .setNegativeButton("Abbrechen", null)
                .show());
        card.addView(wipe);
        card.addView(text("Version " + versionName(), 12, MUTED, false));
        content.addView(card);
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private String ytVersion() {
        try {
            return getPackageManager().getPackageInfo(ShortsService.YT, 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private void renderSetup(boolean usageOk, boolean a11yOk) {
        LinearLayout card = card();
        card.addView(text("Einrichtung", 17, TEXT, true));
        card.addView(text("Damit die App alles sieht, braucht sie zwei Freigaben.", 14, MUTED, false));
        if (!usageOk) {
            Button b = bigButton("1. Nutzungszugriff erlauben");
            b.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)));
            card.addView(b);
        }
        if (!a11yOk) {
            Button b = bigButton((usageOk ? "" : "2. ") + "Shorts-Erkennung einschalten");
            b.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
            card.addView(b);
            card.addView(text("Unter „Heruntergeladene Apps“ → „YouTube Scanner“ einschalten. "
                    + "Wenn Android die Option sperrt: App-Info → ⋮ oben rechts → „Eingeschränkte Einstellungen zulassen“.", 13, MUTED, false));
        }
        content.addView(card);
        space(12);
    }

    private void renderSummary(List<UsageReader.AppSession> apps, List<ShortsDb.Session> shorts, long dayStart, long dayEnd) {
        long screen = 0, yt = 0;
        for (UsageReader.AppSession s : apps) {
            screen += s.duration();
            if (ShortsService.YT.equals(s.pkg)) yt += s.duration();
        }
        long shortsMs = 0;
        int swipes = 0;
        for (ShortsDb.Session s : shorts) {
            shortsMs += clip(s.start, s.end, dayStart, dayEnd);
            swipes += s.swipes;
        }

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(stat("Shorts", dur(shortsMs), shorts.size() + " Sessions · " + swipes + " Shorts", SHORTS),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        View gap = new View(this);
        row.addView(gap, new LinearLayout.LayoutParams(dp(10), 1));
        row.addView(stat("Bildschirmzeit", dur(screen), "YouTube gesamt: " + dur(yt), ACCENT),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        content.addView(row);
        space(12);
    }

    private void renderTopApps(List<UsageReader.AppSession> apps) {
        if (apps.isEmpty()) return;
        Map<String, Long> totals = new HashMap<>();
        Map<String, String> labels = new HashMap<>();
        for (UsageReader.AppSession s : apps) {
            totals.merge(s.pkg, s.duration(), Long::sum);
            labels.put(s.pkg, s.label);
        }
        List<Map.Entry<String, Long>> list = new ArrayList<>(totals.entrySet());
        Collections.sort(list, (a, b) -> Long.compare(b.getValue(), a.getValue()));
        long max = list.get(0).getValue();

        LinearLayout card = card();
        card.addView(text("Meiste Zeit", 17, TEXT, true));
        for (int i = 0; i < Math.min(6, list.size()); i++) {
            Map.Entry<String, Long> e = list.get(i);
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setPadding(0, dp(8), 0, dp(2));
            line.addView(text(labels.get(e.getKey()), 15, TEXT, false),
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            line.addView(text(dur(e.getValue()), 15, MUTED, false));
            card.addView(line);
            View bar = new View(this);
            GradientDrawable d = new GradientDrawable();
            d.setCornerRadius(dp(3));
            d.setColor(ShortsService.YT.equals(e.getKey()) ? SHORTS : ACCENT);
            bar.setBackground(d);
            int widthPct = (int) Math.max(2, 100 * e.getValue() / Math.max(1, max));
            LinearLayout barRow = new LinearLayout(this);
            barRow.setWeightSum(100);
            barRow.addView(bar, new LinearLayout.LayoutParams(0, dp(6), widthPct));
            card.addView(barRow);
        }
        content.addView(card);
        space(12);
    }

    private void renderTimeline(List<UsageReader.AppSession> apps, List<ShortsDb.Session> shorts, long dayStart, long dayEnd) {
        LinearLayout card = card();
        card.addView(text("Zeitleiste", 17, TEXT, true));
        if (apps.isEmpty() && shorts.isEmpty()) {
            card.addView(text("Noch keine Daten für diesen Tag.", 14, MUTED, false));
            content.addView(card);
            space(12);
            return;
        }

        // Neueste zuerst. Shorts-Sessions werden unter dem passenden YouTube-Eintrag eingerückt.
        List<UsageReader.AppSession> appsDesc = new ArrayList<>(apps);
        Collections.reverse(appsDesc);
        List<ShortsDb.Session> usedShorts = new ArrayList<>();

        for (UsageReader.AppSession s : appsDesc) {
            boolean isYt = ShortsService.YT.equals(s.pkg);
            card.addView(row(timeFmt.format(s.start) + "–" + timeFmt.format(s.end), s.label, dur(s.duration()),
                    isYt ? SHORTS : TEXT, false));
            if (isYt) {
                for (ShortsDb.Session sh : shorts) {
                    if (sh.end > s.start - 5000 && sh.start < s.end + 5000) {
                        card.addView(row(timeFmt.format(sh.start) + "–" + timeFmt.format(sh.end),
                                "▶ Shorts · " + sh.swipes + " Videos", dur(sh.end - sh.start), SHORTS, true));
                        usedShorts.add(sh);
                    }
                }
            }
        }
        // Shorts, die keinem YouTube-Eintrag zugeordnet werden konnten (z. B. ohne Nutzungszugriff)
        for (ShortsDb.Session sh : shorts) {
            if (usedShorts.contains(sh)) continue;
            card.addView(row(timeFmt.format(sh.start) + "–" + timeFmt.format(sh.end),
                    "▶ Shorts · " + sh.swipes + " Videos", dur(clip(sh.start, sh.end, dayStart, dayEnd)), SHORTS, false));
        }
        content.addView(card);
        space(12);
    }

    private void renderXiaomiHint() {
        String brand = (Build.MANUFACTURER + " " + Build.BRAND).toLowerCase(Locale.ROOT);
        boolean xiaomi = brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco");
        PowerManager pmgr = (PowerManager) getSystemService(POWER_SERVICE);
        boolean batteryOk = pmgr != null && pmgr.isIgnoringBatteryOptimizations(getPackageName());
        if (!xiaomi && batteryOk) return;

        LinearLayout card = card();
        card.addView(text("Damit nichts verloren geht", 17, TEXT, true));
        card.addView(text("Xiaomi beendet Hintergrund-Dienste gern. Dann fehlen Shorts-Zeiten. "
                + "Stell Akku auf „Keine Einschränkungen“ und erlaube Autostart.", 14, MUTED, false));
        if (!batteryOk) {
            Button b = bigButton("Akku-Einschränkung aufheben");
            b.setOnClickListener(v -> {
                try {
                    Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    i.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                } catch (Exception e) {
                    openAppInfo();
                }
            });
            card.addView(b);
        }
        if (xiaomi) {
            Button auto = bigButton("Autostart erlauben");
            auto.setOnClickListener(v -> {
                try {
                    Intent i = new Intent();
                    i.setComponent(new ComponentName("com.miui.securitycenter",
                            "com.miui.permcenter.autostart.AutoStartManagementActivity"));
                    startActivity(i);
                } catch (Exception e) {
                    openAppInfo();
                }
            });
            card.addView(auto);
        }
        content.addView(card);
    }

    // ------------------------------------------------------------ Hilfen

    private void openAppInfo() {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        i.setData(Uri.parse("package:" + getPackageName()));
        startActivity(i);
    }

    private boolean hasUsageAccess() {
        AppOpsManager ops = (AppOpsManager) getSystemService(APP_OPS_SERVICE);
        int mode;
        if (Build.VERSION.SDK_INT >= 29) {
            mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), getPackageName());
        } else {
            mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), getPackageName());
        }
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    private boolean isAccessibilityEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabled)) return false;
        String me = new ComponentName(this, ShortsService.class).flattenToString();
        String meShort = new ComponentName(this, ShortsService.class).flattenToShortString();
        for (String s : enabled.split(":")) {
            if (s.equalsIgnoreCase(me) || s.equalsIgnoreCase(meShort)) return true;
        }
        return false;
    }

    private static long clip(long s, long e, long from, long to) {
        return Math.max(0, Math.min(e, to) - Math.max(s, from));
    }

    private static String dur(long ms) {
        long sec = ms / 1000;
        if (sec < 60) return sec + " Sek";
        long min = sec / 60;
        if (min < 60) return min + " Min";
        return (min / 60) + " Std " + (min % 60) + " Min";
    }

    private LinearLayout row(String time, String label, String duration, int color, boolean indent) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(indent ? dp(18) : 0, dp(7), 0, dp(7));
        TextView t = text(time, 13, MUTED, false);
        t.setTypeface(Typeface.MONOSPACE);
        r.addView(t, new LinearLayout.LayoutParams(dp(indent ? 92 : 100), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView l = text(label, 15, color, !indent && color == SHORTS);
        l.setSingleLine(true);
        l.setEllipsize(TextUtils.TruncateAt.END);
        r.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(text(duration, 14, indent ? SHORTS : MUTED, false));
        return r;
    }

    private LinearLayout stat(String label, String value, String sub, int color) {
        LinearLayout c = card();
        c.addView(text(label, 13, MUTED, false));
        c.addView(text(value, 24, color, true));
        TextView s = text(sub, 12, MUTED, false);
        c.addView(s);
        return c;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable d = new GradientDrawable();
        d.setColor(CARD);
        d.setCornerRadius(dp(14));
        c.setBackground(d);
        c.setElevation(dp(1));
        return c;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(2), 0, dp(2));
        return t;
    }

    private Button bigButton(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        GradientDrawable d = new GradientDrawable();
        d.setColor(ACCENT);
        d.setCornerRadius(dp(10));
        b.setBackground(d);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        lp.topMargin = dp(10);
        b.setLayoutParams(lp);
        return b;
    }

    private Button smallButton(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextColor(TEXT);
        GradientDrawable d = new GradientDrawable();
        d.setColor(CARD);
        d.setCornerRadius(dp(10));
        b.setBackground(d);
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(44)));
        return b;
    }

    private void space(int dpH) {
        View v = new View(this);
        content.addView(v, new LinearLayout.LayoutParams(1, dp(dpH)));
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
