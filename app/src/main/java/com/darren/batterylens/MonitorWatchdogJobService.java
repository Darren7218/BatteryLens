package com.darren.batterylens;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;

/** Persisted JobScheduler fallback that recovers monitoring after reboots or OEM kills. */
public class MonitorWatchdogJobService extends JobService {
    private static final int JOB_CHARGING = 4101;
    private static final int JOB_WATCHDOG = 4102;
    private static final long WATCHDOG_INTERVAL_MS = 30 * 60_000L;
    private static final long CHARGING_REARM_MS = 15 * 60_000L;

    static void schedule(Context context) {
        if (!CalibrationStore.isAutoEnabled(context)) {
            cancel(context);
            return;
        }
        JobScheduler js = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        ComponentName component = new ComponentName(context, MonitorWatchdogJobService.class);

        if (!hasPendingJob(js, JOB_CHARGING)) {
            scheduleChargingJob(js, component, 0L);
        }

        if (!hasPendingJob(js, JOB_WATCHDOG)) {
            JobInfo watchdog = new JobInfo.Builder(JOB_WATCHDOG, component)
                    .setPeriodic(WATCHDOG_INTERVAL_MS)
                    .setPersisted(true)
                    .build();
            js.schedule(watchdog);
        }
    }

    private static void scheduleChargingJob(JobScheduler js, ComponentName component, long minLatencyMs) {
        JobInfo.Builder charging = new JobInfo.Builder(JOB_CHARGING, component)
                .setRequiresCharging(true)
                .setPersisted(true);
        if (minLatencyMs > 0L) charging.setMinimumLatency(minLatencyMs);
        if (Build.VERSION.SDK_INT >= 26) charging.setRequiresBatteryNotLow(false);
        js.schedule(charging.build());
    }

    static void cancel(Context context) {
        JobScheduler js = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        js.cancel(JOB_CHARGING);
        js.cancel(JOB_WATCHDOG);
    }

    private static boolean hasPendingJob(JobScheduler js, int jobId) {
        return js.getPendingJob(jobId) != null;
    }

    @Override public boolean onStartJob(JobParameters params) {
        if (!CalibrationStore.isAutoEnabled(this)) {
            cancel(this);
            return false;
        }
        final boolean isChargingJob = params != null && params.getJobId() == JOB_CHARGING;
        if (BatteryMonitorService.running || BatteryMonitorService.starting) {
            if (isChargingJob) rearmChargingJob(this);
            return false;
        }
        final Context app = getApplicationContext();
        AppWorker.execute(() -> {
            try {
                BatteryReadings r = BatteryReadings.read(app);
                if (r != null) {
                    CalibrationStore.observeOnWorker(app, r);
                    PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
                    boolean interactive = pm != null && pm.isInteractive();
                    boolean hotspot = CalibrationStore.prefs(app).getBoolean("hotspot_marker", false);
                    TrendStore.observeOnWorker(app, new TrendPoint(
                            System.currentTimeMillis(), SystemClock.elapsedRealtime(),
                            TrendStore.bootCount(app), r.soc, r.currentNowMa(), r.temperatureC(),
                            r.isPlugged(), interactive, pm != null, hotspot));
                }
            } finally {
                AppWorker.postMain(() -> {
                    if (!BatteryMonitorService.running
                            && !BatteryMonitorService.starting
                            && CalibrationStore.isAutoEnabled(app)) {
                        BatteryMonitorService.startFromBackground(app);
                    }
                    if (isChargingJob && CalibrationStore.isAutoEnabled(app)) {
                        rearmChargingJob(app);
                    }
                    jobFinished(params, false);
                });
            }
        });
        return true;
    }

    private static void rearmChargingJob(Context context) {
        JobScheduler js = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        scheduleChargingJob(js, new ComponentName(context, MonitorWatchdogJobService.class), CHARGING_REARM_MS);
    }

    @Override public boolean onStopJob(JobParameters params) {
        return false;
    }
}
