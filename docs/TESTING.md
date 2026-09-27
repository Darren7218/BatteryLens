# BatteryLens 3.0 verification

## Automated checks

Run `bash tools/test-core.sh` with a full JDK 17. It checks capacity unit conversion, short sessions, counter resets, implausible measurements, missing/hot/cold temperatures, reboot and monitoring gaps, observed equivalent-cycle accounting, and cycle-counter parsing.

Run `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug` with Android SDK 36 and build tools 35.0.0. CI runs these gates on pushes and pull requests and uploads a debug APK. It does not sign a production release.

## S24 FE acceptance checks (physical device required)

1. Install without removing the previous app if the signing key matches. Confirm the 4565 mAh baseline and existing history remain. Old sessions are retained as history only because they lack continuity/temperature validation.
2. Enable Automatic monitoring. Accept notifications. Confirm the ongoing notification appears, survives navigating away, and has a Stop monitoring action.
3. In Android app settings, allow unrestricted battery use if needed. Check Samsung Sleeping/Deep sleeping apps. Start around 20%, lock the screen, charge normally to approximately 80%, then unplug. Battery Protection may pause charging at 80%; that is supported.
4. Reopen the app. Confirm the session is in history, including observed endpoints and sample count. The last observed connected level can be slightly below the actual unplug level. If rejected, confirm the reason is useful. A gap over ten minutes ends the segment at its last checkpoint; no later reading should fill that gap.
5. Start another charge, reboot mid-session, then reopen. Confirm the old segment ends at its last checkpoint and the app resumes from a new starting point. Repeat with a force-stop and a gap over ten minutes.
6. Disable automatic monitoring, start/finish a manual session, and unplug during a manual session. Confirm it finishes at the last charging checkpoint. Verify no duplicate sessions appear after reopening.
7. Tap Check compatibility. Copy diagnostics. Compare an exposed lifetime count to the manufacturer's diagnostic display if available. Unavailable must stay unavailable; zero must include the firmware-placeholder caveat. No root/ADB permission is requested.
8. Use the phone unplugged while monitoring. Confirm only observed small SOC drops increment the separate observed cycle counter. Stop monitoring and wait over ten minutes; the unobserved interval must not be counted after resuming.
9. Enable alerts, choose a charge target, and reach it. Confirm an alert once per connected target crossing; it must not repeat on every sample. A temperature alert should trigger at 42°C and rearm below 39°C. Test with simulated readings rather than intentionally heating the device.
10. Export CSV and cancel an export; rotate while the document picker is open, then export again. Open the result in a spreadsheet and verify numeric columns, reasons, and all retained sessions.
11. Check all four tabs with large system fonts, portrait/landscape, gesture and three-button navigation, dark themed launcher icons, notification permission denied, and TalkBack.
12. Clear history through the confirmation dialog. Confirm observed cycles remain and the active calibration is canceled. If automatic monitoring is enabled, the next observation may begin a new session.

## Measurement limitations

This is app-level capacity estimation, not fuel-gauge hardware calibration. Android's charge counter can itself be inferred from SOC; repeated agreement does not prove independent accuracy. Conservative temperature, continuity and duration rules can leave some sessions history-only. The monitor uses no wake lock; Android/OEM suspension may produce gaps. Lifetime cycle access depends on firmware. Observed cycles exclude unmonitored time and may include fuel-gauge rounding; they are never promoted to a lifetime hardware count.

## 3.1 acceptance checks (device testing still required)

- Open every tab at 320dp width, landscape, and 200% font size. Check navigation labels, text wrapping, scrolling and TalkBack descriptions.
- Leave Overview open for two minutes; Trends should contain minute-spaced observations. Switch level/current/temperature and 1/6/24-hour windows, rotate, and verify selections survive.
- Turn monitoring off and leave the app for over ten minutes; reopening must leave a chart gap. Repeat across reboot and a clock edit. No drain rate should bridge the gap.
- Export chart CSV via the document picker, cancel once, then export successfully. Unsupported sensors must be blank, never zero-filled or NaN. Clear live history and verify capacity sessions remain.
- Complete both short and eligible charging sessions. Journal filters must show all matching retained records; tapping one must show duration, measurements, source and rejection reason.
- Set low-battery and temperature reminders. Verify one alert at threshold, no repeated alert on refresh, and rearming only after recovery. Recreate the service at the same reading and verify it does not repeat a delivered alert.
- Deny notifications, cross a threshold, then enable notifications. The previously undelivered alert should still be eligible. Check the alert channel independently from app-wide permission.
- Keep the production app installed when testing the preview; their application IDs and data are separate.

## 3.2 charge-protection and hotspot checks (S24 FE required)

- In Settings, select Auto-detect, then repeat a charging stop for each protection limit available on your phone (80%, 85%, 90%, 95%, or 100%). A stopped reading near the limit should count as a pause; a thermal stop several points below it should not. Repeat with the matching manual setting. Changing the separate charge notification target should have no effect on measured capacity.

- Set Samsung Battery Protection Maximum to **90%**. Start at about 20%, leave automatic monitoring on, charge past 50% and up to 90%, then leave the charger attached. After roughly 10 minutes of a reported charging stop at 90%, the app should save the session through the last advancing checkpoint, show the observed pause, and avoid creating a second session while still plugged in. It must not dilute the measured capacity.
- Keep the hotspot on for one test session. If charging briefly pauses or SOC falls by 1–2%, the displayed progress should keep the highest measured level. An unstable session should be saved as history with an explanation, not lower the headline health number.
- Try slow charging with a lower-wattage charger across 30+ percentage points. Check that duration alone does not alter estimated mAh. If the monitor is suspended for over ten minutes, the journal should show an interrupted segment rather than claim an uninterrupted measurement.
- Check the session detail: estimated capacity, endpoint-only comparison, number of widely spaced comparisons, within-run spread, reversals, and paused time. Verify CSV has these fields. Three agreeing *new* sessions are required before health appears.
- Existing 3.1 endpoint estimates should remain visible on a properly signed production update and be excluded from the new estimate. The 3.2 preview has its own package ID and starts with separate data, so compare its journal to the installed 3.1 preview manually.

## 3.3 general monitoring checks

- Enable monitoring and compare the Overview's interactive/idle drain after at least 20 minutes of interactive use and at least an hour of unplugged idle time. A fresh installation or missing readings must show Collecting, not a fabricated rate. Pause monitoring for over ten minutes; that gap must not enter the rate or runtime estimate.
- Heat the phone only through normal usage and verify that the temperature exposure card reports intervals actually sampled at or above 35/40°C. Compare battery-side charging speed while using the hotspot and with the screen off; the app must describe net battery gain, not promise charger input watts or a specific cable diagnosis.
- In Settings, toggle “I am using hotspot” during a charge. The journal and exported CSV should show a manual marker. Turn it off when done, then check that later samples no longer carry the marker.
- Set a custom limit, for example 73%, and confirm a reported charging stop near it closes a monitored session after approximately ten minutes. With Auto, two separate eligible sessions at an uncommon, sustained limit followed by unplugging should display a learned limit. One thermal stop or resumed charge must not teach a custom limit.
- Check the 7-day chart window, dates at both ends, CSV interactive/hotspot columns, session power source, voltage endpoints and approximate battery energy. Legacy readings must keep the screen state unknown.

## 3.4 storage and OS checks

- Install the supplied 3.4 APK over the supplied 3.3 preview without clearing app data. Confirm old charging sessions still appear and earlier chart points survive one-time migration. Reopen twice; rows must not duplicate. Export the CSV to compare retained values. A separate Android Studio debug APK may use a different certificate and cannot upgrade this preview.
- Leave automatic monitoring enabled for a day and watch interactive/idle transitions and charging/unplugging. The chart should add points roughly every five minutes or on a context transition. Scroll and change 1h/6h/24h/7d windows without an extended main-thread pause. Clearing trends should remove only chart data and allow new points to appear.
- Android 12: confirm the monitor starts from the visible app and shows a foreground notification. Android 13: test granted and denied `POST_NOTIFICATIONS` with no crash. Android 14–17: test cycle count availability and the special-use service. Reboot on each OS: the app should preserve saved data and resume only after you open it. All device checks are pending physical or emulator validation.
