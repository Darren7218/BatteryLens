# Feature research and limits

Forum reports informed feature priorities, not battery-science claims:

- [Galaxy S24 charge-limit discussion](https://www.reddit.com/r/GalaxyS24/comments/1u0z7u9/charging_till_80859095/) includes reports of 80–95% protection and large swings in app-estimated health after changing charging behavior. BatteryLens therefore uses multiple wide charging intervals, displays confidence, and does not equate a protected endpoint with lost battery capacity.
- [Android battery-monitor discussion](https://www.reddit.com/r/ShowYourApp/comments/1vxtsyd/i_built_vizo_battery_an_android_battery_monitor/) describes interest in relating drain to screen state and temperature. BatteryLens records those factors without claiming a particular app caused drain.
- [BetterBatteryStats discussion](https://www.reddit.com/r/androidapps/comments/1suqzu1/betterbatterystats_is_back/) shows demand for app-level diagnostics, but that requires separate permissions and platform constraints. This version instead sends users to Android's battery usage screen for per-app evidence.

[Android's BatteryManager reference](https://developer.android.com/reference/android/os/BatteryManager) documents current, charge/energy counters, charging status and cycle count; values can be unsupported by particular hardware. [PowerManager.isInteractive](https://developer.android.com/reference/android/os/PowerManager#isInteractive()) reports the device's interactive state, which is used as context for observed drain. It is not an exact screen-on-time or app-usage meter. Hotspot use is marked by the user; no hidden API or invasive permission is used to infer tethering.
