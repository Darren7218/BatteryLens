package com.darren.batterylens;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Closes interrupted pre-reboot segments and automatically resumes monitoring if enabled. */
public class PowerEventReceiver extends BroadcastReceiver {
    private static final String QUICKBOOT = "android.intent.action.QUICKBOOT_POWERON";
    private static final String HTC_QUICKBOOT = "com.htc.intent.action.QUICKBOOT_POWERON";

    @Override public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        String action = intent.getAction();
        boolean bootAction = Intent.ACTION_BOOT_COMPLETED.equals(action)
                || QUICKBOOT.equals(action)
                || HTC_QUICKBOOT.equals(action);
        boolean packageReplaced = Intent.ACTION_MY_PACKAGE_REPLACED.equals(action);
        if (!bootAction && !packageReplaced) return;

        if (bootAction) {
            // Enqueued onto AppWorker FIFO before the service's first sample runs.
            CalibrationStore.onBootAsync(context);
        }
        if (CalibrationStore.isAutoEnabled(context)) {
            // Call synchronously inside onReceive to retain BOOT_COMPLETED / MY_PACKAGE_REPLACED exemptions.
            BatteryMonitorService.startFromBackground(context);
            MonitorWatchdogJobService.schedule(context);
        }
        BatteryWidgetUpdater.updateAllIfPinnedAsync(context);
    }
}
