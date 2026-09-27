package com.darren.batterylens;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/** User-started foreground monitor. No wake lock, no exact alarms, no hidden boot start. */
public class BatteryMonitorService extends Service {
    private static final String CHANNEL = "battery_monitor";
    private static final String ALERTS = "battery_alerts";
    private static final String STOP = "com.darren.batterylens.STOP_MONITOR";
    static volatile boolean running;
    static volatile boolean starting;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean registered;
    private final AlertRules alertRules = new AlertRules();
    private String alertConfiguration = "";
    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) { sample(); }
    };
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            sample();
            if (running) handler.postDelayed(this, 60_000);
        }
    };

    static boolean start(Context context) {
        try {
            if (!running) starting = true;
            context.startForegroundService(new Intent(context, BatteryMonitorService.class));
            return true;
        } catch (RuntimeException e) {
            starting = false;
            return false; // The UI reports this and offers Android app settings.
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        CalibrationStore.breakCycleContinuity(this);
        android.content.SharedPreferences prefs = getSharedPreferences(CalibrationStore.PREFS, MODE_PRIVATE);
        alertRules.targetSent = prefs.getBoolean("alert_target_sent", false);
        alertRules.heatSent = prefs.getBoolean("alert_heat_sent", false);
        alertRules.lowSent = prefs.getBoolean("alert_low_sent", false);
        alertConfiguration = prefs.getString("alert_configuration", "");
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Battery monitoring", NotificationManager.IMPORTANCE_LOW));
        manager.createNotificationChannel(new NotificationChannel(ALERTS, "Charge and temperature alerts", NotificationManager.IMPORTANCE_DEFAULT));
        Notification notification = notification(null);
        if (Build.VERSION.SDK_INT >= 34) startForeground(100, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(100, notification);
        running = true;
        starting = false;
        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(batteryReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(batteryReceiver, filter);
        registered = true;
        handler.post(tick);
        new Thread(CycleReader::scan, "battery-cycle-probe").start();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && STOP.equals(intent.getAction())) {
            CalibrationStore.finishCheckpoint(this, "Monitoring stopped by you");
            CalibrationStore.setAutoEnabled(this, false);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!CalibrationStore.isAutoEnabled(this) && !CalibrationStore.hasActive(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        sample();
        return START_STICKY;
    }

    private void sample() {
        if (!CalibrationStore.isAutoEnabled(this) && !CalibrationStore.hasActive(this)) { stopSelf(); return; }
        BatteryReadings r = BatteryReadings.read(this);
        CalibrationStore.observe(this, r);
        TrendStore.observe(this, r);
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.notify(100, notification(r));
        if (r == null) return;
        android.content.SharedPreferences prefs = getSharedPreferences(CalibrationStore.PREFS, MODE_PRIVATE);
        int target = prefs.getInt("charge_target", 80);
        int heat = prefs.getInt("heat_target", 42);
        int low = prefs.getInt("low_target", 0);
        boolean enabled = prefs.getBoolean("alerts", false);
        String configuration = target + ":" + heat + ":" + low + ":" + enabled;
        if (!configuration.equals(alertConfiguration)) {
            alertRules.targetSent = alertRules.heatSent = alertRules.lowSent = false;
            alertConfiguration = configuration;
        }
        // Do not latch undelivered alerts when Android notifications are blocked.
        if (!enabled || !manager.areNotificationsEnabled()) return;
        NotificationChannel channel = manager.getNotificationChannel(ALERTS);
        if (channel != null && channel.getImportance() == NotificationManager.IMPORTANCE_NONE) return;
        int events = alertRules.evaluate(r.soc, r.temperatureC(), r.isPlugged(), target, heat, low);
        if ((events & 1) != 0) alert(101, "Charge target reached", "Battery is at " + Math.round(r.soc) + "%. You can unplug when convenient.");
        if ((events & 2) != 0) alert(102, "Battery temperature alert", "Battery temperature is " + Math.round(r.temperatureC()) + "°C. Your selected threshold is " + heat + "°C.");
        if ((events & 4) != 0) alert(103, "Low battery", "Battery is at " + Math.round(r.soc) + "%. Consider charging soon.");
        prefs.edit().putBoolean("alert_target_sent", alertRules.targetSent)
                .putBoolean("alert_heat_sent", alertRules.heatSent)
                .putBoolean("alert_low_sent", alertRules.lowSent)
                .putString("alert_configuration", configuration).apply();
    }

    private PendingIntent openApp() {
        return PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification notification(BatteryReadings r) {
        String detail = "Waiting for battery readings";
        if (r != null) {
            detail = (MeasurementRules.validSoc(r.soc) ? Math.round(r.soc) + "% • " : "")
                    + (r.isPlugged() ? "Charger connected" : "Watching for your next charge");
            if (CalibrationStore.hasActive(this)) detail += " • +" + Math.max(0, Math.round(r.soc - CalibrationStore.activeStartSoc(this))) + "% recorded";
        }
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, BatteryMonitorService.class).setAction(STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("BatteryLens monitoring").setContentText(detail)
                .setStyle(new Notification.BigTextStyle().bigText(detail)).setContentIntent(openApp())
                .setOnlyAlertOnce(true).setOngoing(true).setShowWhen(false)
                .addAction(new Notification.Action.Builder(null, "Stop monitoring", stop).build()).build();
    }

    private void alert(int id, String title, String message) {
        getSystemService(NotificationManager.class).notify(id, new Notification.Builder(this, ALERTS)
                .setSmallIcon(R.drawable.ic_notification).setContentTitle(title).setContentText(message)
                .setStyle(new Notification.BigTextStyle().bigText(message)).setContentIntent(openApp()).setAutoCancel(true).build());
    }

    @Override public void onDestroy() {
        running = false;
        starting = false;
        handler.removeCallbacksAndMessages(null);
        if (registered) unregisterReceiver(batteryReceiver);
        CalibrationStore.finishCheckpoint(this, "Monitor stopped; saved last observed segment");
        CalibrationStore.breakCycleContinuity(this);
        // Abrupt process death may skip onDestroy; persisted checkpoints still recover conservatively.
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
