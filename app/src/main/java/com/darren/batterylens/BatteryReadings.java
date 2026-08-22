package com.darren.batterylens;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;

final class BatteryReadings {
    final double soc;
    final int tempTenthC;
    final int voltageMv;
    final int health;
    final int status;
    final int plugged;
    final int chargeUah;
    final int currentNowUa;
    final int currentAvgUa;
    final long energyNwh;
    final long chargeTimeMs;
    final int cycles;
    final int deviceSoh;

    private BatteryReadings(double soc, int tempTenthC, int voltageMv, int health,
                            int status, int plugged, int chargeUah, int currentNowUa,
                            int currentAvgUa, long energyNwh, long chargeTimeMs,
                            int cycles, int deviceSoh) {
        this.soc = soc;
        this.tempTenthC = tempTenthC;
        this.voltageMv = voltageMv;
        this.health = health;
        this.status = status;
        this.plugged = plugged;
        this.chargeUah = chargeUah;
        this.currentNowUa = currentNowUa;
        this.currentAvgUa = currentAvgUa;
        this.energyNwh = energyNwh;
        this.chargeTimeMs = chargeTimeMs;
        this.cycles = cycles;
        this.deviceSoh = deviceSoh;
    }

    static BatteryReadings read(Context context) {
        BatteryManager bm = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
        Intent battery = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (bm == null || battery == null) return null;

        int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        double soc = level >= 0 && scale > 0 ? (100.0 * level / scale) : -1;
        int temp = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
        int voltage = battery.getIntExtra(BatteryManager.EXTRA_VOLTAGE, Integer.MIN_VALUE);
        int health = battery.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN);
        int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN);
        int plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);

        int chargeUah = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
        int currentNow = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
        int currentAvg = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE);
        long energyNwh = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER);
        long chargeTimeMs;
        try {
            chargeTimeMs = bm.computeChargeTimeRemaining();
        } catch (Exception ignored) {
            chargeTimeMs = -1;
        }

        int cycles = -1;
        if (Build.VERSION.SDK_INT >= 34) {
            cycles = battery.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1);
        }

        int deviceSoh = -1;

// State-of-Health uses battery property ID 10 internally.
// The named public constant was removed from the stable Android SDK,
// so don't reference BatteryManager.BATTERY_PROPERTY_STATE_OF_HEALTH.
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                final int BATTERY_PROPERTY_STATE_OF_HEALTH_COMPAT = 10;

                int soh = bm.getIntProperty(
                        BATTERY_PROPERTY_STATE_OF_HEALTH_COMPAT
                );

                // Reject unsupported / obviously invalid values.
                if (soh >= 50 && soh <= 120) {
                    deviceSoh = soh;
                }
            } catch (SecurityException | IllegalArgumentException e) {
                deviceSoh = -1;
            } catch (Throwable ignored) {
                deviceSoh = -1;
            }
        }

        return new BatteryReadings(soc, temp, voltage, health, status, plugged,
                chargeUah, currentNow, currentAvg, energyNwh, chargeTimeMs, cycles, deviceSoh);
    }

    boolean hasUsableCounter() {
        return chargeUah != Integer.MIN_VALUE && chargeUah > 0;
    }

    boolean isPlugged() {
        return plugged != 0;
    }

    boolean isChargingLike() {
        return status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL
                || isPlugged();
    }

    boolean hasValidDeviceSoh() {
        return deviceSoh >= 50 && deviceSoh <= 120;
    }

    double temperatureC() {
        return tempTenthC == Integer.MIN_VALUE ? Double.NaN : tempTenthC / 10.0;
    }

    double currentNowMa() {
        return currentNowUa == Integer.MIN_VALUE ? Double.NaN : currentNowUa / 1000.0;
    }

    double batterySidePowerW() {
        if (currentNowUa == Integer.MIN_VALUE || voltageMv == Integer.MIN_VALUE || voltageMv <= 0) {
            return Double.NaN;
        }
        return Math.abs((double) currentNowUa) * voltageMv / 1_000_000_000.0;
    }
}
