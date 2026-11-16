package com.darren.batterylens;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.TouchDelegate;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final String KEY_NOTIF_PROMPTED = "notif_prompted_v1";

    private final DecimalFormat one = new DecimalFormat("0.0");
    private final DecimalFormat zero = new DecimalFormat("0");
    private final SimpleDateFormat shortDate = new SimpleDateFormat("MMM d, HH:mm", Locale.getDefault());
    private final Date tempDate = new Date();

    private TextView healthValue, healthSource, confidenceValue, capacityValue, baselineSummary;
    private TextView autoStatus, autoChip, powerValue, chargeInfo, runtimeValue;
    private TextView screenDrainValue, idleDrainValue, dailyStress, tempValue, cyclesValue, timeValue;
    private TextView historyValue, sessionSummary, trendSummary, currentValue, voltageValue;
    private TextView chargeStatus, cycleExplanation, observedValue, monitorHelp, rawValue;
    private LinearLayout sessionList;
    private LiveChartView liveChart;
    private HealthChartView chart;
    private BatteryGaugeView gauge;
    private ProgressBar progress;
    private EditText designCapacityInput;
    private Button manualButton, notificationsButton;
    private Switch autoSwitch;

    private int sessionFilter, trendMetric, trendHours = 6, healthWindowDays;
    private List<CalibrationStore.Session> cachedSessions;
    private CalibrationStore.Estimate cachedEstimate;
    private long lastHistoryRevision = -1L;
    private float lastHistoryDesign = Float.NaN;
    private boolean historyLoading;

    private List<CalibrationStore.Session> historySnapshot;
    private int historyFilter = -1;
    private float historyDesign = Float.NaN;

    private long lastTrendRevision = Long.MIN_VALUE, lastTrendRefresh;
    private int lastTrendHours = -1, lastTrendMetric = -1;
    private boolean trendsLoading;

    private long lastInsightRevision = Long.MIN_VALUE, lastInsightRefresh;
    private InsightAnalysis cachedInsights;
    private boolean insightsLoading;
    private double latestInsightSoc = -1;
    private boolean latestInsightPlugged;

    private final int bg = Color.rgb(9, 20, 26);
    private final int surface = Color.rgb(13, 27, 34);
    private final int card = Color.rgb(18, 35, 43);
    private final int cardElevated = Color.rgb(22, 44, 54);
    private final int stroke = Color.rgb(38, 61, 70);
    private final int heroStroke = Color.rgb(52, 92, 102);
    private final int primary = Color.rgb(244, 247, 252);
    private final int secondary = Color.rgb(167, 189, 197);
    private final int accent = Color.rgb(109, 235, 209);
    private final int accentBlue = Color.rgb(103, 192, 255);
    private final int warning = Color.rgb(255, 196, 92);
    private final int danger = Color.rgb(255, 137, 112);
    private static final Typeface SANS_REGULAR = Typeface.create("sans-serif", Typeface.NORMAL);
    private static final Typeface SANS_BOLD = Typeface.create("sans-serif", Typeface.BOLD);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean receiverRegistered;
    private boolean visible;
    private boolean updatingSwitch;
    private boolean awaitingDocumentPicker;
    private long lastUiObservation;
    private int selectedPage;
    private int lastChipState;
    private String pendingCsv;
    private final LinearLayout[] pages = new LinearLayout[4];
    private final boolean[] built = new boolean[4];
    private final Button[] tabs = new Button[4];
    private FrameLayout headerWrap, navWrap;
    private ScrollView contentScroll;
    private BatteryReadings lastReadings;

    private final Runnable monitorUiListener = () -> {
        if (!isGone() && visible) refreshBattery();
    };

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) {
            if (!BatteryMonitorService.running) {
                CalibrationStore.reconcileAutoAsync(c);
            }
            refreshBattery(intent);
        }
    };

    private final Runnable liveRefresh = new Runnable() {
        @Override public void run() {
            if (!visible) return;
            long now = SystemClock.elapsedRealtime();
            if (!BatteryMonitorService.running && now - lastUiObservation >= 60_000) {
                CalibrationStore.reconcileAutoAsync(MainActivity.this);
                lastUiObservation = now;
            }
            refreshBattery(null);
            handler.postDelayed(this, 5_000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!BatteryMonitorService.running && !CalibrationStore.isAutoEnabled(this)) {
            AppWorker.execute(() -> CalibrationStore.breakCycleContinuity(getApplicationContext()));
        }
        seedDesignCapacityIfNeeded();
        if (savedInstanceState != null) {
            selectedPage = savedInstanceState.getInt("page", 0);
            pendingCsv = savedInstanceState.getString("csv");
            sessionFilter = savedInstanceState.getInt("filter", 0);
            trendMetric = savedInstanceState.getInt("metric", 0);
            trendHours = savedInstanceState.getInt("hours", 6);
            healthWindowDays = savedInstanceState.getInt("healthWindow", 0);
        }
        buildUi();
        configureSystemBars();
        CalibrationStore.reconcileAutoAsync(this);
        CalibrationStore.recordProcessExitInfo(this);
        refreshBattery();
    }

    private void configureSystemBars() {
        Window window = getWindow();
        if (window == null) return;
        View decor = window.getDecorView();
        if (Build.VERSION.SDK_INT >= 35) {
            window.setNavigationBarContrastEnforced(false);
            clearLightSystemBarFlags(decor, window);
        } else if (Build.VERSION.SDK_INT >= 30) {
            configureSystemBarsApi30(window, decor);
        } else {
            configureSystemBarsLegacy(window, decor);
        }
    }

    @android.annotation.TargetApi(30)
    private void clearLightSystemBarFlags(View decor, Window window) {
        WindowInsetsController controller = decor != null ? decor.getWindowInsetsController() : window.getInsetsController();
        if (controller != null) {
            controller.setSystemBarsAppearance(0,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        }
    }

    @android.annotation.TargetApi(30)
    @SuppressWarnings("deprecation")
    private void configureSystemBarsApi30(Window window, View decor) {
        window.setDecorFitsSystemWindows(false);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        clearLightSystemBarFlags(decor, window);
    }

    @SuppressWarnings("deprecation")
    private void configureSystemBarsLegacy(Window window, View decor) {
        decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }

    @Override protected void onResume() {
        super.onResume();
        visible = true;
        awaitingDocumentPicker = false;
        BatteryMonitorService.setUiStateListener(monitorUiListener);
        if (CalibrationStore.isAutoEnabled(this) || CalibrationStore.hasActive(this)) {
            requestNotificationsOnce();
            if (!BatteryMonitorService.running && !BatteryMonitorService.starting) {
                BatteryMonitorService.start(this);
            }
            MonitorWatchdogJobService.schedule(this);
        }
        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(batteryReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(batteryReceiver, filter);
        receiverRegistered = true;
        handler.post(liveRefresh);
    }

    @Override protected void onPause() {
        visible = false;
        BatteryMonitorService.setUiStateListener(null);
        handler.removeCallbacks(liveRefresh);
        if (receiverRegistered) {
            unregisterReceiver(batteryReceiver);
            receiverRegistered = false;
        }
        super.onPause();
    }

    @Override protected void onStop() {
        if (!isChangingConfigurations() && !awaitingDocumentPicker
                && !BatteryMonitorService.running && !BatteryMonitorService.starting) {
            CalibrationStore.finishCheckpointAsync(this, "App left foreground without background monitoring");
            AppWorker.execute(() -> CalibrationStore.breakCycleContinuity(getApplicationContext()));
        }
        releaseHeavyUiCaches();
        super.onStop();
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_UI_HIDDEN) {
            releaseHeavyUiCaches();
        }
    }

    private void releaseHeavyUiCaches() {
        if (liveChart != null) liveChart.releaseMemory();
        if (chart != null) chart.releaseMemory();
        if (sessionList != null) sessionList.removeAllViews();
        historySnapshot = null;
        cachedSessions = null;
        cachedEstimate = null;
        cachedInsights = null;
        lastHistoryRevision = -1L;
        lastTrendRevision = Long.MIN_VALUE;
        lastInsightRevision = Long.MIN_VALUE;
        CalibrationStore.trimMemory();
        SQLiteDatabase.releaseMemory();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("page", selectedPage);
        state.putString("csv", pendingCsv);
        state.putInt("filter", sessionFilter);
        state.putInt("metric", trendMetric);
        state.putInt("hours", trendHours);
        state.putInt("healthWindow", healthWindowDays);
        super.onSaveInstanceState(state);
    }

    private void seedDesignCapacityIfNeeded() {
        if (CalibrationStore.designCapacity(this) > 0) return;
        String model = Build.MODEL == null ? "" : Build.MODEL.toUpperCase(Locale.US);
        if (model.startsWith("SM-S721")) CalibrationStore.setDesignCapacity(this, 4565f);
    }

    private void buildUi() {
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setBackgroundColor(bg);
        shell.setOnApplyWindowInsetsListener((v, insets) -> {
            applyInsets(shell, insets);
            return insets;
        });

        int maxContentWidth = dp(640);
        int screenWidthPx = getResources().getDisplayMetrics().widthPixels;
        int constrainedWidth = screenWidthPx > maxContentWidth ? maxContentWidth : ViewGroup.LayoutParams.MATCH_PARENT;

        headerWrap = new FrameLayout(this);
        LinearLayout header = new LinearLayout(this);
        header.setPadding(dp(18), dp(12), dp(18), dp(12));
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView mark = new ImageView(this);
        mark.setImageResource(R.drawable.ic_launcher_foreground);
        mark.setBackground(tileBackground());
        mark.setPadding(dp(5), dp(5), dp(5), dp(5));
        mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        header.addView(mark, new LinearLayout.LayoutParams(dp(42), dp(42)));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(text("BatteryLens", 22, primary, Typeface.BOLD));
        TextView subtitle = text("CHARGE · OBSERVE · UNDERSTAND", 10, secondary, Typeface.BOLD);
        subtitle.setLetterSpacing(0.08f);
        titles.addView(subtitle, marginTop(1));
        header.addView(titles, weightedParams(1, 10));
        autoChip = chip("○ Paused", warning);
        autoChip.setOnClickListener(v -> {
            if (selectedPage != 0) showPage(0);
        });
        header.addView(autoChip);
        header.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or_, ob) -> {
            Rect hit = new Rect();
            autoChip.getHitRect(hit);
            int minTap = dp(48);
            int padX = Math.max(0, (minTap - hit.width()) / 2);
            int padY = Math.max(0, (minTap - hit.height()) / 2);
            hit.inset(-padX, -padY);
            header.setTouchDelegate(new TouchDelegate(hit, autoChip));
        });
        FrameLayout.LayoutParams headerLp = new FrameLayout.LayoutParams(
                constrainedWidth, ViewGroup.LayoutParams.WRAP_CONTENT);
        headerLp.gravity = Gravity.CENTER_HORIZONTAL;
        headerWrap.addView(header, headerLp);
        shell.addView(headerWrap);
        shell.addView(hairlineDivider());

        contentScroll = new ScrollView(this);
        contentScroll.setFillViewport(true);
        contentScroll.setVerticalScrollBarEnabled(false);
        FrameLayout centerWrap = new FrameLayout(this);
        LinearLayout pageStack = new LinearLayout(this);
        pageStack.setOrientation(LinearLayout.VERTICAL);
        FrameLayout.LayoutParams stackLp = new FrameLayout.LayoutParams(
                constrainedWidth, ViewGroup.LayoutParams.WRAP_CONTENT);
        stackLp.gravity = Gravity.CENTER_HORIZONTAL;
        for (int i = 0; i < pages.length; i++) {
            pages[i] = new LinearLayout(this);
            pages[i].setOrientation(LinearLayout.VERTICAL);
            pages[i].setPadding(dp(16), dp(12), dp(16), dp(24));
            pageStack.addView(pages[i]);
        }
        centerWrap.addView(pageStack, stackLp);
        contentScroll.addView(centerWrap);
        shell.addView(contentScroll, new LinearLayout.LayoutParams(-1, 0, 1));

        shell.addView(hairlineDivider());
        navWrap = new FrameLayout(this);
        navWrap.setBackgroundColor(surface);
        LinearLayout navigation = new LinearLayout(this);
        navigation.setPadding(dp(12), dp(8), dp(12), dp(8));
        String[] labels = {"Overview", "Trends", "Sessions", "Settings"};
        for (int i = 0; i < pages.length; i++) {
            final int index = i;
            tabs[i] = ghostButton(labels[i]);
            tabs[i].setTextSize(12);
            tabs[i].setSingleLine(true);
            tabs[i].setEllipsize(TextUtils.TruncateAt.END);
            tabs[i].setPadding(dp(4), dp(10), dp(4), dp(10));
            tabs[i].setOnClickListener(v -> showPage(index));
            navigation.addView(tabs[i], weightedParams(1, i == 0 ? 0 : 6));
        }
        FrameLayout.LayoutParams navLp = new FrameLayout.LayoutParams(
                constrainedWidth, ViewGroup.LayoutParams.WRAP_CONTENT);
        navLp.gravity = Gravity.CENTER_HORIZONTAL;
        navWrap.addView(navigation, navLp);
        shell.addView(navWrap);
        setContentView(shell);
        showPage(selectedPage);
    }

    private void applyInsets(View target, WindowInsets insets) {
        if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets sys = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
            boolean imeVisible = ime.bottom > sys.bottom;
            target.setPadding(0, 0, 0, imeVisible ? ime.bottom : 0);
            if (headerWrap != null) headerWrap.setPadding(sys.left, sys.top, sys.right, 0);
            if (contentScroll != null) contentScroll.setPadding(sys.left, 0, sys.right, 0);
            if (navWrap != null) {
                navWrap.setVisibility(imeVisible ? View.GONE : View.VISIBLE);
                navWrap.setPadding(sys.left, 0, sys.right, sys.bottom);
            }
        } else {
            applyInsetsLegacy(insets);
        }
    }

    @SuppressWarnings("deprecation")
    private void applyInsetsLegacy(WindowInsets insets) {
        int left = insets.getSystemWindowInsetLeft();
        int top = insets.getSystemWindowInsetTop();
        int right = insets.getSystemWindowInsetRight();
        int bottom = insets.getSystemWindowInsetBottom();
        if (headerWrap != null) headerWrap.setPadding(left, top, right, 0);
        if (contentScroll != null) contentScroll.setPadding(left, 0, right, 0);
        if (navWrap != null) navWrap.setPadding(left, 0, right, bottom);
    }

    private void ensurePageBuilt(int index) {
        if (built[index]) return;
        built[index] = true;
        if (index == 0) buildOverview(pages[0]);
        else if (index == 1) buildTrends(pages[1]);
        else if (index == 2) buildSessions(pages[2]);
        else buildSettings(pages[3]);
    }

    private void showPage(int index) {
        selectedPage = Math.max(0, Math.min(pages.length - 1, index));
        ensurePageBuilt(selectedPage);
        int tabFillActive = Color.argb(38, 109, 235, 209);
        int tabStrokeActive = Color.argb(120, 109, 235, 209);
        int tabRipple = Color.argb(48, 109, 235, 209);
        for (int i = 0; i < pages.length; i++) {
            boolean active = i == selectedPage;
            pages[i].setVisibility(active ? View.VISIBLE : View.GONE);
            tabs[i].setSelected(active);
            tabs[i].setTextColor(active ? accent : secondary);
            tabs[i].setTypeface(active ? SANS_BOLD : SANS_REGULAR);
            tabs[i].setBackground(rippleDrawable(active ? tabFillActive : Color.TRANSPARENT,
                    active ? tabStrokeActive : Color.TRANSPARENT, 14, tabRipple));
        }
        contentScroll.post(() -> contentScroll.scrollTo(0, 0));
        refreshVisiblePage();
    }

    private void buildOverview(LinearLayout root) {
        LinearLayout live = card(true);
        root.addView(live);
        live.addView(eyebrow("RIGHT NOW"));
        gauge = new BatteryGaugeView(this);
        live.addView(gauge, new LinearLayout.LayoutParams(-1, dp(196)));
        chargeStatus = text("Reading your battery…", 13, secondary, Typeface.NORMAL);
        chargeStatus.setGravity(Gravity.CENTER);
        live.addView(chargeStatus);
        LinearLayout[] liveTiles = addMetricPair(live, "Battery power", "Temperature");
        powerValue = (TextView) liveTiles[0].getChildAt(1);
        tempValue = (TextView) liveTiles[1].getChildAt(1);
        LinearLayout[] electrical = addMetricPair(live, "Current • signed", "Voltage");
        currentValue = (TextView) electrical[0].getChildAt(1);
        voltageValue = (TextView) electrical[1].getChildAt(1);
        live.addView(text("Power is measured at the battery, not the wall charger. Updates every 5 seconds while visible.", 12, secondary, Typeface.NORMAL), marginTop(12));

        LinearLayout hero = card(true);
        root.addView(hero, marginTop(14));
        hero.addView(eyebrow("BATTERY HEALTH"));
        hero.addView(text("Estimated from charge progress, including slow charging to your protection limit", 13, secondary, Typeface.NORMAL), marginTop(6));
        healthValue = text("Learning", 40, accent, Typeface.BOLD);
        healthValue.setFontFeatureSettings("tnum");
        hero.addView(healthValue, marginTop(10));
        healthSource = text("Collect a monitored charge session to begin.", 14, secondary, Typeface.NORMAL);
        hero.addView(healthSource, marginTop(4));
        LinearLayout[] healthTiles = addMetricPair(hero, "Full capacity", "Repeatability");
        capacityValue = (TextView) healthTiles[0].getChildAt(1);
        confidenceValue = (TextView) healthTiles[1].getChildAt(1);
        baselineSummary = text("", 12, secondary, Typeface.NORMAL);
        baselineSummary.setPadding(0, dp(4), 0, dp(4));
        baselineSummary.setOnClickListener(v -> {
            if (CalibrationStore.designCapacity(this) <= 0) showPage(3);
        });
        hero.addView(baselineSummary, marginTop(10));

        LinearLayout monitor = section(root, "Session monitor", "Automatic monitoring runs in the background and resumes after restarts. A persistent notification keeps it visible.");
        autoSwitch = new Switch(this);
        autoSwitch.setText("Automatic monitoring");
        autoSwitch.setTextColor(primary);
        autoSwitch.setTextSize(15);
        autoSwitch.setTypeface(SANS_REGULAR);
        autoSwitch.setMinHeight(dp(48));
        styleSwitch(autoSwitch);
        autoSwitch.setChecked(CalibrationStore.isAutoEnabled(this));
        monitor.addView(autoSwitch, marginTop(10));
        autoStatus = text("", 14, primary, Typeface.NORMAL);
        monitor.addView(autoStatus, marginTop(10));
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(30);
        styleProgressBar(progress);
        monitor.addView(progress, marginTop(10, dp(10)));
        monitorHelp = text("", 12, secondary, Typeface.NORMAL);
        monitor.addView(monitorHelp, marginTop(8));
        manualButton = ghostButton("Start a manual session");
        monitor.addView(manualButton, marginTop(12));
        manualButton.setOnClickListener(v -> toggleManualSession());
        autoSwitch.setOnCheckedChangeListener((v, enabled) -> {
            if (updatingSwitch) return;
            if (enabled) {
                CalibrationStore.setAutoEnabled(this, true);
                requestNotifications();
                if (!BatteryMonitorService.start(this)) toast("Android blocked monitoring. Check app battery settings.");
                MonitorWatchdogJobService.schedule(this);
            } else {
                CalibrationStore.disableAutoAsync(this, "Automatic monitoring disabled", () -> {
                    if (!isGone() && visible) refreshBattery();
                });
                MonitorWatchdogJobService.cancel(this);
                BatteryMonitorService.stopMonitoring(this);
            }
            refreshBattery();
        });

        LinearLayout cycles = section(root, "Battery cycles", "One full cycle represents about 100% of battery use across partial discharges.");
        LinearLayout[] cycleTiles = addMetricPair(cycles, "Lifetime • device", "Observed • this app");
        cyclesValue = (TextView) cycleTiles[0].getChildAt(1);
        observedValue = (TextView) cycleTiles[1].getChildAt(1);
        cycleExplanation = text("", 13, secondary, Typeface.NORMAL);
        cycles.addView(cycleExplanation, marginTop(12));

        LinearLayout eta = section(root, "Charging time", "Android’s estimate may be unavailable when charging is paused or limited by Battery Protection.");
        timeValue = text("—", 24, primary, Typeface.BOLD);
        timeValue.setFontFeatureSettings("tnum");
        eta.addView(timeValue, marginTop(10));
        chargeInfo = text("Waiting for charging data", 13, secondary, Typeface.NORMAL);
        eta.addView(chargeInfo, marginTop(8));

        LinearLayout insights = section(root, "Today's battery insights", "Observed while BatteryLens was running. Rates need enough unplugged readings; missing time is excluded.");
        LinearLayout[] drainTiles = addMetricPair(insights, "Interactive drain", "Idle drain");
        screenDrainValue = (TextView) drainTiles[0].getChildAt(1);
        idleDrainValue = (TextView) drainTiles[1].getChildAt(1);
        runtimeValue = text("Runtime: collecting observations", 14, primary, Typeface.NORMAL);
        insights.addView(runtimeValue, marginTop(12));
        dailyStress = text("Temperature history: collecting observations", 13, secondary, Typeface.NORMAL);
        insights.addView(dailyStress, marginTop(8));
    }

    private void buildTrends(LinearLayout root) {
        root.addView(text("The rhythm of your battery", 24, primary, Typeface.BOLD));
        root.addView(text("Readings collected while this app is visible or monitoring runs.", 14, secondary, Typeface.NORMAL), marginTop(6));
        LinearLayout plot = section(root, "Live history", "Up to 7 days on this phone. Readings are spaced about five minutes apart; missing time stays as a gap.");
        LinearLayout metricBar = segmentedBar(new String[]{"Level %", "Current mA", "Temp °C"}, trendMetric, index -> {
            trendMetric = index;
            refreshTrends();
        });
        plot.addView(metricBar, marginTop(12));
        int windowIdx = trendHours == 1 ? 0 : trendHours == 6 ? 1 : trendHours == 24 ? 2 : 3;
        LinearLayout windowBar = segmentedBar(new String[]{"1h", "6h", "24h", "7d"}, windowIdx, index -> {
            trendHours = new int[]{1, 6, 24, 168}[index];
            refreshTrends();
        });
        plot.addView(windowBar, marginTop(8));
        liveChart = new LiveChartView(this);
        plot.addView(liveChart, marginTop(8, dp(220)));
        trendSummary = text("Waiting for readings", 14, primary, Typeface.NORMAL);
        trendSummary.setLineSpacing(dp(4), 1f);
        plot.addView(trendSummary, marginTop(10));
        LinearLayout meaning = section(root, "Reading the chart", "Positive current means energy is entering the battery; negative means it is leaving, according to Android’s API. Some firmware reports different units or signs. This app does not guess a correction.");
        meaning.addView(text("Observed drain needs at least 30 minutes and a 2-point level drop while unplugged. It excludes large jumps, charging, and monitoring gaps. It is not a prediction of remaining battery life.", 13, secondary, Typeface.NORMAL), marginTop(10));
        Button export = filledButton("Export live readings as CSV");
        root.addView(export, marginTop(14));
        export.setOnClickListener(v -> exportTrends());
        Button clear = ghostButton("Clear live history");
        root.addView(clear, marginTop(10));
        clear.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("Clear live history?")
                .setMessage("Removes the stored chart readings. Charging sessions and observed cycles are kept. New readings will continue while monitoring or the app is open.")
                .setNegativeButton("Keep", null).setPositiveButton("Clear", (d, i) -> {
                    TrendStore.clear(this, success -> {
                        if (isGone()) return;
                        if (success) {
                            cachedInsights = null;
                            lastTrendRevision = Long.MIN_VALUE;
                            refreshTrends();
                            refreshBattery();
                        } else toast("Could not clear history. Try again.");
                    });
                }).show());
    }

    private void refreshTrends() {
        if (!built[1] || selectedPage != 1 || trendsLoading) return;
        long revision = TrendStore.revision(), now = System.currentTimeMillis();
        if (revision == lastTrendRevision && trendHours == lastTrendHours && trendMetric == lastTrendMetric
                && now >= lastTrendRefresh && now - lastTrendRefresh < 60_000) return;
        trendsLoading = true;
        final int requestedHours = trendHours, requestedMetric = trendMetric;
        TrendStore.requestPoints(this, requestedHours * 3_600_000L, (points, loadedRevision) -> {
            trendsLoading = false;
            if (isGone() || !built[1]) return;
            if (trendHours != requestedHours || trendMetric != requestedMetric) {
                refreshTrends();
                return;
            }
            lastTrendRevision = loadedRevision;
            lastTrendHours = requestedHours;
            lastTrendMetric = requestedMetric;
            lastTrendRefresh = System.currentTimeMillis();
            renderTrends(points);
        });
    }

    private void renderTrends(List<TrendPoint> points) {
        liveChart.setData(points, trendMetric);
        TrendAnalysis stats = new TrendAnalysis(points);
        trendSummary.setText(points.isEmpty() ? "No readings yet. Keep the app open or enable monitoring to build your chart."
                : stats.count + " readings · " + formatDuration(stats.observedMs) + " observed"
                + "\nTemperature range: " + (Double.isFinite(stats.minTemp) ? one.format(stats.minTemp) + "–" + one.format(stats.maxTemp) + "°C" : "Unavailable")
                + "\nObserved drain: " + (Double.isFinite(stats.drainPerHour) ? one.format(stats.drainPerHour) + " percentage points/hour" : "Collecting unplugged observations")
                + "\nLatest reading: " + formatShortDate(points.get(points.size() - 1).time));
    }

    private void exportTrends() {
        TrendStore.requestPoints(this, TrendStore.RETENTION_MS, (points, revision) -> {
            if (!isGone()) startTrendExport(points);
        });
    }

    private void startTrendExport(List<TrendPoint> points) {
        if (points.isEmpty()) { toast("No live readings to export"); return; }
        pendingCsv = CsvExports.trends(points);
        awaitingDocumentPicker = true;
        startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("text/csv").putExtra(Intent.EXTRA_TITLE, "BatteryLens-live-readings.csv"), 77);
    }

    private void buildSessions(LinearLayout root) {
        root.addView(text("Your battery, over time", 24, primary, Typeface.BOLD));
        root.addView(text("A longer history gives context to each estimate.", 14, secondary, Typeface.NORMAL), marginTop(6));
        LinearLayout trend = section(root, "Capacity trend", "Only new multi-point measurements are eligible. Earlier endpoint estimates stay in the journal for comparison.");
        int hwIdx = healthWindowDays == 7 ? 0 : healthWindowDays == 30 ? 1 : healthWindowDays == 90 ? 2 : 3;
        LinearLayout hwBar = segmentedBar(new String[]{"7d", "30d", "90d", "All"}, hwIdx, index -> {
            healthWindowDays = new int[]{7, 30, 90, 0}[index];
            if (cachedSessions != null) chart.setData(cachedSessions, CalibrationStore.designCapacity(this), healthWindowDays);
        });
        trend.addView(hwBar, marginTop(12));
        chart = new HealthChartView(this);
        trend.addView(chart, marginTop(8, dp(180)));
        LinearLayout history = section(root, "Charging journal", "Up to 60 sessions. Tap a session for its measurements and validation result.");
        sessionSummary = text("", 13, accent, Typeface.BOLD);
        history.addView(sessionSummary, marginTop(12));
        LinearLayout filterBar = segmentedBar(new String[]{"All", "Eligible", "History"}, sessionFilter, index -> {
            sessionFilter = index;
            if (cachedSessions != null) updateHistory(cachedSessions, CalibrationStore.designCapacity(this));
        });
        history.addView(filterBar, marginTop(10));
        historyValue = text("No sessions yet", 14, primary, Typeface.NORMAL);
        historyValue.setLineSpacing(dp(3), 1.1f);
        history.addView(historyValue, marginTop(12));
        sessionList = new LinearLayout(this);
        sessionList.setOrientation(LinearLayout.VERTICAL);
        history.addView(sessionList);
        Button export = filledButton("Export all sessions as CSV");
        root.addView(export, marginTop(14));
        export.setOnClickListener(v -> exportSessions());
        Button clear = ghostButton("Clear calibration history");
        root.addView(clear, marginTop(10));
        clear.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("Clear calibration history?")
                .setMessage("This removes saved capacity estimates and cancels the current calibration session. Observed cycles are kept.")
                .setNegativeButton("Keep history", null).setPositiveButton("Clear", (dialog, which) -> {
                    CalibrationStore.clearHistoryAsync(this, (changed, note) -> {
                        if (isGone()) return;
                        cachedSessions = null;
                        cachedEstimate = null;
                        historySnapshot = null;
                        lastHistoryRevision = -1L;
                        refreshBattery();
                        BatteryWidgetUpdater.updateAllIfPinnedAsync(this);
                    });
                }).show());
    }

    private void buildSettings(LinearLayout root) {
        root.addView(text("Make it work for you", 25, primary, Typeface.BOLD));
        LinearLayout setup = section(root, "Background monitoring & restart survival", "Set BatteryLens battery usage to Unrestricted and exempt it from Sleeping / Deep sleeping apps so monitoring continues reliably across screen-off charging and phone restarts.");
        Button settings = ghostButton("Open Android app settings");
        setup.addView(settings, marginTop(12));
        settings.setOnClickListener(v -> openAppSettings());
        notificationsButton = ghostButton(areNotificationsAllowed() ? "Notification settings · Enabled" : "Enable notifications");
        setup.addView(notificationsButton, marginTop(10));
        notificationsButton.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                if (CalibrationStore.prefs(this).getBoolean(KEY_NOTIF_PROMPTED, false)
                        && !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                    openNotificationSettings();
                } else {
                    requestNotifications();
                }
            } else {
                openNotificationSettings();
            }
        });

        LinearLayout widgets = section(root, "Home screen widgets", "Choose a size to add BatteryLens to your Home screen, or drag-resize a widget later.");
        final WidgetSnapshot.WidgetSize[] widgetSizes = {
                WidgetSnapshot.WidgetSize.COMPACT_2X1,
                WidgetSnapshot.WidgetSize.SQUARE_2X2,
                WidgetSnapshot.WidgetSize.WIDE_4X1,
                WidgetSnapshot.WidgetSize.DASHBOARD_4X2
        };
        final String[] widgetNotes = {
                "Compact (2×1) — Level %, state badge, temp, power & health.",
                "Status (2×2) — Hero level, power, temp tiles, live badge & refresh.",
                "Wide Strip (4×1) — Single-row telemetry bar with health & monitor state.",
                "Dashboard (4×2) — Full 4-tile Overview card & active charge progress."
        };
        final int[] chosenWidgetIndex = {1};
        TextView widgetSummary = text(widgetNotes[1], 13, secondary, Typeface.NORMAL);
        widgets.addView(segmentedBar(new String[]{"2×1", "2×2", "4×1", "4×2"}, 1, idx -> {
            chosenWidgetIndex[0] = idx;
            widgetSummary.setText(widgetNotes[idx]);
        }), marginTop(12));
        widgets.addView(widgetSummary, marginTop(8));
        Button addWidgetBtn = ghostButton(BatteryWidgetUpdater.canRequestPin(this)
                ? "Add selected widget"
                : "How to add widget");
        widgets.addView(addWidgetBtn, marginTop(12));
        addWidgetBtn.setOnClickListener(v -> {
            WidgetSnapshot.WidgetSize selected = widgetSizes[chosenWidgetIndex[0]];
            if (!BatteryWidgetUpdater.requestPin(this, selected)) {
                toast("Long-press your Home screen → Widgets → BatteryLens");
            }
        });

        LinearLayout limit = section(root, "Battery Protection limit", "Set the charge limit configured on your phone, or leave it on Auto. Auto recognizes common limits when Android reports charging stopped.");
        Button limitButton = ghostButton(chargeLimitLabel(CalibrationStore.chosenChargeLimit(this)));
        limit.addView(limitButton, marginTop(12));
        limitButton.setOnClickListener(v -> {
            String[] options = {"Auto-detect", "80%", "85%", "90%", "95%", "100%", "Other…"};
            int[] values = {0, 80, 85, 90, 95, 100};
            new AlertDialog.Builder(this).setTitle("Measurement charge limit").setItems(options, (d, index) -> {
                if (index == values.length) {
                    EditText input = new EditText(this);
                    input.setInputType(InputType.TYPE_CLASS_NUMBER);
                    input.setHint("50–100%");
                    input.setPadding(dp(20), dp(10), dp(20), dp(10));
                    new AlertDialog.Builder(this).setTitle("Custom protection limit")
                            .setView(input).setNegativeButton("Cancel", null)
                            .setPositiveButton("Save", (dialog, which) -> {
                                try {
                                    int custom = Integer.parseInt(input.getText().toString().trim());
                                    if (custom < 50 || custom > 100) throw new NumberFormatException();
                                    saveChargeLimit(custom, limitButton);
                                } catch (NumberFormatException e) { toast("Enter a limit between 50% and 100%"); }
                            }).show();
                    return;
                }
                saveChargeLimit(values[index], limitButton);
            }).show();
        });

        LinearLayout activity = section(root, "Activity context", "Mark hotspot use yourself when testing its effect on drain or charging. Android does not reliably expose another app's hotspot state.");
        Switch hotspotSwitch = new Switch(this);
        hotspotSwitch.setText("I am using hotspot");
        hotspotSwitch.setTextSize(15);
        hotspotSwitch.setTextColor(primary);
        hotspotSwitch.setTypeface(SANS_REGULAR);
        hotspotSwitch.setMinHeight(dp(48));
        styleSwitch(hotspotSwitch);
        hotspotSwitch.setChecked(CalibrationStore.prefs(this).getBoolean("hotspot_marker", false));
        activity.addView(hotspotSwitch, marginTop(10));
        hotspotSwitch.setOnCheckedChangeListener((v, checked) -> {
            CalibrationStore.prefs(this).edit().putBoolean("hotspot_marker", checked).apply();
            refreshBattery();
        });

        LinearLayout alerts = section(root, "Charge care", "Choose charge, temperature, and low-battery notification thresholds while monitoring runs.");
        Switch alertSwitch = new Switch(this);
        alertSwitch.setText("Enable battery alerts");
        alertSwitch.setTextSize(15);
        alertSwitch.setTextColor(primary);
        alertSwitch.setTypeface(SANS_REGULAR);
        alertSwitch.setMinHeight(dp(48));
        styleSwitch(alertSwitch);
        alertSwitch.setChecked(CalibrationStore.prefs(this).getBoolean("alerts", false));
        alerts.addView(alertSwitch, marginTop(10));
        alertSwitch.setOnCheckedChangeListener((v, enabled) -> {
            CalibrationStore.prefs(this).edit().putBoolean("alerts", enabled).apply();
            if (enabled) { requestNotifications(); toast("Alerts run while monitoring is enabled"); }
        });
        addThresholdSegmented(alerts, "Charge notification target", "charge_target", 80,
                new String[]{"70%", "80%", "85%", "90%", "100%"}, new int[]{70, 80, 85, 90, 100}, 1);
        addThresholdSegmented(alerts, "Temperature alert", "heat_target", 42,
                new String[]{"38°C", "40°C", "42°C", "45°C"}, new int[]{38, 40, 42, 45}, 2);
        addThresholdSegmented(alerts, "Low battery reminder", "low_target", 0,
                new String[]{"Off", "10%", "15%", "20%", "25%"}, new int[]{0, 10, 15, 20, 25}, 0);
        alerts.addView(text("Thresholds are personal reminders, not a battery safety diagnosis. Repeated alerts are suppressed until the reading recovers.", 12, secondary, Typeface.NORMAL), marginTop(10));

        LinearLayout design = section(root, "Rated capacity", "Enter your device's rated battery capacity in mAh (e.g., S24 FE uses 4565 mAh rated / 4700 mAh typical). Affects displayed health %, not measured mAh.");
        designCapacityInput = new EditText(this);
        designCapacityInput.setTextColor(primary);
        designCapacityInput.setHintTextColor(secondary);
        designCapacityInput.setTextSize(18);
        designCapacityInput.setFontFeatureSettings("tnum");
        designCapacityInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        float stored = CalibrationStore.designCapacity(this);
        designCapacityInput.setText(stored > 0 ? String.valueOf(Math.round(stored)) : "");
        designCapacityInput.setHint("Rated capacity in mAh");
        designCapacityInput.setContentDescription("Rated battery capacity in milliamp hours");
        designCapacityInput.setSingleLine(true);
        designCapacityInput.setBackground(inputBackground());
        designCapacityInput.setPadding(dp(14), dp(12), dp(14), dp(12));
        design.addView(designCapacityInput, marginTop(12));
        Button save = filledButton("Save rated capacity");
        design.addView(save, marginTop(10));
        save.setOnClickListener(v -> saveDesignCapacity());

        LinearLayout diagnostics = section(root, "Device compatibility", "Checks public Android readings and ordinary-app-readable cycle files without root. A reported zero can be a firmware placeholder.");
        Button check = ghostButton("Check compatibility");
        diagnostics.addView(check, marginTop(12));
        check.setOnClickListener(v -> {
            check.setEnabled(false);
            check.setText("Checking…");
            AppWorker.execute(() -> {
                CycleReader.scan();
                AppWorker.postMain(() -> {
                    if (!isGone()) {
                        check.setEnabled(true);
                        check.setText("Check compatibility");
                        refreshBattery();
                    }
                });
            });
        });
        rawValue = text("", 13, secondary, Typeface.NORMAL);
        rawValue.setTextIsSelectable(true);
        rawValue.setLineSpacing(dp(3), 1.1f);
        diagnostics.addView(rawValue, marginTop(12));
        Button copy = ghostButton("Copy device diagnostics");
        diagnostics.addView(copy, marginTop(12));
        copy.setOnClickListener(v -> copyDiagnostics());

        LinearLayout accuracy = section(root, "Getting useful estimates", "Charge across at least 30 percentage points below your limit (e.g., 20→80%). Three consistent sessions are needed for a headline health percentage.");
        accuracy.addView(text("BatteryLens excludes long pauses at your limit and checks many points along each charge. Unstable sessions stay as history without skewing health.", 13, secondary, Typeface.NORMAL), marginTop(10));
        TextView footer = text("BatteryLens 3.4 · Offline by design\nNo ads. No analytics. Your data stays on this phone.", 12, secondary, Typeface.NORMAL);
        footer.setGravity(Gravity.CENTER);
        root.addView(footer, marginTop(20));
    }

    private String chargeLimitLabel(int value) {
        int learned = CalibrationStore.learnedChargeLimit(this);
        return "Measurement limit: " + (value == 0
                ? "Auto-detect" + (learned > 0 ? " · learned " + learned + "%" : "") : value + "%");
    }

    private void saveChargeLimit(int value, Button button) {
        CalibrationStore.prefs(this).edit().putInt("measurement_charge_limit", value).apply();
        button.setText(chargeLimitLabel(value));
        toast("Charge limit saved for future sessions");
    }

    private static int indexOfInt(int[] values, int target, int fallbackIndex) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == target) return i;
        }
        return fallbackIndex;
    }

    private void addThresholdSegmented(LinearLayout parent, String title, String key, int fallback,
                                       String[] labels, int[] values, int fallbackIndex) {
        parent.addView(text(title, 12, secondary, Typeface.BOLD), marginTop(12));
        int current = CalibrationStore.prefs(this).getInt(key, fallback);
        parent.addView(segmentedBar(labels, indexOfInt(values, current, fallbackIndex), index ->
                CalibrationStore.prefs(this).edit().putInt(key, values[index]).apply()), marginTop(6));
    }

    private LinearLayout section(LinearLayout root, String title, String subtitle) {
        LinearLayout section = card(false);
        root.addView(section, marginTop(14));
        TextView heading = text(title, 17, primary, Typeface.BOLD);
        if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        section.addView(heading);
        section.addView(text(subtitle, 13, secondary, Typeface.NORMAL), marginTop(6));
        return section;
    }

    private LinearLayout[] addMetricPair(LinearLayout parent, String first, String second) {
        LinearLayout row = metricRow();
        boolean stacked = getResources().getConfiguration().fontScale > 1.25f || getResources().getConfiguration().screenWidthDp < 360;
        if (stacked) row.setOrientation(LinearLayout.VERTICAL);
        LinearLayout a = metricTile(first), b = metricTile(second);
        row.addView(a, stacked ? marginTop(0) : weightedParams(1, 0));
        row.addView(b, stacked ? marginTop(8) : weightedParams(1, 8));
        parent.addView(row, marginTop(14));
        return new LinearLayout[]{a, b};
    }

    private void requestNotificationsOnce() {
        if (Build.VERSION.SDK_INT < 33) return;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return;
        SharedPreferences p = CalibrationStore.prefs(this);
        if (p.getBoolean(KEY_NOTIF_PROMPTED, false)) return;
        p.edit().putBoolean(KEY_NOTIF_PROMPTED, true).apply();
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 42);
    }

    private void requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            CalibrationStore.prefs(this).edit().putBoolean(KEY_NOTIF_PROMPTED, true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 42);
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 42 && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            BatteryMonitorService.refreshNotification(this);
            refreshBattery();
        }
    }

    private boolean areNotificationsAllowed() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        return nm != null && nm.areNotificationsEnabled();
    }

    private void openNotificationSettings() {
        if (Build.VERSION.SDK_INT >= 26) {
            startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
        } else {
            openAppSettings();
        }
    }

    private void openAppSettings() {
        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
    }

    private void exportSessions() {
        CalibrationStore.requestHistoryAsync(this, (sessions, estimate, rev) -> {
            if (isGone()) return;
            if (sessions.isEmpty()) { toast("No sessions to export yet"); return; }
            pendingCsv = CsvExports.sessions(sessions);
            awaitingDocumentPicker = true;
            startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("text/csv").putExtra(Intent.EXTRA_TITLE, "BatteryLens-sessions.csv"), 77);
        });
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 77) return;
        awaitingDocumentPicker = false;
        final String csv = pendingCsv;
        pendingCsv = null;
        if (result != RESULT_OK || data == null || data.getData() == null || csv == null) return;
        final Uri uri = data.getData();
        AppWorker.execute(() -> {
            boolean success = false;
            try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
                if (output != null) { output.write(csv.getBytes(StandardCharsets.UTF_8)); success = true; }
            } catch (Exception ignored) { }
            final boolean saved = success;
            AppWorker.postMain(() -> {
                if (!isGone()) toast(saved ? "CSV exported" : "Could not write this file");
            });
        });
    }

    private void saveDesignCapacity() {
        try {
            float v = Float.parseFloat(designCapacityInput.getText().toString().trim().replace(',', '.'));
            if (!Float.isFinite(v) || v < 500 || v > 20000) throw new NumberFormatException();
            CalibrationStore.setDesignCapacity(this, v);
            lastHistoryRevision = -1L;
            toast("Rated capacity saved");
            refreshBattery();
        } catch (Exception e) {
            toast("Enter a valid capacity in mAh");
        }
    }

    private void toggleManualSession() {
        if (CalibrationStore.hasActive(this)) {
            String source = CalibrationStore.activeSource(this);
            if (!"manual".equals(source)) {
                toast("Automatic session is already recording");
                return;
            }
            CalibrationStore.finishSessionAsync(this, (changed, note) -> {
                if (!isGone()) { toast(note); refreshBattery(); }
            });
        } else {
            CalibrationStore.startSessionAsync(this, "manual", (started, note) -> {
                if (isGone()) return;
                if (started) { requestNotifications(); BatteryMonitorService.start(this); }
                toast(started ? "Manual calibration started" : note);
                refreshBattery();
            });
        }
    }

    private void refreshBattery() {
        refreshBattery(null);
    }

    private void refreshBattery(Intent batteryIntent) {
        BatteryReadings r = BatteryReadings.read(this, batteryIntent);
        lastReadings = r;
        if (!BatteryMonitorService.running) {
            TrendStore.observe(this, r);
        }

        boolean autoEnabled = CalibrationStore.isAutoEnabled(this);
        int chipState = BatteryMonitorService.running ? 1 : (autoEnabled && BatteryMonitorService.starting ? 2 : 3);
        if (chipState != lastChipState) {
            lastChipState = chipState;
            int chipColor = chipState == 1 ? accent : (chipState == 2 ? accentBlue : warning);
            int chipFill = Color.argb(38, Color.red(chipColor), Color.green(chipColor), Color.blue(chipColor));
            int chipStroke = Color.argb(120, Color.red(chipColor), Color.green(chipColor), Color.blue(chipColor));
            autoChip.setText(chipState == 1 ? "● Live" : (chipState == 2 ? "◐ Starting" : "○ Paused"));
            autoChip.setBackground(rippleDrawable(chipFill, chipStroke, 16, chipFill));
            autoChip.setTextColor(chipColor);
        }

        requestHistoryIfStale();
        refreshVisiblePage();
        BatteryWidgetUpdater.updateAllIfPinned(this, r);
    }

    private void requestHistoryIfStale() {
        long rev = CalibrationStore.sessionsRevision();
        float design = CalibrationStore.designCapacity(this);
        if (historyLoading) return;
        if (cachedSessions != null && rev == lastHistoryRevision && design == lastHistoryDesign) return;
        historyLoading = true;
        CalibrationStore.requestHistoryAsync(this, (sessions, estimate, loadedRev) -> {
            historyLoading = false;
            if (isGone()) return;
            cachedSessions = sessions;
            cachedEstimate = estimate;
            lastHistoryRevision = loadedRev;
            lastHistoryDesign = CalibrationStore.designCapacity(this);
            refreshVisiblePage();
            BatteryWidgetUpdater.updateAllIfPinnedAsync(this);
        });
    }

    private void refreshVisiblePage() {
        BatteryReadings r = lastReadings;
        if (selectedPage == 0 && built[0]) renderOverview(r);
        else if (selectedPage == 1 && built[1]) refreshTrends();
        else if (selectedPage == 2 && built[2]) renderSessionsPage();
        else if (selectedPage == 3 && built[3]) renderDiagnostics(r);
    }

    private void renderOverview(BatteryReadings r) {
        currentValue.setText(r != null && Double.isFinite(r.currentNowMa()) ? zero.format(r.currentNowMa()) + " mA" : "Unavailable");
        voltageValue.setText(r != null && r.voltageMv > 0 ? String.format(Locale.getDefault(), "%.2f V", r.voltageMv / 1000.0) : "Unavailable");
        float design = CalibrationStore.designCapacity(this);
        CalibrationStore.Estimate estimate = cachedEstimate;

        baselineSummary.setText(design > 0
                ? "Rated baseline: " + zero.format(design) + " mAh"
                : "Rated baseline not set · tap here to configure");
        baselineSummary.setTextColor(design > 0 ? secondary : accentBlue);
        updatingSwitch = true;
        autoSwitch.setChecked(CalibrationStore.isAutoEnabled(this));
        updatingSwitch = false;
        if (r == null) gauge.setLevel(-1, false);
        else gauge.setLevel(r.soc, r.isPlugged(), r.status);
        observedValue.setText(String.format(Locale.getDefault(), "%.2f", CalibrationStore.observedCycles(this)));
        chargeStatus.setText(r == null ? "Battery unavailable" : statusName(r.status) + " · " + plugName(r.plugged));

        if (r == null) {
            healthValue.setText("Learning");
            healthValue.setTextColor(accent);
            healthSource.setText("Battery data unavailable on this device state.");
            confidenceValue.setText("—");
            capacityValue.setText("—");
            powerValue.setText("—");
            tempValue.setText("—");
            cyclesValue.setText("—");
            cycleExplanation.setText("Device cycle reading unavailable.");
            timeValue.setText("—");
            chargeInfo.setText("Battery data unavailable");
            screenDrainValue.setText("—");
            idleDrainValue.setText("—");
            runtimeValue.setText("Runtime: unavailable");
            dailyStress.setText("Temperature history: unavailable");
            updateAutoStatus(null);
            return;
        }

        Double selectedHealth = null;
        Double selectedFcc = null;
        String source = cachedSessions == null ? "Loading session history…"
                : cachedSessions.isEmpty() ? "Collect a monitored charge of 30% or more."
                : "Earlier estimates are saved in Sessions. Collect new sessions for a checked result.";
        String confidence = "Not enough data";

        if (estimate != null && estimate.fccMah != null) {
            selectedFcc = estimate.fccMah;
            boolean ready = estimate.usedCount >= 3 && estimate.relativeMad <= 0.10;
            selectedHealth = ready && design > 0 ? 100.0 * selectedFcc / design : null;
            source = !ready ? estimate.usedCount < 3
                    ? "Preliminary capacity: " + estimate.usedCount + " of 3 eligible sessions. Health appears when readings agree."
                    : "Charge sessions disagree. Review Sessions and collect another steady charge."
                    : "Median of " + estimate.usedCount + " eligible sessions. Fuel-gauge estimate, not a lab measurement.";
            confidence = !ready ? (estimate.usedCount < 3 ? "Collect more data" : "Inconsistent")
                    : estimate.confidence;
        }

        if (selectedHealth != null) {
            healthValue.setText(one.format(selectedHealth) + "%");
            healthValue.setTextColor(colorForHealth(selectedHealth));
        } else {
            healthValue.setText("Learning");
            healthValue.setTextColor(accent);
        }
        healthSource.setText(source);
        confidenceValue.setText(confidence);
        capacityValue.setText(selectedFcc == null ? "—" : zero.format(selectedFcc) + " mAh");

        double powerW = r.batterySidePowerW();
        powerValue.setText(!Double.isNaN(powerW) ? one.format(powerW) + " W" : "—");
        double tempC = r.temperatureC();
        tempValue.setText(Double.isNaN(tempC) ? "—" : one.format(tempC) + "°C · " + thermalLabel(tempC));
        cyclesValue.setText(r.cycles >= 0 ? Integer.toString(r.cycles) : "Unavailable");
        long since = CalibrationStore.observedSince(this);
        cycleExplanation.setText((r.cycles >= 0 ? "Device source: " + r.cycleSource + (r.cycles == 0 ? ". Zero may be a firmware placeholder." : ".")
                : "Lifetime count is not exposed to this app. Check compatibility in Settings.")
                + "\nObserved cycles count only small, continuous battery-level drops seen by BatteryLens"
                + (since > 0 ? " since " + formatShortDate(since) : "")
                + ". They exclude unmonitored time and are not the phone’s lifetime total.");
        timeValue.setText(r.isPlugged() ? formatDuration(r.chargeTimeMs) : "—");
        refreshInsights(r);
        updateAutoStatus(r);
    }

    private void renderSessionsPage() {
        if (cachedSessions == null) return;
        float design = CalibrationStore.designCapacity(this);
        updateHistory(cachedSessions, design);
        chart.setData(cachedSessions, design, healthWindowDays);
    }

    private void renderDiagnostics(BatteryReadings r) {
        if (r == null) {
            rawValue.setText("Battery data unavailable.");
            return;
        }
        float design = CalibrationStore.designCapacity(this);
        CalibrationStore.Estimate estimate = cachedEstimate;
        Double snapshotFcc = r.hasUsableCounter() && r.soc >= 10 && r.soc <= 100
                ? (r.chargeUah / 1000.0) / (r.soc / 100.0) : null;
        double tempC = r.temperatureC();
        double powerW = r.batterySidePowerW();
        String model = Build.MODEL == null ? "Unknown" : Build.MODEL;

        StringBuilder raw = new StringBuilder();
        raw.append("Model              ").append(model).append('\n');
        raw.append("Android            ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        raw.append("Battery level      ").append(r.soc < 0 ? "—" : one.format(r.soc) + "%").append('\n');
        raw.append("Status             ").append(statusName(r.status)).append('\n');
        raw.append("Power source       ").append(plugName(r.plugged)).append('\n');
        raw.append("Android health     ").append(healthName(r.health)).append('\n');
        raw.append("Temperature        ").append(Double.isNaN(tempC) ? "—" : one.format(tempC) + " °C").append('\n');
        raw.append("Voltage            ").append(r.voltageMv == Integer.MIN_VALUE ? "—" : one.format(r.voltageMv / 1000.0) + " V").append('\n');
        raw.append("Charge counter     ").append(r.hasUsableCounter() ? zero.format(r.chargeUah / 1000.0) + " mAh" : "Unsupported").append('\n');
        raw.append("Current now        ").append(r.currentNowUa == Integer.MIN_VALUE ? "Unsupported" : one.format(r.currentNowUa / 1000.0) + " mA").append('\n');
        raw.append("Average current    ").append(r.currentAvgUa == Integer.MIN_VALUE ? "Unsupported" : one.format(r.currentAvgUa / 1000.0) + " mA").append('\n');
        raw.append("Battery-side power ").append(Double.isNaN(powerW) ? "Unsupported" : one.format(powerW) + " W").append('\n');
        raw.append("Energy counter     ").append(r.energyNwh == Long.MIN_VALUE ? "Unsupported" : one.format(r.energyNwh / 1_000_000.0) + " mWh").append('\n');
        raw.append("Cycle count        ").append(r.cycles >= 0 ? r.cycles : "Unsupported").append('\n');
        if (Build.VERSION.SDK_INT >= 34) {
            raw.append("Charging policy    ").append(r.chargingStatusExtra >= 0 ? r.chargingStatusExtra : "Unavailable").append('\n');
        }
        if (Build.VERSION.SDK_INT >= 36) {
            raw.append("Capacity level     ").append(capacityLevelName(r.capacityLevelExtra)).append('\n');
        }
        raw.append("Charge time left   ").append(formatDuration(r.chargeTimeMs)).append('\n');
        raw.append("Device SoH         No stable public API used\n");
        raw.append("Rated baseline     ").append(design > 0 ? zero.format(design) + " mAh" : "Not set").append('\n');
        raw.append("Checked FCC        ").append(estimate != null && estimate.fccMah != null ? zero.format(estimate.fccMah) + " mAh / " + estimate.usedCount + " sessions" : "Not ready").append('\n');
        raw.append("Fit variability    ").append(estimate != null && Double.isFinite(estimate.relativeMad) ? one.format(estimate.relativeMad * 100) + "%" : "Unavailable").append('\n');
        raw.append("Snapshot FCC       ").append(snapshotFcc != null ? zero.format(snapshotFcc) + " mAh" : "Unavailable").append('\n');
        raw.append("Measurement limit  ").append(chargeLimitLabel(CalibrationStore.chosenChargeLimit(this))).append('\n');
        raw.append("Auto calibration   ").append(CalibrationStore.isAutoEnabled(this) ? "On" : "Off").append('\n');
        raw.append("Cycle source       ").append(r.cycleSource).append('\n');
        raw.append("Observed cycles    ").append(CalibrationStore.observedCycles(this)).append('\n');
        raw.append("Monitor running    ").append(BatteryMonitorService.running).append('\n');
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        raw.append("Notifications      ").append(nm != null && nm.areNotificationsEnabled()).append('\n');
        raw.append("Power saving       ").append(pm != null && pm.isPowerSaveMode()).append('\n');
        raw.append("Battery exemption  ").append(pm != null && pm.isIgnoringBatteryOptimizations(getPackageName()));
        String exitNote = CalibrationStore.lastProcessExitNote(this);
        if (!exitNote.isEmpty()) raw.append("\nLast process exit  ").append(exitNote);
        raw.append("\n\nReadable cycle probes\n").append(CycleReader.cached().details);
        rawValue.setText(raw.toString());
    }

    private void refreshInsights(BatteryReadings r) {
        latestInsightSoc = r.soc;
        latestInsightPlugged = r.isPlugged();
        double current = r.currentNowMa(), power = r.batterySidePowerW();
        if (!r.isPlugged()) chargeInfo.setText("Unplugged · charging speed unavailable");
        else if (r.status == BatteryManager.BATTERY_STATUS_NOT_CHARGING
                || r.status == BatteryManager.BATTERY_STATUS_FULL)
            chargeInfo.setText("Charging stopped · possible protection limit, temperature control, or charger behavior");
        else if (!Double.isFinite(current) || !Double.isFinite(power))
            chargeInfo.setText("Charging speed unavailable on this device");
        else if (current <= 0)
            chargeInfo.setText("Battery is not gaining charge" + (current < 0 ? " · phone load exceeds input" : ""));
        else {
            String speed = power < 2 ? "Very slow" : power < 8 ? "Normal" : "Fast";
            String reason = r.temperatureC() >= 40 ? " · warm battery may slow charging"
                    : r.soc >= 80 ? " · charging may taper near the limit"
                    : CalibrationStore.prefs(this).getBoolean("hotspot_marker", false)
                    ? " · hotspot marked as active" : "";
            chargeInfo.setText(speed + " battery-side gain · " + one.format(power)
                    + " W (net, not charger input)" + reason);
        }
        long now = System.currentTimeMillis(), revision = TrendStore.revision();
        if (!insightsLoading && (cachedInsights == null || revision != lastInsightRevision
                || now < lastInsightRefresh || now - lastInsightRefresh >= 60_000)) {
            insightsLoading = true;
            TrendStore.requestInsights(this, (analysis, loadedRevision) -> {
                insightsLoading = false;
                if (isGone()) return;
                cachedInsights = analysis;
                lastInsightRevision = loadedRevision;
                lastInsightRefresh = System.currentTimeMillis();
                if (built[0] && selectedPage == 0) renderInsightValues(latestInsightSoc, latestInsightPlugged);
            });
        }
        renderInsightValues(r.soc, r.isPlugged());
    }

    private void renderInsightValues(double soc, boolean plugged) {
        InsightAnalysis insights = cachedInsights;
        if (insights == null) {
            screenDrainValue.setText("Collecting");
            idleDrainValue.setText("Collecting");
            runtimeValue.setText("Runtime: collecting unplugged observations");
            dailyStress.setText("Temperature history: collecting observations");
            return;
        }
        screenDrainValue.setText(Double.isFinite(insights.screenRate) ? one.format(insights.screenRate) + "%/h" : "Collecting");
        idleDrainValue.setText(Double.isFinite(insights.idleRate) ? one.format(insights.idleRate) + "%/h" : "Collecting");
        double hours = insights.runtimeHours(soc);
        runtimeValue.setText(plugged ? "Runtime: measured while unplugged only"
                : Double.isFinite(hours) ? "Observed-use runtime: about " + formatDuration((long) (hours * 3_600_000))
                    + " · " + (insights.runtimeWellSampled ? "broader sample" : "early estimate")
                    + " from the last 24h" : "Runtime: collecting unplugged observations");
        dailyStress.setText("Past 24h observed: " + (Double.isFinite(insights.peakTemp)
                ? "peak " + one.format(insights.peakTemp) + "°C" : "temperature unavailable")
                + " · ≥35°C " + formatDuration(insights.hotMs35)
                + " · ≥40°C " + formatDuration(insights.hotMs40)
                + (insights.unusualIdle() ? "\nIdle drain above earlier monitored baseline ("
                    + one.format(insights.recentIdleRate) + " vs " + one.format(insights.baselineIdleRate)
                    + "%/h). Check Android's battery usage for app details." : "")
                + (insights.hotspotMs > 0 ? "\nHotspot manually marked for " + formatDuration(insights.hotspotMs) : ""));
    }

    private void copyDiagnostics() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) { toast("Clipboard unavailable"); return; }
        renderDiagnostics(lastReadings);
        String text = "BatteryLens 3.4 diagnostics\n" + rawValue.getText()
                + "\nLast calibration note  " + CalibrationStore.lastNote(this);
        clipboard.setPrimaryClip(ClipData.newPlainText("BatteryLens diagnostics", text));
        toast("Diagnostics copied");
    }

    private void updateAutoStatus(BatteryReadings r) {
        double gain = r == null || !CalibrationStore.hasActive(this) ? 0
                : CalibrationStore.activePeakSoc(this) - CalibrationStore.activeStartSoc(this);
        progress.setProgress((int) Math.max(0, Math.min(30, gain)));
        boolean notifications = areNotificationsAllowed();
        if (notificationsButton != null) {
            notificationsButton.setText(notifications ? "Notification settings · Enabled" : "Enable notifications");
        }
        String monitorState = BatteryMonitorService.running ? "Background monitor running. "
                : (CalibrationStore.isAutoEnabled(this) && BatteryMonitorService.starting
                        ? "Starting background monitor… "
                        : "Background monitor paused. Enable monitoring to follow future charges. ");
        String helpSuffix = CalibrationStore.activePaused(this)
                ? "Charging paused near its protection limit. The app keeps the last charging checkpoint."
                : gain > 0
                        ? (gain < 30 ? Math.round(30 - gain) + "% more for the minimum session length." : "Minimum length reached. Unplug when convenient.")
                        : (CalibrationStore.hasActive(this) && r != null && r.soc >= CalibrationStore.activeStartSoc(this) + 5
                                ? "Level rose, waiting for device charge counter to advance."
                                : "Aim for 30%+ gained below your phone's charge limit.");
        monitorHelp.setText(monitorState
                + (notifications ? "" : "Notifications are disabled in Android. ")
                + helpSuffix);
        if (CalibrationStore.hasActive(this)) {
            String src = CalibrationStore.activeSource(this);
            double start = CalibrationStore.activeStartSoc(this);
            long started = CalibrationStore.activeStartTime(this);
            String age = started > 0 ? " • " + formatDuration(System.currentTimeMillis() - started) : "";
            String currentPart = r == null || r.soc < 0 ? "" : " → " + one.format(r.soc) + "%";
            autoStatus.setText(("auto".equals(src) ? "Recording automatically" : "Manual session")
                    + " • " + one.format(start) + "%" + currentPart + age);
            if ("manual".equals(src)) {
                manualButton.setEnabled(true);
                manualButton.setText("Finish manual session");
                manualButton.setAlpha(1f);
            } else {
                manualButton.setEnabled(false);
                manualButton.setText("Automatic session in progress");
                manualButton.setAlpha(0.7f);
            }
        } else {
            String prefix = CalibrationStore.isAutoEnabled(this) ? "Waiting for a monitored charge" : "Automatic calibration is off";
            autoStatus.setText(prefix + "\n" + CalibrationStore.lastNote(this));
            manualButton.setEnabled(true);
            manualButton.setText("Start manual session");
            manualButton.setAlpha(1f);
        }
    }

    private void updateHistory(List<CalibrationStore.Session> sessions, float design) {
        if (historySnapshot == sessions && historyFilter == sessionFilter && historyDesign == design
                && sessionList.getChildCount() > 0) return;
        historySnapshot = sessions;
        historyFilter = sessionFilter;
        historyDesign = design;
        int eligible = 0;
        long duration = 0;
        for (CalibrationStore.Session session : sessions) {
            if (session.used) eligible++;
            duration += session.durationMs();
        }
        sessionSummary.setText(sessions.size() + " saved · " + eligible + " eligible · " + formatDuration(duration) + " recorded");
        sessionList.removeAllViews();
        int shown = 0;
        int tileRipple = Color.argb(44, 109, 235, 209);
        for (int i = sessions.size() - 1; i >= 0; i--) {
            CalibrationStore.Session s = sessions.get(i);
            if (sessionFilter == 1 && !s.used || sessionFilter == 2 && s.used) continue;
            shown++;
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setPadding(dp(14), dp(14), dp(14), dp(14));
            item.setBackground(rippleDrawable(surface, stroke, 16, tileRipple));

            LinearLayout topRow = new LinearLayout(this);
            topRow.setGravity(Gravity.CENTER_VERTICAL);
            String formattedDate = formatShortDate(s.endTime);
            TextView dateLabel = text(formattedDate, 12, secondary, Typeface.NORMAL);
            topRow.addView(dateLabel, weightedParams(1, 0));
            int badgeColor = s.used ? accent : warning;
            int deltaGain = Math.round((float) s.deltaSoc());
            TextView deltaBadge = text((deltaGain >= 0 ? "+" : "") + deltaGain + "%", 11, badgeColor, Typeface.BOLD);
            deltaBadge.setFontFeatureSettings("tnum");
            deltaBadge.setPadding(dp(8), dp(3), dp(8), dp(3));
            deltaBadge.setBackground(chipDrawable(
                    Color.argb(34, Color.red(badgeColor), Color.green(badgeColor), Color.blue(badgeColor)),
                    Color.argb(90, Color.red(badgeColor), Color.green(badgeColor), Color.blue(badgeColor)), 10));
            topRow.addView(deltaBadge);
            item.addView(topRow);

            TextView headline = text(zero.format(s.startSoc) + "% → " + zero.format(s.endSoc) + "%  ·  " + formatDuration(s.durationMs()), 17, primary, Typeface.BOLD);
            headline.setFontFeatureSettings("tnum");
            item.addView(headline, marginTop(6));
            item.addView(text(s.used ? "Eligible · " + zero.format(s.fccMah) + " mAh" : "History only · " + s.reason.split(" • ")[0], 13, badgeColor, s.used ? Typeface.BOLD : Typeface.NORMAL), marginTop(5));
            item.setFocusable(true);
            item.setClickable(true);
            item.setContentDescription(formattedDate + ", " + Math.round(s.deltaSoc()) + " percentage points gained, " + (s.used ? "eligible" : "history only") + ". Show details");
            item.setOnClickListener(v -> showSession(s));
            sessionList.addView(item, marginTop(10));
        }
        historyValue.setVisibility(shown == 0 ? View.VISIBLE : View.GONE);
        historyValue.setText(sessions.isEmpty() ? "No completed sessions yet. Enable monitoring and charge normally to start your journal." : "No sessions match this filter.");
    }

    private String formatShortDate(long millis) {
        tempDate.setTime(millis);
        return shortDate.format(tempDate);
    }

    private void showSession(CalibrationStore.Session s) {
        double charged = s.endUah > s.startUah && s.startUah > 0 ? (s.endUah - s.startUah) / 1000.0 : Double.NaN;
        double energyWh = Double.isFinite(charged) && s.startVoltageMv > 0 && s.endVoltageMv > 0
                ? charged * (s.startVoltageMv + s.endVoltageMv) / 2_000_000.0 : Double.NaN;
        String detail = formatShortDate(s.startTime) + " → " + formatShortDate(s.endTime)
                + "\n\nLevel: " + one.format(s.startSoc) + "% → " + one.format(s.endSoc) + "%"
                + "\nDuration: " + formatDuration(s.durationMs())
                + "\nNet charge added: " + (Double.isFinite(charged) ? zero.format(charged) + " mAh" : "Unavailable")
                + "\nAverage net current: " + (Double.isFinite(charged) && s.durationMs() >= 60_000 ? zero.format(charged * 3_600_000 / s.durationMs()) + " mA" : "Unavailable")
                + "\nEstimated net energy: " + (Double.isFinite(energyWh) ? one.format(energyWh) + " Wh" : "Unavailable")
                + "\nPower source: " + plugName(s.plugType)
                + "\nHotspot marked: " + (s.hotspotMarked ? "Yes (manual)" : "No")
                + "\nCapacity estimate: " + (Double.isFinite(s.fccMah) ? zero.format(s.fccMah) + " mAh" : "Unavailable")
                + "\nEndpoint-only comparison: " + (Double.isFinite(s.endpointFccMah) ? zero.format(s.endpointFccMah) + " mAh" : "Unavailable")
                + "\nMulti-point agreement: " + (Double.isFinite(s.fitSpread) ? one.format(s.fitSpread * 100) + "% spread across " + s.fitPairs + " comparisons" : "Unavailable")
                + "\nCharging reversals: " + s.reversals
                + "\nTime paused after progress: " + formatDuration(s.pausedMs)
                + "\nTemperature: " + (Double.isFinite(s.minTemp) && Double.isFinite(s.maxTemp) ? one.format(s.minTemp) + "–" + one.format(s.maxTemp) + "°C" : "Unavailable")
                + "\nObservations: " + s.samples + " · " + s.source
                + "\n\n" + (s.used ? "Eligible for median filtering. " : "Excluded from health estimate. ") + s.reason;
        new AlertDialog.Builder(this).setTitle("Charging session").setMessage(detail).setPositiveButton("Done", null).show();
    }

    private boolean isGone() {
        return isFinishing() || isDestroyed();
    }

    private int colorForHealth(double health) {
        if (health >= 90) return accent;
        if (health >= 80) return warning;
        return danger;
    }

    private String thermalLabel(double c) {
        if (c < 15) return "Cool";
        if (c < 35) return "Normal";
        if (c < 40) return "Warm";
        if (c < 45) return "Hot";
        return "Very hot";
    }

    private String capacityLevelName(int level) {
        switch (level) {
            case BatteryManager.BATTERY_CAPACITY_LEVEL_CRITICAL: return "Critical";
            case BatteryManager.BATTERY_CAPACITY_LEVEL_LOW: return "Low";
            case BatteryManager.BATTERY_CAPACITY_LEVEL_NORMAL: return "Normal";
            case BatteryManager.BATTERY_CAPACITY_LEVEL_HIGH: return "High";
            case BatteryManager.BATTERY_CAPACITY_LEVEL_FULL: return "Full";
            case BatteryManager.BATTERY_CAPACITY_LEVEL_UNKNOWN: return "Unknown";
            default: return "Unsupported";
        }
    }

    private String healthName(int h) {
        switch (h) {
            case BatteryManager.BATTERY_HEALTH_GOOD: return "Good";
            case BatteryManager.BATTERY_HEALTH_OVERHEAT: return "Overheat";
            case BatteryManager.BATTERY_HEALTH_DEAD: return "Dead";
            case BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE: return "Over-voltage";
            case BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE: return "Failure";
            case BatteryManager.BATTERY_HEALTH_COLD: return "Cold";
            default: return "Unknown";
        }
    }

    private String statusName(int s) {
        switch (s) {
            case BatteryManager.BATTERY_STATUS_CHARGING: return "Charging";
            case BatteryManager.BATTERY_STATUS_DISCHARGING: return "Discharging";
            case BatteryManager.BATTERY_STATUS_FULL: return "Full";
            case BatteryManager.BATTERY_STATUS_NOT_CHARGING: return "Charging paused";
            default: return "Unknown";
        }
    }

    private String plugName(int p) {
        if ((p & BatteryManager.BATTERY_PLUGGED_AC) != 0) return "AC";
        if ((p & BatteryManager.BATTERY_PLUGGED_USB) != 0) return "USB";
        if ((p & BatteryManager.BATTERY_PLUGGED_WIRELESS) != 0) return "Wireless";
        if (Build.VERSION.SDK_INT >= 33 && (p & BatteryManager.BATTERY_PLUGGED_DOCK) != 0) return "Dock";
        return "Battery";
    }

    private String formatDuration(long ms) {
        if (ms < 0) return "Unavailable";
        long min = ms / 60000;
        if (min < 1) return "<1m";
        if (min < 60) return min + "m";
        return (min / 60) + "h " + (min % 60) + "m";
    }

    private interface OnSegmentSelect {
        void onSelect(int index);
    }

    private LinearLayout segmentedBar(String[] labels, int initialIndex, OnSegmentSelect listener) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(dp(4), dp(4), dp(4), dp(4));
        bar.setBackground(chipDrawable(surface, stroke, 14));
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            TextView pill = text(labels[i], 12, secondary, Typeface.NORMAL);
            pill.setGravity(Gravity.CENTER);
            pill.setSingleLine(true);
            pill.setEllipsize(TextUtils.TruncateAt.END);
            pill.setMinHeight(dp(40));
            pill.setPadding(dp(6), dp(8), dp(6), dp(8));
            pill.setClickable(true);
            pill.setFocusable(true);
            pill.setOnClickListener(v -> {
                updateSegmentedBar(bar, index);
                listener.onSelect(index);
            });
            bar.addView(pill, weightedParams(1, i == 0 ? 0 : 4));
        }
        updateSegmentedBar(bar, Math.max(0, Math.min(labels.length - 1, initialIndex)));
        return bar;
    }

    private void updateSegmentedBar(LinearLayout bar, int selectedIndex) {
        int activeFill = Color.argb(38, 109, 235, 209);
        int activeStroke = Color.argb(120, 109, 235, 209);
        int rippleColor = Color.argb(48, 109, 235, 209);
        for (int i = 0; i < bar.getChildCount(); i++) {
            TextView pill = (TextView) bar.getChildAt(i);
            boolean active = i == selectedIndex;
            pill.setSelected(active);
            pill.setTextColor(active ? accent : secondary);
            pill.setTypeface(active ? SANS_BOLD : SANS_REGULAR);
            pill.setBackground(rippleDrawable(active ? activeFill : Color.TRANSPARENT,
                    active ? activeStroke : Color.TRANSPARENT, 10, rippleColor));
        }
    }

    private View hairlineDivider() {
        View v = new View(this);
        v.setBackgroundColor(stroke);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1))));
        return v;
    }

    private TextView eyebrow(String title) {
        TextView t = text(title, 11, accent, Typeface.BOLD);
        t.setLetterSpacing(0.08f);
        return t;
    }

    private void styleSwitch(Switch s) {
        int[][] states = {{android.R.attr.state_checked}, {}};
        s.setThumbTintList(new ColorStateList(states, new int[]{accent, secondary}));
        s.setTrackTintList(new ColorStateList(states, new int[]{
                Color.argb(110, 109, 235, 209),
                stroke
        }));
    }

    private void styleProgressBar(ProgressBar bar) {
        GradientDrawable track = new GradientDrawable();
        track.setColor(surface);
        track.setCornerRadius(dp(6));
        track.setStroke(dp(1), stroke);
        GradientDrawable fill = new GradientDrawable();
        fill.setColor(accent);
        fill.setCornerRadius(dp(6));
        ClipDrawable clip = new ClipDrawable(fill, Gravity.START, ClipDrawable.HORIZONTAL);
        LayerDrawable layers = new LayerDrawable(new Drawable[]{track, clip});
        layers.setId(0, android.R.id.background);
        layers.setId(1, android.R.id.progress);
        bar.setProgressDrawable(layers);
    }

    private LinearLayout metricRow() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    private LinearLayout card(boolean elevated) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(16), dp(16), dp(16), dp(16));
        l.setBackground(cardBackground(elevated ? cardElevated : card, elevated ? heroStroke : stroke));
        l.setElevation(dp(elevated ? 4 : 1));
        return l;
    }

    private LinearLayout metricTile(String label) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(12), dp(12), dp(12), dp(12));
        l.setBackground(tileBackground());
        TextView name = text(label, 12, secondary, Typeface.NORMAL);
        TextView value = text("—", 18, primary, Typeface.BOLD);
        value.setFontFeatureSettings("tnum");
        value.setLineSpacing(0f, 1.1f);
        l.addView(name);
        l.addView(value, marginTop(4));
        return l;
    }

    private TextView chip(String value, int fill) {
        TextView t = text(value, 12, fill, Typeface.BOLD);
        t.setFontFeatureSettings("tnum");
        t.setPadding(dp(12), dp(8), dp(12), dp(8));
        int tint = Color.argb(38, Color.red(fill), Color.green(fill), Color.blue(fill));
        int border = Color.argb(120, Color.red(fill), Color.green(fill), Color.blue(fill));
        t.setBackground(rippleDrawable(tint, border, 16, tint));
        return t;
    }

    private TextView text(String value, float sp, int color, int style) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(style == Typeface.BOLD ? SANS_BOLD : SANS_REGULAR);
        return t;
    }

    private Button filledButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(bg);
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setLetterSpacing(0.01f);
        b.setStateListAnimator(null);
        b.setTypeface(SANS_BOLD);
        b.setBackground(rippleDrawable(accent, accent, 14, Color.argb(60, 9, 20, 26)));
        b.setPadding(dp(14), dp(10), dp(14), dp(10));
        b.setMinHeight(dp(48));
        return b;
    }

    private Button ghostButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(primary);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setLetterSpacing(0.01f);
        b.setStateListAnimator(null);
        b.setTypeface(SANS_BOLD);
        b.setBackground(rippleDrawable(surface, stroke, 14, Color.argb(44, 109, 235, 209)));
        b.setPadding(dp(14), dp(10), dp(14), dp(10));
        b.setMinHeight(dp(48));
        return b;
    }

    private RippleDrawable rippleDrawable(int fillColor, int strokeColor, int radiusDp, int rippleColor) {
        return new RippleDrawable(ColorStateList.valueOf(rippleColor),
                chipDrawable(fillColor, strokeColor, radiusDp), null);
    }

    private GradientDrawable cardBackground(int fillColor, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fillColor);
        d.setCornerRadius(dp(22));
        d.setStroke(dp(1), strokeColor);
        return d;
    }

    private GradientDrawable tileBackground() {
        GradientDrawable d = new GradientDrawable();
        d.setColor(surface);
        d.setCornerRadius(dp(16));
        d.setStroke(dp(1), stroke);
        return d;
    }

    private GradientDrawable inputBackground() {
        GradientDrawable d = new GradientDrawable();
        d.setColor(surface);
        d.setCornerRadius(dp(16));
        d.setStroke(dp(1), stroke);
        return d;
    }

    private GradientDrawable chipDrawable(int fillColor, int strokeColor, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fillColor);
        d.setCornerRadius(dp(radiusDp));
        d.setStroke(dp(1), strokeColor);
        return d;
    }

    private LinearLayout.LayoutParams marginTop(int dpValue) {
        return marginTop(dpValue, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams marginTop(int dpValue, int heightPx) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, heightPx);
        p.topMargin = dp(dpValue);
        return p;
    }

    private LinearLayout.LayoutParams weightedParams(float weight, int leftMarginDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
        p.leftMargin = dp(leftMarginDp);
        return p;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
