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

import java.util.ArrayList;
import java.util.List;

final class HealthChartView extends View {
    private static final int MAX_POINTS = 12;
    private static final double[] GUIDES = {80, 90, 100};
    private static final String[] GUIDE_LABELS = {"80%", "90%", "100%"};
    private static final int ACCENT = Color.rgb(109, 235, 209);

    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint area = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint point = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint centerDot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path linePath = new Path();
    private final Path fillPath = new Path();
    private final float density;
    private final List<Double> health = new ArrayList<>(MAX_POINTS);
    private List<CalibrationStore.Session> lastSessions;
    private float lastDesign = Float.NaN;
    private int lastDays = -1;
    private float cachedPlotBottom = -1f;

    HealthChartView(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        grid.setColor(Color.rgb(46, 68, 78));
        grid.setStyle(Paint.Style.STROKE);
        grid.setStrokeWidth(dp(1));
        grid.setPathEffect(new DashPathEffect(new float[]{dp(4), dp(4)}, 0));
        line.setColor(ACCENT);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(dp(2.5f));
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        area.setStyle(Paint.Style.FILL);
        point.setColor(ACCENT);
        centerDot.setColor(Color.rgb(18, 35, 43));
        label.setColor(Color.rgb(167, 189, 197));
        label.setTextSize(dp(11));
        label.setFontFeatureSettings("tnum");
        setMinimumHeight((int) dp(170));
    }

    void setData(List<CalibrationStore.Session> sessions, float designMah) {
        setData(sessions, designMah, 0);
    }

    void setData(List<CalibrationStore.Session> sessions, float designMah, int days) {
        if (lastSessions == sessions && lastDesign == designMah && lastDays == days) return;
        lastSessions = sessions;
        lastDesign = designMah;
        lastDays = days;
        health.clear();
        long cutoff = days <= 0 ? Long.MIN_VALUE : System.currentTimeMillis() - days * 86_400_000L;
        if (designMah > 0 && sessions != null) {
            for (int i = sessions.size() - 1; i >= 0 && health.size() < MAX_POINTS; i--) {
                CalibrationStore.Session s = sessions.get(i);
                if (s.used && s.fccMah > 0 && s.endTime >= cutoff) {
                    health.add(100.0 * s.fccMah / designMah);
                }
            }
            // Reverse in-place so chart plots oldest -> newest left-to-right.
            for (int l = 0, r = health.size() - 1; l < r; l++, r--) {
                Double tmp = health.get(l);
                health.set(l, health.get(r));
                health.set(r, tmp);
            }
        }
        setContentDescription(health.isEmpty()
                ? "No eligible sessions to chart"
                : health.size() + " eligible session estimates. Values are listed in recent sessions.");
        invalidate();
    }

    void releaseMemory() {
        lastSessions = null;
        health.clear();
        linePath.reset();
        fillPath.reset();
        area.setShader(null);
        cachedPlotBottom = -1f;
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
        for (int i = 0; i < health.size(); i++) {
            double v = health.get(i);
            min = Math.min(min, v - 4);
            max = Math.max(max, v + 4);
        }
        min = Math.floor(min / 5.0) * 5.0;
        max = Math.ceil(max / 5.0) * 5.0;
        if (max - min < 20) max = min + 20;

        float bottomEdge = h - bottom;
        if (cachedPlotBottom != bottomEdge) {
            cachedPlotBottom = bottomEdge;
            area.setShader(new LinearGradient(0, top, 0, bottomEdge,
                    Color.argb(58, 109, 235, 209), Color.TRANSPARENT, Shader.TileMode.CLAMP));
        }

        for (int i = 0; i < GUIDES.length; i++) {
            double g = GUIDES[i];
            if (g < min || g > max) continue;
            float y = yFor(g, min, max, top, plotH);
            c.drawLine(left, y, w - right, y, grid);
            label.setTextAlign(Paint.Align.RIGHT);
            c.drawText(GUIDE_LABELS[i], left - dp(5), y + dp(4), label);
        }

        linePath.reset();
        fillPath.reset();
        float firstX = 0, lastX = 0, lastY = 0;
        for (int i = 0; i < health.size(); i++) {
            float x = health.size() == 1 ? left + plotW / 2f : left + plotW * i / (health.size() - 1f);
            float y = yFor(health.get(i), min, max, top, plotH);
            if (i == 0) {
                firstX = x;
                linePath.moveTo(x, y);
                fillPath.moveTo(x, y);
            } else {
                linePath.lineTo(x, y);
                fillPath.lineTo(x, y);
            }
            lastX = x;
            lastY = y;
        }
        if (health.size() >= 2) {
            fillPath.lineTo(lastX, bottomEdge);
            fillPath.lineTo(firstX, bottomEdge);
            fillPath.close();
            c.drawPath(fillPath, area);
            c.drawPath(linePath, line);
        }
        float radius = dp(4f);
        float innerRadius = dp(2f);
        for (int i = 0; i < health.size(); i++) {
            float x = health.size() == 1 ? left + plotW / 2f : left + plotW * i / (health.size() - 1f);
            float y = yFor(health.get(i), min, max, top, plotH);
            if (i == health.size() - 1) {
                point.setColor(Color.argb(64, 109, 235, 209));
                c.drawCircle(x, y, dp(7.5f), point);
                point.setColor(ACCENT);
                c.drawCircle(x, y, radius, point);
            } else {
                point.setColor(ACCENT);
                c.drawCircle(x, y, radius, point);
                c.drawCircle(x, y, innerRadius, centerDot);
            }
        }
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

    private float dp(float v) {
        return v * density;
    }
}
