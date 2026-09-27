# BatteryLens 3.3 validation

## Completed on September 26, 2026

- Compiled with Android API 36, JDK 17, AAPT2, D8 and build-tools 35.0.0. Aligned and verified the signed preview APK (`com.darren.batterylens.preview33`, version 3.3-preview).
- Ran 84 pure measurement checks, including the five common protection limits, multi-point capacity fitting, pauses, hotspot-related reversals and repeated uncommon charge-stop candidates.
- Ran 21 JUnit tests across sessions, trends, insight calculations and alert rules. Tests cover screen/idle separation, charging and monitoring gaps, missing or legacy data, temperature exposure, and persistence of hotspot and voltage context.

## Physical-device validation still needed

BatteryLens was not run on a Galaxy S24 FE in this environment. See [TESTING.md](TESTING.md) for charge-limit learning, Android's reported charging status, seven-day history, background monitoring, hotspot comparison and runtime prediction checks. Both battery health and runtime remain estimates derived from readings the phone actually exposes. Samsung's Battery Protection setting and per-app battery attribution remain outside this app's control.
