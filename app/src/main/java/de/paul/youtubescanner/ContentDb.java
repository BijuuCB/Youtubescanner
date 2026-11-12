package de.paul.youtubescanner;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * Lokales Protokoll der Inhalte aus freigegebenen Apps
 * (Fragen an Claude, Google-Suchen, besuchte Seiten, Videotitel, Notion-Texte).
 * Nichts verlässt das Handy, außer Paul teilt es selbst.
 */
public class ContentDb extends SQLiteOpenHelper {

    public static final long KEEP_MS = 30L * 24 * 3600_000L;

    public static class Entry {
        public final long ts;
        public final String pkg;
        public final String kind;
        public final String text;

        Entry(long ts, String pkg, String kind, String text) {
            this.ts = ts;
            this.pkg = pkg;
            this.kind = kind;
            this.text = text;
        }
    }

    private static ContentDb instance;

    public static synchronized ContentDb get(Context ctx) {
        if (instance == null) instance = new ContentDb(ctx.getApplicationContext());
        return instance;
    }

    private ContentDb(Context ctx) {
        super(ctx, "content.db", null, 1);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE entries (id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, pkg TEXT NOT NULL, kind TEXT NOT NULL, text TEXT NOT NULL)");
        db.execSQL("CREATE INDEX idx_ts ON entries(ts)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int o, int n) {
    }

    /** Speichert einen Eintrag, außer derselbe Text derselben App wurde in den letzten 30 Minuten schon gespeichert. */
    public synchronized void add(String pkg, String kind, String text) {
        long now = System.currentTimeMillis();
        SQLiteDatabase db = getWritableDatabase();
        try (Cursor c = db.rawQuery("SELECT 1 FROM entries WHERE pkg=? AND text=? AND ts>? LIMIT 1",
                new String[]{pkg, text, String.valueOf(now - 30 * 60_000L)})) {
            if (c.moveToFirst()) return;
        }
        ContentValues v = new ContentValues();
        v.put("ts", now);
        v.put("pkg", pkg);
        v.put("kind", kind);
        v.put("text", text);
        db.insert("entries", null, v);
    }

    public List<Entry> between(long from, long to) {
        List<Entry> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT ts, pkg, kind, text FROM entries WHERE ts>=? AND ts<? ORDER BY ts",
                new String[]{String.valueOf(from), String.valueOf(to)})) {
            while (c.moveToNext()) out.add(new Entry(c.getLong(0), c.getString(1), c.getString(2), c.getString(3)));
        }
        return out;
    }

    public void purgeOld() {
        getWritableDatabase().delete("entries", "ts<?", new String[]{String.valueOf(System.currentTimeMillis() - KEEP_MS)});
    }

    public void deleteAll() {
        getWritableDatabase().delete("entries", null, null);
    }
}
