package com.darren.batterylens;

import java.util.List;

/** Observed intervals only: no app attribution and no extrapolation across gaps. */
final class InsightAnalysis {
    static final long HOUR = 3_600_000L;
    final long screenMs, idleMs, hotMs35, hotMs40, hotspotMs;
    final double screenRate, idleRate, overallRate, peakTemp, recentIdleRate, baselineIdleRate;
    final boolean runtimeWellSampled;

    private static final class Bucket {
        long ms;
        double drop;
        void add(long duration, double value) { ms += duration; drop += value; }
        double rate(long minMs, double minDrop) {
            return ms >= minMs && drop >= minDrop ? drop * HOUR / ms : Double.NaN;
        }
    }

    InsightAnalysis(List<TrendPoint> points, long now) {
        Bucket on = new Bucket(), off = new Bucket(), all = new Bucket();
        Bucket recentOff = new Bucket(), earlierOff = new Bucket();
        long above35 = 0, above40 = 0, hotspot = 0;
        double peak = Double.NaN;
        for (int i = 0; i < points.size(); i++) {
            TrendPoint p = points.get(i);
            if (p.time < now - 24 * HOUR || p.time > now) continue;
            if (Double.isFinite(p.temperatureC))
                peak = Double.isFinite(peak) ? Math.max(peak, p.temperatureC) : p.temperatureC;
            if (i == 0) continue;
            TrendPoint before = points.get(i - 1);
            if (!p.connectsTo(before) || before.time < now - 24 * HOUR) continue;
            long duration = p.elapsed - before.elapsed;
            if (Double.isFinite(before.temperatureC) && Double.isFinite(p.temperatureC)) {
                if (before.temperatureC >= 35 && p.temperatureC >= 35) above35 += duration;
                if (before.temperatureC >= 40 && p.temperatureC >= 40) above40 += duration;
            }
            if (before.hotspotMarked && p.hotspotMarked) hotspot += duration;
            if (p.plugged || before.plugged || !MeasurementRules.validSoc(p.soc)
                    || !MeasurementRules.validSoc(before.soc)) continue;
            double drop = before.soc - p.soc;
            if (drop < 0 || drop > 5) continue;
            all.add(duration, drop);
            if (!p.screenKnown || !before.screenKnown || p.interactive != before.interactive) continue;
            if (p.interactive) on.add(duration, drop);
            else {
                off.add(duration, drop);
                if (p.time >= now - 3 * HOUR && before.time >= now - 3 * HOUR)
                    recentOff.add(duration, drop);
                else if (p.time < now - 3 * HOUR) earlierOff.add(duration, drop);
            }
        }
        screenMs = on.ms; idleMs = off.ms; hotMs35 = above35; hotMs40 = above40;
        hotspotMs = hotspot; peakTemp = peak;
        screenRate = on.rate(20 * 60_000L, 2);
        idleRate = off.rate(60 * 60_000L, 1);
        overallRate = all.rate(30 * 60_000L, 2);
        runtimeWellSampled = all.ms >= 4 * HOUR && all.drop >= 5;
        recentIdleRate = recentOff.rate(60 * 60_000L, 1);
        baselineIdleRate = earlierOff.rate(2 * HOUR, 1);
    }

    double runtimeHours(double soc) {
        if (!MeasurementRules.validSoc(soc) || !Double.isFinite(overallRate)
                || overallRate <= 0) return Double.NaN;
        return Math.min(168, soc / overallRate);
    }

    boolean unusualIdle() {
        return Double.isFinite(recentIdleRate) && Double.isFinite(baselineIdleRate)
                && recentIdleRate >= Math.max(1.5, baselineIdleRate * 2);
    }
}
