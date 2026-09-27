# BatteryLens 3.4 validation

## Completed on September 26, 2026

- Compiled and signed the preview against stable Android API 36 with JDK 17 and build-tools 35.0.0. Verified its APK signature, package ID `com.darren.batterylens.preview33`, version code 8 and minimum API 28.
- Ran 86 measurement checks, including long synthetic charging histories and an upper bound on fitting comparisons.
- Ran 25 JUnit tests for sampling, sessions, CSV export, alerts and insight logic. Pure tests cover five-minute samples, immediate context changes, missing readings and quoted text.
- Exercised the bounded retention SQL against SQLite with 2,500 synthetic rows: it kept the newest 2,300, with unknown legacy screen state remaining null. Android database migration still requires device verification.
- Compared the preview signing certificate to the 3.3 preview; their SHA-256 certificate digest matches, allowing an in-place APK update.

## Device testing still required

No Android 12–17 emulator or physical Galaxy S24 FE was available. On an existing 3.3 installation, confirm the 3.4 update retains session history and imports the prior seven-day chart, then monitor with the screen off, denied notification permission and after a reboot. Repeat on Android 12, 13, 14, 15, 16 and 17 devices when available. SQLite corruption, low storage and OEM background-kill behavior can still interrupt history collection; the live dashboard remains usable when trend storage is unavailable.
