# BatteryLens improvement plan · 3.1

Baseline: GitHub main `596980de02b349817142cf2fc8807f33dc3be634`.

## Product direction

Keep BatteryLens small, offline, and useful during ordinary charging. Make observations understandable before adding more health claims. Existing capacity-validation and cycle-source rules stay in place. No copied third-party implementation or assets are used.

## Implemented in this iteration

| Priority | Problem | Change |
| --- | --- | --- |
| P0 | A pasted URL in `temperatureC()` prevents compilation | Restore valid Java and verify a build |
| P1 | A live number gives no recent context | Add level/current/temperature charts, 1/6/24-hour windows, gaps, coverage and observed drain summaries |
| P1 | Only five history entries were shown in a text block | Add a journal with all 60 retained sessions, filters, summary and detail dialogs |
| P1 | Current and voltage were buried in diagnostics | Add readable Overview tiles with signed current and explicit units |
| P1 | Fixed heat threshold and no low-battery reminders | Add selected temperature and low-battery thresholds, persistent hysteresis, notification-block checks |
| P1 | No portable raw observation history | Add CSV export and separate clear action for live readings |
| P2 | Recent rejected records could hide eligible chart points | Chart the latest 12 eligible records across the retained history |
| P2 | Source could accept invalid level percentages | Require a level within the reported scale |

## Research and design decisions

Reviewed September 22, 2026:

- [ABattery](https://github.com/abanana84/abattery): useful reference for a current chart and honest labels for estimated/unavailable battery properties. Adopted those interaction principles with an original implementation.
- [Wattz](https://github.com/dubrowgn/wattz): useful reference for readable electrical readings and explaining that battery-side power differs from an external charger meter. Kept Android's signed current visible, without guessing a vendor-specific scaling correction.
- [Android BatteryManager](https://developer.android.com/reference/android/os/BatteryManager): current values use microamps, charge counter uses microamp-hours; unsupported properties have sentinels. The standard cycle-count extra is device supplied. Preserve these distinctions and missing values.

## Data and measurement rules

Live readings stay in device-local preferences. Collection adds at most one point per minute, with an exception for changes in plugged state. Retain at most 1,440 points within a rolling 24-hour window. Collection adds no service, permissions, wake lock, alarm, network access, or faster background polling.

Charts connect observations only when monotonic time and boot count indicate continuity within ten minutes and the wall clock agrees within five seconds. Large gaps and clock edits never create continuous lines. The summary's observed duration is the sum of connected intervals, not the full chosen window.

Observed drain requires at least thirty minutes of continuous eligible unplugged intervals and two percentage points of total drop. Intervals crossing a charging transition, increasing level, invalid level, or a drop above five points are excluded. This rate is a coarse SOC observation, not an independently measured current average or remaining-time promise.

Temperature and low-battery thresholds are personal reminders. High alerts rearm below threshold minus 3°C; low alerts rearm above threshold plus 3 points or after connection to power. Charge alerts rearm when unplugged or below target minus 2 points. State persists across service recreation; changing thresholds resets the latches. Android may suppress delivery; monitoring and notification permission are required.

## Next iteration after phone validation

1. Test S24 FE screen-off behaviour, reported units/signs, service interruption and alert delivery before changing any OEM-specific logic.
2. Consider a home-screen widget with coarse refresh and a Quick Settings shortcut for starting/stopping the existing monitor.
3. Add charge-source metadata to new sessions before comparing wired and wireless charging; do not infer it for old sessions.
4. Consider opt-in longer history using a database after measuring storage and background overhead.
5. Add light/system theme and full string resources/localization after validating navigation and large-font layouts.

Not planned: battery "repair", forced hardware calibration, unsupported lifetime-cycle guesses, or charger control without a documented platform capability.
