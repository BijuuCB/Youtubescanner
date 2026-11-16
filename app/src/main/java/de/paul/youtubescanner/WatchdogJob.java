package de.paul.youtubescanner;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;

/**
 * Stündlicher Wächter:
 * - sichert die App-Zeitleiste in die eigene Datenbank,
 * - prüft, ob die Shorts-/Inhalts-Erkennung läuft, und meldet sich per
 *   Benachrichtigung, wenn nicht (ein Tipp darauf öffnet die App und weckt den Dienst).
 * Läuft auch nach einem Neustart des Handys weiter.
 *
 * Grenze: Wurde die App über "Beenden erzwingen" gestoppt, lässt Android
 * weder diesen Wächter noch sonst etwas von ihr laufen, bis sie wieder
 * geöffnet wird.
 */
public class WatchdogJob extends JobService {

    private static final int JOB_ID = 4711;
    static final String CHANNEL = "scanner_status";
    private static final int NOTIF_ID = 1;

    public static void schedule(Context ctx) {
        JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        if (js.getPendingJob(JOB_ID) != null) return;
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(ctx, WatchdogJob.class))
                .setPeriodic(60 * 60_000L, 15 * 60_000L)
                .setPersisted(true)
                .build();
        try {
            js.schedule(job);
        } catch (Exception ignored) {
        }
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        new Thread(() -> {
            try {
                UsageArchive.get(this).syncRecent();
                ContentDb.get(this).purgeOld();
            } catch (Exception ignored) {
            }
        }).start();

        // Dem System kurz Zeit geben, den Dienst nach einem Prozess-Neustart wieder anzubinden.
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (isServiceRunning(this)) {
                cancelWarning(this);
            } else {
                showWarning(this);
            }
            jobFinished(params, false);
        }, 15_000);
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }

    static boolean isEnabledInSettings(Context ctx) {
        String enabled = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabled)) return false;
        ComponentName me = new ComponentName(ctx, ShortsService.class);
        for (String s : enabled.split(":")) {
            if (s.equalsIgnoreCase(me.flattenToString()) || s.equalsIgnoreCase(me.flattenToShortString())) return true;
        }
        return false;
    }

    /** Läuft der Dienst gerade wirklich (nicht nur in den Einstellungen eingeschaltet)? */
    static boolean isServiceRunning(Context ctx) {
        return ShortsService.running && isEnabledInSettings(ctx);
    }

    static void ensureChannel(Context ctx) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null || nm.getNotificationChannel(CHANNEL) != null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Scanner-Status", NotificationManager.IMPORTANCE_DEFAULT);
        ch.setDescription("Meldet sich, wenn die Erkennung nicht läuft.");
        nm.createNotificationChannel(ch);
    }

    static void showWarning(Context ctx) {
        ensureChannel(ctx);
        Intent open = new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        boolean enabled = isEnabledInSettings(ctx);
        Notification n = new Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_recent_history)
                .setContentTitle("YouTube Scanner läuft nicht")
                .setContentText(enabled
                        ? "Tippen, um die Erkennung wieder zu starten."
                        : "Die Bedienungshilfe ist aus – tippen und wieder einschalten.")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        try {
            if (nm != null) nm.notify(NOTIF_ID, n);
        } catch (SecurityException ignored) {
            // Benachrichtigungen nicht erlaubt
        }
    }

    static void cancelWarning(Context ctx) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(NOTIF_ID);
    }
}
