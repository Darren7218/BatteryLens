package com.darren.batterylens;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class CalibrationStore {
    static final String PREFS = "battery_lens";
    static final String KEY_DESIGN = "design_capacity";
    static final String KEY_AUTO = "auto_calibration";
    private static final String KEY_SESSIONS = "calibration_sessions_v2";
    private static final String KEY_ACTIVE = "active_calibration_v2";
    private static final String KEY_LAST_NOTE = "last_calibration_note";
    private static final String LEGACY_CALIBRATIONS = "calibrations";

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

        double deltaSoc() { return endSoc - startSoc; }
        long durationMs() { return Math.max(0, endTime - startTime); }

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
                o.put("v", 3);
                o.put("samples", samples);
                putFinite(o, "minTemp", minTemp);
                putFinite(o, "maxTemp", maxTemp);
                o.put("reason", reason);
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
            s.used = o.optInt("v", 0) >= 3 && o.optBoolean("used", false);
            s.samples = o.optInt("samples", 0);
            s.minTemp = o.optDouble("minTemp", Double.NaN);
            s.maxTemp = o.optDouble("maxTemp", Double.NaN);
            s.reason = o.optInt("v", 0) < 3 ? "Imported; collect a monitored session" : o.optString("reason", "");
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

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean isAutoEnabled(Context c) {
        return prefs(c).getBoolean(KEY_AUTO, false);
    }

    static synchronized void setAutoEnabled(Context c, boolean enabled) {
        prefs(c).edit().putBoolean(KEY_AUTO, enabled).apply();
        if (!enabled) cancelActiveIfSource(c, "auto");
    }

    static float designCapacity(Context c) {
        return prefs(c).getFloat(KEY_DESIGN, 0f);
    }

    static void setDesignCapacity(Context c, float value) {
        prefs(c).edit().putFloat(KEY_DESIGN, value).apply();
    }

    static void migrateLegacyIfNeeded(Context c) {
        SharedPreferences p = prefs(c);
        if (p.contains(KEY_SESSIONS)) return;
        String legacy = p.getString(LEGACY_CALIBRATIONS, "");
        JSONArray arr = new JSONArray();
        if (legacy != null && !legacy.trim().isEmpty()) {
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
                    arr.put(s.toJson());
                    offset--;
                } catch (Exception ignored) {}
            }
        }
        p.edit().putString(KEY_SESSIONS, arr.toString()).apply();
    }

    static boolean hasActive(Context c) {
        return activeObject(c) != null;
    }

    static String activeSource(Context c) {
        JSONObject o = activeObject(c);
        return o == null ? "" : o.optString("src", "");
    }

    static double activeStartSoc(Context c) {
        JSONObject o = activeObject(c);
        return o == null ? -1 : o.optDouble("soc", -1);
    }

    static long activeStartTime(Context c) {
        JSONObject o = activeObject(c);
        return o == null ? 0 : o.optLong("time", 0);
    }

    static synchronized boolean startSession(Context c, String source) {
        return startSession(c, source, BatteryReadings.read(c));
    }

    private static boolean startSession(Context c, String source, BatteryReadings r) {
        if (hasActive(c)) return false;
        if (r == null || !r.hasUsableCounter() || !MeasurementRules.validSoc(r.soc) || !r.isPlugged()) {
            setLastNote(c, "Waiting for a charger and a usable charge counter. Check compatibility if this persists.");
            return false;
        }
        JSONObject o = new JSONObject();
        try {
            o.put("time", System.currentTimeMillis());
            o.put("soc", r.soc);
            o.put("uah", r.chargeUah);
            putFinite(o, "temp", r.temperatureC());
            putFinite(o, "minTemp", r.temperatureC());
            putFinite(o, "maxTemp", r.temperatureC());
            o.put("src", source);
            o.put("samples", 0);
            o.put("startElapsed", SystemClock.elapsedRealtime());
            checkpoint(c, o, r);
        } catch (Exception ignored) { return false; }
        setLastNote(c, "Recording from " + Math.round(r.soc) + "%. Gain at least 30% for an estimate.");
        return true;
    }

    /** Called from the monitor and visible Activity, with the same continuity rules. */
    static synchronized void observe(Context c, BatteryReadings r) {
        if (r == null || !MeasurementRules.validSoc(r.soc)) return;
        recordObservedCycles(c, r);
        JSONObject o = activeObject(c);
        if (o != null) {
            long now = SystemClock.elapsedRealtime();
            boolean continuous = o.has("lastElapsed") && MeasurementRules.continuous(
                    o.optLong("lastElapsed"), now, o.optInt("boot", -1), boot(c));
            if (!continuous || now - o.optLong("startElapsed", now) > MeasurementRules.MAX_SESSION_MS) {
                // Preserve only the last known charging segment, never a later discharge reading.
                finishCheckpoint(c, "Monitoring interrupted; saved last observed segment");
            } else if (!r.isPlugged()) {
                // Last connected checkpoint avoids the unplug/battery-broadcast ordering race.
                finishCheckpoint(c, "Charger disconnected");
            } else if (!r.hasUsableCounter()) {
                finishCheckpoint(c, "Charge counter became unavailable");
            } else if (r.soc < o.optDouble("lastSoc") || r.chargeUah < o.optLong("lastUah") - 10_000) {
                finishCheckpoint(c, "Charge counter or level decreased; segment ended");
            } else {
                checkpoint(c, o, r);
            }
        }
        if (r.isPlugged() && !hasActive(c) && isAutoEnabled(c)) startSession(c, "auto", r);
    }

    private static void checkpoint(Context c, JSONObject o, BatteryReadings r) {
        try {
            long elapsed = SystemClock.elapsedRealtime();
            // Count independent observations, not duplicate sticky broadcasts/UI refreshes.
            if (!o.has("lastElapsed") || elapsed - o.optLong("countedElapsed", 0) >= 30_000
                    || r.soc != o.optDouble("lastSoc")) {
                o.put("samples", o.optInt("samples") + 1);
                o.put("countedElapsed", elapsed);
            }
            o.put("lastTime", System.currentTimeMillis());
            o.put("lastElapsed", elapsed);
            o.put("boot", boot(c));
            o.put("lastSoc", r.soc);
            o.put("lastUah", r.chargeUah);
            putFinite(o, "lastTemp", r.temperatureC());
            double t = r.temperatureC();
            if (Double.isFinite(t)) {
                o.put("minTemp", Math.min(o.optDouble("minTemp", t), t));
                o.put("maxTemp", Math.max(o.optDouble("maxTemp", t), t));
            } else o.put("missingTemp", true);
            prefs(c).edit().putString(KEY_ACTIVE, o.toString()).apply();
        } catch (Exception ignored) { setLastNote(c, "Could not save this battery observation."); }
    }

    static synchronized Session finishSession(Context c) {
        long originalStart = activeStartTime(c);
        BatteryReadings r = BatteryReadings.read(c);
        if (r != null) observe(c, r);
        return hasActive(c) && activeStartTime(c) == originalStart ? finishCheckpoint(c, "Finished by you") : null;
    }

    static synchronized Session finishCheckpoint(Context c, String endReason) {
        JSONObject active = activeObject(c);
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
        s.fccMah = MeasurementRules.capacityMah(s.startSoc, s.endSoc, s.startUah, s.endUah);
        long duration = active.optLong("lastElapsed", 0) - active.optLong("startElapsed", 0);
        // Persist duration independently of user wall-clock changes.
        s.endTime = s.startTime + Math.max(0, duration);
        String rejection = MeasurementRules.rejection(s.fccMah, s.deltaSoc(), duration,
                active.optBoolean("missingTemp") ? Double.NaN : s.minTemp,
                s.maxTemp, designCapacity(c), s.samples);
        s.used = rejection.isEmpty();
        s.reason = s.used ? endReason : rejection + " • " + endReason;
        s.quality = s.used ? qualityFor(s.startSoc, s.endSoc, s.deltaSoc()) : "History only";
        // Even rejected sessions remain inspectable. Persist history and clear the active session
        // in ONE edit, so a process death cannot append the same session twice.
        List<Session> all = sessions(c);
        all.add(s);
        while (all.size() > 60) all.remove(0);
        JSONArray arr = new JSONArray();
        for (Session item : all) arr.put(item.toJson());
        prefs(c).edit().putString(KEY_SESSIONS, arr.toString()).remove(KEY_ACTIVE)
                .putString(KEY_LAST_NOTE, s.used ? "Saved a " + Math.round(s.deltaSoc()) + "% segment for estimation. " + endReason
                        : "Session saved as history: " + s.reason).apply();
        return s;
    }

    static synchronized void reconcileAutoState(Context c) { observe(c, BatteryReadings.read(c)); }

    static synchronized void onBoot(Context c) {
        finishCheckpoint(c, "Phone restarted; saved last observed segment");
        prefs(c).edit().remove("cycle_previous_soc").apply();
        setLastNote(c, "Phone restarted. Open BatteryLens to resume monitoring.");
    }

    private static int boot(Context c) {
        return Settings.Global.getInt(c.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
    }

    private static void recordObservedCycles(Context c, BatteryReadings r) {
        SharedPreferences p = prefs(c);
        long elapsed = SystemClock.elapsedRealtime();
        boolean continuous = p.contains("cycle_previous_soc") && MeasurementRules.continuous(
                p.getLong("cycle_elapsed", 0), elapsed, p.getInt("cycle_boot", -2), boot(c));
        double delta = MeasurementRules.observedDischarge(p.getFloat("cycle_previous_soc", -1), r.soc,
                p.getBoolean("cycle_plugged", true), r.isPlugged(), continuous);
        SharedPreferences.Editor edit = p.edit().putFloat("observed_cycles", p.getFloat("observed_cycles", 0) + (float) delta)
                .putFloat("cycle_previous_soc", (float) r.soc).putLong("cycle_elapsed", elapsed)
                .putInt("cycle_boot", boot(c)).putBoolean("cycle_plugged", r.isPlugged());
        if (!p.contains("observed_since")) edit.putLong("observed_since", System.currentTimeMillis());
        edit.apply();
    }

    static synchronized void breakCycleContinuity(Context c) {
        prefs(c).edit().remove("cycle_previous_soc").apply();
    }

    static float observedCycles(Context c) { return prefs(c).getFloat("observed_cycles", 0); }
    static long observedSince(Context c) { return prefs(c).getLong("observed_since", 0); }

    static synchronized void cancelActiveIfSource(Context c, String source) {
        if (hasActive(c) && source.equals(activeSource(c))) {
            clearActive(c);
            setLastNote(c, "Active automatic session cancelled.");
        }
    }

    static synchronized void clearHistory(Context c) {
        prefs(c).edit().remove(KEY_SESSIONS).remove(LEGACY_CALIBRATIONS).remove(KEY_ACTIVE).apply();
        setLastNote(c, "Calibration history cleared.");
    }

    static String lastNote(Context c) {
        return prefs(c).getString(KEY_LAST_NOTE, "Automatic calibration is ready.");
    }

    static List<Session> sessions(Context c) {
        migrateLegacyIfNeeded(c);
        List<Session> out = new ArrayList<>();
        String raw = prefs(c).getString(KEY_SESSIONS, "[]");
        try {
            JSONArray a = new JSONArray(raw == null ? "[]" : raw);
            for (int i = 0; i < a.length(); i++) {
                Session s = Session.fromJson(a.optJSONObject(i));
                if (s != null) out.add(s);
            }
        } catch (Exception ignored) {}
        return out;
    }

    static Estimate estimate(Context c) {
        List<Double> vals = new ArrayList<>();
        List<Session> all = sessions(c);
        for (int i = all.size() - 1; i >= 0 && vals.size() < 16; i--) {
            Session s = all.get(i);
            if (s.used && s.fccMah >= 500 && s.fccMah <= 20000
                    && (designCapacity(c) <= 0 || (s.fccMah >= designCapacity(c) * 0.45 && s.fccMah <= designCapacity(c) * 1.35))) vals.add(s.fccMah);
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

    private static JSONObject activeObject(Context c) {
        String raw = prefs(c).getString(KEY_ACTIVE, "");
        if (raw == null || raw.isEmpty()) return null;
        try { return new JSONObject(raw); } catch (Exception ignored) { return null; }
    }

    private static void clearActive(Context c) {
        prefs(c).edit().remove(KEY_ACTIVE).apply();
    }

    private static void setLastNote(Context c, String note) {
        prefs(c).edit().putString(KEY_LAST_NOTE, note).apply();
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
