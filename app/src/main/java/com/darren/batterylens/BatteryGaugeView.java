package com.darren.batterylens;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.BatteryManager;
import android.view.View;

final class BatteryGaugeView extends View {
    private static final int TRACK_COLOR = Color.rgb(31, 52, 60);
    private static final int TICK_COLOR = Color.rgb(66, 94, 104);
    private static final int PILL_FILL = Color.rgb(16, 33, 41);
    private static final int PILL_STROKE = Color.rgb(44, 70, 80);

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint valuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arc = new RectF();
    private final RectF statusPill = new RectF();
    private final float[] tickPts = new float[20];
    private final float density;

    private double level = -1;
    private boolean plugged;
    private int status = BatteryManager.BATTERY_STATUS_UNKNOWN;

    BatteryGaugeView(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        tickPaint.setStyle(Paint.Style.STROKE);
        tickPaint.setColor(TICK_COLOR);
        tickPaint.setStrokeCap(Paint.Cap.ROUND);
        tickPaint.setStrokeWidth(Math.max(1.5f, 1.5f * density));

        valuePaint.setStyle(Paint.Style.FILL);
        valuePaint.setTextAlign(Paint.Align.CENTER);
        valuePaint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        valuePaint.setColor(Color.rgb(244, 247, 252));
        valuePaint.setFontFeatureSettings("tnum");

        badgePaint.setStyle(Paint.Style.FILL);
        badgePaint.setTextAlign(Paint.Align.CENTER);
        badgePaint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        badgePaint.setLetterSpacing(0.08f);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    void setLevel(double level, boolean plugged) {
        setLevel(level, plugged, plugged ? BatteryManager.BATTERY_STATUS_CHARGING : BatteryManager.BATTERY_STATUS_DISCHARGING);
    }

    void setLevel(double level, boolean plugged, int status) {
        if (this.level == level && this.plugged == plugged && this.status == status) return;
        this.level = level;
        this.plugged = plugged;
        this.status = status;
        String stateText = badgeText();
        setContentDescription(MeasurementRules.validSoc(level)
                ? "Battery " + Math.round(level) + " percent, " + stateText.toLowerCase(java.util.Locale.US)
                : "Battery level unavailable");
        invalidate();
    }

    private int ringColor() {
        if (!MeasurementRules.validSoc(level)) return Color.rgb(109, 235, 209);
        if (status == BatteryManager.BATTERY_STATUS_FULL || status == BatteryManager.BATTERY_STATUS_NOT_CHARGING) {
            return Color.rgb(103, 192, 255);
        }
        if (plugged) return Color.rgb(109, 235, 209);
        if (level <= 12) return Color.rgb(255, 137, 112);
        if (level <= 22) return Color.rgb(255, 196, 92);
        return Color.rgb(109, 235, 209);
    }

    private String badgeText() {
        if (!MeasurementRules.validSoc(level)) return "UNAVAILABLE";
        if (plugged) {
            if (status == BatteryManager.BATTERY_STATUS_FULL) return "FULL";
            if (status == BatteryManager.BATTERY_STATUS_NOT_CHARGING) return "PAUSED AT LIMIT";
            return "CHARGING";
        }
        return "ON BATTERY";
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float size = Math.min(w, h);
        float cx = w / 2f, cy = h / 2f - size * 0.05f, radius = size * 0.35f;
        arc.set(cx - radius, cy - radius, cx + radius, cy + radius);
        float innerR = radius - size * 0.075f;
        float outerR = radius - size * 0.048f;
        for (int i = 0; i < 5; i++) {
            double rad = Math.toRadians(135.0 + i * 67.5);
            float cos = (float) Math.cos(rad);
            float sin = (float) Math.sin(rad);
            int idx = i * 4;
            tickPts[idx] = cx + innerR * cos;
            tickPts[idx + 1] = cy + innerR * sin;
            tickPts[idx + 2] = cx + outerR * cos;
            tickPts[idx + 3] = cy + outerR * sin;
        }
        valuePaint.setTextSize(size * 0.23f);
        badgePaint.setTextSize(Math.max(10f * density, size * 0.056f));
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float size = Math.min(w, getHeight());
        if (size <= 0) return;
        float cx = w / 2f, cy = arc.centerY(), radius = arc.width() / 2f;
        float strokeW = size * 0.056f;

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(strokeW);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(TRACK_COLOR);
        canvas.drawArc(arc, 135, 270, false, paint);
        canvas.drawLines(tickPts, tickPaint);

        int accentColor = ringColor();
        if (MeasurementRules.validSoc(level)) {
            float sweep = (float) (270.0 * Math.max(0.0, Math.min(100.0, level)) / 100.0);
            paint.setStrokeWidth(strokeW * 1.65f);
            paint.setColor(Color.argb(42, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)));
            canvas.drawArc(arc, 135, sweep, false, paint);

            paint.setStrokeWidth(strokeW);
            paint.setColor(accentColor);
            canvas.drawArc(arc, 135, sweep, false, paint);

            if (sweep > 1f) {
                double tipRad = Math.toRadians(135.0 + sweep);
                float tx = cx + radius * (float) Math.cos(tipRad);
                float ty = cy + radius * (float) Math.sin(tipRad);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(Color.rgb(244, 247, 252));
                canvas.drawCircle(tx, ty, strokeW * 0.28f, paint);
            }
        }

        String mainText = MeasurementRules.validSoc(level) ? Math.round(level) + "%" : "—";
        canvas.drawText(mainText, cx, cy + valuePaint.getTextSize() * 0.32f, valuePaint);

        String badge = badgeText();
        float pillH = Math.max(22f * density, size * 0.125f);
        float pillW = Math.min(w - 16f * density, Math.max(pillH * 2.6f, badgePaint.measureText(badge) + pillH * 1.1f));
        float pillTop = cy + radius * 0.88f;
        statusPill.set(cx - pillW / 2f, pillTop, cx + pillW / 2f, pillTop + pillH);
        float pillRadius = pillH / 2f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(PILL_FILL);
        canvas.drawRoundRect(statusPill, pillRadius, pillRadius, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, density));
        paint.setColor(plugged ? Color.argb(120, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)) : PILL_STROKE);
        canvas.drawRoundRect(statusPill, pillRadius, pillRadius, paint);

        badgePaint.setColor(plugged ? accentColor : Color.rgb(171, 191, 198));
        float textY = statusPill.centerY() - (badgePaint.descent() + badgePaint.ascent()) / 2f;
        canvas.drawText(badge, statusPill.centerX(), textY, badgePaint);
    }
}
