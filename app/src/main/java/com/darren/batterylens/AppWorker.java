package com.darren.batterylens;

import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Shared single-thread background worker and main-thread dispatcher for lightweight I/O. */
final class AppWorker {
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "battery-lens-worker");
        t.setDaemon(true);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private AppWorker() { }

    static void execute(Runnable task) {
        WORKER.execute(task);
    }

    static void postMain(Runnable task) {
        MAIN.post(task);
    }

    static boolean isMainThread() {
        return Looper.myLooper() == Looper.getMainLooper();
    }
}
