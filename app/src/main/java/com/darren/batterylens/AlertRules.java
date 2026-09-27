package com.darren.batterylens;

/** Stateful hysteresis; restored by the service so process restarts do not repeat alerts. */
final class AlertRules {
    boolean targetSent, heatSent, lowSent;

    int evaluate(double soc, double temp, boolean plugged, int target, int heat, int low) {
        if (!plugged || soc < target - 2) targetSent = false;
        if (Double.isFinite(temp) && temp < heat - 3) heatSent = false;
        if (plugged || soc > low + 3) lowSent = false;
        int events = 0;
        if (plugged && MeasurementRules.validSoc(soc) && soc >= target && !targetSent) {
            events |= 1; targetSent = true;
        }
        if (Double.isFinite(temp) && temp >= heat && !heatSent) { events |= 2; heatSent = true; }
        if (low > 0 && !plugged && MeasurementRules.validSoc(soc) && soc <= low && !lowSent) {
            events |= 4; lowSent = true;
        }
        return events;
    }
}
