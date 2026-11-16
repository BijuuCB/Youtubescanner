package de.paul.youtubescanner;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * Kachel in den Schnelleinstellungen (Benachrichtigungsleiste herunterziehen).
 * Zeigt, ob der Scanner läuft; ein Tipp öffnet die App und weckt damit den Dienst
 * wieder auf – z. B. nach dem Schließen für Klarna.
 */
public class ScannerTile extends TileService {

    @Override
    public void onStartListening() {
        Tile t = getQsTile();
        if (t == null) return;
        boolean running = WatchdogJob.isServiceRunning(this);
        t.setState(running ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.setLabel("Scanner");
        if (Build.VERSION.SDK_INT >= 29) t.setSubtitle(running ? "läuft" : "aus – tippen");
        t.updateTile();
    }

    @Override
    public void onClick() {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (Build.VERSION.SDK_INT >= 34) {
            PendingIntent pi = PendingIntent.getActivity(this, 1, open, PendingIntent.FLAG_IMMUTABLE);
            startActivityAndCollapse(pi);
        } else {
            startActivityAndCollapse(open);
        }
    }
}
