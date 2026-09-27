package com.darren.batterylens;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

final class LiveChartView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private List<TrendPoint> points = new ArrayList<>();
    private int metric;
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private final SimpleDateFormat dayClock = new SimpleDateFormat("MMM d", Locale.getDefault());

    LiveChartView(Context c) { super(c); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES); }

    void setData(List<TrendPoint> data, int metric) {
        this.points = data; this.metric = metric;
        String name = metric == 0 ? "Battery level" : metric == 1 ? "Current" : "Temperature";
        double latest = data.isEmpty() ? Double.NaN : value(data.get(data.size() - 1));
        setContentDescription(name + " chart, " + data.size() + " readings. Latest: "
                + (Double.isFinite(latest) ? label(latest) : "unavailable") + ". Gaps indicate missing observations.");
        invalidate();
    }

    private double value(TrendPoint p) { return metric == 0 ? (MeasurementRules.validSoc(p.soc) ? p.soc : Double.NaN) : metric == 1 ? p.currentMa : p.temperatureC; }
    private String label(double n) { return String.format(Locale.getDefault(), metric == 2 ? "%.1f°" : "%.0f", n) + (metric == 0 ? "%" : ""); }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        float left = dp(58), right = getWidth() - dp(12), top = dp(18), bottom = getHeight() - dp(32);
        paint.setTextSize(dp(11)); paint.setStrokeWidth(dp(1)); paint.setStyle(Paint.Style.FILL);
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        int valid = 0;
        for (TrendPoint p : points) if (Double.isFinite(value(p))) { min = Math.min(min, value(p)); max = Math.max(max, value(p)); valid++; }
        if (valid == 0) {
            paint.setColor(Color.rgb(171, 191, 198)); paint.setTextAlign(Paint.Align.CENTER);
            c.drawText("No readings in this time window", getWidth() / 2f, getHeight() / 2f, paint);
            return;
        }
        if (metric == 0) { min = 0; max = 100; }
        else { double pad = Math.max(metric == 1 ? 50 : 1, (max - min) * .15); min -= pad; max += pad; }
        long first = points.get(0).time, last = points.get(points.size() - 1).time;
        for (int i = 0; i <= 2; i++) {
            float y = top + (bottom - top) * i / 2;
            paint.setColor(Color.rgb(43, 65, 73)); c.drawLine(left, y, right, y, paint);
            paint.setColor(Color.rgb(171, 191, 198)); paint.setTextAlign(Paint.Align.RIGHT);
            c.drawText(label(max - (max - min) * i / 2), left - dp(7), y + dp(4), paint);
        }
        float px = 0, py = 0;
        boolean previousValid = false;
        for (int i = 0; i < points.size(); i++) {
            TrendPoint p = points.get(i);
            double v = value(p);
            if (!Double.isFinite(v)) { previousValid = false; continue; }
            float x = first == last ? (left + right) / 2 : left + (right - left) * (p.time - first) / (last - first);
            float y = bottom - (float) ((v - min) / (max - min)) * (bottom - top);
            paint.setColor(metric == 2 ? Color.rgb(255, 196, 92) : metric == 1 ? Color.rgb(103, 192, 255) : Color.rgb(109, 235, 209));
            paint.setStrokeWidth(dp(2));
            if (previousValid && p.connectsTo(points.get(i - 1))) c.drawLine(px, py, x, y, paint);
            if (points.size() < 300 || i % Math.max(1, points.size() / 200) == 0)
                c.drawCircle(x, y, dp(2), paint);
            previousValid = true; px = x; py = y;
        }
        paint.setColor(Color.rgb(171, 191, 198)); paint.setTextAlign(Paint.Align.LEFT);
        SimpleDateFormat formatter = last - first >= 24 * 3_600_000L ? dayClock : clock;
        c.drawText(formatter.format(new Date(first)), left, getHeight() - dp(8), paint);
        paint.setTextAlign(Paint.Align.RIGHT); c.drawText(formatter.format(new Date(last)), right, getHeight() - dp(8), paint);
    }

    private float dp(float n) { return n * getResources().getDisplayMetrics().density; }
}
