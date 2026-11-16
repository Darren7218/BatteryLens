package com.darren.batterylens;

import android.os.BatteryManager;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class WidgetSnapshotTest {

    @Test
    public void resolveSizeUsesFallbackWhenBoundsMissingAndAdaptsOnResize() {
        assertEquals(WidgetSnapshot.WidgetSize.COMPACT_2X1,
                WidgetSnapshot.resolveSize(WidgetSnapshot.WidgetSize.COMPACT_2X1, 0, 0));
        assertEquals(WidgetSnapshot.WidgetSize.WIDE_4X1,
                WidgetSnapshot.resolveSize(WidgetSnapshot.WidgetSize.WIDE_4X1, -1, 60));

        assertEquals(WidgetSnapshot.WidgetSize.COMPACT_2X1,
                WidgetSnapshot.resolveSize(WidgetSnapshot.WidgetSize.DASHBOARD_4X2, 140, 56));
        assertEquals(WidgetSnapshot.WidgetSize.SQUARE_2X2,
                WidgetSnapshot.resolveSize(WidgetSnapshot.WidgetSize.COMPACT_2X1, 150, 120));
        assertEquals(WidgetSnapshot.WidgetSize.WIDE_4X1,
                WidgetSnapshot.resolveSize(WidgetSnapshot.WidgetSize.DASHBOARD_4X2, 290, 56));
        assertEquals(WidgetSnapshot.WidgetSize.DASHBOARD_4X2,
                WidgetSnapshot.resolveSize(WidgetSnapshot.WidgetSize.COMPACT_2X1, 290, 130));
    }

    @Test
    public void buildChargingActiveSessionProducesMintSnapshot() {
        WidgetSnapshot s = WidgetSnapshot.build(
                68.4,
                BatteryManager.BATTERY_STATUS_CHARGING,
                BatteryManager.BATTERY_PLUGGED_AC,
                1850.0,
                7.6,
                34.2,
                true,
                true,
                true,
                42.0,
                4380.0,
                4,
                0.02,
                4565f,
                "Consistent");

        assertEquals("68%", s.levelText);
        assertEquals(68, s.progress);
        assertEquals("CHARGING", s.shortBadge);
        assertEquals("CHARGING · AC", s.fullBadge);
        assertEquals(WidgetSnapshot.Tone.MINT, s.stateTone);
        assertEquals(WidgetSnapshot.Tone.MINT, s.progressTone);
        assertEquals("7.6 W", s.powerText);
        assertEquals("+1850 mA", s.currentText);
        assertEquals("34.2°C", s.tempText);
        assertEquals("96%", s.healthTileText);
        assertEquals("● Live", s.monitorBadgeText);
        assertEquals("Recording · 42% → 68% (+26%)", s.footerText);
        assertEquals("Recording · 42%→68%", s.squareFooter);
        assertEquals("7.6 W · 34.2°C", s.widePrimary);
        assertEquals("Recording · 42%→68%", s.wideSecondary);
        assertTrue(s.compactMeta.contains("34.2°C"));
        assertTrue(s.compactMeta.contains("Health 96%"));
    }

    @Test
    public void buildPausedAtLimitAndCheckedHealthUsesBlueState() {
        WidgetSnapshot s = WidgetSnapshot.build(
                80.0,
                BatteryManager.BATTERY_STATUS_NOT_CHARGING,
                BatteryManager.BATTERY_PLUGGED_USB,
                0.0,
                0.0,
                29.5,
                true,
                true,
                false,
                -1,
                4380.0,
                3,
                0.03,
                4565f,
                "Consistent");

        assertEquals("80%", s.levelText);
        assertEquals("PAUSED", s.shortBadge);
        assertEquals("PAUSED AT LIMIT", s.fullBadge);
        assertEquals(WidgetSnapshot.Tone.BLUE, s.stateTone);
        assertEquals(WidgetSnapshot.Tone.BLUE, s.progressTone);
        assertTrue(s.footerText.startsWith("Health 95.9% · 4,380 mAh · Consistent"));
    }

    @Test
    public void buildLowBatteryAndHotThermalUseWarningTones() {
        WidgetSnapshot warn = WidgetSnapshot.build(
                18.0,
                BatteryManager.BATTERY_STATUS_DISCHARGING,
                0,
                -420.0,
                1.6,
                31.0,
                false,
                false,
                false,
                -1,
                null,
                0,
                Double.NaN,
                0f,
                "Low");

        assertEquals("ON BATTERY", warn.shortBadge);
        assertEquals(WidgetSnapshot.Tone.AMBER, warn.stateTone);
        assertEquals("-420 mA", warn.currentText);
        assertEquals("Learning", warn.healthTileText);
        assertEquals("○ Off", warn.monitorBadgeText);
        assertEquals("Monitor paused · tap to open", warn.footerText);

        WidgetSnapshot critical = WidgetSnapshot.build(
                10.0,
                BatteryManager.BATTERY_STATUS_DISCHARGING,
                0,
                Double.NaN,
                Double.NaN,
                43.5,
                true,
                true,
                false,
                -1,
                4300.0,
                1,
                Double.NaN,
                0f,
                "Low");

        assertEquals("LOW", critical.shortBadge);
        assertEquals(WidgetSnapshot.Tone.CORAL, critical.stateTone);
        assertEquals("4,300 mAh", critical.healthTileText);
        assertEquals("Preliminary: 4,300 mAh (1/3 checks)", critical.footerText);
    }
}
