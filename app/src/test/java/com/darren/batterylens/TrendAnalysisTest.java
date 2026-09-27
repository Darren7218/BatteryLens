package com.darren.batterylens;

import org.junit.Test;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class TrendAnalysisTest {
    private TrendPoint point(int minute, double soc, boolean plugged) {
        return new TrendPoint(1_000_000 + minute * 60_000L, minute * 60_000L, 1, soc, -200, 30, plugged);
    }
    @Test public void measuresOnlyObservedDischarge() {
        List<TrendPoint> points = new ArrayList<>();
        for (int i = 0; i <= 30; i += 5) points.add(point(i, 80 - i / 10.0, false));
        TrendAnalysis stats = new TrendAnalysis(points);
        assertEquals(6, stats.drainPerHour, .001);
        assertEquals(30 * 60_000L, stats.observedMs);
    }
    @Test public void shortAndFlatWindowsDoNotInventRates() {
        assertTrue(Double.isNaN(new TrendAnalysis(Arrays.asList(point(0, 80, false), point(5, 79, false))).drainPerHour));
        List<TrendPoint> points = new ArrayList<>();
        for (int i = 0; i <= 30; i += 5) points.add(point(i, 80, false));
        assertTrue(Double.isNaN(new TrendAnalysis(points).drainPerHour));
    }
    @Test public void chargingAndGapsAreNotDischarge() {
        TrendAnalysis stats = new TrendAnalysis(Arrays.asList(point(0, 60, true), point(5, 65, true), point(60, 50, false)));
        assertEquals(5 * 60_000L, stats.observedMs);
        assertTrue(Double.isNaN(stats.drainPerHour));
    }
    @Test public void rebootAndClockEditsBreakLines() {
        TrendPoint before = point(0, 80, false);
        assertFalse(new TrendPoint(1_060_000, 60_000, 2, 79, -100, 30, false).connectsTo(before));
        assertFalse(new TrendPoint(1_600_000, 60_000, 1, 79, -100, 30, false).connectsTo(before));
        assertFalse(new TrendPoint(999_000, 60_000, 1, 79, -100, 30, false).connectsTo(before));
    }
    @Test public void missingSensorsSurviveSerialization() {
        TrendPoint point = new TrendPoint(1000, 100, 1, -1, Double.NaN, Double.NaN, false);
        TrendPoint restored = TrendPoint.fromJson(point.toJson());
        assertNotNull(restored);
        assertTrue(Double.isNaN(restored.soc));
        assertTrue(Double.isNaN(restored.currentMa));
        assertTrue(Double.isNaN(restored.temperatureC));
        assertTrue(Double.isNaN(new TrendAnalysis(Arrays.asList(restored)).maxTemp));
        assertFalse(restored.screenKnown);
    }
    @Test public void interactiveAndHotspotContextSurviveSerialization() {
        TrendPoint point = new TrendPoint(1000, 100, 1, 80, -100, 35,
                false, true, true, true);
        TrendPoint restored = TrendPoint.fromJson(point.toJson());
        assertTrue(restored.screenKnown);
        assertTrue(restored.interactive);
        assertTrue(restored.hotspotMarked);
    }
    @Test public void temperatureRangeIgnoresMissingValues() {
        TrendAnalysis stats = new TrendAnalysis(Arrays.asList(point(0, 80, false),
                new TrendPoint(1_060_000, 60_000, 1, 79, -100, 35, false),
                new TrendPoint(1_120_000, 120_000, 1, 79, -100, Double.NaN, false)));
        assertEquals(30, stats.minTemp, .001); assertEquals(35, stats.maxTemp, .001);
    }
}
