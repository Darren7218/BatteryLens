package com.darren.batterylens;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

final class HealthChartView extends View {
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint point = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<Double> health = new ArrayList<>();
    private List<CalibrationStore.Session> lastSessions;
    private float lastDesign = Float.NaN;
    private int lastDays = -1;

    HealthChartView(Context context) {
        super(context);
        grid.setColor(Color.rgb(64, 70, 82));
        grid.setStrokeWidth(dp(1));
        line.setColor(Color.rgb(109, 235, 209));
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(dp(2.5f));
        point.setColor(Color.rgb(109, 235, 209));
        label.setColor(Color.rgb(170, 178, 191));
        label.setTextSize(dp(11));
        setMinimumHeight(dp(170));
    }

    void setData(List<CalibrationStore.Session> sessions, float designMah) {
        setData(sessions, designMah, 0);
    }

    void setData(List<CalibrationStore.Session> sessions, float designMah, int days) {
        if (lastSessions == sessions && lastDesign == designMah && lastDays == days) return;
        lastSessions = sessions; lastDesign = designMah; lastDays = days;
        health.clear();
        long cutoff = days <= 0 ? Long.MIN_VALUE : System.currentTimeMillis() - days * 86_400_000L;
        if (designMah > 0 && sessions != null) {
            for (int i = 0; i < sessions.size(); i++) {
                CalibrationStore.Session s = sessions.get(i);
                if (s.used && s.fccMah > 0 && s.endTime >= cutoff)
                    health.add(100.0 * s.fccMah / designMah);
            }
        }
        while (health.size() > 12) health.remove(0);
        setContentDescription(health.isEmpty() ? "No eligible sessions to chart" : health.size() + " eligible session estimates. Values are listed in recent sessions.");
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        float w = getWidth();
        float h = getHeight();
        float left = dp(34), right = dp(12), top = dp(14), bottom = dp(26);
        float plotW = Math.max(1, w - left - right);
        float plotH = Math.max(1, h - top - bottom);

        if (health.isEmpty()) {
            label.setTextAlign(Paint.Align.CENTER);
            c.drawText("Complete a monitored charge to begin", w / 2f, h / 2f, label);
            label.setTextAlign(Paint.Align.LEFT);
            return;
        }

        double min = 75, max = 105;
        for (double v : health) {
            min = Math.min(min, v - 4);
            max = Math.max(max, v + 4);
        }
        min = Math.floor(min / 5.0) * 5.0;
        max = Math.ceil(max / 5.0) * 5.0;
        if (max - min < 20) max = min + 20;

        double[] guides = {80, 90, 100};
        for (double g : guides) {
            if (g < min || g > max) continue;
            float y = yFor(g, min, max, top, plotH);
            c.drawLine(left, y, w - right, y, grid);
            label.setTextAlign(Paint.Align.RIGHT);
            c.drawText(Math.round(g) + "%", left - dp(5), y + dp(4), label);
        }

        Path p = new Path();
        for (int i = 0; i < health.size(); i++) {
            float x = health.size() == 1 ? left + plotW / 2f : left + plotW * i / (health.size() - 1f);
            float y = yFor(health.get(i), min, max, top, plotH);
            if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
            c.drawCircle(x, y, dp(3.5f), point);
        }
        c.drawPath(p, line);
        label.setTextAlign(Paint.Align.LEFT);
        c.drawText("older", left, h - dp(5), label);
        label.setTextAlign(Paint.Align.RIGHT);
        c.drawText("newer", w - right, h - dp(5), label);
        label.setTextAlign(Paint.Align.LEFT);
    }

    private float yFor(double value, double min, double max, float top, float plotH) {
        double clamped = Math.max(min, Math.min(max, value));
        return top + (float) ((max - clamped) / (max - min)) * plotH;
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
