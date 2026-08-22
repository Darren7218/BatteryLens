# BatteryLens 2.1

BatteryLens is a lightweight Android battery-health monitor focused on measurement quality rather than fake precision.

## What changed in 2.1

- **Automatic calibration:** a charge session starts on power-connect and closes on power-disconnect, even when the app is not open.
- **No permanent background service:** automatic tracking uses Android's power broadcasts instead of constant polling.
- **Android 16 State of Health:** uses `BATTERY_PROPERTY_STATE_OF_HEALTH` when the device exposes a sane value.
- **Filtered multi-session estimate:** repeated charge sessions are combined with a robust median and obvious outliers are filtered.
- **Confidence score:** increases when several accepted sessions agree closely.
- **Health trend chart:** shows recent accepted calibration estimates.
- **Charging session history:** most recent sessions show SOC range, measured full-charge capacity, health %, quality, and whether the session affected the estimate.
- **Charging insights:** battery-side charging power, temperature, cycle count, and Android's time-to-full estimate.
- **Copy diagnostics:** one tap copies raw BatteryLens readings for troubleshooting/tuning.
- **Legacy migration:** calibration values from BatteryLens 1.0 are imported automatically.
- **Refreshed UI:** cleaner dark cards, improved metric tiles, clearer calibration controls, and better information hierarchy.
- **New app icon:** custom adaptive launcher icon with Android themed-icon support.

## How automatic calibration works

1. Leave **Automatic calibration** enabled (it is on by default).
2. Plug the phone in normally. BatteryLens stores the starting state-of-charge and Android charge counter.
3. Unplug normally. BatteryLens compares the ending charge counter and state-of-charge.
4. Sessions gaining at least 20 percentage points can be kept as history; **30%+ sessions are eligible for the health estimate**.
5. Longer sessions are rated higher. A roughly 20% → 80% charge is an excellent calibration run and works fine with Samsung Battery Protection.
6. The app uses recent accepted sessions, rejects obvious outliers, then takes a median to reduce one-off fuel-gauge error.

Nothing needs to stay open while charging.

## Health calculation priority

1. **Android 16 device-reported State of Health**, if exposed by the phone.
2. **Filtered median of accepted automatic/manual charge sessions.**
3. **Snapshot estimate** (`charge counter / SOC`) only as a low-confidence fallback until better data exists.

## Galaxy S24 FE default

For model `SM-S721*`, BatteryLens pre-fills **4565 mAh rated capacity**. Samsung lists 4700 mAh typical and 4565 mAh rated. The rated value is the correct baseline for a State-of-Health percentage.

## Live readings

Depending on what the phone firmware exposes, BatteryLens can show:

- Battery level and Android battery-health state
- Temperature and voltage
- Remaining charge counter (mAh)
- Instantaneous and average battery current
- Estimated battery-side charging wattage (`|current| × voltage`)
- Remaining energy counter
- Cycle count on Android 14+
- Android time-to-full estimate
- State of Health on Android 16 when exposed

Battery-side charging power is **not** the same as charger/wall wattage because conversion losses and phone power consumption are outside this measurement.

## Privacy and battery impact

- No internet permission
- No ads
- No analytics
- No foreground service
- No scheduled polling
- Calibration/session data is stored locally in `SharedPreferences`

`RECEIVE_BOOT_COMPLETED` is used only to recover automatic calibration state after a reboot while charging.

## Build / install

1. Install a current Android Studio version.
2. Open the `BatteryLens` project folder.
3. Allow Android Studio to install Android SDK 36 and required Gradle components if prompted.
4. Connect the phone with Developer options + USB debugging enabled.
5. Press **Run** and choose the phone.

Project settings:

- `compileSdk 36`
- `targetSdk 36`
- `minSdk 28`
- Java 17
- Android Gradle Plugin 8.9.1

## Accuracy caveat

BatteryLens can only use information Android/OEM firmware exposes to ordinary apps. Charge-counter behavior, cycle-count availability, and direct State of Health support vary between devices. A service-centre diagnostic can have access to lower-level fuel-gauge information that third-party apps cannot access.

For calibration-based health, judge the result after **3+ long sessions**, not from a single run.
