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
11. Check all three tabs with large system fonts, portrait/landscape, gesture and three-button navigation, dark themed launcher icons, notification permission denied, and TalkBack.
12. Clear history through the confirmation dialog. Confirm observed cycles remain and the active calibration is canceled. If automatic monitoring is enabled, the next observation may begin a new session.

## Measurement limitations

This is app-level capacity estimation, not fuel-gauge hardware calibration. Android's charge counter can itself be inferred from SOC; repeated agreement does not prove independent accuracy. Conservative temperature, continuity and duration rules can leave some sessions history-only. The monitor uses no wake lock; Android/OEM suspension may produce gaps. Lifetime cycle access depends on firmware. Observed cycles exclude unmonitored time and may include fuel-gauge rounding; they are never promoted to a lifetime hardware count.
