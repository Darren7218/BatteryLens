package com.darren.batterylens;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/** Shared AppWidgetProvider base that delegates updates off the main thread without waking SQLite. */
abstract class BatteryWidgetProvider extends AppWidgetProvider {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context != null && intent != null && BatteryWidgetUpdater.ACTION_REFRESH.equals(intent.getAction())) {
            BatteryWidgetUpdater.updateProviderAsync(context, getClass(), null, true, goAsync());
            return;
        }
        super.onReceive(context, intent);
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        if (context == null) return;
        BatteryWidgetUpdater.updateProviderAsync(context, getClass(), appWidgetIds, true, goAsync());
    }

    @Override
    public void onAppWidgetOptionsChanged(
            Context context, AppWidgetManager appWidgetManager, int appWidgetId, Bundle newOptions) {
        if (context == null) return;
        BatteryWidgetUpdater.updateProviderAsync(context, getClass(), new int[]{appWidgetId}, true, goAsync());
    }

    @Override
    public void onEnabled(Context context) {
        BatteryWidgetUpdater.invalidatePinnedCache();
    }

    @Override
    public void onDeleted(Context context, int[] appWidgetIds) {
        BatteryWidgetUpdater.invalidatePinnedCache();
    }

    @Override
    public void onDisabled(Context context) {
        BatteryWidgetUpdater.invalidatePinnedCache();
    }
}
