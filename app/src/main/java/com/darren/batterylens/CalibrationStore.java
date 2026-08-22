package com.darren.batterylens;

import android.content.Context;
import android.content.SharedPreferences;

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
                o.put("stp", startTemp);
                o.put("etp", endTemp);
                o.put("fcc", fccMah);
                o.put("src", source);
                o.put("q", quality);
                o.put("used", used);
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
            s.used = o.optBoolean("used", false);
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

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean isAutoEnabled(Context c) {
        return prefs(c).getBoolean(KEY_AUTO, true);
    }

    static void setAutoEnabled(Context c, boolean enabled) {
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
                    if (fcc < 500 || fcc > 20000) continue;
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
                    s.used = true;
                    arr.put(s.toJson());
                    offset--;
                } catch (Exception ignored) {}
            }
        }
        p.edit().putString(KEY_SESSIONS, arr.toString()).apply();
    }

    static boolean hasActive(Context c) {
        String raw = prefs(c).getString(KEY_ACTIVE, "");
        return raw != null && !raw.isEmpty();
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

    static boolean startSession(Context c, String source) {
        if (hasActive(c)) return false;
        BatteryReadings r = BatteryReadings.read(c);
        if (r == null || !r.hasUsableCounter() || r.soc < 0 || !r.isPlugged()) {
            setLastNote(c, "Could not start: charge counter or charger state unavailable.");
            return false;
        }
        JSONObject o = new JSONObject();
        try {
            o.put("time", System.currentTimeMillis());
            o.put("soc", r.soc);
            o.put("uah", r.chargeUah);
            o.put("temp", r.temperatureC());
            o.put("src", source);
        } catch (Exception ignored) {}
        prefs(c).edit().putString(KEY_ACTIVE, o.toString()).apply();
        setLastNote(c, "Session started automatically at " + Math.round(r.soc) + "%.");
        return true;
    }

    static Session finishSession(Context c) {
        JSONObject active = activeObject(c);
        if (active == null) return null;
        BatteryReadings r = BatteryReadings.read(c);
        if (r == null || !r.hasUsableCounter() || r.soc < 0) {
            setLastNote(c, "Could not finish session: battery readings unavailable.");
            return null;
        }

        Session s = new Session();
        s.startTime = active.optLong("time", System.currentTimeMillis());
        s.endTime = System.currentTimeMillis();
        s.startSoc = active.optDouble("soc", -1);
        s.endSoc = r.soc;
        s.startUah = active.optLong("uah", Long.MIN_VALUE);
        s.endUah = r.chargeUah;
        s.startTemp = active.optDouble("temp", Double.NaN);
        s.endTemp = r.temperatureC();
        s.source = active.optString("src", "auto");

        double deltaSoc = s.deltaSoc();
        long deltaUah = s.endUah - s.startUah;
        if (deltaSoc <= 0 || deltaUah <= 0) {
            clearActive(c);
            setLastNote(c, "Session ignored: charge counter did not increase normally.");
            return null;
        }

        s.fccMah = (deltaUah / 1000.0) / (deltaSoc / 100.0);
        s.quality = qualityFor(s.startSoc, s.endSoc, deltaSoc);

        float design = designCapacity(c);
        boolean plausible = s.fccMah >= 500 && s.fccMah <= 20000;
        if (design > 0) {
            double ratio = s.fccMah / design;
            plausible = plausible && ratio >= 0.45 && ratio <= 1.35;
        }
        s.used = plausible && deltaSoc >= 30.0;

        if (deltaSoc >= 20.0 && plausible) {
            addSession(c, s);
            String suffix = s.used ? "used for health estimate" : "saved as low-confidence history";
            setLastNote(c, "Saved " + Math.round(deltaSoc) + "% charge session (" + s.quality + ", " + suffix + ").");
        } else if (deltaSoc < 20.0) {
            setLastNote(c, "Short charge ignored (only " + Math.round(deltaSoc) + "% gained). Aim for 30%+.");
        } else {
            setLastNote(c, "Session rejected because the calculated capacity was implausible.");
        }
        clearActive(c);
        return s;
    }

    static void reconcileAutoState(Context c) {
        if (!isAutoEnabled(c)) return;
        BatteryReadings r = BatteryReadings.read(c);
        if (r == null) return;
        if (r.isPlugged() && !hasActive(c)) {
            startSession(c, "auto");
        } else if (!r.isPlugged() && hasActive(c) && "auto".equals(activeSource(c))) {
            finishSession(c);
        }
    }

    static void cancelActiveIfSource(Context c, String source) {
        if (hasActive(c) && source.equals(activeSource(c))) {
            clearActive(c);
            setLastNote(c, "Active automatic session cancelled.");
        }
    }

    static void clearHistory(Context c) {
        prefs(c).edit().remove(KEY_SESSIONS).remove(LEGACY_CALIBRATIONS).apply();
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
                if (s != null && !Double.isNaN(s.fccMah)) out.add(s);
            }
        } catch (Exception ignored) {}
        return out;
    }

    static Estimate estimate(Context c) {
        List<Double> vals = new ArrayList<>();
        List<Session> all = sessions(c);
        for (int i = Math.max(0, all.size() - 16); i < all.size(); i++) {
            Session s = all.get(i);
            if (s.used && s.fccMah >= 500 && s.fccMah <= 20000) vals.add(s.fccMah);
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
        if (n >= 4 && relMad <= 0.035) confidence = "High";
        else if (n >= 3 && relMad <= 0.065) confidence = "Medium–High";
        else if (n >= 2) confidence = "Medium";
        else confidence = "Low–Medium";

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

    private static void addSession(Context c, Session s) {
        List<Session> all = sessions(c);
        all.add(s);
        while (all.size() > 30) all.remove(0);
        JSONArray arr = new JSONArray();
        for (Session item : all) arr.put(item.toJson());
        prefs(c).edit().putString(KEY_SESSIONS, arr.toString()).apply();
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
