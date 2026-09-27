package com.darren.batterylens;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Serialized storage queue. The main thread only collects sensor snapshots and renders results. */
final class TrendStore {
    static final long RETENTION_MS = 7 * 24 * 60 * 60_000L;
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "battery-trend-storage"); t.setDaemon(true); return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static TrendRepository repository; // Worker-thread only.
    private static TrendPoint last; // Worker-thread only.
    private static volatile long revision;

    interface PointsCallback { void onPoints(List<TrendPoint> points, long revision); }
    interface InsightsCallback { void onInsights(InsightAnalysis analysis, long revision); }
    interface ClearCallback { void onCleared(boolean success); }

    private static TrendRepository repository(Context c) {
        if (repository == null) repository = new TrendRepository(c.getApplicationContext());
        return repository;
    }

    static long revision() { return revision; }

    static void observe(Context c, BatteryReadings r) {
        if (r == null) return;
        Context app = c.getApplicationContext();
        long now = System.currentTimeMillis(), elapsed = SystemClock.elapsedRealtime();
        int boot = Settings.Global.getInt(c.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        PowerManager power = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        boolean interactive = power != null && power.isInteractive();
        boolean hotspot = c.getSharedPreferences(CalibrationStore.PREFS, 0)
                .getBoolean("hotspot_marker", false);
        WORKER.execute(() -> {
            try {
                TrendRepository db = repository(app);
                if (last == null) last = db.latest();
                TrendPoint point = new TrendPoint(now, elapsed, boot, r.soc, r.currentNowMa(),
                        r.temperatureC(), r.isPlugged(), interactive, power != null, hotspot);
                if (!TrendSamplingPolicy.shouldRecord(last, point)) return;
                db.add(point, now - RETENTION_MS, now);
                last = point;
                revision++;
            } catch (RuntimeException ignored) { last = null; }
        });
    }

    static void requestPoints(Context c, long windowMs, PointsCallback callback) {
        Context app = c.getApplicationContext();
        WORKER.execute(() -> {
            List<TrendPoint> points;
            try { points = repository(app).points(System.currentTimeMillis(), Math.min(windowMs, RETENTION_MS)); }
            catch (RuntimeException ignored) { points = Collections.emptyList(); }
            final List<TrendPoint> result = points;
            long snapshotRevision = revision;
            MAIN.post(() -> callback.onPoints(result, snapshotRevision));
        });
    }

    static void requestInsights(Context c, InsightsCallback callback) {
        Context app = c.getApplicationContext();
        WORKER.execute(() -> {
            long now = System.currentTimeMillis();
            InsightAnalysis analysis;
            try { analysis = new InsightAnalysis(repository(app).points(now, 24 * 3_600_000L), now); }
            catch (RuntimeException ignored) { analysis = new InsightAnalysis(Collections.emptyList(), now); }
            InsightAnalysis result = analysis;
            long snapshotRevision = revision;
            MAIN.post(() -> callback.onInsights(result, snapshotRevision));
        });
    }

    static void clear(Context c, ClearCallback callback) {
        Context app = c.getApplicationContext();
        WORKER.execute(() -> {
            boolean success;
            try { repository(app).clear(); last = null; revision++; success = true; }
            catch (RuntimeException ignored) { success = false; }
            boolean cleared = success;
            MAIN.post(() -> callback.onCleared(cleared));
        });
    }
}
