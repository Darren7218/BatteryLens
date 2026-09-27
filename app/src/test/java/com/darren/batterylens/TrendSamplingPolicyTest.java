package com.darren.batterylens;

import org.junit.Test;
import static org.junit.Assert.*;

public class TrendSamplingPolicyTest {
    private TrendPoint point(long time, long elapsed, int boot, boolean plugged,
                             boolean interactive, boolean hotspot) {
        return new TrendPoint(time, elapsed, boot, 80, -200, 35, plugged,
                interactive, true, hotspot);
    }

    @Test public void waitsFiveMinutesBetweenEquivalentSamples() {
        TrendPoint initial = point(1_000_000, 100_000, 1, false, true, false);
        assertTrue(TrendSamplingPolicy.shouldRecord(null, initial));
        assertFalse(TrendSamplingPolicy.shouldRecord(initial,
                point(1_060_000, 160_000, 1, false, true, false)));
        assertTrue(TrendSamplingPolicy.shouldRecord(initial,
                point(1_300_000, 400_000, 1, false, true, false)));
    }

    @Test public void transitionsAndRebootsAreNotLost() {
        TrendPoint initial = point(1_000_000, 100_000, 1, false, true, false);
        assertTrue(TrendSamplingPolicy.shouldRecord(initial,
                point(1_060_000, 160_000, 1, true, true, false)));
        assertTrue(TrendSamplingPolicy.shouldRecord(initial,
                point(1_060_000, 160_000, 1, false, false, false)));
        assertTrue(TrendSamplingPolicy.shouldRecord(initial,
                point(1_060_000, 160_000, 1, false, true, true)));
        assertTrue(TrendSamplingPolicy.shouldRecord(initial,
                point(1_060_000, 20_000, 2, false, true, false)));
    }
}
