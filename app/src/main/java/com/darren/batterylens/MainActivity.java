package com.darren.batterylens;

import android.app.Activity;
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

    private final int bg = Color.rgb(12, 15, 20);
    private final int surface = Color.rgb(18, 22, 29);
    private final int card = Color.rgb(24, 29, 39);
    private final int cardElevated = Color.rgb(29, 35, 47);
    private final int stroke = Color.rgb(44, 53, 68);
    private final int primary = Color.rgb(244, 247, 252);
    private final int secondary = Color.rgb(163, 173, 188);
    private final int accent = Color.rgb(120, 228, 162);
    private final int accentStrong = Color.rgb(32, 175, 112);
    private final int accentBlue = Color.rgb(103, 192, 255);
    private final int warning = Color.rgb(255, 196, 92);
    private final int danger = Color.rgb(255, 137, 112);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        seedDesignCapacityIfNeeded();
        CalibrationStore.migrateLegacyIfNeeded(this);
        buildUi();
        CalibrationStore.reconcileAutoState(this);
        refreshBattery();
    }

    @Override
    protected void onResume() {
        super.onResume();
        CalibrationStore.reconcileAutoState(this);
        refreshBattery();
    }

    private void seedDesignCapacityIfNeeded() {
        if (CalibrationStore.designCapacity(this) > 0) return;
        String model = Build.MODEL == null ? "" : Build.MODEL.toUpperCase(Locale.US);
        if (model.startsWith("SM-S721")) CalibrationStore.setDesignCapacity(this, 4565f);
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(bg);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        final int side = dp(18), topBase = dp(18), bottomBase = dp(28);
        root.setPadding(side, topBase, side, bottomBase);
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            int topInset;
            int bottomInset;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                topInset = bars.top;
                bottomInset = bars.bottom;
            } else {
                topInset = insets.getSystemWindowInsetTop();
                bottomInset = insets.getSystemWindowInsetBottom();
            }
            root.setPadding(side, topBase + topInset, side, bottomBase + bottomInset);
            return insets;
        });

        LinearLayout headerCard = card(true);
        root.addView(headerCard);
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        headerCard.addView(titleRow);

        TextView brandMark = text("BL", 14, primary, Typeface.BOLD);
        brandMark.setGravity(Gravity.CENTER);
        brandMark.setBackground(chipDrawable(accentStrong, accentStrong, 20));
        titleRow.addView(brandMark, new LinearLayout.LayoutParams(dp(40), dp(40)));

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams titleColParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleColParams.leftMargin = dp(12);
        titleRow.addView(titleCol, titleColParams);
        titleCol.addView(text("BatteryLens", 24, primary, Typeface.BOLD));
        titleCol.addView(text("Auto-calibrated battery health that stays honest about confidence", 13, secondary, Typeface.NORMAL), marginTop(2));

        autoChip = chip("Auto: on", accentBlue);
        titleRow.addView(autoChip);

        LinearLayout hero = card(true);
        root.addView(hero, marginTop(14));
        hero.addView(text("ESTIMATED BATTERY HEALTH", 12, secondary, Typeface.BOLD));
        healthValue = text("—", 46, accent, Typeface.BOLD);
        hero.addView(healthValue, marginTop(6));
        healthSource = text("Waiting for battery reading", 13, secondary, Typeface.NORMAL);
        healthSource.setLineSpacing(0f, 1.15f);
        hero.addView(healthSource, marginTop(2));

        LinearLayout heroMetrics = new LinearLayout(this);
        heroMetrics.setOrientation(LinearLayout.HORIZONTAL);
        hero.addView(heroMetrics, marginTop(16));
        LinearLayout capacityTile = metricTile("Estimated full capacity");
        capacityValue = (TextView) capacityTile.getChildAt(1);
        LinearLayout confidenceTile = metricTile("Confidence");
        confidenceValue = (TextView) confidenceTile.getChildAt(1);
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        heroMetrics.addView(capacityTile, half);
        LinearLayout.LayoutParams halfRight = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        halfRight.leftMargin = dp(10);
        heroMetrics.addView(confidenceTile, halfRight);

        baselineSummary = text("Rated baseline: —", 12, secondary, Typeface.NORMAL);
        hero.addView(baselineSummary, marginTop(10));

        LinearLayout autoCard = card(false);
        root.addView(autoCard, marginTop(12));
        autoCard.addView(text("Calibration mode", 16, primary, Typeface.BOLD));
        autoCard.addView(text("Charge sessions start automatically when power is connected and finish when you unplug. No permanent background service and no constant polling.", 13, secondary, Typeface.NORMAL), marginTop(4));

        autoSwitch = new Switch(this);
        autoSwitch.setText("Automatic calibration");
        autoSwitch.setTextColor(primary);
        autoSwitch.setTextSize(16);
        autoSwitch.setTypeface(Typeface.DEFAULT_BOLD);
        autoSwitch.setChecked(CalibrationStore.isAutoEnabled(this));
        autoCard.addView(autoSwitch, marginTop(12));

        autoStatus = text("Waiting for next charge session", 13, secondary, Typeface.NORMAL);
        autoStatus.setLineSpacing(0f, 1.2f);
        autoCard.addView(autoStatus, marginTop(8));

        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        autoCard.addView(actionRow, marginTop(12));
        manualButton = ghostButton("Start manual session");
        LinearLayout.LayoutParams actionHalf = new LinearLayout.LayoutParams(0, dp(46), 1f);
        actionRow.addView(manualButton, actionHalf);
        Button clearHistory = ghostButton("Clear history");
        LinearLayout.LayoutParams actionHalf2 = new LinearLayout.LayoutParams(0, dp(46), 1f);
        actionHalf2.leftMargin = dp(10);
        actionRow.addView(clearHistory, actionHalf2);

        manualButton.setOnClickListener(v -> toggleManualSession());
        clearHistory.setOnClickListener(v -> {
            CalibrationStore.clearHistory(this);
            toast("Calibration history cleared");
            refreshBattery();
        });

        autoSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            CalibrationStore.setAutoEnabled(this, isChecked);
            if (isChecked) {
                CalibrationStore.reconcileAutoState(this);
                toast("Automatic calibration enabled");
            } else {
                toast("Automatic calibration disabled");
            }
            refreshBattery();
        });

        LinearLayout insightCard = card(false);
        root.addView(insightCard, marginTop(12));
        insightCard.addView(text("Charging insights", 16, primary, Typeface.BOLD));
        insightCard.addView(text("These are device-reported battery-side values, useful for checking charging behavior and heat.", 13, secondary, Typeface.NORMAL), marginTop(4));

        LinearLayout row1 = metricRow();
        LinearLayout m1 = metricTile("Battery-side power");
        powerValue = (TextView) m1.getChildAt(1);
        LinearLayout m2 = metricTile("Temperature");
        tempValue = (TextView) m2.getChildAt(1);
        row1.addView(m1, weightedParams(1f, 0));
        row1.addView(m2, weightedParams(1f, 10));
        insightCard.addView(row1, marginTop(12));

        LinearLayout row2 = metricRow();
        LinearLayout m3 = metricTile("Cycle count");
        cyclesValue = (TextView) m3.getChildAt(1);
        LinearLayout m4 = metricTile("Time to full");
        timeValue = (TextView) m4.getChildAt(1);
        row2.addView(m3, weightedParams(1f, 0));
        row2.addView(m4, weightedParams(1f, 10));
        insightCard.addView(row2, marginTop(10));

        insightCard.addView(text("*Power = reported battery current × voltage. It is not the wall-charger wattage.", 11, secondary, Typeface.NORMAL), marginTop(10));

        LinearLayout trendCard = card(false);
        root.addView(trendCard, marginTop(12));
        trendCard.addView(text("Health trend", 16, primary, Typeface.BOLD));
        trendCard.addView(text("The chart uses accepted 30%+ charging sessions. Clear outliers are filtered before calculating the median estimate.", 13, secondary, Typeface.NORMAL), marginTop(4));
        chart = new HealthChartView(this);
        trendCard.addView(chart, marginTop(10));
        historyValue = text("No sessions yet", 12, primary, Typeface.NORMAL);
        historyValue.setLineSpacing(0f, 1.24f);
        trendCard.addView(historyValue, marginTop(8));

        LinearLayout designCard = card(false);
        root.addView(designCard, marginTop(12));
        designCard.addView(text("Rated capacity baseline", 16, primary, Typeface.BOLD));
        designCard.addView(text("Battery health is estimated as measured full capacity divided by rated capacity. For Galaxy S24 FE, 4565 mAh rated is prefilled.", 13, secondary, Typeface.NORMAL), marginTop(4));

        LinearLayout inputRow = new LinearLayout(this);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        designCard.addView(inputRow, marginTop(12));

        designCapacityInput = new EditText(this);
        designCapacityInput.setTextColor(primary);
        designCapacityInput.setHintTextColor(secondary);
        designCapacityInput.setTextSize(18);
        designCapacityInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        float stored = CalibrationStore.designCapacity(this);
        designCapacityInput.setText(stored > 0 ? zero.format(stored) : "");
        designCapacityInput.setHint("e.g. 4565");
        designCapacityInput.setSingleLine(true);
        designCapacityInput.setBackground(inputBackground());
        designCapacityInput.setPadding(dp(14), dp(10), dp(14), dp(10));
        inputRow.addView(designCapacityInput, new LinearLayout.LayoutParams(0, dp(50), 1f));

        Button saveDesign = filledButton("Save");
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(dp(90), dp(50));
        saveParams.leftMargin = dp(10);
        inputRow.addView(saveDesign, saveParams);
        saveDesign.setOnClickListener(v -> saveDesignCapacity());

        LinearLayout liveCard = card(false);
        root.addView(liveCard, marginTop(12));
        LinearLayout liveHeader = new LinearLayout(this);
        liveHeader.setOrientation(LinearLayout.HORIZONTAL);
        liveHeader.setGravity(Gravity.CENTER_VERTICAL);
        liveCard.addView(liveHeader);
        liveHeader.addView(text("Live battery data", 16, primary, Typeface.BOLD), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button refresh = ghostButton("Refresh");
        liveHeader.addView(refresh, new LinearLayout.LayoutParams(dp(92), dp(44)));
        refresh.setOnClickListener(v -> {
            CalibrationStore.reconcileAutoState(this);
            refreshBattery();
        });

        rawValue = text("—", 13, primary, Typeface.NORMAL);
        rawValue.setTypeface(Typeface.MONOSPACE);
        rawValue.setLineSpacing(0f, 1.20f);
        rawValue.setBackground(inputBackground());
        rawValue.setPadding(dp(14), dp(14), dp(14), dp(14));
        liveCard.addView(rawValue, marginTop(12));

        Button copyDiagnostics = ghostButton("Copy diagnostics");
        liveCard.addView(copyDiagnostics, marginTop(10));
        copyDiagnostics.setOnClickListener(v -> copyDiagnostics());

        LinearLayout noteCard = card(false);
        root.addView(noteCard, marginTop(12));
        noteCard.addView(text("Accuracy rules", 16, primary, Typeface.BOLD));
        noteCard.addView(text(
                "• Android 16 device State of Health is preferred when exposed by the phone.\n" +
                "• Otherwise BatteryLens uses the median of accepted charging sessions.\n" +
                "• 30%+ gained is used for estimation; 45%+ is better; around 20→80% is excellent.\n" +
                "• Sessions below 20% gained are ignored.\n" +
                "• A one-time snapshot is shown only as a low-confidence fallback.\n\n" +
                "Fuel-gauge behavior differs by manufacturer, so repeated sessions are more trustworthy than any single reading.",
                13, secondary, Typeface.NORMAL), marginTop(8));

        TextView footer = text("No internet permission • No ads • Calibration data stays on this phone", 12, secondary, Typeface.NORMAL);
        footer.setGravity(Gravity.CENTER);
        root.addView(footer, marginTop(18));

        setContentView(scroll);
    }

    private void saveDesignCapacity() {
        try {
            float v = Float.parseFloat(designCapacityInput.getText().toString().trim());
            if (v < 500 || v > 20000) throw new NumberFormatException();
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
        autoChip.setText(CalibrationStore.isAutoEnabled(this) ? "Auto: on" : "Auto: off");
        autoChip.setBackground(chipDrawable(CalibrationStore.isAutoEnabled(this) ? accentBlue : warning,
                CalibrationStore.isAutoEnabled(this) ? accentBlue : warning, 16));
        autoChip.setTextColor(CalibrationStore.isAutoEnabled(this) ? bg : Color.rgb(47, 34, 0));

        if (r == null) {
            healthValue.setText("—");
            healthValue.setTextColor(accent);
            healthSource.setText("Battery data unavailable on this device state.");
            confidenceValue.setText("—");
            capacityValue.setText("—");
            powerValue.setText("—");
            tempValue.setText("—");
            cyclesValue.setText("—");
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
        String source = "Need rated capacity and usable battery data";
        String confidence = "Low";

        if (r.hasValidDeviceSoh()) {
            selectedHealth = (double) r.deviceSoh;
            selectedFcc = design > 0 ? design * selectedHealth / 100.0 : null;
            source = "Android 16 device-reported State of Health";
            confidence = "High (device)";
        } else if (design > 0 && estimate.fccMah != null) {
            selectedFcc = estimate.fccMah;
            selectedHealth = 100.0 * selectedFcc / design;
            source = "Filtered median of " + estimate.usedCount + " accepted charge session" + (estimate.usedCount == 1 ? "" : "s");
            confidence = estimate.confidence;
        } else if (design > 0 && snapshotFcc != null) {
            selectedFcc = snapshotFcc;
            selectedHealth = 100.0 * snapshotFcc / design;
            source = "Low-confidence snapshot — automatic calibration will replace this";
            confidence = "Low";
        }

        if (selectedHealth != null) {
            double displayHealth = Math.max(0, Math.min(150, selectedHealth));
            healthValue.setText(one.format(displayHealth) + "%");
            healthValue.setTextColor(colorForHealth(displayHealth));
        } else {
            healthValue.setText("—");
            healthValue.setTextColor(accent);
        }
        healthSource.setText(source);
        confidenceValue.setText(confidence);
        capacityValue.setText(selectedFcc == null ? "—" : zero.format(selectedFcc) + " mAh");

        double powerW = r.batterySidePowerW();
        powerValue.setText(r.isPlugged() && !Double.isNaN(powerW) ? one.format(powerW) + " W" : "—");
        double tempC = r.temperatureC();
        tempValue.setText(Double.isNaN(tempC) ? "—" : one.format(tempC) + "°C · " + thermalLabel(tempC));
        cyclesValue.setText(r.cycles >= 0 ? Integer.toString(r.cycles) : "Unsupported");
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
        raw.append("Device SoH         ").append(r.hasValidDeviceSoh() ? r.deviceSoh + "%" : "Not exposed").append('\n');
        raw.append("Rated baseline     ").append(design > 0 ? zero.format(design) + " mAh" : "Not set").append('\n');
        raw.append("Calibrated FCC     ").append(estimate.fccMah != null ? zero.format(estimate.fccMah) + " mAh" : "Not ready").append('\n');
        raw.append("Snapshot FCC       ").append(snapshotFcc != null ? zero.format(snapshotFcc) + " mAh" : "Unavailable").append('\n');
        raw.append("Auto calibration   ").append(CalibrationStore.isAutoEnabled(this) ? "On" : "Off");
        rawValue.setText(raw.toString());
    }

    private void copyDiagnostics() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            toast("Clipboard unavailable");
            return;
        }
        String text = "BatteryLens 2.1 diagnostics\n" + rawValue.getText()
                + "\nLast calibration note  " + CalibrationStore.lastNote(this);
        clipboard.setPrimaryClip(ClipData.newPlainText("BatteryLens diagnostics", text));
        toast("Diagnostics copied");
    }

    private void updateAutoStatus(BatteryReadings r) {
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
                manualButton.setText("Automatic session running");
                manualButton.setAlpha(0.7f);
            }
        } else {
            String prefix = CalibrationStore.isAutoEnabled(this) ? "Ready for next plug-in" : "Automatic calibration is off";
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
                    .append("  •  ").append(zero.format(s.fccMah)).append(" mAh");
            if (design > 0) b.append(" (").append(one.format(100.0 * s.fccMah / design)).append("%)");
            b.append("\n")
                    .append(s.quality)
                    .append(" • ")
                    .append(s.used ? "used in estimate" : "history only")
                    .append(" • ")
                    .append("auto".equals(s.source) ? "automatic" : s.source);
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
            case BatteryManager.BATTERY_STATUS_NOT_CHARGING: return "Plugged, not charging";
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
        b.setPadding(dp(12), 0, dp(12), 0);
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
        b.setPadding(dp(12), 0, dp(12), 0);
        return b;
    }

    private GradientDrawable cardBackground(int fillColor) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fillColor);
        d.setCornerRadius(dp(20));
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
