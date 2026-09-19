package com.darren.batterylens;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** A reboot ends continuity. Starting a foreground monitor again requires opening the app. */
public class PowerEventReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent != null && Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) CalibrationStore.onBoot(context);
    }
}
