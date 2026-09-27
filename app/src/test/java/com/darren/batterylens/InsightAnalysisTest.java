package com.darren.batterylens;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class InsightAnalysisTest {
    private static final long NOW = 30_000_000L;
    private TrendPoint point(int minute, double soc, boolean plugged, boolean screen, double temp) {
        return new TrendPoint(NOW - (240 - minute) * 60_000L,
                1_000_000 + minute * 60_000L, 1, soc, -300, temp, plugged,
                screen, true, false);
    }

    @Test public void distinguishesInteractiveFromIdleWithoutTreatingChargingAsDrain() {
        List<TrendPoint> points = new ArrayList<>();
        for (int minute = 0; minute <= 30; minute += 5)
            points.add(point(minute, 90 - minute / 10.0, false, true, 36));
        // The screen transition itself is not attributed to either bucket.
        for (int minute = 35; minute <= 155; minute += 5)
            points.add(point(minute, 87 - (minute - 30) / 60.0, false, false, 36));
        points.add(point(160, 95, true, false, 40));
        InsightAnalysis a = new InsightAnalysis(points, NOW);
        assertEquals(6, a.screenRate, .01);
        assertEquals(1, a.idleRate, .01);
        assertEquals(30 * 60_000L, a.screenMs);
        assertEquals(120 * 60_000L, a.idleMs);
        assertEquals(160 * 60_000L, a.hotMs35);
        assertEquals(0, a.hotMs40);
        assertTrue(Double.isFinite(a.runtimeHours(50)));
    }

    @Test public void gapsAndUnknownHistoricalScreenStateStayUnclassified() {
        TrendPoint first = point(0, 90, false, false, 30);
        TrendPoint afterGap = point(60, 80, false, false, 35);
        TrendPoint legacy = new TrendPoint(afterGap.time + 60_000, afterGap.elapsed + 60_000,
                1, 79, -200, 34, false);
        InsightAnalysis a = new InsightAnalysis(Arrays.asList(first, afterGap, legacy), NOW);
        assertTrue(Double.isNaN(a.idleRate));
        assertTrue(Double.isNaN(a.overallRate));
        assertEquals(0, a.hotMs35);
        assertTrue(Double.isNaN(a.runtimeHours(80)));
    }

    @Test public void missingDataCannotRaiseFalseIdleWarning() {
        InsightAnalysis a = new InsightAnalysis(Arrays.asList(point(0, 80, false, false, 31)), NOW);
        assertFalse(a.unusualIdle());
        assertTrue(Double.isNaN(a.baselineIdleRate));
    }
}
