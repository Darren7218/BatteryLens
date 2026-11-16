package com.darren.batterylens;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.view.View;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

final class LiveChartView extends View {
    private static final int COLOR_GRID = Color.rgb(38, 60, 68);
    private static final int COLOR_TEXT = Color.rgb(167, 189, 197);
    private static final int COLOR_SOC = Color.rgb(109, 235, 209);
    private static final int COLOR_CURRENT = Color.rgb(103, 192, 255);
    private static final int COLOR_TEMP = Color.rgb(255, 196, 92);

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint areaPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path linePath = new Path();
    private final Path fillPath = new Path();
    private final Date tempDate = new Date();
    private final float density;
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private final SimpleDateFormat dayClock = new SimpleDateFormat("MMM d", Locale.getDefault());
    private List<TrendPoint> points = Collections.emptyList();
    private int metric;
    private int shaderColor;
    private float shaderBottom = -1f;

    LiveChartView(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        gridPaint.setColor(COLOR_GRID);
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(dp(1));
        gridPaint.setPathEffect(new DashPathEffect(new float[]{dp(4), dp(4)}, 0));

        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(dp(2.2f));
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);

        areaPaint.setStyle(Paint.Style.FILL);
        paint.setFontFeatureSettings("tnum");
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    void setData(List<TrendPoint> data, int metric) {
        this.points = data == null ? Collections.emptyList() : data;
        this.metric = metric;
        String name = metric == 0 ? "Battery level" : metric == 1 ? "Current" : "Temperature";
        double latest = this.points.isEmpty() ? Double.NaN : value(this.points.get(this.points.size() - 1));
        setContentDescription(name + " chart, " + this.points.size() + " readings. Latest: "
                + (Double.isFinite(latest) ? label(latest) : "unavailable") + ". Gaps indicate missing observations.");
        invalidate();
    }

    void releaseMemory() {
        points = Collections.emptyList();
        linePath.reset();
        fillPath.reset();
        areaPaint.setShader(null);
        shaderBottom = -1f;
    }

    private double value(TrendPoint p) {
        return metric == 0 ? (MeasurementRules.validSoc(p.soc) ? p.soc : Double.NaN)
                : metric == 1 ? p.currentMa : p.temperatureC;
    }

    private String label(double n) {
        return String.format(Locale.getDefault(), metric == 2 ? "%.1f°" : "%.0f", n) + (metric == 0 ? "%" : "");
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        float left = dp(58), right = getWidth() - dp(12), top = dp(18), bottom = getHeight() - dp(32);
        paint.setTextSize(dp(11));
        paint.setStrokeWidth(dp(1));
        paint.setStyle(Paint.Style.FILL);
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        int valid = 0;
        for (int i = 0; i < points.size(); i++) {
            double v = value(points.get(i));
            if (Double.isFinite(v)) {
                if (v < min) min = v;
                if (v > max) max = v;
                valid++;
            }
        }
        if (valid == 0) {
            paint.setColor(COLOR_TEXT);
            paint.setTextAlign(Paint.Align.CENTER);
            c.drawText("No readings in this time window", getWidth() / 2f, getHeight() / 2f, paint);
            return;
        }
        if (metric == 0) {
            min = 0;
            max = 100;
        } else {
            double pad = Math.max(metric == 1 ? 50 : 1, (max - min) * .15);
            min -= pad;
            max += pad;
        }
        long first = points.get(0).time, last = points.get(points.size() - 1).time;
        for (int i = 0; i <= 2; i++) {
            float y = top + (bottom - top) * i / 2;
            c.drawLine(left, y, right, y, gridPaint);
            paint.setColor(COLOR_TEXT);
            paint.setTextAlign(Paint.Align.RIGHT);
            c.drawText(label(max - (max - min) * i / 2), left - dp(7), y + dp(4), paint);
        }

        float baselineY = (metric == 1 && min < 0 && max > 0)
                ? bottom - (float) ((0.0 - min) / (max - min)) * (bottom - top)
                : bottom;
        if (metric == 1 && min < 0 && max > 0) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(Color.argb(95, 167, 189, 197));
            c.drawLine(left, baselineY, right, baselineY, paint);
            paint.setStyle(Paint.Style.FILL);
        }

        int seriesColor = metric == 2 ? COLOR_TEMP : metric == 1 ? COLOR_CURRENT : COLOR_SOC;
        if (shaderColor != seriesColor || shaderBottom != bottom) {
            shaderColor = seriesColor;
            shaderBottom = bottom;
            int topAlpha = Color.argb(66, Color.red(seriesColor), Color.green(seriesColor), Color.blue(seriesColor));
            areaPaint.setShader(new LinearGradient(0, top, 0, bottom, topAlpha, Color.TRANSPARENT, Shader.TileMode.CLAMP));
        }
        linePaint.setColor(seriesColor);
        paint.setColor(seriesColor);
        float dotRadius = dp(2.1f);
        int n = points.size();
        int step = n < 300 ? 1 : Math.max(1, n / 200);
        linePath.reset();
        fillPath.reset();
        boolean previousValid = false;
        boolean segOpen = false;
        float segStartX = 0, lastValidX = 0, lastValidY = 0;
        for (int i = 0; i < n; i++) {
            TrendPoint p = points.get(i);
            double v = value(p);
            if (!Double.isFinite(v)) {
                if (segOpen) {
                    fillPath.lineTo(lastValidX, baselineY);
                    fillPath.lineTo(segStartX, baselineY);
                    fillPath.close();
                    segOpen = false;
                }
                previousValid = false;
                continue;
            }
            float x = first == last ? (left + right) / 2 : left + (right - left) * (p.time - first) / (last - first);
            float y = bottom - (float) ((v - min) / (max - min)) * (bottom - top);
            boolean connected = previousValid && p.connectsTo(points.get(i - 1));
            if (connected) {
                linePath.lineTo(x, y);
                fillPath.lineTo(x, y);
            } else {
                if (segOpen) {
                    fillPath.lineTo(lastValidX, baselineY);
                    fillPath.lineTo(segStartX, baselineY);
                    fillPath.close();
                }
                linePath.moveTo(x, y);
                fillPath.moveTo(x, y);
                segStartX = x;
                segOpen = true;
            }
            if (step == 1 || i % step == 0 || !connected) c.drawCircle(x, y, dotRadius, paint);
            previousValid = true;
            lastValidX = x;
            lastValidY = y;
        }
        if (segOpen) {
            fillPath.lineTo(lastValidX, baselineY);
            fillPath.lineTo(segStartX, baselineY);
            fillPath.close();
        }
        c.drawPath(fillPath, areaPaint);
        c.drawPath(linePath, linePaint);
        if (valid > 0) {
            paint.setColor(Color.argb(72, Color.red(seriesColor), Color.green(seriesColor), Color.blue(seriesColor)));
            c.drawCircle(lastValidX, lastValidY, dp(5.5f), paint);
            paint.setColor(seriesColor);
            c.drawCircle(lastValidX, lastValidY, dp(3f), paint);
        }

        paint.setColor(COLOR_TEXT);
        paint.setTextAlign(Paint.Align.LEFT);
        SimpleDateFormat formatter = last - first >= 24 * 3_600_000L ? dayClock : clock;
        tempDate.setTime(first);
        c.drawText(formatter.format(tempDate), left, getHeight() - dp(8), paint);
        paint.setTextAlign(Paint.Align.RIGHT);
        tempDate.setTime(last);
        c.drawText(formatter.format(tempDate), right, getHeight() - dp(8), paint);
    }

    private float dp(float n) { return n * density; }
}
