package com.darren.batterylens;

/** Pure measurement rules. No device APIs, clocks, storage, or UI. */
final class MeasurementRules {
    static final long MAX_GAP_MS = 10 * 60_000L;
    static final long MAX_SESSION_MS = 18 * 60 * 60_000L;

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
