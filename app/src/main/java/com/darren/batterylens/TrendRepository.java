package com.darren.batterylens;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/** Bounded local trend & session storage. Accessed exclusively on AppWorker. */
final class TrendRepository extends SQLiteOpenHelper {
    private static final String TABLE = "readings";
    private static final String SESSIONS_TABLE = "sessions";
    private static final String OLD_KEY = "live_trends_v1";
    private static final String MIGRATED = "trend_database_migrated_v1";
    private static final String SESSIONS_KEY = "calibration_sessions_v2";
    private static final String LEGACY_CALIBRATIONS = "calibrations";
    private static final String SESSIONS_MIGRATED = "session_database_migrated_v1";
    private static final int MAX_POINTS = 2300;
    private static final int MAX_SESSIONS = 60;
    private static final int PRUNE_INTERVAL = 24;

    private final Context context;
    private boolean trendMigrationChecked;
    private boolean sessionMigrationChecked;
    private int insertsSincePrune;
    private SQLiteStatement insertReadingStmt;
    private SQLiteStatement insertSessionStmt;

    TrendRepository(Context context) {
        super(context, "battery_trends.db", null, 2);
        this.context = context.getApplicationContext();
        setWriteAheadLoggingEnabled(true);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE readings (time INTEGER PRIMARY KEY, elapsed INTEGER NOT NULL, "
                + "boot INTEGER NOT NULL, soc REAL, ma REAL, temp REAL, plugged INTEGER NOT NULL, "
                + "screen INTEGER, hotspot INTEGER NOT NULL)");
        createSessionsTable(db);
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            createSessionsTable(db);
        }
    }

    private static void createSessionsTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS sessions ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "end_time INTEGER NOT NULL, "
                + "json TEXT NOT NULL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sessions_end_time ON sessions(end_time)");
    }

    private SQLiteDatabase database() {
        SQLiteDatabase db = getWritableDatabase();
        if (!trendMigrationChecked) {
            SharedPreferences p = context.getSharedPreferences(CalibrationStore.PREFS, 0);
            if (!p.getBoolean(MIGRATED, false)) {
                String old = p.getString(OLD_KEY, "[]");
                db.beginTransactionNonExclusive();
                try {
                    JSONArray entries = new JSONArray(old == null ? "[]" : old);
                    for (int i = 0; i < entries.length(); i++) {
                        TrendPoint point = TrendPoint.fromJson(entries.optJSONObject(i));
                        if (point != null) insertReading(db, point);
                    }
                    db.setTransactionSuccessful();
                } catch (org.json.JSONException e) {
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                }
                p.edit().putBoolean(MIGRATED, true).remove(OLD_KEY).apply();
            }
            trendMigrationChecked = true;
        }
        return db;
    }

    private SQLiteDatabase sessionDatabase() {
        SQLiteDatabase db = database();
        if (!sessionMigrationChecked) {
            SharedPreferences p = context.getSharedPreferences(CalibrationStore.PREFS, 0);
            if (!p.getBoolean(SESSIONS_MIGRATED, false)) {
                migrateSessionsFromPrefs(db, p);
            }
            sessionMigrationChecked = true;
        }
        return db;
    }

    private void migrateSessionsFromPrefs(SQLiteDatabase db, SharedPreferences p) {
        db.beginTransactionNonExclusive();
        try {
            if (p.contains(SESSIONS_KEY)) {
                String raw = p.getString(SESSIONS_KEY, "[]");
                JSONArray arr = new JSONArray(raw == null ? "[]" : raw);
                int start = Math.max(0, arr.length() - MAX_SESSIONS);
                for (int i = start; i < arr.length(); i++) {
                    JSONObject obj = arr.optJSONObject(i);
                    CalibrationStore.Session s = CalibrationStore.Session.fromJson(obj);
                    if (s != null) insertSession(db, s);
                }
            } else if (p.contains(LEGACY_CALIBRATIONS)) {
                List<CalibrationStore.Session> legacy = CalibrationStore.parseLegacyString(p.getString(LEGACY_CALIBRATIONS, ""));
                int start = Math.max(0, legacy.size() - MAX_SESSIONS);
                for (int i = start; i < legacy.size(); i++) {
                    insertSession(db, legacy.get(i));
                }
            }
            db.setTransactionSuccessful();
        } catch (Exception ignored) {
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        p.edit().putBoolean(SESSIONS_MIGRATED, true).remove(SESSIONS_KEY).remove(LEGACY_CALIBRATIONS).apply();
    }

    private void insertReading(SQLiteDatabase db, TrendPoint p) {
        if (insertReadingStmt == null) {
            insertReadingStmt = db.compileStatement(
                    "INSERT OR REPLACE INTO readings (time,elapsed,boot,soc,ma,temp,plugged,screen,hotspot) "
                            + "VALUES (?,?,?,?,?,?,?,?,?)");
        }
        insertReadingStmt.clearBindings();
        insertReadingStmt.bindLong(1, p.time);
        insertReadingStmt.bindLong(2, p.elapsed);
        insertReadingStmt.bindLong(3, p.boot);
        if (MeasurementRules.validSoc(p.soc)) insertReadingStmt.bindDouble(4, p.soc); else insertReadingStmt.bindNull(4);
        if (Double.isFinite(p.currentMa)) insertReadingStmt.bindDouble(5, p.currentMa); else insertReadingStmt.bindNull(5);
        if (Double.isFinite(p.temperatureC)) insertReadingStmt.bindDouble(6, p.temperatureC); else insertReadingStmt.bindNull(6);
        insertReadingStmt.bindLong(7, p.plugged ? 1L : 0L);
        if (p.screenKnown) insertReadingStmt.bindLong(8, p.interactive ? 1L : 0L); else insertReadingStmt.bindNull(8);
        insertReadingStmt.bindLong(9, p.hotspotMarked ? 1L : 0L);
        insertReadingStmt.executeInsert();
    }

    private void insertSession(SQLiteDatabase db, CalibrationStore.Session s) {
        if (insertSessionStmt == null) {
            insertSessionStmt = db.compileStatement("INSERT INTO sessions (end_time, json) VALUES (?, ?)");
        }
        insertSessionStmt.clearBindings();
        insertSessionStmt.bindLong(1, s.endTime);
        insertSessionStmt.bindString(2, s.toJson().toString());
        insertSessionStmt.executeInsert();
    }

    void add(TrendPoint p, long earliest, long now) {
        SQLiteDatabase db = database();
        boolean prune = insertsSincePrune == 0 || insertsSincePrune >= PRUNE_INTERVAL;
        db.beginTransactionNonExclusive();
        try {
            insertReading(db, p);
            if (prune) {
                db.delete(TABLE, "time < ? OR time > ?", new String[]{Long.toString(earliest), Long.toString(now)});
                try (Cursor cutoff = db.rawQuery("SELECT time FROM readings ORDER BY time DESC LIMIT 1 OFFSET "
                        + (MAX_POINTS - 1), null)) {
                    if (cutoff.moveToFirst()) db.delete(TABLE, "time < ?", new String[]{Long.toString(cutoff.getLong(0))});
                }
                insertsSincePrune = 1;
            } else {
                insertsSincePrune++;
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
        insertsSincePrune = 0;
        context.getSharedPreferences(CalibrationStore.PREFS, 0).edit().remove(OLD_KEY)
                .putBoolean(MIGRATED, true).apply();
    }

    List<CalibrationStore.Session> loadSessions() {
        SQLiteDatabase db = sessionDatabase();
        List<CalibrationStore.Session> out = new ArrayList<>();
        try (Cursor cursor = db.rawQuery("SELECT json FROM sessions ORDER BY id ASC", null)) {
            while (cursor.moveToNext()) {
                String raw = cursor.getString(0);
                if (raw == null || raw.isEmpty()) continue;
                try {
                    CalibrationStore.Session s = CalibrationStore.Session.fromJson(new JSONObject(raw));
                    if (s != null) out.add(s);
                } catch (Exception ignored) { }
            }
        }
        return out;
    }

    void appendSession(CalibrationStore.Session s) {
        if (s == null) return;
        SQLiteDatabase db = sessionDatabase();
        db.beginTransactionNonExclusive();
        try {
            insertSession(db, s);
            db.execSQL("DELETE FROM sessions WHERE id NOT IN (SELECT id FROM sessions ORDER BY id DESC LIMIT " + MAX_SESSIONS + ")");
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    void clearSessions() {
        SQLiteDatabase db = sessionDatabase();
        db.delete(SESSIONS_TABLE, null, null);
        context.getSharedPreferences(CalibrationStore.PREFS, 0).edit()
                .remove(SESSIONS_KEY).remove(LEGACY_CALIBRATIONS)
                .putBoolean(SESSIONS_MIGRATED, true).apply();
    }

    private static TrendPoint fromCursor(Cursor c) {
        return new TrendPoint(c.getLong(0), c.getLong(1), c.getInt(2),
                c.isNull(3) ? Double.NaN : c.getDouble(3),
                c.isNull(4) ? Double.NaN : c.getDouble(4),
                c.isNull(5) ? Double.NaN : c.getDouble(5), c.getInt(6) != 0,
                c.getInt(7) != 0, !c.isNull(7), c.getInt(8) != 0);
    }
}
