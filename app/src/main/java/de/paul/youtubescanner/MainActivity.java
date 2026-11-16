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

        // Für die Warnung "Scanner läuft nicht" (Android 13+ fragt einmal nach).
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
        // Zeitleiste der letzten Tage im Hintergrund sichern und Wächter planen.
        new Thread(() -> {
            try {
                UsageArchive.get(this).syncRecent();
            } catch (Exception ignored) {
            }
        }).start();
        WatchdogJob.schedule(this);
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
        else renderStatus();

        List<UsageReader.AppSession> apps = UsageArchive.get(this).sessionsForDay(dayStart, dayEnd);
        List<ShortsDb.Session> shorts = ShortsDb.get(this).between(dayStart, dayEnd);

        renderSummary(apps, shorts, dayStart, dayEnd);
        renderTopApps(apps);
        renderShare(apps, shorts, dayStart, dayEnd);
        renderContent(dayStart, dayEnd);
        renderTimeline(apps, shorts, dayStart, dayEnd);
        renderContentSettings();
        renderXiaomiHint();
        renderTools();
    }

    // ------------------------------------------------- Inhalte & Teilen

    private static String appName(String pkg) {
        for (String[] a : ContentWatcher.APPS) if (a[0].equals(pkg)) return a[1].split(" ")[0];
        return pkg;
    }

    private void renderStatus() {
        boolean running = ShortsService.running;
        TextView t = text(running
                ? "● Erkennung läuft"
                : "● Erkennung startet … falls das so bleibt: Bedienungshilfe kurz aus- und wieder einschalten", 13,
                running ? ACCENT : SHORTS, true);
        t.setGravity(Gravity.CENTER);
        if (!running) t.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        content.addView(t);
        space(10);
    }

    private void renderShare(List<UsageReader.AppSession> apps, List<ShortsDb.Session> shorts, long dayStart, long dayEnd) {
        Button week = bigButton("Letzte 7 Tage an Claude teilen");
        week.setOnClickListener(v -> shareWeek());
        content.addView(week);
        Button share = bigButton("Nur diesen Tag teilen");
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.parseColor("#5E8A7D"));
        d.setCornerRadius(dp(10));
        share.setBackground(d);
        share.setOnClickListener(v -> shareDay(apps, shorts, dayStart, dayEnd));
        content.addView(share);
        space(12);
    }

    /** Baut das Protokoll der letzten 7 Tage (inkl. heute) und teilt es als Textdatei. */
    private void shareWeek() {
        android.widget.Toast.makeText(this, "Protokoll wird erstellt …", android.widget.Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String text;
            try {
                UsageArchive.get(this).syncRecent();
                text = buildWeekText();
            } catch (Exception e) {
                runOnUiThread(() -> android.widget.Toast.makeText(this, "Fehler: " + e.getMessage(), android.widget.Toast.LENGTH_LONG).show());
                return;
            }
            final String body = text;
            runOnUiThread(() -> sendAsFile(body));
        }).start();
    }

    private String buildWeekText() {
        SimpleDateFormat dateFmt = new SimpleDateFormat("EEEE, d. MMMM yyyy", Locale.GERMANY);
        SimpleDateFormat shortDate = new SimpleDateFormat("EE d.M.", Locale.GERMANY);
        long from = UsageArchive.dayStart(-6);
        long to = UsageArchive.dayStart(1);

        StringBuilder sb = new StringBuilder();
        sb.append("HANDY-PROTOKOLL DER LETZTEN 7 TAGE\n")
                .append(dateFmt.format(from)).append(" bis ").append(dateFmt.format(to - 1)).append("\n\n")
                .append("Aufbau: zuerst eine Übersicht pro Tag, danach für jeden Tag die Shorts, ")
                .append("die App-Zeitleiste (ab 1 Minute) und alle mitgelesenen Inhalte ")
                .append("(Fragen an Claude, Google-Suchen, besuchte Seiten, YouTube-Videos, Notion).\n\n");

        // Übersicht
        sb.append("== ÜBERSICHT ==\n");
        long weekScreen = 0, weekShorts = 0;
        int weekShortCount = 0;
        Map<String, Long> weekApps = new HashMap<>();
        Map<String, String> labels = new HashMap<>();
        for (int off = -6; off <= 0; off++) {
            long ds = UsageArchive.dayStart(off), de = UsageArchive.dayStart(off + 1);
            List<UsageReader.AppSession> apps = UsageArchive.get(this).read(ds, de);
            long screen = 0;
            for (UsageReader.AppSession a : apps) {
                screen += a.duration();
                weekApps.merge(a.pkg, a.duration(), Long::sum);
                labels.put(a.pkg, a.label);
            }
            long sm = 0;
            int sc = 0;
            for (ShortsDb.Session s : ShortsDb.get(this).between(ds, de)) {
                sm += clip(s.start, s.end, ds, de);
                sc += s.swipes;
            }
            int contentCount = ContentDb.get(this).between(ds, de).size();
            weekScreen += screen;
            weekShorts += sm;
            weekShortCount += sc;
            sb.append(shortDate.format(ds)).append(": Bildschirm ").append(apps.isEmpty() ? "keine Daten" : dur(screen))
                    .append(" · Shorts ").append(dur(sm)).append(" (").append(sc).append(")")
                    .append(" · ").append(contentCount).append(" Inhalte\n");
        }
        sb.append("Woche gesamt: Bildschirm ").append(dur(weekScreen)).append(" · Shorts ").append(dur(weekShorts))
                .append(" (").append(weekShortCount).append(" Shorts)\n");
        List<Map.Entry<String, Long>> top = new ArrayList<>(weekApps.entrySet());
        Collections.sort(top, (a, b) -> Long.compare(b.getValue(), a.getValue()));
        sb.append("Meiste Zeit in der Woche: ");
        for (int i = 0; i < Math.min(10, top.size()); i++) {
            if (i > 0) sb.append(", ");
            sb.append(labels.get(top.get(i).getKey())).append(" ").append(dur(top.get(i).getValue()));
        }
        sb.append("\n");

        // Tage im Detail
        for (int off = -6; off <= 0; off++) {
            long ds = UsageArchive.dayStart(off), de = UsageArchive.dayStart(off + 1);
            sb.append("\n\n######## ").append(dateFmt.format(ds).toUpperCase(Locale.GERMANY)).append(" ########\n");

            List<UsageReader.AppSession> apps = UsageArchive.get(this).read(ds, de);
            Map<String, Long> totals = new HashMap<>();
            for (UsageReader.AppSession a : apps) {
                totals.merge(a.pkg, a.duration(), Long::sum);
                labels.put(a.pkg, a.label);
            }
            List<Map.Entry<String, Long>> list = new ArrayList<>(totals.entrySet());
            Collections.sort(list, (a, b) -> Long.compare(b.getValue(), a.getValue()));
            sb.append("Apps: ");
            if (list.isEmpty()) sb.append("keine Daten");
            for (int i = 0; i < Math.min(8, list.size()); i++) {
                if (i > 0) sb.append(", ");
                sb.append(labels.get(list.get(i).getKey())).append(" ").append(dur(list.get(i).getValue()));
            }
            sb.append("\n");

            sb.append("\n-- Shorts --\n");
            List<ShortsDb.Session> shorts = ShortsDb.get(this).between(ds, de);
            if (shorts.isEmpty()) sb.append("keine\n");
            for (ShortsDb.Session s : shorts) {
                sb.append(timeFmt.format(s.start)).append("–").append(timeFmt.format(s.end)).append("  ")
                        .append(dur(s.end - s.start)).append(", ").append(s.swipes).append(" Shorts\n");
            }

            sb.append("\n-- Zeitleiste --\n");
            int n = 0;
            for (UsageReader.AppSession a : apps) {
                if (a.duration() < 60_000) continue;
                n++;
                sb.append(timeFmt.format(a.start)).append("–").append(timeFmt.format(a.end)).append("  ")
                        .append(a.label).append(" (").append(dur(a.duration())).append(")\n");
            }
            if (n == 0) sb.append(apps.isEmpty() ? "keine Daten\n" : "nur kurze App-Besuche unter 1 Minute\n");

            sb.append("\n-- Inhalte --\n");
            List<ContentDb.Entry> entries = ContentDb.get(this).between(ds, de);
            if (entries.isEmpty()) sb.append("keine\n");
            for (ContentDb.Entry e : entries) {
                sb.append(timeFmt.format(e.ts)).append(" [").append(appName(e.pkg)).append(" · ").append(e.kind).append("] ")
                        .append(e.text).append("\n");
            }
        }
        return sb.toString();
    }

    private void sendAsFile(String body) {
        try {
            String name = "Handy-Protokoll_7-Tage_" + new SimpleDateFormat("yyyy-MM-dd", Locale.GERMANY).format(System.currentTimeMillis()) + ".txt";
            java.io.File dir = ShareProvider.dir(this);
            java.io.File[] old = dir.listFiles();
            if (old != null) for (java.io.File f : old) f.delete();
            java.io.File f = new java.io.File(dir, name);
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
                out.write(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_STREAM, ShareProvider.uriFor(f));
            send.putExtra(Intent.EXTRA_SUBJECT, "Handy-Protokoll der letzten 7 Tage");
            send.putExtra(Intent.EXTRA_TEXT, "Im Anhang mein Handy-Protokoll der letzten 7 Tage. Bitte fass die Woche zusammen: "
                    + "Welche Themen haben mich beschäftigt? Wie viel war echtes Lernen bzw. sinnvolle Arbeit, wie viel Ablenkung – "
                    + "pro Tag und insgesamt? Was habe ich gegoogelt und worüber habe ich mich informiert? "
                    + "Wann und wie viel habe ich Shorts geschaut, gibt es Muster (Uhrzeiten, Tage)? "
                    + "Was fällt dir im Vergleich der Tage auf?");
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            send.setClipData(android.content.ClipData.newRawUri(name, ShareProvider.uriFor(f)));
            startActivity(Intent.createChooser(send, "7 Tage teilen"));
        } catch (Exception e) {
            android.widget.Toast.makeText(this, "Teilen fehlgeschlagen: " + e.getMessage(), android.widget.Toast.LENGTH_LONG).show();
        }
    }

    private void shareDay(List<UsageReader.AppSession> apps, List<ShortsDb.Session> shorts, long dayStart, long dayEnd) {
        SimpleDateFormat dateFmt = new SimpleDateFormat("EEEE, d. MMMM yyyy", Locale.GERMANY);
        StringBuilder sb = new StringBuilder();
        sb.append("Hier ist mein Handy-Protokoll vom ").append(dateFmt.format(dayStart)).append(".\n")
                .append("Bitte fass meinen Tag kurz zusammen: Welche Themen haben mich beschäftigt? ")
                .append("Wie viel davon war echtes Lernen bzw. sinnvolle Arbeit und wie viel Ablenkung? ")
                .append("Was habe ich gegoogelt und worüber habe ich mich informiert? ")
                .append("Und wie lange und wann habe ich Shorts geschaut?\n\n");

        // Bildschirmzeit pro App
        Map<String, Long> totals = new HashMap<>();
        Map<String, String> labels = new HashMap<>();
        long screen = 0;
        for (UsageReader.AppSession s : apps) {
            totals.merge(s.pkg, s.duration(), Long::sum);
            labels.put(s.pkg, s.label);
            screen += s.duration();
        }
        List<Map.Entry<String, Long>> list = new ArrayList<>(totals.entrySet());
        Collections.sort(list, (a, b) -> Long.compare(b.getValue(), a.getValue()));
        sb.append("== Bildschirmzeit gesamt: ").append(dur(screen)).append(" ==\n");
        for (int i = 0; i < Math.min(12, list.size()); i++) {
            sb.append("- ").append(labels.get(list.get(i).getKey())).append(": ").append(dur(list.get(i).getValue())).append('\n');
        }

        sb.append("\n== Shorts ==\n");
        if (shorts.isEmpty()) sb.append("keine\n");
        for (ShortsDb.Session s : shorts) {
            sb.append(timeFmt.format(s.start)).append("–").append(timeFmt.format(s.end)).append("  ")
                    .append(dur(s.end - s.start)).append(", ").append(s.swipes).append(" Shorts\n");
        }

        sb.append("\n== Zeitleiste ==\n");
        for (UsageReader.AppSession s : apps) {
            if (s.duration() < 30_000) continue;
            sb.append(timeFmt.format(s.start)).append("–").append(timeFmt.format(s.end)).append("  ")
                    .append(s.label).append(" (").append(dur(s.duration())).append(")\n");
        }

        sb.append("\n== Inhalte (was ich getippt, gesucht, geöffnet habe) ==\n");
        List<ContentDb.Entry> entries = ContentDb.get(this).between(dayStart, dayEnd);
        if (entries.isEmpty()) sb.append("keine\n");
        for (ContentDb.Entry e : entries) {
            sb.append(timeFmt.format(e.ts)).append(" [").append(appName(e.pkg)).append(" · ").append(e.kind).append("] ")
                    .append(e.text).append('\n');
        }

        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, "Handy-Protokoll " + dateFmt.format(dayStart));
        send.putExtra(Intent.EXTRA_TEXT, sb.toString());
        startActivity(Intent.createChooser(send, "Protokoll teilen"));
    }

    private void renderContent(long dayStart, long dayEnd) {
        List<ContentDb.Entry> entries = ContentDb.get(this).between(dayStart, dayEnd);
        LinearLayout card = card();
        card.addView(text("Was du gemacht hast", 17, TEXT, true));
        if (entries.isEmpty()) {
            card.addView(text("Noch nichts mitgelesen. Tippe z. B. etwas in Claude oder suche bei Google.", 14, MUTED, false));
        }
        int shown = 0;
        for (int i = entries.size() - 1; i >= 0 && shown < 40; i--, shown++) {
            ContentDb.Entry e = entries.get(i);
            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.VERTICAL);
            r.setPadding(0, dp(7), 0, dp(7));
            TextView meta = text(timeFmt.format(e.ts) + " · " + appName(e.pkg) + " · " + e.kind, 12, MUTED, false);
            TextView body = text(e.text, 14, TEXT, false);
            body.setMaxLines(3);
            body.setEllipsize(TextUtils.TruncateAt.END);
            r.addView(meta);
            r.addView(body);
            card.addView(r);
        }
        if (entries.size() > 40) card.addView(text("… und " + (entries.size() - 40) + " ältere Einträge (im Teilen enthalten)", 12, MUTED, false));
        content.addView(card);
        space(12);
    }

    private void renderContentSettings() {
        space(12);
        android.content.SharedPreferences p = getSharedPreferences("content_settings", MODE_PRIVATE);
        LinearLayout card = card();
        card.addView(text("Inhalte mitlesen", 17, TEXT, true));
        card.addView(text("Nur in diesen Apps. Passwortfelder und lange Zahlenfolgen werden nie gespeichert. "
                + "Alles bleibt auf dem Handy und wird nach 30 Tagen gelöscht.", 13, MUTED, false));

        android.widget.Switch master = new android.widget.Switch(this);
        master.setText("Mitlesen eingeschaltet");
        master.setTextSize(15);
        master.setTextColor(TEXT);
        master.setChecked(p.getBoolean("on", true));
        master.setPadding(0, dp(10), 0, dp(6));
        master.setOnCheckedChangeListener((b, on) -> p.edit().putBoolean("on", on).apply());
        card.addView(master);

        for (String[] a : ContentWatcher.APPS) {
            android.widget.Switch sw = new android.widget.Switch(this);
            sw.setText(a[1] + "\n" + a[2]);
            sw.setTextSize(14);
            sw.setTextColor(TEXT);
            sw.setPadding(dp(8), dp(6), 0, dp(6));
            sw.setChecked(p.getBoolean("app_" + a[0], true));
            sw.setOnCheckedChangeListener((b, on) -> p.edit().putBoolean("app_" + a[0], on).apply());
            card.addView(sw);
        }

        Button wipe = bigButton("Alle mitgelesenen Inhalte löschen");
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.parseColor("#B23A30"));
        d.setCornerRadius(dp(10));
        wipe.setBackground(d);
        wipe.setOnClickListener(v -> new android.app.AlertDialog.Builder(this)
                .setTitle("Inhalte löschen?")
                .setMessage("Alle mitgelesenen Texte, Suchen und Seiten werden entfernt.")
                .setPositiveButton("Löschen", (dlg, w) -> {
                    ContentDb.get(this).deleteAll();
                    render();
                })
                .setNegativeButton("Abbrechen", null)
                .show());
        card.addView(wipe);
        content.addView(card);
        space(12);
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
