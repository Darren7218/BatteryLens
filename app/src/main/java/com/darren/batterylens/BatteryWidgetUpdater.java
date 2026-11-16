package com.darren.batterylens;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.RemoteViews;

/** Coordinates lightweight event-driven updates across all BatteryLens home-screen widget sizes. */
final class BatteryWidgetUpdater {
    static final String ACTION_REFRESH = "com.darren.batterylens.WIDGET_REFRESH";

    private static final int COLOR_MINT = 0xFF6DEBD1;
    private static final int COLOR_BLUE = 0xFF67C0FF;
    private static final int COLOR_AMBER = 0xFFFFC45C;
    private static final int COLOR_CORAL = 0xFFFF8970;

    private static volatile String lastAutoSignature = "";
    // 0 = unknown (not checked yet), 1 = none pinned, 2 = at least one pinned
    private static volatile int pinnedState;

    private BatteryWidgetUpdater() { }

    static void invalidatePinnedCache() {
        pinnedState = 0;
        lastAutoSignature = "";
    }

    static Class<? extends AppWidgetProvider> providerClass(WidgetSnapshot.WidgetSize size) {
        switch (size) {
            case COMPACT_2X1: return BatteryWidgetCompactProvider.class;
            case WIDE_4X1: return BatteryWidgetWideProvider.class;
            case DASHBOARD_4X2: return BatteryWidgetDashboardProvider.class;
            case SQUARE_2X2:
            default: return BatteryWidgetSquareProvider.class;
        }
    }

    static ComponentName componentFor(Context c, WidgetSnapshot.WidgetSize size) {
        return new ComponentName(c.getApplicationContext(), providerClass(size));
    }

    static boolean canRequestPin(Context c) {
        if (Build.VERSION.SDK_INT < 26) return false;
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(c.getApplicationContext());
            return mgr != null && mgr.isRequestPinAppWidgetSupported();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    static boolean requestPin(Context c, WidgetSnapshot.WidgetSize size) {
        if (Build.VERSION.SDK_INT < 26) return false;
        Context app = c.getApplicationContext();
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(app);
            if (mgr == null || !mgr.isRequestPinAppWidgetSupported()) return false;
            return mgr.requestPinAppWidget(componentFor(app, size), null, null);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    static boolean hasAnyWidgets(Context c) {
        int cached = pinnedState;
        if (cached != 0) return cached == 2;
        Context app = c.getApplicationContext();
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(app);
            if (mgr == null) {
                pinnedState = 1;
                return false;
            }
            for (WidgetSnapshot.WidgetSize size : WidgetSnapshot.WidgetSize.values()) {
                int[] ids = mgr.getAppWidgetIds(componentFor(app, size));
                if (ids != null && ids.length > 0) {
                    pinnedState = 2;
                    return true;
                }
            }
            pinnedState = 1;
        } catch (RuntimeException ignored) { }
        return false;
    }

    static void updateAllIfPinned(Context c, BatteryReadings readings) {
        int cached = pinnedState;
        if (cached == 1) return;
        Context app = c.getApplicationContext();
        AppWorker.execute(() -> {
            if (!hasAnyWidgets(app)) return;
            CalibrationStore.Estimate est = CalibrationStore.ensureEstimateCachedOnWorker(app);
            updateInternal(app, readings, est, null, null, false);
        });
    }

    static void updateAllIfPinnedAsync(Context c) {
        if (pinnedState == 1) return;
        Context app = c.getApplicationContext();
        AppWorker.execute(() -> {
            if (!hasAnyWidgets(app)) return;
            updateOnWorker(app, null, null, false);
        });
    }

    static void updateProviderAsync(
            Context c,
            Class<? extends AppWidgetProvider> cls,
            int[] widgetIds,
            boolean force,
            BroadcastReceiver.PendingResult pending) {
        Context app = c.getApplicationContext();
        AppWorker.execute(() -> {
            try {
                updateOnWorker(app, cls, widgetIds, force);
            } finally {
                if (pending != null) {
                    try { pending.finish(); } catch (RuntimeException ignored) { }
                }
            }
        });
    }

    private static void updateOnWorker(
            Context app,
            Class<? extends AppWidgetProvider> onlyProvider,
            int[] specificIds,
            boolean force) {
        CalibrationStore.Estimate est = CalibrationStore.ensureEstimateCachedOnWorker(app);
        BatteryReadings readings = BatteryReadings.read(app);
        updateInternal(app, readings, est, onlyProvider, specificIds, force);
    }

    private static void updateInternal(
            Context app,
            BatteryReadings r,
            CalibrationStore.Estimate est,
            Class<? extends AppWidgetProvider> onlyProvider,
            int[] specificIds,
            boolean force) {
        AppWidgetManager mgr;
        try {
            mgr = AppWidgetManager.getInstance(app);
        } catch (RuntimeException ignored) {
            return;
        }
        if (mgr == null) return;

        WidgetSnapshot snap = buildSnapshot(app, r, est);
        if (!force && onlyProvider == null && snap.signature.equals(lastAutoSignature)) {
            return;
        }

        boolean updatedAny = false;
        for (WidgetSnapshot.WidgetSize defaultSize : WidgetSnapshot.WidgetSize.values()) {
            Class<? extends AppWidgetProvider> cls = providerClass(defaultSize);
            if (onlyProvider != null && onlyProvider != cls) continue;
            ComponentName cn = new ComponentName(app, cls);
            int[] ids = (onlyProvider != null && specificIds != null) ? specificIds : safeGetIds(mgr, cn);
            if (ids == null || ids.length == 0) continue;

            for (int widgetId : ids) {
                WidgetSnapshot.WidgetSize targetSize = resolveTargetSize(app, mgr, widgetId, defaultSize);
                RemoteViews views = buildRemoteViews(app, snap, targetSize, cn);
                try {
                    mgr.updateAppWidget(widgetId, views);
                    updatedAny = true;
                } catch (RuntimeException ignored) { }
            }
        }
        if (onlyProvider == null) {
            pinnedState = updatedAny ? 2 : (hasAnyWidgetsDirect(app, mgr) ? 2 : 1);
            if (updatedAny) lastAutoSignature = snap.signature;
        } else if (updatedAny) {
            pinnedState = 2;
        }
    }

    private static boolean hasAnyWidgetsDirect(Context app, AppWidgetManager mgr) {
        for (WidgetSnapshot.WidgetSize size : WidgetSnapshot.WidgetSize.values()) {
            int[] ids = safeGetIds(mgr, componentFor(app, size));
            if (ids != null && ids.length > 0) return true;
        }
        return false;
    }

    private static int[] safeGetIds(AppWidgetManager mgr, ComponentName cn) {
        try { return mgr.getAppWidgetIds(cn); }
        catch (RuntimeException ignored) { return null; }
    }

    private static WidgetSnapshot.WidgetSize resolveTargetSize(
            Context app, AppWidgetManager mgr, int widgetId, WidgetSnapshot.WidgetSize fallback) {
        try {
            Bundle opts = mgr.getAppWidgetOptions(widgetId);
            if (opts != null) {
                boolean landscape = app.getResources().getConfiguration().orientation
                        == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
                int w = opts.getInt(landscape
                        ? AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH
                        : AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0);
                int h = opts.getInt(landscape
                        ? AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT
                        : AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0);
                if (w <= 0) w = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0);
                if (h <= 0) h = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0);
                return WidgetSnapshot.resolveSize(fallback, w, h);
            }
        } catch (RuntimeException ignored) { }
        return fallback;
    }

    private static WidgetSnapshot buildSnapshot(Context app, BatteryReadings r, CalibrationStore.Estimate est) {
        double soc = r != null ? r.soc : -1;
        int status = r != null ? r.status : 0;
        int plugged = r != null ? r.plugged : 0;
        double currentMa = r != null ? r.currentNowMa() : Double.NaN;
        double powerW = r != null ? r.batterySidePowerW() : Double.NaN;
        double tempC = r != null ? r.temperatureC() : Double.NaN;

        boolean running = BatteryMonitorService.running;
        boolean autoEnabled = CalibrationStore.isAutoEnabled(app);
        boolean activeSession = CalibrationStore.hasActiveFast(app);
        double activeStart = activeSession ? CalibrationStore.activeStartSoc(app) : -1;

        Double fcc = est != null ? est.fccMah : null;
        int used = est != null ? est.usedCount : 0;
        double mad = est != null ? est.relativeMad : Double.NaN;
        String conf = est != null ? est.confidence : "";
        float design = CalibrationStore.designCapacity(app);

        return WidgetSnapshot.build(soc, status, plugged, currentMa, powerW, tempC,
                running, autoEnabled, activeSession, activeStart, fcc, used, mad, design, conf);
    }

    private static RemoteViews buildRemoteViews(
            Context app,
            WidgetSnapshot snap,
            WidgetSnapshot.WidgetSize size,
            ComponentName provider) {
        int layoutId;
        switch (size) {
            case COMPACT_2X1: layoutId = R.layout.widget_compact; break;
            case WIDE_4X1: layoutId = R.layout.widget_wide; break;
            case DASHBOARD_4X2: layoutId = R.layout.widget_dashboard; break;
            case SQUARE_2X2:
            default: layoutId = R.layout.widget_square; break;
        }
        RemoteViews views = new RemoteViews(app.getPackageName(), layoutId);

        views.setContentDescription(R.id.widget_root, snap.contentDescription);
        views.setTextViewText(R.id.widget_level, snap.levelText);
        applyBadge(views, R.id.widget_state_badge,
                size == WidgetSnapshot.WidgetSize.DASHBOARD_4X2
                        ? snap.fullBadge : snap.shortBadge,
                snap.stateTone);
        applyProgress(views, snap.progress, snap.progressTone);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        Intent openIntent = new Intent(app, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPi = PendingIntent.getActivity(app, 0, openIntent, flags);
        views.setOnClickPendingIntent(R.id.widget_root, openPi);

        if (size == WidgetSnapshot.WidgetSize.COMPACT_2X1) {
            views.setTextViewText(R.id.widget_monitor_badge, snap.monitorDotText);
            views.setTextColor(R.id.widget_monitor_badge, colorForTone(snap.monitorTone));
            views.setTextViewText(R.id.widget_meta, snap.compactMeta);
            return views;
        }

        applyBadge(views, R.id.widget_monitor_badge, snap.monitorBadgeText, snap.monitorTone);
        Intent refreshIntent = new Intent(ACTION_REFRESH).setComponent(provider);
        int refreshReqCode = 100 + (provider.getClassName().hashCode() & 0xFFF);
        PendingIntent refreshPi = PendingIntent.getBroadcast(app, refreshReqCode, refreshIntent, flags);
        views.setOnClickPendingIntent(R.id.widget_refresh, refreshPi);

        if (size == WidgetSnapshot.WidgetSize.SQUARE_2X2) {
            views.setTextViewText(R.id.widget_tile_power, snap.powerText);
            views.setTextViewText(R.id.widget_tile_temp, snap.tempText);
            views.setTextViewText(R.id.widget_footer, snap.squareFooter);
        } else if (size == WidgetSnapshot.WidgetSize.WIDE_4X1) {
            views.setTextViewText(R.id.widget_primary_line, snap.widePrimary);
            views.setTextViewText(R.id.widget_secondary_line, snap.wideSecondary);
        } else {
            views.setTextViewText(R.id.widget_tile_power, snap.powerText);
            views.setTextViewText(R.id.widget_tile_current, snap.currentText);
            views.setTextViewText(R.id.widget_tile_temp, snap.tempText);
            views.setTextViewText(R.id.widget_tile_health, snap.healthTileText);
            views.setTextViewText(R.id.widget_footer, snap.footerText);
        }
        return views;
    }

    private static void applyBadge(RemoteViews views, int viewId, String text, WidgetSnapshot.Tone tone) {
        views.setTextViewText(viewId, text);
        views.setTextColor(viewId, colorForTone(tone));
        views.setInt(viewId, "setBackgroundResource", pillForTone(tone));
    }

    private static void applyProgress(RemoteViews views, int progress, WidgetSnapshot.Tone tone) {
        int activeId = progressIdForTone(tone);
        int[] all = {R.id.widget_bar_mint, R.id.widget_bar_blue, R.id.widget_bar_amber, R.id.widget_bar_coral};
        for (int id : all) {
            boolean show = id == activeId;
            views.setViewVisibility(id, show ? View.VISIBLE : View.GONE);
            if (show) views.setProgressBar(id, 100, progress, false);
        }
    }

    private static int colorForTone(WidgetSnapshot.Tone tone) {
        switch (tone) {
            case BLUE: return COLOR_BLUE;
            case AMBER: return COLOR_AMBER;
            case CORAL: return COLOR_CORAL;
            case MINT:
            default: return COLOR_MINT;
        }
    }

    private static int pillForTone(WidgetSnapshot.Tone tone) {
        switch (tone) {
            case BLUE: return R.drawable.widget_pill_blue;
            case AMBER: return R.drawable.widget_pill_amber;
            case CORAL: return R.drawable.widget_pill_coral;
            case MINT:
            default: return R.drawable.widget_pill_mint;
        }
    }

    private static int progressIdForTone(WidgetSnapshot.Tone tone) {
        switch (tone) {
            case BLUE: return R.id.widget_bar_blue;
            case AMBER: return R.id.widget_bar_amber;
            case CORAL: return R.id.widget_bar_coral;
            case MINT:
            default: return R.id.widget_bar_mint;
        }
    }
}
