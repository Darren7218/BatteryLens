package com.darren.batterylens;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class CalibrationStoreLogicTest {

    private static CalibrationStore.Session usedSession(double fccMah) {
        CalibrationStore.Session s = new CalibrationStore.Session();
        s.used = true;
        s.fccMah = fccMah;
        return s;
    }

    @Test
    public void parseLegacyStringHandlesEmptyAndOutOfRangeValues() {
        assertTrue(CalibrationStore.parseLegacyString(null).isEmpty());
        assertTrue(CalibrationStore.parseLegacyString("   ").isEmpty());

        List<CalibrationStore.Session> parsed = CalibrationStore.parseLegacyString("400, 4400, bad, 21000, NaN, 4525.5");
        assertEquals(2, parsed.size());
        assertEquals(4400.0, parsed.get(0).fccMah, 0.01);
        assertEquals(4525.5, parsed.get(1).fccMah, 0.01);
        for (CalibrationStore.Session s : parsed) {
            assertFalse(s.used);
            assertEquals("legacy", s.source);
            assertEquals("Imported", s.quality);
        }
        assertTrue(parsed.get(0).startTime < parsed.get(1).startTime);
    }

    @Test
    public void estimateFromSessionsReturnsLowWhenNoEligibleSessions() {
        List<CalibrationStore.Session> list = CalibrationStore.parseLegacyString("4500, 4550");
        CalibrationStore.Estimate est = CalibrationStore.estimateFromSessions(list, 4565f);
        assertNull(est.fccMah);
        assertEquals(0, est.usedCount);
        assertEquals("Low", est.confidence);
        assertTrue(Double.isNaN(est.relativeMad));
    }

    @Test
    public void estimateFromSessionsRejectsDesignBoundsAndMadOutlier() {
        List<CalibrationStore.Session> list = new ArrayList<>();
        // Out of design bounds (design = 4500 -> [2025, 6075])
        list.add(usedSession(1800));
        list.add(usedSession(6500));
        // Valid sessions plus one in-bounds outlier (2800 mAh)
        list.add(usedSession(2800));
        list.add(usedSession(4490));
        list.add(usedSession(4510));
        list.add(usedSession(4500));
        list.add(usedSession(4520));

        CalibrationStore.Estimate est = CalibrationStore.estimateFromSessions(list, 4500f);
        assertNotNull(est.fccMah);
        assertEquals(4, est.usedCount);
        assertEquals(4505.0, est.fccMah, 0.1);
        assertEquals("Consistent", est.confidence);
    }

    @Test
    public void estimateFromSessionsUsesOnlyLatestSixteenAndConfidenceTiers() {
        List<CalibrationStore.Session> single = new ArrayList<>();
        single.add(usedSession(4400));
        CalibrationStore.Estimate est1 = CalibrationStore.estimateFromSessions(single, 0f);
        assertEquals("Early estimate", est1.confidence);
        assertEquals(1, est1.usedCount);

        List<CalibrationStore.Session> two = new ArrayList<>();
        two.add(usedSession(4400));
        two.add(usedSession(4500));
        CalibrationStore.Estimate est2 = CalibrationStore.estimateFromSessions(two, 0f);
        assertEquals("Building", est2.confidence);
        assertEquals(2, est2.usedCount);

        // Oldest 4 entries (2500) must be outside the 16-session sliding window.
        List<CalibrationStore.Session> window = new ArrayList<>();
        for (int i = 0; i < 4; i++) window.add(usedSession(2500));
        for (int i = 0; i < 16; i++) window.add(usedSession(4500));
        CalibrationStore.Estimate estWindow = CalibrationStore.estimateFromSessions(window, 4565f);
        assertEquals(16, estWindow.usedCount);
        assertEquals(4500.0, estWindow.fccMah, 0.01);
        assertEquals("Consistent", estWindow.confidence);
    }
}
