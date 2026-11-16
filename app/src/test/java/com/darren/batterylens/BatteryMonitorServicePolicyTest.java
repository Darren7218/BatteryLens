package com.darren.batterylens;

import android.os.BatteryManager;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BatteryMonitorServicePolicyTest {

    @Test
    public void shouldSkipBatteryBroadcastDebouncesUnchangedStateWithinWindow() {
        assertTrue(BatteryMonitorService.shouldSkipBatteryBroadcast(
                false, 62, 2, 1, 314,
                62, 2, 1, 310,
                120_000L, 100_000L));

        // Temperature omitted from sticky broadcast keeps debounce active
        assertTrue(BatteryMonitorService.shouldSkipBatteryBroadcast(
                false, 62, 2, 1, Integer.MIN_VALUE,
                62, 2, 1, 310,
                120_000L, 100_000L));
    }

    @Test
    public void shouldSkipBatteryBroadcastAllowsSignificantChangesAndInitialSamples() {
        // Forced sample never skipped
        assertFalse(BatteryMonitorService.shouldSkipBatteryBroadcast(
                true, 62, 2, 1, 310,
                62, 2, 1, 310,
                105_000L, 100_000L));

        // Uninitialized previous elapsed time never skipped
        assertFalse(BatteryMonitorService.shouldSkipBatteryBroadcast(
                false, 62, 2, 1, 310,
                62, 2, 1, 310,
                10_000L, 0L));

        // Uninitialized prevTempTenth (Integer.MIN_VALUE) with temp 0 must not overflow & skip
        assertFalse(BatteryMonitorService.shouldSkipBatteryBroadcast(
                false, 62, 2, 1, 0,
                62, 2, 1, Integer.MIN_VALUE,
                105_000L, 100_000L));

        // Level change, status change, plug change, >= 0.5C change, or elapsed >= 55s must process
        assertFalse(BatteryMonitorService.shouldSkipBatteryBroadcast(
                false, 63, 2, 1, 310,
                62, 2, 1, 310,
                105_000L, 100_000L));
        assertFalse(BatteryMonitorService.shouldSkipBatteryBroadcast(
                false, 62, 3, 1, 310,
                62, 2, 1, 310,
                105_000L, 100_000L));
        assertFalse(BatteryMonitorService.shouldSkipBatteryBroadcast(
                false, 62, 2, 0, 310,
                62, 2, 1, 310,
                105_000L, 100_000L));
        assertFalse(BatteryMonitorService.shouldSkipBatteryBroadcast(
                false, 62, 2, 1, 315,
                62, 2, 1, 310,
                105_000L, 100_000L));
        assertFalse(BatteryMonitorService.shouldSkipBatteryBroadcast(
                false, 62, 2, 1, 310,
                62, 2, 1, 310,
                155_000L, 100_000L));
    }

    @Test
    public void nextTickIntervalAdaptsToChargingAndScreenState() {
        assertEquals(60_000L, BatteryMonitorService.nextTickIntervalMs(true, true));
        assertEquals(60_000L, BatteryMonitorService.nextTickIntervalMs(true, false));
        assertEquals(TrendSamplingPolicy.SAMPLE_MS, BatteryMonitorService.nextTickIntervalMs(false, true));
        assertEquals(-1L, BatteryMonitorService.nextTickIntervalMs(false, false));
    }

    @Test
    public void formatDetailTextReflectsStateGainAndTemperature() {
        assertEquals("54% • Charging • +18% • 31.2°C",
                BatteryMonitorService.formatDetailText(54.2, BatteryManager.BATTERY_STATUS_CHARGING, true, 31.2, true, 18.4));
        assertEquals("80% • Paused at limit • 28.5°C",
                BatteryMonitorService.formatDetailText(80.0, BatteryManager.BATTERY_STATUS_NOT_CHARGING, true, 28.5, false, 0));
        assertEquals("100% • Full",
                BatteryMonitorService.formatDetailText(100.0, BatteryManager.BATTERY_STATUS_FULL, true, Double.NaN, false, 0));
        assertEquals("Watching for next charge",
                BatteryMonitorService.formatDetailText(-1, BatteryManager.BATTERY_STATUS_DISCHARGING, false, Double.NaN, false, 0));
    }

    @Test
    public void monitorNotificationRespectsPermissionAndDeduplicates() {
        assertFalse(BatteryMonitorService.shouldPostMonitorNotification(false, "80% • Paused at limit", ""));
        assertTrue(BatteryMonitorService.shouldPostMonitorNotification(true, "80% • Paused at limit", ""));
        assertFalse(BatteryMonitorService.shouldPostMonitorNotification(true, "80% • Paused at limit", "80% • Paused at limit"));
        assertTrue(BatteryMonitorService.shouldPostMonitorNotification(true, "81% • Charging", "80% • Paused at limit"));
    }
}
