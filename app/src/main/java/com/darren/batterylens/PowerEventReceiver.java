package com.darren.batterylens;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class PowerEventReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !CalibrationStore.isAutoEnabled(context)) return;
        String action = intent.getAction();
        if (Intent.ACTION_POWER_CONNECTED.equals(action)) {
            CalibrationStore.startSession(context, "auto");
        } else if (Intent.ACTION_POWER_DISCONNECTED.equals(action)) {
            if (CalibrationStore.hasActive(context) && "auto".equals(CalibrationStore.activeSource(context))) {
                CalibrationStore.finishSession(context);
            }
        } else if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            CalibrationStore.reconcileAutoState(context);
        }
    }
}
