package com.darren.batterylens;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import java.util.ArrayList;
import java.util.List;

/** Bounded local trend history. Indexed inserts avoid rewriting every stored reading. */
final class TrendRepository extends SQLiteOpenHelper {
    private static final String TABLE = "readings";
    private static final String OLD_KEY = "live_trends_v1";
    private static final String MIGRATED = "trend_database_migrated_v1";
    private static final int MAX_POINTS = 2300;
    private final Context context;
    private boolean migrationChecked;

    TrendRepository(Context context) {
        super(context, "battery_trends.db", null, 1);
        this.context = context.getApplicationContext();
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE readings (time INTEGER PRIMARY KEY, elapsed INTEGER NOT NULL, "
                + "boot INTEGER NOT NULL, soc REAL, ma REAL, temp REAL, plugged INTEGER NOT NULL, "
                + "screen INTEGER, hotspot INTEGER NOT NULL)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Migrations for later schema versions belong here; never discard user history.
    }

    private SQLiteDatabase database() {
        SQLiteDatabase db = getWritableDatabase();
        if (!migrationChecked) {
            android.content.SharedPreferences p = context.getSharedPreferences(CalibrationStore.PREFS, 0);
            if (!p.getBoolean(MIGRATED, false)) {
                String old = p.getString(OLD_KEY, "[]");
                db.beginTransaction();
                try {
                    JSONArray entries = new JSONArray(old == null ? "[]" : old);
                    for (int i = 0; i < entries.length(); i++) {
                        TrendPoint point = TrendPoint.fromJson(entries.optJSONObject(i));
                        if (point != null) insert(db, point);
                    }
                    db.setTransactionSuccessful();
                } catch (org.json.JSONException e) {
                    // A damaged legacy array cannot be trusted as readings.
                    db.setTransactionSuccessful();
                } finally { db.endTransaction(); }
                p.edit().putBoolean(MIGRATED, true).remove(OLD_KEY).apply();
            }
            migrationChecked = true;
        }
        return db;
    }

    private static void insert(SQLiteDatabase db, TrendPoint p) {
        ContentValues values = new ContentValues(9);
        values.put("time", p.time); values.put("elapsed", p.elapsed);
        values.put("boot", p.boot); values.put("plugged", p.plugged ? 1 : 0);
        values.put("hotspot", p.hotspotMarked ? 1 : 0);
        if (MeasurementRules.validSoc(p.soc)) values.put("soc", p.soc);
        if (Double.isFinite(p.currentMa)) values.put("ma", p.currentMa);
        if (Double.isFinite(p.temperatureC)) values.put("temp", p.temperatureC);
        if (p.screenKnown) values.put("screen", p.interactive ? 1 : 0);
        db.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    void add(TrendPoint p, long earliest, long now) {
        SQLiteDatabase db = database();
        db.beginTransaction();
        try {
            insert(db, p);
            db.delete(TABLE, "time < ? OR time > ?", new String[]{Long.toString(earliest), Long.toString(now)});
            try (Cursor cutoff = db.rawQuery("SELECT time FROM readings ORDER BY time DESC LIMIT 1 OFFSET "
                    + (MAX_POINTS - 1), null)) {
                if (cutoff.moveToFirst()) db.delete(TABLE, "time < ?", new String[]{Long.toString(cutoff.getLong(0))});
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    TrendPoint latest() {
        try (Cursor cursor = database().rawQuery("SELECT time,elapsed,boot,soc,ma,temp,plugged,screen,hotspot "
                + "FROM readings ORDER BY time DESC LIMIT 1", null)) {
            return cursor.moveToFirst() ? fromCursor(cursor) : null;
        }
    }

    List<TrendPoint> points(long now, long windowMs) {
        List<TrendPoint> result = new ArrayList<>();
        try (Cursor cursor = database().rawQuery("SELECT time,elapsed,boot,soc,ma,temp,plugged,screen,hotspot "
                + "FROM readings WHERE time BETWEEN ? AND ? ORDER BY time ASC",
                new String[]{Long.toString(now - windowMs), Long.toString(now)})) {
            while (cursor.moveToNext()) result.add(fromCursor(cursor));
        }
        return result;
    }

    void clear() {
        database().delete(TABLE, null, null);
        context.getSharedPreferences(CalibrationStore.PREFS, 0).edit().remove(OLD_KEY)
                .putBoolean(MIGRATED, true).apply();
    }

    private static TrendPoint fromCursor(Cursor c) {
        return new TrendPoint(c.getLong(0), c.getLong(1), c.getInt(2),
                c.isNull(3) ? Double.NaN : c.getDouble(3),
                c.isNull(4) ? Double.NaN : c.getDouble(4),
                c.isNull(5) ? Double.NaN : c.getDouble(5), c.getInt(6) != 0,
                c.getInt(7) != 0, !c.isNull(7), c.getInt(8) != 0);
    }
}
