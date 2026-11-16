package com.darren.batterylens;

import android.os.BatteryManager;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Pure immutable snapshot and sizing rules for home-screen BatteryLens widgets. */
final class WidgetSnapshot {
    enum WidgetSize {
        COMPACT_2X1,
        SQUARE_2X2,
        WIDE_4X1,
        DASHBOARD_4X2
    }

    enum Tone {
        MINT,
        BLUE,
        AMBER,
        CORAL
    }

    final String levelText;
    final int progress;
    final String shortBadge;
    final String fullBadge;
    final Tone stateTone;
    final Tone progressTone;
    final String powerText;
    final String currentText;
    final String tempText;
    final String healthTileText;
    final String compactMeta;
    final String widePrimary;
    final String wideSecondary;
    final String squareFooter;
    final String footerText;
    final String monitorBadgeText;
    final String monitorDotText;
    final Tone monitorTone;
    final String contentDescription;
    final String signature;

    private WidgetSnapshot(
            String levelText, int progress, String shortBadge, String fullBadge,
            Tone stateTone, Tone progressTone, String powerText, String currentText,
            String tempText, String healthTileText, String compactMeta,
            String widePrimary, String wideSecondary, String squareFooter, String footerText,
            String monitorBadgeText, String monitorDotText, Tone monitorTone,
            String contentDescription) {
        this.levelText = levelText;
        this.progress = progress;
        this.shortBadge = shortBadge;
        this.fullBadge = fullBadge;
        this.stateTone = stateTone;
        this.progressTone = progressTone;
        this.powerText = powerText;
        this.currentText = currentText;
        this.tempText = tempText;
        this.healthTileText = healthTileText;
        this.compactMeta = compactMeta;
        this.widePrimary = widePrimary;
        this.wideSecondary = wideSecondary;
        this.squareFooter = squareFooter;
        this.footerText = footerText;
        this.monitorBadgeText = monitorBadgeText;
        this.monitorDotText = monitorDotText;
        this.monitorTone = monitorTone;
        this.contentDescription = contentDescription;
        this.signature = levelText + "|" + progress + "|" + fullBadge + "|" + stateTone + "|"
                + progressTone + "|" + powerText + "|" + currentText + "|" + tempText + "|"
                + healthTileText + "|" + footerText + "|" + monitorBadgeText;
    }

    /** Picks the best widget layout bucket when the user drops or resizes a widget. */
    static WidgetSize resolveSize(WidgetSize fallback, int minWidthDp, int minHeightDp) {
        if (minWidthDp <= 0 || minHeightDp <= 0) {
            return fallback != null ? fallback : WidgetSize.SQUARE_2X2;
        }
        if (minWidthDp >= 220) {
            return minHeightDp >= 95 ? WidgetSize.DASHBOARD_4X2 : WidgetSize.WIDE_4X1;
        }
        return minHeightDp >= 95 ? WidgetSize.SQUARE_2X2 : WidgetSize.COMPACT_2X1;
    }

    static WidgetSnapshot build(
            double soc, int status, int plugged,
            double currentNowMa, double powerW, double tempC,
            boolean monitoringRunning, boolean autoEnabled,
            boolean activeSession, double activeStartSoc,
            Double fccMah, int usedCount, double relativeMad,
            float designMah, String confidence) {
        DecimalFormat one = new DecimalFormat("0.0", DecimalFormatSymbols.getInstance(Locale.US));
        DecimalFormat integer = new DecimalFormat("#,##0", DecimalFormatSymbols.getInstance(Locale.US));

        boolean validSoc = MeasurementRules.validSoc(soc);
        boolean isPlugged = plugged != 0;
        long roundedSoc = validSoc ? Math.max(0L, Math.min(100L, Math.round(soc))) : 0L;
        String levelText = validSoc ? roundedSoc + "%" : "—%";
        int progress = validSoc ? (int) roundedSoc : 0;

        String shortBadge;
        String fullBadge;
        Tone stateTone;
        if (!validSoc) {
            shortBadge = "NO DATA";
            fullBadge = "UNAVAILABLE";
            stateTone = Tone.AMBER;
        } else if (isPlugged) {
            String plug = plugLabel(plugged);
            if (status == BatteryManager.BATTERY_STATUS_FULL) {
                shortBadge = "FULL";
                fullBadge = plug.isEmpty() ? "FULL" : "FULL · " + plug;
                stateTone = Tone.BLUE;
            } else if (status == BatteryManager.BATTERY_STATUS_NOT_CHARGING) {
                shortBadge = "PAUSED";
                fullBadge = "PAUSED AT LIMIT";
                stateTone = Tone.BLUE;
            } else {
                shortBadge = "CHARGING";
                fullBadge = plug.isEmpty() ? "CHARGING" : "CHARGING · " + plug;
                stateTone = Tone.MINT;
            }
        } else {
            shortBadge = roundedSoc <= 15 ? "LOW" : "ON BATTERY";
            fullBadge = roundedSoc <= 15 ? "LOW BATTERY" : "ON BATTERY";
            stateTone = roundedSoc <= 12 ? Tone.CORAL : (roundedSoc <= 22 ? Tone.AMBER : Tone.MINT);
        }

        Tone progressTone = stateTone;
        if (Double.isFinite(tempC) && tempC >= 42.0 && progressTone == Tone.MINT) {
            progressTone = Tone.AMBER;
        }

        String powerText = Double.isFinite(powerW)
                ? (powerW >= 100.0 ? Math.round(powerW) + " W" : one.format(powerW) + " W")
                : "—";
        String currentText;
        if (Double.isFinite(currentNowMa)) {
            long ma = Math.round(currentNowMa);
            currentText = (ma > 0 ? "+" : "") + ma + " mA";
        } else {
            currentText = "—";
        }
        String tempText = Double.isFinite(tempC) ? one.format(tempC) + "°C" : "—";

        boolean healthReady = fccMah != null && Double.isFinite(fccMah)
                && usedCount >= 3 && Double.isFinite(relativeMad) && relativeMad <= 0.10;
        String healthTileText;
        Double healthPct = null;
        if (healthReady && designMah > 0) {
            healthPct = 100.0 * fccMah / designMah;
            healthTileText = Math.round(healthPct) + "%";
        } else if (fccMah != null && Double.isFinite(fccMah)) {
            healthTileText = integer.format(Math.round(fccMah)) + " mAh";
        } else {
            healthTileText = "Learning";
        }

        String monitorBadgeText = monitoringRunning ? "● Live" : (autoEnabled ? "○ Ready" : "○ Off");
        String monitorDotText = monitoringRunning ? "●" : "○";
        Tone monitorTone = monitoringRunning ? Tone.MINT : Tone.AMBER;

        List<String> compactParts = new ArrayList<>(3);
        if (!"—".equals(tempText)) compactParts.add(tempText);
        if (!"—".equals(powerText)) compactParts.add(powerText);
        if (healthPct != null) compactParts.add("Health " + Math.round(healthPct) + "%");
        else if (fccMah != null && Double.isFinite(fccMah)) compactParts.add(integer.format(Math.round(fccMah)) + " mAh");
        else if (!"—".equals(currentText)) compactParts.add(currentText);
        String compactMeta = compactParts.isEmpty()
                ? (monitoringRunning ? "Monitoring live" : "Tap to open BatteryLens")
                : joinDot(compactParts);

        List<String> widePrimParts = new ArrayList<>(2);
        if (!"—".equals(powerText)) widePrimParts.add(powerText);
        if (!"—".equals(tempText)) widePrimParts.add(tempText);
        else if (!"—".equals(currentText)) widePrimParts.add(currentText);
        String widePrimary = widePrimParts.isEmpty() ? fullBadge : joinDot(widePrimParts);

        String footerText;
        String squareFooter;
        String wideSecondary;
        if (activeSession && validSoc && MeasurementRules.validSoc(activeStartSoc)) {
            long start = Math.round(activeStartSoc);
            long delta = Math.max(0L, roundedSoc - start);
            footerText = "Recording · " + start + "% → " + roundedSoc + "% (+" + delta + "%)";
            squareFooter = "Recording · " + start + "%→" + roundedSoc + "%";
            wideSecondary = squareFooter;
        } else if (healthPct != null) {
            String conf = confidence != null && !confidence.isEmpty() ? confidence : "Checked";
            footerText = "Health " + one.format(healthPct) + "% · " + integer.format(Math.round(fccMah)) + " mAh · " + conf;
            squareFooter = "Health " + Math.round(healthPct) + "% · " + integer.format(Math.round(fccMah)) + " mAh";
            wideSecondary = squareFooter;
        } else if (fccMah != null && Double.isFinite(fccMah)) {
            footerText = "Preliminary: " + integer.format(Math.round(fccMah)) + " mAh (" + usedCount + "/3 checks)";
            squareFooter = "Est. " + integer.format(Math.round(fccMah)) + " mAh (" + usedCount + "/3)";
            wideSecondary = squareFooter;
        } else if (monitoringRunning) {
            footerText = "Monitor live · waiting for next charge";
            squareFooter = "Live · Learning health";
            wideSecondary = "Live · Learning health";
        } else if (autoEnabled) {
            footerText = "Auto monitor ready · tap to open";
            squareFooter = "Auto ready · Learning";
            wideSecondary = "Auto ready · Learning";
        } else {
            footerText = "Monitor paused · tap to open";
            squareFooter = "Monitor paused";
            wideSecondary = "Monitor off";
        }

        String a11y = "BatteryLens " + levelText + ", " + fullBadge + ", " + compactMeta;
        return new WidgetSnapshot(
                levelText, progress, shortBadge, fullBadge, stateTone, progressTone,
                powerText, currentText, tempText, healthTileText, compactMeta,
                widePrimary, wideSecondary, squareFooter, footerText, monitorBadgeText,
                monitorDotText, monitorTone, a11y);
    }

    private static String plugLabel(int plugged) {
        switch (plugged) {
            case BatteryManager.BATTERY_PLUGGED_AC: return "AC";
            case BatteryManager.BATTERY_PLUGGED_USB: return "USB";
            case BatteryManager.BATTERY_PLUGGED_WIRELESS: return "WIRELESS";
            default: return "";
        }
    }

    private static String joinDot(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append(" · ");
            sb.append(parts.get(i));
        }
        return sb.toString();
    }
}
