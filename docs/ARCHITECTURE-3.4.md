# Architecture and complexity

`BatteryReadings` reads supported Android sensors and preserves unavailable values. `CalibrationStore` owns charging-session continuity and saved history; `CapacityEstimator` owns the pure robust capacity fit. `TrendSamplingPolicy` decides when a trend point is worth keeping. `TrendStore` serializes work on one background thread, while `TrendRepository` owns SQLite persistence and one-time migration. `InsightAnalysis` and `TrendAnalysis` consume immutable snapshots; `CsvExports` owns file formatting; `MainActivity` renders results after the worker returns.

| Operation | Work | Reason |
| --- | --- | --- |
| Decide whether to retain a point | O(1) | Compare the new reading to the latest point. |
| Append a reading | O(log N) indexed write; N ≤ 2,300 | SQLite uses a time index. No complete history rewrite occurs per point. |
| Read/chart k points | O(k) | Drawing or exporting k readings must visit them. Full chart reads do not run on every five-second repaint. |
| Fit charging capacity | O(n + B²), B ≤ 26 | A linear pass selects SOC bins; at most 325 wide-separation comparisons remain. Persisted new sessions store at most roughly 26 levels. |
| Render unchanged session journal | O(1) | Cache the parsed 60-session snapshot until its saved JSON changes. |

Android compatibility: `minSdk 28` supports Android 9 and therefore Android 12–17; `targetSdk 36` and `compileSdk 36` use the stable toolchain for this preview. The app uses `PowerManager.isInteractive` and SQLite on the supported range. Android 13's runtime notification request and receiver flag are guarded at API 33; Android 14's public cycle-count broadcast and special-use foreground-service type are guarded at API 34. The monitor is started by user-visible app interaction. A boot receiver closes an interrupted session; it does not launch a service from the background. Android 17 has new API 37 behaviors for apps targeting 37; this preview targets 36, has no networking, and needs on-device verification on Android 17.

Migration: on first database access, the worker imports `live_trends_v1` into the local database transaction. Only after a successful transaction does it mark migration complete and remove the older preference. Retrying after a process death replaces same-timestamp readings rather than duplicating them. Session preferences retain their existing keys and format. The database is capped at 2,300 points and seven days.
