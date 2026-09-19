package com.darren.batterylens;

import java.io.BufferedReader;
import java.io.FileReader;

/** Read-only, ordinary-app access. Never shells out, requests root, or guesses OEM units. */
final class CycleReader {
    private static final String[] PATHS = {
            "/sys/class/power_supply/battery/cycle_count",
            "/sys/class/power_supply/bms/cycle_count",
            "/sys/class/power_supply/battery/charge_cycles"
    };
    static final class Result {
        final int count;
        final String source;
        final String details;
        Result(int count, String source, String details) {
            this.count = count; this.source = source; this.details = details;
        }
    }
    private static volatile Result cached = new Result(-1, "Not exposed", "Tap Check compatibility to probe readable cycle counters.");
    static Result cached() { return cached; }

    /** Call on a worker thread; denial is normal on production Samsung firmware. */
    static Result scan() {
        StringBuilder details = new StringBuilder();
        int count = -1;
        String source = "Not exposed";
        for (String path : PATHS) {
            try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
                char[] buffer = new char[64];
                int length = reader.read(buffer);
                Integer parsed = MeasurementRules.cycleValue(length > 0 ? new String(buffer, 0, length) : "");
                details.append(path).append(parsed == null ? ": invalid value\n" : ": " + parsed + "\n");
                if (parsed != null && (count < 0 || (count == 0 && parsed > 0))) {
                    count = parsed; source = path;
                }
            } catch (Exception ignored) { details.append(path).append(": unavailable to this app\n"); }
        }
        cached = new Result(count, source, details.toString().trim());
        return cached;
    }
}
