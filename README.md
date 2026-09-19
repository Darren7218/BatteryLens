# BatteryLens 3.0

An offline Android battery monitor with transparent capacity estimates, device compatibility diagnostics, and a lightweight native UI. Supports Android 9+; includes a Galaxy S24 FE rated-capacity preset.

## What changed

- User-enabled foreground monitoring with an ongoing notification and Stop action. Battery broadcasts plus a once-per-minute checkpoint while running replace the unreliable manifest-only plug/unplug assumption. The visible dashboard refreshes every five seconds.
- Charging progress survives process interruptions. A reboot or a monitoring gap over ten minutes ends the previous segment at its last observed charging checkpoint, never at a later discharge reading.
- Capacity estimates require 30%+ gained, at least four observations, 10 minutes to 18 hours of continuous observation, temperatures within 10–42°C, and plausible counter values. Invalid/short sessions stay visible with reasons.
- Fixed non-finite temperature/capacity JSON handling so a missing sensor cannot silently truncate saved records.
- Removed the undocumented numeric State-of-Health property lookup and snapshot-based headline health. The headline waits for monitored data; snapshot capacity remains diagnostic only.
- Reports lifetime cycles from Android's public extra or a readable, explicitly named cycle-count file. Shows the source and explains unavailable/placeholder-zero readings. No root, shell commands, hidden API access, or guessed OEM units.
- Separately records **observed equivalent cycles** from small continuous unplugged SOC drops. This excludes unmonitored time and is never labeled as lifetime cycle count.
- Optional charge-target notifications (70/80/85/90/100%) and a 42°C temperature notification. The app cannot stop charging.
- Three tabs: Overview, Sessions, Settings. New live gauge, responsive metric cards, monitoring progress, adaptive lens/lightning icon, and themed-icon support.
- Export retained sessions as CSV using Android's document picker; clear history with confirmation; copy detailed device diagnostics.
- Compatible Gradle 8.11.1 wrapper for AGP 8.9.1, executable wrapper, and GitHub Actions build/lint workflow.

## Start monitoring on a Galaxy S24 FE

1. Open BatteryLens and enable **Automatic monitoring**. Allow notifications so status and alerts remain visible.
2. If needed, open **Settings → Open Android app settings**, set battery usage to **Unrestricted**, and remove BatteryLens from Samsung Sleeping/Deep sleeping apps. Labels vary by One UI version.
3. Charge normally across 30+ percentage points. A 20→80% charge is useful and works with Battery Protection. There is no need to drain to zero or charge to 100%.
4. Unplug and check **Sessions**. The app uses its last observed connected checkpoint; rejected sessions explain why they were excluded. Judge the trend after at least three long sessions.
5. After rebooting or force-stopping the app, open it again to resume. Android can still suspend or terminate monitoring; the app does not claim guaranteed collection while stopped.

New installations start with monitoring off. Existing automatic-mode preferences are preserved. The monitor stays enabled while unplugged so it can detect the next charging session; stop it from the switch or notification. No wake lock or exact alarm is used. If Android sleeps long enough to miss checkpoints, incomplete segments may be retained without contributing to health.

## Capacity and health

`full capacity (mAh) = (ending charge µAh − starting charge µAh) / (10 × percentage points gained)`

`estimated health (%) = full capacity / rated baseline × 100`

The estimate uses up to 16 recent eligible sessions, a median, and MAD-based outlier filtering. **Repeatability describes agreement between sessions, not certified accuracy.** A fuel gauge may derive its charge counter from SOC, so these measurements are not necessarily independent. Neither calibration sessions nor this app reset/recalibrate battery hardware.

S24 FE models `SM-S721*` prefill **4565 mAh rated**; Samsung advertises 4700 mAh typical. A small result over 100% is possible because rated and typical capacity differ. The baseline is editable. Old history is preserved, but pre-3.0 sessions are history-only because they do not carry the new validation data.

## Why some phones show cycles and others do not

Android 14 added `BatteryManager.EXTRA_CYCLE_COUNT`, but firmware determines what is supplied. BatteryLens first reads it, then can check these ordinary-app-readable files:

- `/sys/class/power_supply/battery/cycle_count`
- `/sys/class/power_supply/bms/cycle_count`
- `/sys/class/power_supply/battery/charge_cycles`

Permission denial/missing files are normal. The app never fabricates a lifetime count from battery health. A reported zero is shown with a caveat because firmware may publish a default zero. An accessible positive file count can take precedence over that zero. OEM fields with undocumented scaling are intentionally not interpreted.

Use **Settings → Check compatibility → Copy device diagnostics** to report your model, Android version, public readings, probe results, and monitor/notification state. Observed cycles in the other tile count only the SOC drops the app actually sees; they are an approximate lower-coverage usage record, not lifetime history.

## Privacy

No internet permission, accounts, ads or analytics. Data stays in device-local SharedPreferences; automatic Android backup is disabled because calibration and lifetime-cycle context belong to one battery. CSV export writes only to a location selected by the user.

Foreground service permissions support visible monitoring. Notification permission supports monitoring status and optional alerts. Boot reception only closes the last checkpoint and marks monitoring as needing reopening. The `specialUse` foreground-service declaration explains battery monitoring; publishing on Google Play may require review of that use case.

## Build and test

Use JDK 17 and Android SDK 36 with build tools 35.0.0. Open this project in Android Studio or run:

```sh
bash tools/test-core.sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Debug/preview builds install as **BatteryLens Preview** (`com.darren.batterylens.preview`) alongside the existing app, with separate data. Release builds retain `com.darren.batterylens`.

The debug APK is at `app/build/outputs/apk/debug/app-debug.apk`. CI also uploads it as `BatteryLens-debug`. Debug builds are for testing. Updating the production installation requires the original signing key. Use the existing release key for a production update.

See [device acceptance checks](docs/TESTING.md) for S24 FE testing, including screen-off charging, reboot, notification denial and CSV export.

## Platform references

- [BatteryManager API and units](https://developer.android.com/reference/android/os/BatteryManager)
- [Android 8 background and broadcast restrictions](https://developer.android.com/about/versions/oreo/background)
- [Foreground-service types and specialUse](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [Foreground-service background-start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)

### SDK-only preview build

If Gradle dependencies cannot be downloaded, this app has no runtime library dependencies and can also be packaged directly with the official Android SDK tools:

```sh
# Set JAVA_HOME to JDK 17 and ANDROID_HOME to the Android SDK first.
python3 tools/build-preview.py
```

This compiles resources with AAPT2, compiles Java against Android 36, converts bytecode with D8, aligns and test-signs the APK. The result is `app/build/outputs/apk/preview/BatteryLens-3.0-preview.apk`. It does not replace Gradle lint or physical-device testing. The test key stays in ignored build output; keep it locally to update a previously installed preview. Never use this key for a production release.
