package com.darren.batterylens;

import org.json.JSONObject;

/** One observed reading. Monotonic timestamps prevent clock edits from inventing rates. */
final class TrendPoint {
    final long time, elapsed;
    final int boot;
    final double soc, currentMa, temperatureC;
    final boolean plugged;
    final boolean interactive, screenKnown, hotspotMarked;

    TrendPoint(long time, long elapsed, int boot, double soc, double currentMa,
               double temperatureC, boolean plugged) {
        this(time, elapsed, boot, soc, currentMa, temperatureC, plugged, false, false, false);
    }

    TrendPoint(long time, long elapsed, int boot, double soc, double currentMa,
               double temperatureC, boolean plugged, boolean interactive,
               boolean screenKnown, boolean hotspotMarked) {
        this.time = time; this.elapsed = elapsed; this.boot = boot; this.soc = soc;
        this.currentMa = currentMa; this.temperatureC = temperatureC; this.plugged = plugged;
        this.interactive = interactive; this.screenKnown = screenKnown;
        this.hotspotMarked = hotspotMarked;
    }

    boolean connectsTo(TrendPoint before) {
        return time > before.time && MeasurementRules.continuous(before.elapsed, elapsed, before.boot, boot)
                && Math.abs((time - before.time) - (elapsed - before.elapsed)) < 5_000;
    }

    JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("time", time); o.put("elapsed", elapsed); o.put("boot", boot);
            if (MeasurementRules.validSoc(soc)) o.put("soc", soc);
            if (Double.isFinite(currentMa)) o.put("ma", currentMa);
            if (Double.isFinite(temperatureC)) o.put("temp", temperatureC);
            o.put("plugged", plugged);
            if (screenKnown) o.put("screen", interactive);
            if (hotspotMarked) o.put("hotspot", true);
        } catch (org.json.JSONException ignored) { }
        return o;
    }

    static TrendPoint fromJson(JSONObject o) {
        if (o == null || o.optLong("time") <= 0) return null;
        return new TrendPoint(o.optLong("time"), o.optLong("elapsed"), o.optInt("boot", -1),
                o.optDouble("soc", Double.NaN), o.optDouble("ma", Double.NaN),
                o.optDouble("temp", Double.NaN), o.optBoolean("plugged"),
                o.optBoolean("screen"), o.has("screen"), o.optBoolean("hotspot"));
    }
}
