# BatteryLens 3.2 validation

## Completed on September 26, 2026

- Compiled the app against Android API 36 with JDK 17, D8, AAPT2 and build-tools 35.0.0. Signed and verified the preview APK; package `com.darren.batterylens.preview32`, version `3.2-preview`, minimum API 28, target API 36.
- Ran 80 pure measurement regression checks. Synthetic charges ending at 80, 85, 90, 95 and 100 percent produce the same capacity estimate. The checks also cover slow charging, load-related level dips, bad endpoints, repeated reversals, sparse readings and false protection pauses.
- Ran 17 JUnit checks for saved sessions, trend analysis and alert rules, including migration of old endpoint-only estimates to history and persistence of paused and actual charging duration.

## Device checks still needed

Install on a Samsung Galaxy S24 FE and perform the scenarios in [TESTING.md](TESTING.md). In particular, verify that One UI reports `NOT_CHARGING` or `FULL` while holding each enabled protection limit, and that Android supplies a usable charge counter during hotspot use. The APK was built and verified without an emulator or a physical S24 FE; no battery-health accuracy claim has been established by a device test. Compare three eligible sessions and service diagnostics before interpreting a low percentage.
