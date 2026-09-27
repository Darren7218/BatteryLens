# BatteryLens 3.4

An offline Android battery monitor with transparent capacity estimates, device compatibility diagnostics, and a lightweight native UI. Supports Android 9+; includes a Galaxy S24 FE rated-capacity preset.

## New in 3.4

- Moved seven-day trend readings from an entire rewritten preferences array to a bounded, indexed SQLite database. Existing `live_trends_v1` readings are imported once on a signed update. Storage and trend analysis run on a serial background worker, away from the main UI thread.
- Split the pure sampling decision (`TrendSamplingPolicy`), trend persistence (`TrendRepository`), and robust charge fitting (`CapacityEstimator`) into focused classes. Session history is parsed only when the persisted version changes; chart and insight calculations run when new readings arrive or their time window expires.
- Capacity fitting first uses 4%-wide battery-level bins. Even a lengthy charge has at most 26 representatives and 325 pair comparisons. The noisy-endpoint and disagreement checks still apply. App-visible UI refreshes no longer rewrite the calibration checkpoint every five seconds while the background monitor is running.
- Android 12–17 compatibility is documented in [architecture and API notes](docs/ARCHITECTURE-3.4.md). The APK still supports Android 9+ (`minSdk 28`); feature-specific APIs are guarded by OS version. On Android 12+, a foreground monitor starts from the visible app, not the boot receiver.
- The SDK-only 3.4 preview **updates the 3.3 preview** under the same app ID and signing certificate, retaining its sessions and migrating trend history. Its test signing key is not included in source exports. See [validation](docs/VALIDATION-3.4.md).

## New in 3.3

- Today card separates observed interactive and idle drain, estimates remaining runtime from a rolling monitored drain rate, reports peak temperature and time spent at or above 35/40°C, and flags a recent idle drain increase only when an earlier baseline exists. Missing or short observations stay unavailable.
- Charging card shows net battery-side power and a cautious slow/normal/fast description, plus contextual reasons such as a hot battery, a high charge level, or a manually marked hotspot. It never claims to measure wall-charger output or identify a faulty cable.
- Trends retain up to seven days of five-minute samples with a new seven-day chart window. The health chart can show 7, 30, 90 days or lifetime (latest 12 eligible sessions per window). Interactive state and a manual hotspot marker are stored with readings and CSV; legacy trend points keep screen state unknown. The hotspot marker needs to be turned on/off by the user.
- Charging sessions now retain power source, voltage endpoints, approximate net energy and whether hotspot use was marked. Session CSV includes these fields.
- Measurement limits support a manually selected custom stop level from 50–100%, alongside Auto and 80/85/90/95/100%. Auto also learns an uncommon limit after two separate eligible sessions stop at approximately the same level for at least ten minutes and then unplug; a single thermal pause is not learned. Common protection limits remain recognized immediately after the phone reports charging stopped.
- The 3.3 preview uses its own app ID and local data so it can coexist with the 3.2 preview. Production releases retain the original package ID and can reuse existing session history when signed with the original key.

Forum reports informed the priorities: limited-charge health numbers can swing, and users want explanations for drain and slow charging. The app cannot identify per-app drain, infer hotspot state without your marker, or certify battery health from these readings. See [research notes](docs/RESEARCH-3.3.md) and [3.3 validation](docs/VALIDATION-3.3.md).

## New in 3.2

- Fits capacity from many widely spaced charge-counter and battery-level readings instead of letting a single suspect start/end reading pull the health estimate down. Shows the raw endpoint comparison and disagreement in session details/CSV.
- Holds the last advancing charge checkpoint when Battery Protection pauses at a selected 80/85/90/95/100% limit, or when the battery temporarily discharges while plugged in under heavy use such as hotspot. Time spent waiting at a recognized limit does not extend measured charging duration. Choose a limit in Settings or use automatic recognition; reaching 100% is unnecessary.
- Sessions with sparse, inconsistent or repeatedly reversing readings remain in the journal with a reason. Old 3.1 endpoint-only estimates remain visible but no longer determine the new health percentage, because their intermediate readings were not recorded.
- Requires three agreeing new sessions before a headline health percentage appears. One low or noisy charge is preliminary, not an authoritative diagnosis.
- The 3.2 preview uses its own app ID, so it can be installed beside the 3.1 preview without removing its data. A separately signed preview cannot update the old preview in place.

These changes improve measurement screening. They cannot correct a consistently inaccurate phone fuel gauge or establish certified battery health. If the 3.2 result is still low after three eligible charges, export the sessions for review or use Samsung's service diagnostics. See [3.2 validation](docs/VALIDATION-3.2.md) and [device checks](docs/TESTING.md).

## New in 3.1

- Four-page navigation: Overview, Trends, Sessions, Settings. Live signed current and voltage are visible on Overview.
- Local 24-hour battery level, current, and temperature charts with 1/6/24-hour windows. Roughly one sample per minute (plus power transitions), at most 1,440 points. Charts break across long monitoring gaps, reboots, and wall-clock changes. Missing sensors stay unavailable.
- Trend summaries show observed time, temperature range, and a conservative observed unplugged drain rate after at least 30 minutes and a 2-point drop. This is not a time-remaining prediction.
- Export retained live readings as CSV; clear chart history separately from capacity sessions.
- Charging journal shows all retained sessions with All / Eligible / History-only filters and detail dialogs for duration, net charge added, average net current, observations, temperature, and validation reason.
- Configurable temperature reminders (38/40/42/45°C) and optional low-battery reminders (10/15/20/25%). Existing charge-target alerts remain. Alert hysteresis survives service restarts; denied/blocked notifications do not consume an alert.
- Fixed a pasted URL that broke Java compilation; reject out-of-range battery percentages; capacity chart now selects the latest 12 eligible sessions across the retained history.

See [implementation plan and research](docs/IMPROVEMENT_PLAN.md) and [3.1 validation](docs/VALIDATION-3.1.md).

## Existing features

- User-enabled foreground monitoring with an ongoing notification and Stop action. Battery broadcasts plus a once-per-minute checkpoint while running replace the unreliable manifest-only plug/unplug assumption. The visible dashboard refreshes every five seconds.
- Charging progress survives process interruptions. A reboot or a monitoring gap over ten minutes ends the previous segment at its last observed charging checkpoint, never at a later discharge reading.
- Capacity estimates require 30%+ gained, at least four observations, 10 minutes to 48 hours of charging progress, temperatures within 10–42°C, and agreeing charge-counter readings. Invalid/short sessions stay visible with reasons.
- Fixed non-finite temperature/capacity JSON handling so a missing sensor cannot silently truncate saved records.
- Removed the undocumented numeric State-of-Health property lookup and snapshot-based headline health. The headline waits for monitored data; snapshot capacity remains diagnostic only.
- Reports lifetime cycles from Android's public extra or a readable, explicitly named cycle-count file. Shows the source and explains unavailable/placeholder-zero readings. No root, shell commands, hidden API access, or guessed OEM units.
- Separately records **observed equivalent cycles** from small continuous unplugged SOC drops. This excludes unmonitored time and is never labeled as lifetime cycle count.
- Optional charge-target, configurable temperature, and low-battery notifications. The app cannot stop charging.
- Four tabs: Overview, Trends, Sessions, Settings. New live gauge, responsive metric cards, monitoring progress, adaptive lens/lightning icon, and themed-icon support.
- Export retained sessions as CSV using Android's document picker; clear history with confirmation; copy detailed device diagnostics.
- Compatible Gradle 8.11.1 wrapper for AGP 8.9.1, executable wrapper, and GitHub Actions build/lint workflow.

## Start monitoring on a Galaxy S24 FE

1. Open BatteryLens and enable **Automatic monitoring**. Allow notifications so status and alerts remain visible.
2. If needed, open **Settings → Open Android app settings**, set battery usage to **Unrestricted**, and remove BatteryLens from Samsung Sleeping/Deep sleeping apps. Labels vary by One UI version.
3. In Settings, choose Auto-detect or the 80%, 85%, 90%, 95%, or 100% limit configured on your phone. Charge normally across 30+ percentage points below that limit; 20→80% is enough. There is no need to drain to zero or charge to 100%. Hotspot or slow charging can be monitored as long as the battery still gains charge and monitoring remains continuous. The app's separate notification charge target does not change Battery Protection.
4. Unplug and check **Sessions**. The app uses its last observed connected checkpoint; rejected sessions explain why they were excluded. Judge the trend after at least three long sessions.
5. After rebooting or force-stopping the app, open it again to resume. Android can still suspend or terminate monitoring; the app does not claim guaranteed collection while stopped.

New installations start with monitoring off. Existing automatic-mode preferences are preserved. The monitor stays enabled while unplugged so it can detect the next charging session; stop it from the switch or notification. No wake lock or exact alarm is used. If Android sleeps long enough to miss checkpoints, incomplete segments may be retained without contributing to health.

## Capacity and health

`capacity between two readings (mAh) = (later charge µAh − earlier charge µAh) / (10 × percentage points gained)`

`estimated health (%) = full capacity / rated baseline × 100`

BatteryLens takes the **median of many comparisons at least 12 percentage points apart** across each charging run. It rejects runs with over 15% relative within-run deviation or repeated reversals under load, then combines up to 16 eligible sessions using a median and MAD-based outlier filtering. The headline health number requires at least three eligible sessions that agree within 10% relative median absolute deviation. **Repeatability describes agreement, not certified accuracy.** A fuel gauge may derive its charge counter from SOC, so these measurements are not necessarily independent. Neither calibration sessions nor this app reset/recalibrate battery hardware.

S24 FE models `SM-S721*` prefill **4565 mAh rated**; Samsung advertises 4700 mAh typical. A small result over 100% is possible because rated and typical capacity differ. The baseline is editable. Pre-3.2 history is preserved for reference but excluded from the revised health estimate because it lacks intermediate charge readings.

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

The provided SDK-only preview installs as **BatteryLens 3.4 Preview** (`com.darren.batterylens.preview33`) and updates the provided 3.3 APK while preserving its app data. An Android Studio debug build uses Android Studio's own debug certificate and cannot replace the supplied preview unless signed with the same local test key. Release builds retain `com.darren.batterylens`; updating production requires its original signing key.

The debug APK is at `app/build/outputs/apk/debug/app-debug.apk`. CI also uploads it as `BatteryLens-debug`. Debug builds are for testing. Updating the production installation requires the original signing key. Use the existing release key for a production update.

See [device acceptance checks](docs/TESTING.md) for S24 FE testing, including screen-off charging, reboot, notification denial and CSV export.

## Platform references

- [BatteryManager API and units](https://developer.android.com/reference/android/os/BatteryManager)
- [Android 8 background and broadcast restrictions](https://developer.android.com/about/versions/oreo/background)
- [Foreground-service types and specialUse](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [Foreground-service background-start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
- [Samsung Malaysia: Battery protection levels and charging pauses](https://www.samsung.com/my/support/galaxy-battery/battery-protection-tips/)

### SDK-only preview build

If Gradle dependencies cannot be downloaded, this app has no runtime library dependencies and can also be packaged directly with the official Android SDK tools:

```sh
# Set JAVA_HOME to JDK 17 and ANDROID_HOME to the Android SDK first.
python3 tools/build-preview.py
```

This compiles resources with AAPT2, compiles Java against Android 36, converts bytecode with D8, aligns and test-signs the APK. The result is `app/build/outputs/apk/preview/BatteryLens-3.4-preview.apk`. It does not replace Gradle lint or physical-device testing. Keep the 3.3 preview test key in ignored build output to sign an in-place update; without it a new local build cannot replace that installed preview. Never use this key for a production release.
