# Validation of the prepared BatteryLens 3.0 preview

Prepared from `Darren7218/BatteryLens` main commit `2b621cf284b7e1cee9685e800aa0967586ed8970`.

## Passed in the build environment

- 37 executable measurement regression assertions (JDK 17).
- Four JUnit session serialization tests using `org.json:json:20240303`, including non-finite values and pre-3.0 history migration. These were run directly with JUnit because Gradle dependency resolution failed.
- Production Java compilation against the real Android 36 SDK, including generated R resource symbols during APK packaging.
- AAPT2 resource compilation and manifest linking, D8 dex generation, and APK packaging using Android build tools 35.0.0.
- APK signature verification (v3, compatible with minimum API 28) and zip alignment.
- Packaged manifest inspection: `com.darren.batterylens.preview`, label `BatteryLens Preview`, version `3.0-preview`, minimum API 28, target API 36, correct activity class, and no internet permission.
- Resource XML parsing and `git diff --check`.
- Visual inspection of the icon rendered from its vector paths.

## Not verified here

- Standard Gradle tasks / Android lint: plugin `com.android.application:8.9.1` could not be resolved in this environment. The wrapper was aligned to Gradle 8.11.1. The included GitHub Actions workflow runs standard Gradle tests/build/lint where dependencies are available.
- Actual Activity rendering, TalkBack behavior, foreground-service lifecycle, notifications and CSV picker behavior on a phone/emulator.
- Real S24 FE charge-counter behavior, screen-off observation continuity, One UI service restrictions and lifetime cycle-count availability. No physical device was attached.
- Release signing with the owner's existing key or updating the existing production app.

The preview is a test build with separate application data. It does not contain your old calibration history. Collect a new monitored session on the S24 FE and use Settings → Check compatibility → Copy device diagnostics to confirm what the firmware exposes. See TESTING.md for the acceptance checks.
