package de.paul.youtubescanner;

import android.app.AppOpsManager;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Build;
import android.os.Process;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * Eigene Kopie der App-Zeitleiste. Android hebt die genauen Nutzungszeiten nur
 * etwa 7–10 Tage auf (Xiaomi teils weniger); hier bleiben sie 60 Tage erhalten.
 * Wird beim Öffnen der App und stündlich vom Wächter aktualisiert.
 */
public class UsageArchive extends SQLiteOpenHelper {

    private static final long KEEP_MS = 60L * 24 * 3600_000L;
    private static final int SYNC_DAYS = 10;

    private static UsageArchive instance;

    public static synchronized UsageArchive get(Context ctx) {
        if (instance == null) instance = new UsageArchive(ctx.getApplicationContext());
        return instance;
    }

    private final Context ctx;

    private UsageArchive(Context ctx) {
        super(ctx, "usage_archive.db", null, 1);
        this.ctx = ctx;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE sessions (pkg TEXT NOT NULL, label TEXT NOT NULL, start INTEGER NOT NULL, end INTEGER NOT NULL, PRIMARY KEY (pkg, start))");
        db.execSQL("CREATE INDEX idx_s_start ON sessions(start)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int o, int n) {
    }

    public static boolean hasUsageAccess(Context ctx) {
        AppOpsManager ops = (AppOpsManager) ctx.getSystemService(Context.APP_OPS_SERVICE);
        int mode;
        if (Build.VERSION.SDK_INT >= 29) {
            mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.getPackageName());
        } else {
            mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.getPackageName());
        }
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    public static long dayStart(int offset) {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        cal.add(Calendar.DAY_OF_YEAR, offset);
        return cal.getTimeInMillis();
    }

    /** Kopiert die letzten 10 Tage aus Android in die eigene Datenbank. */
    public synchronized void syncRecent() {
        if (!hasUsageAccess(ctx)) return;
        UsageReader reader = new UsageReader(ctx);
        for (int off = -(SYNC_DAYS - 1); off <= 0; off++) {
            syncDay(reader, dayStart(off), dayStart(off + 1));
        }
        getWritableDatabase().delete("sessions", "end<?",
                new String[]{String.valueOf(System.currentTimeMillis() - KEEP_MS)});
    }

    /**
     * Ersetzt einen Tag nur, wenn Android für ihn mindestens so viele Daten liefert
     * wie schon gespeichert sind – sonst würden ältere, von Android bereits
     * gelöschte Zeiten verloren gehen.
     */
    private void syncDay(UsageReader reader, long from, long to) {
        List<UsageReader.AppSession> fresh = reader.sessions(from, to);
        if (fresh.isEmpty()) return;
        long freshTotal = 0;
        for (UsageReader.AppSession s : fresh) freshTotal += s.duration();
        long storedTotal = 0;
        for (UsageReader.AppSession s : read(from, to)) storedTotal += s.duration();
        if (freshTotal < storedTotal * 0.9) return;

        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("sessions", "start>=? AND start<?", new String[]{String.valueOf(from), String.valueOf(to)});
            for (UsageReader.AppSession s : fresh) {
                ContentValues v = new ContentValues();
                v.put("pkg", s.pkg);
                v.put("label", s.label);
                v.put("start", s.start);
                v.put("end", s.end);
                db.insertWithOnConflict("sessions", null, v, SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** Zeitleiste eines Tages: erst frisch aus Android holen, dann aus dem Archiv lesen. */
    public synchronized List<UsageReader.AppSession> sessionsForDay(long from, long to) {
        if (hasUsageAccess(ctx) && to > System.currentTimeMillis() - SYNC_DAYS * 24 * 3600_000L) {
            syncDay(new UsageReader(ctx), from, to);
        }
        return read(from, to);
    }

    public List<UsageReader.AppSession> read(long from, long to) {
        List<UsageReader.AppSession> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT pkg, label, start, end FROM sessions WHERE start>=? AND start<? ORDER BY start",
                new String[]{String.valueOf(from), String.valueOf(to)})) {
            while (c.moveToNext()) {
                out.add(new UsageReader.AppSession(c.getString(0), c.getString(1), c.getLong(2), c.getLong(3)));
            }
        }
        return out;
    }
}
