package com.darren.batterylens;

import java.util.List;

final class TrendAnalysis {
    final long observedMs;
    final double drainPerHour, minTemp, maxTemp;
    final int count;

    TrendAnalysis(List<TrendPoint> points) {
        long observed = 0, discharge = 0;
        double drop = 0, low = Double.POSITIVE_INFINITY, high = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < points.size(); i++) {
            TrendPoint p = points.get(i);
            if (Double.isFinite(p.temperatureC)) { low = Math.min(low, p.temperatureC); high = Math.max(high, p.temperatureC); }
            if (i == 0) continue;
            TrendPoint before = points.get(i - 1);
            if (!p.connectsTo(before)) continue;
            long dt = p.elapsed - before.elapsed;
            observed += dt;
            double delta = before.soc - p.soc;
            if (!p.plugged && !before.plugged && MeasurementRules.validSoc(p.soc)
                    && MeasurementRules.validSoc(before.soc) && delta >= 0 && delta <= 5) {
                discharge += dt;
                drop += delta;
            }
        }
        observedMs = observed; count = points.size();
        // Avoid presenting one percentage-step over a short interval as a reliable rate.
        drainPerHour = discharge >= 30 * 60_000L && drop >= 2 ? drop * 3_600_000 / discharge : Double.NaN;
        minTemp = Double.isFinite(low) ? low : Double.NaN;
        maxTemp = Double.isFinite(high) ? high : Double.NaN;
    }
}
