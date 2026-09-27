package com.darren.batterylens;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Robust, bounded capacity fit. Processing n observations costs O(n); the fit itself
 * uses at most 26 state-of-charge bins and 325 comparisons, independent of session length.
 */
final class CapacityEstimator {
    static final int BIN_WIDTH = 4;
    static final int MAX_BINS = 100 / BIN_WIDTH + 1;

    private CapacityEstimator() { }

    static MeasurementRules.CapacityFit fit(List<MeasurementRules.ChargePoint> points) {
        if (points == null || points.size() < 4) return null;
        MeasurementRules.ChargePoint[] bins = new MeasurementRules.ChargePoint[MAX_BINS];
        for (MeasurementRules.ChargePoint p : points) {
            if (p != null && MeasurementRules.validSoc(p.soc) && p.uah > 0) {
                int index = (int) p.soc / BIN_WIDTH;
                if (bins[index] == null) bins[index] = p;
            }
        }
        List<Double> slopes = new ArrayList<>(MAX_BINS * (MAX_BINS - 1) / 2);
        for (int i = 0; i < MAX_BINS; i++) {
            MeasurementRules.ChargePoint a = bins[i];
            if (a == null) continue;
            for (int j = i + 1; j < MAX_BINS; j++) {
                MeasurementRules.ChargePoint b = bins[j];
                if (b == null || b.soc - a.soc < 12) continue;
                double value = MeasurementRules.capacityMah(a.soc, b.soc, a.uah, b.uah);
                if (Double.isFinite(value) && value >= 500 && value <= 20000) slopes.add(value);
            }
        }
        if (slopes.size() < 6) return null;
        double median = median(slopes);
        List<Double> deviations = new ArrayList<>(slopes.size());
        for (double v : slopes) deviations.add(Math.abs(v - median));
        return new MeasurementRules.CapacityFit(median, median(deviations) / median, slopes.size());
    }

    private static double median(List<Double> values) {
        Collections.sort(values);
        int n = values.size();
        return n % 2 == 1 ? values.get(n / 2)
                : (values.get(n / 2 - 1) + values.get(n / 2)) / 2.0;
    }
}
