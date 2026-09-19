package com.darren.batterylens;

import android.app.Activity;
import android.app.AlertDialog;
import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.ImageView;
import android.widget.ProgressBar;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private final DecimalFormat one = new DecimalFormat("0.0");
    private final DecimalFormat zero = new DecimalFormat("0");
    private final SimpleDateFormat shortDate = new SimpleDateFormat("MMM d, HH:mm", Locale.getDefault());

    private TextView healthValue;
    private TextView healthSource;
    private TextView confidenceValue;
    private TextView capacityValue;
    private TextView baselineSummary;
    private TextView autoStatus;
    private TextView autoChip;
    private TextView powerValue;
    private TextView tempValue;
    private TextView cyclesValue;
    private TextView timeValue;
    private TextView historyValue;
    private TextView rawValue;
    private EditText designCapacityInput;
    private Button manualButton;
    private Switch autoSwitch;
    private HealthChartView chart;

    private final int bg = Color.rgb(9, 20, 26);
    private final int surface = Color.rgb(13, 28, 35);
    private final int card = Color.rgb(19, 36, 44);
    private final int cardElevated = Color.rgb(23, 45, 53);
    private final int stroke = Color.rgb(43, 65, 73);
    private final int primary = Color.rgb(244, 247, 252);
    private final int secondary = Color.rgb(171, 191, 198);
    private final int accent = Color.rgb(109, 235, 209);
    private final int accentStrong = Color.rgb(32, 175, 112);
    private final int accentBlue = Color.rgb(103, 192, 255);
    private final int warning = Color.rgb(255, 196, 92);
    private final int danger = Color.rgb(255, 137, 112);

    private BatteryGaugeView gauge;
    private TextView chargeStatus, cycleExplanation, observedValue, monitorHelp;
    private ProgressBar progress;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean receiverRegistered;
    private boolean visible;
    private boolean updatingSwitch;
    private int selectedPage;
    private String pendingCsv;
    private final LinearLayout[] pages = new LinearLayout[3];
    private final Button[] tabs = new Button[3];
    private ScrollView contentScroll;
    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) {
            CalibrationStore.reconcileAutoState(c);
            refreshBattery();
        }
    };
    private final Runnable liveRefresh = new Runnable() {
        @Override public void run() {
            if (!visible) return;
            CalibrationStore.reconcileAutoState(MainActivity.this);
            refreshBattery();
            handler.postDelayed(this, 5_000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        else getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if (!BatteryMonitorService.running) CalibrationStore.breakCycleContinuity(this);
        seedDesignCapacityIfNeeded();
        CalibrationStore.migrateLegacyIfNeeded(this);
        if (savedInstanceState != null) {
            selectedPage = savedInstanceState.getInt("page", 0);
            pendingCsv = savedInstanceState.getString("csv");
        }
        buildUi();
        CalibrationStore.reconcileAutoState(this);
        refreshBattery();
    }

    @Override protected void onResume() {
        super.onResume();
        visible = true;
        if (CalibrationStore.isAutoEnabled(this) || CalibrationStore.hasActive(this)) BatteryMonitorService.start(this);
        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(batteryReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(batteryReceiver, filter);
        receiverRegistered = true;
        handler.post(liveRefresh);
    }

    @Override protected void onPause() {
        visible = false;
        handler.removeCallbacks(liveRefresh);
        if (receiverRegistered) { unregisterReceiver(batteryReceiver); receiverRegistered = false; }
        if (!BatteryMonitorService.running && !BatteryMonitorService.starting) {
            CalibrationStore.finishCheckpoint(this, "App left foreground without background monitoring");
            CalibrationStore.breakCycleContinuity(this);
        }
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("page", selectedPage);
        state.putString("csv", pendingCsv);
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
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                shell.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else shell.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        LinearLayout header = new LinearLayout(this);
        header.setPadding(dp(20), dp(12), dp(20), dp(12));
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView mark = new ImageView(this);
        mark.setImageResource(R.drawable.ic_launcher_foreground);
        mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        header.addView(mark, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout titles = new LinearLayout(this); titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(text("BatteryLens", 23, primary, Typeface.BOLD));
        titles.addView(text("Know your battery better", 12, secondary, Typeface.NORMAL));
        header.addView(titles, weightedParams(1, 8));
        autoChip = chip("Paused", accentBlue);
        header.addView(autoChip);
        shell.addView(header);

        contentScroll = new ScrollView(this);
        contentScroll.setFillViewport(true);
        LinearLayout pageStack = new LinearLayout(this); pageStack.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < pages.length; i++) {
            pages[i] = new LinearLayout(this); pages[i].setOrientation(LinearLayout.VERTICAL);
            pages[i].setPadding(dp(18), dp(6), dp(18), dp(24)); pageStack.addView(pages[i]);
        }
        contentScroll.addView(pageStack);
        shell.addView(contentScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        buildOverview(pages[0]); buildSessions(pages[1]); buildSettings(pages[2]);
        LinearLayout navigation = new LinearLayout(this);
        navigation.setPadding(dp(12), dp(8), dp(12), dp(8)); navigation.setBackgroundColor(surface);
        String[] labels = {"Overview", "Sessions", "Settings"};
        for (int i = 0; i < 3; i++) {
            final int index = i; tabs[i] = ghostButton(labels[i]);
            tabs[i].setOnClickListener(v -> showPage(index));
            navigation.addView(tabs[i], weightedParams(1, i == 0 ? 0 : 6));
        }
        shell.addView(navigation);
        setContentView(shell);
        showPage(selectedPage);
    }

    private void showPage(int index) {
        selectedPage = Math.max(0, Math.min(2, index));
        for (int i = 0; i < 3; i++) {
            pages[i].setVisibility(i == selectedPage ? View.VISIBLE : View.GONE);
            tabs[i].setSelected(i == selectedPage);
            tabs[i].setTextColor(i == selectedPage ? bg : secondary);
            tabs[i].setBackground(chipDrawable(i == selectedPage ? accent : surface, i == selectedPage ? accent : surface, 16));
        }
        contentScroll.post(() -> contentScroll.scrollTo(0, 0));
    }

    private void buildOverview(LinearLayout root) {
        LinearLayout live = card(true); root.addView(live);
        live.addView(text("RIGHT NOW", 11, accent, Typeface.BOLD));
        gauge = new BatteryGaugeView(this);
        live.addView(gauge, new LinearLayout.LayoutParams(-1, dp(208)));
        chargeStatus = text("Reading your battery…", 14, primary, Typeface.BOLD);
        chargeStatus.setGravity(Gravity.CENTER); live.addView(chargeStatus);
        LinearLayout[] liveTiles = addMetricPair(live, "Battery power", "Temperature");
        powerValue = (TextView) liveTiles[0].getChildAt(1); tempValue = (TextView) liveTiles[1].getChildAt(1);
        live.addView(text("Power is measured at the battery, not the wall charger. Updates every 5 seconds while visible.", 12, secondary, Typeface.NORMAL), marginTop(12));

        LinearLayout hero = section(root, "BATTERY HEALTH", "Capacity estimated from your charging sessions");
        healthValue = text("Learning", 38, accent, Typeface.BOLD); hero.addView(healthValue, marginTop(8));
        healthSource = text("Collect a monitored charge session to begin.", 14, secondary, Typeface.NORMAL);
        hero.addView(healthSource, marginTop(4));
        LinearLayout[] healthTiles = addMetricPair(hero, "Full capacity", "Repeatability");
        capacityValue = (TextView) healthTiles[0].getChildAt(1); confidenceValue = (TextView) healthTiles[1].getChildAt(1);
        baselineSummary = text("", 12, secondary, Typeface.NORMAL); hero.addView(baselineSummary, marginTop(12));

        LinearLayout monitor = section(root, "Session monitor", "Enable monitoring once, then charge normally. A persistent notification keeps monitoring visible and lets you stop it.");
        autoSwitch = new Switch(this); autoSwitch.setText("Automatic monitoring"); autoSwitch.setTextColor(primary);
        autoSwitch.setTextSize(16); autoSwitch.setMinHeight(dp(48));
        autoSwitch.setChecked(CalibrationStore.isAutoEnabled(this)); monitor.addView(autoSwitch, marginTop(10));
        autoStatus = text("", 14, primary, Typeface.NORMAL); monitor.addView(autoStatus, marginTop(10));
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(30); progress.setProgressTintList(android.content.res.ColorStateList.valueOf(accent));
        monitor.addView(progress, new LinearLayout.LayoutParams(-1, dp(12)));
        monitorHelp = text("", 12, secondary, Typeface.NORMAL); monitor.addView(monitorHelp, marginTop(8));
        manualButton = ghostButton("Start a manual session"); monitor.addView(manualButton, marginTop(12));
        manualButton.setOnClickListener(v -> toggleManualSession());
        autoSwitch.setOnCheckedChangeListener((v, enabled) -> {
            if (updatingSwitch) return;
            if (!enabled) CalibrationStore.finishCheckpoint(this, "Automatic monitoring disabled");
            CalibrationStore.setAutoEnabled(this, enabled);
            if (enabled) {
                requestNotifications();
                if (!BatteryMonitorService.start(this)) toast("Android blocked monitoring. Check app battery settings.");
            } else stopService(new Intent(this, BatteryMonitorService.class));
            refreshBattery();
        });

        LinearLayout cycles = section(root, "Battery cycles", "One full cycle represents about 100% of battery use across partial discharges.");
        LinearLayout[] cycleTiles = addMetricPair(cycles, "Lifetime • device", "Observed • this app");
        cyclesValue = (TextView) cycleTiles[0].getChildAt(1); observedValue = (TextView) cycleTiles[1].getChildAt(1);
        cycleExplanation = text("", 13, secondary, Typeface.NORMAL); cycles.addView(cycleExplanation, marginTop(12));
        LinearLayout eta = section(root, "Charging time", "Android’s estimate may be unavailable when charging is paused or limited by Battery Protection.");
        timeValue = text("—", 23, primary, Typeface.BOLD); eta.addView(timeValue, marginTop(10));
    }

    private void buildSessions(LinearLayout root) {
        root.addView(text("Your battery, over time", 25, primary, Typeface.BOLD));
        root.addView(text("A longer history gives context to each estimate.", 14, secondary, Typeface.NORMAL), marginTop(6));
        LinearLayout trend = section(root, "Capacity trend", "Individual eligible sessions, before median outlier filtering. Consistency is not a guarantee of absolute accuracy.");
        chart = new HealthChartView(this); trend.addView(chart, new LinearLayout.LayoutParams(-1, dp(180)));
        LinearLayout history = section(root, "Recent sessions", "Short and interrupted sessions stay here with their measurement status.");
        historyValue = text("No sessions yet", 14, primary, Typeface.NORMAL); historyValue.setLineSpacing(dp(3), 1.1f);
        history.addView(historyValue, marginTop(12));
        Button export = filledButton("Export all sessions as CSV"); root.addView(export, marginTop(14));
        export.setOnClickListener(v -> exportSessions());
        Button clear = ghostButton("Clear calibration history"); root.addView(clear, marginTop(10));
        clear.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("Clear calibration history?")
                .setMessage("This removes saved capacity estimates and cancels the current calibration session. Observed cycles are kept.")
                .setNegativeButton("Keep history", null).setPositiveButton("Clear", (dialog, which) -> {
                    CalibrationStore.clearHistory(this); refreshBattery();
                }).show());
    }

    private void buildSettings(LinearLayout root) {
        root.addView(text("Make it work for you", 25, primary, Typeface.BOLD));
        LinearLayout setup = section(root, "Samsung & background monitoring", "On your S24 FE, set BatteryLens battery usage to Unrestricted and remove it from Sleeping / Deep sleeping apps if needed. After a reboot or force-stop, open BatteryLens again. Android can still interrupt monitoring; gaps are never silently treated as continuous charging.");
        Button settings = ghostButton("Open Android app settings"); setup.addView(settings, marginTop(12));
        settings.setOnClickListener(v -> openAppSettings());
        Button notifications = ghostButton("Enable notifications"); setup.addView(notifications, marginTop(10));
        notifications.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) requestNotifications();
            else startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
        });
        LinearLayout alerts = section(root, "Charge care", "Optional target and 42°C temperature notifications while monitoring runs. BatteryLens cannot stop charging or change Samsung Battery Protection.");
        Switch alertSwitch = new Switch(this); alertSwitch.setText("Charge & temperature alerts");
        alertSwitch.setTextSize(15); alertSwitch.setTextColor(primary); alertSwitch.setMinHeight(dp(48));
        alertSwitch.setChecked(getSharedPreferences(CalibrationStore.PREFS, MODE_PRIVATE).getBoolean("alerts", false));
        alerts.addView(alertSwitch, marginTop(10));
        alertSwitch.setOnCheckedChangeListener((v, enabled) -> {
            getSharedPreferences(CalibrationStore.PREFS, MODE_PRIVATE).edit().putBoolean("alerts", enabled).apply();
            if (enabled) { requestNotifications(); toast("Alerts run while monitoring is enabled"); }
        });
        int target = getSharedPreferences(CalibrationStore.PREFS, MODE_PRIVATE).getInt("charge_target", 80);
        Button targetButton = ghostButton("Charge target: " + target + "%"); alerts.addView(targetButton, marginTop(10));
        targetButton.setOnClickListener(v -> {
            String[] options = {"70%", "80%", "85%", "90%", "100%"}; int[] values = {70, 80, 85, 90, 100};
            new AlertDialog.Builder(this).setTitle("Charge notification target").setItems(options, (d, index) -> {
                getSharedPreferences(CalibrationStore.PREFS, MODE_PRIVATE).edit().putInt("charge_target", values[index]).apply();
                targetButton.setText("Charge target: " + values[index] + "%");
            }).show();
        });
        LinearLayout design = section(root, "Rated capacity", "S24 FE uses 4565 mAh rated (4700 mAh typical). Use a documented baseline for your model; changing it affects displayed health, not measured capacity.");
        designCapacityInput = new EditText(this); designCapacityInput.setTextColor(primary); designCapacityInput.setHintTextColor(secondary);
        designCapacityInput.setTextSize(18); designCapacityInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        float stored = CalibrationStore.designCapacity(this); designCapacityInput.setText(stored > 0 ? String.valueOf(Math.round(stored)) : "");
        designCapacityInput.setHint("Rated capacity in mAh"); designCapacityInput.setContentDescription("Rated battery capacity in milliamp hours");
        designCapacityInput.setSingleLine(true); designCapacityInput.setBackground(inputBackground());
        designCapacityInput.setPadding(dp(14), dp(12), dp(14), dp(12)); design.addView(designCapacityInput, marginTop(12));
        Button save = filledButton("Save rated capacity"); design.addView(save, marginTop(10)); save.setOnClickListener(v -> saveDesignCapacity());
        LinearLayout diagnostics = section(root, "Device compatibility", "Some firmware exposes lifetime cycles; some restricts them. This checks the public Android reading and readable cycle-count files without root. A reported zero can be a firmware placeholder.");
        Button check = ghostButton("Check compatibility"); diagnostics.addView(check, marginTop(12));
        check.setOnClickListener(v -> {
            check.setEnabled(false); check.setText("Checking…");
            new Thread(() -> {
                CycleReader.scan();
                runOnUiThread(() -> { if (!isDestroyed()) { check.setEnabled(true); check.setText("Check compatibility"); refreshBattery(); } });
            }, "battery-compatibility").start();
        });
        rawValue = text("", 13, secondary, Typeface.NORMAL); rawValue.setTextIsSelectable(true);
        rawValue.setLineSpacing(dp(3), 1.1f); diagnostics.addView(rawValue, marginTop(12));
        Button copy = ghostButton("Copy device diagnostics"); diagnostics.addView(copy, marginTop(12)); copy.setOnClickListener(v -> copyDiagnostics());
        LinearLayout accuracy = section(root, "Getting useful estimates", "Charge normally across at least 30 percentage points; 20→80% works with Battery Protection. Keep temperatures moderate. There is no need to drain the battery to zero. Collect at least three long sessions before judging a trend.");
        accuracy.addView(text("Charge-counter estimates depend on the phone’s fuel gauge. This app measures and estimates; it does not recalibrate the battery hardware. Snapshot capacity is diagnostic only.", 13, secondary, Typeface.NORMAL), marginTop(10));
        TextView footer = text("BatteryLens 3.0 · Offline by design\nNo ads. No analytics. Your data stays on this phone.", 12, secondary, Typeface.NORMAL);
        footer.setGravity(Gravity.CENTER); root.addView(footer, marginTop(20));
    }

    private LinearLayout section(LinearLayout root, String title, String subtitle) {
        LinearLayout section = card(false); root.addView(section, marginTop(14));
        TextView heading = text(title, 17, primary, Typeface.BOLD); heading.setAccessibilityHeading(true); section.addView(heading);
        section.addView(text(subtitle, 13, secondary, Typeface.NORMAL), marginTop(6)); return section;
    }

    private LinearLayout[] addMetricPair(LinearLayout parent, String first, String second) {
        LinearLayout row = metricRow();
        boolean stacked = getResources().getConfiguration().fontScale > 1.25f || getResources().getConfiguration().screenWidthDp < 360;
        if (stacked) row.setOrientation(LinearLayout.VERTICAL);
        LinearLayout a = metricTile(first), b = metricTile(second);
        row.addView(a, stacked ? marginTop(0) : weightedParams(1, 0));
        row.addView(b, stacked ? marginTop(8) : weightedParams(1, 8));
        parent.addView(row, marginTop(14)); return new LinearLayout[]{a, b};
    }

    private void requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 42);
    }

    private void openAppSettings() {
        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
    }

    private void exportSessions() {
        List<CalibrationStore.Session> sessions = CalibrationStore.sessions(this);
        if (sessions.isEmpty()) { toast("No sessions to export yet"); return; }
        StringBuilder csv = new StringBuilder("start_time_utc_ms,end_time_utc_ms,start_percent,end_percent,capacity_mah,eligible,observations,min_temp_c,max_temp_c,source,reason\n");
        for (CalibrationStore.Session s : sessions) {
            csv.append(s.startTime).append(',').append(s.endTime).append(',').append(s.startSoc).append(',').append(s.endSoc).append(',')
                    .append(Double.isFinite(s.fccMah) ? s.fccMah : "").append(',').append(s.used).append(',').append(s.samples).append(',')
                    .append(Double.isFinite(s.minTemp) ? s.minTemp : "").append(',').append(Double.isFinite(s.maxTemp) ? s.maxTemp : "").append(',')
                    .append(csvCell(s.source)).append(',').append(csvCell(s.reason)).append('\n');
        }
        pendingCsv = csv.toString();
        startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("text/csv").putExtra(Intent.EXTRA_TITLE, "BatteryLens-sessions.csv"), 77);
    }

    private static String csvCell(String value) { return "\"" + (value == null ? "" : value.replace("\"", "\"\"")) + "\""; }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 77) return;
        final String csv = pendingCsv; pendingCsv = null;
        if (result != RESULT_OK || data == null || data.getData() == null || csv == null) return;
        final Uri uri = data.getData();
        new Thread(() -> {
            boolean success = false;
            try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
                if (output != null) { output.write(csv.getBytes(StandardCharsets.UTF_8)); success = true; }
            } catch (Exception ignored) { }
            final boolean saved = success;
            runOnUiThread(() -> toast(saved ? "Sessions exported" : "Could not write this file"));
        }, "battery-csv-export").start();
    }

    private void saveDesignCapacity() {
        try {
            float v = Float.parseFloat(designCapacityInput.getText().toString().trim().replace(',', '.'));
            if (!Float.isFinite(v) || v < 500 || v > 20000) throw new NumberFormatException();
            CalibrationStore.setDesignCapacity(this, v);
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
            CalibrationStore.finishSession(this);
            toast(CalibrationStore.lastNote(this));
        } else {
            boolean started = CalibrationStore.startSession(this, "manual");
            if (started) { requestNotifications(); BatteryMonitorService.start(this); }
            toast(started ? "Manual calibration started" : CalibrationStore.lastNote(this));
        }
        refreshBattery();
    }

    private void refreshBattery() {
        BatteryReadings r = BatteryReadings.read(this);
        float design = CalibrationStore.designCapacity(this);
        CalibrationStore.Estimate estimate = CalibrationStore.estimate(this);
        List<CalibrationStore.Session> sessions = CalibrationStore.sessions(this);

        baselineSummary.setText(design > 0 ? "Rated baseline: " + zero.format(design) + " mAh" : "Rated baseline: not set");
        updatingSwitch = true; autoSwitch.setChecked(CalibrationStore.isAutoEnabled(this)); updatingSwitch = false;
        autoChip.setText(BatteryMonitorService.running ? "Live" : "Paused");
        gauge.setLevel(r == null ? -1 : r.soc, r != null && r.isPlugged());
        observedValue.setText(String.format(Locale.getDefault(), "%.2f", CalibrationStore.observedCycles(this)));
        chargeStatus.setText(r == null ? "Battery unavailable" : statusName(r.status) + " · " + plugName(r.plugged));
        autoChip.setBackground(chipDrawable(CalibrationStore.isAutoEnabled(this) ? accentBlue : warning,
                CalibrationStore.isAutoEnabled(this) ? accentBlue : warning, 16));
        autoChip.setTextColor(CalibrationStore.isAutoEnabled(this) ? bg : Color.rgb(47, 34, 0));

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
            rawValue.setText("Battery data unavailable.");
            updateAutoStatus(null);
            updateHistory(sessions, design);
            chart.setData(sessions, design);
            return;
        }

        Double snapshotFcc = null;
        if (r.hasUsableCounter() && r.soc >= 10 && r.soc <= 100) {
            snapshotFcc = (r.chargeUah / 1000.0) / (r.soc / 100.0);
        }

        Double selectedHealth = null;
        Double selectedFcc = null;
        String source = "Collect a monitored charge of 30% or more to estimate capacity.";
        String confidence = "Not enough data";

        if (estimate.fccMah != null) {
            selectedFcc = estimate.fccMah;
            selectedHealth = design > 0 ? 100.0 * selectedFcc / design : null;
            source = "Median of " + estimate.usedCount + " eligible charge session" + (estimate.usedCount == 1 ? "" : "s")
                    + (design > 0 ? ". Fuel-gauge estimate, not a laboratory measurement." : ". Set rated capacity in Settings to show health.");
            confidence = estimate.confidence;
        }

        if (selectedHealth != null) {
            double displayHealth = selectedHealth;
            healthValue.setText(one.format(displayHealth) + "%");
            healthValue.setTextColor(colorForHealth(displayHealth));
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
                + (since > 0 ? " since " + shortDate.format(new Date(since)) : "")
                + ". They exclude unmonitored time and are not the phone’s lifetime total.");
        timeValue.setText(r.isPlugged() ? formatDuration(r.chargeTimeMs) : "—");

        updateAutoStatus(r);
        updateHistory(sessions, design);
        chart.setData(sessions, design);

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
        raw.append("Charge time left   ").append(formatDuration(r.chargeTimeMs)).append('\n');
        raw.append("Device SoH         No stable public API used\n");
        raw.append("Rated baseline     ").append(design > 0 ? zero.format(design) + " mAh" : "Not set").append('\n');
        raw.append("Calibrated FCC     ").append(estimate.fccMah != null ? zero.format(estimate.fccMah) + " mAh" : "Not ready").append('\n');
        raw.append("Snapshot FCC       ").append(snapshotFcc != null ? zero.format(snapshotFcc) + " mAh" : "Unavailable").append('\n');
        raw.append("Auto calibration   ").append(CalibrationStore.isAutoEnabled(this) ? "On" : "Off");
        raw.append("\nCycle source       ").append(r.cycleSource);
        raw.append("\nObserved cycles    ").append(CalibrationStore.observedCycles(this));
        raw.append("\nMonitor running    ").append(BatteryMonitorService.running);
        raw.append("\nNotifications      ").append(getSystemService(android.app.NotificationManager.class).areNotificationsEnabled());
        raw.append("\nPower saving       ").append(getSystemService(android.os.PowerManager.class).isPowerSaveMode());
        raw.append("\nBattery exemption  ").append(getSystemService(android.os.PowerManager.class).isIgnoringBatteryOptimizations(getPackageName()));
        raw.append("\n\nReadable cycle probes\n").append(CycleReader.cached().details);
        rawValue.setText(raw.toString());
    }

    private void copyDiagnostics() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            toast("Clipboard unavailable");
            return;
        }
        String text = "BatteryLens 3.0 diagnostics\n" + rawValue.getText()
                + "\nLast calibration note  " + CalibrationStore.lastNote(this);
        clipboard.setPrimaryClip(ClipData.newPlainText("BatteryLens diagnostics", text));
        toast("Diagnostics copied");
    }

    private void updateAutoStatus(BatteryReadings r) {
        double gain = r == null || !CalibrationStore.hasActive(this) ? 0 : r.soc - CalibrationStore.activeStartSoc(this);
        progress.setProgress((int) Math.max(0, Math.min(30, gain)));
        boolean notifications = getSystemService(android.app.NotificationManager.class).areNotificationsEnabled();
        monitorHelp.setText((BatteryMonitorService.running ? "Background monitor running. " : "Background monitor paused. Enable monitoring to follow future charges. ")
                + (notifications ? "" : "Notifications are disabled in Android. ")
                + (gain > 0 ? (gain < 30 ? Math.round(30 - gain) + "% more for the minimum session length." : "Minimum length reached. Continue charging normally.") : "Aim for 30%+ gained; 20→80% is a useful session."));
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
        if (sessions == null || sessions.isEmpty()) {
            historyValue.setText("No completed sessions yet. Your normal charging routine will populate this automatically.");
            return;
        }
        StringBuilder b = new StringBuilder();
        int start = Math.max(0, sessions.size() - 5);
        for (int i = sessions.size() - 1; i >= start; i--) {
            CalibrationStore.Session s = sessions.get(i);
            if (b.length() > 0) b.append("\n\n");
            b.append(shortDate.format(new Date(s.endTime > 0 ? s.endTime : s.startTime)))
                    .append("  •  ").append(zero.format(s.startSoc)).append("→").append(zero.format(s.endSoc)).append("%")
                    .append("  •  ").append(Double.isFinite(s.fccMah) ? zero.format(s.fccMah) + " mAh" : "No capacity estimate");
            if (design > 0 && Double.isFinite(s.fccMah)) b.append(" (").append(one.format(100.0 * s.fccMah / design)).append("%)");
            b.append("\n")
                    .append(s.quality)
                    .append(" • ")
                    .append(s.used ? "eligible for median" : "history only")
                    .append(" • ")
                    .append("auto".equals(s.source) ? "automatic" : s.source)
                    .append(" • ").append(s.samples).append(" observations")
                    .append("\n").append(s.reason);
        }
        historyValue.setText(b.toString());
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

    private LinearLayout metricRow() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    private LinearLayout card(boolean elevated) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(16), dp(16), dp(16), dp(16));
        l.setBackground(cardBackground(elevated ? cardElevated : card));
        l.setElevation(dp(elevated ? 3 : 1));
        return l;
    }

    private LinearLayout metricTile(String label) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(12), dp(12), dp(12), dp(12));
        l.setBackground(tileBackground());
        TextView name = text(label, 12, secondary, Typeface.NORMAL);
        TextView value = text("—", 18, primary, Typeface.BOLD);
        value.setLineSpacing(0f, 1.1f);
        l.addView(name);
        l.addView(value, marginTop(4));
        return l;
    }

    private TextView chip(String value, int fill) {
        TextView t = text(value, 12, bg, Typeface.BOLD);
        t.setPadding(dp(10), dp(6), dp(10), dp(6));
        t.setBackground(chipDrawable(fill, fill, 16));
        return t;
    }

    private TextView text(String value, float sp, int color, int style) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create("sans-serif", style));
        return t;
    }

    private Button filledButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(bg);
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackground(chipDrawable(accent, accent, 14));
        b.setPadding(dp(12), dp(10), dp(12), dp(10));
        b.setMinHeight(dp(48));
        return b;
    }

    private Button ghostButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(primary);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackground(chipDrawable(surface, stroke, 14));
        b.setPadding(dp(12), dp(10), dp(12), dp(10));
        b.setMinHeight(dp(48));
        return b;
    }

    private GradientDrawable cardBackground(int fillColor) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fillColor);
        d.setCornerRadius(dp(24));
        d.setStroke(dp(1), stroke);
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
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
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
