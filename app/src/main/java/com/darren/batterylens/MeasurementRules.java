package com.darren.batterylens;

import java.util.List;

/** Pure measurement rules. No device APIs, clocks, storage, or UI. */
final class MeasurementRules {
    static final long MAX_GAP_MS = 10 * 60_000L;
    // Charging time excludes a recognized long hold at the phone's protection limit.
    static final long MAX_SESSION_MS = 48 * 60 * 60_000L;

    static final class ChargePoint {
        final double soc;
        final long uah;
        ChargePoint(double soc, long uah) { this.soc = soc; this.uah = uah; }
    }

    static final class CapacityFit {
        final double mah;
        final double relativeSpread;
        final int pairs;
        CapacityFit(double mah, double relativeSpread, int pairs) {
            this.mah = mah; this.relativeSpread = relativeSpread; this.pairs = pairs;
        }
    }

    static CapacityFit fitCapacity(List<ChargePoint> points) {
        return CapacityEstimator.fit(points);
    }

    static boolean forwardProgress(double peakSoc, long peakUah, double soc, long uah) {
        return validSoc(soc) && soc > peakSoc && uah > peakUah;
    }

    /** Android does not expose the user's OEM charge-protection setting to ordinary apps.
     * Auto recognizes common stop levels only after charging actually stops.
     */
    static int pausedLimit(int selectedLimit, double peakSoc, boolean chargeStopped) {
        if (!chargeStopped || !validSoc(peakSoc)) return -1;
        if (selectedLimit >= 50 && selectedLimit <= 100)
            return Math.abs(peakSoc - selectedLimit) <= 1 ? selectedLimit : -1;
        for (int limit : new int[]{80, 85, 90, 95, 100})
            if (Math.abs(peakSoc - limit) <= 1) return limit;
        return -1;
    }

    static int nextCustomStopCount(int candidate, int priorCandidate, int priorCount) {
        if (candidate < 50 || candidate >= 100) return 0;
        return Math.abs(candidate - priorCandidate) <= 1 ? Math.min(2, priorCount + 1) : 1;
    }

    static boolean continuous(long previousElapsed, long nowElapsed, int previousBoot, int boot) {
        return previousBoot == boot && nowElapsed >= previousElapsed
                && nowElapsed - previousElapsed <= MAX_GAP_MS;
    }

    static boolean validSoc(double soc) { return Double.isFinite(soc) && soc >= 0 && soc <= 100; }

    static double capacityMah(double startSoc, double endSoc, long startUah, long endUah) {
        if (!validSoc(startSoc) || !validSoc(endSoc) || endSoc <= startSoc
                || startUah <= 0 || endUah <= startUah) return Double.NaN;
        return (endUah - startUah) / (10.0 * (endSoc - startSoc));
    }

    static String rejection(double fcc, double gain, long duration, double minTemp,
                            double maxTemp, float design, int samples) {
        if (!Double.isFinite(fcc) || fcc < 500 || fcc > 20000) return "Invalid charge counter";
        if (gain < 30) return "Less than 30% gained";
        if (duration < 10 * 60_000L || duration > MAX_SESSION_MS) return "Unreliable session duration";
        if (samples < 4) return "Too few observations";
        if (!Double.isFinite(minTemp) || !Double.isFinite(maxTemp)) return "Temperature unavailable";
        if (minTemp < 10 || maxTemp > 42) return "Temperature outside 10–42°C";
        if (design > 0 && (fcc < design * 0.45 || fcc > design * 1.35)) return "Capacity outside plausible range";
        return "";
    }

    static String fitRejection(CapacityFit fit, int reversals) {
        if (fit == null) return "Too few independent charge levels";
        if (fit.relativeSpread > 0.15) return "Charge counter disagreed across the session";
        if (reversals >= 3) return "Charging repeatedly reversed under load";
        return "";
    }

    static double observedDischarge(double before, double after, boolean beforePlugged,
                                    boolean plugged, boolean continuous) {
        double drop = before - after;
        return continuous && !beforePlugged && !plugged && validSoc(before) && validSoc(after)
                && drop > 0 && drop <= 5 ? drop / 100.0 : 0;
    }

    static Integer cycleValue(String raw) {
        if (raw == null || !raw.trim().matches("[0-9]{1,6}")) return null;
        try {
            int n = Integer.parseInt(raw.trim());
            return n <= 100000 ? n : null;
        } catch (NumberFormatException ignored) { return null; }
    }
}
