# BatteryLens 3.1 validation

Date: September 22, 2026. Base revision: `596980de02b349817142cf2fc8807f33dc3be634`.

## Verified

- JDK 17, Gradle 8.11.1, Android platform 36, build tools 35.0.0.
- `testDebugUnitTest assembleDebug lintDebug`: BUILD SUCCESSFUL, 47 tasks executed.
- 16 JUnit tests passed, zero failures/errors/skips: 4 session serialization tests, 6 trend/continuity tests, 6 alert tests.
- `bash tools/test-core.sh`: all 37 existing measurement regression cases passed.
- SDK-only preview build also compiled, aligned, and passed APK signature verification.
- `git diff --check`: passed.
- The delivered APK is the Gradle debug build, version 3.1-preview (code 5), application ID `com.darren.batterylens.preview`.

Lint: **0 errors, 37 warnings**. Warnings concern text localization, allocations in chart drawing, unused resources, an obsolete resource qualifier, and explicit Android 12 data-extraction configuration. They are retained in the included lint report; warnings have not been hidden with a baseline or blanket suppressions. The UI remains English and dark themed in this iteration.

## Not verified here

No Android emulator or physical phone was used. This is a compiled preview, not a device-certified release. Runtime layout, TalkBack, notification delivery, document-provider export, Samsung sensor behaviour, and screen-off monitoring still require the acceptance checks in `docs/TESTING.md`. No claim is made about cycle-count availability or battery-capacity accuracy on a particular phone.

The connected GitHub account provides read access only. Changes are delivered as source and patch; nothing was pushed and no pull request was opened.

## Reports

The delivery package contains Gradle JUnit XML, the HTML test report, lint HTML/XML/text, and this record. Build directories and machine-specific SDK paths are excluded from the source archive.
