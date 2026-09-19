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
    private boolean targetAlerted;
    private boolean heatAlerted;
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
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.notify(100, notification(r));
        if (r == null) return;
        int target = getSharedPreferences(CalibrationStore.PREFS, MODE_PRIVATE).getInt("charge_target", 80);
        boolean alerts = getSharedPreferences(CalibrationStore.PREFS, MODE_PRIVATE).getBoolean("alerts", false);
        if (!r.isPlugged() || r.soc < target - 2) targetAlerted = false;
        if (r.temperatureC() < 39) heatAlerted = false;
        if (!alerts) return;
        if (r.isPlugged() && r.soc >= target && !targetAlerted) {
            alert(101, "Charge target reached", "Battery is at " + Math.round(r.soc) + "%. You can unplug when convenient.");
            targetAlerted = true;
        }
        if (r.temperatureC() >= 42 && !heatAlerted) {
            alert(102, "Battery is getting warm", "Battery temperature is " + Math.round(r.temperatureC()) + "°C. Consider pausing charging or heavy use.");
            heatAlerted = true;
        }
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
