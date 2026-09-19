# Improve monitored calibration, cycle transparency, and native UI

The previous app depended on manifest power broadcasts and two charge-counter endpoints, so missed transitions could leave calibration inactive or finish it against a much later reading. Non-finite temperature values could also truncate session JSON, and a hidden numeric battery property was presented as authoritative health.

This change adds user-enabled foreground monitoring with persisted checkpoints and explicit interruption handling, conservative capacity eligibility checks, and useful rejection history. It removes the hidden SoH lookup and snapshot-based headline health. Lifetime cycle readings show their source, with readable-file fallback and clear firmware limitations; app-observed equivalent cycles are tracked separately.

The native interface now has Overview, Sessions, and Settings tabs, a live gauge, accessible metric cards, monitoring progress, charge/temperature alerts, CSV export, and compatibility diagnostics. A new adaptive icon includes a themed variant.

New preview builds use a separate application ID. Production updates retain the existing ID and require the original signing key. New installations require enabling the visible monitor; existing automatic-mode preferences and historical sessions are preserved, with older sessions excluded from the new estimate until revalidated by new monitored sessions.

Validation: 37 measurement assertions, four persistence tests, Android SDK Java/resource compilation, D8 packaging, APK signature/alignment checks, and icon inspection pass. Standard Gradle dependency resolution was unavailable; lint and S24 FE runtime acceptance checks remain outstanding. See VALIDATION.md and TESTING.md.
