package com.darren.batterylens;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.os.Build;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

final class CalibrationStore {
    static final String PREFS = "battery_lens";
    static final String RUNTIME_PREFS = "battery_runtime";
    static final String KEY_DESIGN = "design_capacity";
    static final String KEY_AUTO = "auto_calibration";
    private static final String KEY_ACTIVE = "active_calibration_v2";
    private static final String KEY_LAST_NOTE = "last_calibration_note";
    private static final String KEY_LIMIT_HOLD = "charge_limit_hold_v4";
    private static final String KEY_RUNTIME_MIGRATED = "runtime_migrated_v1";
    private static final String KEY_LAST_EXIT = "last_exit_note";
    private static final String KEY_LAST_EXIT_TS = "last_exit_ts";
    private static final String KEY_EST_FCC = "cached_est_fcc";
    private static final String KEY_EST_USED = "cached_est_used";
    private static final String KEY_EST_CONF = "cached_est_conf";
    private static final String KEY_EST_MAD = "cached_est_mad";

    interface HistoryCallback {
        void onHistory(List<Session> sessions, Estimate estimate, long revision);
    }

    interface ActionCallback {
        void onDone(boolean changed, String note);
    }

    private static volatile boolean runtimeLoaded;
    private static volatile boolean runtimeMigrated;
    private static volatile boolean activeFlag;
    private static volatile String activeSourceCache = "";
    private static volatile double activeStartSocCache = -1;
    private static volatile double activePeakSocCache = -1;
    private static volatile boolean activePausedCache;
    private static volatile long activeStartTimeCache;
    private static volatile float observedCyclesCache;
    private static volatile long observedSinceCache;
    private static volatile String lastNoteCache = "Automatic monitoring is enabled.";
    private static volatile long sessionsRevision = 1;
    private static volatile Estimate estimateCache = new Estimate(null, 0, "Low", Double.NaN);

    private static JSONObject activeObjCache;
    private static boolean limitHoldCache;
    private static float cyclePrevSoc = -1f;
    private static boolean cyclePrevPresent;
    private static long cycleElapsed;
    private static int cycleBoot = -2;
    private static boolean cyclePlugged = true;

    private static List<Session> sessionsCache;
    private static long sessionsCacheRevision = -1;

    static final class Session {
        long startTime;
        long endTime;
        double startSoc;
        double endSoc;
        long startUah;
        long endUah;
        double startTemp;
        double endTemp;
        double fccMah;
        String source;
        String quality;
        boolean used;
        int samples;
        double minTemp = Double.NaN;
        double maxTemp = Double.NaN;
        String reason = "";
        double endpointFccMah = Double.NaN;
        double fitSpread = Double.NaN;
        int fitPairs;
        int reversals;
        long pausedMs;
        long chargeDurationMs;
        boolean hotspotMarked;
        int plugType, startVoltageMv, endVoltageMv;

        double deltaSoc() { return endSoc - startSoc; }
        long durationMs() { return chargeDurationMs > 0 ? chargeDurationMs : Math.max(0, endTime - startTime); }

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("st", startTime);
                o.put("et", endTime);
                o.put("ss", startSoc);
                o.put("es", endSoc);
                o.put("su", startUah);
                o.put("eu", endUah);
                putFinite(o, "stp", startTemp);
                putFinite(o, "etp", endTemp);
                putFinite(o, "fcc", fccMah);
                o.put("src", source);
                o.put("q", quality);
                o.put("used", used);
                o.put("v", 4);
                o.put("samples", samples);
                putFinite(o, "minTemp", minTemp);
                putFinite(o, "maxTemp", maxTemp);
                o.put("reason", reason);
                putFinite(o, "rawFcc", endpointFccMah);
                putFinite(o, "spread", fitSpread);
                o.put("pairs", fitPairs);
                o.put("reversals", reversals);
                o.put("pausedMs", pausedMs);
                o.put("chargeMs", chargeDurationMs);
                o.put("hotspot", hotspotMarked);
                o.put("plug", plugType);
                o.put("startMv", startVoltageMv);
                o.put("endMv", endVoltageMv);
            } catch (Exception ignored) {}
            return o;
        }

        static Session fromJson(JSONObject o) {
            if (o == null) return null;
            Session s = new Session();
            s.startTime = o.optLong("st", 0);
            s.endTime = o.optLong("et", 0);
            s.startSoc = o.optDouble("ss", -1);
            s.endSoc = o.optDouble("es", -1);
            s.startUah = o.optLong("su", Long.MIN_VALUE);
            s.endUah = o.optLong("eu", Long.MIN_VALUE);
            s.startTemp = o.optDouble("stp", Double.NaN);
            s.endTemp = o.optDouble("etp", Double.NaN);
            s.fccMah = o.optDouble("fcc", Double.NaN);
            s.source = o.optString("src", "auto");
            s.quality = o.optString("q", "Unknown");
            s.used = o.optInt("v", 0) >= 4 && o.optBoolean("used", false);
            s.samples = o.optInt("samples", 0);
            s.minTemp = o.optDouble("minTemp", Double.NaN);
            s.maxTemp = o.optDouble("maxTemp", Double.NaN);
            s.reason = o.optInt("v", 0) < 3 ? "Imported; collect a monitored session"
                    : o.optInt("v", 0) < 4 && o.optBoolean("used", false)
                    ? "Earlier endpoint estimate; collect a new monitored session" : o.optString("reason", "");
            s.endpointFccMah = o.optDouble("rawFcc", Double.NaN);
            s.fitSpread = o.optDouble("spread", Double.NaN);
            s.fitPairs = o.optInt("pairs", 0);
            s.reversals = o.optInt("reversals", 0);
            s.pausedMs = o.optLong("pausedMs", 0);
            s.chargeDurationMs = o.optLong("chargeMs", 0);
            s.hotspotMarked = o.optBoolean("hotspot", false);
            s.plugType = o.optInt("plug", 0);
            s.startVoltageMv = o.optInt("startMv", 0);
            s.endVoltageMv = o.optInt("endMv", 0);
            return s;
        }
    }

    static final class Estimate {
        final Double fccMah;
        final int usedCount;
        final String confidence;
        final double relativeMad;

        Estimate(Double fccMah, int usedCount, String confidence, double relativeMad) {
            this.fccMah = fccMah;
            this.usedCount = usedCount;
            this.confidence = confidence;
            this.relativeMad = relativeMad;
        }
    }

    private static void putFinite(JSONObject o, String key, double value) throws org.json.JSONException {
        if (Double.isFinite(value)) o.put(key, value);
        else o.remove(key);
    }

    static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static SharedPreferences runtimePrefs(Context c) {
        Context app = c.getApplicationContext();
        SharedPreferences rt = app.getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE);
        if (!runtimeMigrated) {
            SharedPreferences cold = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            if (!cold.getBoolean(KEY_RUNTIME_MIGRATED, false)) {
                migrateRuntimeKeys(cold, rt);
            }
            runtimeMigrated = true;
        }
        return rt;
    }

    private static synchronized void migrateRuntimeKeys(SharedPreferences cold, SharedPreferences rt) {
        if (cold.getBoolean(KEY_RUNTIME_MIGRATED, false)) return;
        SharedPreferences.Editor rtEdit = rt.edit();
        SharedPreferences.Editor coldEdit = cold.edit();
        String[] strings = {KEY_ACTIVE, KEY_LAST_NOTE, "alert_configuration"};
        for (String k : strings) {
            if (cold.contains(k)) { rtEdit.putString(k, cold.getString(k, "")); coldEdit.remove(k); }
        }
        String[] bools = {KEY_LIMIT_HOLD, "cycle_plugged", "alert_target_sent", "alert_heat_sent", "alert_low_sent"};
        for (String k : bools) {
            if (cold.contains(k)) { rtEdit.putBoolean(k, cold.getBoolean(k, false)); coldEdit.remove(k); }
        }
        String[] floats = {"observed_cycles", "cycle_previous_soc"};
        for (String k : floats) {
            if (cold.contains(k)) { rtEdit.putFloat(k, cold.getFloat(k, 0f)); coldEdit.remove(k); }
        }
        if (cold.contains("cycle_elapsed")) { rtEdit.putLong("cycle_elapsed", cold.getLong("cycle_elapsed", 0)); coldEdit.remove("cycle_elapsed"); }
        if (cold.contains("cycle_boot")) { rtEdit.putInt("cycle_boot", cold.getInt("cycle_boot", -2)); coldEdit.remove("cycle_boot"); }
        rtEdit.apply();
        coldEdit.putBoolean(KEY_RUNTIME_MIGRATED, true).apply();
    }

    private static void ensureRuntimeLoaded(Context c) {
        if (runtimeLoaded) return;
        synchronized (CalibrationStore.class) {
            if (runtimeLoaded) return;
            SharedPreferences rt = runtimePrefs(c);
            String rawActive = rt.getString(KEY_ACTIVE, "");
            if (rawActive != null && !rawActive.isEmpty()) {
                try { activeObjCache = new JSONObject(rawActive); } catch (Exception ignored) { activeObjCache = null; }
            } else {
                activeObjCache = null;
            }
            limitHoldCache = rt.getBoolean(KEY_LIMIT_HOLD, false);
            observedCyclesCache = rt.getFloat("observed_cycles", 0f);
            observedSinceCache = prefs(c).getLong("observed_since", 0L);
            lastNoteCache = rt.getString(KEY_LAST_NOTE, "Automatic monitoring is ready.");
            cyclePrevPresent = rt.contains("cycle_previous_soc");
            cyclePrevSoc = rt.getFloat("cycle_previous_soc", -1f);
            cycleElapsed = rt.getLong("cycle_elapsed", 0L);
            cycleBoot = rt.getInt("cycle_boot", -2);
            cyclePlugged = rt.getBoolean("cycle_plugged", true);
            estimateCache = readEstimateCache(rt);
            syncVolatileActive();
            runtimeLoaded = true;
        }
    }

    private static Estimate readEstimateCache(SharedPreferences rt) {
        float fcc = rt.getFloat(KEY_EST_FCC, -1f);
        int used = rt.getInt(KEY_EST_USED, 0);
        String conf = rt.getString(KEY_EST_CONF, "Low");
        float mad = rt.getFloat(KEY_EST_MAD, Float.NaN);
        Double fccVal = fcc > 0 ? (double) fcc : null;
        return new Estimate(fccVal, used, conf != null ? conf : "Low", Float.isFinite(mad) ? (double) mad : Double.NaN);
    }

    private static void saveEstimateCache(Context c, Estimate est) {
        if (est == null) return;
        estimateCache = est;
        SharedPreferences.Editor ed = runtimePrefs(c).edit()
                .putInt(KEY_EST_USED, est.usedCount)
                .putString(KEY_EST_CONF, est.confidence != null ? est.confidence : "Low");
        if (est.fccMah != null && Double.isFinite(est.fccMah)) {
            ed.putFloat(KEY_EST_FCC, est.fccMah.floatValue());
        } else {
            ed.remove(KEY_EST_FCC);
        }
        if (Double.isFinite(est.relativeMad)) {
            ed.putFloat(KEY_EST_MAD, (float) est.relativeMad);
        } else {
            ed.remove(KEY_EST_MAD);
        }
        ed.apply();
    }

    /** Fast non-SQLite health estimate cache suitable for home-screen widgets. */
    static Estimate cachedEstimate(Context c) {
        ensureRuntimeLoaded(c);
        return estimateCache;
    }

    /** Populates the lightweight widget health cache once on AppWorker if never stored yet. */
    static Estimate ensureEstimateCachedOnWorker(Context c) {
        ensureRuntimeLoaded(c);
        if (!runtimePrefs(c).contains(KEY_EST_USED)) {
            saveEstimateCache(c, estimateFromSessions(loadSessionsOnWorker(c), designCapacity(c)));
        }
        return estimateCache;
    }

    private static void syncVolatileActive() {
        JSONObject o = activeObjCache;
        activeFlag = o != null;
        activeSourceCache = o == null ? "" : o.optString("src", "");
        activeStartSocCache = o == null ? -1 : o.optDouble("soc", -1);
        activePeakSocCache = o == null ? -1 : o.optDouble("lastSoc", -1);
        activePausedCache = o != null && o.optLong("pauseStart", 0) > 0;
        activeStartTimeCache = o == null ? 0 : o.optLong("time", 0);
    }

    static boolean isAutoEnabled(Context c) {
        return prefs(c).getBoolean(KEY_AUTO, true);
    }

    static synchronized void setAutoEnabled(Context c, boolean enabled) {
        prefs(c).edit().putBoolean(KEY_AUTO, enabled).apply();
    }

    /** Marks automatic mode disabled immediately and finishes any active auto checkpoint on AppWorker. */
    static void disableAutoAsync(Context c, String endReason) {
        disableAutoAsync(c, endReason, null);
    }

    static void disableAutoAsync(Context c, String endReason, Runnable onDone) {
        Context app = c.getApplicationContext();
        prefs(app).edit().putBoolean(KEY_AUTO, false).apply();
        AppWorker.execute(() -> {
            finishCheckpointBySourceOnWorker(app, "auto", endReason);
            if (onDone != null) AppWorker.postMain(onDone);
        });
    }

    static float designCapacity(Context c) {
        return prefs(c).getFloat(KEY_DESIGN, 0f);
    }

    static void setDesignCapacity(Context c, float value) {
        Context app = c.getApplicationContext();
        prefs(app).edit().putFloat(KEY_DESIGN, value).apply();
        AppWorker.execute(() -> saveEstimateCache(app, estimateFromSessions(loadSessionsOnWorker(app), value)));
    }

    static List<Session> parseLegacyString(String legacy) {
        List<Session> list = new ArrayList<>();
        if (legacy == null || legacy.trim().isEmpty()) return list;
        String[] parts = legacy.split(",");
        long now = System.currentTimeMillis();
        int offset = parts.length;
        for (String part : parts) {
            try {
                double fcc = Double.parseDouble(part.trim());
                if (!Double.isFinite(fcc) || fcc < 500 || fcc > 20000) continue;
                Session s = new Session();
                s.startTime = now - (long) offset * 86_400_000L;
                s.endTime = s.startTime;
                s.startSoc = 0;
                s.endSoc = 100;
                s.startUah = 0;
                s.endUah = Math.round(fcc * 1000);
                s.startTemp = Double.NaN;
                s.endTemp = Double.NaN;
                s.fccMah = fcc;
                s.source = "legacy";
                s.quality = "Imported";
                s.used = false;
                s.reason = "Imported; collect a monitored session";
                list.add(s);
                offset--;
            } catch (Exception ignored) {}
        }
        return list;
    }

    static boolean hasActive(Context c) {
        ensureRuntimeLoaded(c);
        return activeFlag;
    }

    /** Fast non-blocking check using in-memory state after initial load. */
    static boolean hasActiveFast(Context c) {
        if (!runtimeLoaded) ensureRuntimeLoaded(c);
        return activeFlag;
    }

    static String activeSource(Context c) {
        ensureRuntimeLoaded(c);
        return activeSourceCache;
    }

    static double activeStartSoc(Context c) {
        ensureRuntimeLoaded(c);
        return activeStartSocCache;
    }

    static double activePeakSoc(Context c) {
        ensureRuntimeLoaded(c);
        return activePeakSocCache;
    }

    static boolean activePaused(Context c) {
        ensureRuntimeLoaded(c);
        return activePausedCache;
    }

    static long activeStartTime(Context c) {
        ensureRuntimeLoaded(c);
        return activeStartTimeCache;
    }

    static int chosenChargeLimit(Context c) {
        return prefs(c).getInt("measurement_charge_limit", 0);
    }

    static int learnedChargeLimit(Context c) {
        return prefs(c).getInt("learned_charge_limit", 0);
    }

    private static int observedChargeLimit(Context c, double peakSoc, boolean stopped) {
        int selected = chosenChargeLimit(c);
        int recognized = MeasurementRules.pausedLimit(selected, peakSoc, stopped);
        return recognized >= 0 || selected != 0 ? recognized
                : MeasurementRules.pausedLimit(learnedChargeLimit(c), peakSoc, stopped);
    }

    static long sessionsRevision() { return sessionsRevision; }

    static void trimMemory() {
        sessionsCache = null;
        sessionsCacheRevision = -1;
    }

    static void requestHistoryAsync(Context c, HistoryCallback callback) {
        Context app = c.getApplicationContext();
        AppWorker.execute(() -> {
            List<Session> list = loadSessionsOnWorker(app);
            Estimate est = estimateFromSessions(list, designCapacity(app));
            saveEstimateCache(app, est);
            long rev = sessionsRevision;
            AppWorker.postMain(() -> callback.onHistory(list, est, rev));
        });
    }

    private static List<Session> loadSessionsOnWorker(Context app) {
        long rev = sessionsRevision;
        if (sessionsCache != null && sessionsCacheRevision == rev) {
            return sessionsCache;
        }
        List<Session> loaded;
        try {
            loaded = Collections.unmodifiableList(TrendStore.repository(app).loadSessions());
        } catch (RuntimeException ignored) {
            loaded = Collections.emptyList();
        }
        sessionsCache = loaded;
        sessionsCacheRevision = rev;
        return loaded;
    }

    static void startSessionAsync(Context c, String source, ActionCallback callback) {
        Context app = c.getApplicationContext();
        AppWorker.execute(() -> {
            BatteryReadings r = BatteryReadings.read(app);
            boolean started = startSession(app, source, r);
            String note = lastNote(app);
            if (callback != null) AppWorker.postMain(() -> callback.onDone(started, note));
        });
    }

    static void finishSessionAsync(Context c, ActionCallback callback) {
        Context app = c.getApplicationContext();
        AppWorker.execute(() -> {
            Session finished = finishSessionOnWorker(app);
            String note = lastNote(app);
            if (callback != null) AppWorker.postMain(() -> callback.onDone(finished != null, note));
        });
    }

    static void finishCheckpointAsync(Context c, String endReason) {
        finishCheckpointAsync(c, endReason, null);
    }

    static void finishCheckpointAsync(Context c, String endReason, Runnable onDone) {
        Context app = c.getApplicationContext();
        AppWorker.execute(() -> {
            finishCheckpointOnWorker(app, endReason);
            if (onDone != null) AppWorker.postMain(onDone);
        });
    }

    static void clearHistoryAsync(Context c, ActionCallback callback) {
        Context app = c.getApplicationContext();
        AppWorker.execute(() -> {
            clearHistoryOnWorker(app);
            String note = lastNote(app);
            if (callback != null) AppWorker.postMain(() -> callback.onDone(true, note));
        });
    }

    static void reconcileAutoAsync(Context c) {
        Context app = c.getApplicationContext();
        AppWorker.execute(() -> observeOnWorker(app, BatteryReadings.read(app)));
    }

    static void onBootAsync(Context c) {
        Context app = c.getApplicationContext();
        AppWorker.execute(() -> onBootOnWorker(app));
    }

    static synchronized boolean startSession(Context c, String source) {
        return startSession(c, source, BatteryReadings.read(c));
    }

    private static synchronized boolean startSession(Context c, String source, BatteryReadings r) {
        ensureRuntimeLoaded(c);
        if (activeObjCache != null) return false;
        if (r == null || !r.hasUsableCounter() || !MeasurementRules.validSoc(r.soc) || !r.isPlugged()) {
            setLastNote(c, "Waiting for a charger and a usable charge counter. Check compatibility if this persists.");
            return false;
        }
        boolean stopped = r.status == BatteryManager.BATTERY_STATUS_NOT_CHARGING
                || r.status == BatteryManager.BATTERY_STATUS_FULL;
        if (limitHoldCache || observedChargeLimit(c, r.soc, stopped) >= 0) {
            setLastNote(c, "Charging is paused near its limit. Unplug before starting another session.");
            return false;
        }
        JSONObject o = new JSONObject();
        try {
            o.put("time", System.currentTimeMillis());
            o.put("soc", r.soc);
            o.put("uah", r.chargeUah);
            o.put("plug", r.plugged);
            o.put("startMv", r.voltageMv);
            putFinite(o, "temp", r.temperatureC());
            putFinite(o, "minTemp", r.temperatureC());
            putFinite(o, "maxTemp", r.temperatureC());
            o.put("src", source);
            o.put("samples", 0);
            o.put("startElapsed", SystemClock.elapsedRealtime());
            o.put("steps", new JSONArray().put(new JSONArray().put(r.soc).put(r.chargeUah)));
            checkpoint(c, o, r);
        } catch (Exception ignored) { return false; }
        setLastNote(c, "Recording from " + Math.round(r.soc) + "%. Gain at least 30% for an estimate.");
        return true;
    }

    /** Executes checkpoint & cycle accounting on AppWorker (off the main thread). */
    static synchronized void observeOnWorker(Context c, BatteryReadings r) {
        if (r == null || !MeasurementRules.validSoc(r.soc)) return;
        ensureRuntimeLoaded(c);
        recordObservedCycles(c, r);
        if (!r.isPlugged() && limitHoldCache) {
            limitHoldCache = false;
            runtimePrefs(c).edit().remove(KEY_LIMIT_HOLD).apply();
        }
        JSONObject o = activeObjCache;
        boolean finishedNow = false;
        if (o != null) {
            long now = SystemClock.elapsedRealtime();
            int currentBoot = TrendStore.bootCount(c);
            int savedBoot = o.optInt("boot", -1);
            boolean rebooted = savedBoot >= 0 && currentBoot >= 0 && savedBoot != currentBoot;
            boolean continuous = o.has("lastElapsed") && MeasurementRules.continuous(
                    o.optLong("lastElapsed"), now, savedBoot, currentBoot);
            if (!continuous || o.optLong("progressElapsed", now) - o.optLong("startElapsed", now)
                    > MeasurementRules.MAX_SESSION_MS) {
                finishCheckpointOnWorker(c, rebooted
                        ? "Phone restarted; saved last observed segment"
                        : "Monitoring interrupted; saved last observed segment");
                finishedNow = true;
            } else if (!r.isPlugged()) {
                finishCheckpointOnWorker(c, "Charger disconnected");
                finishedNow = true;
            } else if (!r.hasUsableCounter()) {
                finishCheckpointOnWorker(c, "Charge counter became unavailable");
                finishedNow = true;
            } else {
                checkpoint(c, o, r);
                if (o.optBoolean("limitPaused")) {
                    finishCheckpointOnWorker(c, "Charging paused at protection limit");
                    finishedNow = true;
                }
            }
        }
        if (r.isPlugged() && activeObjCache == null && !finishedNow && isAutoEnabled(c)) {
            startSession(c, "auto", r);
        }
    }

    private static void checkpoint(Context c, JSONObject o, BatteryReadings r) {
        try {
            if (prefs(c).getBoolean("hotspot_marker", false)) o.put("hotspot", true);
            long elapsed = SystemClock.elapsedRealtime();
            boolean first = !o.has("lastElapsed");
            boolean progress = first || MeasurementRules.forwardProgress(
                    o.optDouble("lastSoc", -1), o.optLong("lastUah", 0), r.soc, r.chargeUah);
            if (first || elapsed - o.optLong("countedElapsed", 0) >= 30_000 || progress) {
                o.put("samples", o.optInt("samples") + 1);
                o.put("countedElapsed", elapsed);
            }
            if (progress) {
                if (o.has("pauseStart")) {
                    o.put("pausedAccumulated", o.optLong("pausedAccumulated", 0)
                            + Math.max(0, elapsed - o.optLong("pauseStart", elapsed)));
                    o.remove("pauseStart");
                }
                o.put("lastTime", System.currentTimeMillis());
                o.put("progressElapsed", elapsed);
                o.put("lastSoc", r.soc);
                o.put("lastUah", r.chargeUah);
                o.put("endMv", r.voltageMv);
                putFinite(o, "lastTemp", r.temperatureC());
                JSONArray steps = o.optJSONArray("steps");
                if (steps == null) steps = new JSONArray();
                JSONArray last = steps.optJSONArray(steps.length() - 1);
                if (last == null || r.soc >= last.optDouble(0, -1) + CapacityEstimator.BIN_WIDTH) {
                    steps.put(new JSONArray().put(r.soc).put(r.chargeUah));
                }
                o.put("steps", steps);
                double t = r.temperatureC();
                if (Double.isFinite(t)) {
                    o.put("minTemp", Math.min(o.optDouble("minTemp", t), t));
                    o.put("maxTemp", Math.max(o.optDouble("maxTemp", t), t));
                } else o.put("missingTemp", true);
                o.remove("unknownStopStart");
                o.remove("unknownStopCandidate");
            } else if (!first) {
                double previous = o.optDouble("observedSoc", r.soc);
                boolean stopped = r.status == BatteryManager.BATTERY_STATUS_NOT_CHARGING
                        || r.status == BatteryManager.BATTERY_STATUS_FULL;
                int limit = observedChargeLimit(c, o.optDouble("lastSoc", -1), stopped);
                if (r.soc < o.optDouble("lastSoc", -1) - 1) limit = -1;
                if (r.soc <= previous - 1 && limit < 0) {
                    o.put("reversals", o.optInt("reversals") + 1);
                }
                if (limit >= 0) {
                    o.put("pausedLimit", limit);
                    if (!o.has("pauseStart")) o.put("pauseStart", elapsed);
                } else o.remove("pauseStart");
                if (limit < 0 && chosenChargeLimit(c) == 0 && stopped
                        && o.optDouble("lastSoc", -1) >= 50 && o.optDouble("lastSoc", -1) < 100
                        && Math.abs(r.soc - o.optDouble("lastSoc", -1)) <= 1) {
                    if (!o.has("unknownStopStart")) o.put("unknownStopStart", elapsed);
                    if (elapsed - o.optLong("unknownStopStart") >= 10 * 60_000L)
                        o.put("unknownStopCandidate", Math.round((float) o.optDouble("lastSoc")));
                } else {
                    o.remove("unknownStopStart");
                    o.remove("unknownStopCandidate");
                }
            }
            o.put("observedSoc", r.soc);
            o.put("lastElapsed", elapsed);
            o.put("boot", TrendStore.bootCount(c));
            if (o.has("pauseStart") && elapsed - o.optLong("pauseStart") >= 10 * 60_000L)
                o.put("limitPaused", true);
            activeObjCache = o;
            syncVolatileActive();
            runtimePrefs(c).edit().putString(KEY_ACTIVE, o.toString()).apply();
        } catch (Exception ignored) { setLastNote(c, "Could not save this battery observation."); }
    }

    private static synchronized Session finishSessionOnWorker(Context c) {
        ensureRuntimeLoaded(c);
        long originalStart = activeStartTimeCache;
        BatteryReadings r = BatteryReadings.read(c);
        if (r != null) observeOnWorker(c, r);
        return activeObjCache != null && activeStartTimeCache == originalStart
                ? finishCheckpointOnWorker(c, "Finished by you") : null;
    }

    static synchronized Session finishCheckpointBySourceOnWorker(Context c, String source, String endReason) {
        ensureRuntimeLoaded(c);
        if (activeObjCache == null || !source.equals(activeSourceCache)) return null;
        return finishCheckpointOnWorker(c, endReason);
    }

    static synchronized Session finishCheckpointOnWorker(Context c, String endReason) {
        ensureRuntimeLoaded(c);
        JSONObject active = activeObjCache;
        if (active == null) return null;
        Session s = new Session();
        s.startTime = active.optLong("time", 0);
        s.endTime = active.optLong("lastTime", s.startTime);
        s.startSoc = active.optDouble("soc", -1);
        s.endSoc = active.optDouble("lastSoc", s.startSoc);
        s.startUah = active.optLong("uah", 0);
        s.endUah = active.optLong("lastUah", s.startUah);
        s.startTemp = active.optDouble("temp", Double.NaN);
        s.endTemp = active.optDouble("lastTemp", Double.NaN);
        s.minTemp = active.optDouble("minTemp", Double.NaN);
        s.maxTemp = active.optDouble("maxTemp", Double.NaN);
        s.samples = active.optInt("samples", 0);
        s.source = active.optString("src", "auto");
        s.endpointFccMah = MeasurementRules.capacityMah(s.startSoc, s.endSoc, s.startUah, s.endUah);
        JSONArray steps = active.optJSONArray("steps");
        List<MeasurementRules.ChargePoint> points = new ArrayList<>();
        if (steps != null) for (int i = 0; i < steps.length(); i++) {
            JSONArray pair = steps.optJSONArray(i);
            if (pair != null) points.add(new MeasurementRules.ChargePoint(pair.optDouble(0, -1), pair.optLong(1, 0)));
        }
        MeasurementRules.CapacityFit fit = MeasurementRules.fitCapacity(points);
        s.fccMah = fit == null ? Double.NaN : fit.mah;
        s.fitSpread = fit == null ? Double.NaN : fit.relativeSpread;
        s.fitPairs = fit == null ? 0 : fit.pairs;
        s.reversals = active.optInt("reversals", 0);
        s.pausedMs = active.optLong("pausedAccumulated", 0) + (active.has("pauseStart")
                ? Math.max(0, active.optLong("lastElapsed", 0) - active.optLong("pauseStart", 0)) : 0);
        long duration = active.optLong("progressElapsed", 0) - active.optLong("startElapsed", 0)
                - active.optLong("pausedAccumulated", 0);
        s.chargeDurationMs = Math.max(0, duration);
        s.hotspotMarked = active.optBoolean("hotspot", false);
        s.plugType = active.optInt("plug", 0);
        s.startVoltageMv = active.optInt("startMv", 0);
        s.endVoltageMv = active.optInt("endMv", 0);
        String rejection = s.deltaSoc() < 30 ? "Less than 30% gained"
                : MeasurementRules.fitRejection(fit, s.reversals);
        if (rejection.isEmpty()) rejection = MeasurementRules.rejection(s.fccMah, s.deltaSoc(), duration,
                active.optBoolean("missingTemp") ? Double.NaN : s.minTemp,
                s.maxTemp, designCapacity(c), s.samples);
        s.used = rejection.isEmpty();
        String pauseNote = active.optBoolean("limitPaused")
                ? " • charging paused near " + active.optInt("pausedLimit", Math.round((float) s.endSoc)) + "%" : "";
        s.reason = s.used ? endReason + pauseNote : rejection + " • " + endReason + pauseNote;
        s.quality = s.used ? qualityFor(s.startSoc, s.endSoc, s.deltaSoc()) : "History only";
        if ("Charger disconnected".equals(endReason) && chosenChargeLimit(c) == 0
                && s.used && active.has("unknownStopCandidate")) {
            int candidate = active.optInt("unknownStopCandidate", -1);
            SharedPreferences p = prefs(c);
            int count = MeasurementRules.nextCustomStopCount(candidate,
                    p.getInt("custom_stop_candidate", -1), p.getInt("custom_stop_count", 0));
            if (count > 0) p.edit().putInt("custom_stop_candidate", candidate)
                    .putInt("custom_stop_count", count)
                    .putInt("learned_charge_limit", count >= 2 ? candidate : learnedChargeLimit(c)).apply();
        }
        TrendStore.repository(c).appendSession(s);
        activeObjCache = null;
        syncVolatileActive();
        sessionsCache = null;
        sessionsRevision++;
        String note = s.used ? "Saved a " + Math.round(s.deltaSoc()) + "% segment for estimation. " + endReason
                : "Session saved as history: " + s.reason;
        lastNoteCache = note;
        SharedPreferences.Editor edit = runtimePrefs(c).edit().remove(KEY_ACTIVE).putString(KEY_LAST_NOTE, note);
        if (active.optBoolean("limitPaused") && !"Charger disconnected".equals(endReason)) {
            limitHoldCache = true;
            edit.putBoolean(KEY_LIMIT_HOLD, true);
        }
        edit.apply();
        if (s.used) {
            saveEstimateCache(c, estimateFromSessions(loadSessionsOnWorker(c), designCapacity(c)));
        }
        return s;
    }

    private static synchronized void onBootOnWorker(Context c) {
        ensureRuntimeLoaded(c);
        finishCheckpointOnWorker(c, "Phone restarted; saved last observed segment");
        cyclePrevPresent = false;
        cyclePrevSoc = -1f;
        runtimePrefs(c).edit().remove("cycle_previous_soc").apply();
        setLastNote(c, isAutoEnabled(c)
                ? "Phone restarted. Monitoring resumed automatically."
                : "Phone restarted. Enable monitoring to resume.");
    }

    private static void recordObservedCycles(Context c, BatteryReadings r) {
        long elapsed = SystemClock.elapsedRealtime();
        int boot = TrendStore.bootCount(c);
        boolean continuous = cyclePrevPresent && MeasurementRules.continuous(
                cycleElapsed, elapsed, cycleBoot, boot);
        double delta = MeasurementRules.observedDischarge(cyclePrevPresent ? cyclePrevSoc : -1f, r.soc,
                cyclePlugged, r.isPlugged(), continuous);
        float newSoc = (float) r.soc;
        boolean plugged = r.isPlugged();
        boolean shouldPersist = !continuous
                || delta > 0
                || !cyclePrevPresent
                || Math.abs(newSoc - cyclePrevSoc) >= 0.1f
                || plugged != cyclePlugged
                || elapsed - cycleElapsed >= 55_000L;
        if (!shouldPersist) return;

        observedCyclesCache = observedCyclesCache + (float) delta;
        cyclePrevPresent = true;
        cyclePrevSoc = newSoc;
        cycleElapsed = elapsed;
        cycleBoot = boot;
        cyclePlugged = plugged;

        SharedPreferences.Editor edit = runtimePrefs(c).edit()
                .putFloat("observed_cycles", observedCyclesCache)
                .putFloat("cycle_previous_soc", newSoc)
                .putLong("cycle_elapsed", elapsed)
                .putInt("cycle_boot", boot)
                .putBoolean("cycle_plugged", plugged);
        if (observedSinceCache <= 0) {
            long since = System.currentTimeMillis();
            observedSinceCache = since;
            prefs(c).edit().putLong("observed_since", since).apply();
        }
        edit.apply();
    }

    static synchronized void breakCycleContinuity(Context c) {
        ensureRuntimeLoaded(c);
        if (!cyclePrevPresent) return;
        cyclePrevPresent = false;
        cyclePrevSoc = -1f;
        runtimePrefs(c).edit().remove("cycle_previous_soc").apply();
    }

    static float observedCycles(Context c) {
        ensureRuntimeLoaded(c);
        return observedCyclesCache;
    }

    static long observedSince(Context c) {
        ensureRuntimeLoaded(c);
        return observedSinceCache;
    }

    private static synchronized void clearHistoryOnWorker(Context c) {
        ensureRuntimeLoaded(c);
        TrendStore.repository(c).clearSessions();
        activeObjCache = null;
        syncVolatileActive();
        sessionsCache = null;
        sessionsRevision++;
        lastNoteCache = "Calibration history cleared.";
        runtimePrefs(c).edit().remove(KEY_ACTIVE).putString(KEY_LAST_NOTE, lastNoteCache).apply();
        saveEstimateCache(c, new Estimate(null, 0, "Low", Double.NaN));
    }

    static String lastNote(Context c) {
        ensureRuntimeLoaded(c);
        return lastNoteCache;
    }

    static void recordProcessExitInfo(Context c) {
        if (Build.VERSION.SDK_INT < 30) return;
        Context app = c.getApplicationContext();
        AppWorker.execute(() -> recordExitInfoApi30(app));
    }

    @android.annotation.TargetApi(30)
    private static void recordExitInfoApi30(Context app) {
        try {
            ActivityManager am = (ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return;
            List<ApplicationExitInfo> reasons = am.getHistoricalProcessExitReasons(app.getPackageName(), 0, 1);
            if (reasons == null || reasons.isEmpty()) return;
            ApplicationExitInfo info = reasons.get(0);
            long ts = info.getTimestamp();
            SharedPreferences rt = runtimePrefs(app);
            if (ts <= rt.getLong(KEY_LAST_EXIT_TS, 0)) return;
            String desc = exitReasonLabel(info.getReason()) + " @ "
                    + new SimpleDateFormat("MMM d HH:mm", Locale.US).format(new Date(ts));
            String detail = info.getDescription();
            if (detail != null && !detail.trim().isEmpty()) desc += " (" + detail.trim() + ")";
            rt.edit().putLong(KEY_LAST_EXIT_TS, ts).putString(KEY_LAST_EXIT, desc).apply();
        } catch (Exception ignored) { }
    }

    static String lastProcessExitNote(Context c) {
        return runtimePrefs(c).getString(KEY_LAST_EXIT, "");
    }

    @android.annotation.TargetApi(30)
    private static String exitReasonLabel(int reason) {
        switch (reason) {
            case ApplicationExitInfo.REASON_LOW_MEMORY: return "Low memory kill";
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE: return "Excessive resource usage";
            case ApplicationExitInfo.REASON_USER_REQUESTED: return "Stopped by user";
            case ApplicationExitInfo.REASON_USER_STOPPED: return "Force-stopped";
            case ApplicationExitInfo.REASON_CRASH: return "App crash";
            case ApplicationExitInfo.REASON_ANR: return "ANR";
            case ApplicationExitInfo.REASON_OTHER: return "System killed";
            default: return "Code " + reason;
        }
    }

    static Estimate estimateFromSessions(List<Session> all, float design) {
        List<Double> vals = new ArrayList<>();
        if (all != null) {
            for (int i = all.size() - 1; i >= 0 && vals.size() < 16; i--) {
                Session s = all.get(i);
                if (s.used && s.fccMah >= 500 && s.fccMah <= 20000
                        && (design <= 0 || (s.fccMah >= design * 0.45 && s.fccMah <= design * 1.35))) vals.add(s.fccMah);
            }
        }
        if (vals.isEmpty()) return new Estimate(null, 0, "Low", Double.NaN);

        double initialMedian = median(vals);
        List<Double> deviations = new ArrayList<>();
        for (double v : vals) deviations.add(Math.abs(v - initialMedian));
        double mad = median(deviations);

        List<Double> filtered = new ArrayList<>();
        double threshold = Math.max(initialMedian * 0.10, 3.0 * mad);
        if (vals.size() >= 4) {
            for (double v : vals) {
                if (Math.abs(v - initialMedian) <= threshold) filtered.add(v);
            }
        }
        if (filtered.size() < 2) filtered = vals;

        double finalMedian = median(filtered);
        List<Double> finalDev = new ArrayList<>();
        for (double v : filtered) finalDev.add(Math.abs(v - finalMedian));
        double relMad = finalMedian > 0 ? median(finalDev) / finalMedian : Double.NaN;

        String confidence;
        int n = filtered.size();
        if (n >= 4 && relMad <= 0.035) confidence = "Consistent";
        else if (n >= 3 && relMad <= 0.065) confidence = "Consistent";
        else if (n >= 2) confidence = "Building";
        else confidence = "Early estimate";

        return new Estimate(finalMedian, n, confidence, relMad);
    }

    private static void setLastNote(Context c, String note) {
        lastNoteCache = note;
        runtimePrefs(c).edit().putString(KEY_LAST_NOTE, note).apply();
    }

    private static String qualityFor(double start, double end, double delta) {
        if (delta >= 55 && start <= 35 && end >= 75) return "Excellent";
        if (delta >= 45) return "Good";
        if (delta >= 30) return "Fair";
        if (delta >= 20) return "Low";
        return "Too short";
    }

    private static double median(List<Double> vals) {
        if (vals == null || vals.isEmpty()) return Double.NaN;
        List<Double> copy = new ArrayList<>(vals);
        Collections.sort(copy);
        int n = copy.size();
        return n % 2 == 1 ? copy.get(n / 2) : (copy.get(n / 2 - 1) + copy.get(n / 2)) / 2.0;
    }
}
