package com.darren.batterylens;

import android.content.Context;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import java.util.Collections;
import java.util.List;

/** Serialized storage queue with fast in-memory throttling before touching IPC or SQLite. */
final class TrendStore {
    static final long RETENTION_MS = 7 * 24 * 60 * 60_000L;
    private static TrendRepository repository; // AppWorker-only.
    private static TrendPoint last; // AppWorker-only.
    private static volatile long revision;

    // Fast-path filter fields to avoid Binder IPC and Runnable allocations between 5-minute samples.
    private static volatile long fastLastElapsed = Long.MIN_VALUE;
    private static volatile boolean fastLastPlugged;
    private static volatile boolean fastLastInteractive;
    private static volatile boolean fastLastHotspot;
    private static volatile int cachedBootCount = Integer.MIN_VALUE;

    interface PointsCallback { void onPoints(List<TrendPoint> points, long revision); }
    interface InsightsCallback { void onInsights(InsightAnalysis analysis, long revision); }
    interface ClearCallback { void onCleared(boolean success); }

    static TrendRepository repository(Context c) {
        if (repository == null) repository = new TrendRepository(c.getApplicationContext());
        return repository;
    }

    static long revision() { return revision; }

    static int bootCount(Context c) {
        int b = cachedBootCount;
        if (b != Integer.MIN_VALUE) return b;
        b = Settings.Global.getInt(c.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        cachedBootCount = b;
        return b;
    }

    static void observe(Context c, BatteryReadings r) {
        if (r == null) return;
        PowerManager power = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        boolean screenKnown = power != null;
        boolean interactive = power != null && power.isInteractive();
        boolean hotspot = c.getSharedPreferences(CalibrationStore.PREFS, 0)
                .getBoolean("hotspot_marker", false);
        observe(c, r, screenKnown, interactive, hotspot);
    }

    static void observe(Context c, BatteryReadings r, boolean screenKnown, boolean interactive, boolean hotspot) {
        if (r == null) return;
        long elapsed = SystemClock.elapsedRealtime();
        boolean plugged = r.isPlugged();
        long prevElapsed = fastLastElapsed;
        if (prevElapsed != Long.MIN_VALUE
                && elapsed >= prevElapsed
                && elapsed - prevElapsed < TrendSamplingPolicy.SAMPLE_MS
                && plugged == fastLastPlugged
                && interactive == fastLastInteractive
                && hotspot == fastLastHotspot) {
            return;
        }

        Context app = c.getApplicationContext();
        long now = System.currentTimeMillis();
        int boot = bootCount(app);
        TrendPoint point = new TrendPoint(now, elapsed, boot, r.soc, r.currentNowMa(),
                r.temperatureC(), plugged, interactive, screenKnown, hotspot);
        AppWorker.execute(() -> observeOnWorker(app, point));
    }

    /** Worker-thread entrypoint for callers already executing on AppWorker. */
    static void observeOnWorker(Context app, TrendPoint point) {
        if (point == null) return;
        try {
            TrendRepository db = repository(app);
            if (last == null) last = db.latest();
            if (!TrendSamplingPolicy.shouldRecord(last, point)) {
                if (last != null) syncFastFilter(last);
                return;
            }
            db.add(point, point.time - RETENTION_MS, point.time);
            last = point;
            syncFastFilter(point);
            revision++;
        } catch (RuntimeException ignored) {
            last = null;
            fastLastElapsed = Long.MIN_VALUE;
        }
    }

    private static void syncFastFilter(TrendPoint p) {
        fastLastElapsed = p.elapsed;
        fastLastPlugged = p.plugged;
        fastLastInteractive = p.interactive;
        fastLastHotspot = p.hotspotMarked;
    }

    static void requestPoints(Context c, long windowMs, PointsCallback callback) {
        Context app = c.getApplicationContext();
        AppWorker.execute(() -> {
            List<TrendPoint> points;
            try { points = repository(app).points(System.currentTimeMillis(), Math.min(windowMs, RETENTION_MS)); }
            catch (RuntimeException ignored) { points = Collections.emptyList(); }
            final List<TrendPoint> result = points;
            long snapshotRevision = revision;
            AppWorker.postMain(() -> callback.onPoints(result, snapshotRevision));
        });
    }

    static void requestInsights(Context c, InsightsCallback callback) {
        Context app = c.getApplicationContext();
        AppWorker.execute(() -> {
            long now = System.currentTimeMillis();
            InsightAnalysis analysis;
            try { analysis = new InsightAnalysis(repository(app).points(now, 24 * 3_600_000L), now); }
            catch (RuntimeException ignored) { analysis = new InsightAnalysis(Collections.emptyList(), now); }
            InsightAnalysis result = analysis;
            long snapshotRevision = revision;
            AppWorker.postMain(() -> callback.onInsights(result, snapshotRevision));
        });
    }

    static void clear(Context c, ClearCallback callback) {
        Context app = c.getApplicationContext();
        AppWorker.execute(() -> {
            boolean success;
            try {
                repository(app).clear();
                last = null;
                fastLastElapsed = Long.MIN_VALUE;
                revision++;
                success = true;
            } catch (RuntimeException ignored) {
                success = false;
            }
            boolean cleared = success;
            AppWorker.postMain(() -> callback.onCleared(cleared));
        });
    }
}
