package de.paul.youtubescanner;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/** Lokale Datenbank für erkannte Shorts-Sessions. Nichts verlässt das Handy. */
public class ShortsDb extends SQLiteOpenHelper {

    public static class Session {
        public final long start;
        public final long end;
        public final int swipes;

        Session(long start, long end, int swipes) {
            this.start = start;
            this.end = end;
            this.swipes = swipes;
        }
    }

    private static ShortsDb instance;

    public static synchronized ShortsDb get(Context ctx) {
        if (instance == null) instance = new ShortsDb(ctx.getApplicationContext());
        return instance;
    }

    private ShortsDb(Context ctx) {
        super(ctx, "shorts.db", null, 1);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE sessions (id INTEGER PRIMARY KEY AUTOINCREMENT, start INTEGER NOT NULL, end INTEGER NOT NULL, swipes INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX idx_start ON sessions(start)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        // Version 1 – noch keine Migrationen nötig.
    }

    public void insert(long start, long end, int swipes) {
        ContentValues v = new ContentValues();
        v.put("start", start);
        v.put("end", end);
        v.put("swipes", swipes);
        getWritableDatabase().insert("sessions", null, v);
    }

    public void deleteAll() {
        getWritableDatabase().delete("sessions", null, null);
    }

    /** Alle Sessions, die den Zeitraum [from, to) berühren. */
    public List<Session> between(long from, long to) {
        List<Session> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT start, end, swipes FROM sessions WHERE end > ? AND start < ? ORDER BY start",
                new String[]{String.valueOf(from), String.valueOf(to)})) {
            while (c.moveToNext()) {
                out.add(new Session(c.getLong(0), c.getLong(1), c.getInt(2)));
            }
        }
        return out;
    }
}
