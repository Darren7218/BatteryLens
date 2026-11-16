package com.darren.batterylens;

import android.app.ForegroundServiceStartNotAllowedException;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.drawable.Icon;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

/** Lightweight foreground monitor with adaptive sampling, debounced updates, and restart recovery. */
public class BatteryMonitorService extends Service {
    private static final String CHANNEL = "battery_monitor";
    private static final String ALERTS = "battery_alerts";
    private static final String STOP = "com.darren.batterylens.STOP_MONITOR";
    private static final String REFRESH_NOTIF = "com.darren.batterylens.REFRESH_NOTIFICATION";
    private static final int NOTIF_MONITOR = 100;
    private static final int NOTIF_RESUME = 104;
    private static final long PLUGGED_TICK_MS = 60_000L;
    private static final long SCREEN_ON_TICK_MS = TrendSamplingPolicy.SAMPLE_MS;
    private static final long DEBOUNCE_MS = 55_000L;

    static volatile boolean running;
    static volatile boolean starting;
    private static volatile Runnable uiStateListener;

    static void setUiStateListener(Runnable listener) {
        uiStateListener = listener;
    }

    private static void notifyUiChanged() {
        Runnable listener = uiStateListener;
        if (listener == null) return;
        if (Looper.myLooper() == Looper.getMainLooper()) listener.run();
        else AppWorker.postMain(listener);
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AlertRules alertRules = new AlertRules();
    private String alertConfiguration = "";
    private boolean registered;
    private boolean selfStopping;
    private boolean interactiveState;
    private boolean powerManagerAvailable;

    private PendingIntent openAppIntent;
    private PendingIntent stopMonitorIntent;
    private String lastNotificationDetail = "";

    private long lastSampleElapsed;
    private int lastLevel = Integer.MIN_VALUE;
    private int lastStatus = Integer.MIN_VALUE;
    private int lastPlugged = Integer.MIN_VALUE;
    private int lastTempTenth = Integer.MIN_VALUE;
    private long currentTickMs = -1L;

    private final BroadcastReceiver systemReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) {
            String action = intent == null ? null : intent.getAction();
            if (Intent.ACTION_SCREEN_ON.equals(action)) {
                interactiveState = true;
                sample(true, null);
            } else if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                interactiveState = false;
                sample(true, null);
            } else if (Intent.ACTION_POWER_CONNECTED.equals(action)
                    || Intent.ACTION_POWER_DISCONNECTED.equals(action)) {
                sample(true, null);
            } else {
                sample(false, intent);
            }
        }
    };

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            sample(true, null);
        }
    };

    static boolean start(Context context) {
        return startInternal(context, false);
    }

    static boolean startFromBackground(Context context) {
        return startInternal(context, true);
    }

    static void stopMonitoring(Context context) {
        if (!running && !starting) return;
        Context app = context.getApplicationContext();
        starting = false;
        app.stopService(new Intent(app, BatteryMonitorService.class));
    }

    static void refreshNotification(Context context) {
        if (!running) return;
        Context app = context.getApplicationContext();
        try {
            app.startService(new Intent(app, BatteryMonitorService.class).setAction(REFRESH_NOTIF));
        } catch (RuntimeException ignored) { }
    }

    private static boolean startInternal(Context context, boolean fromBackground) {
        Context app = context.getApplicationContext();
        try {
            if (!running) starting = true;
            Intent intent = new Intent(app, BatteryMonitorService.class);
            if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(intent);
            else app.startService(intent);
            return true;
        } catch (RuntimeException e) {
            starting = false;
            if (fromBackground) {
                boolean fgsBlocked = Build.VERSION.SDK_INT >= 31 && isFgsStartBlocked(e);
                notifyTapToResume(app, fgsBlocked);
            }
            return false;
        }
    }

    @android.annotation.TargetApi(31)
    private static boolean isFgsStartBlocked(RuntimeException e) {
        return e instanceof ForegroundServiceStartNotAllowedException;
    }

    static void notifyTapToResume(Context context, boolean blockedBySystem) {
        Context app = context.getApplicationContext();
        SharedPreferences rt = CalibrationStore.runtimePrefs(app);
        if (rt.getBoolean("boot_block_notified", false)) return;
        NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null || !nm.areNotificationsEnabled()) return;
        ensureChannels(nm);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent open = PendingIntent.getActivity(app, 2, new Intent(app, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP), flags);
        String msg = blockedBySystem
                ? "Android paused background startup after restart. Tap to resume automatic health monitoring."
                : "Tap to resume automatic battery health monitoring.";
        Notification n = builderFor(app, ALERTS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("BatteryLens monitoring paused")
                .setContentText(msg)
                .setStyle(new Notification.BigTextStyle().bigText(msg))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build();
        nm.notify(NOTIF_RESUME, n);
        rt.edit().putBoolean("boot_block_notified", true).apply();
    }

    private static void ensureChannels(NotificationManager manager) {
        if (Build.VERSION.SDK_INT < 26 || manager == null) return;
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Battery monitoring", NotificationManager.IMPORTANCE_LOW));
        manager.createNotificationChannel(new NotificationChannel(ALERTS, "Charge and temperature alerts", NotificationManager.IMPORTANCE_DEFAULT));
    }

    private static Notification.Builder builderFor(Context c, String channelId) {
        if (Build.VERSION.SDK_INT >= 26) return new Notification.Builder(c, channelId);
        return builderForLegacy(c, channelId);
    }

    @SuppressWarnings("deprecation")
    private static Notification.Builder builderForLegacy(Context c, String channelId) {
        Notification.Builder b = new Notification.Builder(c);
        b.setPriority(CHANNEL.equals(channelId) ? Notification.PRIORITY_LOW : Notification.PRIORITY_DEFAULT);
        return b;
    }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        ensureChannels(manager);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        openAppIntent = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), flags);
        stopMonitorIntent = PendingIntent.getService(this, 1, new Intent(this, BatteryMonitorService.class).setAction(STOP), flags);

        Notification initial = notification("Starting battery monitor");
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_MONITOR, initial, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(NOTIF_MONITOR, initial);

        if (!CalibrationStore.isAutoEnabled(this) && !CalibrationStore.hasActive(this)) {
            selfStopping = true;
            starting = false;
            return;
        }

        running = true;
        starting = false;
        if (manager != null) manager.cancel(NOTIF_RESUME);
        notifyUiChanged();

        SharedPreferences rt = CalibrationStore.runtimePrefs(this);
        if (rt.getBoolean("boot_block_notified", false)) {
            rt.edit().remove("boot_block_notified").apply();
        }
        alertRules.targetSent = rt.getBoolean("alert_target_sent", false);
        alertRules.heatSent = rt.getBoolean("alert_heat_sent", false);
        alertRules.lowSent = rt.getBoolean("alert_low_sent", false);
        alertConfiguration = rt.getString("alert_configuration", "");

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        powerManagerAvailable = pm != null;
        interactiveState = pm != null && pm.isInteractive();

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(Intent.ACTION_POWER_CONNECTED);
        filter.addAction(Intent.ACTION_POWER_DISCONNECTED);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(systemReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(systemReceiver, filter);
        registered = true;

        AppWorker.execute(() -> {
            CalibrationStore.breakCycleContinuity(this);
            CycleReader.scan();
        });
        CalibrationStore.recordProcessExitInfo(this);
        MonitorWatchdogJobService.schedule(this);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (STOP.equals(action)) {
            selfStopping = true;
            CalibrationStore.setAutoEnabled(this, false);
            CalibrationStore.finishCheckpointAsync(this, "Monitoring stopped by you", () -> {
                notifyUiChanged();
                BatteryWidgetUpdater.updateAllIfPinnedAsync(this);
            });
            MonitorWatchdogJobService.cancel(this);
            stopSelf();
            notifyUiChanged();
            BatteryWidgetUpdater.updateAllIfPinnedAsync(this);
            return START_NOT_STICKY;
        }
        if (selfStopping || (!CalibrationStore.isAutoEnabled(this) && !CalibrationStore.hasActive(this))) {
            selfStopping = true;
            stopSelf();
            return START_NOT_STICKY;
        }
        if (REFRESH_NOTIF.equals(action)) {
            lastNotificationDetail = "";
        }
        sample(true, null);
        return START_STICKY;
    }

    private void sample(boolean force, Intent batteryIntent) {
        if (!CalibrationStore.isAutoEnabled(this) && !CalibrationStore.hasActiveFast(this)) {
            selfStopping = true;
            stopSelf();
            return;
        }
        long elapsed = SystemClock.elapsedRealtime();
        Intent stickyIntent = batteryIntent != null && Intent.ACTION_BATTERY_CHANGED.equals(batteryIntent.getAction())
                ? batteryIntent : null;
        if (stickyIntent != null) {
            int level = stickyIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int status = stickyIntent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN);
            int plugged = stickyIntent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
            int temp = stickyIntent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
            if (shouldSkipBatteryBroadcast(force, level, status, plugged, temp,
                    lastLevel, lastStatus, lastPlugged, lastTempTenth, elapsed, lastSampleElapsed)) {
                return;
            }
        }
        lastSampleElapsed = elapsed;

        final Context app = getApplicationContext();
        final boolean screenKnown = powerManagerAvailable;
        final boolean interactive = interactiveState;

        AppWorker.execute(() -> {
            BatteryReadings r = BatteryReadings.read(app, stickyIntent);
            if (r == null) return;
            CalibrationStore.observeOnWorker(app, r);
            boolean hotspot = CalibrationStore.prefs(app).getBoolean("hotspot_marker", false);
            TrendPoint point = new TrendPoint(System.currentTimeMillis(), SystemClock.elapsedRealtime(),
                    TrendStore.bootCount(app), r.soc, r.currentNowMa(), r.temperatureC(),
                    r.isPlugged(), interactive, screenKnown, hotspot);
            TrendStore.observeOnWorker(app, point);

            String detail = formatDetail(app, r);
            boolean hasActive = CalibrationStore.hasActiveFast(app);
            AppWorker.postMain(() -> applySampleOnMain(r, detail, hasActive));
        });
    }

    private void applySampleOnMain(BatteryReadings r, String detail, boolean hasActive) {
        if (!running) return;
        if (!CalibrationStore.isAutoEnabled(this) && !hasActive) {
            selfStopping = true;
            stopSelf();
            notifyUiChanged();
            return;
        }
        lastLevel = MeasurementRules.validSoc(r.soc) ? (int) Math.round(r.soc) : Integer.MIN_VALUE;
        lastStatus = r.status;
        lastPlugged = r.plugged;
        lastTempTenth = r.tempTenthC;

        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            boolean enabled = manager.areNotificationsEnabled();
            if (shouldPostMonitorNotification(enabled, detail, lastNotificationDetail)) {
                lastNotificationDetail = detail;
                manager.notify(NOTIF_MONITOR, notification(detail));
            } else if (!enabled) {
                lastNotificationDetail = "";
            }
        }
        evaluateAndPostAlerts(r, manager);
        rescheduleAdaptiveTick(r.isPlugged() || hasActive, interactiveState);
        notifyUiChanged();
        BatteryWidgetUpdater.updateAllIfPinned(this, r);
    }

    /** Posts ongoing monitor updates only when notifications are permitted and text actually changed. */
    static boolean shouldPostMonitorNotification(boolean enabled, String detail, String lastDetail) {
        return enabled && detail != null && !detail.equals(lastDetail);
    }

    /** Returns true when a sticky/noisy BATTERY_CHANGED broadcast can be skipped safely. */
    static boolean shouldSkipBatteryBroadcast(
            boolean force, int level, int status, int plugged, int tempTenth,
            int prevLevel, int prevStatus, int prevPlugged, int prevTempTenth,
            long elapsedMs, long prevSampleElapsedMs) {
        if (force) return false;
        boolean sameTemp = tempTenth == Integer.MIN_VALUE
                || (prevTempTenth != Integer.MIN_VALUE && Math.abs((long) tempTenth - (long) prevTempTenth) < 5);
        boolean unchanged = level == prevLevel && status == prevStatus && plugged == prevPlugged && sameTemp;
        return unchanged && prevSampleElapsedMs > 0 && elapsedMs >= prevSampleElapsedMs
                && elapsedMs - prevSampleElapsedMs < DEBOUNCE_MS;
    }

    /** Chooses adaptive foreground polling cadence based on plug, session, and interactive state. */
    static long nextTickIntervalMs(boolean chargingOrActive, boolean screenOn) {
        return chargingOrActive ? PLUGGED_TICK_MS : (screenOn ? SCREEN_ON_TICK_MS : -1L);
    }

    private void rescheduleAdaptiveTick(boolean chargingOrActive, boolean screenOn) {
        long targetMs = nextTickIntervalMs(chargingOrActive, screenOn);
        if (targetMs <= 0 && currentTickMs <= 0) return;
        currentTickMs = targetMs;
        handler.removeCallbacks(tick);
        if (targetMs > 0) handler.postDelayed(tick, targetMs);
    }

    private void evaluateAndPostAlerts(BatteryReadings r, NotificationManager manager) {
        if (manager == null) return;
        SharedPreferences prefs = CalibrationStore.prefs(this);
        SharedPreferences rt = CalibrationStore.runtimePrefs(this);
        int target = prefs.getInt("charge_target", 80);
        int heat = prefs.getInt("heat_target", 42);
        int low = prefs.getInt("low_target", 0);
        boolean enabled = prefs.getBoolean("alerts", false);
        String configuration = target + ":" + heat + ":" + low + ":" + enabled;

        boolean configChanged = !configuration.equals(alertConfiguration);
        if (configChanged) {
            alertRules.targetSent = alertRules.heatSent = alertRules.lowSent = false;
            alertConfiguration = configuration;
        }

        boolean prevTarget = alertRules.targetSent;
        boolean prevHeat = alertRules.heatSent;
        boolean prevLow = alertRules.lowSent;

        int events = 0;
        boolean notificationsAllowed = enabled && manager.areNotificationsEnabled();
        if (notificationsAllowed && Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = manager.getNotificationChannel(ALERTS);
            if (channel != null && channel.getImportance() == NotificationManager.IMPORTANCE_NONE) {
                notificationsAllowed = false;
            }
        }
        if (notificationsAllowed) {
            events = alertRules.evaluate(r.soc, r.temperatureC(), r.isPlugged(), target, heat, low);
            if ((events & 1) != 0) alert(manager, 101, "Charge target reached", "Battery is at " + Math.round(r.soc) + "%. You can unplug when convenient.");
            if ((events & 2) != 0) alert(manager, 102, "Battery temperature alert", "Battery temperature is " + Math.round(r.temperatureC()) + "°C. Your selected threshold is " + heat + "°C.");
            if ((events & 4) != 0) alert(manager, 103, "Low battery", "Battery is at " + Math.round(r.soc) + "%. Consider charging soon.");
        }

        if (configChanged || prevTarget != alertRules.targetSent || prevHeat != alertRules.heatSent || prevLow != alertRules.lowSent) {
            rt.edit().putBoolean("alert_target_sent", alertRules.targetSent)
                    .putBoolean("alert_heat_sent", alertRules.heatSent)
                    .putBoolean("alert_low_sent", alertRules.lowSent)
                    .putString("alert_configuration", configuration).apply();
        }
    }

    private static String formatDetail(Context app, BatteryReadings r) {
        if (r == null) return "Waiting for battery readings";
        boolean active = CalibrationStore.hasActiveFast(app);
        double delta = active ? Math.max(0.0, r.soc - CalibrationStore.activeStartSoc(app)) : Double.NaN;
        return formatDetailText(r.soc, r.status, r.isPlugged(), r.temperatureC(), active, delta);
    }

    static String formatDetailText(double soc, int status, boolean plugged, double tempC, boolean active, double deltaSoc) {
        String state = !plugged ? "Watching for next charge"
                : status == BatteryManager.BATTERY_STATUS_FULL ? "Full"
                : status == BatteryManager.BATTERY_STATUS_NOT_CHARGING ? "Paused at limit"
                : "Charging";
        StringBuilder sb = new StringBuilder();
        if (MeasurementRules.validSoc(soc)) sb.append(Math.round(soc)).append("% • ");
        sb.append(state);
        if (active && Double.isFinite(deltaSoc)) {
            sb.append(" • +").append(Math.max(0L, Math.round(deltaSoc))).append("%");
        }
        if (Double.isFinite(tempC)) {
            sb.append(" • ").append(String.format(java.util.Locale.US, "%.1f°C", tempC));
        }
        return sb.toString();
    }

    private Notification notification(String detail) {
        Notification.Action stopAction = new Notification.Action.Builder(
                (Icon) null, "Stop monitoring", stopMonitorIntent).build();
        Notification.Builder b = builderFor(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("BatteryLens monitoring")
                .setContentText(detail)
                .setStyle(new Notification.BigTextStyle().bigText(detail))
                .setContentIntent(openAppIntent)
                .setColor(0xFF6DEBD1)
                .setCategory(Notification.CATEGORY_STATUS)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setShowWhen(false)
                .addAction(stopAction);
        if (Build.VERSION.SDK_INT >= 31) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return b.build();
    }

    private void alert(NotificationManager manager, int id, String title, String message) {
        manager.notify(id, builderFor(this, ALERTS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(new Notification.BigTextStyle().bigText(message))
                .setContentIntent(openAppIntent)
                .setAutoCancel(true)
                .build());
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_BACKGROUND) {
            CalibrationStore.trimMemory();
            SQLiteDatabase.releaseMemory();
        }
    }

    @Override public void onDestroy() {
        running = false;
        starting = false;
        currentTickMs = -1L;
        handler.removeCallbacksAndMessages(null);
        if (registered) {
            unregisterReceiver(systemReceiver);
            registered = false;
        }
        boolean unexpectedStop = !selfStopping && CalibrationStore.isAutoEnabled(this);
        if (unexpectedStop) {
            CalibrationStore.finishCheckpointAsync(this, "Monitor stopped; saved last observed segment");
            Context app = getApplicationContext();
            AppWorker.execute(() -> CalibrationStore.breakCycleContinuity(app));
        }
        notifyUiChanged();
        BatteryWidgetUpdater.updateAllIfPinnedAsync(this);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
